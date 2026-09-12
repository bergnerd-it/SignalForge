package com.bergnerd.signalforge.app.chat;

import com.bergnerd.signalforge.app.accounting.AccountingCore;
import com.bergnerd.signalforge.app.operation.IdempotencyExceptions;
import com.bergnerd.signalforge.app.portfolio.PortfolioResponse;
import com.bergnerd.signalforge.app.portfolio.PortfolioService;
import com.bergnerd.signalforge.app.portfolio.PositionDto;
import com.bergnerd.signalforge.app.portfolio.TradeRequest;
import com.bergnerd.signalforge.app.portfolio.TradeResponse;
import com.bergnerd.signalforge.app.watchlist.WatchlistEntryDto;
import com.bergnerd.signalforge.app.watchlist.WatchlistService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private static final Pattern TICKER_PATTERN = Pattern.compile("^[A-Z]{1,5}$");
    private static final String ACTION_ERROR = "Action could not be completed";

    private final JdbcTemplate jdbcTemplate;
    private final PortfolioService portfolioService;
    private final WatchlistService watchlistService;
    private final LlmClient llmClient;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile Consumer<String> failureInjector = ignored -> {};

    public List<ChatMessageRecord> getChatHistory(String userId) {
        return jdbcTemplate.query(
                "SELECT id, role, content, actions, created_at FROM chat_messages WHERE user_id = ? ORDER BY created_at ASC",
                (rs, rowNum) -> new ChatMessageRecord(
                        rs.getString("id"), rs.getString("role"), rs.getString("content"),
                        rs.getString("actions"), rs.getString("created_at")
                ),
                normalizeUser(userId)
        );
    }

    public void clearChatHistory(String userId) {
        jdbcTemplate.update("DELETE FROM chat_messages WHERE user_id = ?", normalizeUser(userId));
    }

    public ChatResponse processUserMessage(String userId, String userMessage) {
        throw new IdempotencyExceptions.MissingIdempotencyKeyException(
                "Idempotency key is required for chat requests"
        );
    }

    public ChatResponse processUserMessage(String userId, String userMessage, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IdempotencyExceptions.MissingIdempotencyKeyException(
                    "Idempotency key is required for chat requests"
            );
        }
        String uid = normalizeUser(userId);
        String message = userMessage == null ? "" : userMessage.trim();
        String key = idempotencyKey.trim();
        String payloadHash = sha256(uid + "\n" + message);

        RequestState request = findRequest(uid, key);
        if (request != null) {
            assertMatchingIntent(request, payloadHash);
            if ("COMPLETED".equals(request.status())) {
                return deserialize(request.responseJson(), ChatResponse.class);
            }
            if ("PLAN_READY".equals(request.status()) || "EXECUTING".equals(request.status())) {
                return executeStoredPlan(request);
            }
            if (leaseIsActive(request)) {
                throw new ChatRequestInProgressException("Chat request is already in progress");
            }
            if (!claimExpiredInference(request.id())) {
                throw new ChatRequestInProgressException("Chat request is already in progress");
            }
        } else {
            request = createRequest(uid, key, payloadHash, message);
            if (request == null) {
                return processUserMessage(uid, message, key);
            }
        }

        PortfolioResponse portfolio = portfolioService.getPortfolio(uid);
        List<WatchlistEntryDto> watchlist = watchlistService.getWatchlist(uid);
        List<ChatMessageRecord> history = getChatHistory(uid);
        List<ChatMessageRecord> recentHistory = history.size() > 10
                ? history.subList(history.size() - 10, history.size())
                : history;

        LlmStructuredResponse modelResponse;
        try {
            modelResponse = llmClient.generateResponse(buildSystemPrompt(portfolio, watchlist), recentHistory, message);
        } catch (Exception exception) {
            log.warn("LLM inference failed for chat request {}", request.id());
            return completeWithoutActions(request, "I encountered an error processing your request. Please try again.");
        }

        List<PlannedAction> plan;
        try {
            plan = validateCompletePlan(modelResponse);
        } catch (IllegalArgumentException exception) {
            return completeWithoutActions(request, "The requested actions were rejected by server validation.");
        }
        persistPlan(request, modelResponse.message(), plan);
        return executeStoredPlan(findRequest(uid, key));
    }

    private RequestState createRequest(String uid, String key, String payloadHash, String message) {
        String requestId = "chat-request-" + UUID.randomUUID();
        String now = Instant.now().toString();
        try {
            transactionTemplate.executeWithoutResult(status -> {
                jdbcTemplate.update(
                        "INSERT INTO chat_requests (id, user_id, idempotency_key, payload_hash, user_message, status, lease_owner, lease_until, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, ?, 'INFERENCE_PENDING', ?, ?, ?, ?)",
                        requestId, uid, key, payloadHash, message, UUID.randomUUID().toString(),
                        Instant.now().plus(30, ChronoUnit.SECONDS).toString(), now, now
                );
                jdbcTemplate.update(
                        "INSERT OR IGNORE INTO chat_messages (id, user_id, role, content, actions, created_at) VALUES (?, ?, 'user', ?, NULL, ?)",
                        "chat-user-" + requestId, uid, message, now
                );
            });
            return findRequest(uid, key);
        } catch (DuplicateKeyException exception) {
            return null;
        }
    }

    private boolean claimExpiredInference(String requestId) {
        String now = Instant.now().toString();
        return jdbcTemplate.update(
                "UPDATE chat_requests SET lease_owner = ?, lease_until = ?, updated_at = ? "
                        + "WHERE id = ? AND status = 'INFERENCE_PENDING' AND (lease_until IS NULL OR lease_until <= ?)",
                UUID.randomUUID().toString(), Instant.now().plus(30, ChronoUnit.SECONDS).toString(),
                now, requestId, now
        ) == 1;
    }

    private void persistPlan(RequestState request, String assistantMessage, List<PlannedAction> plan) {
        transactionTemplate.executeWithoutResult(status -> {
            String now = Instant.now().toString();
            for (int index = 0; index < plan.size(); index++) {
                PlannedAction action = plan.get(index);
                jdbcTemplate.update(
                        "INSERT INTO chat_actions (id, chat_message_id, operation_id, action_type, action_payload, status, created_at, "
                                + "chat_request_id, action_index, action_key, updated_at) "
                                + "VALUES (?, ?, NULL, ?, ?, 'PENDING', ?, ?, ?, ?, ?)",
                        "chat-action-" + request.id() + "-" + index, "chat-user-" + request.id(),
                        action.type(), serialize(action), now, request.id(), index,
                        request.idempotencyKey() + "-action-" + index, now
                );
            }
            jdbcTemplate.update(
                    "UPDATE chat_requests SET assistant_message = ?, status = 'PLAN_READY', lease_owner = NULL, lease_until = NULL, updated_at = ? WHERE id = ?",
                    assistantMessage == null ? "" : assistantMessage, now, request.id()
            );
        });
    }

    private ChatResponse executeStoredPlan(RequestState request) {
        String now = Instant.now().toString();
        int claimed = jdbcTemplate.update(
                "UPDATE chat_requests SET status = 'EXECUTING', lease_owner = ?, lease_until = ?, updated_at = ? "
                        + "WHERE id = ? AND (status = 'PLAN_READY' OR (status = 'EXECUTING' AND (lease_until IS NULL OR lease_until <= ?)))",
                UUID.randomUUID().toString(), Instant.now().plus(30, ChronoUnit.SECONDS).toString(), now, request.id(), now
        );
        if (claimed == 0) {
            RequestState current = findRequest(request.userId(), request.idempotencyKey());
            if (current != null && "COMPLETED".equals(current.status())) {
                return deserialize(current.responseJson(), ChatResponse.class);
            }
            throw new ChatRequestInProgressException("Chat request actions are already in progress");
        }
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT id, action_payload, action_key, status FROM chat_actions "
                            + "WHERE chat_request_id = ? ORDER BY action_index", request.id()
            );
            for (Map<String, Object> row : rows) {
                if (!"SUCCESS".equals(row.get("status")) && !"FAILED".equals(row.get("status"))) {
                    executeAction(request, row);
                }
            }
            failureInjector.accept("assistant-message");
            return completeFromStoredActions(request);
        } catch (InjectedChatFailure exception) {
            jdbcTemplate.update(
                    "UPDATE chat_requests SET status = 'PLAN_READY', lease_owner = NULL, lease_until = NULL, updated_at = ? WHERE id = ?",
                    Instant.now().toString(), request.id()
            );
            throw exception;
        }
    }

    private void executeAction(RequestState request, Map<String, Object> row) {
        PlannedAction action = deserialize((String) row.get("action_payload"), PlannedAction.class);
        String actionId = (String) row.get("id");
        String actionKey = (String) row.get("action_key");
        try {
            ChatActionExecution result;
            String operationId = null;
            if ("TRADE".equals(action.type())) {
                portfolioService.executeTrade(
                        request.userId(),
                        new TradeRequest(action.ticker(), new BigDecimal(action.quantity()).doubleValue(), action.verb(), actionKey),
                        actionKey
                );
                operationId = jdbcTemplate.queryForObject(
                        "SELECT id FROM operations WHERE portfolio_id = ? AND kind = 'TRADE' AND idempotency_key = ?",
                        String.class, "portfolio-legacy-demo-" + request.userId(), actionKey
                );
                result = new ChatActionExecution(
                        "trade", action.ticker(), action.verb().toUpperCase() + " " + action.quantity()
                                + " shares of " + action.ticker(), true, null
                );
                failureInjector.accept("after-trade");
            } else if ("WATCHLIST_ADD".equals(action.type())) {
                watchlistService.addTicker(request.userId(), action.ticker());
                result = new ChatActionExecution("watchlist", action.ticker(), "Added to watchlist", true, null);
                failureInjector.accept("after-watchlist");
            } else {
                watchlistService.removeTicker(request.userId(), action.ticker());
                result = new ChatActionExecution("watchlist", action.ticker(), "Removed from watchlist", true, null);
                failureInjector.accept("after-watchlist");
            }
            jdbcTemplate.update(
                    "UPDATE chat_actions SET status = 'SUCCESS', operation_id = ?, result_json = ?, updated_at = ? WHERE id = ?",
                    operationId, serialize(result), Instant.now().toString(), actionId
            );
        } catch (InjectedChatFailure exception) {
            throw exception;
        } catch (Exception exception) {
            log.warn("Chat action {} failed: {}", actionId, exception.getClass().getSimpleName());
            ChatActionExecution result = new ChatActionExecution(
                    action.type().startsWith("WATCHLIST") ? "watchlist" : "trade",
                    action.ticker(), action.verb(), false, ACTION_ERROR
            );
            jdbcTemplate.update(
                    "UPDATE chat_actions SET status = 'FAILED', result_json = ?, error_code = 'ACTION_FAILED', updated_at = ? WHERE id = ?",
                    serialize(result), Instant.now().toString(), actionId
            );
        }
    }

    private ChatResponse completeFromStoredActions(RequestState request) {
        List<ChatActionExecution> results = jdbcTemplate.query(
                "SELECT result_json FROM chat_actions WHERE chat_request_id = ? ORDER BY action_index",
                (rs, rowNum) -> deserialize(rs.getString(1), ChatActionExecution.class), request.id()
        );
        String completedAt = Instant.now().toString();
        ChatResponse response = new ChatResponse(request.assistantMessage(), results, completedAt);
        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update(
                    "INSERT OR IGNORE INTO chat_messages (id, user_id, role, content, actions, created_at) VALUES (?, ?, 'assistant', ?, ?, ?)",
                    "chat-assistant-" + request.id(), request.userId(), request.assistantMessage(),
                    results.isEmpty() ? null : serialize(results), completedAt
            );
            jdbcTemplate.update(
                    "UPDATE chat_requests SET status = 'COMPLETED', response_json = ?, lease_owner = NULL, lease_until = NULL, updated_at = ? WHERE id = ?",
                    serialize(response), completedAt, request.id()
            );
        });
        return response;
    }

    private ChatResponse completeWithoutActions(RequestState request, String message) {
        jdbcTemplate.update(
                "UPDATE chat_requests SET assistant_message = ?, status = 'PLAN_READY', lease_owner = NULL, lease_until = NULL, updated_at = ? WHERE id = ?",
                message, Instant.now().toString(), request.id()
        );
        return completeFromStoredActions(new RequestState(
                request.id(), request.userId(), request.idempotencyKey(), request.payloadHash(),
                "PLAN_READY", message, null, null
        ));
    }

    private List<PlannedAction> validateCompletePlan(LlmStructuredResponse response) {
        if (response == null || response.message() == null) {
            throw new IllegalArgumentException("Model response is missing a message");
        }
        List<PlannedAction> plan = new ArrayList<>();
        for (TradeInstruction trade : response.safeTrades()) {
            if (trade == null || trade.ticker() == null || trade.side() == null) {
                throw new IllegalArgumentException("Malformed trade action");
            }
            String ticker = normalizeTicker(trade.ticker());
            String side = trade.side().trim().toLowerCase();
            if (!"buy".equals(side) && !"sell".equals(side)) {
                throw new IllegalArgumentException("Invalid trade side");
            }
            BigDecimal quantity = AccountingCore.normalizeQuantity(BigDecimal.valueOf(trade.quantity()));
            if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Invalid trade quantity");
            }
            plan.add(new PlannedAction("TRADE", ticker, side, quantity.toPlainString()));
        }
        for (WatchlistChange change : response.safeWatchlistChanges()) {
            if (change == null || change.ticker() == null || change.action() == null) {
                throw new IllegalArgumentException("Malformed watchlist action");
            }
            String ticker = normalizeTicker(change.ticker());
            String verb = change.action().trim().toLowerCase();
            if (!"add".equals(verb) && !"remove".equals(verb)) {
                throw new IllegalArgumentException("Invalid watchlist action");
            }
            plan.add(new PlannedAction(
                    "add".equals(verb) ? "WATCHLIST_ADD" : "WATCHLIST_REMOVE", ticker, verb, null
            ));
        }
        return plan;
    }

    private RequestState findRequest(String uid, String key) {
        List<RequestState> rows = jdbcTemplate.query(
                "SELECT id, user_id, idempotency_key, payload_hash, status, assistant_message, response_json, lease_until "
                        + "FROM chat_requests WHERE user_id = ? AND idempotency_key = ?",
                (rs, rowNum) -> new RequestState(
                        rs.getString("id"), rs.getString("user_id"), rs.getString("idempotency_key"),
                        rs.getString("payload_hash"), rs.getString("status"), rs.getString("assistant_message"),
                        rs.getString("response_json"), rs.getString("lease_until")
                ), uid, key
        );
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void assertMatchingIntent(RequestState request, String payloadHash) {
        if (!request.payloadHash().equals(payloadHash)) {
            throw new IdempotencyExceptions.IdempotencyConflictException(
                    "Conflicting reuse of chat idempotency key: " + request.idempotencyKey()
            );
        }
    }

    private boolean leaseIsActive(RequestState request) {
        return request.leaseUntil() != null && Instant.parse(request.leaseUntil()).isAfter(Instant.now());
    }

    private String normalizeTicker(String value) {
        String ticker = value.trim().toUpperCase();
        if (!TICKER_PATTERN.matcher(ticker).matches()) {
            throw new IllegalArgumentException("Invalid ticker");
        }
        return ticker;
    }

    private String normalizeUser(String userId) {
        return userId == null || userId.isBlank() ? "default" : userId;
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize durable chat state", exception);
        }
    }

    private <T> T deserialize(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to read durable chat state", exception);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    void setFailureInjector(Consumer<String> failureInjector) {
        this.failureInjector = failureInjector == null ? ignored -> {} : failureInjector;
    }

    private String buildSystemPrompt(PortfolioResponse portfolio, List<WatchlistEntryDto> watchlist) {
        StringBuilder prompt = new StringBuilder("You are SignalForge, an AI trading copilot for a synthetic demo.\n");
        prompt.append("Cash balance: ").append(portfolio.cashBalance()).append("\nPositions:\n");
        for (PositionDto position : portfolio.positions()) {
            prompt.append(position.ticker()).append(": ").append(position.quantity()).append(" units\n");
        }
        prompt.append("Watchlist:\n");
        for (WatchlistEntryDto item : watchlist) {
            prompt.append(item.ticker()).append("\n");
        }
        prompt.append("Return only the configured structured response. Trades use buy/sell and positive quantities. ")
                .append("Watchlist changes use add/remove.");
        return prompt.toString();
    }

    private record RequestState(
            String id,
            String userId,
            String idempotencyKey,
            String payloadHash,
            String status,
            String assistantMessage,
            String responseJson,
            String leaseUntil
    ) {}

    private record PlannedAction(String type, String ticker, String verb, String quantity) {}

    @ResponseStatus(HttpStatus.CONFLICT)
    public static class ChatRequestInProgressException extends RuntimeException {
        public ChatRequestInProgressException(String message) {
            super(message);
        }
    }

    static class InjectedChatFailure extends RuntimeException {
        InjectedChatFailure(String message) {
            super(message);
        }
    }
}

package com.bergnerd.signalforge.app.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.bergnerd.signalforge.app.portfolio.PortfolioResponse;
import com.bergnerd.signalforge.app.portfolio.PortfolioService;
import com.bergnerd.signalforge.app.portfolio.PositionDto;
import com.bergnerd.signalforge.app.portfolio.TradeRequest;
import com.bergnerd.signalforge.app.watchlist.WatchlistEntryDto;
import com.bergnerd.signalforge.app.watchlist.WatchlistService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final JdbcTemplate jdbcTemplate;
    private final PortfolioService portfolioService;
    private final WatchlistService watchlistService;
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<ChatMessageRecord> getChatHistory(String userId) {
        String uid = (userId == null || userId.isBlank()) ? "default" : userId;
        return jdbcTemplate.query(
                "SELECT id, role, content, actions, created_at FROM chat_messages WHERE user_id = ? ORDER BY created_at ASC",
                (rs, rowNum) -> new ChatMessageRecord(
                        rs.getString("id"),
                        rs.getString("role"),
                        rs.getString("content"),
                        rs.getString("actions"),
                        rs.getString("created_at")
                ),
                uid
        );
    }

    public void clearChatHistory(String userId) {
        String uid = (userId == null || userId.isBlank()) ? "default" : userId;
        jdbcTemplate.update("DELETE FROM chat_messages WHERE user_id = ?", uid);
    }

    public ChatResponse processUserMessage(String userId, String userMessage) {
        String uid = (userId == null || userId.isBlank()) ? "default" : userId;
        String now = Instant.now().toString();

        // 1. Fetch current portfolio and watchlist context
        PortfolioResponse portfolio = portfolioService.getPortfolio(uid);
        List<WatchlistEntryDto> watchlist = watchlistService.getWatchlist(uid);

        // 2. Fetch recent conversation history
        List<ChatMessageRecord> fullHistory = getChatHistory(uid);
        List<ChatMessageRecord> recentHistory = fullHistory.size() > 10
                ? fullHistory.subList(fullHistory.size() - 10, fullHistory.size())
                : fullHistory;

        // 3. Assemble system prompt
        String systemPrompt = buildSystemPrompt(portfolio, watchlist);

        // 4. Record user message
        jdbcTemplate.update(
                "INSERT INTO chat_messages (id, user_id, role, content, actions, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), uid, "user", userMessage, null, now
        );

        // 5. Call LLM
        LlmStructuredResponse llmResponse = llmClient.generateResponse(systemPrompt, recentHistory, userMessage);

        // 6. Auto-execute trades and watchlist changes
        List<ChatActionExecution> actionExecutions = new ArrayList<>();

        for (TradeInstruction trade : llmResponse.safeTrades()) {
            try {
                portfolioService.executeTrade(uid, new TradeRequest(trade.ticker(), trade.quantity(), trade.side()));
                actionExecutions.add(new ChatActionExecution(
                        "trade",
                        trade.ticker().toUpperCase(),
                        String.format("%s %.2f shares of %s", trade.side().toUpperCase(), trade.quantity(), trade.ticker().toUpperCase()),
                        true,
                        null
                ));
            } catch (Exception e) {
                log.warn("Auto-execution of trade failed: {}", e.getMessage());
                actionExecutions.add(new ChatActionExecution(
                        "trade",
                        trade.ticker().toUpperCase(),
                        String.format("%s %.2f shares of %s", trade.side().toUpperCase(), trade.quantity(), trade.ticker().toUpperCase()),
                        false,
                        e.getMessage()
                ));
            }
        }

        for (WatchlistChange change : llmResponse.safeWatchlistChanges()) {
            try {
                String action = change.action().toLowerCase();
                String ticker = change.ticker().toUpperCase();
                if ("add".equals(action)) {
                    watchlistService.addTicker(uid, ticker);
                    actionExecutions.add(new ChatActionExecution(
                            "watchlist",
                            ticker,
                            "Added " + ticker + " to watchlist",
                            true,
                            null
                    ));
                } else if ("remove".equals(action)) {
                    watchlistService.removeTicker(uid, ticker);
                    actionExecutions.add(new ChatActionExecution(
                            "watchlist",
                            ticker,
                            "Removed " + ticker + " from watchlist",
                            true,
                            null
                    ));
                }
            } catch (Exception e) {
                log.warn("Auto-execution of watchlist change failed: {}", e.getMessage());
                actionExecutions.add(new ChatActionExecution(
                        "watchlist",
                        change.ticker().toUpperCase(),
                        "Watchlist update " + change.action(),
                        false,
                        e.getMessage()
                ));
            }
        }

        // 7. Store assistant message with actions JSON
        String assistantNow = Instant.now().toString();
        String actionsJson = null;
        try {
            if (!actionExecutions.isEmpty()) {
                actionsJson = objectMapper.writeValueAsString(actionExecutions);
            }
        } catch (Exception e) {
            log.error("Failed to serialize chat actions to JSON: {}", e.getMessage());
        }

        jdbcTemplate.update(
                "INSERT INTO chat_messages (id, user_id, role, content, actions, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), uid, "assistant", llmResponse.message(), actionsJson, assistantNow
        );

        return new ChatResponse(llmResponse.message(), actionExecutions, assistantNow);
    }

    private String buildSystemPrompt(PortfolioResponse portfolio, List<WatchlistEntryDto> watchlist) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are SignalForge, an expert AI trading copilot in a simulated trading terminal workstation.\n");
        sb.append("You have the power to execute market orders and modify the user's watchlist autonomously.\n\n");

        sb.append("PORTFOLIO STATUS:\n");
        sb.append(String.format("- Cash Balance: $%.2f\n", portfolio.cashBalance()));
        sb.append(String.format("- Total Positions Value: $%.2f\n", portfolio.totalPositionValue()));
        sb.append(String.format("- Total Portfolio Value: $%.2f\n", portfolio.totalPortfolioValue()));
        sb.append(String.format("- Unrealized P&L: $%.2f (%.2f%%)\n", portfolio.unrealizedPnl(), portfolio.unrealizedPnlPercent()));

        sb.append("CURRENT POSITIONS:\n");
        if (portfolio.positions().isEmpty()) {
            sb.append("  (No positions held)\n");
        } else {
            for (PositionDto pos : portfolio.positions()) {
                sb.append(String.format("  - %s: %.2f shares @ avg $%.2f (current: $%.2f, value: $%.2f, P&L: $%.2f / %.2f%%)\n",
                        pos.ticker(), pos.quantity(), pos.avgCost(), pos.currentPrice(), pos.totalValue(), pos.unrealizedPnl(), pos.unrealizedPnlPercent()));
            }
        }

        sb.append("WATCHLIST & LIVE PRICES:\n");
        for (WatchlistEntryDto item : watchlist) {
            sb.append(String.format("  - %s: $%.2f (%+.2f%%)\n", item.ticker(), item.price(), item.changePercent()));
        }

        sb.append("\nGUIDELINES:\n");
        sb.append("1. Answer user questions directly, concisely, and with concrete numbers.\n");
        sb.append("2. When requested to buy/sell or when proposing executed trades, fill the 'trades' array with objects: {\"ticker\": \"...\", \"side\": \"buy\"|\"sell\", \"quantity\": ...}.\n");
        sb.append("3. When requested to add/remove tickers from the watchlist, fill the 'watchlist_changes' array: {\"ticker\": \"...\", \"action\": \"add\"|\"remove\"}.\n");
        sb.append("4. All trades execute instantly at current simulated market prices.\n");
        sb.append("5. Respond with clean JSON matching the required schema.\n");

        return sb.toString();
    }
}

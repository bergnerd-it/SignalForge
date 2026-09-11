package com.bergnerd.signalforge.app.chat;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class MockLlmClient implements LlmClient {

    private static final Pattern BUY_PATTERN = Pattern.compile("(?i)\\bbuy\\s+(\\d+(?:\\.\\d+)?)\\s*(?:shares?\\s+of\\s+)?([a-zA-Z]{1,5})\\b");
    private static final Pattern SELL_PATTERN = Pattern.compile("(?i)\\bsell\\s+(\\d+(?:\\.\\d+)?)\\s*(?:shares?\\s+of\\s+)?([a-zA-Z]{1,5})\\b");
    private static final Pattern ADD_WATCH_PATTERN = Pattern.compile("(?i)\\badd\\s+([a-zA-Z]{1,5})\\s*(?:to\\s+watchlist)?\\b");
    private static final Pattern REMOVE_WATCH_PATTERN = Pattern.compile("(?i)\\bremove\\s+([a-zA-Z]{1,5})\\s*(?:from\\s+watchlist)?\\b");

    @Override
    public LlmStructuredResponse generateResponse(String systemPrompt, List<ChatMessageRecord> history, String userMessage) {
        log.info("Mock LLM processing prompt: {}", userMessage);
        List<TradeInstruction> trades = new ArrayList<>();
        List<WatchlistChange> watchlistChanges = new ArrayList<>();
        StringBuilder messageBuilder = new StringBuilder();

        Matcher buyMatcher = BUY_PATTERN.matcher(userMessage);
        while (buyMatcher.find()) {
            double qty = Double.parseDouble(buyMatcher.group(1));
            String ticker = buyMatcher.group(2).toUpperCase();
            trades.add(new TradeInstruction(ticker, "buy", qty));
            messageBuilder.append(String.format("Executing market order to BUY %.2f shares of %s. ", qty, ticker));
        }

        Matcher sellMatcher = SELL_PATTERN.matcher(userMessage);
        while (sellMatcher.find()) {
            double qty = Double.parseDouble(sellMatcher.group(1));
            String ticker = sellMatcher.group(2).toUpperCase();
            trades.add(new TradeInstruction(ticker, "sell", qty));
            messageBuilder.append(String.format("Executing market order to SELL %.2f shares of %s. ", qty, ticker));
        }

        Matcher addMatcher = ADD_WATCH_PATTERN.matcher(userMessage);
        while (addMatcher.find()) {
            String ticker = addMatcher.group(1).toUpperCase();
            if (!"TO".equals(ticker) && !"THE".equals(ticker)) {
                watchlistChanges.add(new WatchlistChange(ticker, "add"));
                messageBuilder.append(String.format("Adding %s to your watchlist. ", ticker));
            }
        }

        Matcher removeMatcher = REMOVE_WATCH_PATTERN.matcher(userMessage);
        while (removeMatcher.find()) {
            String ticker = removeMatcher.group(1).toUpperCase();
            if (!"FROM".equals(ticker) && !"THE".equals(ticker)) {
                watchlistChanges.add(new WatchlistChange(ticker, "remove"));
                messageBuilder.append(String.format("Removing %s from your watchlist. ", ticker));
            }
        }

        if (trades.isEmpty() && watchlistChanges.isEmpty()) {
            String lower = userMessage.toLowerCase();
            if (lower.contains("portfolio") || lower.contains("balance") || lower.contains("performance") || lower.contains("analyze")) {
                messageBuilder.append("Your portfolio is currently being tracked in real time. Your virtual cash balance and live equity values are streaming on the dashboard. Diversification across tech and defensive sectors is recommended.");
            } else if (lower.contains("help") || lower.contains("hello") || lower.contains("hi")) {
                messageBuilder.append("Hello! I am SignalForge, your AI trading assistant. You can ask me to analyze your portfolio, execute trades (e.g. 'buy 10 AAPL'), or manage your watchlist (e.g. 'add NVDA').");
            } else {
                messageBuilder.append(String.format("Received request: \"%s\". Markets are actively streaming live prices. Let me know if you would like me to execute any orders or adjust your watchlist.", userMessage));
            }
        }

        return new LlmStructuredResponse(messageBuilder.toString().trim(), trades, watchlistChanges);
    }
}

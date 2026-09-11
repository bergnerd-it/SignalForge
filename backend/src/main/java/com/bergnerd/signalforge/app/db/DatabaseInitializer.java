package com.bergnerd.signalforge.app.db;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseInitializer {

    private final JdbcTemplate jdbcTemplate;

    @Value("${spring.datasource.url}")
    private String datasourceUrl;

    public static final List<String> DEFAULT_TICKERS = List.of(
            "AAPL", "GOOGL", "MSFT", "AMZN", "TSLA",
            "NVDA", "META", "JPM", "V", "NFLX"
    );

    @PostConstruct
    public void init() {
        ensureDatabaseDirectory();
        executeSchema();
        seedDefaults();
    }

    private void ensureDatabaseDirectory() {
        if (datasourceUrl != null && datasourceUrl.startsWith("jdbc:sqlite:")) {
            String dbPath = datasourceUrl.substring("jdbc:sqlite:".length());
            if (!":memory:".equals(dbPath)) {
                File dbFile = new File(dbPath);
                File parent = dbFile.getParentFile();
                if (parent != null && !parent.exists()) {
                    boolean created = parent.mkdirs();
                    log.info("Created database parent directory: {} (success={})", parent.getAbsolutePath(), created);
                }
            }
        }
    }

    private void executeSchema() {
        try {
            ClassPathResource resource = new ClassPathResource("db/schema.sql");
            try (InputStream is = resource.getInputStream()) {
                String sql = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                for (String statement : sql.split(";")) {
                    String trimmed = statement.trim();
                    if (!trimmed.isEmpty()) {
                        jdbcTemplate.execute(trimmed);
                    }
                }
            }
            log.info("Database schema initialized successfully");
        } catch (Exception e) {
            log.error("Failed to execute database schema: {}", e.getMessage(), e);
            throw new IllegalStateException("Database initialization failed", e);
        }
    }

    private void seedDefaults() {
        String now = Instant.now().toString();

        // Seed default user profile
        Integer userCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users_profile WHERE id = 'default'",
                Integer.class
        );
        if (userCount == null || userCount == 0) {
            jdbcTemplate.update(
                    "INSERT INTO users_profile (id, cash_balance, created_at) VALUES (?, ?, ?)",
                    "default", 10000.0, now
            );
            log.info("Seeded default user profile with $10,000 cash balance");
        }

        // Seed default watchlist
        Integer watchlistCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM watchlist WHERE user_id = 'default'",
                Integer.class
        );
        if (watchlistCount == null || watchlistCount == 0) {
            for (String ticker : DEFAULT_TICKERS) {
                jdbcTemplate.update(
                        "INSERT OR IGNORE INTO watchlist (id, user_id, ticker, added_at) VALUES (?, ?, ?, ?)",
                        UUID.randomUUID().toString(), "default", ticker, now
                );
            }
            log.info("Seeded default watchlist with {} tickers", DEFAULT_TICKERS.size());
        }

        // Seed initial portfolio snapshot if none exist
        Integer snapshotCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM portfolio_snapshots WHERE user_id = 'default'",
                Integer.class
        );
        if (snapshotCount == null || snapshotCount == 0) {
            jdbcTemplate.update(
                    "INSERT INTO portfolio_snapshots (id, user_id, total_value, recorded_at) VALUES (?, ?, ?, ?)",
                    UUID.randomUUID().toString(), "default", 10000.0, now
            );
            log.info("Seeded initial portfolio snapshot with $10,000");
        }
    }
}

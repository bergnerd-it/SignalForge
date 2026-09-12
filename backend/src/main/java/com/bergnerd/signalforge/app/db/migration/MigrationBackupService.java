package com.bergnerd.signalforge.app.db.migration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;

/**
 * SQLite consistent backup procedure.
 * Guarantees atomic snapshot of database state including committed WAL transactions
 * using SQLite's VACUUM INTO and PRAGMA wal_checkpoint.
 * Refuses unsafe overwrite of an existing backup file.
 */
@Slf4j
@Service
public class MigrationBackupService {

    public Path performConsistentBackup(DataSource dataSource, String datasourceUrl, Path destinationDir) {
        try {
            Files.createDirectories(destinationDir);
            String timestamp = Instant.now().toString().replace(":", "-");
            String backupFilename = "signalforge-pre-migration-" + timestamp + ".db";
            Path backupPath = destinationDir.resolve(backupFilename);

            if (Files.exists(backupPath)) {
                throw new IllegalStateException("Refusing unsafe overwrite: backup file already exists at " + backupPath);
            }

            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement()) {

                // 1. Checkpoint WAL into database file
                try {
                    stmt.execute("PRAGMA wal_checkpoint(TRUNCATE)");
                } catch (SQLException e) {
                    log.warn("wal_checkpoint failed (possibly in-memory or not in WAL mode): {}", e.getMessage());
                }

                // 2. Perform atomic vacuum into backup file
                String escapedPath = backupPath.toAbsolutePath().toString().replace("'", "''");
                stmt.execute("VACUUM INTO '" + escapedPath + "'");
            }

            log.info("Consistent SQLite backup created successfully at: {}", backupPath);
            return backupPath;
        } catch (Exception e) {
            log.error("Failed to perform consistent database backup: {}", e.getMessage(), e);
            throw new IllegalStateException("Database backup failed: " + e.getMessage(), e);
        }
    }
}

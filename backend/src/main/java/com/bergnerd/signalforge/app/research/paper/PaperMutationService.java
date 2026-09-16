package com.bergnerd.signalforge.app.research.paper;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaperMutationService {

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public record MutationEntry(
            String id,
            String ownerId,
            String action,
            String idempotencyKey,
            String payloadHash,
            String status,
            String resourceId,
            String resultJson
    ) {}

    public static String computeHash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public Optional<MutationEntry> checkMutation(String ownerId, String action, String idempotencyKey, String payloadHash) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key is required for " + action);
        }
        try {
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT id, owner_id, action, idempotency_key, payload_hash, status, resource_id, result_json " +
                            "FROM paper_mutation_requests WHERE owner_id = ? AND action = ? AND idempotency_key = ?",
                    ownerId, action, idempotencyKey
            );
            String recordedHash = (String) row.get("payload_hash");
            if (!payloadHash.equals(recordedHash)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Conflicting reuse of idempotency key: " + idempotencyKey);
            }
            return Optional.of(new MutationEntry(
                    (String) row.get("id"),
                    (String) row.get("owner_id"),
                    (String) row.get("action"),
                    (String) row.get("idempotency_key"),
                    recordedHash,
                    (String) row.get("status"),
                    (String) row.get("resource_id"),
                    (String) row.get("result_json")
            ));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Transactional
    public void recordCommitted(String ownerId, String action, String idempotencyKey, String payloadHash, String resourceId, Object result) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key is required for " + action);
        }
        String now = clock.instant().toString();
        String resultJson;
        try {
            resultJson = objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            resultJson = "{}";
        }
        try {
            jdbcTemplate.update(
                    "INSERT INTO paper_mutation_requests (id, owner_id, action, idempotency_key, payload_hash, status, resource_id, result_json, created_at, updated_at) " +
                            "VALUES (?, ?, ?, ?, ?, 'COMMITTED', ?, ?, ?, ?)",
                    "mut-" + UUID.randomUUID(), ownerId, action, idempotencyKey, payloadHash, resourceId, resultJson, now, now
            );
        } catch (DuplicateKeyException e) {
            MutationEntry existing = checkMutation(ownerId, action, idempotencyKey, payloadHash)
                    .orElseThrow(() -> e);
            if ("COMMITTED".equals(existing.status())) {
                return;
            }
            jdbcTemplate.update(
                    "UPDATE paper_mutation_requests SET status = 'COMMITTED', resource_id = ?, result_json = ?, updated_at = ? " +
                            "WHERE owner_id = ? AND action = ? AND idempotency_key = ?",
                    resourceId, resultJson, now, ownerId, action, idempotencyKey
            );
        }
    }
}

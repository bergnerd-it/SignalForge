package com.bergnerd.signalforge.app.research.backtest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class UniverseService {

    private final JdbcTemplate jdbcTemplate;

    public BacktestDtos.UniverseDto createUniverse(String ownerId, BacktestDtos.CreateUniverseRequest request) {
        String uid = (ownerId == null || ownerId.isBlank()) ? "default" : ownerId;
        if (request.name() == null || request.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }
        if (request.listingIds() == null || request.listingIds().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "listingIds must contain at least one listing");
        }

        String id = "uni-" + UUID.randomUUID();
        String now = Instant.now().toString();
        String version = (request.version() != null && !request.version().isBlank()) ? request.version() : "1.0.0";
        String calendarId = (request.calendarId() != null && !request.calendarId().isBlank()) ? request.calendarId() : "XETR";
        String currency = (request.currency() != null && !request.currency().isBlank()) ? request.currency() : "EUR";
        String datasetId = request.datasetId();
        if (datasetId == null || datasetId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "datasetId is required");
        }

        jdbcTemplate.update(
                "INSERT INTO universes (id, owner_id, name, version, description, dataset_id, calendar_id, currency, provenance, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, uid, request.name().trim(), version, request.description(), datasetId, calendarId, currency, request.provenance(), now
        );

        int ordinal = 0;
        for (String lid : request.listingIds()) {
            jdbcTemplate.update(
                    "INSERT INTO universe_listings (universe_id, listing_id, ordinal) VALUES (?, ?, ?)",
                    id, lid.trim(), ++ordinal
            );
        }

        return getUniverse(id, uid);
    }

    public BacktestDtos.UniverseDto getUniverse(String id, String ownerId) {
        String uid = (ownerId == null || ownerId.isBlank()) ? "default" : ownerId;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM universes WHERE id = ? AND (owner_id = ? OR owner_id = 'default')",
                id, uid
        );
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Universe not found: " + id);
        }
        Map<String, Object> r = rows.get(0);

        List<String> listingIds = jdbcTemplate.queryForList(
                "SELECT listing_id FROM universe_listings WHERE universe_id = ? ORDER BY listing_id ASC",
                String.class, id
        );

        List<BacktestDtos.UniverseListingDto> listings = new ArrayList<>();
        for (int i = 0; i < listingIds.size(); i++) {
            String lid = listingIds.get(i);
            listings.add(new BacktestDtos.UniverseListingDto(lid, i + 1, lid, "XETR", "EUR"));
        }

        return new BacktestDtos.UniverseDto(
                (String) r.get("id"),
                (String) r.get("owner_id"),
                (String) r.get("name"),
                (String) r.get("version"),
                (String) r.get("description"),
                (String) r.get("dataset_id"),
                (String) r.get("calendar_id"),
                (String) r.get("currency"),
                (String) r.get("provenance"),
                listings.size(),
                listings,
                (String) r.get("created_at")
        );
    }

    public List<BacktestDtos.UniverseDto> listUniverses(String ownerId) {
        String uid = (ownerId == null || ownerId.isBlank()) ? "default" : ownerId;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id FROM universes WHERE owner_id = ? OR owner_id = 'default' ORDER BY created_at DESC",
                uid
        );
        List<BacktestDtos.UniverseDto> list = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            list.add(getUniverse((String) r.get("id"), uid));
        }
        return list;
    }
}

package com.bergnerd.signalforge.app.research.backtest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class UniverseService {

    private final JdbcTemplate jdbcTemplate;

    @Transactional
    public BacktestDtos.UniverseDto createUniverse(String ownerId, BacktestDtos.CreateUniverseRequest request) {
        String uid = (ownerId == null || ownerId.isBlank()) ? "default" : ownerId;
        if (request.name() == null || request.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }
        if (request.listingIds() == null || request.listingIds().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "listingIds must contain at least one listing");
        }
        String datasetId = request.datasetId();
        if (datasetId == null || datasetId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "datasetId is required");
        }

        // Validate dataset exists and is valid
        List<Map<String, Object>> dsRows = jdbcTemplate.queryForList(
                "SELECT id, validation_status FROM datasets WHERE id = ?", datasetId
        );
        if (dsRows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Dataset not found: " + datasetId);
        }
        String validationStatus = (String) dsRows.get(0).get("validation_status");
        if (!"VALID".equalsIgnoreCase(validationStatus) && !"VALIDATED".equalsIgnoreCase(validationStatus)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Dataset is not validated: " + datasetId);
        }

        // Validate all listings exist in the declared dataset
        String placeholders = String.join(",", Collections.nCopies(request.listingIds().size(), "?"));
        List<Object> params = new ArrayList<>();
        params.add(datasetId);
        params.addAll(request.listingIds().stream().map(String::trim).toList());
        List<String> existingListings = jdbcTemplate.queryForList(
                "SELECT listing_id FROM dataset_listings WHERE dataset_id = ? AND listing_id IN (" + placeholders + ")",
                String.class, params.toArray()
        );
        Set<String> existingSet = new HashSet<>(existingListings);
        for (String lid : request.listingIds()) {
            if (!existingSet.contains(lid.trim())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Listing " + lid.trim() + " does not exist in declared dataset " + datasetId);
            }
        }

        String id = "uni-" + UUID.randomUUID();
        String now = Instant.now().toString();
        String version = (request.version() != null && !request.version().isBlank()) ? request.version() : "1.0.0";
        String calendarId = (request.calendarId() != null && !request.calendarId().isBlank()) ? request.calendarId() : "XETR";
        String currency = (request.currency() != null && !request.currency().isBlank()) ? request.currency() : "EUR";
        if (!"EUR".equalsIgnoreCase(currency)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Universe currency must be EUR");
        }
        Integer incompatibleListings = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dataset_listings WHERE dataset_id = ? AND listing_id IN (" + placeholders + ") " +
                        "AND (calendar_id <> ? OR quote_currency <> 'EUR')",
                Integer.class, java.util.stream.Stream.concat(params.stream(), java.util.stream.Stream.of(calendarId))
                        .toArray());
        if (incompatibleListings != null && incompatibleListings > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Universe listings must share the declared calendar and EUR currency");
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
        String datasetId = (String) r.get("dataset_id");

        List<Map<String, Object>> listingRows = jdbcTemplate.queryForList(
                "SELECT ul.listing_id, ul.ordinal, dl.symbol, dl.calendar_id, dl.quote_currency " +
                        "FROM universe_listings ul " +
                        "LEFT JOIN dataset_listings dl ON dl.dataset_id = ? AND dl.listing_id = ul.listing_id " +
                        "WHERE ul.universe_id = ? ORDER BY ul.ordinal ASC",
                datasetId, id
        );

        List<BacktestDtos.UniverseListingDto> listings = new ArrayList<>();
        for (Map<String, Object> lr : listingRows) {
            String lid = (String) lr.get("listing_id");
            int ord = ((Number) lr.get("ordinal")).intValue();
            String sym = (String) lr.get("symbol");
            String cal = (String) lr.get("calendar_id");
            String curr = (String) lr.get("quote_currency");
            if (sym == null || cal == null || curr == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Universe listing " + lid + " is missing dataset metadata");
            }
            listings.add(new BacktestDtos.UniverseListingDto(lid, ord, sym, cal, curr));
        }

        return new BacktestDtos.UniverseDto(
                (String) r.get("id"),
                (String) r.get("owner_id"),
                (String) r.get("name"),
                (String) r.get("version"),
                (String) r.get("description"),
                datasetId,
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

package com.bergnerd.signalforge.app.research.paper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaperAuditRecorder {

    private final JdbcTemplate jdbcTemplate;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String recordAdoptionInNewTx(
            String portfolioId,
            String datasetId,
            String checksum,
            String coverageStart,
            String coverageEnd,
            String status,
            String rejectionReason,
            String adoptedAt
    ) {
        String adoptionId = "adopt-" + UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO paper_dataset_adoptions (id, portfolio_id, dataset_id, dataset_checksum, " +
                        "coverage_start_session, coverage_end_session, validation_status, rejection_reason, adopted_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                adoptionId, portfolioId, datasetId, checksum,
                coverageStart != null ? coverageStart : "",
                coverageEnd != null ? coverageEnd : "",
                status, rejectionReason, adoptedAt
        );
        return adoptionId;
    }
}

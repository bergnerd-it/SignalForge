---
id: TASK-16
title: Correct M4 after independent code review
status: Done
assignee:
  - '@codex'
created_date: '2026-09-14 13:00'
updated_date: '2026-09-14 20:28'
labels: []
dependencies:
  - TASK-15
references:
  - planning/PROMPT-SIGNALFORGE-M4.md
documentation:
  - planning/reports/M4-code-review.md
priority: high
type: bug
ordinal: 16000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Independent code review of M4 (planning/reports/M4-code-review.md) identified 10 findings across data availability cutoffs, comparison creation/typing, holdout exposure transactionality, S3 sizing rules, universe and strategy parameter validation, frontend-backend contract mismatches, comparison boundary matching, experiment validation, and rolling window calendar coverage.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 P0-1: Enforce observation availability cutoffs and timing for monthly signals and corporate actions; reject or delay if data arrives after open
- [x] #2 P0-2: Fix comparison creation idempotency key, unwrap paged comparison list, and align frontend comparison models
- [x] #3 P0-3: Ensure holdout exposure is recorded transactionally before returning results, fail closed on exposure failure, including summary/list endpoints
- [x] #4 P1-4: Follow shared target-weight sizing rule for S3 (floor(weight * preTradeEquity / raw open)) and handle dividend cash timing correctly
- [x] #5 P1-5: Validate universe versions as owner-scoped, frozen input sets against declared dataset and calendar, make creation atomic
- [x] #6 P1-6: Validate strategy parameters, reject unknown versions, unsupported parameters, malformed JSON, and invalid K (1 <= K <= universe size)
- [x] #7 P1-7: Align signals and strategy catalog frontend contracts with backend DTOs, implement signals CSV export
- [x] #8 P1-8: Match all required financial boundaries in comparisons (funding instant, reporting session sequence, benchmark accounting, engine versions) and reconcile comparison DTO
- [x] #9 P1-9: Validate experiment boundaries (parse dates, session boundaries, non-overlapping development/holdout, compatible datasets) and set default status to UNKNOWN
- [x] #10 P2-10: Verify dataset calendar coverage of rolling five-year window anniversary boundaries and ensure exact-decimal return typing
<!-- AC:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
1. Repair valid S2 creation, immutable version metadata, owner-scoped universe and experiment validation. 2. Align frontend DTOs and download routes; bound and escape signals CSV. 3. Correct signal-history timing, initial funding boundary, and S3 reinvestment. 4. Strengthen comparison and rolling-window edge handling. 5. Add focused acceptance tests, run required suites, and reconcile M4 review, walkthrough, and research report.
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Validation: 174 backend tests passed (./gradlew test), 43 frontend tests passed (npm test -- --watch=false), production build passed (npm run build).

Final verification: ./gradlew clean test passed 175 backend tests; npm test -- --watch=false passed 43 frontend tests; npm run build passed with the existing CSS budget warning. The S2 HTTP fixture verifies valid execution, owner rejection, fractional K rejection, and a later-month observation delayed past the next open. Holdout list and signals export persist allowed exposure types. Updated research-M4.md, walkthrough-M4.md, and M4-code-review.md. Native browser and populated V9 upgrade were not repeated in this correction run.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
Corrected all ten M4 review findings across availability timing, S2/S3 execution, owner and parameter validation, exposure recording, API/UI contracts, comparisons, experiments, rolling windows, and migration metadata. Verified with 175 backend tests, 43 frontend tests, and an Angular production build; updated M4 review, walkthrough, and research reports.
<!-- SECTION:FINAL_SUMMARY:END -->

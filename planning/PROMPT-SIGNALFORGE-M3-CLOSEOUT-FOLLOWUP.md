# Prompt: finish the remaining native M3 closeout work

Complete M3 only. Do not implement M4. Docker remains explicitly deferred.

Read `AGENTS.md`, `planning/PROMPT-SIGNALFORGE-M3.md`, `planning/PROMPT-SIGNALFORGE-M3-FIXES.md`, `planning/reports/M3-code-review.md`, `planning/reports/M3-closeout-review.md`, and **`planning/reports/M3-closeout-followup-review.md`** before changing code. Verify the follow-up findings against the current tree; preserve the **original** finding IDs. Preserve all existing uncommitted work. Use disposable databases only; never inspect, migrate or modify the owner's working database. Do not add dependencies without the approval required by `AGENTS.md`.

Resolve the remaining verified defects in the follow-up review:

1. **HIGH-3:** Make same-key replay respect the complete frozen engine/dataset/build identity. Record truthful source commit/build/dirty metadata from the tested binary, not fixed literals. Export the frozen run metadata without silently substituting current-binary values. Test version/identity changes and deterministic repeat output.
2. **MEDIUM-1:** Align the initial funded equity boundary with funding immediately before first execution. Exclude evaluation/warm-up days from CAGR and derive partial-year labels from actual reporting/calendar boundaries. Keep the first-fill cost and 1.8% reference return visible; test a longer period and year/holiday boundaries.
3. **HIGH-5:** Never present a truncated candidate or benchmark chart as complete. Follow `hasMore` through the full supported range, or expose an explicit loaded/total limit and incomplete state. Test the 50,000-point boundary and stale response transitions.
4. **MEDIUM-3:** Enforce exact row and practical byte bounds before returning a streaming ZIP response, with an actionable HTTP error on unsupported exports. Preserve streaming and exact decimal text. Test at and beyond each bound, including holdings, and verify downloaded contents.

Close the **HIGH-4 evidence gap** with deterministic temporary-SQLite tests for an actual queued worker followed by cancellation, repeat cancellation of a CANCELLED run, cancellation versus publication, queue saturation, and injected failure during child publication. Assert durable terminal status and no partial completed result. Treat a failed probe as an implementation defect and fix it; do not claim race safety from sequential tests or test counts.

Correct `planning/reports/research-M3-fixes.md` under the original IDs with the tested commit plus dirty-tree identity, exact toolchain versions from manifests/lockfiles/runtime, commands and results, and honest browser/download evidence. The current report overstates all-gates completion and misstates some versions/test types. Run the required backend/frontend suites and production build with the pinned Java 21/Node 24 toolchains. Perform a native browser and real ZIP-download walkthrough on a disposable database if available; otherwise mark it NOT VERIFIED. Recommend closing M3 only if all applicable native M3 gates pass. Stop before M4.

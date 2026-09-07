---
id: TASK-5
title: Fix response_format JSON schema required fields for OpenAI Structured Outputs
status: Done
assignee:
  - '@developer'
created_date: '2026-09-06 14:56'
updated_date: '2026-09-06 14:57'
labels: []
dependencies: []
ordinal: 5000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Fix 400 Bad Request error from OpenAI API by including all properties ('message', 'trades', 'watchlist_changes') in the 'required' array of the JSON schema.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 Root JSON schema required array includes 'message', 'trades', and 'watchlist_changes'
- [x] #2 Unit tests verify response_format schema contains all required fields and strict structure
- [x] #3 All backend and frontend tests pass
<!-- AC:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
1. Update createJsonSchema() in OpenAiCompatibleLlmClient.java to include all property keys ('message', 'trades', 'watchlist_changes') in the required list.
2. Add assertions in OpenAiCompatibleLlmClientTest to verify jsonPath for response_format.json_schema.schema.required array.
3. Run backend tests to verify.
4. Run frontend tests to verify.
5. Finalize Backlog task.
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Updated OpenAiCompatibleLlmClient.java to specify 'message', 'trades', and 'watchlist_changes' in the JSON schema required list for strict Structured Outputs. Updated OpenAiCompatibleLlmClientTest.java with assertions for the required array and strict configuration. Verified via Gradle clean test and Angular Vitest suites.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
Resolved OpenAI 400 Bad Request error by including all declared property keys ('message', 'trades', 'watchlist_changes') in the JSON schema 'required' array under strict Structured Outputs mode. Verified with passing backend and frontend test suites.
<!-- SECTION:FINAL_SUMMARY:END -->

---
id: TASK-6
title: >-
  Replace .env file & filehandling with a Spring Boot compliant
  application-local.yml
status: Done
assignee:
  - '@junie'
created_date: '2026-09-07 08:56'
updated_date: '2026-09-07 08:57'
labels: []
dependencies: []
ordinal: 6000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Custom .env parsing and custom EnvironmentPostProcessor deviate from standard Spring Boot configuration mechanisms. Replacing them with a native Spring Boot application-local.yml configuration profile simplifies setup, adheres to Spring conventions, and eliminates custom file I/O.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 Remove DotenvLoader, DotenvEnvironmentPostProcessor, EnvironmentPostProcessor imports, and custom .env bootstrap calls
- [x] #2 Configure Spring Boot to support application-local.yml for local overrides with template documentation
- [x] #3 Update .gitignore to ignore application-local.yml files
- [x] #4 Add unit and integration tests verifying Spring Boot profile and local YAML configuration resolution
<!-- AC:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
Removed custom .env loader/processor classes and imports, configured Spring Boot with native local profile and application-local.yml support, added application-local.yml.example template, updated .gitignore, and added ApplicationLocalConfigTest tests.
<!-- SECTION:FINAL_SUMMARY:END -->

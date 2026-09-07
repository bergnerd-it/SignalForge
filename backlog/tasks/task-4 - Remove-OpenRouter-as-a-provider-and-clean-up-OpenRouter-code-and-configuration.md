---
id: TASK-4
title: Remove OpenRouter as a provider and clean up OpenRouter code and configuration
status: Done
assignee:
  - '@developer'
created_date: '2026-09-06 14:47'
updated_date: '2026-09-06 14:53'
labels: []
dependencies: []
ordinal: 4000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Remove OpenRouter provider support, remove OpenRouter-specific client implementations, legacy configuration fallback keys, and update all configuration and documentation to standard OpenAI-compatible providers.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 OpenRouter is completely removed as a provider option from OpenAiCompatibleLlmClient and ChatConfig
- [x] #2 OpenRouterLlmClient and OpenRouter-specific headers and request body routing logic are removed
- [x] #3 Legacy openrouter configuration properties and OPENROUTER_API_KEY environment variable fallbacks are removed from application.yml and .env.example
- [x] #4 Default provider is switched to openai with gpt-4o-mini
- [x] #5 All unit and integration tests pass without OpenRouter references
<!-- AC:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
1. Delete OpenRouterLlmClient.java.
2. Refactor OpenAiCompatibleLlmClient to remove OpenRouter headers, Cerebras routing, provider constant, and update default provider/URL/model to OpenAI.
3. Refactor ChatConfig to remove legacy OpenRouter properties and set default provider to openai.
4. Update application.yml, .env.example, and docker-compose.yml to remove OpenRouter properties and use standard LLM_* environment variables with openai default.
5. Update test suites (ChatConfigTest, OpenAiCompatibleLlmClientTest, etc.) to verify OpenAI, Ollama, Groq, and custom providers without OpenRouter.
6. Update documentation files in planning/ to reflect the removal of OpenRouter.
7. Run format, lint, and full backend and frontend test suites to verify everything passes.
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Deleted OpenRouterLlmClient.java. Refactored OpenAiCompatibleLlmClient and ChatConfig to remove OpenRouter provider routing, custom headers, and legacy fallback properties. Set OpenAI (gpt-4o-mini) as default provider. Updated application.yml, .env.example, docker-compose.yml, tests, and documentation. Verified all backend tests via gradle test and frontend tests via vitest.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
Removed OpenRouter provider and OpenRouter-specific client code/configuration across backend, environment configs, docker-compose, and documentation. Switched default provider to OpenAI (gpt-4o-mini). Verified with full clean test suites in Gradle and Angular.
<!-- SECTION:FINAL_SUMMARY:END -->

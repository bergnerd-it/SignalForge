---
id: TASK-1
title: Implement Configuration for other LLM clients
status: Done
assignee:
  - '@developer'
created_date: '2026-09-05 14:11'
updated_date: '2026-09-05 14:34'
labels: []
dependencies: []
ordinal: 1000
---

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 Unified OpenAiCompatibleLlmClient supports multiple providers (OpenRouter, OpenAI, Ollama, Groq, custom)
- [x] #2 Configuration properties under finally.llm (provider, base-url, api-key, model, mock) with environment variable mappings
- [x] #3 Backwards compatibility maintained for OPENROUTER_API_KEY and mock mode fallback
- [x] #4 Unit and configuration tests provide high coverage for provider resolution and request handling
<!-- AC:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
1. Implement OpenAiCompatibleLlmClient supporting generic OpenAI-compatible providers (OpenRouter, OpenAI, Ollama, Groq, custom).
2. Update ChatConfig with generic properties under finally.llm with fallback for backwards compatibility.
3. Update application.yml and .env.example with provider, base-url, api-key, and model configuration options.
4. Add comprehensive unit tests in OpenAiCompatibleLlmClientTest and ChatConfigTest.
5. Verify test suites across backend and frontend, and ensure linter/formatting compliance.
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Implemented unified OpenAiCompatibleLlmClient with support for OpenRouter, OpenAI, Ollama, Groq, and custom endpoints. Added generic configuration properties under finally.llm with backwards-compatible fallback for OPENROUTER_API_KEY. Added OpenAiCompatibleLlmClientTest and ChatConfigTest.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
Implemented unified OpenAI-compatible LLM client and generic configuration namespace under finally.llm with multi-provider presets (OpenRouter, OpenAI, Ollama, Groq, custom). Verified with unit and integration test suites in Gradle.
<!-- SECTION:FINAL_SUMMARY:END -->

Extend the backend, so that other llm clients can be used in the chat.
Depending on which API_KEY is set in the environment, the corresponding llm should be used.  
e.g. if OPENAI_API_KEY is set in .env, OPENAI should be used.
If the call produces an error, the reason should be logged and the next key should be tried.
Implement support for OPENAI_API_KEY, GEMINI_API_KEY, OPENROUTER_API_KEY . LLMs should be tried in that order.
If no key is specified, the now existing mock should be used as itt is now.

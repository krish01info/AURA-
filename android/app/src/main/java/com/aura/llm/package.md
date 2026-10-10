# llm/ package

Two LLM clients — same interface, different providers.

| File | Provider | When used |
|---|---|---|
| GroqClient.kt | Groq API | Primary. Model cascade: llama-3.3-70b → llama-3.1-8b → gemma2-9b |
| GeminiClient.kt | Google Gemini | Fallback when Groq fails (429, 5xx, timeout) |

## Shared interface
Both expose: `suspend fun plan(goal, uiContext, memoryContext): List<ActionStep>`

## Prompt budget (per call)
- System prompt: ~70 tokens
- Memory context: ~60 tokens (compact [MEMORY:...] tag)
- UI snapshot: max 2000 chars (~500 tokens)
- Goal: ~10-30 tokens
- Total input: ~650 tokens typical

## Response
JSON array of ActionStep only — no prose. Parsed by parseActionSteps() / parseSteps().

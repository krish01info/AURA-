# llm/ package

LLM planning: turn a goal + screen text into a list of `ActionStep`s.

| File | Role |
|---|---|
| GroqClient.kt | Primary. `plan()` for new tasks, `complete(userContent)` for any request (recovery). Model cascade: `openai/gpt-oss-120b` → `openai/gpt-oss-20b` → `qwen/qwen3.8-27b`. Throws `LlmException` with a user-readable message (bad key, rate limit, offline…). |
| GeminiClient.kt | Fallback (`gemini-3.8-flash`, thinking level "low", JSON mode, default temperature). Used by TaskManager when Groq fails or has no key. |
| PlannerPrompt.kt | Shared system prompt + tolerant parser → `Plan` (steps, intent, params, phrase patterns). One call gives both the steps and everything needed to save them as a skill. |
| ApiKeyStore.kt | Runtime API keys: Settings value first, `local.properties`/BuildConfig second. Encrypted prefs. |

## Rules
- Providers retire models often (Groq shut down all three old models in 2025–26). When a model dies, update the id in GroqClient/GeminiClient and the Settings info card.
- Adding an action or field to `ActionStep` → add it to `PlannerPrompt.SYSTEM`, or the model can't use it.
- Never read keys from BuildConfig directly; use `ApiKeyStore` (CI builds have empty BuildConfig keys).

## Prompt budget (per call)
- System prompt: ~800 tokens (skill fields, shortcuts incl. generic intent/media/notifications, verify)
- Recovery requests (`continueMessage`) only happen after a failed step
- Memory context: ~60 tokens (compact `[MEMORY:...]` tag)
- UI snapshot: max 2000 chars (~500 tokens)
- Goal: ~10–30 tokens

## Response
`{"intent":{…},"params":{…},"patterns":[…],"steps":[…]}` (a bare array or `{"steps":[…]}` is also accepted), parsed by `PlannerPrompt.parsePlan()`.
The LLM is only called when no stored skill matches (see `agent/SkillRouter`).

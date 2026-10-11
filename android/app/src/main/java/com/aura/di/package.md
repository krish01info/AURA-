# di/ package

Hilt modules (all `SingletonComponent`).

| File | Provides |
|---|---|
| DatabaseModule.kt | `AURADatabase` (destructive-migration fallback), every DAO, `SharedPreferences` |
| NetworkModule.kt | `OkHttpClient` (Authorization redacted in logs), Groq `Retrofit` whose auth header is read from `ApiKeyStore` per request, `GroqClient` |
| SupabaseModule.kt | Supabase client (Auth, Postgrest, Realtime) from `BuildConfig`; `isConfigured` is false when keys are blank |

## Rules
- `GeminiClient` and `UpdateChecker` build their own HTTP clients (different hosts, no Groq header).
- API keys come from `llm/ApiKeyStore` (Settings first, `local.properties` → `BuildConfig` second); never hard-code them.

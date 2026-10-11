# di/ package

Hilt modules (all `SingletonComponent`).

| File | Provides |
|---|---|
| DatabaseModule.kt | `AURADatabase` (destructive-migration fallback), every DAO, `SharedPreferences` |
| NetworkModule.kt | `OkHttpClient` with Groq auth header, Groq `Retrofit`, `GroqClient` |
| SupabaseModule.kt | Supabase client (Auth, Postgrest, Realtime) from `BuildConfig`; `isConfigured` is false when keys are blank |

## Rules
- `GeminiClient` and `UpdateChecker` build their own HTTP clients (different hosts, no Groq header).
- Keys come from `android/local.properties` → `BuildConfig`; never hard-code them.

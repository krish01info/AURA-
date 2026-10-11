# AURA — Roadmap (deferred work)

> Moved out of AGENTS.md so it isn't loaded into every AI session. Do these when ready.


## Supabase Realtime — Live Skill Push Notifications

**What it does:**
Instead of polling every few hours, Supabase pushes a WebSocket notification the instant a community skill changes (new version, verified, marked stale). Every phone gets the update silently within seconds.

**Why deferred:**
- `UpdateCheckWorker` + `SkillUpdater.checkForCloudUpdate()` already cover the use case via periodic polling.
- A persistent WebSocket costs battery — not worth it until there are many active users.
- Free tier supports 200 concurrent connections (sufficient for early launch).

**How to implement:** Create `cloud/SupabaseRealtimeManager.kt`, subscribe to `skill_packs` UPDATE events via `supabase.realtime.channel()`, silently upsert newer versions into Room + promote to cache. Call `subscribeToSkillUpdates()` from `AURAApplication.onCreate()` after `ensureSession()`.

---

## Memory Sync + pgvector

**What it does:**
Replace the in-memory cosine loop in `MemoryManager.recall()` with a Postgres `pgvector` nearest-neighbor query. Scales to millions of memories and enables cross-device memory sync.

**Why deferred:** `pgvector` extension requires the Supabase Pro plan.

**SQL when upgrading:**
```sql
CREATE EXTENSION IF NOT EXISTS vector;
ALTER TABLE memory ADD COLUMN IF NOT EXISTS embedding vector(384);
CREATE INDEX ON memory USING ivfflat (embedding vector_cosine_ops);
```
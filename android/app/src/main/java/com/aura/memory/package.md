# memory/ package

Offline long-term memory of past tasks, used to add context to LLM prompts.

| File | Role |
|---|---|
| MemoryManager.kt | `remember()` after a task; `recall()` top-3 similar; `buildContextString()` → compact `[MEMORY:...]` tag (~60 tokens); also `getLastAppUsage`, `getSuggestions`, `getAllFlow`. |
| EmbeddingEngine.kt | Hash-based 384-dim TF-IDF embeddings, fully offline. (Future: ONNX all-MiniLM-L6-v2.) |

## Rules
- Keep the memory tag compact; it goes into every LLM call.
- Recall is an in-memory cosine loop; pgvector plan is in `docs/plans/roadmap.md`.

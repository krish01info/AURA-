# data/ package (db/ + model/)

Room database and all data classes.

## data/db/ — `AURADatabase` (version 7)

| DAO | Entity | Key queries |
|---|---|---|
| TaskLogDao | TaskLogEntity | insert, updateStatus, getAll (Flow) |
| MemoryDao | MemoryEntry | insert, getAllList, getByApp, getByKeyword, deleteOldest |
| SkillPackDao | SkillPackEntity | findByKeyword, getSkill, getVariantsForIntent, upsert, recordSuccess/Failure |
| AuditLogDao | AuditLogEntity | insert, getAll (Flow) |
| MacroDao | MacroEntity | insert, getAll (Flow) |
| PermissionRuleDao | PermissionRule | upsert, getByPackage |
| BlockedAppDao (in PermissionRuleDao.kt) | BlockedApp | blocked-app list |

Migrations: 6 → 7 is a Room **auto-migration** (adds skill-variant columns, clears old
unsafe skill rows, keeps all other data). Versions without an exported schema
(< 6) still fall back to a destructive rebuild. Commit each new `android/app/schemas/…/N.json`
that the build generates — the next auto-migration needs it.

## data/model/

| File | Contents |
|---|---|
| ActionStep.kt | `ActionStep` (one LLM step: action, pkg, text, viewId, inputText, direction, waitMs, message, risk, clarification) + action constants (`OPEN_APP`, `TAP`, `TYPE`, `SWIPE`, `SCROLL`, `BACK`, `HOME`, `FIND_ELEMENT`, `WAIT`, `CONFIRM_SEND`, `READ_TEXT`, `PRESS_KEY`, `CLARIFY`); `Goal` |
| SkillPackEntity.kt | One skill **variant** (`@Serializable`, also stored as JSON in the disk cache): task id, phrase patterns, step template, OS/skin/app-version key, health counters |
| MemoryEntry.kt | Long-term memory row |
| TaskLogEntity.kt | Task log row + `TaskStatus` constants |
| AuditLogEntity.kt | Policy audit event |
| MacroEntity.kt | Saved macro (list of goals) |
| PermissionModels.kt | `PermissionRule`, `AppPermission` enum, `BlockedApp` |
| LlmModels.kt | Chat API request/response classes |
| UpdateModels.kt | GitHub Release API classes + `UpdateInfo` |

## Rules
- New table / column → entity here + DAO + register in `AURADatabase.kt` + bump version + `AutoMigration` (or a manual `Migration`).
- DB work only in DAO `suspend fun`s or `Dispatchers.IO`.

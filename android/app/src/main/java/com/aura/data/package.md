# data/ package (db/ + model/)

Room database and all data classes.

## data/db/ — `AURADatabase` (version 6)

| DAO | Entity | Key queries |
|---|---|---|
| TaskLogDao | TaskLogEntity | insert, updateStatus, getAll (Flow) |
| MemoryDao | MemoryEntry | insert, getAllList, getByApp, getByKeyword, deleteOldest |
| SkillPackDao | SkillPackEntity | findByKeyword, findBySkillId, upsert, incrementUsage |
| AuditLogDao | AuditLogEntity | insert, getAll (Flow) |
| MacroDao | MacroEntity | insert, getAll (Flow) |
| PermissionRuleDao | PermissionRule | upsert, getByPackage |
| BlockedAppDao (in PermissionRuleDao.kt) | BlockedApp | blocked-app list |

Note: `di/DatabaseModule.kt` uses `fallbackToDestructiveMigration()`, so a version bump
without a migration **wipes all user data** on upgrade.

## data/model/

| File | Contents |
|---|---|
| ActionStep.kt | `ActionStep` (one LLM step: action, pkg, text, viewId, inputText, direction, waitMs, message, risk, clarification) + action constants (`OPEN_APP`, `TAP`, `TYPE`, `SWIPE`, `SCROLL`, `BACK`, `HOME`, `FIND_ELEMENT`, `WAIT`, `CONFIRM_SEND`, `READ_TEXT`, `PRESS_KEY`, `CLARIFY`); `Goal` |
| SkillPackEntity.kt | A learned or downloaded skill, with OS classification fields |
| MemoryEntry.kt | Long-term memory row |
| TaskLogEntity.kt | Task log row + `TaskStatus` constants |
| AuditLogEntity.kt | Policy audit event |
| MacroEntity.kt | Saved macro (list of goals) |
| PermissionModels.kt | `PermissionRule`, `AppPermission` enum, `BlockedApp` |
| LlmModels.kt | Chat API request/response classes |
| UpdateModels.kt | GitHub Release API classes + `UpdateInfo` |

## Rules
- New table → entity here + DAO + register in `AURADatabase.kt` + bump version + migration.
- DB work only in DAO `suspend fun`s or `Dispatchers.IO`.

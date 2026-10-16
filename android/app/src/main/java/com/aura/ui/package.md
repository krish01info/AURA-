# ui/ package

Single-activity Compose UI.

| File | Role |
|---|---|
| MainActivity.kt | Hosts the Compose UI. |
| AURAApp.kt | `NavHost` with every route. |
| theme/AURATheme.kt | Material 3 theme: calm indigo, light + dark (follows the phone), shapes, type. `Aura*` colours are mid-tones that work on both. The popup (`voice/AssistantOverlay`) copies these values. |

## Screens and ViewModels (one each)

| Screen (`screens/`) | ViewModel (`viewmodel/`) | Purpose |
|---|---|---|
| HomeScreen (~710 lines) | HomeViewModel | Command input, task state, approvals, update banner |
| DashboardScreen (~375) | DashboardViewModel | Stats and quick actions |
| SettingsScreen (~455) | SettingsViewModel | API keys, app rules, manual update check |
| PermissionsScreen (~475) | PermissionsViewModel | Android permission setup flow |
| SkillStoreScreen (~311) | SkillStoreViewModel | Browse and install skills |
| TaskLogScreen | TaskLogViewModel | Task / audit log |

## Rules
- State as `StateFlow` in the ViewModel; collect with `collectAsStateWithLifecycle`.
- New screen → route in `AURAApp.kt` + its own ViewModel.
- Screens are large: search for the composable you need instead of reading the whole file.

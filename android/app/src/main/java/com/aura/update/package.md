# update/ package

App self-update from GitHub Releases (`krish01info/AURA-`).

| File | Role |
|---|---|
| UpdateChecker.kt | Polls the GitHub Releases API (own OkHttp client, no auth). |
| UpdateCheckWorker.kt | WorkManager job every 12 h; notifies when a new version exists. Scheduled from `AURAApplication`. |
| UpdateManager.kt | Downloads the APK with DownloadManager and opens the system installer. |
| UpdateRepository.kt | Shared update state for `HomeScreen` (banner) and `SettingsScreen` (manual check). |

Models: `data/model/UpdateModels.kt`. Needs `REQUEST_INSTALL_PACKAGES` and the FileProvider in the manifest.

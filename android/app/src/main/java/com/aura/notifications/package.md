# notifications/ package

Optional "Notification access" (Settings → Notification access). Lets AURA act on
messages and media **without opening apps**.

| File | Role |
|---|---|
| AuraNotificationListener.kt | System `NotificationListenerService`; forwards posted/removed notifications to the store (ignores AURA's own). |
| NotificationStore.kt | In-memory list of the last 50 notifications (title, text, app, Reply action). `reply()` uses the app's own Reply action (RemoteInput); `recent()` for reading aloud; `nowPlaying()` via `MediaSessionManager`. |

Used by `agent/ActionExecutor` for the `reply`, `read_notifications` and `now_playing` actions.

## Rules
- Privacy: memory only — never write notification text to disk, Room, logs or the cloud.
- Every entry point must handle access being off: return `NotificationStore.NEEDS_ACCESS` (spoken to the user).
- `reply` sends a message → `PolicyEngine` rates it HIGH (asks first).

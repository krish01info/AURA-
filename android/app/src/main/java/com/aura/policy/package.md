# policy/ package

Safety layer: decides what AURA may do and records what it did.

| File | Role |
|---|---|
| PolicyEngine.kt | `classify(step)` → `RiskLevel` LOW / MEDIUM / HIGH / CRITICAL. The only place risk is decided. |
| AppPermissionManager.kt | Per-app user rules (ALWAYS_ALLOW / ALWAYS_DENY / ALWAYS_CONFIRM), evaluated before risk. |
| PromptInjectionDefender.kt | Sanitizes and validates the user's goal before it reaches the LLM. |
| AnomalyDetector.kt | Flags suspicious step sequences and payments far above the user's usual amount. |
| AmountParser.kt | Reads a rupee amount from text ("₹5,000", "Rs. 250"); used by payment rules and the anomaly check. |
| AuditLogger.kt | Logs every auto-run, approved or denied step to `AuditLogEntity`. |
| BiometricGate.kt | Biometric prompt before CRITICAL actions (used from `HomeViewModel`). |

## Rules
- Never hard-code risk in UI or agent code; call `PolicyEngine.classify()`.
- Any change here is security-sensitive — add or update unit tests.

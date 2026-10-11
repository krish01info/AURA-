# macros/ package

| File | Role |
|---|---|
| MacroRunner.kt | Runs a macro = a list of natural-language goals in order, each through the full `TaskManager` pipeline (skill cache or LLM, policy checks, confirmations). `runMacro`, `runByTrigger`, `saveMacro`, `deleteMacro`, `getAllMacros`, `getScheduledMacros`. |

Storage: `MacroEntity` / `MacroDao` in `data/`.

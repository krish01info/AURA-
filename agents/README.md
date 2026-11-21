# agents/

**`repo_rules.md` and `this_repo.md` apply to everyone working in this repo, people and AI assistants.**
**The code skill is optional. Use it only if you want to, and change it as you like.**

## What is here

| File | What it is for |
|---|---|
| `repo_rules.md` | The rules every repo with this folder shares: git, talking a change through before it is built, simple code, and how an assistant keeps notes between conversations |
| `this_repo.md` | What only this repo has: its Android stack, module layout, Gradle checks, owner, what never gets committed, and the AURA-specific coding rules |
| `../.claude/skills/code/` | The code skill: writing and refactoring Android (Kotlin) code you can explain. A plan first, safe behavior-preserving refactors, clean layer boundaries, lifecycle and thread safety, a review checklist, and cleaned-up AURA examples |

In this repo the code skill lives in `.claude/skills/code/` instead of `agents/skills/code/`, so Claude Code
loads it and `/code` works. The pack's doc and deck skills were left out: AURA doesn't need them.

## Why this exists

An AI assistant makes it easy to push code nobody on the team can explain, or to rewrite half an app in one go and silently break behavior. The rules keep each person in charge of what their assistant writes: talk it through first, keep it simple, refactor in small verified steps, and leave notes for the next conversation.

## How to use it

- **Claude Code:** reads `CLAUDE.md`, which points here. Call the skill with `/code`, for example
  `/code add a unit test for AnomalyDetector`.
- **Other assistants (Copilot, Cursor, Codex, Gemini):** `AGENTS.md` and `.github/copilot-instructions.md` point
  them at `repo_rules.md` and `this_repo.md`. To use the code skill, ask them to read
  `.claude/skills/code/SKILL.md`, or paste it into their custom instructions.
- **Keep `this_repo.md` true:** when the stack, folders or checks change, update it in the same pull request.
- **Style examples:** `.claude/skills/code/examples/` has cleaned-up copies of real AURA files. Refresh a copy
  when the real file changes in a way that matters for style.

# Agent Instructions

This project uses **bd** (beads) for issue tracking. Run `bd onboard` to get started.

> **Legacy reference**: This app was forked from "Overdrive" and rebranded to BladeWatch. The legacy BladeWatch app lives at `/Volumes/mandark-1Tb/projects/loabletech/BladeWatch-Legacy` for reference only — do not modify it.

## Quick Reference

```bash
bd ready              # Find available work
bd show <id>          # View issue details
bd update <id> --claim  # Claim work atomically
bd close <id>         # Complete work
bd sync               # Sync with git
```

## Non-Interactive Shell Commands

**ALWAYS use non-interactive flags** with file operations to avoid hanging on confirmation prompts.

Shell commands like `cp`, `mv`, and `rm` may be aliased to include `-i` (interactive) mode on some systems, causing the agent to hang indefinitely waiting for y/n input.

**Use these forms instead:**
```bash
# Force overwrite without prompting
cp -f source dest           # NOT: cp source dest
mv -f source dest           # NOT: mv source dest
rm -f file                  # NOT: rm file

# For recursive operations
rm -rf directory            # NOT: rm -r directory
cp -rf source dest          # NOT: cp -r source dest
```

**Other commands that may prompt:**
- `scp` - use `-o BatchMode=yes` for non-interactive
- `ssh` - use `-o BatchMode=yes` to fail instead of prompting
- `apt-get` - use `-y` flag
- `brew` - use `HOMEBREW_NO_AUTO_UPDATE=1` env var

<!-- BEGIN BEADS INTEGRATION -->
## Issue Tracking with bd (beads)

**IMPORTANT**: This project uses **bd (beads)** for ALL issue tracking. Do NOT use markdown TODOs, task lists, or other tracking methods.

### Why bd?

- Dependency-aware: Track blockers and relationships between issues
- Version-controlled: Built on Dolt with cell-level merge
- Agent-optimized: JSON output, ready work detection, discovered-from links
- Prevents duplicate tracking systems and confusion

### Quick Start

**Check for ready work:**

```bash
bd ready --json
```

**Create new issues:**

```bash
bd create "Issue title" --description="Detailed context" -t bug|feature|task -p 0-4 --json
bd create "Issue title" --description="What this issue is about" -p 1 --deps discovered-from:bd-123 --json
```

**Claim and update:**

```bash
bd update <id> --claim --json
bd update bd-42 --priority 1 --json
```

### Writing Task Descriptions (CRITICAL)

Every task you create will likely be implemented by a **different, low-context agentic AI model** that has NONE of the context you have right now. The implementing agent sees ONLY the description — not this conversation, not the files you just read, not the reasoning in your head. A thin description guarantees the implementing agent will guess, drift, and make mistakes.

**Therefore: put EVERYTHING needed for implementation into the `--description`. Assume the implementer knows nothing.**

Every description you write MUST contain these sections, in order:

1. **Context / Problem** — What is broken or missing, and *why* this task exists. State the symptom and the root cause if known.
2. **Exact file paths and locations** — Full paths (e.g. `app/src/main/java/com/loabletech/bladewatch/...`), class names, function names, and line numbers where the work happens. Never say "the auth file" — name it.
3. **What to do** — Concrete, step-by-step implementation instructions. Include the current code and the intended code where helpful.
4. **What NOT to do / Constraints** — Patterns to preserve, files that must not change, project rules (e.g. BYD SDK stub pattern, `127.0.0.1` binding, no logging of secrets, do not auto-commit).
5. **Acceptance criteria** — ALWAYS REQUIRED. A checklist of objectively verifiable conditions that prove the task is done (e.g. "build passes", "button reverts to X after failed delete", "unit test Y added and green"). The implementing agent uses this to self-verify.
6. **A literal warning to be careful.** ALWAYS end the description with: *"Do not make mistakes. Read the referenced files fully before editing. Verify the build compiles and all acceptance criteria pass before closing. If anything is ambiguous, stop and ask rather than guessing."*

**Rules:**

- ✅ Self-contained: the description alone is enough to implement correctly with zero outside context.
- ✅ Specific: real paths, real names, real line numbers, real commands.
- ✅ Always include acceptance criteria — no exceptions.
- ✅ Always include the "do not make mistakes / verify before closing" warning.
- ❌ Do NOT rely on conversational context, prior messages, or "you know what I mean."
- ❌ Do NOT write vague descriptions like "fix the bug in live view" — name the file, the symptom, the fix, and how to verify it.

**Use `--acceptance` and `--validate` to enforce this:**

```bash
bd create "fix: deleteArmed not reset after failed delete" \
  --description="## Context
deleteSelectedTrip() in web/app/src/app/app.ts (~line 494) only resets deleteArmed on the success path. If deleteTrip() throws, the catch block calls applyRouteError but never resets deleteArmed, leaving the button stuck on 'Confirm delete?'.

## File
web/app/src/app/app.ts — deleteSelectedTrip(), catch block ~line 494

## What to do
Add this.deleteArmed.set(false) inside the catch block, after applyRouteError(error).

## Constraints
Do not change the success-path logic. Do not touch navigate() or loadTripDetail().

## Acceptance criteria
- After a simulated failed delete (network throttle / killed gateway), the button reverts to 'Delete selected' and the warning paragraph disappears.
- A subsequent delete requires two clicks again (re-arm).
- npm run build passes.

Do not make mistakes. Read the referenced files fully before editing. Verify the build compiles and all acceptance criteria pass before closing. If anything is ambiguous, stop and ask rather than guessing." \
  --acceptance="Button reverts after failed delete; re-arm required; build passes" \
  -t bug -p 2 --validate --json
```

Run `bd lint` to catch issues missing required sections.

**Complete work:**

```bash
bd close bd-42 --reason "Completed" --json
```

### Issue Types

- `bug` - Something broken
- `feature` - New functionality
- `task` - Work item (tests, docs, refactoring)
- `epic` - Large feature with subtasks
- `chore` - Maintenance (dependencies, tooling)

### Priorities

- `0` - Critical (security, data loss, broken builds)
- `1` - High (major features, important bugs)
- `2` - Medium (default, nice-to-have)
- `3` - Low (polish, optimization)
- `4` - Backlog (future ideas)

### Workflow for AI Agents

1. **Check ready work**: `bd ready` shows unblocked issues
2. **Claim your task atomically**: `bd update <id> --claim`
3. **Work on it**: Implement, test, document
4. **Discover new work?** Create linked issue:
   - `bd create "Found bug" --description="Details about what was found" -p 1 --deps discovered-from:<parent-id>`
5. **Complete**: `bd close <id> --reason "Done"`

### Auto-Sync

bd automatically syncs with git:

- Exports to `.beads/issues.jsonl` after changes (5s debounce)
- Imports from JSONL when newer (e.g., after `git pull`)
- No manual export/import needed!

### Important Rules

- ✅ Use bd for ALL task tracking
- ✅ Always use `--json` flag for programmatic use
- ✅ Link discovered work with `discovered-from` dependencies
- ✅ Check `bd ready` before asking "what should I work on?"
- ❌ Do NOT create markdown TODO lists
- ❌ Do NOT use external issue trackers
- ❌ Do NOT duplicate tracking systems

For more details, see README.md and docs/QUICKSTART.md.

## Landing the Plane (Session Completion)

**When ending a work session**, you MUST complete ALL steps below. Work is NOT complete until `git push` succeeds.

**MANDATORY WORKFLOW:**

1. **File issues for remaining work** - Create issues for anything that needs follow-up
2. **Run quality gates** (if code changed) - Tests, linters, builds
3. **Update issue status** - Close finished work, update in-progress items
4. **PUSH TO REMOTE** - This is MANDATORY:
   ```bash
   git pull --rebase
   bd sync
   git push
   git status  # MUST show "up to date with origin"
   ```
5. **Clean up** - Clear stashes, prune remote branches
6. **Verify** - All changes committed AND pushed
7. **Hand off** - Provide context for next session

**CRITICAL RULES:**
- Work is NOT complete until `git push` succeeds
- NEVER stop before pushing - that leaves work stranded locally
- NEVER say "ready to push when you are" - YOU must push
- If push fails, resolve and retry until it succeeds

<!-- END BEADS INTEGRATION -->

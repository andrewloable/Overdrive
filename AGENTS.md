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

## Device Deployment (BYD head unit @ 192.168.0.251:5555)

**ALWAYS stop all running BladeWatch daemons and uninstall the old app BEFORE
installing a new APK.** The shell-launched daemons (`byd_cam_daemon`,
`sentry_daemon`, `acc_sentry_daemon`, tunnels, etc.) run as detached
`app_process` processes that are NOT tied to the package manager — they survive
both `uninstall` and `install -r`, leaving stale daemons holding an old native
`.so` and lock files. A stale CameraDaemon will block the new build with
"Another CameraDaemon instance is already running" and the live view shows
"Camera unavailable".

See the **clean reinstall** block in `CLAUDE.md` (Build Commands → Install) for
the exact stop-daemons → uninstall → install sequence. Key points:
- Kill the `start_*.sh` watcher scripts FIRST, or they respawn the daemons.
- Use the grep bracket trick (`start_[c]am_daemon`) so the kill command does not
  match and terminate its own adb shell.
- `killall` is unreliable on this head unit (BYD toybox) — kill daemons by PID
  (enumerate with `ps -A -o PID,NAME,ARGS` then `kill -9 <pid>`), not `killall`.
- Killing daemons can briefly drop the ADB-over-TCP link — reconnect with an
  `until [ "$(adb ... get-state)" = device ]` loop before continuing.

### Clean up debug artifacts when done

Screenshots and pulled logs created while developing/debugging must be removed
from BOTH the device and the dev machine when finished — never leave them lying
around:
- **Device**: `adb -s 192.168.0.251:5555 shell rm -f /sdcard/*.png` (the
  `screencap -p` outputs land in `/sdcard` root) plus any pulled copies like
  `/sdcard/*.gz` / `/sdcard/*.jsonl*`. Do NOT touch `/sdcard/BladeWatch/**`
  (real recordings/trips) or `/sdcard/Pictures/**` (user media).
- **Dev machine**: remove the pulled screenshots/logs (e.g. `rm -f /tmp/bw_*.png
  /tmp/*.jsonl* /tmp/bwcfg.json` and any temp venv such as `/tmp/safeenv`).
- Never commit screenshots, pulled logs, or `*.jsonl.gz` telemetry dumps.

<!-- BEGIN BEADS INTEGRATION v:1 profile:minimal hash:7510c1e2 -->
## Beads Issue Tracker

This project uses **bd (beads)** for issue tracking. Run `bd prime` to see full workflow context and commands.

### Quick Reference

```bash
bd ready              # Find available work
bd show <id>          # View issue details
bd update <id> --claim  # Claim work
bd close <id>         # Complete work
```

### Rules

- Use `bd` for ALL task tracking — do NOT use TodoWrite, TaskCreate, or markdown TODO lists
- Run `bd prime` for detailed command reference and session close protocol
- Use `bd remember` for persistent knowledge — do NOT use MEMORY.md files

**Architecture in one line:** issues live in a local Dolt DB; sync uses `refs/dolt/data` on your git remote; `.beads/issues.jsonl` is a passive export. See https://github.com/gastownhall/beads/blob/main/docs/SYNC_CONCEPTS.md for details and anti-patterns.

## Session Completion

**When ending a work session**, you MUST complete ALL steps below. Work is NOT complete until `git push` succeeds.

**MANDATORY WORKFLOW:**

1. **File issues for remaining work** - Create issues for anything that needs follow-up
2. **Run quality gates** (if code changed) - Tests, linters, builds
3. **Update issue status** - Close finished work, update in-progress items
4. **PUSH TO REMOTE** - This is MANDATORY:
   ```bash
   git pull --rebase
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

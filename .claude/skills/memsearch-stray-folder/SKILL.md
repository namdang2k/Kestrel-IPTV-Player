---
name: memsearch-stray-folder
description: Clean up the stray "<drive letter> + U+F03A" folder the memsearch plugin sometimes creates in the project root (shows as "C\357\200\272/" in git status). Use when git status shows an unfamiliar folder named with a drive letter followed by a private-use-area character, or when memsearch memory files seem to have landed outside .memsearch\memory.
---

# Stray drive-letter folder (memsearch bug)

The memsearch plugin sometimes treats an absolute project path such as `<drive>:\...\.memsearch\...` as a relative path
and creates a folder named `<drive letter>` + U+F03A (Windows' stand-in for `:`) in the project root, holding the rest of
the path (`...\.memsearch\memory\*.md`). In `git status` it shows as `"C\357\200\272/"` (or another letter). It was first
seen in the old StbEmu project as `C` + U+F03A; none exists in this repo now, so check the real name before deleting.

Cause: `hooks/common.sh` in the plugin only treats `/...` as absolute, so `CLAUDE_PROJECT_DIR=C:\...` gets prefixed with
`$(pwd)/`. Patched locally in `~/.claude/plugins/cache/memsearch-plugins/memsearch/<version>/hooks/common.sh`:
`/*) ;;` → `/*|[A-Za-z]:*) ;;`. A plugin update overwrites the patch; if the folder reappears, re-apply it.

Cleanup: move the `.md` files into `.memsearch\memory\` (don't overwrite an existing date), then delete the folder.

**Never touch it from Git Bash** (`rm -rf C*`, `find C*/`, etc.): Git Bash renders the name as `C:/` and resolves it to
the real C: drive. Use PowerShell and find the folder by its U+F03A character:

```powershell
$bad = Get-ChildItem -Force -Directory | Where-Object { $_.Name.Contains([string][char]0xF03A) }
$bad | ForEach-Object { Get-ChildItem -LiteralPath $_.FullName -Recurse -Force }   # inspect first
$bad | ForEach-Object { Remove-Item -LiteralPath $_.FullName -Recurse -Force -Confirm:$false }
```

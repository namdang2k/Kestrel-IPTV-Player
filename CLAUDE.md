# CLAUDE.md

## Stray `C` folder (memsearch bug)

The memsearch plugin sometimes treats `C:\Unity Project\StbEmu\.memsearch\...` as a relative path and creates a folder
named `C` + U+F03A (Windows' stand-in for `:`) in the project root, holding `Unity Project\StbEmu\.memsearch\memory\*.md`.
In `git status` it shows as `"C\357\200\272/"`.

Cause: `hooks/common.sh` in the plugin only treats `/...` as absolute, so `CLAUDE_PROJECT_DIR=C:\...` gets prefixed with
`$(pwd)/`. Patched locally in `~/.claude/plugins/cache/memsearch-plugins/memsearch/<version>/hooks/common.sh`:
`/*) ;;` → `/*|[A-Za-z]:*) ;;`. A plugin update overwrites the patch; if the folder reappears, re-apply it.

Cleanup: move the `.md` files into `.memsearch\memory\` (don't overwrite an existing date), then delete the folder.

**Never touch it from Git Bash** (`rm -rf C*`, `find C*/`, etc.): Git Bash renders the name as `C:/` and resolves it to
the real C: drive. Use PowerShell with the exact name:

```powershell
$bad = Join-Path 'C:\Unity Project\StbEmu' ('C' + [char]0xF03A)
Get-ChildItem -LiteralPath $bad -Recurse -Force   # inspect first
Remove-Item -LiteralPath $bad -Recurse -Force -Confirm:$false
```

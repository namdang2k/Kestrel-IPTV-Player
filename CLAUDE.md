# CLAUDE.md

## Project

Unity 6000.6.3f1 Android app (Live TV from Xtream Codes servers) with a native Java layer in `Native/StbHost`.
`README.md` is the map of the current flows; read the section for the area you change before editing.

- Names inherited from StbEmu stay as they are: namespace `StbEmu.*`, Java package `com.stbemu.host`, `StbEmu/...`
  editor menus, and PlayerPrefs keys `StbEmu.*`. Renaming the keys resets users' monetization and ownership state.
- Java changes only reach the app through `Assets/Plugins/Android/StbHost.aar`. After editing `Native/StbHost`, run
  `Native/StbHost/gradlew.bat test copyToUnity --offline` (JDK 17 = Android Studio JBR, `JAVA_HOME`/`ANDROID_HOME` set)
  and commit the rebuilt AAR with the sources.
- C# tests are NUnit EditMode in `Assets/App/Tests/EditMode`. Headless run (Editor must be closed, no `-quit`):
  `Unity.exe -batchmode -nographics -projectPath . -runTests -testPlatform EditMode -testResults <file> -logFile <file>`.
  `Tools/Test-AdServices.ps1` is currently broken, so do not rely on it.
- Monetization timings are always read through `MonetizationController.Get(...)`, never straight from `AdMobSettings`.
  Changing `AdMobSettings` fields means updating `AdMobRemoteConfig` and the Remote Config JSON too (README, last section).
- Never log or send the profile password (Firebase, logcat, errors). `server_connect` carries `url` and `user` only.
- Docs are Vietnamese except the ones already in English. When behavior changes, update README and the related doc in the
  same change, and delete plans that are finished instead of leaving their checklists behind.

## Stray drive-letter folder (memsearch bug)

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

## Agent skills

### Issue tracker

Issues live in GitHub Issues for `namdang2k/Kestrel-IPTV-Player`, managed with the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

Uses the five default labels: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: one `CONTEXT.md` and `docs/adr/` at the repo root. See `docs/agents/domain.md`.

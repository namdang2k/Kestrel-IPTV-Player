# Streams logcat of the StbEmu app only (Unity Debug.Log, WebView/portal console, ExoPlayer, crashes).
#   powershell -File Tools/logcat.ps1                 # app already started (e.g. by "Build And Run Android (Dev)")
#   powershell -File Tools/logcat.ps1 -Install        # install Builds/StbEmu-dev.apk, launch, then stream
#   powershell -File Tools/logcat.ps1 | Select-String Smoke   # only PortalSmokeTest lines
param(
    [string]$Package = "com.DefaultCompany.StbEmu", # AndroidDevBuild logs the real one after each build
    [switch]$Install
)
$ErrorActionPreference = "Stop"
$adb = "C:\Program Files\Unity\Hub\Editor\6000.6.3f1\Editor\Data\PlaybackEngines\AndroidPlayer\SDK\platform-tools\adb.exe"

& $adb wait-for-device
if ($Install) {
    & $adb install -r "$PSScriptRoot\..\Builds\StbEmu-dev.apk"
    & $adb shell monkey -p $Package -c android.intent.category.LAUNCHER 1 | Out-Null
}

$appPid = ""
for ($i = 0; $i -lt 60 -and -not $appPid; $i++) {
    $appPid = (& $adb shell pidof $Package) -join ""
    if (-not $appPid) { Start-Sleep -Milliseconds 500 }
}
if (-not $appPid) { throw "$Package is not running. Start the app, or pass -Install." }

Write-Host "Streaming logcat for $Package (pid $appPid). Ctrl+C to stop."
& $adb logcat --pid=$appPid -v time

# Rebuilds Native/StbHost and copies the release AAR to Assets/Plugins/Android/StbHost.aar (gradle copyToUnity).
#   powershell -File Tools/build-stbhost.ps1          # build + copy
#   powershell -File Tools/build-stbhost.ps1 -Clean   # clean first
# Rebuild the Unity player afterwards; the AAR only reaches the app through a Unity build.
param(
    [switch]$Clean
)
$ErrorActionPreference = "Stop"

if (-not $env:JAVA_HOME) { $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr" }
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk" }
if (-not (Test-Path "$env:JAVA_HOME\bin\java.exe")) { throw "JAVA_HOME is invalid: $env:JAVA_HOME" }
if (-not (Test-Path $env:ANDROID_HOME)) { throw "ANDROID_HOME is invalid: $env:ANDROID_HOME" }

Push-Location "$PSScriptRoot\..\Native\StbHost"
try {
    $tasks = @("copyToUnity")
    if ($Clean) { $tasks = @("clean") + $tasks }
    & .\gradlew.bat @tasks
    if ($LASTEXITCODE -ne 0) { throw "gradle failed with exit code $LASTEXITCODE" }
} finally {
    Pop-Location
}

$aar = Get-Item "$PSScriptRoot\..\Assets\Plugins\Android\StbHost.aar"
Write-Host "OK: $($aar.FullName) ($([math]::Round($aar.Length / 1KB)) KB, $($aar.LastWriteTime))"

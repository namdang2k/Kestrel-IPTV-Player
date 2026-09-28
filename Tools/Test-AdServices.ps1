param([string]$UnityData = 'C:/Program Files/Unity/Hub/Editor/6000.6.3f1/Editor/Data')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Push-Location $projectRoot
try {
    New-Item -ItemType Directory -Path 'Temp/ServicesValidation' -Force | Out-Null
    & "$UnityData/MonoBleedingEdge/bin/mono.exe" "$UnityData/MonoBleedingEdge/lib/mono/4.5/csc.exe" /nologo /out:Temp/ServicesValidation/PolicyTests.exe Tools/Tests/AdServicesPolicyTests.cs Assets/App/Services/AdDisplayTiming.cs Assets/App/Services/AdCallbacks.cs Assets/App/Services/AppOpenEntryPolicy.cs
    if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed.' }
    & "$UnityData/MonoBleedingEdge/bin/mono.exe" Temp/ServicesValidation/PolicyTests.exe
    if ($LASTEXITCODE -ne 0) { throw 'Tests failed.' }
} finally { Pop-Location }

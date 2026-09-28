param(
    [string]$UnityData = 'C:/Program Files/Unity/Hub/Editor/6000.6.3f1/Editor/Data'
)

# Compatibility repair for the bundled Odin reflection bridge. No hooks are disabled.
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Add-Type -Path "$UnityData/Tools/BuildPipeline/Compilation/Unity.ILPP.Runner/Mono.Cecil.dll"
$dllPath = Join-Path $projectRoot 'Assets/Plugins/Sirenix/Assemblies/Sirenix.Reflection.Editor.dll'
$reader = New-Object Mono.Cecil.ReaderParameters
$reader.InMemory = $true
$bridge = [Mono.Cecil.AssemblyDefinition]::ReadAssembly($dllPath, $reader)
$unity = [Mono.Cecil.AssemblyDefinition]::ReadAssembly("$UnityData/Managed/UnityEngine/UnityEngine.UIElementsModule.dll")
try {
    $wrapper = $bridge.MainModule.Types | Where-Object FullName -eq 'Sirenix.Reflection.Editor.UIElementsUtility_Internals'
    if (!$wrapper) { throw 'Expected Odin wrapper not found; do not apply this patch to this version.' }
    $changes = 0
    foreach ($method in $wrapper.Methods) {
        foreach ($instruction in $method.Body.Instructions) {
            $member = $instruction.Operand
            if ($member -isnot [Mono.Cecil.MemberReference] -or
                $member.DeclaringType.FullName -ne 'UnityEngine.UIElements.UIElementsUtility') { continue }
            if ($member -is [Mono.Cecil.FieldReference] -and
                $member.Name -in @('s_BeginContainerCallback', 's_EndContainerCallback')) {
                $ownerName = 'UnityEngine.UIElements.UIElementsIMGUIUtility'
                $owner = $unity.MainModule.Types | Where-Object FullName -eq $ownerName
                $target = $owner.Fields | Where-Object Name -eq $member.Name
                if (!$target -or !$target.IsStatic -or $target.FieldType.FullName -ne $member.FieldType.FullName) {
                    throw "Unity callback signature mismatch: $($member.Name)"
                }
            } elseif ($member -is [Mono.Cecil.MethodReference] -and $member.Name -eq 'GetCurrentIMGUIContainer') {
                $ownerName = 'UnityEngine.UIElements.IMGUIContainer'
                $owner = $unity.MainModule.Types | Where-Object FullName -eq $ownerName
                $target = $owner.Methods | Where-Object { $_.Name -eq $member.Name -and $_.Parameters.Count -eq 0 }
                if (!$target -or !$target.IsStatic -or $target.ReturnType.FullName -ne $member.ReturnType.FullName) {
                    throw 'Unity GetCurrentIMGUIContainer signature mismatch.'
                }
            } else { throw "Unexpected old API reference: $member" }
            $member.DeclaringType = New-Object Mono.Cecil.TypeReference(
                $owner.Namespace, $owner.Name, $bridge.MainModule, $member.DeclaringType.Scope)
            $changes++
        }
    }
    if ($changes -eq 0) { Write-Output 'Already compatible; no changes.'; return }
    if ($changes -ne 3) { throw "Expected 3 unique member references, found $changes; original DLL is untouched." }
    $backupDir = Join-Path $projectRoot 'Temp/OdinUnity66Backup'
    New-Item -ItemType Directory -Path $backupDir -Force | Out-Null
    $backupPath = Join-Path $backupDir 'Sirenix.Reflection.Editor.dll.original'
    if (!(Test-Path -LiteralPath $backupPath)) { Copy-Item -LiteralPath $dllPath -Destination $backupPath }
    $outputPath = Join-Path $backupDir 'Sirenix.Reflection.Editor.dll.patched'
    $bridge.Write($outputPath)
    $check = [Mono.Cecil.AssemblyDefinition]::ReadAssembly($outputPath)
    $check.Dispose()
    Copy-Item -LiteralPath $outputPath -Destination $dllPath -Force
    Write-Output "Repaired $changes unique member references. Original backup: $backupPath"
} finally {
    $bridge.Dispose()
    $unity.Dispose()
}



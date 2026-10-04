param([string]$MinecraftDirectory='E:\.minecraft\versions\1.20.1-Fabric 0.16.10')
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$recordPath=Join-Path $projectRoot 'runtime\installation.json'
$record=Get-Content -LiteralPath $recordPath -Raw | ConvertFrom-Json
if(!$record.active){throw 'Install Passthrough with switch-sekiro.ps1 before updating.'}
function Assert-GamesClosed {
    if(Get-Process -Name sekiro,java,javaw -ErrorAction SilentlyContinue){throw 'Close Sekiro and Minecraft normally before replacing DLL/JNI.'}
}
Assert-GamesClosed
$packageRoot=Join-Path $projectRoot 'dist\SekiroCraft-Passthrough'
$manifest=Get-Content -LiteralPath (Join-Path $projectRoot 'build\verification.json') -Raw | ConvertFrom-Json
if(!$manifest.verified -or $manifest.patch -ne 'gameplay2-combatfix1'){throw 'Build and verify the paired combatfix1 package first.'}
$gameRoot=[IO.Path]::GetFullPath($record.gameDirectory)
$mcRoot=[IO.Path]::GetFullPath($MinecraftDirectory)
$dllTarget=Join-Path $gameRoot 'dinput8.dll'
$jarTarget=Join-Path $mcRoot 'mods\sekiro-minecraft-passthrough-0.1.0.jar'
$configTarget=Join-Path $gameRoot 'sekirobridge.ini'
$dllSource=Join-Path $packageRoot 'dinput8.dll'
$jarSource=Join-Path $packageRoot 'minecraft\sekiro-minecraft-passthrough-0.1.0.jar'
foreach($file in $record.files){
    if((Get-FileHash -LiteralPath (Join-Path $gameRoot $file.name)).Hash -ne $file.sha256){throw "Installed file changed: $($file.name)"}
}
foreach($source in @($dllSource,$jarSource)){
    $relative=[IO.Path]::GetRelativePath($packageRoot,$source)
    $expected=@($manifest.files | Where-Object name -EQ $relative)
    if($expected.Count -ne 1 -or (Get-FileHash -LiteralPath $source).Hash -ne $expected[0].sha256){throw "Verified package checksum mismatch: $relative"}
}
$original=@($record.files | Where-Object name -EQ 'dinput8.dll')[0]
if($original.originalHash -and (Get-FileHash -LiteralPath (Join-Path $record.backupRoot 'dinput8.dll')).Hash -ne $original.originalHash){throw 'Original DLL backup checksum mismatch.'}
$backupRoot=Join-Path $projectRoot ('runtime\combatfix1-update-backups\'+(Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $backupRoot -Force | Out-Null
Copy-Item -LiteralPath $dllTarget,$jarTarget,$configTarget,$recordPath -Destination $backupRoot
$settingsPath=Join-Path $gameRoot 'sekirocraft.ini'
$optionsPath=Join-Path $mcRoot 'options.txt'
$settingsHash=(Get-FileHash -LiteralPath $settingsPath).Hash
$optionsHash=(Get-FileHash -LiteralPath $optionsPath).Hash
$suffix=[Guid]::NewGuid().ToString('N')
$dllTemp=Join-Path $gameRoot ("bridge-update-$suffix.tmp")
$jarTemp=Join-Path $mcRoot ("mods\bridge-update-$suffix.tmp")
try {
    Copy-Item -LiteralPath $dllSource -Destination $dllTemp
    Copy-Item -LiteralPath $jarSource -Destination $jarTemp
    $dllHash=(Get-FileHash -LiteralPath $dllSource).Hash
    $jarHash=(Get-FileHash -LiteralPath $jarSource).Hash
    if((Get-FileHash -LiteralPath $dllTemp).Hash -ne $dllHash -or (Get-FileHash -LiteralPath $jarTemp).Hash -ne $jarHash){throw 'Staged checksum mismatch.'}
    Assert-GamesClosed
    Move-Item -LiteralPath $dllTemp -Destination $dllTarget -Force
    Move-Item -LiteralPath $jarTemp -Destination $jarTarget -Force
    if((Get-FileHash -LiteralPath $dllTarget).Hash -ne $dllHash -or (Get-FileHash -LiteralPath $jarTarget).Hash -ne $jarHash){throw 'Installed checksum mismatch.'}
    if((Get-FileHash -LiteralPath $settingsPath).Hash -ne $settingsHash -or (Get-FileHash -LiteralPath $optionsPath).Hash -ne $optionsHash){throw 'Settings changed during update.'}
    ($record.files | Where-Object name -EQ 'dinput8.dll').sha256=$dllHash
    $record.installedAt=(Get-Date).ToString('o')
    $record | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $recordPath -Encoding UTF8
    $report=@{patch=$manifest.patch;installedAt=$record.installedAt;gameLaunched=$false;backupRoot=$backupRoot;originalBackup=$record.backupRoot;originalConfigHash=$settingsHash;mcOptionsHash=$optionsHash;sourceCommit=$manifest.sourceCommit;files=@(Get-FileHash -LiteralPath $dllTarget,$jarTarget | Select-Object Path,Hash);pending=$manifest.pending}
    $report | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $projectRoot 'runtime\combatfix1-verification.json') -Encoding UTF8
    Write-Output "Paired combatfix1 update installed and verified. Backup: $backupRoot. No game launched."
} catch {
    Copy-Item -LiteralPath (Join-Path $backupRoot 'dinput8.dll') -Destination $dllTarget -Force
    Copy-Item -LiteralPath (Join-Path $backupRoot 'sekiro-minecraft-passthrough-0.1.0.jar') -Destination $jarTarget -Force
    Copy-Item -LiteralPath (Join-Path $backupRoot 'installation.json') -Destination $recordPath -Force
    throw
} finally {
    foreach($temp in @($dllTemp,$jarTemp)){if(Test-Path -LiteralPath $temp){Remove-Item -LiteralPath $temp}}
}

param([string]$MinecraftDirectory='E:\.minecraft\versions\1.20.1-Fabric 0.16.10',[switch]$Diagnostic)
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
if(!$manifest.verified -or $manifest.patch -notin @('gameplay2-combatfix1','gameplay3-life-actions-projectiles','gameplay4-gui-native-combat')){throw 'Build and verify a supported paired package first.'}
$guiPatch=$manifest.patch -eq 'gameplay4-gui-native-combat'
if($guiPatch -and $manifest.protocol -ne 2){throw 'Expected paired protocol v2.'}
if($Diagnostic -and !$guiPatch){throw 'Diagnostic mode requires the paired GUI/native combat package.'}
$gameRoot=[IO.Path]::GetFullPath($record.gameDirectory)
$mcRoot=[IO.Path]::GetFullPath($MinecraftDirectory)
if($manifest.gameSha256 -and (Get-FileHash -LiteralPath (Join-Path $gameRoot 'sekiro.exe')).Hash -ne $manifest.gameSha256){throw 'Verified Sekiro executable changed.'}
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
$tracePath=Join-Path $projectRoot 'runtime\combat-trace-session.json'
if(Test-Path -LiteralPath $tracePath){Copy-Item -LiteralPath $tracePath -Destination $backupRoot}
$traceStop=Join-Path $projectRoot 'runtime\combat-trace.stop'
$propertiesTarget=Join-Path $mcRoot 'sekirobridge\bridge.properties'
$hadProperties=Test-Path -LiteralPath $propertiesTarget
$hadTraceStop=Test-Path -LiteralPath $traceStop
if($hadProperties){Copy-Item -LiteralPath $propertiesTarget -Destination $backupRoot}
if($hadTraceStop){Copy-Item -LiteralPath $traceStop -Destination $backupRoot}
if(($guiPatch -or $manifest.patch -eq 'gameplay3-life-actions-projectiles') -and $manifest.gameSha256){
    $saveRoot=Join-Path $env:APPDATA 'Sekiro'
    if(Test-Path -LiteralPath $saveRoot){
        $saveBackup=Join-Path $backupRoot 'saves';New-Item -ItemType Directory -Path $saveBackup|Out-Null
        Get-ChildItem -LiteralPath $saveRoot -Recurse -File -Filter '*.sl2*'|ForEach-Object {
            $relative=[IO.Path]::GetRelativePath($saveRoot,$_.FullName);$target=Join-Path $saveBackup $relative
            New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($target)) -Force|Out-Null
            Copy-Item -LiteralPath $_.FullName -Destination $target
        }
    }
}
$settingsPath=Join-Path $gameRoot 'sekirocraft.ini'
$optionsPath=Join-Path $mcRoot 'options.txt'
$settingsHash=(Get-FileHash -LiteralPath $settingsPath).Hash
$optionsHash=(Get-FileHash -LiteralPath $optionsPath).Hash
$suffix=[Guid]::NewGuid().ToString('N')
$dllTemp=Join-Path $gameRoot ("bridge-update-$suffix.tmp")
$jarTemp=Join-Path $mcRoot ("mods\bridge-update-$suffix.tmp")
try {
    if($guiPatch -or $manifest.patch -eq 'gameplay3-life-actions-projectiles'){
        $ini=Get-Content -LiteralPath $configTarget -Raw
        if($ini -notmatch '(?im)^\[SekiroBridge\]'){throw 'Expected SekiroBridge INI section is missing.'}
        $ini=[regex]::Replace($ini,'(?is)(\[SekiroBridge\][^\r\n]*\r?\n)(.*?)(?=\r?\n\[|$)',{
            param($match)
            $body=[regex]::Replace($match.Groups[2].Value,'(?im)^\s*(combat_trace|native_hits|native_phase_finish)\s*=.*(?:\r?\n|$)','')
            $preview=[int][bool]$Diagnostic
            $match.Groups[1].Value+$body.TrimEnd()+"`r`ncombat_trace=$preview`r`nnative_hits=$preview`r`nnative_phase_finish=0`r`n"
        })
        Set-Content -LiteralPath $configTarget -Value $ini -Encoding UTF8
    }
    if($guiPatch){
        $properties=if($hadProperties){Get-Content -LiteralPath $propertiesTarget -Raw -Encoding Latin1}else{"channel=default`r`n"}
        $properties=[regex]::Replace($properties,'(?im)^\s*gui_trace\s*=.*(?:\r?\n|$)','')
        New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($propertiesTarget)) -Force|Out-Null
        Set-Content -LiteralPath $propertiesTarget -Value ($properties.TrimEnd()+"`r`ngui_trace="+$Diagnostic.ToString().ToLowerInvariant()) -Encoding Latin1
        if($Diagnostic -and $hadTraceStop){Remove-Item -LiteralPath $traceStop}
    }
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
    ($record.files | Where-Object name -EQ 'sekirobridge.ini').sha256=(Get-FileHash -LiteralPath $configTarget).Hash
    $record.installedAt=(Get-Date).ToString('o')
    $record | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $recordPath -Encoding UTF8
    if($Diagnostic){
        @{active=$true;patch=$manifest.patch;installedAt=$record.installedAt;diagnosticSha256=$dllHash;backup=$backupRoot;phaseFinishesEnabled=$false}|ConvertTo-Json -Depth 6|Set-Content -LiteralPath $tracePath -Encoding UTF8
    }elseif(($guiPatch -or $manifest.patch -eq 'gameplay3-life-actions-projectiles') -and (Test-Path -LiteralPath $tracePath)){
        $trace=Get-Content -LiteralPath $tracePath -Raw|ConvertFrom-Json
        $trace.active=$false;$trace|ConvertTo-Json -Depth 6|Set-Content -LiteralPath $tracePath -Encoding UTF8
    }
    $report=@{patch=$manifest.patch;diagnostic=[bool]$Diagnostic;installedAt=$record.installedAt;gameLaunched=$false;backupRoot=$backupRoot;originalBackup=$record.backupRoot;originalConfigHash=$settingsHash;mcOptionsHash=$optionsHash;sourceCommit=$manifest.sourceCommit;files=@(Get-FileHash -LiteralPath $dllTarget,$jarTarget | Select-Object Path,Hash);pending=$manifest.pending}
    $report | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $projectRoot 'runtime\combatfix1-verification.json') -Encoding UTF8
    Write-Output "Paired $($manifest.patch) update installed and verified. Backup: $backupRoot. No game launched."
} catch {
    Copy-Item -LiteralPath (Join-Path $backupRoot 'dinput8.dll') -Destination $dllTarget -Force
    Copy-Item -LiteralPath (Join-Path $backupRoot 'sekiro-minecraft-passthrough-0.1.0.jar') -Destination $jarTarget -Force
    Copy-Item -LiteralPath (Join-Path $backupRoot 'installation.json') -Destination $recordPath -Force
    Copy-Item -LiteralPath (Join-Path $backupRoot 'sekirobridge.ini') -Destination $configTarget -Force
    if(Test-Path -LiteralPath (Join-Path $backupRoot 'combat-trace-session.json')){
        Copy-Item -LiteralPath (Join-Path $backupRoot 'combat-trace-session.json') -Destination $tracePath -Force
    }elseif(Test-Path -LiteralPath $tracePath){Remove-Item -LiteralPath $tracePath}
    if($guiPatch){
        if($hadProperties){Copy-Item -LiteralPath (Join-Path $backupRoot 'bridge.properties') -Destination $propertiesTarget -Force}
        elseif(Test-Path -LiteralPath $propertiesTarget){Remove-Item -LiteralPath $propertiesTarget}
        if($hadTraceStop){Copy-Item -LiteralPath (Join-Path $backupRoot 'combat-trace.stop') -Destination $traceStop -Force}
    }
    throw
} finally {
    foreach($temp in @($dllTemp,$jarTemp)){if(Test-Path -LiteralPath $temp){Remove-Item -LiteralPath $temp}}
}

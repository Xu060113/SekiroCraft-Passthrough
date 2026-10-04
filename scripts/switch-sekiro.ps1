param([ValidateSet('Install','Restore')][string]$Action='Install',
      [string]$GameDirectory='D:\Steam\steamapps\common\Sekiro',
      [string]$OriginalProjectDirectory)
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$gameRoot=[IO.Path]::GetFullPath($GameDirectory).TrimEnd('\')
$runtimeRoot=Join-Path $projectRoot 'runtime'
$recordPath=Join-Path $runtimeRoot 'installation.json'
$packageRoot=Join-Path $projectRoot 'dist\SekiroCraft-Passthrough'
if(!(Test-Path -LiteralPath $packageRoot)){$packageRoot=$projectRoot}
if(!$OriginalProjectDirectory){$OriginalProjectDirectory=Join-Path $projectRoot '..\SekiroCraft-Original'}
if(Get-Process -Name sekiro -ErrorAction SilentlyContinue){throw 'Close Sekiro before switching.'}
$gameExe=Join-Path $gameRoot 'sekiro.exe'
if(!(Test-Path -LiteralPath $gameExe)-or(Get-FileHash -LiteralPath $gameExe).Hash -ne '637ACA527538C0EC6E1F136C8ED66046E95DFBDBB1F51926E134D9916398B856'){throw 'Unsupported Sekiro executable.'}
$dllTarget=Join-Path $gameRoot 'dinput8.dll'
$configTarget=Join-Path $gameRoot 'sekirobridge.ini'
function Save-Record($Record){
    $temp=Join-Path $runtimeRoot ('record-'+[Guid]::NewGuid().ToString('N')+'.tmp')
    $Record|ConvertTo-Json -Depth 6|Set-Content -LiteralPath $temp -Encoding UTF8
    Move-Item -LiteralPath $temp -Destination $recordPath -Force
}
function Assert-Record($Record){
    if($Record.gameDirectory -ne $gameRoot){throw 'Installation belongs to a different game directory.'}
    $resolvedBackup=[IO.Path]::GetFullPath($Record.backupRoot)
    if(!$resolvedBackup.StartsWith([IO.Path]::GetFullPath($runtimeRoot)+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'Invalid backup directory.'}
    foreach($file in $Record.files){
        if($file.name -notin @('dinput8.dll','sekirobridge.ini')){throw 'Invalid owned filename.'}
        $target=Join-Path $gameRoot $file.name
        if(!(Test-Path -LiteralPath $target)-or(Get-FileHash -LiteralPath $target).Hash -ne $file.sha256){throw "File changed after installation; preserve it before restoring: $target"}
        if($file.originalHash){$backup=Join-Path $resolvedBackup $file.name;if(!(Test-Path -LiteralPath $backup)-or(Get-FileHash -LiteralPath $backup).Hash -ne $file.originalHash){throw 'Original backup failed checksum validation.'}}
    }
}
function Atomic-Copy([string]$Source,[string]$Target){
    $temp=Join-Path $gameRoot ('sekirobridge-'+[Guid]::NewGuid().ToString('N')+'.tmp')
    try{Copy-Item -LiteralPath $Source -Destination $temp;Move-Item -LiteralPath $temp -Destination $Target -Force}
    finally{if(Test-Path -LiteralPath $temp){Remove-Item -LiteralPath $temp -Force}}
}
if($Action -eq 'Restore'){
    if(!(Test-Path -LiteralPath $recordPath)){throw 'No passthrough installation record.'}
    $record=Get-Content -LiteralPath $recordPath -Raw|ConvertFrom-Json
    if(!$record.active){Write-Output 'Original installation is already restored.';return}
    Assert-Record $record
    foreach($file in $record.files){$target=Join-Path $gameRoot $file.name;if($file.originalHash){Atomic-Copy (Join-Path $record.backupRoot $file.name) $target}else{Remove-Item -LiteralPath $target -Force}}
    $record.active=$false;Save-Record $record;Write-Output 'Restored the exact original DLL; game saves and sekirocraft.ini were not modified.';return
}
if((Test-Path -LiteralPath $recordPath)-and(Get-Content -LiteralPath $recordPath -Raw|ConvertFrom-Json).active){throw 'Passthrough is installed. Restore it before replacing or updating the build.'}
$dllSource=Join-Path $packageRoot 'dinput8.dll'
if(!(Test-Path -LiteralPath $dllSource)){throw 'Build the host DLL first.'}
if(Test-Path -LiteralPath $configTarget){throw 'An unowned sekirobridge.ini already exists.'}
$originalHash=$null
if(Test-Path -LiteralPath $dllTarget){
    $originalHash=(Get-FileHash -LiteralPath $dllTarget).Hash
    $allowedHashes=@('F2334BD3EF97D6AC40273D84731CBDF5B8F66365A37E7040384EE911C98A9039','D3946D762A75763084042F34B8B0771EB21DD1A1E34601D197021AD98D617828')
    $originalRelease=Join-Path $OriginalProjectDirectory 'dist\SekiroCraft\dinput8.dll'
    if(Test-Path -LiteralPath $originalRelease){$allowedHashes+=(Get-FileHash -LiteralPath $originalRelease).Hash}
    if($originalHash -notin $allowedHashes){throw 'An unrecognized dinput8.dll is present; it will not be overwritten.'}
}
New-Item -ItemType Directory -Path $runtimeRoot -Force|Out-Null
$backupRoot=Join-Path $runtimeRoot ('install-backups\'+(Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $backupRoot -Force|Out-Null
if($originalHash){Copy-Item -LiteralPath $dllTarget -Destination (Join-Path $backupRoot 'dinput8.dll')}
$configSource=Join-Path $backupRoot 'new-sekirobridge.ini'
$template=Get-Content -LiteralPath (Join-Path $packageRoot 'sekirobridge.ini') -Raw
($template.Trim()+"`r`ndata_root=$runtimeRoot`r`n")|Set-Content -LiteralPath $configSource -Encoding Unicode
$record=@{gameDirectory=$gameRoot;backupRoot=$backupRoot;active=$true;installedAt=(Get-Date).ToString('o');files=@(
    @{name='dinput8.dll';sha256=(Get-FileHash -LiteralPath $dllSource).Hash;originalHash=$originalHash},
    @{name='sekirobridge.ini';sha256=(Get-FileHash -LiteralPath $configSource).Hash;originalHash=$null})}
Save-Record $record
try{Atomic-Copy $dllSource $dllTarget;Atomic-Copy $configSource $configTarget;Assert-Record $record}
catch{
    if($originalHash){Atomic-Copy (Join-Path $backupRoot 'dinput8.dll') $dllTarget}
    elseif((Test-Path -LiteralPath $dllTarget)-and(Get-FileHash -LiteralPath $dllTarget).Hash -eq $record.files[0].sha256){Remove-Item -LiteralPath $dllTarget}
    if((Test-Path -LiteralPath $configTarget)-and(Get-FileHash -LiteralPath $configTarget).Hash -eq $record.files[1].sha256){Remove-Item -LiteralPath $configTarget}
    $record.active=$false;Save-Record $record;throw
}
Write-Output 'Installed the dual-process host. Start both games yourself when ready; this script never launches them.'
Write-Output "Original DLL backup: $backupRoot"

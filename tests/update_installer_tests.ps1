$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$testRoot=Join-Path $root ('.cache\update-installer-tests\'+[Guid]::NewGuid().ToString('N'))
$checks=0
function Check($ok,$label){$script:checks++;if(!$ok){throw $label}}
function Fixture([string]$name){
    $dir=Join-Path $testRoot $name
    $game=Join-Path $dir 'game';$mc=Join-Path $dir 'mc';$original=Join-Path $dir 'original'
    $package=Join-Path $dir 'dist\SekiroCraft-Passthrough'
    New-Item -ItemType Directory -Path "$dir\scripts","$dir\runtime","$dir\build",$game,"$mc\mods",$original,"$package\minecraft" -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $root 'scripts\update-installed.ps1') -Destination "$dir\scripts"
    Set-Content -LiteralPath "$game\dinput8.dll" -Value 'old host'
    Set-Content -LiteralPath "$original\dinput8.dll" -Value 'original host'
    Set-Content -LiteralPath "$game\sekirobridge.ini" -Value 'custom bridge settings'
    Set-Content -LiteralPath "$game\sekirocraft.ini" -Value 'original settings'
    Set-Content -LiteralPath "$mc\options.txt" -Value 'MC options'
    Set-Content -LiteralPath "$mc\mods\sekiro-minecraft-passthrough-0.1.0.jar" -Value 'old MC'
    Set-Content -LiteralPath "$package\dinput8.dll" -Value 'new host'
    Set-Content -LiteralPath "$package\minecraft\sekiro-minecraft-passthrough-0.1.0.jar" -Value 'new MC'
    $record=@{active=$true;gameDirectory=$game;backupRoot=$original;installedAt='fixture';files=@(@{name='dinput8.dll';sha256=(Get-FileHash "$game\dinput8.dll").Hash;originalHash=(Get-FileHash "$original\dinput8.dll").Hash},@{name='sekirobridge.ini';sha256=(Get-FileHash "$game\sekirobridge.ini").Hash;originalHash=$null})}
    $record | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath "$dir\runtime\installation.json"
    $manifest=@{verified=$true;patch='gameplay2-combatfix1';sourceCommit='fixture';pending=@('real-game acceptance');files=@(@{name='dinput8.dll';sha256=(Get-FileHash "$package\dinput8.dll").Hash},@{name='minecraft\sekiro-minecraft-passthrough-0.1.0.jar';sha256=(Get-FileHash "$package\minecraft\sekiro-minecraft-passthrough-0.1.0.jar").Hash})}
    $manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath "$dir\build\verification.json"
    return @{dir=$dir;game=$game;mc=$mc;package=$package;record=$record}
}
$f=Fixture 'success'
& "$($f.dir)\scripts\update-installed.ps1" -MinecraftDirectory $f.mc | Out-Null
Check ((Get-Content "$($f.game)\dinput8.dll") -eq 'new host') 'paired host update'
Check ((Get-Content "$($f.mc)\mods\sekiro-minecraft-passthrough-0.1.0.jar") -eq 'new MC') 'paired MC update'
$r=Get-Content "$($f.dir)\runtime\installation.json" -Raw | ConvertFrom-Json
Check ($r.files[0].sha256 -eq (Get-FileHash "$($f.game)\dinput8.dll").Hash) 'restore record updated to installed DLL'
Check ($r.backupRoot -eq $f.record.backupRoot) 'original restore backup preserved'
Check ((Get-Content "$($f.game)\sekirobridge.ini") -eq 'custom bridge settings') 'custom bridge config preserved'
$report=Get-Content "$($f.dir)\runtime\combatfix1-verification.json" -Raw | ConvertFrom-Json
Check ((Get-Content "$($report.backupRoot)\dinput8.dll") -eq 'old host') 'previous host backed up'
Check ((Get-Content "$($report.backupRoot)\sekiro-minecraft-passthrough-0.1.0.jar") -eq 'old MC') 'previous JAR backed up'
$f=Fixture 'rollback'
function Move-Item {
    param([string]$LiteralPath,[string]$Destination,[switch]$Force)
    if($Destination.EndsWith('sekiro-minecraft-passthrough-0.1.0.jar')){throw 'forced second-peer write failure'}
    Microsoft.PowerShell.Management\Move-Item @PSBoundParameters
}
$failed=$false
try{& "$($f.dir)\scripts\update-installed.ps1" -MinecraftDirectory $f.mc | Out-Null}catch{$failed=$true}
Remove-Item Function:\Move-Item
Check $failed 'second-peer failure propagates'
Check ((Get-Content "$($f.game)\dinput8.dll") -eq 'old host') 'rollback restores host after JAR failure'
Check ((Get-Content "$($f.mc)\mods\sekiro-minecraft-passthrough-0.1.0.jar") -eq 'old MC') 'rollback retains paired previous JAR'
$r=Get-Content "$($f.dir)\runtime\installation.json" -Raw | ConvertFrom-Json
Check ($r.files[0].sha256 -eq $f.record.files[0].sha256) 'rollback restores original restore record'
$f=Fixture 'tampered-package'
Set-Content -LiteralPath "$($f.package)\dinput8.dll" -Value 'tampered'
$failed=$false
try{& "$($f.dir)\scripts\update-installed.ps1" -MinecraftDirectory $f.mc | Out-Null}catch{$failed=$true}
Check $failed 'unverified package rejected'
Check ((Get-Content "$($f.game)\dinput8.dll") -eq 'old host') 'checksum failure leaves installed host unchanged'
Write-Output "$checks paired update, checksum, backup and rollback checks passed (isolated fake directories)."

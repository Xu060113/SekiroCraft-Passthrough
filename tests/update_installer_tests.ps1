$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$testRoot=Join-Path $root ('.cache\update-installer-tests\'+[Guid]::NewGuid().ToString('N'))
$checks=0
# The fixtures never replace a running game's files. Isolate their process guard
# from a developer's unrelated Gradle JVM or game session.
function Get-Process {param($Name,$ErrorAction);return @()}
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
function LifeFixture([string]$name){
    $f=Fixture $name
    Set-Content -LiteralPath "$($f.game)\sekirobridge.ini" -Value "[SekiroBridge]`nenabled=1`ny_offset=137`ndata_root=custom/path`ncombat_trace=1`nnative_hits=1"
    $r=Get-Content "$($f.dir)\runtime\installation.json" -Raw|ConvertFrom-Json
    $r.files[1].sha256=(Get-FileHash "$($f.game)\sekirobridge.ini").Hash
    $r|ConvertTo-Json -Depth 6|Set-Content "$($f.dir)\runtime\installation.json"
    $manifest=Get-Content "$($f.dir)\build\verification.json" -Raw|ConvertFrom-Json
    $manifest.patch='gameplay3-life-actions-projectiles';$manifest|ConvertTo-Json -Depth 6|Set-Content "$($f.dir)\build\verification.json"
    @{active=$true}|ConvertTo-Json|Set-Content "$($f.dir)\runtime\combat-trace-session.json"
    return $f
}
$f=LifeFixture 'life-success'
& "$($f.dir)\scripts\update-installed.ps1" -MinecraftDirectory $f.mc|Out-Null
$ini=Get-Content "$($f.game)\sekirobridge.ini" -Raw
Check ($ini -match 'y_offset=137' -and $ini -match 'data_root=custom/path') 'life update preserves calibrated config'
Check ($ini -match 'combat_trace=0' -and $ini -match 'native_hits=0' -and $ini -notmatch 'combat_trace=1') 'life update disables recorder and unaccepted preview hits'
$r=Get-Content "$($f.dir)\runtime\installation.json" -Raw|ConvertFrom-Json
Check ($r.files[1].sha256 -eq (Get-FileHash "$($f.game)\sekirobridge.ini").Hash) 'config restore ownership updated'
Check (!(Get-Content "$($f.dir)\runtime\combat-trace-session.json" -Raw|ConvertFrom-Json).active) 'trace session retired'
$f=LifeFixture 'life-rollback';$before=(Get-FileHash "$($f.game)\sekirobridge.ini").Hash
function Move-Item {
    param([string]$LiteralPath,[string]$Destination,[switch]$Force)
    if($Destination.EndsWith('sekiro-minecraft-passthrough-0.1.0.jar')){throw 'forced JAR failure'}
    Microsoft.PowerShell.Management\Move-Item @PSBoundParameters
}
$failed=$false;try{& "$($f.dir)\scripts\update-installed.ps1" -MinecraftDirectory $f.mc|Out-Null}catch{$failed=$true}
Remove-Item Function:\Move-Item
Check ($failed -and (Get-FileHash "$($f.game)\sekirobridge.ini").Hash -eq $before) 'failure rolls config back with both peers'
Check ((Get-Content "$($f.dir)\runtime\combat-trace-session.json" -Raw|ConvertFrom-Json).active) 'failed update retains prior trace session'
Write-Output "$checks total paired-update and life/action rollback checks passed."
function GuiFixture([string]$name){
    $f=LifeFixture $name
    $manifest=Get-Content "$($f.dir)\build\verification.json" -Raw|ConvertFrom-Json
    $manifest.patch='gameplay4-gui-native-combat'
    $manifest|Add-Member -NotePropertyName protocol -NotePropertyValue 2
    $manifest|ConvertTo-Json -Depth 6|Set-Content "$($f.dir)\build\verification.json"
    New-Item -ItemType Directory -Path "$($f.mc)\sekirobridge" -Force|Out-Null
    Set-Content "$($f.mc)\sekirobridge\bridge.properties" "channel=custom`ngui_trace=false"
    Set-Content "$($f.dir)\runtime\combat-trace.stop" 'previous session'
    return $f
}
$f=GuiFixture 'gui-diagnostic'
& "$($f.dir)\scripts\update-installed.ps1" -MinecraftDirectory $f.mc -Diagnostic|Out-Null
$ini=Get-Content "$($f.game)\sekirobridge.ini" -Raw
Check ($ini -match 'combat_trace=1' -and $ini -match 'native_hits=1' -and $ini -match 'native_phase_finish=0') 'diagnostic enables recording and normal hits but keeps unaccepted phase profile off'
Check ((Get-Content "$($f.mc)\sekirobridge\bridge.properties" -Raw) -match 'channel=custom[\s\S]*gui_trace=true') 'GUI diagnostic retains channel and enables bounded tracing'
Check (!(Test-Path "$($f.dir)\runtime\combat-trace.stop")) 'old stop marker does not silently stop new capture'
Check ((Get-Content "$($f.dir)\runtime\combat-trace-session.json" -Raw|ConvertFrom-Json).active) 'diagnostic session active'
$f=GuiFixture 'gui-rollback'
function Move-Item {
    param([string]$LiteralPath,[string]$Destination,[switch]$Force)
    if($Destination.EndsWith('sekiro-minecraft-passthrough-0.1.0.jar')){throw 'forced GUI package JAR failure'}
    Microsoft.PowerShell.Management\Move-Item @PSBoundParameters
}
$failed=$false;try{& "$($f.dir)\scripts\update-installed.ps1" -MinecraftDirectory $f.mc -Diagnostic|Out-Null}catch{$failed=$true}
Remove-Item Function:\Move-Item
Check ($failed -and (Get-Content "$($f.game)\dinput8.dll") -eq 'old host') 'diagnostic rollback restores host'
Check ((Get-Content "$($f.mc)\sekirobridge\bridge.properties" -Raw) -match 'gui_trace=false') 'diagnostic rollback restores GUI properties'
Check (Test-Path "$($f.dir)\runtime\combat-trace.stop") 'diagnostic rollback restores trace stop marker'
$f=GuiFixture 'gui-wrong-protocol'
$manifest=Get-Content "$($f.dir)\build\verification.json" -Raw|ConvertFrom-Json
$manifest.protocol=1;$manifest|ConvertTo-Json -Depth 6|Set-Content "$($f.dir)\build\verification.json"
$failed=$false;try{& "$($f.dir)\scripts\update-installed.ps1" -MinecraftDirectory $f.mc -Diagnostic|Out-Null}catch{$failed=$true}
Check ($failed -and (Get-Content "$($f.game)\dinput8.dll") -eq 'old host') 'old protocol rejected before mutation'
Write-Output "$checks total paired-update, diagnostic and rollback checks passed."
$f=GuiFixture 'mapped-grapple'
& "$($f.dir)\scripts\update-installed.ps1" -MinecraftDirectory $f.mc -Diagnostic -NativeGrappleKey m|Out-Null
$ini=Get-Content "$($f.game)\sekirobridge.ini" -Raw
Check ($ini -match '(?m)^grapple_key=M\r?$' -and $ini -match 'y_offset=137' -and $ini -match 'data_root=custom/path') 'paired update saves user M binding without losing calibration'
$r=Get-Content "$($f.dir)\runtime\installation.json" -Raw|ConvertFrom-Json
Check ($r.files[1].sha256 -eq (Get-FileHash "$($f.game)\sekirobridge.ini").Hash) 'mapped key remains covered by installation checksum'
$f=GuiFixture 'invalid-grapple';$before=(Get-FileHash "$($f.game)\sekirobridge.ini").Hash
$failed=$false;try{& "$($f.dir)\scripts\update-installed.ps1" -MinecraftDirectory $f.mc -Diagnostic -NativeGrappleKey 'MM'|Out-Null}catch{$failed=$true}
Check ($failed -and (Get-FileHash "$($f.game)\sekirobridge.ini").Hash -eq $before) 'invalid native binding fails before touching installed files'
Write-Output "$checks total paired-update, diagnostic, mapped grapple and rollback checks passed."
$f=GuiFixture 'auto-boss'
$manifest=Get-Content "$($f.dir)\build\verification.json" -Raw|ConvertFrom-Json
$manifest|Add-Member -NotePropertyName experimental -NotePropertyValue @('HP-depleted Boss stage mode')
$manifest|ConvertTo-Json -Depth 6|Set-Content "$($f.dir)\build\verification.json"
& "$($f.dir)\scripts\update-installed.ps1" -MinecraftDirectory $f.mc -AutoBossPhases -NativeGrappleKey M|Out-Null
$ini=Get-Content "$($f.game)\sekirobridge.ini" -Raw
Check ($ini -match 'auto_boss_phases=1' -and $ini -match 'native_hits=1' -and $ini -match 'native_phase_finish=0') 'explicit HP-stage option enables supported combat backend without legacy remote profile'
$f=GuiFixture 'unsupported-auto-boss';$before=(Get-FileHash "$($f.game)\sekirobridge.ini").Hash
$failed=$false;try{& "$($f.dir)\scripts\update-installed.ps1" -MinecraftDirectory $f.mc -AutoBossPhases|Out-Null}catch{$failed=$true}
Check ($failed -and (Get-FileHash "$($f.game)\sekirobridge.ini").Hash -eq $before) 'unsupported auto-stage package fails before mutations'
Write-Output "$checks total paired-update and explicit HP-stage checks passed."

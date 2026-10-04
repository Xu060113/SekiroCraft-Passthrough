$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$fixtureRoot=Join-Path $projectRoot '.cache\installer-fixtures'
$id=[Guid]::NewGuid().ToString('N')
$fixtureProject=Join-Path $fixtureRoot "project-$id"
$fixtureGame=Join-Path $fixtureRoot "game-$id"
New-Item -ItemType Directory -Path "$fixtureProject\scripts",$fixtureGame -Force|Out-Null
Copy-Item -LiteralPath "$projectRoot\scripts\switch-sekiro.ps1" -Destination "$fixtureProject\scripts\switch-sekiro.ps1"
Copy-Item -LiteralPath "$projectRoot\dist\SekiroCraft-Passthrough\dinput8.dll","$projectRoot\dist\SekiroCraft-Passthrough\sekirobridge.ini" -Destination $fixtureProject
# Read-only copy into an ignored test fixture, never started or distributed.
Copy-Item -LiteralPath 'D:\Steam\steamapps\common\Sekiro\sekiro.exe' -Destination "$fixtureGame\sekiro.exe"
$originalDll=[IO.Path]::GetFullPath((Join-Path $projectRoot '..\SekiroCraft-Original\dist\SekiroCraft\dinput8.dll'))
Copy-Item -LiteralPath $originalDll -Destination "$fixtureGame\dinput8.dll"
'original settings remain byte-identical'|Set-Content -LiteralPath "$fixtureGame\sekirocraft.ini"
$originalHash=(Get-FileHash -LiteralPath "$fixtureGame\dinput8.dll").Hash
$settingsHash=(Get-FileHash -LiteralPath "$fixtureGame\sekirocraft.ini").Hash
$installer=Join-Path $fixtureProject 'scripts\switch-sekiro.ps1'
& $installer -GameDirectory $fixtureGame -Action Install
if((Get-FileHash -LiteralPath "$fixtureGame\dinput8.dll").Hash -ne (Get-FileHash -LiteralPath "$fixtureProject\dinput8.dll").Hash){throw 'Fixture DLL not installed'}
if((Get-FileHash -LiteralPath "$fixtureGame\sekirocraft.ini").Hash -ne $settingsHash){throw 'Original config modified'}
'a different mod was installed after bridge'|Set-Content -LiteralPath "$fixtureGame\dinput8.dll"
$refused=$false;try{& $installer -GameDirectory $fixtureGame -Action Restore}catch{$refused=$true}
if(!$refused){throw 'Restore overwrote an unowned modified file'}
Copy-Item -LiteralPath "$fixtureProject\dinput8.dll" -Destination "$fixtureGame\dinput8.dll" -Force
& $installer -GameDirectory $fixtureGame -Action Restore
if((Get-FileHash -LiteralPath "$fixtureGame\dinput8.dll").Hash -ne $originalHash){throw 'Original DLL was not restored exactly'}
if(Test-Path -LiteralPath "$fixtureGame\sekirobridge.ini"){throw 'Owned bridge config left behind'}
if((Get-FileHash -LiteralPath "$fixtureGame\sekirocraft.ini").Hash -ne $settingsHash){throw 'Restore touched original config'}
'foreign DLL'|Set-Content -LiteralPath "$fixtureGame\dinput8.dll"
$refused=$false;try{& $installer -GameDirectory $fixtureGame -Action Install}catch{$refused=$true}
if(!$refused){throw 'Install overwrote a foreign DLL'}
Write-Output 'PASS isolated installer fixture: original backup, exact restore, original config preserved and foreign files refused. No game launched.'

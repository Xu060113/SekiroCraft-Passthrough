param([switch]$Offline,[switch]$SkipTests)
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
& "$PSScriptRoot\build-native.ps1" -SkipTests:$SkipTests
if($LASTEXITCODE){throw 'Native build failed'}
$gradle=Join-Path $projectRoot '.cache\gradle-8.8\bin\gradle.bat'
if(!(Test-Path -LiteralPath $gradle)){throw 'Run scripts/bootstrap.ps1 first.'}
$gradleArgs=@('--gradle-user-home',"$projectRoot\.cache\gradle-home",'--no-daemon','-p',"$projectRoot\mc")
if($Offline){$gradleArgs+='--offline'}
$gradleTasks=@('build');if(!$SkipTests){$gradleTasks+='terrainTest'}
& $gradle @gradleArgs @gradleTasks
if($LASTEXITCODE){throw 'Minecraft build failed'}
if(!$SkipTests){
    & "$PSScriptRoot\verify-java.ps1";if($LASTEXITCODE){throw 'Java/native verification failed'}
    & "$projectRoot\tests\update_installer_tests.ps1"
}
$packageRoot=Join-Path $projectRoot 'dist\SekiroCraft-Passthrough'
New-Item -ItemType Directory -Path "$packageRoot\minecraft","$packageRoot\docs","$packageRoot\scripts" -Force|Out-Null
Copy-Item -LiteralPath "$projectRoot\mc\build\libs\sekiro-minecraft-passthrough-0.1.0.jar" -Destination "$packageRoot\minecraft\sekiro-minecraft-passthrough-0.1.0.jar" -Force
$apiJar=Get-ChildItem -LiteralPath "$projectRoot\.cache\gradle-home\caches\modules-2\files-2.1\net.fabricmc.fabric-api\fabric-api\0.92.2+1.20.1" -Recurse -File -Filter '*.jar'|Where-Object Name -NotLike '*sources*'|Select-Object -First 1
if(!$apiJar){throw 'Pinned Fabric API JAR not found'}
Copy-Item -LiteralPath $apiJar.FullName -Destination "$packageRoot\minecraft\fabric-api-0.92.2+1.20.1.jar" -Force
Copy-Item -LiteralPath "$projectRoot\README.md","$projectRoot\THIRD_PARTY_NOTICES.md" -Destination $packageRoot -Force
Copy-Item -LiteralPath "$projectRoot\docs\RETURN_TEST.md" -Destination "$packageRoot\docs\RETURN_TEST.md" -Force
Copy-Item -LiteralPath "$projectRoot\docs\PROTOCOL.md" -Destination "$packageRoot\docs\PROTOCOL.md" -Force
Copy-Item -LiteralPath "$projectRoot\docs\FEATURE_TEST.md" -Destination "$packageRoot\docs\FEATURE_TEST.md" -Force
Copy-Item -LiteralPath "$projectRoot\docs\REFERENCE_DESIGN.md" -Destination "$packageRoot\docs\REFERENCE_DESIGN.md" -Force
Copy-Item -LiteralPath "$projectRoot\docs\GAMEPLAY_TEST.md" -Destination "$packageRoot\docs\GAMEPLAY_TEST.md" -Force
Copy-Item -LiteralPath "$projectRoot\docs\COMBATFIX1_TEST.md" -Destination "$packageRoot\docs\COMBATFIX1_TEST.md" -Force
Copy-Item -LiteralPath "$projectRoot\docs\GUI_NATIVE_COMBAT_TEST.md" -Destination "$packageRoot\docs\GUI_NATIVE_COMBAT_TEST.md" -Force
Copy-Item -LiteralPath "$projectRoot\docs\OGRE_GRAPPLE_TEST.md" -Destination "$packageRoot\docs\OGRE_GRAPPLE_TEST.md" -Force
Copy-Item -LiteralPath "$projectRoot\docs\HP_STAGE_TRAVERSAL_TEST.md" -Destination "$packageRoot\docs\HP_STAGE_TRAVERSAL_TEST.md" -Force
Copy-Item -LiteralPath "$projectRoot\docs\NATIVE_COMBAT_TRACE.md" -Destination "$packageRoot\docs\NATIVE_COMBAT_TRACE.md" -Force
Copy-Item -LiteralPath "$projectRoot\docs\LIFE_ACTION_PROJECTILE_TEST.md" -Destination "$packageRoot\docs\LIFE_ACTION_PROJECTILE_TEST.md" -Force
Copy-Item -LiteralPath "$projectRoot\licenses\SekiroTool-LICENSE.txt" -Destination "$packageRoot\SekiroTool-LICENSE.txt" -Force
Copy-Item -LiteralPath "$PSScriptRoot\switch-sekiro.ps1","$PSScriptRoot\prepare-minecraft.ps1","$PSScriptRoot\update-installed.ps1" -Destination "$packageRoot\scripts" -Force
$workspaceRoot=[IO.Path]::GetFullPath((Join-Path $projectRoot '..\..'))
$dependencyRoot=Join-Path $projectRoot 'third_party'
if(!(Test-Path -LiteralPath $dependencyRoot)){$dependencyRoot=Join-Path $workspaceRoot 'third_party'}
Copy-Item -LiteralPath "$dependencyRoot\minhook\LICENSE.txt" -Destination "$packageRoot\MinHook-LICENSE.txt" -Force
Copy-Item -LiteralPath "$dependencyRoot\imgui\LICENSE.txt" -Destination "$packageRoot\ImGui-LICENSE.txt" -Force
$sourceCommit=(& git -C $projectRoot rev-parse HEAD).Trim()
$sourceDirty=[bool](& git -C $projectRoot status --porcelain)
$manifest=@{version='0.1.0';protocol=2;sourceCommit=$sourceCommit;sourceDirty=$sourceDirty;patch='gameplay4-gui-native-combat';gameSha256='637ACA527538C0EC6E1F136C8ED66046E95DFBDBB1F51926E134D9916398B856';gameLaunched=$false;installed=$false;fullPort=$false;verified=(-not $SkipTests);capabilities=@('MC player control','GUI events paired with displayed frame generation and viewport','paired ABI v2 guard','F6 native menu input routing with Esc entry','native UI pauses MC and combat delivery','completed frame camera pairing','isolated deferred scene metadata','depth-composite','independent fresh overlay','native ground block placement','background MC audio','damage source, impact and actor stage transport');experimental=@('HP-depleted Boss stage mode','held native grapple traversal with run/jump','spawn eggs on sampled native ground','read-only animation-state action handoff without fixed 6.5s delay','native G grapple and R attack/deathblow button','MC follows native root motion without Wolf mesh','bounded native projectile segment ray queries','native actor lodged-arrow rendering','nearby MC entity ground collision','local human-size NPC block sweeps','opt-in native normal-hit backend','disabled candidate native phase finish profile','bidirectional player health delta ledger','owned native player creative NoDamage');pending=@('live GUI slot and native menu acceptance','live animation pointer ownership and finisher completion acceptance','MC-driven Boss eligibility and stage finish including scripted gates and final rewards','remote automatic native phase profile acceptance (default disabled)','full native weapon-specific hit profiles, knockback and deflection','exact large Boss projectile/body geometry','full native terrain collision geometry','Havok block bodies and navigation','native NPC targeting MC mobs','map and save-slot binding');files=@(Get-ChildItem -LiteralPath $packageRoot -Recurse -File|ForEach-Object{@{name=$_.FullName.Substring($packageRoot.Length+1);sha256=(Get-FileHash -LiteralPath $_.FullName).Hash}})}
$manifest|ConvertTo-Json -Depth 6|Set-Content -LiteralPath "$projectRoot\build\verification.json" -Encoding UTF8
Compress-Archive -LiteralPath $packageRoot -DestinationPath "$projectRoot\dist\SekiroCraft-Passthrough-0.1.0.zip" -Force
Get-FileHash -LiteralPath "$projectRoot\dist\SekiroCraft-Passthrough-0.1.0.zip"
Write-Output "Build and offline checks complete: $packageRoot"

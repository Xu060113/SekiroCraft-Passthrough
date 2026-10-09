param([switch]$Offline,[switch]$SkipTests,[string]$SlashBladeJar)
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
& "$PSScriptRoot\build-native.ps1" -SkipTests:$SkipTests
if($LASTEXITCODE){throw 'Native build failed'}
$gradle=Join-Path $projectRoot '.cache\gradle-8.8\bin\gradle.bat'
if(!(Test-Path -LiteralPath $gradle)){throw 'Run scripts/bootstrap.ps1 first.'}
$gradleArgs=@('--gradle-user-home',"$projectRoot\.cache\gradle-home",'--no-daemon','-p',"$projectRoot\mc")
if($Offline){$gradleArgs+='--offline'}
$gradleTasks=@('build');if(!$SkipTests){$gradleTasks+=@('terrainTest','actorShapeTest','actorPartsTest')}
& $gradle @gradleArgs @gradleTasks
if($LASTEXITCODE){throw 'Minecraft build failed'}
if(!$SkipTests){
    & "$PSScriptRoot\verify-java.ps1" -SlashBladeJar $SlashBladeJar;if($LASTEXITCODE){throw 'Java/native verification failed'}
    & "$projectRoot\tests\update_installer_tests.ps1"
}
$packageRoot=Join-Path $projectRoot 'dist\SekiroCraft-Passthrough'
New-Item -ItemType Directory -Path "$packageRoot\minecraft","$packageRoot\docs","$packageRoot\scripts" -Force|Out-Null
Copy-Item -LiteralPath "$projectRoot\mc\build\libs\sekiro-minecraft-passthrough-0.1.0.jar" -Destination "$packageRoot\minecraft\sekiro-minecraft-passthrough-0.1.0.jar" -Force
$apiJar=Get-ChildItem -LiteralPath "$projectRoot\.cache\gradle-home\caches\modules-2\files-2.1\net.fabricmc.fabric-api\fabric-api\0.92.2+1.20.1" -Recurse -File -Filter '*.jar'|Where-Object Name -NotLike '*sources*'|Select-Object -First 1
if(!$apiJar){throw 'Pinned Fabric API JAR not found'}
Copy-Item -LiteralPath $apiJar.FullName -Destination "$packageRoot\minecraft\fabric-api-0.92.2+1.20.1.jar" -Force
Copy-Item -LiteralPath "$projectRoot\README.md","$projectRoot\THIRD_PARTY_NOTICES.md","$projectRoot\CHANGELOG.md" -Destination $packageRoot -Force
Copy-Item -LiteralPath "$projectRoot\docs\RETURN_TEST.md" -Destination "$packageRoot\docs\RETURN_TEST.md" -Force
Copy-Item -LiteralPath "$projectRoot\docs\PROTOCOL.md" -Destination "$packageRoot\docs\PROTOCOL.md" -Force
Copy-Item -LiteralPath "$projectRoot\docs\FEATURE_TEST.md" -Destination "$packageRoot\docs\FEATURE_TEST.md" -Force
Copy-Item -LiteralPath "$projectRoot\docs\REFERENCE_DESIGN.md" -Destination "$packageRoot\docs\REFERENCE_DESIGN.md" -Force
Copy-Item -LiteralPath "$projectRoot\docs\GAMEPLAY_TEST.md" -Destination "$packageRoot\docs\GAMEPLAY_TEST.md" -Force
Copy-Item -LiteralPath "$projectRoot\docs\COMBATFIX1_TEST.md" -Destination "$packageRoot\docs\COMBATFIX1_TEST.md" -Force
Copy-Item -LiteralPath "$projectRoot\docs\GUI_NATIVE_COMBAT_TEST.md" -Destination "$packageRoot\docs\GUI_NATIVE_COMBAT_TEST.md" -Force
Copy-Item -LiteralPath "$projectRoot\docs\DEFENSE_RENDER_TEST.md" -Destination "$packageRoot\docs\DEFENSE_RENDER_TEST.md" -Force
Copy-Item -LiteralPath "$projectRoot\docs\CINEMATIC_TEST.md","$projectRoot\docs\MC_DEATHBLOW_DESIGN.md","$projectRoot\docs\SLASHBLADE_TEST.md","$projectRoot\docs\CANYON_VOID_TEST.md","$projectRoot\docs\MOD_INPUT_TEST.md" -Destination "$packageRoot\docs" -Force
Copy-Item -LiteralPath "$projectRoot\docs\F5_RENDER_TEST.md","$projectRoot\docs\MODPACK.md" -Destination "$packageRoot\docs" -Force
Copy-Item -LiteralPath "$projectRoot\docs\BOSS_HITBOX_TEST.md" -Destination "$packageRoot\docs" -Force
Copy-Item -LiteralPath "$projectRoot\docs\SWORD_EFFECT_TEST.md" -Destination "$packageRoot\docs" -Force
New-Item -ItemType Directory -Path "$packageRoot\config" -Force | Out-Null
Copy-Item -LiteralPath "$projectRoot\config\minecraft-runtime.json" -Destination "$packageRoot\config" -Force
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
$manifest=@{version='0.1.0';protocol=3;sourceCommit=$sourceCommit;sourceDirty=$sourceDirty;patch='gameplay5-defense-render';gameSha256='637ACA527538C0EC6E1F136C8ED66046E95DFBDBB1F51926E134D9916398B856';gameLaunched=$false;installed=$false;fullPort=$false;verified=(-not $SkipTests);capabilities=@('MC player control','GUI events paired with displayed frame generation and viewport','paired ABI v3 guard','F6 native menu input routing with Esc entry','native UI pauses MC and combat delivery','SHA-gated native cinematic presentation handoff','completed frame camera pairing','isolated deferred scene metadata','depth-composite','independent fresh overlay','native ground block placement','background MC audio','damage source, impact and actor stage transport','vanilla incoming-hit defense queue','opaque-only native scene depth selection','configurable MC-equivalent enemy health','hostile Monster proxies selectable before first received hit','native-owned player and actor canyon void protection','R and five-button mod input callbacks','source-matched OpenGL depth capture with hidden GL regression');experimental=@('HP-depleted Boss stage mode','M-only grapple with MC movement and camera','spawn eggs on sampled native ground','read-only animation-state action handoff without fixed 6.5s delay','M-only native grapple; R reserved for MC mods; death-screen resurrection request only','MC follows native root motion without Wolf mesh','bounded native projectile segment ray queries','native actor lodged-arrow rendering','nearby MC entity ground collision','local human-size NPC block sweeps','opt-in native normal-hit backend','disabled candidate native phase finish profile','bidirectional player health delta ledger','owned native player creative NoDamage');pending=@('live F5 third-person player and gun render acceptance','live gun reload and mouse side binding acceptance','live deep canyon safe-floor and native-death acceptance','live SlashBlade first-hit, lock-on and area attack acceptance','native red-flower automatic deathblow interface','live cinematic full-play, skip and resume acceptance','custom MC deathblow animation and authoritative native settlement interface','live native shield direction, armor durability and attack FX acceptance','live GUI slot and native menu acceptance','live animation pointer ownership and finisher completion acceptance','MC-driven Boss eligibility and stage finish including scripted gates and final rewards','remote automatic native phase profile acceptance (default disabled)','full native weapon-specific hit profiles, knockback and deflection','exact large Boss projectile/body geometry','full native terrain collision geometry','Havok block bodies and navigation','native NPC targeting MC mobs','map and save-slot binding');files=@(Get-ChildItem -LiteralPath $packageRoot -Recurse -File|ForEach-Object{@{name=$_.FullName.Substring($packageRoot.Length+1);sha256=(Get-FileHash -LiteralPath $_.FullName).Hash}})}
$manifest.capabilities+=@('native-sized actor body bounds with paired client/server tracking')
$manifest.pending+=@('live large Boss torso/flank, mod sword-wave and moving body acceptance')
$manifest.capabilities+=@('scoped SlashBlade additive-effect color/depth export with GL-to-D3D occlusion regression')
$manifest.pending+=@('live SlashBlade sword-wave and charge visual acceptance','subtractive SlashBlade judgement-cut visual transport')
$manifest.capabilities+=@('animated native main-model bone bounds with multipart rays, area overlap and body-distance validation')
$manifest.capabilities+=@('owned-player void protection and movement hold across native map loading with stable pose reseed')
$manifest.capabilities+=@('explicit MC HUD and first-person hand recovery command')
$manifest.pending+=@('live HUD recovery command and bridge activation visibility acceptance')
$manifest.pending+=@('live Reflection of Strength teleport/loading survival and resumed native damage acceptance')
$manifest.pending+=@('user live per-Boss body-part hit acceptance','assembly-only body meshes and detached scripted Boss components')
$manifest|ConvertTo-Json -Depth 6|Set-Content -LiteralPath "$projectRoot\build\verification.json" -Encoding UTF8
Compress-Archive -LiteralPath $packageRoot -DestinationPath "$projectRoot\dist\SekiroCraft-Passthrough-0.1.0.zip" -Force
Get-FileHash -LiteralPath "$projectRoot\dist\SekiroCraft-Passthrough-0.1.0.zip"
Write-Output "Build and offline checks complete: $packageRoot"

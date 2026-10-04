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
if(!$SkipTests){& "$PSScriptRoot\verify-java.ps1";if($LASTEXITCODE){throw 'Java/native verification failed'}}
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
Copy-Item -LiteralPath "$projectRoot\licenses\SekiroTool-LICENSE.txt" -Destination "$packageRoot\SekiroTool-LICENSE.txt" -Force
Copy-Item -LiteralPath "$PSScriptRoot\switch-sekiro.ps1","$PSScriptRoot\prepare-minecraft.ps1" -Destination "$packageRoot\scripts" -Force
$workspaceRoot=[IO.Path]::GetFullPath((Join-Path $projectRoot '..\..'))
$dependencyRoot=Join-Path $projectRoot 'third_party'
if(!(Test-Path -LiteralPath $dependencyRoot)){$dependencyRoot=Join-Path $workspaceRoot 'third_party'}
Copy-Item -LiteralPath "$dependencyRoot\minhook\LICENSE.txt" -Destination "$packageRoot\MinHook-LICENSE.txt" -Force
Copy-Item -LiteralPath "$dependencyRoot\imgui\LICENSE.txt" -Destination "$packageRoot\ImGui-LICENSE.txt" -Force
$manifest=@{version='0.1.0';patch='gameplay1';gameLaunched=$false;installed=$false;fullPort=$false;verified=(-not $SkipTests);capabilities=@('MC player control','ordered input events','completed frame camera pairing','depth-composite','independent fresh overlay','native ground block placement','background MC audio');experimental=@('nearby MC entity ground collision','native actor HP proxy combat','spawned hostile mob targets and local steering','bidirectional player health delta ledger','owned native player creative NoDamage');pending=@('real-game feature acceptance','full native terrain collision geometry','NPC/Havok block collision','native ApplyDamage/posture/deathblow/rewards','native NPC targeting MC mobs','map and save-slot binding');files=@(Get-ChildItem -LiteralPath $packageRoot -Recurse -File|ForEach-Object{@{name=[IO.Path]::GetRelativePath($packageRoot,$_.FullName);sha256=(Get-FileHash -LiteralPath $_.FullName).Hash}})}
$manifest|ConvertTo-Json -Depth 6|Set-Content -LiteralPath "$projectRoot\build\verification.json" -Encoding UTF8
Compress-Archive -LiteralPath $packageRoot -DestinationPath "$projectRoot\dist\SekiroCraft-Passthrough-0.1.0.zip" -Force
Get-FileHash -LiteralPath "$projectRoot\dist\SekiroCraft-Passthrough-0.1.0.zip"
Write-Output "Build and offline checks complete: $packageRoot"

param([switch]$Offline,[string]$Gradle,[string]$InitScript,[string]$VixJar,[string]$RuntimeMixinJar)
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if(!$Gradle){$Gradle=Join-Path $projectRoot '.cache\gradle-8.8\bin\gradle.bat'}
if(!(Test-Path -LiteralPath $Gradle)){throw 'Gradle 8.8 is required; run scripts/bootstrap.ps1 or pass -Gradle.'}
$jni=Join-Path $projectRoot 'build\passthrough-native\sekirobridge-jni.dll'
if(!(Test-Path -LiteralPath $jni)){
    & "$PSScriptRoot\build-native.ps1"
    if($LASTEXITCODE){throw 'Paired native build failed'}
}
$gradleArguments=@('--gradle-user-home',"$projectRoot\.cache\gradle-home",'--no-daemon','-p',"$projectRoot\mc-forge")
if($Offline){$gradleArguments+='--offline'}
if($InitScript){$gradleArguments+=@('--init-script',[IO.Path]::GetFullPath($InitScript))}
& $Gradle @gradleArguments 'build' 'writeVerificationClasspath'
if($LASTEXITCODE){throw 'Forge build failed'}
& "$PSScriptRoot\verify-forge.ps1" -VixJar $VixJar -RuntimeMixinJar $RuntimeMixinJar
if($LASTEXITCODE){throw 'Forge verification failed'}
$modVersion=(@(Get-Content -LiteralPath "$projectRoot\mc-forge\gradle.properties" | Where-Object {$_ -match '^mod_version='})[0] -split '=',2)[1].Trim()
$previewVersion=($modVersion -split '-forge-',2)[1]
$destination=Join-Path $projectRoot "dist\forge-preview\$previewVersion"
New-Item -ItemType Directory -Path $destination -Force | Out-Null
$jar=Join-Path $projectRoot "mc-forge\build\libs\sekiro-minecraft-passthrough-forge-$modVersion.jar"
Copy-Item -LiteralPath $jar -Destination $destination -Force
$hostDll=Join-Path $projectRoot 'dist\SekiroCraft-Passthrough\dinput8.dll'
if(!(Test-Path -LiteralPath $hostDll)){throw 'Build the paired native host before packaging Forge.'}
Copy-Item -LiteralPath $hostDll -Destination $destination -Force
Copy-Item -LiteralPath "$projectRoot\config\sekirobridge.ini","$projectRoot\licenses\SekiroTool-LICENSE.txt" -Destination $destination -Force
$dependencyRoot=Join-Path $projectRoot 'third_party'
if(!(Test-Path -LiteralPath $dependencyRoot)){$dependencyRoot=Join-Path ([IO.Path]::GetFullPath((Join-Path $projectRoot '..\..'))) 'third_party'}
Copy-Item -LiteralPath "$dependencyRoot\minhook\LICENSE.txt" -Destination "$destination\MinHook-LICENSE.txt" -Force
Copy-Item -LiteralPath "$dependencyRoot\imgui\LICENSE.txt" -Destination "$destination\ImGui-LICENSE.txt" -Force
Copy-Item -LiteralPath "$projectRoot\docs\FORGE_PORT.md","$projectRoot\THIRD_PARTY_NOTICES.md","$projectRoot\build\forge-verification.json" -Destination $destination -Force
$hash=(Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash.ToLowerInvariant()
@("$hash  $([IO.Path]::GetFileName($jar))","$((Get-FileHash -LiteralPath $hostDll).Hash.ToLowerInvariant())  dinput8.dll") | Set-Content -LiteralPath "$destination\SHA256SUMS.txt" -Encoding ASCII
Compress-Archive -LiteralPath "$destination\$([IO.Path]::GetFileName($jar))","$destination\dinput8.dll","$destination\sekirobridge.ini","$destination\FORGE_PORT.md","$destination\THIRD_PARTY_NOTICES.md","$destination\MinHook-LICENSE.txt","$destination\ImGui-LICENSE.txt","$destination\SekiroTool-LICENSE.txt","$destination\forge-verification.json","$destination\SHA256SUMS.txt" -DestinationPath "$projectRoot\dist\SekiroCraft-Forge-1.20.1-$previewVersion.zip" -Force
Write-Output "Forge preview ready: $destination. Game test pending; nothing installed or published."

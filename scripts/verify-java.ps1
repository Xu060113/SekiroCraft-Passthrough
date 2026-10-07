param([string]$CompilerRoot,[string]$SlashBladeJar)
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$workspaceRoot=[IO.Path]::GetFullPath((Join-Path $projectRoot '..\..'))
if(!$CompilerRoot){$CompilerRoot=Join-Path $projectRoot '.tools\llvm-mingw-20260922-ucrt-x86_64\bin';if(!(Test-Path -LiteralPath $CompilerRoot)){$CompilerRoot=Join-Path $workspaceRoot '.tools\llvm-mingw-20260922-ucrt-x86_64\bin'}}
$buildRoot=Join-Path $projectRoot 'build\passthrough-native'
& "$buildRoot\bridge_tests.exe" "$buildRoot\control-fixture.bin"
if($LASTEXITCODE){throw 'Native control fixture failed'}
& java '-cp' "$projectRoot\mc\build\classes\java\main" 'dev.sekirobridge.ProtocolSelfTest' "$buildRoot\sekirobridge-jni.dll" "$buildRoot\control-fixture.bin" "$buildRoot\java-frame-meta.bin"
if($LASTEXITCODE){throw 'Java/JNI protocol test failed'}
& java '-cp' "$projectRoot\mc\build\classes\java\main" 'dev.sekirobridge.InputForwarderSelfTest'
if($LASTEXITCODE){throw 'Production input replay regression failed'}
foreach($test in @('AudioBridgeSelfTest','CombatSelfTest')){
    & java '-cp' "$projectRoot\mc\build\classes\java\main" "dev.sekirobridge.$test"
    if($LASTEXITCODE){throw "$test failed"}
}
& javac '-cp' "$projectRoot\mc\build\classes\java\main" '-d' $buildRoot "$projectRoot\tests\JniPeer.java"
if($LASTEXITCODE){throw 'Java peer fixture build failed'}
& "$CompilerRoot\x86_64-w64-mingw32-clang++.exe" '-std=c++20' '-O2' '-static' '-municode' "$projectRoot\tests\jni_host.cpp" '-o' "$buildRoot\jni_host.exe"
if($LASTEXITCODE){throw 'C++ peer fixture build failed'}
$channel='cross-process-'+[Guid]::NewGuid().ToString('N')
$hostProcess=Start-Process -FilePath "$buildRoot\jni_host.exe" -ArgumentList $channel -WindowStyle Hidden -PassThru -RedirectStandardOutput "$buildRoot\jni-host.log" -RedirectStandardError "$buildRoot\jni-host-error.log"
try {
    & java '-cp' "$projectRoot\mc\build\classes\java\main;$buildRoot" 'JniPeer' "$buildRoot\sekirobridge-jni.dll" $channel
    if($LASTEXITCODE){throw 'Java cross-process peer failed'}
    if(!$hostProcess.WaitForExit(10000)){throw 'Cross-process fixture timed out'}
    if($hostProcess.ExitCode){throw "C++ cross-process fixture failed: $($hostProcess.ExitCode)"}
    Get-Content -LiteralPath "$buildRoot\jni-host.log"
} finally {if(!$hostProcess.HasExited){Stop-Process -Id $hostProcess.Id -Force}}
$gameJar=Get-ChildItem -LiteralPath "$projectRoot\mc\.gradle\loom-cache\minecraftMaven" -Recurse -File -Filter '*.jar'|Where-Object { $_.Name -like 'minecraft-merged*' -and $_.Name -notlike '*sources*' }|Select-Object -First 1
$asmJars=@(Get-ChildItem -LiteralPath "$projectRoot\.cache\gradle-home\caches\modules-2\files-2.1\org.ow2.asm" -Recurse -File -Filter '*.jar'|Where-Object Name -NotLike '*sources*'|Select-Object -ExpandProperty FullName)
if(!$gameJar -or !$asmJars.Count){throw 'Mapped game or ASM dependency missing; run the MC build first'}
$gsonJar=Get-ChildItem -LiteralPath "$projectRoot\.cache\gradle-home\caches\modules-2\files-2.1\com.google.code.gson\gson" -Recurse -File -Filter '*.jar'|Where-Object Name -NotLike '*sources*'|Select-Object -First 1
if(!$gsonJar){throw 'Gson dependency missing'}
$verificationClasspath=(@($asmJars)+@($gsonJar.FullName)) -join ';'
& java '-cp' $verificationClasspath "$projectRoot\tests\VanillaShield.java" $gameJar.FullName
if($LASTEXITCODE){throw 'Vanilla shield direction checks failed'}
& javac '-cp' "$verificationClasspath;$projectRoot\mc\build\classes\java\main" '-d' $buildRoot "$projectRoot\tests\VanillaVoid.java"
if($LASTEXITCODE){throw 'Native canyon regression fixture build failed'}
& java '-cp' "$verificationClasspath;$projectRoot\mc\build\classes\java\main;$buildRoot" 'dev.sekirobridge.VanillaVoid' $gameJar.FullName "$projectRoot\mc\build\classes\java\main\dev\sekirobridge\mixin"
if($LASTEXITCODE){throw 'Native canyon void and health feedback checks failed'}
$glJars=@(foreach($module in @('lwjgl','lwjgl-glfw','lwjgl-opengl')){
    Get-ChildItem -LiteralPath "$projectRoot\.cache\gradle-home\caches\modules-2\files-2.1\org.lwjgl\$module\3.3.2" -Recurse -File -Filter '*.jar' |
        Where-Object { $_.Name -eq "$module-3.3.2.jar" -or $_.Name -eq "$module-3.3.2-natives-windows.jar" } |
        Select-Object -ExpandProperty FullName
})
if($glJars.Count -ne 6){throw 'Windows x64 LWJGL dependencies missing for hidden GL readback check'}
$glClasspath=(@($glJars)+@("$projectRoot\mc\build\classes\java\main",$buildRoot)) -join ';'
& javac '-cp' $glClasspath '-d' $buildRoot "$projectRoot\tests\FrameCaptureGl.java"
if($LASTEXITCODE){throw 'GL readback regression fixture build failed'}
& java '-cp' $glClasspath 'dev.sekirobridge.FrameCaptureGl'
if($LASTEXITCODE){throw 'Production GL depth-format and readback regression failed'}
& java '-cp' $verificationClasspath "$projectRoot\tests\MixinTargets.java" $gameJar.FullName "$projectRoot\mc\build\classes\java\main\dev\sekirobridge\mixin"
if($LASTEXITCODE){throw 'Minecraft injection target verification failed'}
$productionGame=Get-ChildItem -LiteralPath "$projectRoot\.cache\gradle-home\caches\fabric-loom\minecraftMaven\net\minecraft\minecraft-merged-intermediary" -Recurse -File -Filter '*.jar'|Select-Object -First 1
$productionMod=Join-Path $projectRoot 'mc\build\libs\sekiro-minecraft-passthrough-0.1.0.jar'
if(!$productionGame -or !(Test-Path -LiteralPath $productionMod)){throw 'Production game or remapped mod missing'}
$proxyArgs=@($productionMod);if($SlashBladeJar){$proxyArgs+=$SlashBladeJar}
& java '-cp' $verificationClasspath "$projectRoot\tests\SlashBladeTargets.java" @proxyArgs
if($LASTEXITCODE){throw 'Production proxy / SlashBlade target checks failed'}
& java '-cp' $verificationClasspath "$projectRoot\tests\MixinTargets.java" $productionGame.FullName $productionMod
if($LASTEXITCODE){throw 'Production Minecraft injection target verification failed'}

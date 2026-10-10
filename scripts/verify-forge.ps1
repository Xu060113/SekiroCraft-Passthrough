param([string]$RuntimeGameJar,[string]$VixJar,[string]$RuntimeMixinJar)
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$forgeRoot=Join-Path $projectRoot 'mc-forge\build'
$classpathFile=Join-Path $forgeRoot 'verification-classpath.txt'
if(!(Test-Path -LiteralPath $classpathFile)){throw 'Run the Forge build and writeVerificationClasspath task first.'}
$classpath=(Get-Content -LiteralPath $classpathFile -Raw).Trim()
$classes=Join-Path $forgeRoot 'classes\java\main'
$modVersion=(@(Get-Content -LiteralPath "$projectRoot\mc-forge\gradle.properties" | Where-Object {$_ -match '^mod_version='})[0] -split '=',2)[1].Trim()
$jar=Join-Path $forgeRoot "libs\sekiro-minecraft-passthrough-forge-$modVersion.jar"
$nativeRoot=Join-Path $projectRoot 'build\passthrough-native'
$jni=Join-Path $nativeRoot 'sekirobridge-jni.dll'
$results=@()
function Invoke-ForgeCheck([string]$CheckName,[string[]]$JavaArguments){
    $previousErrorAction=$ErrorActionPreference
    try {
        $ErrorActionPreference='Continue' # JVM/LWJGL warnings on stderr are not test failures.
        $output=@(& java @JavaArguments 2>&1)
        $code=$LASTEXITCODE
    } finally { $ErrorActionPreference=$previousErrorAction }
    $output | ForEach-Object { Write-Output $_ }
    if($code){throw "$CheckName failed (exit $code)"}
    $script:results+=@{name=$CheckName;passed=$true;output=($output -join "`n")}
}
& "$nativeRoot\bridge_tests.exe" "$nativeRoot\control-fixture.bin"
if($LASTEXITCODE){throw 'Native protocol fixture failed'}
Invoke-ForgeCheck 'Forge JNI ABI v3' @('-cp',$classes,'dev.sekirobridge.ProtocolSelfTest',$jni,"$nativeRoot\control-fixture.bin","$nativeRoot\java-frame-meta.bin")
& "$nativeRoot\bridge_tests.exe" "$nativeRoot\control-fixture.bin" "$nativeRoot\java-frame-meta.bin.roll"
if($LASTEXITCODE){throw 'Cross-language rolled camera metadata failed'}
foreach($test in @('InputForwarderSelfTest','AudioBridgeSelfTest','CombatSelfTest','NativeLoadSelfTest','HudPreferencesSelfTest')){
    Invoke-ForgeCheck $test @('-cp',$classes,"dev.sekirobridge.$test")
}
$asmJars=@(Get-ChildItem -LiteralPath "$projectRoot\.cache\gradle-home\caches\modules-2\files-2.1\org.ow2.asm" -Recurse -File -Filter '*.jar' | Where-Object { $_.Directory.Parent.Name -eq '9.6' -and $_.Name -notlike '*sources*' } | Select-Object -ExpandProperty FullName)
if(!$asmJars.Count){throw 'ASM 9.6 verification dependency missing'}
$verificationClasspath=(@($classpath)+$asmJars) -join ';'
Invoke-ForgeCheck 'Forge loading overlay before JNI initialization' @('-cp',$classpath,"$projectRoot\tests\ForgeStartup.java")
$compatClasspath=$classpath
if($RuntimeMixinJar){
    $RuntimeMixinJar=(Resolve-Path -LiteralPath $RuntimeMixinJar).Path
    $compatClasspath="$RuntimeMixinJar;$classpath"
}
$compatTests=Join-Path $projectRoot 'build\forge-compat-tests'
New-Item -ItemType Directory -Path $compatTests -Force | Out-Null
& javac -proc:none -cp $compatClasspath -d $compatTests "$projectRoot\tests\ForgeCompatStartup.java" "$projectRoot\tests\ForgePacingPreferences.java"
if($LASTEXITCODE){throw 'Forge ModLauncher startup fixture compilation failed'}
$compatArguments=@('-cp',"$compatTests;$compatClasspath",'dev.sekirobridge.compat.ForgeCompatStartup')
if($VixJar){$compatArguments+=[IO.Path]::GetFullPath($VixJar)}
Invoke-ForgeCheck 'Forge optional adapter ModLauncher startup' $compatArguments
Invoke-ForgeCheck 'Forge window pacing and profile settings' @('-cp',"$compatTests;$classpath",'dev.sekirobridge.ForgePacingPreferences')
$glTests=Join-Path $projectRoot 'build\forge-gl-tests'
New-Item -ItemType Directory -Path $glTests -Force | Out-Null
& javac -proc:none -cp $classpath -d $glTests "$projectRoot\tests\ForgePostEffectsGl.java" "$projectRoot\tests\ForgeParticleDepthGl.java" "$projectRoot\tests\ForgeRenderedCamera.java"
if($LASTEXITCODE){throw 'Forge post effect fixture compilation failed'}
Invoke-ForgeCheck 'Forge full-screen post effect isolation' @('-cp',"$glTests;$classpath",'dev.sekirobridge.ForgePostEffectsGl')
Invoke-ForgeCheck 'Forge final camera and roll' @('-cp',"$glTests;$classpath",'dev.sekirobridge.ForgeRenderedCamera')
Invoke-ForgeCheck 'Native AAA particle geometry and coverage depth' @('-cp',"$glTests;$classpath",'dev.sekirobridge.ForgeParticleDepthGl',$jni)
foreach($test in @('TerrainSelfTest','ActorShapeSelfTest','ActorPartsSelfTest')){
    Invoke-ForgeCheck $test @('-cp',$classpath,"dev.sekirobridge.$test")
}
$mappedGame=@($classpath.Split(';') | Where-Object { [IO.Path]::GetFileName($_) -like '*minecraft-merged*.jar' -and $_ -notlike '*sources*' })
if($mappedGame.Count -ne 1){throw 'Expected one Forge-patched named game JAR on the verification classpath'}
if(!$RuntimeGameJar){
    $candidate=@(Get-ChildItem -LiteralPath "$projectRoot\.cache\gradle-home\caches\fabric-loom\1.20.1" -Recurse -File -Filter 'minecraft-merged-srg-patched.jar' | Where-Object FullName -Match '47\.4\.10')
    if($candidate.Count -ne 1){throw 'Pass -RuntimeGameJar with the exact Forge 47.4.10 patched SRG game JAR.'}
    $RuntimeGameJar=$candidate[0].FullName
}
Invoke-ForgeCheck 'Forge production artifact and lifecycle' @('-cp',$verificationClasspath,"$projectRoot\tests\ForgePort.java",$jar,$jni,$mappedGame[0],$RuntimeGameJar)
Invoke-ForgeCheck 'Forge-patched named injection points' @('-cp',$verificationClasspath,"$projectRoot\tests\MixinTargets.java",$mappedGame[0],"$classes\dev\sekirobridge\mixin")
Invoke-ForgeCheck 'Forge production SRG injection points' @('-cp',$verificationClasspath,"$projectRoot\tests\MixinTargets.java",$RuntimeGameJar,$jar)
if($VixJar){
    $optionalArguments=@('-cp',$verificationClasspath,"$projectRoot\tests\ForgeVixTargets.java",[IO.Path]::GetFullPath($VixJar),$jar)
    $aaaJars=@(Get-ChildItem -LiteralPath ([IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($VixJar))) -Filter 'aaa_particles-*.jar' -File)
    if($aaaJars.Count -eq 1){$optionalArguments+=$aaaJars[0].FullName}
    elseif($aaaJars.Count -gt 1){throw 'More than one AAA Particles JAR; resolve duplicate versions before verification'}
    Invoke-ForgeCheck 'Installed VIX and AAA original rendering integration' $optionalArguments
}
$receipt=@{minecraft='1.20.1';forge='47.4.10';protocol=3;status='offline-verified-preview';gamesLaunched=$false;gameTestPending=$true;installed=$false;githubPublished=$false;checks=$results;jarSha256=(Get-FileHash -LiteralPath $jar).Hash.ToLowerInvariant();jniSha256=(Get-FileHash -LiteralPath $jni).Hash.ToLowerInvariant();sourceCommit=(& git -C $projectRoot rev-parse HEAD).Trim();sourceDirty=[bool](& git -C $projectRoot status --porcelain);knownIssues=@('Native attack FX flicker remains under investigation','Forge third-party combat and renderer integration requires gameplay validation')}
$receipt | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath "$projectRoot\build\forge-verification.json" -Encoding UTF8
Write-Output 'Forge offline checks passed. Game test and publication authorization still required.'

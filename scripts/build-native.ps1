param([string]$CompilerRoot,[string]$DependencyRoot,[string]$JdkRoot='C:\Program Files\Java\jdk-21',[switch]$SkipTests)
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$workspaceRoot=[IO.Path]::GetFullPath((Join-Path $projectRoot '..\..'))
if(!$CompilerRoot){$CompilerRoot=Join-Path $projectRoot '.tools\llvm-mingw-20260922-ucrt-x86_64\bin';if(!(Test-Path -LiteralPath $CompilerRoot)){$CompilerRoot=Join-Path $workspaceRoot '.tools\llvm-mingw-20260922-ucrt-x86_64\bin'}}
if(!$DependencyRoot){$DependencyRoot=Join-Path $projectRoot 'third_party';if(!(Test-Path -LiteralPath $DependencyRoot)){$DependencyRoot=Join-Path $workspaceRoot 'third_party'}}
$cpp=Join-Path $CompilerRoot 'x86_64-w64-mingw32-clang++.exe'
$cc=Join-Path $CompilerRoot 'x86_64-w64-mingw32-clang.exe'
if(!(Test-Path -LiteralPath $cpp)){throw 'Supply -CompilerRoot for llvm-mingw x64.'}
if(!(Test-Path -LiteralPath "$JdkRoot\include\jni.h")){throw 'Supply -JdkRoot for a Windows x64 JDK.'}
$buildRoot=Join-Path $projectRoot 'build\passthrough-native'
$packageRoot=Join-Path $projectRoot 'dist\SekiroCraft-Passthrough'
New-Item -ItemType Directory -Path $buildRoot,$packageRoot -Force|Out-Null
$common=@('-std=c++20','-O2','-g','-static','-DNOMINMAX','-DWIN32_LEAN_AND_MEAN','-Wall','-Wextra','-Wpedantic','-I',"$projectRoot\include",'-I',"$DependencyRoot\minhook\include",'-I',"$DependencyRoot\imgui")
$objects=@()
foreach($source in @('buffer','hook','trampoline','hde\hde64')){
    $object=Join-Path $buildRoot (($source -replace '\\','_')+'.o')
    & $cc '-O2' '-DNOMINMAX' '-DWIN32_LEAN_AND_MEAN' '-c' "$DependencyRoot\minhook\src\$source.c" '-o' $object
    if($LASTEXITCODE){throw 'MinHook build failed'}
    $objects+=$object
}
& $cpp @common '-shared' '-I' "$JdkRoot\include" '-I' "$JdkRoot\include\win32" "$projectRoot\bridge\jni.cpp" '-o' "$buildRoot\sekirobridge-jni.dll"
if($LASTEXITCODE){throw 'JNI build failed'}
$imgui=@('imgui.cpp','imgui_draw.cpp','imgui_tables.cpp','imgui_widgets.cpp','backends\imgui_impl_dx11.cpp','backends\imgui_impl_win32.cpp')|ForEach-Object{Join-Path "$DependencyRoot\imgui" $_}
& $cpp @common '-shared' "$projectRoot\src\proxy.cpp" "$projectRoot\src\host_mod.cpp" "$projectRoot\src\movement_hook.cpp" "$projectRoot\src\movement_stub.S" "$projectRoot\src\dinput8.def" @imgui @objects '-ld3d11' '-ldxgi' '-ld3dcompiler' '-lbcrypt' '-luser32' '-lgdi32' '-limm32' '-ldwmapi' '-ldxguid' '-o' "$packageRoot\dinput8.dll"
if($LASTEXITCODE){throw 'Host DLL build failed'}
Copy-Item -LiteralPath "$projectRoot\config\sekirobridge.ini" -Destination "$packageRoot\sekirobridge.ini" -Force
if(!$SkipTests){
    & $cpp @common "$projectRoot\tests\movement_tests.cpp" "$projectRoot\tests\movement_fixture.S" "$projectRoot\src\movement_hook.cpp" "$projectRoot\src\movement_stub.S" @objects '-lbcrypt' '-o' "$buildRoot\movement_tests.exe"
    if($LASTEXITCODE){throw 'Movement fixture build failed'}
    & "$buildRoot\movement_tests.exe"
    if($LASTEXITCODE){throw 'Movement checks failed'}
    & $cpp @common "$projectRoot\tests\bridge_tests.cpp" '-o' "$buildRoot\bridge_tests.exe"
    if($LASTEXITCODE){throw 'Bridge test build failed'}
    & "$buildRoot\bridge_tests.exe"
    if($LASTEXITCODE){throw 'Bridge tests failed'}
    & $cpp @common "$projectRoot\tests\compositor_tests.cpp" '-ld3d11' '-ldxgi' '-ld3dcompiler' '-o' "$buildRoot\compositor_tests.exe"
    if($LASTEXITCODE){throw 'Compositor test build failed'}
    & "$buildRoot\compositor_tests.exe"
    if($LASTEXITCODE){throw 'Compositor tests failed'}
    & $cpp @common "$projectRoot\tests\overlay_tests.cpp" '-ld3d11' '-ldxgi' '-ld3dcompiler' '-o' "$buildRoot\overlay_tests.exe"
    if($LASTEXITCODE){throw 'Independent HUD test build failed'}
    & "$buildRoot\overlay_tests.exe"
    if($LASTEXITCODE){throw 'Independent HUD checks failed'}
    & $cpp @common "$projectRoot\tests\input_tests.cpp" @objects '-ldxguid' '-o' "$buildRoot\input_tests.exe"
    if($LASTEXITCODE){throw 'Input fixture build failed'}
    & "$buildRoot\input_tests.exe"
    if($LASTEXITCODE){throw 'Input isolation checks failed'}
}
Get-FileHash -LiteralPath "$packageRoot\dinput8.dll","$buildRoot\sekirobridge-jni.dll"

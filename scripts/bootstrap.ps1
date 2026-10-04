$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$workspaceRoot=[IO.Path]::GetFullPath((Join-Path $projectRoot '..\..'))
foreach($name in @('.tools','third_party')){
    $localPath=Join-Path $projectRoot $name;$sharedPath=Join-Path $workspaceRoot $name
    if(!(Test-Path -LiteralPath $localPath)-and(Test-Path -LiteralPath $sharedPath)){New-Item -ItemType Junction -Path $localPath -Target $sharedPath|Out-Null}
}
& "$PSScriptRoot\bootstrap-native-reference.ps1"
$cacheRoot=Join-Path $projectRoot '.cache'
$gradle=Join-Path $cacheRoot 'gradle-8.8\bin\gradle.bat'
if(!(Test-Path -LiteralPath $gradle)){
    New-Item -ItemType Directory -Path $cacheRoot -Force|Out-Null
    $archive=Join-Path $cacheRoot 'gradle-8.8-bin.zip'
    & curl.exe --ssl-revoke-best-effort -fL --retry 2 -o $archive 'https://services.gradle.org/distributions/gradle-8.8-bin.zip'
    if($LASTEXITCODE){throw 'Gradle download failed'}
    if((Get-FileHash -LiteralPath $archive).Hash -ne 'A4B4158601F8636CDEEAB09BD76AFB640030BB5B144AAFE261A5E8AF027DC612'){throw 'Gradle checksum mismatch'}
    Expand-Archive -LiteralPath $archive -DestinationPath $cacheRoot
}
Write-Output 'Pinned C++ toolchain, dependencies and Gradle ready. No games launched.'

param([string]$GameDirectory)
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if(!$GameDirectory){$GameDirectory=Join-Path $projectRoot 'runtime\minecraft'}
$gameRoot=[IO.Path]::GetFullPath($GameDirectory)
$packageRoot=Join-Path $projectRoot 'dist\SekiroCraft-Passthrough'
if(!(Test-Path -LiteralPath $packageRoot)){$packageRoot=$projectRoot}
$bridgeJars=@(Get-ChildItem -LiteralPath (Join-Path $packageRoot 'minecraft') -File -Filter 'sekiro-minecraft-passthrough-*.jar' | Where-Object {$_.Name -notlike '*-forge-*'})
if($bridgeJars.Count -ne 1){throw 'Expected one built Fabric companion JAR; use the installer when updating an existing instance.'}
$source=$bridgeJars[0].FullName
$mods=Join-Path $gameRoot 'mods'
New-Item -ItemType Directory -Path $mods -Force|Out-Null
foreach($jar in Get-ChildItem -LiteralPath (Join-Path $packageRoot 'minecraft') -File -Filter '*.jar'){
    $target=Join-Path $mods $jar.Name
    if((Test-Path -LiteralPath $target)-and(Get-FileHash -LiteralPath $target).Hash -ne (Get-FileHash -LiteralPath $jar.FullName).Hash){throw "Existing mod differs: $target"}
    Copy-Item -LiteralPath $jar.FullName -Destination $target -Force
}
Write-Output "Companion mods prepared in $mods"
Write-Output 'Use a separate Minecraft 1.20.1 Fabric profile with this game directory. Existing Forge/OptiFine profiles are not compatible. No launcher was started.'

param([string]$GameDirectory)
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if(!$GameDirectory){$GameDirectory=Join-Path $projectRoot 'runtime\minecraft'}
$gameRoot=[IO.Path]::GetFullPath($GameDirectory)
$packageRoot=Join-Path $projectRoot 'dist\SekiroCraft-Passthrough'
if(!(Test-Path -LiteralPath $packageRoot)){$packageRoot=$projectRoot}
$source=Join-Path $packageRoot 'minecraft\sekiro-minecraft-passthrough-0.1.0.jar'
if(!(Test-Path -LiteralPath $source)){throw 'Build the Minecraft JAR first.'}
$mods=Join-Path $gameRoot 'mods'
New-Item -ItemType Directory -Path $mods -Force|Out-Null
foreach($jar in Get-ChildItem -LiteralPath (Join-Path $packageRoot 'minecraft') -File -Filter '*.jar'){
    $target=Join-Path $mods $jar.Name
    if((Test-Path -LiteralPath $target)-and(Get-FileHash -LiteralPath $target).Hash -ne (Get-FileHash -LiteralPath $jar.FullName).Hash){throw "Existing mod differs: $target"}
    Copy-Item -LiteralPath $jar.FullName -Destination $target -Force
}
Write-Output "Companion mods prepared in $mods"
Write-Output 'Use a separate Minecraft 1.20.1 Fabric profile with this game directory. Existing Forge/OptiFine profiles are not compatible. No launcher was started.'

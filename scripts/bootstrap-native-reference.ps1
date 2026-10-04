$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$toolsRoot = Join-Path $projectRoot '.tools'
New-Item -ItemType Directory -Path $toolsRoot,"$projectRoot\third_party" -Force | Out-Null
$archive = Join-Path $toolsRoot 'llvm-mingw-20260922-ucrt-x86_64.zip'
$compiler = Join-Path $toolsRoot 'llvm-mingw-20260922-ucrt-x86_64\bin\clang++.exe'
if(!(Test-Path -LiteralPath $compiler)) {
    curl.exe --ssl-revoke-best-effort -fL --retry 2 -o $archive 'https://github.com/mstorsjo/llvm-mingw/releases/download/20260922/llvm-mingw-20260922-ucrt-x86_64.zip'
    if($LASTEXITCODE -ne 0){throw 'Compiler download failed'}
    if((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne 'e3ad77d117a4bea19a7a3b333341824d79a5a371004a10e25b8504e7b3047666'){throw 'Compiler checksum mismatch'}
    Expand-Archive -LiteralPath $archive -DestinationPath $toolsRoot -Force
}
foreach($dependency in @(
    @{Name='minhook';Repo='https://github.com/TsudaKageyu/minhook.git';Tag='v1.3.4';Commit='c3fcafdc10146beb5919319d0683e44e3c30d537'},
    @{Name='imgui';Repo='https://github.com/ocornut/imgui.git';Tag='v1.91.9b';Commit='f5befd2d29e66809cd1110a152e375a7f1981f06'}
)) {
    $destination = Join-Path $projectRoot "third_party\$($dependency.Name)"
    if(!(Test-Path -LiteralPath "$destination\.git")){
        git clone --depth 1 --branch $dependency.Tag $dependency.Repo $destination
        if($LASTEXITCODE -ne 0){throw 'Dependency download failed'}
    }
    $dependencyDirectory = Get-Item -LiteralPath (Join-Path $projectRoot 'third_party')
    $repositoryForCheck = $destination
    if($dependencyDirectory.LinkType){$repositoryForCheck=Join-Path $dependencyDirectory.ResolveLinkTarget($true).FullName $dependency.Name}
    $actual = git -c "safe.directory=$repositoryForCheck" -C $destination rev-parse HEAD
    if($LASTEXITCODE -ne 0){throw "Cannot inspect pinned dependency: $($dependency.Name)"}
    if($actual -ne $dependency.Commit){throw "Dependency commit mismatch: $($dependency.Name)"}
}
Write-Output 'Pinned portable compiler and dependencies ready.'

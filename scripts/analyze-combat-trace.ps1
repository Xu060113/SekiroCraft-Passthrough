param([string]$TracePath)
$ErrorActionPreference='Stop'
$traceProject=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if(!$TracePath){$TracePath=(Get-ChildItem -LiteralPath (Join-Path $traceProject 'runtime') -Filter 'combat-trace-*.jsonl' | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName}
if(!$TracePath -or !(Test-Path -LiteralPath $TracePath)){throw 'No combat trace has been recorded'}
$traceLines=@(Get-Content -LiteralPath $TracePath)
$traceEvents=@();$traceStopped=$false;$traceDropped=$null
for($traceIndex=0;$traceIndex -lt $traceLines.Count;$traceIndex++){
 try{$traceEvent=$traceLines[$traceIndex]|ConvertFrom-Json}catch{
  if($traceIndex -eq $traceLines.Count-1){continue};throw 'Malformed complete trace record'
 }
 if($traceEvent.stopped){$traceStopped=$true;$traceDropped=$traceEvent.dropped}
 if($traceEvent.kind){$traceEvents+=$traceEvent}
}
$traceChanges=@($traceEvents | Where-Object {$_.after.hp -ne $_.before.hp -or $_.after.posture -ne $_.before.posture -or $_.after.bossNode -ne $_.before.bossNode})
$traceCandidates=@($traceChanges | Group-Object kind,source,@{Expression={if($_.stackRva.Count){$_.stackRva[0]}else{'unknown'}}} | ForEach-Object {
 $traceFirst=$_.Group[0]
 @{kind=$traceFirst.kind;source=$traceFirst.source;firstCallerRva=$traceFirst.stackRva[0];count=$_.Count;
   stackRva=$traceFirst.stackRva;hpDelta=$traceFirst.after.hp-$traceFirst.before.hp;
   postureDelta=$traceFirst.after.posture-$traceFirst.before.posture;
   bossNodeBefore=$traceFirst.before.bossNode;bossNodeAfter=$traceFirst.after.bossNode}
})
$traceReport=@{trace=$TracePath;records=$traceEvents.Count;effectiveChanges=$traceChanges.Count;
 stopped=$traceStopped;dropped=$traceDropped;calls=$traceCandidates;
 nativeHpInjuries=@($traceChanges|Where-Object {$_.source -eq 'native' -and $_.after.hp -lt $_.before.hp}).Count;
 nativePostureLosses=@($traceChanges|Where-Object {$_.source -eq 'native' -and $_.after.posture -lt $_.before.posture}).Count;
 bossNodeTransitions=@($traceChanges|Where-Object {$_.before.bossNode -ne $_.after.bossNode}).Count;
 nativeDamageAbiVerified=$false;nativeDeathblowAbiVerified=$false}
$traceReport|ConvertTo-Json -Depth 8

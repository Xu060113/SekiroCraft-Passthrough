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
$traceNativeHits=@($traceEvents|Where-Object {$_.kind -eq 'native-hit-entry'})
$traceAnimations=@($traceEvents|Where-Object {$_.kind -eq 'animation-state'})
$traceAnimationStates=@($traceAnimations|ForEach-Object {
 @{tick=$_.tick;id=$_.target;valid=[bool]$_.recovery;hero=$_.args[0];module=$_.args[1];owner=$_.args[2];actionFlags=[Convert]::ToUInt32($_.args[3].Substring(2),16)}
})
$tracePhaseChanges=@();$tracePending=@{}
foreach($traceEvent in $traceEvents){
 $traceKey='{0}:{1}' -f $traceEvent.thread,$traceEvent.data
 if($traceEvent.kind -eq 'native-hit-entry'){
  $traceType=$null
  if($traceEvent.hitBytes -and $traceEvent.hitBytes.Length -ge 88){
   $traceTypeBytes=[byte[]]::new(4)
   for($traceByte=0;$traceByte -lt 4;$traceByte++){$traceTypeBytes[$traceByte]=[Convert]::ToByte($traceEvent.hitBytes.Substring(80+2*$traceByte,2),16)}
   $traceType=[BitConverter]::ToInt32($traceTypeBytes,0)
  }
  if($traceType -eq 5 -and $traceEvent.before.bossNode -gt 0){$tracePending[$traceKey]=$traceEvent}
 }elseif($tracePending.ContainsKey($traceKey)){
  $traceHit=$tracePending[$traceKey]
  $traceAge=[long]$traceEvent.tick-[long]$traceHit.tick
  if($traceAge -lt 0 -or $traceAge -gt 64 -or $traceHit.before.maxHp -ne $traceEvent.before.maxHp){$tracePending.Remove($traceKey)}
  elseif($traceHit.before.bossNode-1 -eq $traceEvent.before.bossNode){
   $tracePhaseChanges+=@{hitId=$traceHit.hitId;tick=$traceEvent.tick;thread=$traceEvent.thread;
     source=$traceHit.source;bossNodeBefore=$traceHit.before.bossNode;bossNodeAfter=$traceEvent.before.bossNode}
   $tracePending.Remove($traceKey)
  }
 }
}
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
 nativeHitEntries=$traceNativeHits.Count;correlatedBossPhaseChanges=$tracePhaseChanges;
 animationSamples=$traceAnimations.Count;validAnimationSamples=@($traceAnimations|Where-Object {$_.recovery -eq 1}).Count;animationStates=$traceAnimationStates;
 nativeDamageAbiVerified=$false;nativeDeathblowAbiVerified=$false}
$traceReport|ConvertTo-Json -Depth 8

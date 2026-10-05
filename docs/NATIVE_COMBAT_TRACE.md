# Native combat investigation

This is an opt-in diagnostic for the already fingerprinted Sekiro 1.06
executable. Set `combat_trace=1` only while collecting a native gameplay sample.
The default is `0`. Native normal-hit dispatch is separately opt-in with `native_hits=1`. The candidate phase profile remains off with `native_phase_finish=0` until directed validation.

The original HP and posture setters remain responsible for their results.
The trace records requested and actual values, the native thread and executable
return-address RVAs. It labels bridge writes separately. A bounded queue is
drained by a writer thread; game setter callbacks do not write files. Diagnostic
files stay under the configured `data_root` and are excluded from Git archives.

On 2026-10-05 the first live capture recorded 9 actual HP losses and 11 posture
losses, with zero dropped records. Their common native path is:

```
B68FF0 -> B6F690 -> B6E6A0 -> BD4D40 -> BD64E0
                 (HP / posture processing)
```

HP samples returned through `B6E897`; posture samples returned through `B6EB48`.
`B69D0D` also appeared for posture loss. Initialization and healing have separate
call paths. These are observed call sites, not a complete native damage API.
This first capture does not prove Boss phase transitions or native deathblows.

The second live capture has 119 native hit-entry records and zero drops. The
user performed ordinary and Boss deathblows. Boss sample 67 has 713 / 1273 HP,
depleted posture and two remaining nodes. Its third argument has int32 value
5 at offset 0x28; the next HP record on the same thread and tick has one node.
The native branch at B6E800 decrements ChrData+0x25C and calls 9F0410. This
verifies an observed native Boss phase change, not MC-driven deathblows or
final rewards. Correlation uses a 64 ms window, thread, actor data and max HP.

The caller at 9E64C6 dispatches the three-argument method at vtable offset
0x48 of ChrIns.modules+0x98. The observed entry is B6A040. The packet is
initialized by 997890 / 997CF0. Its actor pointers at 0x190 / 0x198 must be
resolved for each dispatch; trace addresses can become invalid after respawn.

An experimental normal-hit backend is available with `native_hits=1` (default
0). It constructs a zeroed, owned packet using the native initializer, fills
explicit scalar fields of the observed ordinary Kusabimaru profile, resolves
the PC attack PARAM row on each call, and calls B6A040. Its ordinary damage path uses attack type 1. A separate disabled phase candidate constructs type 5 with a fresh initializer and the scalar identity of Boss sample 67. Signature matching alone does not prove script gates, animation or rewards; do not enable it as an accepted release feature. Pending MC commands drain only at the signature
guarded AttackManager update 9A0DC0 with a current manager, positive frame
delta and fresh peer/session. Physics callbacks do not dispatch these hits.
Every relevant code entry and both current damage-module owners are checked.
Commands rejected by a guard are acknowledged without retry or HP fallback.
Engine block/deflect, damage values, hit reactions and rewards need live
acceptance; the backend is not a complete native combat API.
The periodic log reports `nativeHitFailure`: 0 dispatched, 1 invalid input,
2 target module, 3 attacker module, 4 PARAM manager, 5 missing attack row, 6 invalid impact geometry, 7 phase profile signature mismatch. Phase counters describe dispatched calls and immediate node observations only; they do not assert reward completion.

Compatibility HP damage now retains at least 1 HP for actors with remaining
Boss nodes even when their NoDeath bit is absent. It never edits those nodes.
Action control now observes the read-only animation chain WorldChrMan -> hero+1FF8 -> module+10 -> id+20. Its owner checks and idle-return condition require live validation; the fixed minimum wait is removed. A changed animation confirms a request, two baseline samples return control, an unstarted request expires at 500 ms, and a 20 s watchdog bounds a stuck handoff. Native-menu time is excluded from that watchdog.

The second diagnostic additionally records the entry at `B68FF0`, guarded by
its complete 24-byte prologue. An assembly probe saves volatile integer/SIMD
registers, flags and MXCSR, calls the recorder, restores state and tail-jumps to
the original trampoline. It does not assume an ApplyDamage return type. Entry
records contain four argument values and bounded readable snapshots of the
second and third arguments. Pointers are diagnostic values only; the bridge
never replays a captured pointer or packet.

Recording stops after ten minutes without combat activity, sixty minutes total,
or when `data_root/combat-trace.stop` exists. The hooks continue forwarding
original calls after recording stops. Remove an existing stop marker before a
new collection. `scripts/analyze-combat-trace.ps1` summarizes the latest capture
without claiming that a damage/deathblow ABI has been verified.

The native engine permits posture remainders down to -100. The adapter now
publishes these as a full gauge while retaining maximum posture and Boss node
data. Further MC posture damage does not raise an already broken negative
remainder to zero.

Verification: 49 production trace checks cover native detours, original arguments,
return values, register/SIMD/flag preservation, bounded snapshots, asynchronous
writing and native/bridge labeling; 36 production combat checks cover the
existing adapter, negative-posture behavior and Boss HP floor; 49 native-hit
checks cover update-hook register preservation, current owners, PARAM lookup,
fresh peer/frame guards and command deduplication. Live acceptance of MC-driven
hit reactions, Boss deathblows and rewards remains pending.

Animation records have `kind:"animation-state"`; actionFlags in args[3] identify handoff=1, confirmed=2, R=4, G=8, native UI=16, MC owner=32. They correlate current animation id, module/hero identity and HP/posture/node with hit records. GUI tracing uses MC `sekirobridge/bridge.properties` `gui_trace=true`, limited to 64 clicks, with the actual HandledScreen slot and a delayed integrated-server revision/cursor observation.

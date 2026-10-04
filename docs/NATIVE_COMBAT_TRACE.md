# Native combat investigation

This is an opt-in diagnostic for the already fingerprinted Sekiro 1.06
executable. Set `combat_trace=1` only while collecting a native gameplay sample.
The default is `0`. No Minecraft attack is dispatched through an unverified
native hit or deathblow ABI.

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

Verification: 48 production trace checks cover native detours, original arguments,
return values, register/SIMD/flag preservation, bounded snapshots, asynchronous
writing and native/bridge labeling; 34 production combat checks cover the
existing adapter and negative-posture behavior. Live acceptance of MC-driven
hit reactions, Boss deathblows and rewards remains pending.

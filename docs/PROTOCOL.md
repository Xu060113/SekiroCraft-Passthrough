# Shared-memory protocol v1

All integers and IEEE-754 floats use little endian. C++ layouts are statically asserted; Java uses explicit byte offsets and is checked against C++ binary fixtures. Win32 `GetTickCount64()` is the only time base, exposed through JNI to avoid mixing Java nanoTime with the host clock.

Names: `Local\SekiroBridge-<channel>` and its `-lock` mutex. Channel names contain only ASCII letters, digits, hyphens or underscores, maximum 64 characters. Both participants create/open the same mapping, with a default user/session ACL. Use distinct channel names for additional pairs.

| Structure | Size | Fields |
| --- | ---: | --- |
| Header | 248 bytes | magic `SBP1`, version, mapping size, Control, newest frame, peer status/time/epoch |
| Control | 200 bytes | sequence/time/epoch, flags/capabilities, player/eye/forward, camera lens, mapping offset/scale, output dimensions, VK bitmap, pointer/buttons/wheel, command and UTF32 stream |
| FrameMeta | 96 bytes | sequence/time/epoch, source control sequence, dimensions/flags, capture camera/lens and source control timestamp |
| Frame payload | width × height × 12 | RGBA8 world, float32 OpenGL depth [0,1], RGBA8 premultiplied overlay |

The mapping contains one Header and three fixed-capacity frame slots. Maximum dimensions are 1920×1080; capacity is 74,650,136 bytes. Default capture width is 1280, with height following the host camera aspect. The mapping owns the publication sequence, so restarting the MC producer does not reuse old frame numbers. A new host process supplies a new epoch; frames from an earlier epoch cannot be drawn.

The mutex always uses timeout zero on both the render thread and publisher. The C++ receiver copies a completed frame on a worker thread and exposes an immutable CPU snapshot through a short try-lock. A busy peer drops or skips a frame; the render path does not wait for IPC or perform a synchronous GPU readback. Triple-slot storage is protected by the named mutex, not a lock-free seqlock.

Frames must carry a valid source control timestamp as well as a publication time, both at most 350 ms old. This prevents delayed GL readbacks from becoming fresh merely because they were published later. Scene/focus and native depth freshness are additionally checked before drawing; the original hero is hidden only after a successful composite submission. A large camera discontinuity rejects the frame; normal camera differences use bounded iterative reprojection.

Control flags: scene=1, host focus=2, MC edit=4. Capability bits: camera=1, input=2, depth composite=4, terrain columns=8, native block collision=16, native combat=32. This version advertises only the first three. Reserved features are not simulated or falsely acknowledged.

Coordinates: Sekiro `(x,y,z)` to MC `(x×scale,y×scale+y_offset,-z×scale)`. MC projection near/far use the same scale; exported metadata retains host units so normalized depth reconstructs a host-space distance. MC camera yaw/pitch follows the native forward vector; native roll is not yet transmitted.

Wheel is cumulative, F9 command is edge counted, and UTF32 input is an eight-entry circular stream with a cumulative sequence. A peer reconnect initializes its input cursors to the latest state, releases previously owned key/button presses and avoids replaying old text or test-block requests. The bounded text ring may lose characters if the peer misses more than eight characters before reading again; IME composition forwarding is not implemented.

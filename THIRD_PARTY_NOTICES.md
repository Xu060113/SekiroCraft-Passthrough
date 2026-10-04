# Components and references

- MinHook 1.3.4, commit c3fcafdc10146beb5919319d0683e44e3c30d537. BSD-style license included as MinHook-LICENSE.txt. https://github.com/TsudaKageyu/minhook
- Dear ImGui 1.91.9b, commit f5befd2d29e66809cd1110a152e375a7f1981f06. MIT license included as ImGui-LICENSE.txt. https://github.com/ocornut/imgui
- Fabric API 0.92.2+1.20.1, Apache 2.0; distributed as its original dependency JAR with embedded notices/licenses. https://github.com/FabricMC/fabric
- Fabric Loader 0.16.10, Fabric Loom 1.6.12 and Yarn 1.20.1+build.10 are development/runtime requirements. https://fabricmc.net/
- Gradle 8.8 and llvm-mingw 20260922 are development tools; not included in mod or source releases.
- Architectural reference only: https://github.com/rehan-remade/universal-modder/tree/main/examples/minecraft-gta5-passthrough . No source from that example is copied; the shared-memory/JNI protocol, MC 1.20.1 integration and Sekiro compositor are this project's implementation.
- Authored C++ proxy, math, read-only Sekiro host accessor and D3D11 context hook code originate from this user's preserved SekiroCraft 0.4.0 project, baseline 5e96627b3872dee6e181276c4bf71825be8e9d89.
- Sekiro pointer and appearance-flag research: https://github.com/veeenu/sekiro-practice-tool/blob/master/lib/libsekiro/src/pointers.rs and https://github.com/mstampfli/sekiro-coop/blob/main/crates/sekiro-sdk-sys/src/live.rs . Native data is gated by the exact executable fingerprint. No original Sekiro save, executable or game resource is distributed.

Minecraft and Sekiro are not bundled. No game textures, maps, skins, source or decompiled game classes are included. The optional development caches are excluded from Git and distribution.

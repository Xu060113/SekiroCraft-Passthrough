#include "shared_memory.hpp"
#include <jni.h>

// Handles are owned by NativeBridge and used on Minecraft's render thread only.
extern "C" {
JNIEXPORT jint JNICALL Java_dev_sekirobridge_NativeBridge_abiVersion(JNIEnv*,jclass){return bridge::version;}
JNIEXPORT jboolean JNICALL Java_dev_sekirobridge_NativeBridge_projectileRays(JNIEnv *env,jclass,jlong handle,jobject b) {
    if(!handle || !b || env->GetDirectBufferCapacity(b)<jlong(sizeof(bridge::ProjectileRays)))return false;
    auto ptr=env->GetDirectBufferAddress(b);if(!ptr)return false;
    bridge::ProjectileRays p;std::memcpy(&p,ptr,sizeof(p));
    return bridge::validRays(p) && reinterpret_cast<bridge::SharedMemory*>(handle)->projectileRays.write(p);
}
JNIEXPORT jboolean JNICALL Java_dev_sekirobridge_NativeBridge_projectileHits(JNIEnv *env,jclass,jlong handle,jobject b) {
    if(!handle || !b || env->GetDirectBufferCapacity(b)<jlong(sizeof(bridge::ProjectileHits)))return false;
    auto ptr=env->GetDirectBufferAddress(b);if(!ptr)return false;
    bridge::ProjectileHits p;
    if(!reinterpret_cast<bridge::SharedMemory*>(handle)->projectileHits.read(p) || !bridge::validHits(p))return false;
    std::memcpy(ptr,&p,sizeof(p));return true;
}
JNIEXPORT jboolean JNICALL Java_dev_sekirobridge_NativeBridge_nativeAction(JNIEnv *env,jclass,jlong handle,jobject b) {
    if(!handle || !b || env->GetDirectBufferCapacity(b)<jlong(sizeof(bridge::NativeActionRequest)))return false;
    auto ptr=env->GetDirectBufferAddress(b);if(!ptr)return false;
    bridge::NativeActionRequest p;std::memcpy(&p,ptr,sizeof(p));
    return bridge::validAction(p) && reinterpret_cast<bridge::SharedMemory*>(handle)->nativeAction.write(p);
}
JNIEXPORT jlong JNICALL Java_dev_sekirobridge_NativeBridge_open(JNIEnv *env, jclass, jstring channel) {
    if (!channel)
        return 0;
    const jchar *s = env->GetStringChars(channel, nullptr);
    if (!s)
        return 0;
    std::wstring name(reinterpret_cast<const wchar_t *>(s), env->GetStringLength(channel));
    env->ReleaseStringChars(channel, s);
    auto m = std::make_unique<bridge::SharedMemory>();
    if (!m->open(name))
        return 0;
    return reinterpret_cast<jlong>(m.release());
}
JNIEXPORT void JNICALL Java_dev_sekirobridge_NativeBridge_close(JNIEnv *, jclass, jlong handle) {
    delete reinterpret_cast<bridge::SharedMemory *>(handle);
}
JNIEXPORT jboolean JNICALL Java_dev_sekirobridge_NativeBridge_control(JNIEnv *env, jclass, jlong handle,
                                                                      jobject buffer) {
    if (!handle || !buffer || env->GetDirectBufferCapacity(buffer) < jlong(sizeof(bridge::Control)))
        return false;
    auto p = env->GetDirectBufferAddress(buffer);
    if (!p)
        return false;
    bridge::Control c;
    if (!reinterpret_cast<bridge::SharedMemory *>(handle)->readControl(c))
        return false;
    std::memcpy(p, &c, sizeof(c));
    return true;
}
JNIEXPORT jboolean JNICALL Java_dev_sekirobridge_NativeBridge_publish(JNIEnv *env, jclass, jlong handle,
                                                                      jobject metadata, jobject pixels) {
    if (!handle || !metadata || !pixels ||
        env->GetDirectBufferCapacity(metadata) < jlong(sizeof(bridge::FrameMeta)))
        return false;
    auto m = env->GetDirectBufferAddress(metadata);
    auto p = env->GetDirectBufferAddress(pixels);
    if (!m || !p)
        return false;
    bridge::FrameMeta meta;
    std::memcpy(&meta, m, sizeof(meta));
    if (!bridge::valid(meta) || env->GetDirectBufferCapacity(pixels) < int64_t(bridge::frameBytes(meta)))
        return false;
    return reinterpret_cast<bridge::SharedMemory *>(handle)->publish(
        meta, {static_cast<const uint8_t *>(p), size_t(bridge::frameBytes(meta))});
}
JNIEXPORT jboolean JNICALL Java_dev_sekirobridge_NativeBridge_status(JNIEnv *, jclass, jlong handle,
                                                                     jint flags, jlong epoch) {
    return handle &&
           reinterpret_cast<bridge::SharedMemory *>(handle)->status(uint32_t(flags), uint64_t(epoch));
}
JNIEXPORT jlong JNICALL Java_dev_sekirobridge_NativeBridge_clockMs(JNIEnv *, jclass) {
    return GetTickCount64();
}
JNIEXPORT jboolean JNICALL Java_dev_sekirobridge_NativeBridge_physics(JNIEnv *env, jclass, jlong handle,
                                                                      jobject buffer) {
    if (!handle || !buffer || env->GetDirectBufferCapacity(buffer) < jlong(sizeof(bridge::PhysicsPacket)))
        return false;
    auto bytes = env->GetDirectBufferAddress(buffer); if (!bytes) return false;
    bridge::PhysicsPacket p; std::memcpy(&p, bytes, sizeof(p));
    return reinterpret_cast<bridge::SharedMemory *>(handle)->physics.write(p);
}
JNIEXPORT jboolean JNICALL Java_dev_sekirobridge_NativeBridge_input(JNIEnv *env,jclass,jlong handle,jobject b) {
    if(!handle || !b || env->GetDirectBufferCapacity(b)<jlong(sizeof(bridge::InputPacket)))return false;
    auto ptr=env->GetDirectBufferAddress(b);if(!ptr)return false;
    bridge::InputPacket p;
    if(!reinterpret_cast<bridge::SharedMemory*>(handle)->input.read(p) || !bridge::validInput(p))return false;
    std::memcpy(ptr,&p,sizeof(p));return true;
}
JNIEXPORT jboolean JNICALL Java_dev_sekirobridge_NativeBridge_player(JNIEnv *env,jclass,jlong handle,jobject b) {
    if(!handle || !b || env->GetDirectBufferCapacity(b)<jlong(sizeof(bridge::PlayerPacket)))return false;
    auto ptr=env->GetDirectBufferAddress(b);if(!ptr)return false;
    bridge::PlayerPacket p;std::memcpy(&p,ptr,sizeof(p));
    return bridge::validPlayer(p) && reinterpret_cast<bridge::SharedMemory*>(handle)->player.write(p);
}
JNIEXPORT jboolean JNICALL Java_dev_sekirobridge_NativeBridge_combatState(JNIEnv *env,jclass,jlong handle,jobject b) {
    if(!handle || !b || env->GetDirectBufferCapacity(b)<jlong(sizeof(bridge::CombatState)))return false;
    auto ptr=env->GetDirectBufferAddress(b);if(!ptr)return false;
    bridge::CombatState p;
    if(!reinterpret_cast<bridge::SharedMemory*>(handle)->combatState.read(p) || !bridge::validCombat(p))return false;
    std::memcpy(ptr,&p,sizeof(p));return true;
}
JNIEXPORT jboolean JNICALL Java_dev_sekirobridge_NativeBridge_combatReport(JNIEnv *env,jclass,jlong handle,jobject b) {
    if(!handle || !b || env->GetDirectBufferCapacity(b)<jlong(sizeof(bridge::CombatReport)))return false;
    auto ptr=env->GetDirectBufferAddress(b);if(!ptr)return false;
    bridge::CombatReport p;std::memcpy(&p,ptr,sizeof(p));
    return bridge::validCombat(p) && reinterpret_cast<bridge::SharedMemory*>(handle)->combatReport.write(p);
}
JNIEXPORT jboolean JNICALL Java_dev_sekirobridge_NativeBridge_actorShapes(JNIEnv *env,jclass,jlong handle,jobject b) {
    if(!handle || !b || env->GetDirectBufferCapacity(b)<jlong(sizeof(bridge::ActorShapes)))return false;
    auto ptr=env->GetDirectBufferAddress(b);if(!ptr)return false;
    bridge::ActorShapes p;
    if(!reinterpret_cast<bridge::SharedMemory*>(handle)->actorShapes.read(p) || !bridge::validActorShapes(p))return false;
    std::memcpy(ptr,&p,sizeof(p));return true;
}
JNIEXPORT jboolean JNICALL Java_dev_sekirobridge_NativeBridge_actorParts(JNIEnv *env,jclass,jlong handle,jobject b) {
    if(!handle || !b || env->GetDirectBufferCapacity(b)<jlong(sizeof(bridge::ActorParts)))return false;
    auto dst=env->GetDirectBufferAddress(b);if(!dst)return false;
    auto packet=std::make_unique<bridge::ActorParts>();
    if(!reinterpret_cast<bridge::SharedMemory*>(handle)->actorParts.read(*packet) || !bridge::validActorParts(*packet))return false;
    std::memcpy(dst,packet.get(),sizeof(*packet));return true;
}
JNIEXPORT jboolean JNICALL Java_dev_sekirobridge_NativeBridge_nativeInjuries(JNIEnv *env,jclass,jlong handle,jobject b) {
    if(!handle || !b || env->GetDirectBufferCapacity(b)<jlong(sizeof(bridge::NativeInjuries)))return false;
    auto ptr=env->GetDirectBufferAddress(b);if(!ptr)return false;
    bridge::NativeInjuries p;
    if(!reinterpret_cast<bridge::SharedMemory*>(handle)->nativeInjuries.read(p) || !bridge::validInjuries(p))return false;
    std::memcpy(ptr,&p,sizeof(p));return true;
}
JNIEXPORT jboolean JNICALL Java_dev_sekirobridge_NativeBridge_nativeInjuryAck(JNIEnv *env,jclass,jlong handle,jobject b) {
    if(!handle || !b || env->GetDirectBufferCapacity(b)<jlong(sizeof(bridge::NativeInjuryAck)))return false;
    auto ptr=env->GetDirectBufferAddress(b);if(!ptr)return false;
    bridge::NativeInjuryAck p;std::memcpy(&p,ptr,sizeof(p));
    return p.tick && p.epoch && p.hero && p.session && reinterpret_cast<bridge::SharedMemory*>(handle)->nativeInjuryAck.write(p);
}
JNIEXPORT jboolean JNICALL Java_dev_sekirobridge_NativeBridge_terrain(JNIEnv *env,jclass,jlong handle,jobject b) {
    if(!handle || !b || env->GetDirectBufferCapacity(b)<jlong(sizeof(bridge::TerrainPacket)))return false;
    auto ptr=env->GetDirectBufferAddress(b);if(!ptr)return false;
    bridge::TerrainPacket p;
    if(!reinterpret_cast<bridge::SharedMemory*>(handle)->terrain.read(p) || !bridge::validTerrain(p))return false;
    std::memcpy(ptr,&p,sizeof(p));return true;
}
}

#include "movement_hook.hpp"
extern "C" {
void *scMovementContinue{};
void (*scMovementHandler)(uintptr_t, float *) noexcept{};
void *scCameraContinue{};
void (*scCameraHandler)(uintptr_t) noexcept{};
}

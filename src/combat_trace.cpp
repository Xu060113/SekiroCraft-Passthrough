#include "combat_trace.hpp"
extern "C" {
void *scCombatHitTraceContinue{};
void (*scCombatHitTraceCallback)(uintptr_t,uintptr_t,uintptr_t,uintptr_t){};
}

#include "combat_game_hook.hpp"
extern "C" {
void *scCombatGameContinue{};
void (*scCombatGameCallback)(uintptr_t,float) noexcept{};
}

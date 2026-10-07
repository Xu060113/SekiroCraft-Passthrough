package dev.sekirobridge;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.mob.Monster;
import net.minecraft.world.World;

/** Native enemy target recognized on both sides before its first received hit.
 * Keep friendly/native neutral actors on the base type: area weapons must not
 * classify every nearby Sekiro NPC as a monster. No SlashBlade dependency. */
public final class NativeEnemyProxy extends NativeActorProxy implements Monster {
    public NativeEnemyProxy(EntityType<? extends NativeEnemyProxy> type,World world){super(type,world);}
}

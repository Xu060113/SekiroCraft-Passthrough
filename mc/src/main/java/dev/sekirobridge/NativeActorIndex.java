package dev.sekirobridge;
import net.minecraft.world.World;
import java.lang.ref.WeakReference;
import java.util.*;

/** Minecraft indexes an entity by its root section. A long arm/tail may be in
 * a different section; keep a bounded side index without adding duplicate mobs. */
public final class NativeActorIndex {
    private static final Map<World,Map<Integer,WeakReference<NativeActorProxy>>> worlds=new WeakHashMap<>();
    static synchronized void track(NativeActorProxy actor){
        worlds.computeIfAbsent(actor.getWorld(),w->new HashMap<>()).put(actor.getId(),new WeakReference<>(actor));
    }
    public static synchronized List<NativeActorProxy> actors(World world){
        var entries=worlds.get(world);if(entries==null)return List.of();
        var result=new ArrayList<NativeActorProxy>();
        entries.values().removeIf(ref->{var actor=ref.get();if(actor==null || actor.isRemoved())return true;
            if(actor.getBoundingBox() instanceof NativeActorBounds)result.add(actor);return false;});
        return result;
    }
}

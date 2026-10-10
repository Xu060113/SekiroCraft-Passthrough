package dev.sekirobridge;

import java.util.function.BooleanSupplier;

/** Distinguishes vanilla pose-fit checks from movement and placement collisions. */
public final class TerrainPoseProbe {
    private static final ThreadLocal<Object> entity=new ThreadLocal<>();
    private TerrainPoseProbe(){}
    static boolean active(Object candidate){return candidate!=null && entity.get()==candidate;}
    public static boolean test(Object candidate,BooleanSupplier fits){
        var previous=entity.get();
        entity.set(candidate);
        try{return fits.getAsBoolean();}
        finally{if(previous==null)entity.remove();else entity.set(previous);}
    }
}

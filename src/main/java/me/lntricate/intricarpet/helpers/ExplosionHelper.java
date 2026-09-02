package me.lntricate.intricarpet.helpers;

import net.minecraft.world.phys.Vec3;

public class ExplosionHelper {
    private static Vec3 POS = null;
    private static int COUNT_IN_POS = 0;
    private static int COUNT_IN_TICK = 0;
    private static long TICK = 0;
    private static long TIME = 0;
    private static boolean AFFECT_BLOCKS;

    public static Vec3 getPos() {
        return POS;
    }

    public static int getCountInPos() {
        return COUNT_IN_POS;
    }

    public static int getCountInTick() {
        return COUNT_IN_TICK;
    }

    public static long getTick() {
        return TICK;
    }

    public static long getTime() {
        return TIME;
    }

    public static boolean getAffectBlocks() {
        return AFFECT_BLOCKS;
    }

    public static boolean isNew(Vec3 newPos, long newTick) {
        return TICK != newTick || !POS.equals(newPos);
    }

    public static boolean isEmpty() {
        return POS == null;
    }

    public static void registerNewPos(
        Vec3 newPos, long newTick, long newTime, boolean newAffectBlocks) {
        POS = newPos;
        TICK = newTick;
        COUNT_IN_POS = 1;
        COUNT_IN_TICK += 1;
        TIME = newTime;
        AFFECT_BLOCKS = newAffectBlocks;
    }

    public static void clear() {
        POS = null;
        COUNT_IN_TICK = 0;
    }

    public static void incrementCounts() {
        COUNT_IN_POS++;
        COUNT_IN_TICK++;
    }
}

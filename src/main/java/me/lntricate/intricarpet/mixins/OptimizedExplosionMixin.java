package me.lntricate.intricarpet.mixins;

import carpet.helpers.OptimizedExplosion;
import carpet.logging.logHelpers.ExplosionLogHelper;
import carpet.mixins.ExplosionAccessor;
import me.lntricate.intricarpet.Rules;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.At.Shift;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

@Mixin(OptimizedExplosion.class)
public class OptimizedExplosionMixin {
    // Carpet 26 dropped `OptimizedExplosion#doExplosionA`, and with it the edge case this fixes.
    #[cfg(not(feature = "since-26"))]
    @Unique
    private static final double SAME_POSITION_VELOCITY = 0.9923437498509884;

    #[cfg(not(feature = "since-26"))]
    @Inject(
        method = "doExplosionA",
        at =
            @At(
                value = "INVOKE",
                target = "Lnet/minecraft/world/entity/Entity;getZ()D",
                ordinal = 1,
                shift = Shift.BY,
                by = 3),
        locals = LocalCapture.CAPTURE_FAILHARD)
    private static void onSamePosition(
        Explosion e,
        ExplosionLogHelper eLogger,
        CallbackInfo ci,
        ExplosionAccessor eAccess,
        boolean eventNeeded,
        float f3,
        int k1,
        int l1,
        int i2,
        int i1,
        int j2,
        int j1,
        Vec3 vec3d,
        Entity explodingEntity,
        int k2,
        Entity entity) {
        // `Entity#isOnGround` is `onGround` in the official mappings from 1.20 until 26.1 renamed it
        // back. `since-1.20` alone is the whole band here: this method only exists under the
        // `not(since-26)` above, so the releases that would answer the predicate the other way are
        // not compiling this line at all.
        #[cfg(feature = "since-1.20")] boolean grounded = entity.onGround();
        #[cfg(not(feature = "since-1.20"))] boolean grounded = entity.isOnGround();
        if (Rules.optimizedTNTEdgeCases || !grounded) {
            Vec3 vel = entity.getDeltaMovement();
            entity.setDeltaMovement(vel.x, vel.y - SAME_POSITION_VELOCITY, vel.z);
        }
    }
}

package me.lntricate.intricarpet.mixins.logger;

import carpet.logging.logHelpers.ExplosionLogHelper;
import com.llamalad7.mixinextras.injector.ModifyReceiver;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ExplosionLogHelper.class)
public class ExplosionLogHelperMixin {
    @Shadow
    @Final
    private Vec3 pos;
    @Shadow(remap = false)
    private boolean affectBlocks;

    @Inject(method = "onExplosionDone", at = @At("HEAD"), remap = false)
    private void onExplosionDone(long gametime, CallbackInfo _ci) {
        me.lntricate.intricarpet.logging.logHelpers.ExplosionLogHelper.onExplosion(
            this.pos, gametime, this.affectBlocks);
    }

    private String option = "";

    // Carpet 26 dropped a lambda from `onExplosionDone`, so the two the injectors below target moved
    // from `$1` to `$0`, and the surviving one now yields the whole message array.
    #[cfg(feature = "since-26")]
    @Inject(method = "lambda$onExplosionDone$0", at = @At("HEAD"), remap = false)
    private void getOption(
        long _gametime, String option, CallbackInfoReturnable<Component[]> _cir) {
        this.option = option;
    }

    #[cfg(not(feature = "since-26"))]
    @Inject(method = "lambda$onExplosionDone$1", at = @At("HEAD"), remap = false)
    private void getOption(long _gametime, String option, CallbackInfoReturnable<Component> _cir) {
        this.option = option;
    }

    #[cfg(feature = "since-26")]
    @ModifyReceiver(
        method = "lambda$onExplosionDone$0",
        at =
            @At(
                value = "INVOKE",
                target = "Ljava/util/List;toArray([Ljava/lang/Object;)[Ljava/lang/Object;",
                remap = false))
    private List<Component> addLoggers(List<Component> messages, Object[] _dummy) {
        return me.lntricate.intricarpet.logging.logHelpers.ExplosionLogHelper.onLog(
            messages, this.option);
    }

    #[cfg(not(feature = "since-26"))]
    @ModifyReceiver(
        method = "lambda$onExplosionDone$1",
        at =
            @At(
                value = "INVOKE",
                target = "Ljava/util/List;toArray([Ljava/lang/Object;)[Ljava/lang/Object;",
                remap = false))
    private List<Component> addLoggers(List<Component> messages, Object[] _dummy) {
        return me.lntricate.intricarpet.logging.logHelpers.ExplosionLogHelper.onLog(
            messages, this.option);
    }
}

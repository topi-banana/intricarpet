package me.lntricate.intricarpet.mixins.logger;

import java.util.List;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.llamalad7.mixinextras.injector.ModifyReceiver;

import carpet.logging.logHelpers.ExplosionLogHelper;
// 1.19 folded BaseComponent into Component.
#[cfg(not(feature = "mc-ge-1.19.2"))]
import net.minecraft.network.chat.BaseComponent;
#[cfg(feature = "mc-ge-1.19.2")]
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

@Mixin(ExplosionLogHelper.class)
public class ExplosionLogHelperMixin
{
  @Shadow @Final private Vec3 pos;
  @Shadow(remap = false) private boolean affectBlocks;

  @Inject(method = "onExplosionDone", at = @At("HEAD"), remap = false)
  private void onExplosionDone(long gametime, CallbackInfo ci)
  {
    me.lntricate.intricarpet.logging.logHelpers.ExplosionLogHelper.onExplosion(pos, gametime, affectBlocks);
  }

  private String option = "";

  // 26.1 dropped a lambda from ExplosionLogHelper#onExplosionDone, so the synthetic name of the
  // one this injects into moves from $1 to $0.
  #[cfg(feature = "mc-ge-26.1.2")]
  @Inject(method = "lambda$onExplosionDone$0", at = @At("HEAD"), remap = false)
  private void getOption(long gametime, String option_, CallbackInfoReturnable<Component[]> cir)
  {
    option = option_;
  }

  #[cfg(all(feature = "mc-ge-1.19.2", not(feature = "mc-ge-26.1.2")))]
  @Inject(method = "lambda$onExplosionDone$1", at = @At("HEAD"), remap = false)
  private void getOption(long gametime, String option_, CallbackInfoReturnable<Component> cir)
  {
    option = option_;
  }

  #[cfg(not(feature = "mc-ge-1.19.2"))]
  @Inject(method = "lambda$onExplosionDone$1", at = @At("HEAD"), remap = false)
  private void getOption(long gametime, String option_, CallbackInfoReturnable<BaseComponent> cir)
  {
    option = option_;
  }

  #[cfg(feature = "mc-ge-26.1.2")]
  @ModifyReceiver(method = "lambda$onExplosionDone$0", at = @At(value = "INVOKE", target = "Ljava/util/List;toArray([Ljava/lang/Object;)[Ljava/lang/Object;", remap = false))
  private List<Component> addLoggers(List<Component> messages, Object[] dummy)
  {
    return me.lntricate.intricarpet.logging.logHelpers.ExplosionLogHelper.onLog(messages, option);
  }

  #[cfg(all(feature = "mc-ge-1.19.2", not(feature = "mc-ge-26.1.2")))]
  @ModifyReceiver(method = "lambda$onExplosionDone$1", at = @At(value = "INVOKE", target = "Ljava/util/List;toArray([Ljava/lang/Object;)[Ljava/lang/Object;", remap = false))
  private List<Component> addLoggers(List<Component> messages, Object[] dummy)
  {
    return me.lntricate.intricarpet.logging.logHelpers.ExplosionLogHelper.onLog(messages, option);
  }

  #[cfg(not(feature = "mc-ge-1.19.2"))]
  @ModifyReceiver(method = "lambda$onExplosionDone$1", at = @At(value = "INVOKE", target = "Ljava/util/List;toArray([Ljava/lang/Object;)[Ljava/lang/Object;", remap = false))
  private List<BaseComponent> addLoggers(List<BaseComponent> messages, Object[] dummy)
  {
    return me.lntricate.intricarpet.logging.logHelpers.ExplosionLogHelper.onLog(messages, option);
  }
}

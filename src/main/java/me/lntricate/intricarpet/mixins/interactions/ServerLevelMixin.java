package me.lntricate.intricarpet.mixins.interactions;

import org.spongepowered.asm.mixin.Mixin;

import net.minecraft.server.level.ServerLevel;

#[cfg(not(feature = "since-1.18"))]
import org.spongepowered.asm.mixin.injection.At;
#[cfg(not(feature = "since-1.18"))]
import org.spongepowered.asm.mixin.injection.Inject;
#[cfg(not(feature = "since-1.18"))]
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
#[cfg(not(feature = "since-1.18"))]
import me.lntricate.intricarpet.interactions.Interaction;
#[cfg(not(feature = "since-1.18"))]
import me.lntricate.intricarpet.interfaces.IChunkMap;
#[cfg(not(feature = "since-1.18"))]
import net.minecraft.world.level.chunk.LevelChunk;

// On MC >= 1.18 the random tick condition is applied at the call site in ServerChunkCacheMixin, so
// this mixin only carries a body on the older versions where that call site sits inside a synthetic
// lambda with no Mojang name to target.
@Mixin(ServerLevel.class)
public class ServerLevelMixin
{
  #[cfg(not(feature = "since-1.18"))]
  @Inject(method = "tickChunk(Lnet/minecraft/world/level/chunk/LevelChunk;I)V", at = @At("HEAD"), cancellable = true)
  private void shouldRandomTick(LevelChunk levelChunk, int i, CallbackInfo ci)
  {
    if(!((IChunkMap)((ServerLevel)(Object)this).getChunkSource().chunkMap).anyPlayerCloseWithInteraction(levelChunk.getPos(), Interaction.RANDOMTICKS))
      ci.cancel();
  }
}

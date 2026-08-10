package me.lntricate.intricarpet.mixins.interactions;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import me.lntricate.intricarpet.interactions.Interaction;
import me.lntricate.intricarpet.interfaces.IChunkMap;
import me.lntricate.intricarpet.interfaces.IServerPlayer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.PlayerMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

#[cfg(not(feature = "since-1.21.5"))]
import net.minecraft.world.entity.Entity;
#[cfg(feature = "since-1.21.5")]
import net.minecraft.world.phys.Vec3;

@Mixin(ChunkMap.class)
public class ChunkMapMixin implements IChunkMap
{
  // 1.21.5 measures the distance from the player's position rather than from the player.
  #[cfg(feature = "since-1.21.5")]
  @Shadow private static double euclideanDistanceSquared(ChunkPos chunkPos, Vec3 vec){return 0.0;}

  #[cfg(not(feature = "since-1.21.5"))]
  @Shadow private static double euclideanDistanceSquared(ChunkPos chunkPos, Entity entity){return 0.0;}

  @Shadow @Final private PlayerMap playerMap;

  private boolean playerValid(ServerPlayer player, ChunkPos chunkPos, Interaction interaction)
  {
    #[cfg(feature = "since-1.21.5")]
    return ((IServerPlayer)player).getInteraction(interaction) && euclideanDistanceSquared(chunkPos, player.position()) < 16384d;

    #[cfg(not(feature = "since-1.21.5"))]
    return ((IServerPlayer)player).getInteraction(interaction) && euclideanDistanceSquared(chunkPos, player) < 16384d;
  }

  @Override
  public boolean anyPlayerCloseWithInteraction(ChunkPos chunkPos, Interaction interaction)
  {
    // 1.18 turned `PlayerMap`'s per-chunk stream into a plain collection, and 1.20.2 replaced the
    // per-chunk lookup with one over every tracked player.
    #[cfg(feature = "since-1.20.2")]
    for(ServerPlayer player : playerMap.getAllPlayers())
      if(playerValid(player, chunkPos, interaction))
        return true;

    #[cfg(all(feature = "since-1.18", not(feature = "since-1.20.2")))]
    for(ServerPlayer player : playerMap.getPlayers(chunkPos.toLong()))
      if(playerValid(player, chunkPos, interaction))
        return true;

    #[cfg(feature = "since-1.18")]
    return false;

    #[cfg(not(feature = "since-1.18"))]
    return playerMap.getPlayers(chunkPos.toLong()).anyMatch(player -> playerValid(player, chunkPos, interaction));
  }

  @Inject(method = "skipPlayer", at = @At("HEAD"), cancellable = true)
  private void skipPlayer(ServerPlayer player, CallbackInfoReturnable<Boolean> cir)
  {
    if(!((IServerPlayer)player).getInteraction(Interaction.CHUNKLOADING))
      cir.setReturnValue(true);
  }
}

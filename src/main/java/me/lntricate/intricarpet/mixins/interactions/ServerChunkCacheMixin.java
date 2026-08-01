package me.lntricate.intricarpet.mixins.interactions;

import org.spongepowered.asm.mixin.Mixin;

import net.minecraft.server.level.ServerChunkCache;

#[cfg(feature = "mc-ge-1.18.2")]
import org.spongepowered.asm.mixin.Final;
#[cfg(feature = "mc-ge-1.18.2")]
import org.spongepowered.asm.mixin.Shadow;
#[cfg(feature = "mc-ge-1.18.2")]
import org.spongepowered.asm.mixin.Unique;
#[cfg(feature = "mc-ge-1.18.2")]
import org.spongepowered.asm.mixin.injection.At;
#[cfg(feature = "mc-ge-1.18.2")]
import com.llamalad7.mixinextras.injector.WrapWithCondition;
#[cfg(feature = "mc-ge-1.18.2")]
import me.lntricate.intricarpet.interactions.Interaction;
#[cfg(feature = "mc-ge-1.18.2")]
import me.lntricate.intricarpet.interfaces.IChunkMap;
#[cfg(feature = "mc-ge-1.18.2")]
import net.minecraft.server.level.ChunkMap;
#[cfg(feature = "mc-ge-1.18.2")]
import net.minecraft.server.level.ServerLevel;
#[cfg(feature = "mc-ge-1.18.2")]
import net.minecraft.world.level.NaturalSpawner.SpawnState;
#[cfg(feature = "mc-ge-1.18.2")]
import net.minecraft.world.level.chunk.LevelChunk;

#[cfg(feature = "mc-ge-1.21.1")]
import java.util.List;

#[cfg(feature = "mc-ge-1.21.5")]
import org.spongepowered.asm.mixin.injection.Redirect;
#[cfg(feature = "mc-ge-1.21.5")]
import java.util.function.Consumer;

// On MC < 1.18 the mob spawning and random tick calls live inside a synthetic lambda in
// ServerChunkCache#tickChunks, which has no Mojang name to target. Those two conditions are
// applied on the callee side instead, see NaturalSpawnerMixin and ServerLevelMixin.
@Mixin(ServerChunkCache.class)
public class ServerChunkCacheMixin
{
  #[cfg(feature = "mc-ge-1.18.2")]
  @Final
  @Shadow
  public ChunkMap chunkMap;

  // 1.21.2 gave tickChunks a profiler and the ticking chunk list; 1.21.5 took the list back out.
  #[cfg(feature = "mc-ge-1.21.5")]
  @Unique
  private static final String targetMethod =
    "tickChunks(Lnet/minecraft/util/profiling/ProfilerFiller;J)V";
  #[cfg(all(feature = "mc-ge-1.21.4", not(feature = "mc-ge-1.21.5")))]
  @Unique
  private static final String targetMethod =
    "tickChunks(Lnet/minecraft/util/profiling/ProfilerFiller;JLjava/util/List;)V";
  #[cfg(all(feature = "mc-ge-1.18.2", not(feature = "mc-ge-1.21.4")))]
  @Unique
  private static final String targetMethod =
    "tickChunks()V";

  // 1.21.5 moved the spawnForChunk call into ServerChunkCache#tickSpawningChunk; before that it
  // sits in tickChunks itself, taking the spawn flags directly until 1.21.2 replaced them with the
  // chunk list.
  #[cfg(feature = "mc-ge-1.21.5")]
  @WrapWithCondition(method = "tickSpawningChunk", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/NaturalSpawner;spawnForChunk(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/world/level/NaturalSpawner$SpawnState;Ljava/util/List;)V"))
  private boolean shouldSpawnMobs(ServerLevel a, LevelChunk levelChunk, SpawnState b, List c)
  {
    return ((IChunkMap)chunkMap).anyPlayerCloseWithInteraction(levelChunk.getPos(), Interaction.MOBSPAWNING);
  }

  #[cfg(all(feature = "mc-ge-1.21.4", not(feature = "mc-ge-1.21.5")))]
  @WrapWithCondition(method = targetMethod, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/NaturalSpawner;spawnForChunk(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/world/level/NaturalSpawner$SpawnState;Ljava/util/List;)V"))
  private boolean shouldSpawnMobs(ServerLevel a, LevelChunk levelChunk, SpawnState b, List c)
  {
    return ((IChunkMap)chunkMap).anyPlayerCloseWithInteraction(levelChunk.getPos(), Interaction.MOBSPAWNING);
  }

  #[cfg(all(feature = "mc-ge-1.18.2", not(feature = "mc-ge-1.21.4")))]
  @WrapWithCondition(method = targetMethod, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/NaturalSpawner;spawnForChunk(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/world/level/NaturalSpawner$SpawnState;ZZZ)V"))
  private boolean shouldSpawnMobs(ServerLevel a, LevelChunk levelChunk, SpawnState b, boolean c, boolean d, boolean e)
  {
    return ((IChunkMap)chunkMap).anyPlayerCloseWithInteraction(levelChunk.getPos(), Interaction.MOBSPAWNING);
  }

  // From 1.21.5 the random tick call sits behind ChunkMap#forEachBlockTickingChunk, so the
  // condition wraps the consumer rather than the call.
  #[cfg(feature = "mc-ge-1.21.5")]
  @Redirect(
          method = targetMethod,
          at = @At(
                  value = "INVOKE",
                  target = "Lnet/minecraft/server/level/ChunkMap;forEachBlockTickingChunk(Ljava/util/function/Consumer;)V"
          )
  )
  private void redirectForEachBlockTickingChunk(ChunkMap chunkMapInstance, Consumer<LevelChunk> originalConsumer) {
    Consumer<LevelChunk> wrapper = (levelChunk) -> {
      try {
        boolean should = ((IChunkMap)chunkMapInstance)
                .anyPlayerCloseWithInteraction(levelChunk.getPos(), Interaction.RANDOMTICKS);
        if (should) {
          originalConsumer.accept(levelChunk);
        }
      } catch (Throwable t) {
        t.printStackTrace();
      }
    };
    chunkMapInstance.forEachBlockTickingChunk(wrapper);
  }

  #[cfg(all(feature = "mc-ge-1.18.2", not(feature = "mc-ge-1.21.5")))]
  @WrapWithCondition(method = targetMethod, at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;tickChunk(Lnet/minecraft/world/level/chunk/LevelChunk;I)V"))
  private boolean shouldRandomTick(ServerLevel instance, LevelChunk levelChunk, int i)
  {
    return ((IChunkMap)chunkMap).anyPlayerCloseWithInteraction(levelChunk.getPos(), Interaction.RANDOMTICKS);
  }
}

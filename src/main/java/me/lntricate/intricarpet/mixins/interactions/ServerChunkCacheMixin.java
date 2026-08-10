package me.lntricate.intricarpet.mixins.interactions;

import org.spongepowered.asm.mixin.Mixin;

import net.minecraft.server.level.ServerChunkCache;

#[cfg(feature = "since-1.18")]
import org.spongepowered.asm.mixin.Final;
#[cfg(feature = "since-1.18")]
import org.spongepowered.asm.mixin.Shadow;
#[cfg(feature = "since-1.18")]
import org.spongepowered.asm.mixin.Unique;
#[cfg(feature = "since-1.18")]
import org.spongepowered.asm.mixin.injection.At;
#[cfg(feature = "since-1.18")]
import com.llamalad7.mixinextras.injector.WrapWithCondition;
#[cfg(feature = "since-1.18")]
import me.lntricate.intricarpet.interactions.Interaction;
#[cfg(feature = "since-1.18")]
import me.lntricate.intricarpet.interfaces.IChunkMap;
#[cfg(feature = "since-1.18")]
import net.minecraft.server.level.ChunkMap;
#[cfg(feature = "since-1.18")]
import net.minecraft.server.level.ServerLevel;
#[cfg(feature = "since-1.18")]
import net.minecraft.world.level.NaturalSpawner.SpawnState;
#[cfg(feature = "since-1.18")]
import net.minecraft.world.level.chunk.LevelChunk;

#[cfg(feature = "since-1.21")]
import java.util.List;

#[cfg(feature = "since-1.21.5")]
import org.spongepowered.asm.mixin.injection.Redirect;
#[cfg(feature = "since-1.21.5")]
import java.util.function.Consumer;

// On MC < 1.18 the mob spawning and random tick calls live inside a synthetic lambda in
// ServerChunkCache#tickChunks, which has no Mojang name to target. Those two conditions are
// applied on the callee side instead, see NaturalSpawnerMixin and ServerLevelMixin.
@Mixin(ServerChunkCache.class)
public class ServerChunkCacheMixin
{
  #[cfg(feature = "since-1.18")]
  @Final
  @Shadow
  public ChunkMap chunkMap;

  // `tickChunks` gained a profiler and a chunk list in 1.21.2, and lost the list again in 1.21.5.
  #[cfg(feature = "since-1.21.5")]
  @Unique
  private static final String targetMethod = "tickChunks(Lnet/minecraft/util/profiling/ProfilerFiller;J)V";

  #[cfg(all(feature = "since-1.21.2", not(feature = "since-1.21.5")))]
  @Unique
  private static final String targetMethod = "tickChunks(Lnet/minecraft/util/profiling/ProfilerFiller;JLjava/util/List;)V";

  #[cfg(all(feature = "since-1.18", not(feature = "since-1.21.2")))]
  @Unique
  private static final String targetMethod = "tickChunks()V";

  #[cfg(feature = "since-1.21.5")]
  @WrapWithCondition(method = "tickSpawningChunk", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/NaturalSpawner;spawnForChunk(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/world/level/NaturalSpawner$SpawnState;Ljava/util/List;)V"))
  private boolean shouldSpawnMobs(ServerLevel a, LevelChunk levelChunk, SpawnState b, List c)
  {
    return ((IChunkMap)chunkMap).anyPlayerCloseWithInteraction(levelChunk.getPos(), Interaction.MOBSPAWNING);
  }

  #[cfg(all(feature = "since-1.21.2", not(feature = "since-1.21.5")))]
  @WrapWithCondition(method = targetMethod, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/NaturalSpawner;spawnForChunk(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/world/level/NaturalSpawner$SpawnState;Ljava/util/List;)V"))
  private boolean shouldSpawnMobs(ServerLevel a, LevelChunk levelChunk, SpawnState b, List c)
  {
    return ((IChunkMap)chunkMap).anyPlayerCloseWithInteraction(levelChunk.getPos(), Interaction.MOBSPAWNING);
  }

  #[cfg(all(feature = "since-1.18", not(feature = "since-1.21.2")))]
  @WrapWithCondition(method = targetMethod, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/NaturalSpawner;spawnForChunk(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/world/level/NaturalSpawner$SpawnState;ZZZ)V"))
  private boolean shouldSpawnMobs(ServerLevel a, LevelChunk levelChunk, SpawnState b, boolean c, boolean d, boolean e)
  {
    return ((IChunkMap)chunkMap).anyPlayerCloseWithInteraction(levelChunk.getPos(), Interaction.MOBSPAWNING);
  }

  // 1.21.5 moved the per-chunk random tick behind `ChunkMap#forEachBlockTickingChunk`, so the
  // condition can no longer wrap a single call: the consumer itself has to be filtered.
  // `forEachBlockTickingChunk` is package-private, which under Loom was an access widener;
  // `ChunkMapAccessor` is the Mixin-native way to reach it and needs no build-tool support.
  #[cfg(feature = "since-1.21.5")]
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
    ((ChunkMapAccessor)chunkMapInstance).invokeForEachBlockTickingChunk(wrapper);
  }

  #[cfg(all(feature = "since-1.18", not(feature = "since-1.21.5")))]
  @WrapWithCondition(method = targetMethod, at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;tickChunk(Lnet/minecraft/world/level/chunk/LevelChunk;I)V"))
  private boolean shouldRandomTick(ServerLevel instance, LevelChunk levelChunk, int i)
  {
    return ((IChunkMap)chunkMap).anyPlayerCloseWithInteraction(levelChunk.getPos(), Interaction.RANDOMTICKS);
  }
}

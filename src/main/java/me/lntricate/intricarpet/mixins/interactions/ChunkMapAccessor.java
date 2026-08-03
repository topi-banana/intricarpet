package me.lntricate.intricarpet.mixins.interactions;

import org.spongepowered.asm.mixin.Mixin;

import net.minecraft.server.level.ChunkMap;

#[cfg(feature = "mc-ge-1.21.5")]
import org.spongepowered.asm.mixin.gen.Invoker;
#[cfg(feature = "mc-ge-1.21.5")]
import java.util.function.Consumer;
#[cfg(feature = "mc-ge-1.21.5")]
import net.minecraft.world.level.chunk.LevelChunk;

// `ChunkMap#forEachBlockTickingChunk` is package-private, and ServerChunkCacheMixin has to call it
// to re-issue the iteration it redirects. The access widener that used to make it callable was
// applied to the compile classpath by loom, which nothing does now that the build is jals — so the
// call goes through the invoker Mixin generates instead, which needs no transformed classpath at
// all. The widener stays for the loader; this only replaces what it did at compile time.
@Mixin(ChunkMap.class)
public interface ChunkMapAccessor
{
  #[cfg(feature = "mc-ge-1.21.5")]
  @Invoker("forEachBlockTickingChunk")
  void invokeForEachBlockTickingChunk(Consumer<LevelChunk> consumer);
}

package me.lntricate.intricarpet.mixins.interactions;

#[cfg(feature = "since-1.21.5")] import java.util.function.Consumer;
#[cfg(feature = "since-1.21.5")] import net.minecraft.server.level.ChunkMap;
#[cfg(feature = "since-1.21.5")] import net.minecraft.world.level.chunk.LevelChunk;
#[cfg(feature = "since-1.21.5")] import org.spongepowered.asm.mixin.Mixin;
#[cfg(feature = "since-1.21.5")] import org.spongepowered.asm.mixin.gen.Invoker;

// `ChunkMap#forEachBlockTickingChunk` is package-private, and ServerChunkCacheMixin's redirect has
// to call it from outside `net.minecraft.server.level`. Under Loom that was an access widener — a
// build-tool artifact applied to the development jar. An `@Invoker` accessor is the same widening
// expressed in Mixin itself: it works at runtime for the same reason the access widener was never
// needed there, and it needs nothing of the build tool.
//
// The method it names only exists from 1.21.5, so the whole type is `cfg`-gated and the build
// script lists it in `intricarpet.mixins.json` only for the selections that define it.
#[cfg(feature = "since-1.21.5")]
@Mixin(ChunkMap.class)
public interface ChunkMapAccessor {
    @Invoker("forEachBlockTickingChunk")
    void invokeForEachBlockTickingChunk(Consumer<LevelChunk> consumer);
}

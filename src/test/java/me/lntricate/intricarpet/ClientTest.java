package me.lntricate.intricarpet;

// `#[cfg(feature = "client-test")]` on every declaration, imports included. Under any other
// selection this file is blanked whole, so `jals build --features 1.21.11` and the lint that
// follows it never see a `net.minecraft.client.*` name, nor `com.example.mctest`, which comes from
// a dev-dependency neither of them resolves.
#[cfg(feature = "client-test")] import com.example.mctest.GameClient;
#[cfg(feature = "client-test")] import java.util.UUID;
#[cfg(feature = "client-test")] import me.lntricate.intricarpet.helpers.ExplosionHelper;
#[cfg(feature = "client-test")] import me.lntricate.intricarpet.interactions.Interaction;
#[cfg(feature = "client-test")] import net.minecraft.client.gui.screens.TitleScreen;
#[cfg(feature = "client-test")] import net.minecraft.core.BlockPos;
#[cfg(feature = "client-test")] import net.minecraft.server.level.ServerLevel;
#[cfg(feature = "client-test")] import net.minecraft.world.level.block.Blocks;
#[cfg(feature = "client-test")] import net.minecraft.world.phys.Vec3;

/**
 * Boots a real Minecraft client of the selected release and asserts against it.
 *
 * <p><b>What these can and cannot see.</b> The test JVM has the game and this mod's compiled
 * classes on one classpath, and that is all it has: there is no Fabric loader, no Knot and no Mixin
 * transformer, so <em>none of this mod's mixins are applied</em>. Every `@Inject` and `@Redirect`
 * under `mixins/` is inert here, and no test below asserts about behaviour a mixin injects — the
 * gate that mixins name types the release has is {@link MixinTargetsTest}, which needs no client.
 * What is left is worth having and is what these assert: the release boots, its world loads, and
 * this mod's own release-portable state classes behave against real game objects of that release
 * rather than against types a unit test invented.
 *
 * <p>Each `#[test]` runs in its own JVM and boots its own client, which is the shape `jals test`
 * gives and the reason there are two here rather than ten: a boot on a software rasterizer costs
 * the better part of a minute. Run them one at a time — {@code jals test --features
 * 26.2,client-test -j 1} — since two clients at once want two GL contexts and twice the memory, and
 * never with {@code --no-capture}: the harness halts the JVM on its way out, so the sentinel line
 * the runner reads is the only verdict there is.
 *
 * <p>Any of this mod's fifteen releases goes in place of {@code 26.2}; nothing in this file names
 * one. The JVM has to be the one the release asks for, though — 16 for 1.17.1, 17 through 1.20.2,
 * 21 through 1.21.11, 25 for 26.x — which is what `$JAVA` says and what the CI matrix installs.
 */
#[cfg(feature = "client-test")]
public final class ClientTest {
    private ClientTest() {}

    /**
     * The client of this release comes up far enough to be driven, and the harness can read its
     * state back through the render thread.
     *
     * <p>This is the cheap diagnostic. When a cell of the matrix goes red, whether this one also
     * went red is the difference between "the release stopped booting" and "this mod stopped
     * agreeing with it".
     */
    #[test]
    static void bootsToTheTitleScreen() {
        try (GameClient game = GameClient.launch()) {
            assert game.screen() instanceof TitleScreen : "the boot settles on the title screen";
            assert game.overlay() == null : "the resource reload has finished";
        }
    }

    /**
     * A world of this release loads, and the mod's own state classes hold against the objects
     * inside it.
     */
    #[test]
    static void theModsOwnStateHoldsAgainstARunningWorld() {
        try (GameClient game = GameClient.launch()) {
            game.openWorld("intricarpet-test");

            // Read once, off the harness: 1.16 replaced `getLevel(DimensionType.OVERWORLD)` with
            // `overworld()` and the harness is what knows that. Reading it here rather than inside
            // each body also keeps the bodies to one hop onto the server thread, and a hop inside a
            // hop would wait on the thread it is running on.
            ServerLevel overworld = game.overworld();

            // Vanilla first, so that a failure below is this mod's and not the world's.
            BlockPos placed = new BlockPos(0, 64, 0);
            game.runCommand("setblock 0 64 0 minecraft:diamond_block");
            assert game.evalOnServer(
                    server -> overworld.getBlockState(placed).getBlock() == Blocks.DIAMOND_BLOCK)
                : "a command the harness sent reached the world";

            // `Interaction`'s per-player map, keyed by a UUID the running server minted rather than
            // by one this test made up.
            UUID player =
                game.evalOnServer(server -> server.getPlayerList().getPlayers().get(0).getUUID());
            assert Interaction.get(player).get(Interaction.BLOCKS)
                : "the player who opened the world starts with every interaction on";

            // `ExplosionHelper` is static state the explosion mixins drive. They are not applied
            // here, so it is driven directly — with this release's own `Vec3` and this world's own
            // game time, which is the half a unit test could not supply.
            long tick = game.evalOnServer(server -> overworld.getGameTime());
            Vec3 position = new Vec3(0.5, 64.0, 0.5);

            ExplosionHelper.clear();
            assert ExplosionHelper.isEmpty() : "a cleared helper holds no explosion";

            ExplosionHelper.registerNewPos(position, tick, 0L, true);
            assert !ExplosionHelper.isEmpty() : "a registered explosion is held";
            assert position.equals(ExplosionHelper.getPos()) : "at the position it was given";
            assert ExplosionHelper.getTick() == tick : "in the tick the world was on";
            assert ExplosionHelper.getCountInPos() == 1 : "counted once at that position";
            assert ExplosionHelper.getCountInTick() == 1 : "and once in that tick";
            assert ExplosionHelper.getAffectBlocks() : "carrying the flag it was given";

            assert !ExplosionHelper.isNew(position, tick)
                : "the same position in the same tick is the same explosion";
            assert ExplosionHelper.isNew(position, tick + 1) : "a later tick is a new one";
            assert ExplosionHelper.isNew(position.add(1.0, 0.0, 0.0), tick)
                : "and so is another position in this one";

            ExplosionHelper.incrementCounts();
            assert ExplosionHelper.getCountInPos() == 2 : "a repeat at one position counts there";
            assert ExplosionHelper.getCountInTick() == 2 : "and in the tick";

            // Left the way it was found: one JVM is one test, but the class is static state and
            // saying so in the test is cheaper than finding out from the next one.
            ExplosionHelper.clear();
        }
    }
}

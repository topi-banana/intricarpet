package me.lntricate.intricarpet.interactions;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * `Interaction` is the one part of this mod that names no Minecraft and no Carpet type, so it is
 * the one part whose behaviour can be stated without a game running. It is also the state
 * `/interaction` reads and every interaction mixin writes through, which is why it is worth
 * stating.
 *
 * <p>These carry no `#[cfg]` and want no client: they run in every cell of the matrix. Fifteen
 * identical verdicts is the point — the class compiles into all fifteen releases, and a change that
 * broke it would break it everywhere at once rather than on the release nobody selected.
 */
public final class InteractionTest {
    private InteractionTest() {}

    /**
     * The command keys round-trip. `/interaction` completes on `commandKeys()` and dispatches on
     * `byCommandKey`, so a key the first hands out and the second does not answer is a completion
     * the command cannot execute.
     */
    #[test]
    static void everyCommandKeyNamesTheInteractionItCompletes() {
        String[] keys = Interaction.commandKeys();
        assert keys.length == Interaction.values().length
            : "one command key per interaction, and no more";
        Set<Interaction> reached = EnumSet.noneOf(Interaction.class);
        for (String key : keys) {
            Interaction interaction = Interaction.byCommandKey(key);
            assert interaction != null : "`" + key + "` is a key the command offers and answers";
            assert reached.add(interaction) : "`" + key + "` is the only key naming " + interaction;
        }
        assert reached.size() == Interaction.values().length : "every interaction is reachable";
        assert Interaction.byCommandKey("nothing is spelled this") == null
            : "an unknown key names nothing rather than the first thing";
    }

    /** A player nobody has configured has every interaction on. */
    #[test]
    static void aPlayerStartsWithEveryInteractionOn() {
        Map<Interaction, Boolean> interactions = Interaction.get(UUID.randomUUID());
        assert interactions.size() == Interaction.values().length
            : "the defaults name every interaction";
        for (Interaction interaction : Interaction.values()) {
            assert interactions.get(interaction) : interaction + " defaults to on";
        }
    }

    /**
     * The map is per player, and `set` writes into the caller's own copy.
     *
     * <p>The `get` before the `set` is the class's precondition rather than this test being
     * careful: `set` reaches into the map directly and throws on a player `get` has never seen. In
     * the mod nothing can reach `set` without the command having read the player first, so the
     * order is real; asserting it here is what keeps it deliberate.
     */
    #[test]
    static void settingOnePlayerLeavesTheOthersAlone() {
        UUID configured = UUID.randomUUID();
        UUID untouched = UUID.randomUUID();
        Interaction.get(configured);
        Interaction.get(untouched);

        Interaction.set(configured, Interaction.BLOCKS, false);

        assert !Interaction.get(configured).get(Interaction.BLOCKS)
            : "the player the command named is the player that changed";
        assert Interaction.get(configured).get(Interaction.ENTITIES)
            : "the interaction the command named is the only one that changed";
        assert Interaction.get(untouched).get(Interaction.BLOCKS)
            : "no other player's settings moved with it";
    }
}

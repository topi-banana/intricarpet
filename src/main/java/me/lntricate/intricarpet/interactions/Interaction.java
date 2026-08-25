package me.lntricate.intricarpet.interactions;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public enum Interaction {
    BLOCKS("blocks", "Blocks"),
    CHUNKLOADING("chunkloading", "Chunkloading"),
    ENTITIES("entities", "Entities"),
    MOBSPAWNING("mobSpawning", "Mob Spawning"),
    RANDOMTICKS("randomTicks", "Random Ticks"),
    UPDATES("updates", "Updates");

    private final String name;
    private final String commandKey;

    private static final Map<Interaction, Boolean> DEFAULT_INTERACTIONS = new HashMap<>();
    private static final Map<String, Interaction> BY_COMMAND_KEY = new HashMap<>();
    private static final Map<UUID, Map<Interaction, Boolean>> PLAYER_INTERACTION_MAP =
        new HashMap<>();

    static {
        for (Interaction i : values()) {
            DEFAULT_INTERACTIONS.put(i, true);
            BY_COMMAND_KEY.put(i.commandKey, i);
        }
    }

    Interaction(String commandKey, String name) {
        this.commandKey = commandKey;
        this.name = name;
    }

    public static String[] commandKeys() {
        return BY_COMMAND_KEY.keySet().toArray(new String[0]);
    }

    public static Interaction byCommandKey(String key) {
        return BY_COMMAND_KEY.get(key);
    }

    public String getName() {
        return this.name;
    }

    public static Map<Interaction, Boolean> get(UUID id) {
        Map<Interaction, Boolean> interactions = PLAYER_INTERACTION_MAP.get(id);
        if (interactions != null) {
            return interactions;
        }

        interactions = new HashMap<>(DEFAULT_INTERACTIONS);
        PLAYER_INTERACTION_MAP.put(id, interactions);
        return interactions;
    }

    public static void set(UUID id, Interaction i, boolean value) {
        PLAYER_INTERACTION_MAP.get(id).put(i, value);
    }
}

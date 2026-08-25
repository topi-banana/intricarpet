package me.lntricate.intricarpet;

import carpet.settings.Rule;

// Carpet reads a rule's *field name* as the rule's name: `commandInteraction` is exactly what
// `/carpet commandInteraction` is spelled, and what a `carpet.conf` line carries. So the
// `lowerCamelCase` here is the API's demand, not this project's choice, and renaming these to
// satisfy `naming-convention` would rename the mod's user-facing settings.
//
// It sits on the type rather than on each field because it is one statement of one reason, and the
// reason holds for every rule this class declares — `optimizedTNTEdgeCases`, which exists only
// below 26, included.
@SuppressWarnings("naming-convention")
public class Rules {
    @Rule(
        desc =
            "Enables /interaction command for controlling the effects of players on the environment",
        category = {"COMMAND", "intricarpet"},
        options = {"true", "false", "ops"})
    public static String commandInteraction = "ops";

    #[cfg(not(feature = "since-26"))]
    @Rule(
        desc = "Enables edge case fixes in optimizedTNT, at the cost of a bit less optimization",
        category = {"COMMAND", "intricarpet"})
    public static boolean optimizedTNTEdgeCases = false;
}

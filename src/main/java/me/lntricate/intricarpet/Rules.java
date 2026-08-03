package me.lntricate.intricarpet;

import carpet.settings.Rule;

public class Rules
{
  @Rule(
    desc = "Enables /interaction command for controlling the effects of players on the environment",
    category = {"COMMAND", "intricarpet"},
    options = {"true", "false", "ops"}
  )
  public static String commandInteraction = "ops";

  // Carpet's optimizedTNT is gone from 26.1 onwards, so the edge-case toggle has nothing to fix.
  #[cfg(not(feature = "mc-ge-26.1.2"))]
  @Rule(
    desc = "Enables edge case fixes in optimizedTNT, at the cost of a bit less optimization",
    category = {"COMMAND", "intricarpet"}
  )
  public static boolean optimizedTNTEdgeCases = false;
}

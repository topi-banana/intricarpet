package me.lntricate.intricarpet.logging.logHelpers;

import carpet.logging.LoggerRegistry;
import carpet.utils.Messenger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import me.lntricate.intricarpet.helpers.ExplosionHelper;

// 1.19 removed `BaseComponent`; every version in range has the `Component` interface it implemented,
// so the whole file speaks `Component` and needs no per-version alternative. Under the preprocessor
// this was `versions/mapping-1.18.2-1.19.2.txt`, a source-level rename applied to the whole tree —
// the one place a *type* differed, spelled as a build-tool mapping rather than in the source.
#[cfg(not(feature = "since-1.19"))] import net.minecraft.network.chat.BaseComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

public class ExplosionLogHelper {
    private static Component LOG;

    public static void onExplosion(Vec3 pos, long tick, boolean affectBlocks) {
        if (ExplosionHelper.isEmpty() || ExplosionHelper.isNew(pos, tick)) {
            long time = System.currentTimeMillis();
            logCompact(time, false);
            ExplosionHelper.registerNewPos(pos, tick, time, affectBlocks);
        } else {
            LOG = null;
            ExplosionHelper.incrementCounts();
        }
    }

    public static List<Component> onLog(List<Component> messages, String option) {
        if (option.equals("compact") && LOG != null) {
            messages.add(LOG);
        }

        return messages;
    }

    private static void logCompact(long time, boolean endOfTick) {
        if (ExplosionHelper.isEmpty()) {
            return;
        }
        Vec3 pos = ExplosionHelper.getPos();
        LOG =
            Messenger.c(
                "d " + ExplosionHelper.getCountInPos() + "x ",
                Messenger.dblt("l", pos.x, pos.y, pos.z),
                "p  [Tp]",
                String.format(Locale.ENGLISH, "!/tp %.3f %.3f %.3f", pos.x, pos.y, pos.z),
                ExplosionHelper.getAffectBlocks() ? "m   damage" : "m  no damage",
                "g  (",
                "d " + (time - ExplosionHelper.getTime()),
                "g ms)",
                endOfTick
                    ? Messenger.c("g  \n(", "d " + ExplosionHelper.getCountInTick(), "g  total)")
                    : Messenger.c());
    }

    public static void afterEntities() {
        if (LoggerRegistry.__explosions) {
            logCompact(System.currentTimeMillis(), true);
            // Carpet's `lMessage` yields the array type its own Minecraft version names, so only the
            // array the lambda allocates differs — the list it comes from is `Component` throughout.
            #[cfg(feature = "since-1.19")] LoggerRegistry.getLogger("explosions")
                .log(
                    (option) -> {
                        return onLog(new ArrayList<Component>(), option).toArray(new Component[0]);
                    });

            #[cfg(not(feature = "since-1.19"))] LoggerRegistry.getLogger("explosions")
                .log(
                    (option) -> {
                        return onLog(new ArrayList<Component>(), option)
                            .toArray(new BaseComponent[0]);
                    });

            LOG = null;
        }
        ExplosionHelper.clear();
    }
}

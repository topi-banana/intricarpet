import jals.build.Build;
import jals.build.Output;
import jals.build.Project;
import java.util.ArrayList;
import java.util.List;

public class Build {

    public static void main() {
        String[][] catalog = {
            {"26.2", ">=26.2 <26.3", ">=26.2", "25"},
            {"26.1.2", ">=26.1 <26.2", ">=26.1", "25"},
            {"1.21.11", ">=1.21.11 <1.22", ">=1.4.193", "21"},
            {"1.21.10", ">=1.21.9 <=1.21.10", ">=1.4.188", "21"},
            {"1.21.8", ">=1.21.6 <=1.21.8", ">=1.4.169", "21"},
            {"1.21.5", "1.21.5", ">=1.4.169", "21"},
            {"1.21.4", ">=1.21.2 <=1.21.4", ">=1.4.161", "21"},
            {"1.21.1", ">=1.21 <=1.21.1", ">=1.4.147", "21"},
            {"1.20.2", "1.20.2", ">=1.4.119", "17"},
            {"1.20.1", "1.20.1", ">=1.4.112", "17"},
            {"1.19.4", "1.19.4", ">=1.4.100", "17"},
            {"1.19.3", "1.19.3", ">=1.4.96", "17"},
            {"1.19.2", "1.19.2", ">=1.4.84", "17"},
            {"1.18.2", "1.18.x", ">=1.4.69", "17"},
            {"1.17.1", "1.17.x", ">=1.4.57", "16"},
        };
        @Nullable String[] release = null;
        for (String[] entry : catalog) {
            if (Build.feature(entry[0])) {
                if (release != null) {
                    Build.error(
                        "select exactly one Minecraft version feature, got `"
                            + release[0]
                            + "` and `"
                            + entry[0]
                            + "`");
                    return;
                }
                release = entry;
            }
        }
        if (release == null) {
            Build.error(
                "select a Minecraft version feature, e.g. `--features "
                    + catalog[0][0]
                    + "`. There is deliberately no default: a release chooses the game jar, the Carpet jar and every `#[cfg]` branch at once, and guessing one of those would compile the mod against a game it was not asked for.");
            return;
        }

        String version = release[0];
        String minecraftDependency = release[1];
        String carpetDependency = release[2];
        String javaRelease = release[3];

        Build.metadata("minecraft-version", version);

        Build.addJavacArg("--release");
        Build.addJavacArg(javaRelease);
        Build.addJavacArg("-proc:none");
        Build.addJavacArg("-Xlint:deprecation");
        Build.addJavacArg("-Xlint:unchecked");
        if (javaRelease.equals("8")
            || javaRelease.equals("7")
            || javaRelease.equals("6")
            || javaRelease.equals("5")
            || javaRelease.equals("4")
            || javaRelease.equals("3")
            || javaRelease.equals("2")
            || javaRelease.equals("1")) {
            Build.addJavacArg("-Xlint:-options");
        }

        Build.rerunIfEnvChanged("JALS_BUILD_ID");
        Build.rerunIfEnvChanged("JALS_BUILD_RELEASE");

        String modVersion = "2.0.7";
        String envRelease = Build.env("JALS_BUILD_RELEASE");
        if (!"true".equals(envRelease)) {
            String buildId = Build.env("JALS_BUILD_ID");
            if (buildId == null || buildId.equals("")) {
                modVersion += "-SNAPSHOT";
            } else {
                modVersion += "+build." + buildId;
            }
        }

        String modJson = Project.readText("templates/fabric.mod.json");
        modJson = replace(modJson, "${id}", "intricarpet");
        modJson = replace(modJson, "${name}", "IntriCarpet");
        modJson = replace(modJson, "${version}", modVersion);
        modJson = replace(modJson, "${minecraft_dependency}", minecraftDependency);
        modJson = replace(modJson, "${carpet_dependency}", carpetDependency);
        Output.writeText("resources/fabric.mod.json", modJson);

        List<String> mixinsList = new ArrayList<>();
        mixinsList.add("OptimizedExplosionMixin");
        mixinsList.add("logger.ExplosionLogHelperMixin");
        mixinsList.add("logger.ServerLevelMixin");
        if (Build.feature("since-1.21.5")) {
            mixinsList.add("interactions.ChunkMapAccessor");
        }
        mixinsList.add("interactions.ChunkMapMixin");
        mixinsList.add("interactions.EntityMixin");
        mixinsList.add("interactions.EntitySelectorMixin");
        mixinsList.add("interactions.NaturalSpawnerMixin");
        mixinsList.add("interactions.ProjectileMixin");
        mixinsList.add("interactions.ServerChunkCacheMixin");
        mixinsList.add("interactions.ServerGamePacketListenerImplMixin");
        mixinsList.add("interactions.ServerLevelMixin");
        mixinsList.add("interactions.ServerPlayerMixin");

        StringBuilder mixinList = new StringBuilder();
        for (int i = 0; i < mixinsList.size(); i++) {
            if (i > 0) {
                mixinList.append(",\n    ");
            }
            mixinList.append("\"").append(mixinsList.get(i)).append("\"");
        }

        String refmap = "";
        if (!Build.feature("since-26")) {
            Output.writeText(
                "resources/intricarpet-refmap.json",
                Project.readText("mappings/refmap-" + version + ".json"));
            refmap = "\n  \"refmap\": \"intricarpet-refmap.json\",";
        }

        String mixinJson = Project.readText("templates/intricarpet.mixins.json");
        mixinJson = replace(mixinJson, "${id}", "intricarpet");
        mixinJson = replace(mixinJson, "${compatibility_level}", "JAVA_" + javaRelease);
        mixinJson = replace(mixinJson, "${mixins}", mixinList.toString());
        mixinJson = replace(mixinJson, "${refmap}", refmap);
        Output.writeText("resources/intricarpet.mixins.json", mixinJson);

        if (Build.feature("client-test")) {
            Build.addJvmArg("-Xmx2G");
        }
    }

    /**
     * 依存関係の少ない安全な文字列置換メソッド
     */
    private static String replace(String original, String target, String replacement) {
        if (original == null || target == null || replacement == null) {
            return original;
        }
        int targetLen = target.length();
        if (targetLen == 0) {
            return original;
        }

        StringBuilder sb = new StringBuilder();
        int start = 0;
        int i = 0;
        while (i <= original.length() - targetLen) {
            boolean match = true;
            for (int j = 0; j < targetLen; j++) {
                if (original.charAt(i + j) != target.charAt(j)) {
                    match = false;
                    break;
                }
            }
            if (match) {
                for (int k = start; k < i; k++) {
                    sb.append(original.charAt(k));
                }
                sb.append(replacement);
                i += targetLen;
                start = i;
            } else {
                i++;
            }
        }
        for (int k = start; k < original.length(); k++) {
            sb.append(original.charAt(k));
        }
        return sb.toString();
    }
}

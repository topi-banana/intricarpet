import jals.build.Build;
import jals.build.MappingFormat;
import jals.build.Output;
import jals.build.Project;
import jals.build.Tasks;
import java.util.ArrayList;
import java.util.List;

public class Build {

    public static void main() {
        // One row per release:
        //
        //   {version, minecraft-dependency, carpet-dependency, java-release,
        //    client-mappings sha1, client-mappings bytes, intermediary sha1, intermediary bytes}
        //
        // The four trailing columns are the two mapping halves an obfuscated release composes
        // from, and they are pinned here rather than committed as generated files: the SHA-1 of
        // Mojang's `client.txt` is content-addressed into its own URL
        // (`https://piston-data.mojang.com/v1/objects/<sha1>/client.txt`), so the one digest pins
        // both the address and the bytes, and Fabric's intermediary jar is an immutable Maven
        // artifact whose digest was computed once, when the row was added. The two 26.x rows carry
        // empty digests: those releases ship deobfuscated, so there is nothing to compose, no
        // Carpet jar to deobfuscate and no refmap to write — exactly what their absent
        // `[[mappings.intermediary]]` alternative in `jals.toml` says from the other side.
        //
        // To add a release: take its `client_mappings.{sha1,size}` from the version metadata
        // (`https://piston-meta.mojang.com/v1/packages/<metadata sha1>/<version>.json`; the jals
        // repository's `examples/minecraft/build.java` catalogs the metadata digest of every
        // release), and `sha1sum`/`wc -c` the `intermediary-<version>-v2.jar` from
        // `https://maven.fabricmc.net/net/fabricmc/intermediary/<version>/`.
        String[][] catalog = {
            {"26.2", ">=26.2 <26.3", ">=26.2", "25", "", "", "", ""},
            {"26.1.2", ">=26.1 <26.2", ">=26.1", "25", "", "", "", ""},
            {
                "1.21.11",
                ">=1.21.11 <1.22",
                ">=1.4.193",
                "21",
                "031a68bebf55d824f66d6573d8c752f0e1bf232a",
                "11779287",
                "016a84fabebbbfaf4950abe072f6d3653090430a",
                "707972"
            },
            {
                "1.21.10",
                ">=1.21.9 <=1.21.10",
                ">=1.4.188",
                "21",
                "7e62354a697f95cf5e7d5981face0583676a9ef7",
                "11511143",
                "e802e9d8590537ee8a2aea3de704a2dd6cdb19c7",
                "690428"
            },
            {
                "1.21.8",
                ">=1.21.6 <=1.21.8",
                ">=1.4.169",
                "21",
                "bdeb624c3aefba11d9d40f34bc96176350b549b6",
                "10989738",
                "72da29ed97d2b4357653359b884f42426846f31a",
                "662377"
            },
            {
                "1.21.5",
                "1.21.5",
                ">=1.4.169",
                "21",
                "57669731d542f98646772e91a0d68628f9827a5c",
                "10670987",
                "8204b54acf51aacaf3bbaed2b1a49af1dc5ac95c",
                "643511"
            },
            {
                "1.21.4",
                ">=1.21.2 <=1.21.4",
                ">=1.4.161",
                "21",
                "0cf2a0b7f056da1a5a5dd99fc6dc752f33987150",
                "10323161",
                "644745e4e54d7277cb9e89319d4c5a041ba1acf8",
                "622486"
            },
            {
                "1.21.1",
                ">=1.21 <=1.21.1",
                ">=1.4.147",
                "21",
                "2244b6f072256667bcd9a73df124d6c58de77992",
                "9598610",
                "9ce486577f40c5ed70b243b4a513bdf5dcf2cc7f",
                "582921"
            },
            {
                "1.20.2",
                "1.20.2",
                ">=1.4.119",
                "17",
                "5c292ff7d3161977041116698e295083fd5ec8f5",
                "8578898",
                "1779e468ccbd23b854bcd80576139e8dd568c5bd",
                "520537"
            },
            {
                "1.20.1",
                "1.20.1",
                ">=1.4.112",
                "17",
                "6c48521eed01fe2e8ecdadbd5ae348415f3c47da",
                "8001795",
                "20ba9399cb5a23aa06ea901eff0a84e648d866db",
                "508743"
            },
            {
                "1.19.4",
                "1.19.4",
                ">=1.4.100",
                "17",
                "f14771b764f943c154d3a6fcb47694477e328148",
                "7844191",
                "018758db7eb3a3d9587a9fe44afa5342aeb17ac3",
                "506600"
            },
            {
                "1.19.3",
                "1.19.3",
                ">=1.4.96",
                "17",
                "42366909cc612e76208d34bf1356f05a88e08a1d",
                "7574170",
                "450784e00cd22814b77724154be6f1ccc29f9829",
                "487799"
            },
            {
                "1.19.2",
                "1.19.2",
                ">=1.4.84",
                "17",
                "8e8c9be5dc27802caba47053d4fdea328f7f89bd",
                "7236064",
                "b4122bb57b4e4e8b94376f7b41df211b8aa51d62",
                "467472"
            },
            {
                "1.18.2",
                "1.18.x",
                ">=1.4.69",
                "17",
                "a661c6a55a0600bd391bdbbd6827654c05b2109c",
                "6716693",
                "e19bddfa57da82ba4e10b5bece3fb8b90092e5e3",
                "432989"
            },
            {
                "1.17.1",
                "1.17.x",
                ">=1.4.57",
                "16",
                "e4d540e0cba05a6097e885dffdf363e621f87d3f",
                "6437531",
                "c7c98a432dd633cf45bde17990f8cce2d7fdf0fc",
                "410406"
            },
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

        // An obfuscated release is one the catalog carries mapping digests for. Its two mapping
        // artifacts — the tiny table every remap reads and the refmap Mixin resolves its own
        // annotation strings through — are *derived*, below, by the task graph this script
        // declares; nothing is read from a committed `mappings/` tree, because there is none.
        if (!release[4].equals("")) {
            declareMappingTasks(version, release[4], release[5], release[6], release[7]);
            if (SCAN_ERROR != null) {
                Build.error(SCAN_ERROR);
                return;
            }
        }

        String refmap = "";
        if (!Build.feature("since-26")) {
            // The file itself is written into this same rendered-resources directory by the
            // `Tasks.publishText` terminal `declareMappingTasks` recorded, which is why nothing is
            // copied here: `[build] resource-dirs` already names
            // `target/jals/build/script/out/resources`, and the jar packages whatever the script
            // phase committed there.
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

    // ---------------------------------------------------------------------------------------------
    // The mapping task chain
    // ---------------------------------------------------------------------------------------------
    //
    // What the committed `mappings/` directory used to carry is derived at build time instead, by
    // host-side task nodes over data this script only *points at*:
    //
    //   client.txt ──────────┐
    //                        ├─ composeMappings ─ copyMappings ─┬─ publishText → the tiny table
    //   intermediary-v2.jar ─┘         (whole game)  (@Shadow)   │    both `[dependencies] remap`
    //        └ jarText                                          │    and `[build] remap` read
    //                                                           │
    //   the mixin sources ── scanMixins (below) ─ requests ─ resolveReferences ─ jsonFromLines
    //                                                            └─ publishText → the refmap
    //
    // The split is the point. Everything Mixin-shaped — which annotations select a member, which
    // of them a `#[cfg]` disables for this release, which `remap = false` opts out, what a refmap
    // looks like — is *this file's* knowledge and stays in this file. Everything table-shaped —
    // joining two published mapping sets, re-filing an entry under a second owner, translating a
    // member reference through the join — is generic mapping arithmetic, and rides the same
    // declarative task graph the game jar's own deobfuscation does: digest-verified, cached, and
    // offline on every run after the first.

    /**
     * Record the tasks that derive this release's mapping table and refmap.
     *
     * <p>The two published files land below the script's output root: the table at
     * `mappings/intermediary-<version>.tiny`, which is where `jals.toml`'s
     * `[[mappings.intermediary]]` alternative for this release points, and the refmap at
     * `resources/intricarpet-refmap.json`, inside the resource directory the mixin config's
     * `refmap` key names.
     */
    private static void declareMappingTasks(
        String version,
        String clientSha1,
        String clientBytes,
        String intermediarySha1,
        String intermediaryBytes) {
        List<MixinScan> scans = scanMixins();
        if (SCAN_ERROR != null) {
            return;
        }

        // What the two table-shaped artifacts need from the scan:
        //
        //   copies    one `mixin<tab>target<tab>member` line per active `@Shadow` of a Minecraft
        //             member. `@Shadow` is the one Mixin string no refmap answers — the mixin
        //             class has to *carry* the runtime name in its own bytecode — so the table
        //             re-files the target's entry under the mixin class, an identity section the
        //             reobfuscating remap then renames the declaration and every use of it by.
        //             The host side is strict: a shadow of a Minecraft member the table has no
        //             entry for fails the build rather than shipping a mixin that cannot apply.
        //   requests  one `path<tab>context<tab>selector` line per active selector a Minecraft
        //             class owns, twice — once under `mappings`, once under
        //             `data/named:intermediary`, the two halves of a refmap carrying one table.
        //             The path segments are the JSON keys the selector ends up under, so the
        //             resolved lines feed `jsonFromLines` directly and this script never sees a
        //             translated name.
        StringBuilder copies = new StringBuilder();
        StringBuilder requests = new StringBuilder();
        for (int i = 0; i < scans.size(); i++) {
            MixinScan scan = scans.get(i);
            if (startsWith(scan.target, "net/minecraft/")) {
                for (int s = 0; s < scan.shadows.size(); s++) {
                    copies.append(scan.name).append('\t').append(scan.target).append('\t')
                        .append(scan.shadows.get(s)).append('\n');
                }
            }
            for (int r = 0; r < scan.references.size(); r++) {
                String selector = scan.references.get(r);
                String owner = "";
                String rest = selector;
                if (selector.length() > 0 && selector.charAt(0) == 'L') {
                    int semi = indexOfChar(selector, ';', 0);
                    if (semi > 0) {
                        owner = selector.substring(1, semi);
                        rest = selector.substring(semi + 1);
                    }
                }
                int cut = rest.length();
                for (int c = 0; c < rest.length(); c++) {
                    char ch = rest.charAt(c);
                    if (ch == '(' || ch == ':') {
                        cut = c;
                        break;
                    }
                }
                String name = rest.substring(0, cut);
                String descriptor = rest.substring(cut);
                String effective = scan.target;
                if (!owner.equals("")) {
                    effective = owner;
                }
                // A selector no Minecraft class owns — Carpet's `doExplosionA`, a JDK method —
                // spells the same at runtime and needs no entry, exactly as it needed none when
                // the refmaps were generated files.
                if (!startsWith(effective, "net/minecraft/")) {
                    continue;
                }
                // A constructor keeps its name in every namespace, so a bare `<init>` would
                // resolve to itself: an entry that says nothing. One *with* a descriptor still
                // travels, because the descriptor's classes do move.
                if (startsWith(name, "<") && descriptor.equals("")) {
                    continue;
                }
                String path = "\u0001" + scan.name + "\u0001" + selector;
                requests.append("mappings").append(path).append('\t').append(scan.target)
                    .append('\t').append(selector).append('\n');
                requests.append("data\u0001named:intermediary").append(path).append('\t')
                    .append(scan.target).append('\t').append(selector).append('\n');
            }
        }

        int official =
            Tasks.fetchText(
                Tasks.httpsUrl(
                    "https://piston-data.mojang.com/v1/objects/" + clientSha1 + "/client.txt"),
                Tasks.sha1(clientSha1),
                Tasks.bytes(Long.parseLong(clientBytes)));
        int jar =
            Tasks.fetchJar(
                Tasks.httpsUrl(
                    "https://maven.fabricmc.net/net/fabricmc/intermediary/"
                        + version
                        + "/intermediary-"
                        + version
                        + "-v2.jar"),
                Tasks.sha1(intermediarySha1),
                Tasks.bytes(Long.parseLong(intermediaryBytes)));
        int intermediary = Tasks.jarText(jar, "mappings/mappings.tiny");
        MappingFormat pair = Tasks.tinyV2("intermediary", "mojang");
        int composed = Tasks.composeMappings(official, intermediary, pair);
        int extended = Tasks.copyMappings(composed, copies.toString());
        Tasks.publishText("mappings/intermediary-" + version + ".tiny", extended);
        int resolved = Tasks.resolveReferences(requests.toString(), extended, pair);
        Tasks.publishText("resources/intricarpet-refmap.json", Tasks.jsonFromLines(resolved));
    }

    // ---------------------------------------------------------------------------------------------
    // The mixin scan
    // ---------------------------------------------------------------------------------------------
    //
    // Everything below reads the mixin sources as *bytes*, before any `#[cfg]` lowering, and
    // answers the one question the task chain cannot: which member selectors does this release's
    // mixins actually carry, and which `@Shadow` declarations does it actually compile. It is the
    // same scan the `mappings/regenerate.py` generator performed when the tables were committed
    // files — comments blanked, literals blanked, `#[cfg]` predicates evaluated against this
    // build's features, annotation elements read the way Mixin reads them — with one simplification
    // the split buys: a per-release build evaluates a predicate here instead of attaching it to a
    // reference for a consumer to evaluate, so a disabled branch never reaches the requests at all.
    //
    // Bytes rather than text because the scan runs inside the script's instruction budget: a file
    // arrives through `Project.read`, whose bytes the host fills without spending one instruction
    // of it, and every loop below is an array index rather than a `String.charAt` call. Only the
    // names a loop *finds* are decoded into strings, and they are short. UTF-8 is transparent to
    // this: every byte of a multi-byte character is outside ASCII, so no structural comparison —
    // a bracket, a quote, an identifier byte — can mistake one.

    private static final String MIXINS = "src/main/java/me/lntricate/intricarpet/mixins";

    /** The injector annotations whose `method` element and nested `@At` targets select members. */
    private static final String[] INJECTORS = {
        "Inject",
        "ModifyArg",
        "ModifyArgs",
        "ModifyConstant",
        "ModifyExpressionValue",
        "ModifyReceiver",
        "ModifyReturnValue",
        "ModifyVariable",
        "Redirect",
        "WrapMethod",
        "WrapOperation",
        "WrapWithCondition",
    };

    /** The accessor annotations whose `value` element names the member directly. */
    private static final String[] ACCESSORS = {"Accessor", "Invoker"};

    /** The first scan failure, reported through `Build.error` where the chain is declared. */
    private static String SCAN_ERROR;

    /** One `private static final String NAME = "selector"` branch this release compiles. */
    private static final class Constant {

        final String name;
        final String value;

        Constant(String name, String value) {
            this.name = name;
            this.value = value;
        }
    }

    /** One `#[cfg(...)]` attribute: where it ends, and what it asks. */
    private static final class Span {

        final int end;
        final String predicate;

        Span(int end, String predicate) {
            this.end = end;
            this.predicate = predicate;
        }
    }

    /** One mixin class: what it applies to, and every Minecraft name it spells out. */
    private static final class MixinScan {

        final String name;
        final String target;
        final List<String> references;
        final List<String> shadows;

        MixinScan(String name, String target, List<String> references, List<String> shadows) {
            this.name = name;
            this.target = target;
            this.references = references;
            this.shadows = shadows;
        }
    }

    private static void fail(String message) {
        if (SCAN_ERROR == null) {
            SCAN_ERROR = message;
        }
    }

    private static List<MixinScan> scanMixins() {
        List<MixinScan> scans = new ArrayList<>();
        String[] paths = Project.walkFiles(MIXINS);
        for (int i = 0; i < paths.length && SCAN_ERROR == null; i++) {
            String path = paths[i];
            if (!endsWith(path, ".java")) {
                continue;
            }
            scanMixin(path, scans);
        }
        return scans;
    }

    private static void scanMixin(String path, List<MixinScan> scans) {
        byte[] text = Project.read(path);
        byte[] clean = new byte[text.length];
        byte[] skel = new byte[text.length];
        stripAndBlank(text, clean, skel);
        List<Span> spans = cfgSpans(clean, skel);

        String pkg = findPackage(skel);
        List<String> importNames = new ArrayList<>();
        List<String> importQualified = new ArrayList<>();
        collectImports(skel, importNames, importQualified);
        int mixinAt = findMixinAnnotation(skel);
        if (pkg.equals("") || mixinAt < 0) {
            fail(path + ": not a mixin class this script can read");
            return;
        }
        String simple = mixinTargetSimpleName(skel, mixinAt, path);
        if (simple.equals("")) {
            return;
        }
        String target = "";
        for (int i = 0; i < importNames.size(); i++) {
            if (importNames.get(i).equals(simple)) {
                target = importQualified.get(i);
            }
        }
        if (target.equals("")) {
            fail(path + ": `@Mixin(" + simple + ".class)` names an unimported type");
            return;
        }
        String predicate = governing(spans, skel, mixinAt);
        if (!predicate.equals("") && !evaluate(predicate)) {
            // A release this mixin does not exist in: `ChunkMapAccessor` is the standing example,
            // gated whole on `since-1.21.5`.
            return;
        }

        String stem = path.substring(lastIndexOfChar(path, '/') + 1, path.length() - 5);
        String name = internal(pkg + "." + stem);

        List<String> declared = new ArrayList<>();
        List<Constant> constants = new ArrayList<>();
        collectConstants(clean, skel, spans, declared, constants);
        if (SCAN_ERROR != null) {
            return;
        }

        List<String> references = new ArrayList<>();
        collectReferences(clean, skel, spans, path, declared, constants, references);
        if (SCAN_ERROR != null) {
            return;
        }

        List<String> shadows = new ArrayList<>();
        collectShadows(skel, spans, path, shadows);
        if (SCAN_ERROR != null) {
            return;
        }

        scans.add(new MixinScan(name, internal(target), references, shadows));
    }

    /**
     * One pass over `text` filling both derived arrays, length-preservingly, so one offset names
     * the same byte in all three: `clean` with every comment blanked (what literal *values* are
     * read from — commented-out code is why comments go first), and `skel` with every literal's
     * contents blanked on top of that (what structure is read from, where a bracket or semicolon
     * inside a string can never be mistaken for one in the source).
     */
    private static void stripAndBlank(byte[] text, byte[] clean, byte[] skel) {
        int length = text.length;
        for (int i = 0; i < length; i++) {
            clean[i] = text[i];
            skel[i] = text[i];
        }
        int index = 0;
        while (index < length) {
            byte b = text[index];
            if (b == '"' || b == '\'') {
                int end = endOfLiteral(text, index);
                for (int position = index + 1; position < end - 1 && position < length;
                    position++) {
                    skel[position] = ' ';
                }
                index = end;
            } else if (b == '/' && index + 1 < length && text[index + 1] == '/') {
                while (index < length && text[index] != '\n') {
                    clean[index] = ' ';
                    skel[index] = ' ';
                    index++;
                }
            } else if (b == '/' && index + 1 < length && text[index + 1] == '*') {
                int end = indexOfBytes(text, (byte) '*', (byte) '/', index + 2);
                end = end < 0 ? length : end + 2;
                for (int position = index; position < end && position < length; position++) {
                    if (text[position] != '\n') {
                        clean[position] = ' ';
                        skel[position] = ' ';
                    }
                }
                index = end;
            } else {
                index++;
            }
        }
    }

    /** The index just past the literal opening at `start`, escapes included. */
    private static int endOfLiteral(byte[] text, int start) {
        byte quote = text[start];
        int index = start + 1;
        while (index < text.length && text[index] != quote) {
            index += text[index] == '\\' ? 2 : 1;
        }
        return index + 1;
    }

    /** `(offset just past the attribute, predicate)` for every `#[cfg(...)]`, in source order. */
    private static List<Span> cfgSpans(byte[] clean, byte[] skel) {
        List<Span> spans = new ArrayList<>();
        int i = 0;
        while (i + 1 < skel.length) {
            if (skel[i] == '#' && skel[i + 1] == '[') {
                int j = skipWs(skel, i + 2);
                if (keywordAt(skel, j, "cfg")) {
                    int k = skipWs(skel, j + 3);
                    if (k < skel.length && skel[k] == '(') {
                        int bracket = matching(skel, i + 1, '[', ']');
                        int paren = matching(skel, k, '(', ')');
                        if (bracket < 0 || paren < 0) {
                            fail("unbalanced `#[cfg(...)]` at offset " + i);
                            return spans;
                        }
                        spans.add(new Span(bracket, decode(clean, k + 1, paren - 1)));
                        i = bracket;
                        continue;
                    }
                }
            }
            i++;
        }
        return spans;
    }

    /**
     * The `#[cfg(...)]` that decides whether the declaration at `position` exists: the nearest one
     * no `;`, `{` or `}` separates from it. The separators are also what keeps a class-level
     * attribute from reaching the members inside the class body.
     *
     * <p>Only the nearest preceding span is a candidate, which is what the definition reduces to:
     * a separator between it and `position` lies between *every* earlier span and `position` too,
     * so one that fails the check fails it for all of them. That is the difference between a walk
     * of every span over a range of the file and one range check, inside the script's instruction
     * budget.
     */
    private static String governing(List<Span> spans, byte[] skel, int position) {
        for (int i = spans.size() - 1; i >= 0; i--) {
            Span span = spans.get(i);
            if (span.end <= position) {
                if (containsAny(skel, span.end, position, ';', '{', '}')) {
                    return "";
                }
                return span.predicate;
            }
        }
        return "";
    }

    /** Predicates already answered, as two lists — the features of one build never move mid-run. */
    private static final List<String> EVAL_TRUE = new ArrayList<>();
    private static final List<String> EVAL_FALSE = new ArrayList<>();

    /** A `#[cfg]` predicate against *this build's* features, which `Build.feature` answers directly. */
    private static boolean evaluate(String predicate) {
        for (int i = 0; i < EVAL_TRUE.size(); i++) {
            if (EVAL_TRUE.get(i).equals(predicate)) {
                return true;
            }
        }
        for (int i = 0; i < EVAL_FALSE.size(); i++) {
            if (EVAL_FALSE.get(i).equals(predicate)) {
                return false;
            }
        }
        boolean result = evaluateUncached(predicate);
        if (result) {
            EVAL_TRUE.add(predicate);
        } else {
            EVAL_FALSE.add(predicate);
        }
        return result;
    }

    private static boolean evaluateUncached(String predicate) {
        String t = trimWs(predicate);
        String[] combinators = {"not", "all", "any"};
        for (int c = 0; c < combinators.length; c++) {
            String op = combinators[c];
            if (!startsWith(t, op)
                || (t.length() > op.length() && isWordChar(t.charAt(op.length())))) {
                continue;
            }
            int j = skipWsStr(t, op.length());
            if (j >= t.length() || t.charAt(j) != '(') {
                continue;
            }
            int close = matchingStr(t, j, '(', ')');
            if (close != t.length()) {
                continue;
            }
            List<String> parts = splitArguments(t.substring(j + 1, close - 1));
            if (op.equals("not")) {
                if (parts.size() != 1) {
                    fail("unsupported `#[cfg]` predicate: " + predicate);
                    return false;
                }
                return !evaluate(parts.get(0));
            }
            if (op.equals("all")) {
                for (int p = 0; p < parts.size(); p++) {
                    if (!evaluate(parts.get(p))) {
                        return false;
                    }
                }
                return true;
            }
            boolean any = false;
            for (int p = 0; p < parts.size(); p++) {
                if (evaluate(parts.get(p))) {
                    any = true;
                }
            }
            return any;
        }
        if (startsWith(t, "feature") && (t.length() == 7 || !isWordChar(t.charAt(7)))) {
            int j = skipWsStr(t, 7);
            if (j < t.length() && t.charAt(j) == '=') {
                j = skipWsStr(t, j + 1);
                if (j < t.length() && t.charAt(j) == '"') {
                    int close = indexOfChar(t, '"', j + 1);
                    if (close > j && trimWs(t.substring(close + 1)).equals("")) {
                        return Build.feature(t.substring(j + 1, close));
                    }
                }
            }
        }
        fail("unsupported `#[cfg]` predicate: " + predicate);
        return false;
    }

    /** `text` split on the commas that are not inside brackets. */
    private static List<String> splitArguments(String text) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            if (ch == '(' || ch == '[') {
                depth++;
            } else if (ch == ')' || ch == ']') {
                depth--;
            } else if (ch == ',' && depth == 0) {
                String part = trimWs(text.substring(start, index));
                if (!part.equals("")) {
                    parts.add(part);
                }
                start = index + 1;
            }
        }
        String part = trimWs(text.substring(start));
        if (!part.equals("")) {
            parts.add(part);
        }
        return parts;
    }

    /** The `package` declaration's qualified name, or `null`. */
    private static String findPackage(byte[] skel) {
        for (int i = 0; i < skel.length; i++) {
            if (skel[i] != 'p'
                || !keywordAt(skel, i, "package")
                || (i > 0 && isWordChar(skel[i - 1]))) {
                continue;
            }
            int j = skipWs(skel, i + 7);
            int end = j;
            while (end < skel.length && (isWordChar(skel[end]) || skel[end] == '.')) {
                end++;
            }
            if (end > j) {
                int k = skipWs(skel, end);
                if (k < skel.length && skel[k] == ';') {
                    return decode(skel, j, end);
                }
            }
            return "";
        }
        return "";
    }

    /** Every `import [static] a.b.C;`, as parallel simple-name and qualified-name lists. */
    private static void collectImports(byte[] skel, List<String> names, List<String> qualified) {
        for (int i = 0; i < skel.length; i++) {
            if (skel[i] != 'i'
                || !keywordAt(skel, i, "import")
                || (i > 0 && isWordChar(skel[i - 1]))) {
                continue;
            }
            int j = skipWs(skel, i + 6);
            if (keywordAt(skel, j, "static")) {
                j = skipWs(skel, j + 6);
            }
            int end = j;
            while (end < skel.length && (isWordChar(skel[end]) || skel[end] == '.')) {
                end++;
            }
            if (end == j) {
                continue;
            }
            int k = skipWs(skel, end);
            if (k < skel.length && skel[k] == ';') {
                String full = decode(skel, j, end);
                names.add(afterLastDot(full));
                qualified.add(full);
                i = k;
            }
        }
    }

    /** The offset of the class's `@Mixin` annotation, or -1. */
    private static int findMixinAnnotation(byte[] skel) {
        for (int i = 0; i < skel.length; i++) {
            if (skel[i] == '@' && keywordAt(skel, i + 1, "Mixin")) {
                return i;
            }
        }
        return -1;
    }

    /** The `X` of `@Mixin(X.class)`, or `null` with the reason recorded. */
    private static String mixinTargetSimpleName(byte[] skel, int at, String path) {
        int j = skipWs(skel, at + 6);
        if (j >= skel.length || skel[j] != '(') {
            fail(path + ": not a mixin class this script can read");
            return "";
        }
        int k = skipWs(skel, j + 1);
        int end = k;
        while (end < skel.length && (isWordChar(skel[end]) || skel[end] == '.')) {
            end++;
        }
        String run = decode(skel, k, end);
        if (!endsWith(run, ".class")) {
            fail(path + ": not a mixin class this script can read");
            return "";
        }
        return run.substring(0, run.length() - 6);
    }

    /**
     * Every `static final String NAME = "selector"` whose `#[cfg]` this release satisfies, plus
     * the set of names declared at all — a reference through a constant every branch of which is
     * disabled contributes no selector, while one through a name this file never declares is the
     * mistake the scan refuses.
     */
    private static void collectConstants(
        byte[] clean,
        byte[] skel,
        List<Span> spans,
        List<String> declared,
        List<Constant> constants) {
        for (int i = 0; i < skel.length && SCAN_ERROR == null; i++) {
            if (skel[i] != 's'
                || !keywordAt(skel, i, "static")
                || (i > 0 && isWordChar(skel[i - 1]))) {
                continue;
            }
            int j = skipWs(skel, i + 6);
            if (!keywordAt(skel, j, "final")) {
                continue;
            }
            j = skipWs(skel, j + 5);
            if (!keywordAt(skel, j, "String")) {
                continue;
            }
            j = skipWs(skel, j + 6);
            int end = j;
            while (end < skel.length && isWordChar(skel[end])) {
                end++;
            }
            if (end == j) {
                continue;
            }
            String name = decode(skel, j, end);
            int k = skipWs(skel, end);
            if (k >= skel.length || skel[k] != '=') {
                continue;
            }
            k = skipWs(skel, k + 1);
            if (k >= skel.length || skel[k] != '"') {
                continue;
            }
            int close = endOfLiteral(skel, k);
            declared.add(name);
            String predicate = governing(spans, skel, i);
            if (predicate.equals("") || evaluate(predicate)) {
                constants.add(new Constant(name, decode(clean, k + 1, close - 1)));
            }
            i = close - 1;
        }
    }

    /**
     * Every selector the injector and accessor annotations of this file carry, under the `#[cfg]`
     * and `remap = false` rules Mixin itself reads them under.
     */
    private static void collectReferences(
        byte[] clean,
        byte[] skel,
        List<Span> spans,
        String path,
        List<String> declared,
        List<Constant> constants,
        List<String> references) {
        int i = 0;
        while (i < skel.length && SCAN_ERROR == null) {
            if (skel[i] != '@') {
                i++;
                continue;
            }
            int idStart = i + 1;
            int idEnd = idStart;
            while (idEnd < skel.length && isWordChar(skel[idEnd])) {
                idEnd++;
            }
            if (idEnd == idStart) {
                i++;
                continue;
            }
            int open = skipWs(skel, idEnd);
            if (open >= skel.length || skel[open] != '(') {
                i = idEnd;
                continue;
            }
            boolean injector = isOneOf(skel, idStart, idEnd, INJECTORS);
            boolean accessor = isOneOf(skel, idStart, idEnd, ACCESSORS);
            if (!injector && !accessor) {
                i = idEnd;
                continue;
            }
            int close = matching(skel, open, '(', ')');
            if (close < 0) {
                fail(path + ": unbalanced `(` at offset " + open);
                return;
            }
            if (!injector && !accessor) {
                i = idEnd;
                continue;
            }
            String predicate = governing(spans, skel, i);
            if (!predicate.equals("") && !evaluate(predicate)) {
                i = idEnd;
                continue;
            }
            if (SCAN_ERROR != null) {
                return;
            }
            int start = open + 1;
            int end = close - 1;
            if (accessor) {
                literalsBetween(clean, skel, start, end, references);
                i = idEnd;
                continue;
            }
            // `@At` is a nested annotation with a selector and a `remap` of its own, and the two
            // are read separately from the injector's: `ExplosionLogHelperMixin` turns remapping
            // off on an `@At` whose target is a JDK method while leaving the injector's own alone.
            List<Integer> atOpens = new ArrayList<>();
            List<Integer> atCloses = new ArrayList<>();
            findAtSpans(skel, start, end, atOpens, atCloses);
            int bodyLength = end > start ? end - start : 0;
            byte[] outer = new byte[bodyLength];
            for (int k = 0; k < bodyLength; k++) {
                outer[k] = skel[start + k];
            }
            for (int a = 0; a < atOpens.size(); a++) {
                int from = atOpens.get(a).intValue() - start;
                int to = atCloses.get(a).intValue() - start;
                for (int k = from; k < to && k < bodyLength; k++) {
                    outer[k] = ' ';
                }
            }
            if (!containsRemapFalse(outer, 0, bodyLength)) {
                for (int a = 0; a < atOpens.size() && SCAN_ERROR == null; a++) {
                    int atOpen = atOpens.get(a).intValue();
                    int atClose = atCloses.get(a).intValue();
                    if (containsRemapFalse(skel, atOpen, atClose)) {
                        continue;
                    }
                    int from = 0;
                    while (SCAN_ERROR == null) {
                        int at = findElement(skel, atOpen, atClose, "target", from);
                        if (at < 0) {
                            break;
                        }
                        selectors(clean, skel, at, path, declared, constants, references);
                        from = at - atOpen + 1;
                    }
                }
                int from = 0;
                while (SCAN_ERROR == null) {
                    int at = findElement(outer, 0, bodyLength, "method", from);
                    if (at < 0) {
                        break;
                    }
                    selectors(clean, skel, start + at, path, declared, constants, references);
                    from = at + 1;
                }
            }
            i = idEnd;
        }
    }

    /** The `@At(...)` spans inside `[start, end)`, as parallel open/close lists. */
    private static void findAtSpans(
        byte[] skel, int start, int end, List<Integer> opens, List<Integer> closes) {
        int i = start;
        while (i < end) {
            if (skel[i] == '@' && keywordAt(skel, i + 1, "At")) {
                int j = skipWs(skel, i + 3);
                if (j < end && skel[j] == '(') {
                    int close = matching(skel, j, '(', ')');
                    if (close < 0) {
                        return;
                    }
                    opens.add(Integer.valueOf(j));
                    closes.add(Integer.valueOf(close));
                    i = close;
                    continue;
                }
            }
            i++;
        }
    }

    /** The selectors an annotation element's value names, starting at `position`. */
    private static void selectors(
        byte[] clean,
        byte[] skel,
        int position,
        String path,
        List<String> declared,
        List<Constant> constants,
        List<String> out) {
        int p = skipWs(skel, position);
        if (p >= skel.length) {
            fail(path + ": unreadable selector at offset " + position);
            return;
        }
        byte b = skel[p];
        if (b == '{') {
            int close = matching(skel, p, '{', '}');
            if (close < 0) {
                fail(path + ": unbalanced `{` at offset " + p);
                return;
            }
            literalsBetween(clean, skel, p, close, out);
        } else if (b == '"') {
            int close = endOfLiteral(skel, p);
            out.add(decode(clean, p + 1, close - 1));
        } else {
            int end = p;
            while (end < skel.length && (isWordChar(skel[end]) || skel[end] == '.')) {
                end++;
            }
            if (end == p) {
                fail(path + ": unreadable selector at offset " + position);
                return;
            }
            String run = decode(skel, p, end);
            String simple = afterLastDot(run);
            boolean isDeclared = false;
            for (int i = 0; i < declared.size(); i++) {
                if (declared.get(i).equals(simple)) {
                    isDeclared = true;
                }
            }
            if (!isDeclared) {
                fail(path + ": `" + run + "` is not a constant of this file");
                return;
            }
            for (int i = 0; i < constants.size(); i++) {
                Constant constant = constants.get(i);
                if (constant.name.equals(simple)) {
                    out.add(constant.value);
                }
            }
        }
    }

    /** The contents of every string literal in `[from, to)`. */
    private static void literalsBetween(
        byte[] clean, byte[] skel, int from, int to, List<String> out) {
        int index = from;
        while (index < to) {
            if (skel[index] == '"') {
                int close = endOfLiteral(skel, index);
                out.add(decode(clean, index + 1, close - 1));
                index = close;
            } else {
                index++;
            }
        }
    }

    /** Every `@Shadow` this release compiles, by the member name it declares. */
    private static void collectShadows(
        byte[] skel, List<Span> spans, String path, List<String> shadows) {
        int i = 0;
        while (i < skel.length && SCAN_ERROR == null) {
            if (skel[i] != '@' || !keywordAt(skel, i + 1, "Shadow")) {
                i++;
                continue;
            }
            int position = i + 7;
            int args = skipWs(skel, position);
            if (args < skel.length && skel[args] == '(') {
                int close = matching(skel, args, '(', ')');
                if (close < 0) {
                    fail(path + ": unbalanced `(` at offset " + args);
                    return;
                }
                if (containsRemapFalse(skel, args, close)) {
                    i = close;
                    continue;
                }
                position = close;
            }
            position = skipAnnotations(skel, position);
            // Modifiers and a type, then the name — and the same read serves a field and a method,
            // because the name is the last identifier before the `;`, `=` or `(` that ends the head.
            int head = position;
            while (head < skel.length
                && skel[head] != ';'
                && skel[head] != '('
                && skel[head] != '{'
                && skel[head] != '=') {
                head++;
            }
            String member = lastIdentifier(skel, position, head);
            if (member.equals("")) {
                fail(path + ": unreadable `@Shadow` at offset " + i);
                return;
            }
            String predicate = governing(spans, skel, i);
            if (predicate.equals("") || evaluate(predicate)) {
                shadows.add(member);
            }
            i = head > position ? head : position;
        }
    }

    /** `position` advanced past the annotations that follow it, if any. */
    private static int skipAnnotations(byte[] skel, int position) {
        while (true) {
            int j = skipWs(skel, position);
            if (j >= skel.length || skel[j] != '@') {
                return position;
            }
            int idStart = j + 1;
            int idEnd = idStart;
            while (idEnd < skel.length && isWordChar(skel[idEnd])) {
                idEnd++;
            }
            if (idEnd == idStart) {
                return position;
            }
            int k = skipWs(skel, idEnd);
            if (k < skel.length && skel[k] == '(') {
                int close = matching(skel, k, '(', ')');
                if (close < 0) {
                    return position;
                }
                position = close;
            } else {
                position = k;
            }
        }
    }

    /** The last `[A-Za-z_$][A-Za-z0-9_$]*` run in `skel[from, to)`, or `null`. */
    private static String lastIdentifier(byte[] skel, int from, int to) {
        int start = -1;
        int end = -1;
        int i = from;
        while (i < to) {
            if (isIdentStart(skel[i])) {
                int stop = i + 1;
                while (stop < to && isWordChar(skel[stop])) {
                    stop++;
                }
                start = i;
                end = stop;
                i = stop;
            } else {
                i++;
            }
        }
        if (start < 0) {
            return "";
        }
        return decode(skel, start, end);
    }

    /** Whether `skel[from, to)` carries a `remap = false` element anywhere. */
    private static boolean containsRemapFalse(byte[] skel, int from, int to) {
        int at = 0;
        while (true) {
            at = findElement(skel, from, to, "remap", at);
            if (at < 0) {
                return false;
            }
            int j = at;
            if (j + 5 <= to
                && skel[j] == 'f'
                && skel[j + 1] == 'a'
                && skel[j + 2] == 'l'
                && skel[j + 3] == 's'
                && skel[j + 4] == 'e'
                && (j + 5 == to || !isWordChar(skel[j + 5]))) {
                return true;
            }
            at = at - from + 1;
        }
    }

    /**
     * The position just past a `name =` element and its whitespace within `skel[from, to)`, found
     * at or after `from + searchFrom`, or -1. Both word boundaries are checked, so `targetMethod =`
     * is not a `target` element. The answer is absolute, in `skel`'s own offsets.
     */
    private static int findElement(byte[] skel, int from, int to, String name, int searchFrom) {
        int length = name.length();
        int i = from + searchFrom;
        while (i + length <= to) {
            boolean match =
                (i == 0 || !isWordChar(skel[i - 1]))
                    && (i + length == to || !isWordChar(skel[i + length]));
            if (match) {
                for (int k = 0; k < length; k++) {
                    if (skel[i + k] != (byte) name.charAt(k)) {
                        match = false;
                        break;
                    }
                }
            }
            if (match) {
                int j = skipWs(skel, i + length);
                if (j < to && skel[j] == '=') {
                    return skipWs(skel, j + 1);
                }
            }
            i++;
        }
        return -1;
    }

    /** The index just past the bracket closing the one at `start`, or -1 (recorded as a failure). */
    private static int matching(byte[] text, int start, char open, char close) {
        int depth = 0;
        for (int index = start; index < text.length; index++) {
            if (text[index] == (byte) open) {
                depth++;
            } else if (text[index] == (byte) close) {
                depth--;
                if (depth == 0) {
                    return index + 1;
                }
            }
        }
        fail("unbalanced `" + open + "` at offset " + start);
        return -1;
    }

    private static int matchingStr(String text, int start, char open, char close) {
        int depth = 0;
        for (int index = start; index < text.length(); index++) {
            if (text.charAt(index) == open) {
                depth++;
            } else if (text.charAt(index) == close) {
                depth--;
                if (depth == 0) {
                    return index + 1;
                }
            }
        }
        fail("unbalanced `" + open + "` at offset " + start);
        return -1;
    }

    private static boolean keywordAt(byte[] text, int position, String word) {
        int length = word.length();
        if (position < 0 || position + length > text.length) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            if (text[position + i] != (byte) word.charAt(i)) {
                return false;
            }
        }
        int after = position + length;
        return after >= text.length || !isWordChar(text[after]);
    }

    private static boolean containsAny(byte[] text, int from, int to, char a, char b, char c) {
        for (int i = from; i < to && i < text.length; i++) {
            if (text[i] == (byte) a || text[i] == (byte) b || text[i] == (byte) c) {
                return true;
            }
        }
        return false;
    }

    /** `bytes[from, to)` decoded as the Latin-1-shaped characters a name or predicate is made of. */
    private static String decode(byte[] bytes, int from, int to) {
        char[] chars = new char[to > from ? to - from : 0];
        for (int i = from; i < to; i++) {
            chars[i - from] = (char) (bytes[i] & 0xFF);
        }
        return new String(chars);
    }

    private static int indexOfBytes(byte[] text, byte first, byte second, int from) {
        for (int i = from; i + 1 < text.length; i++) {
            if (text[i] == first && text[i + 1] == second) {
                return i;
            }
        }
        return -1;
    }

    private static int skipWs(byte[] text, int from) {
        int i = from;
        while (i < text.length && isSpace(text[i])) {
            i++;
        }
        return i;
    }

    private static int skipWsStr(String text, int from) {
        int i = from;
        while (i < text.length() && isSpace(text.charAt(i))) {
            i++;
        }
        return i;
    }

    private static boolean isSpace(byte c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '\f';
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '\f';
    }

    private static boolean isWordChar(byte c) {
        return c == '_'
            || c == '$'
            || (c >= '0' && c <= '9')
            || (c >= 'A' && c <= 'Z')
            || (c >= 'a' && c <= 'z');
    }

    private static boolean isWordChar(char c) {
        return c == '_'
            || c == '$'
            || (c >= '0' && c <= '9')
            || (c >= 'A' && c <= 'Z')
            || (c >= 'a' && c <= 'z');
    }

    private static boolean isIdentStart(byte c) {
        return c == '_' || c == '$' || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    private static int indexOfChar(String text, char target, int from) {
        for (int i = from; i < text.length(); i++) {
            if (text.charAt(i) == target) {
                return i;
            }
        }
        return -1;
    }

    private static int lastIndexOfChar(String text, char target) {
        for (int i = text.length() - 1; i >= 0; i--) {
            if (text.charAt(i) == target) {
                return i;
            }
        }
        return -1;
    }

    private static boolean startsWith(String text, String prefix) {
        if (prefix.length() > text.length()) {
            return false;
        }
        for (int i = 0; i < prefix.length(); i++) {
            if (text.charAt(i) != prefix.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static boolean endsWith(String text, String suffix) {
        if (suffix.length() > text.length()) {
            return false;
        }
        int offset = text.length() - suffix.length();
        for (int i = 0; i < suffix.length(); i++) {
            if (text.charAt(offset + i) != suffix.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static String afterLastDot(String text) {
        int dot = lastIndexOfChar(text, '.');
        return dot < 0 ? text : text.substring(dot + 1);
    }

    private static String internal(String dotted) {
        StringBuilder out = new StringBuilder(dotted.length());
        for (int i = 0; i < dotted.length(); i++) {
            char c = dotted.charAt(i);
            out.append(c == '.' ? '/' : c);
        }
        return out.toString();
    }

    private static String trimWs(String text) {
        int start = 0;
        while (start < text.length() && isSpace(text.charAt(start))) {
            start++;
        }
        int end = text.length();
        while (end > start && isSpace(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(start, end);
    }

    /** Whether `skel[from, to)` spells exactly one of `words`, compared byte by byte. */
    private static boolean isOneOf(byte[] skel, int from, int to, String[] words) {
        int length = to - from;
        for (int w = 0; w < words.length; w++) {
            String word = words[w];
            if (word.length() != length) {
                continue;
            }
            boolean match = true;
            for (int k = 0; k < length; k++) {
                if (skel[from + k] != (byte) word.charAt(k)) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return true;
            }
        }
        return false;
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

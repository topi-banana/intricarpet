// intricarpet's build script: pick the Minecraft release, set the Java level it needs, and put the
// two jars jals cannot fetch itself on the compile classpath.
//
// Everything Minecraft-shaped — the game jar, its bundled libraries, Mixin and MixinExtras — comes
// from the `minecraft` dependency in `jals.toml`, which `[features]` forwards this release to. This
// script fetches nothing over the network at all.

// Every release intricarpet supports, newest first, as `[release, javac --release level]`. The
// Java level mirrors `common.gradle`'s JAVA_COMPATIBILITY table: 16 for 1.17, 17 from 1.18, 21 from
// 1.20.5, and 25 from 26.1.
const CATALOG = [
    ["26.2", "25"],
    ["26.1.2", "25"],
    ["1.21.11", "21"],
    ["1.21.10", "21"],
    ["1.21.8", "21"],
    ["1.21.5", "21"],
    ["1.21.4", "21"],
    ["1.21.1", "21"],
    ["1.20.2", "17"],
    ["1.20.1", "17"],
    ["1.19.4", "17"],
    ["1.19.3", "17"],
    ["1.19.2", "17"],
    ["1.18.2", "17"],
    ["1.17.1", "16"],
];

// Where a locally provided jar is read from. Carpet and the Fabric loader are published only to
// repositories that hand out no digest jals could pin a `fetch_jar` against (masa's maven, the
// Fabric maven, jitpack), so they are supplied as files instead: `./gradlew :<release>:jalsLibs`
// copies the ones Gradle already resolved — carpet remapped to Mojang names, like the rest of this
// classpath — into here.
const LIB_DIR = "libs";

// Resolve the release. The release features are mutually exclusive, but `[features]` resolution is
// additive (Cargo's model), so this script is the only place the exclusivity can be enforced. A
// reported error publishes nothing and fails the build before anything else runs.
let selected = "";
let level = "";
for entry in CATALOG {
    if build.feature(entry[0]) {
        if selected != "" {
            build.error(
                "select exactly one Minecraft release feature, got `" + selected + "` and `"
                    + entry[0] + "`. The `default` list carries " + CATALOG[0][0]
                    + ", so selecting another release needs `--no-default-features --features "
                    + entry[0] + "`."
            );
            return;
        }
        selected = entry[0];
        level = entry[1];
    }
}
if selected == "" {
    build.error(
        "no Minecraft release selected. Pass one of the `[features]` releases, e.g. "
            + "`--no-default-features --features 1.21.8`."
    );
    return;
}
build.metadata("minecraft-version", selected);

// The Java level the release's own class files were compiled at. `[build] release` cannot say this,
// because it is per-release and the manifest is not.
build.add_javac_arg("--release");
build.add_javac_arg(level);

// Mixin and MixinExtras both ship an annotation processor in the jar that carries their classes,
// and javac discovers a processor on the classpath whether or not anything asked for one. Refmap
// generation belongs to the Gradle/loom build that packages the mod, not to a compile check, so
// this build never runs one.
build.add_javac_arg("-proc:none");

// Carpet and the Fabric loader, if they have been supplied. Absence is a warning rather than an
// error: the sources still lower, the editor still resolves everything Minecraft-shaped, and
// `jals expand` — the step the Gradle build depends on — needs no classpath at all.
let jars = [];
if project.exists(LIB_DIR) {
    for entry in project.read_dir(LIB_DIR) {
        if entry.ends_with(".jar") {
            jars.push(entry);
        }
    }
}
for jar in jars {
    build.rerun_if_changed(jar);
    build.add_classpath(jar);
}
if jars.is_empty() {
    build.warning(
        "no jars in `" + LIB_DIR + "`, so carpet and the Fabric loader are not on the compile "
            + "classpath; run `./gradlew :" + selected + ":jalsLibs` to populate it."
    );
}

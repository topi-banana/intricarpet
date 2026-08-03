// intricarpet's build definition. This is the whole build — there is no Gradle any more.
//
// It selects the Minecraft release from `[features]`, sets the Java level that release needs, puts
// the two jars jals cannot fetch itself on the compile classpath, and assembles everything the mod
// jar carries besides class files: the expanded `fabric.mod.json`, the release's access widener,
// and the licence. `jals package` archives the result.
//
// Everything Minecraft-shaped — the game jar, its bundled libraries, Mixin and MixinExtras — comes
// from the `minecraft` dependency in `jals.toml`, which `[features]` forwards this release to. This
// script fetches nothing over the network at all: the release metadata is in the repository, and
// carpet and the loader arrive through `scripts/fetch-libs.sh`.

// Every release intricarpet supports, newest first, as `[release, javac --release level]`. The
// Java level is the one that release's own class files were compiled at: 16 for 1.17, 17 from 1.18,
// 21 from 1.20.5, and 25 from 26.1. Everything else about a release lives in its
// `versions/<release>/release.properties`.
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

// Where `scripts/fetch-libs.sh` puts carpet and the Fabric loader. Neither is published to a
// repository jals could pin a `fetch_jar` digest against (masa's maven, the Fabric maven, jitpack),
// so they are supplied as files rather than fetched by this script.
const LIB_DIR = "libs";

// `key = value` lines, `#` comments and blank lines ignored. Both property files this build reads
// are in that format, which is also what the Gradle build read them as. Rhai's `trim` mutates in
// place and returns nothing, so each part is trimmed as a statement rather than in the expression
// that produced it.
fn read_properties(text) {
    let values = #{};
    for line in text.split('\n') {
        let entry = line;
        entry.trim();
        if entry == "" || entry.starts_with("#") {
            continue;
        }
        let separator = entry.index_of('=');
        if separator < 0 {
            continue;
        }
        let key = entry.sub_string(0, separator);
        key.trim();
        let value = entry.sub_string(separator + 1);
        value.trim();
        values[key] = value;
    }
    return values;
}

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

let mod_properties = "mod.properties";
let release_properties = "versions/" + selected + "/release.properties";
let access_widener = "versions/" + selected + "/intricarpet.accesswidener";
build.rerun_if_changed(mod_properties);
build.rerun_if_changed(release_properties);
let modinfo = read_properties(project.read_text(mod_properties));
let release = read_properties(project.read_text(release_properties));
build.metadata("minecraft-version", release.minecraft_version);

// The mod version, with the same suffix rule the Gradle build had: a release build is bare, a CI
// build carries its run number, and anything else is a snapshot. Host values are opt-in by prefix,
// so CI exports `JALS_BUILD_ID` / `JALS_BUILD_RELEASE`.
build.rerun_if_env_changed("JALS_BUILD_ID");
build.rerun_if_env_changed("JALS_BUILD_RELEASE");
let build_id = build.env("JALS_BUILD_ID");
let mod_version = modinfo.mod_version;
if build.env("JALS_BUILD_RELEASE") != "true" {
    if build_id == () {
        mod_version += "-SNAPSHOT";
    } else {
        mod_version += "+build." + build_id;
    }
}
build.metadata("mod-version", mod_version);

// The Java level the release's own class files were compiled at. `[build] release` cannot say this,
// because it is per-release and the manifest is not.
build.add_javac_arg("--release");
build.add_javac_arg(level);

// Mixin and MixinExtras both ship an annotation processor in the jar that carries their classes,
// and javac discovers a processor on the classpath whether or not anything asked for one. Refmap
// generation belongs to a remapping step this build does not have (see docs/jals.md), so nothing
// here runs one.
build.add_javac_arg("-proc:none");

// The mod descriptor, with the values the Gradle build used to expand through `processResources`.
// The authored file under `src/main/resources` is the template; this overwrites it in the output
// tree, which is what `build.add_resource` after the resource dirs means.
let descriptor = project.read_text("src/main/resources/fabric.mod.json");
descriptor.replace("${id}", modinfo.mod_id);
descriptor.replace("${name}", modinfo.mod_name);
descriptor.replace("${version}", mod_version);
descriptor.replace("${minecraft_dependency}", release.minecraft_dependency);
descriptor.replace("${carpet_dependency}", release.carpet_dependency);
build.add_resource(output.write_text("fabric.mod.json", descriptor), "fabric.mod.json");

// The access widener is per release and lives beside its release properties, but the loader reads
// it from the archive root under the name `fabric.mod.json` gives — so this is exactly the case
// `add_resource`'s explicit destination exists for.
build.rerun_if_changed(access_widener);
build.add_resource(access_widener, "intricarpet.accesswidener");

// The licence, under the name the Gradle build renamed it to.
build.add_resource("LICENSE", "LICENSE_" + modinfo.mod_id);

// Carpet and the Fabric loader, if they have been fetched. Absence is a warning rather than an
// error: the sources still lower, the editor still resolves everything Minecraft-shaped, and the
// message names the script that fixes it.
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
            + "classpath; run `scripts/fetch-libs.sh " + selected + "` to populate it."
    );
}

// Carpet is published in the namespace the game of its release shipped in. From 26.1 that is
// Mojang's own names, which is what everything else on this classpath uses, so the compile goes
// through. Before that it is Fabric's intermediary, and carpet's own API exposes Minecraft types
// (`Messenger.c` returns a chat component) — so those releases need an intermediary-to-named
// remapper, which is the one thing Gradle's loom did that jals has no equivalent for yet. Said here
// rather than left to javac, which would report it as a hundred unrelated type errors.
if release.carpet_namespace != "official" {
    build.warning(
        "carpet for " + selected + " is published in the `" + release.carpet_namespace
            + "` namespace while the rest of this classpath is in Mojang names, so the compile "
            + "will not typecheck against it. `jals expand`, `jals lint` and the language server "
            + "are unaffected; see docs/jals.md."
    );
}

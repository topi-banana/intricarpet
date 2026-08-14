// The half of the old Gradle build that was not Loom.
//
// `common.gradle` derived four things per release and fed them to `processResources` and
// `JavaCompile`: the Java release to compile against, the Mixin compatibility level, and the
// Minecraft and Carpet version ranges `fabric.mod.json` declares. All four are per-release data
// and none of them is a *build tool* concern, which is why they survive the tool: the catalog
// below is the same table `versions/*/gradle.properties` held, and the two templates it renders
// are the two files `processResources` used to filter.
//
// Everything Loom did — fetching the game, unwrapping the bundler, remapping it, putting Mixin and
// MixinExtras on the classpath — is `[dependencies] minecraft`, jals' own Minecraft SDK. Nothing
// here reaches for it.

// `[version, minecraft dependency, carpet dependency, java release]`, newest first.
//
// The two dependency strings go verbatim into `fabric.mod.json`; the release is both the `javac`
// `--release` and the Mixin `compatibilityLevel`, exactly as `common.gradle` derived them from the
// packed `mcVersion` (>= 26 → 25, >= 1.20.5 → 21, >= 1.18 → 17, >= 1.17 → 16).
const CATALOG = [
    ["26.2",    ">=26.2 <26.3",       ">=26.2",     25],
    ["26.1.2",  ">=26.1 <26.2",       ">=26.1",     25],
    ["1.21.11", ">=1.21.11 <1.22",    ">=1.4.193",  21],
    ["1.21.10", ">=1.21.9 <=1.21.10", ">=1.4.188",  21],
    ["1.21.8",  ">=1.21.6 <=1.21.8",  ">=1.4.169",  21],
    ["1.21.5",  "1.21.5",             ">=1.4.169",  21],
    ["1.21.4",  ">=1.21.2 <=1.21.4",  ">=1.4.161",  21],
    ["1.21.1",  ">=1.21 <=1.21.1",    ">=1.4.147",  21],
    ["1.20.2",  "1.20.2",             ">=1.4.119",  17],
    ["1.20.1",  "1.20.1",             ">=1.4.112",  17],
    ["1.19.4",  "1.19.4",             ">=1.4.100",  17],
    ["1.19.3",  "1.19.3",             ">=1.4.96",   17],
    ["1.19.2",  "1.19.2",             ">=1.4.84",   17],
    ["1.18.2",  "1.18.x",             ">=1.4.69",   17],
    ["1.17.1",  "1.17.x",             ">=1.4.57",   16],
];

// Resolve the release. Feature resolution is additive (Cargo's model), so the exclusivity the
// version features need — and the requirement that one be selected at all — can only be enforced
// here. A reported error publishes nothing and stops the build before a single fetch.
let release = ();
for entry in CATALOG {
    if build.feature(entry[0]) {
        if release != () {
            build.error(
                "select exactly one Minecraft version feature, got `" + release[0] + "` and `"
                    + entry[0] + "`"
            );
            return;
        }
        release = entry;
    }
}
if release == () {
    build.error(
        "select a Minecraft version feature, e.g. `--features " + CATALOG[0][0]
            + "`. There is deliberately no default: a release chooses the game jar, the Carpet jar "
            + "and every `#[cfg]` branch at once, and guessing one of those would compile the mod "
            + "against a game it was not asked for."
    );
    return;
}

let version = release[0];
let minecraft_dependency = release[1];
let carpet_dependency = release[2];
let java_release = release[3];
build.metadata("minecraft-version", version);

// `common.gradle`'s `JavaCompile` block, minus the tool. `--release` rather than
// `source`/`target`, because only `--release` also pins the platform API the mod may reach for.
build.add_javac_arg("--release");
build.add_javac_arg("" + java_release);
// Mixin's jar registers an annotation processor in `META-INF/services`, so a `javac` that still
// discovers processors on the class path starts it — where it immediately dies on the ASM it needs
// and is not given (`NoClassDefFoundError: org/objectweb/asm/tree/MethodNode`). Nothing in this
// build wants that processor: its job is the refmap, which is exactly what is not produced yet (see
// README §What is not there yet). Measured against this project's own class path: JDK 16, 17 and 21
// all start it and fail, and 25 does not look at all — so without this line "does the build work" is
// a property of whichever JDK happened to be installed rather than of anything written down.
build.add_javac_arg("-proc:none");
build.add_javac_arg("-Xlint:deprecation");
build.add_javac_arg("-Xlint:unchecked");
if java_release <= 8 {
    build.add_javac_arg("-Xlint:-options");
}

// The mod version. A release build is the bare version; anything else carries where it came from,
// the way the Gradle build did — except the two variables now need the `JALS_` prefix, the one
// opt-in a host environment has into a build script (a script that could read the whole
// environment could forward a token into a fetch URL).
build.rerun_if_env_changed("JALS_BUILD_ID");
build.rerun_if_env_changed("JALS_BUILD_RELEASE");
let mod_version = "2.0.7";
if build.env("JALS_BUILD_RELEASE") != "true" {
    let build_id = build.env("JALS_BUILD_ID");
    if build_id == () || build_id == "" {
        mod_version += "-SNAPSHOT";
    } else {
        mod_version += "+build." + build_id;
    }
}

// `processResources`' two filters. Both templates are project files, so they are read through the
// immutable snapshot and are rerun inputs without being declared as such.
let mod_json = project.read_text("templates/fabric.mod.json");
mod_json.replace("${id}", "intricarpet");
mod_json.replace("${name}", "IntriCarpet");
mod_json.replace("${version}", mod_version);
mod_json.replace("${minecraft_dependency}", minecraft_dependency);
mod_json.replace("${carpet_dependency}", carpet_dependency);
output.write_text("resources/fabric.mod.json", mod_json);

// `ChunkMapAccessor` is `#[cfg(feature = "since-1.21.5")]`, so on an older selection the class is
// not in the jar at all — and a Mixin configuration that names a missing class is a hard load
// failure, not a warning. The list is therefore built here rather than committed: the one place
// that knows whether the type exists is the same feature set that decided it.
let mixins = ["OptimizedExplosionMixin", "logger.ExplosionLogHelperMixin", "logger.ServerLevelMixin"];
if build.feature("since-1.21.5") {
    mixins.push("interactions.ChunkMapAccessor");
}
mixins.push("interactions.ChunkMapMixin");
mixins.push("interactions.EntityMixin");
mixins.push("interactions.EntitySelectorMixin");
mixins.push("interactions.NaturalSpawnerMixin");
mixins.push("interactions.ProjectileMixin");
mixins.push("interactions.ServerChunkCacheMixin");
mixins.push("interactions.ServerGamePacketListenerImplMixin");
mixins.push("interactions.ServerLevelMixin");
mixins.push("interactions.ServerPlayerMixin");

let mixin_list = "";
for entry in mixins {
    if mixin_list != "" {
        mixin_list += ",\n    ";
    }
    mixin_list += "\"" + entry + "\"";
}

let mixin_json = project.read_text("templates/intricarpet.mixins.json");
mixin_json.replace("${id}", "intricarpet");
mixin_json.replace("${compatibility_level}", "JAVA_" + java_release);
mixin_json.replace("${mixins}", mixin_list);
output.write_text("resources/intricarpet.mixins.json", mixin_json);

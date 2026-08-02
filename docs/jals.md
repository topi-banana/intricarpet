# Building IntriCarpet with jals

There is no Gradle in this repository. [jals](https://github.com/topi-banana/jals) is the build:
`jals.toml` is the manifest, `build.rhai` is the build definition, and `jals package` produces the
mod jar. The version switch that used to be the
[ReplayMod/Fallen-Breath preprocessor](https://github.com/Fallen-Breath/preprocessor) is jals build
features, and `src/main/java` is written in the jals dialect.

```sh
cargo install --locked --git https://github.com/topi-banana/jals jals-cli   # once
scripts/fetch-libs.sh 26.2                                                  # carpet + the loader
jals package --no-default-features --features 26.2                          # → target/intricarpet.jar
```

| before                                | now                                                          |
| ------------------------------------- | ------------------------------------------------------------ |
| `//#if MC >= 11800`                   | `#[cfg(feature = "mc-ge-1.18.2")]`                           |
| `//$$ ` on every inactive line        | nothing — an inactive branch is ordinary code                |
| the version graph in `build.gradle`   | `[features]` in `jals.toml`                                  |
| `versions/mapping-1.18.2-1.19.2.txt`  | two `#[cfg]` declarations, one per name                      |
| `versions/<r>/gradle.properties`      | `versions/<r>/release.properties`, read by `build.rhai`      |
| root `gradle.properties`              | `mod.properties`, read by `build.rhai`                       |
| `processResources` + `expand`         | `build.add_resource` in `build.rhai`                         |
| loom's dependency resolution          | the `minecraft` dependency + `scripts/fetch-libs.sh`         |
| `./gradlew build`                     | `jals package`                                               |

## The release features

`jals.toml` declares one feature per supported release (`1.17.1` … `26.2`) and a `mc-ge-<release>`
threshold beside it. The thresholds form a chain — `mc-ge-1.19.2` enables `mc-ge-1.18.2`, which
enables `mc-ge-1.17.1` — so selecting one release turns on every older threshold, and
`#[cfg(feature = "mc-ge-1.18.2")]` means exactly what `MC >= 11800` meant. Build features are a
set, not a number; the chain is what gives a set an ordering.

A threshold is named after the nearest *declared* release above the vanilla boundary it stands for,
because only declared releases are ever selected: 1.18's change is `mc-ge-1.18.2`, 1.21.2's is
`mc-ge-1.21.4`, 1.21.6's is `mc-ge-1.21.8`.

Selecting a release also forwards it to the `minecraft` dependency (`"1.21.8" = […,
"minecraft/1.21.8"]`), so the sources and the classpath cannot end up on different versions.

The releases are mutually exclusive, which an additive feature model cannot express, so `build.rhai`
rejects a second one. `default` carries the newest release, and switching replaces it:

```sh
jals build                                          # 26.2
jals build --no-default-features --features 1.21.8  # 1.21.8
```

That check lives in the build script, so it is `jals build`/`package` that reports two releases.
`jals expand` and `jals lint` run no build script by design — selecting two releases there just
resolves both threshold chains, which reads as the newer of the two. Always pass
`--no-default-features` with a release.

## What the build does

`build.rhai` is the whole build definition:

1. resolves the release from `[features]` and reads `mod.properties` and
   `versions/<release>/release.properties`;
2. sets `--release` for the Java level that Minecraft release needs, and `-proc:none` (the Mixin
   annotation processor belongs to a remapping step this build does not have — see below);
3. expands `src/main/resources/fabric.mod.json` — id, name, version, and the two dependency ranges —
   and adds it to the jar, along with the release's access widener and the licence;
4. puts every jar in `libs/` on the compile classpath.

`jals package` then compiles and archives the output tree: class files, everything under
`[build] resource-dirs` (`src/main/resources` — the mixin config and the icon), and the three files
the script contributed. The archive is stored-only with zeroed timestamps, so the same inputs give
the same bytes.

The mod version carries the same suffix the Gradle build gave it — `+build.<n>` in CI,
`-SNAPSHOT` otherwise, bare for a release — from `JALS_BUILD_ID` / `JALS_BUILD_RELEASE`. Build
scripts only see host environment values that opt in with the `JALS_` prefix, hence the names.

## The classpath

Minecraft, Mixin and MixinExtras come from the `minecraft` dependency in `jals.toml`, which is the
jals repository's `examples/minecraft` project: it fetches the selected release (every download
pinned by SHA-1), remaps it with the official Mojang mappings, publishes the decompiled game as
navigation sources, and puts the remapped jar — plus, from 1.18, the libraries bundled beside it
(Brigadier, Guava, log4j2) — on the compile classpath.

Carpet and the Fabric loader are not published anywhere jals can pin a digest against, so
`scripts/fetch-libs.sh <release>` downloads them into `libs/`, reading the release's own
`carpet_version` and the loader URL in `mod.properties` — the same coordinates the Gradle build
resolved. Without `libs/` the build still lowers and still resolves everything Minecraft-shaped;
`build.rhai` says so.

## What Gradle did that this does not

Loom's namespace remapping has no jals equivalent yet, and two things follow from it.

**Carpet before 26.1 is in the wrong namespace.** Fabric mods are published in Fabric's
*intermediary* names, and loom remapped them to Mojang names before compiling. Carpet's own API
exposes Minecraft types — `Messenger.c` returns a chat component — so compiling against an
intermediary carpet with a Mojang-named Minecraft does not typecheck. From 26.1 the game ships
deobfuscated and carpet is published in Mojang names, which is why those releases build here and
older ones do not. Each release records which it is as `carpet_namespace` in its
`release.properties`, `build.rhai` warns rather than letting javac report it as a hundred unrelated
type errors, and CI packages only the releases that can be compiled. Everything else — lowering,
linting, the language server — works on every release.

**A mod jar for an obfuscated release needs remapping on the way out too**, plus the mixin refmap
loom's annotation processor generated. So even where the compile succeeds today (26.1.2, 26.2 — which
need neither), a pre-26 release would need both steps added before its jar could be loaded by the
game.

Two smaller things went with Gradle: jar-in-jar bundling of MixinExtras, which the Gradle build did
for Minecraft below 1.20.5 (`include(…)`), and the JitPack publication, which was Gradle's
`maven-publish`. Both need capabilities jals does not have yet.

## Adding a release

1. `versions/<release>/release.properties` — Minecraft version, the two dependency ranges, the
   game versions, `carpet_namespace`, and `carpet_version`.
2. `versions/<release>/intricarpet.accesswidener`.
3. `jals.toml` — the release feature, its `mc-ge-<release>` threshold, and the chain link from the
   release below.
4. `build.rhai` — one catalog entry, `[release, javac --release level]`.
5. `.github/workflows/matrix_includes.json` — one entry.

## Checking a conversion

The lowered output is plain Java, so it can be diffed against anything. The conversion in this
repository was verified by lowering all 15 releases and comparing each one, token for token, with
what the old preprocessor emitted for the same release — including the `BaseComponent` →
`Component` rename that the mapping file used to apply from 1.19.2. CI re-checks that every release
still lowers on each run.

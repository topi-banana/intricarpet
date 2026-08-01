# Building IntriCarpet with jals

IntriCarpet targets 15 Minecraft releases from one source tree. That used to be the
[ReplayMod/Fallen-Breath preprocessor](https://github.com/Fallen-Breath/preprocessor): `//#if MC >=
11800` comment directives, `//$$`-commented inactive branches, a version graph in `build.gradle`,
and a `versions/mapping-*.txt` file for the one identifier Mojang renamed between releases.

All of it is gone. The version switch is now [jals](https://github.com/topi-banana/jals) build
features, and `src/main/java` is written in the jals dialect:

| before                                | now                                                          |
| ------------------------------------- | ------------------------------------------------------------ |
| `//#if MC >= 11800`                   | `#[cfg(feature = "mc-ge-1.18.2")]`                           |
| `//#if MC < 11800`                    | `#[cfg(not(feature = "mc-ge-1.18.2"))]`                      |
| `//#if MC >= 11800` + `//#elseif`     | `#[cfg(all(feature = "mc-ge-1.18.2", not(feature = "…")))]`  |
| `//$$ ` on every inactive line        | nothing — an inactive branch is ordinary code                |
| the version graph in `build.gradle`   | `[features]` in `jals.toml`                                  |
| `versions/mapping-1.18.2-1.19.2.txt`  | two `#[cfg]` declarations, one per name                      |

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

That check lives in the build script, so it is `jals build` that reports two releases. `jals expand`
and `jals lint` run no build script by design — selecting two releases there just resolves both
threshold chains, which reads as the newer of the two. Always pass `--no-default-features` with a
release, as `common.gradle` does.

Adding a release means adding three lines to `jals.toml` (the release, its threshold, the chain link
from the release below), one line to `settings.gradle`, one entry to `build.rhai`'s catalog and to
`.github/workflows/matrix_includes.json`, and a `versions/<release>/` directory — the same work as
before, minus the version graph.

## Building the mod jar

Gradle and loom still package the mod: remapping, the Mixin annotation processor, the access
widener, and `fabric.mod.json` expansion are all theirs. The only thing that changed is where the
Java it compiles comes from — each subproject runs

```sh
jals expand --manifest-path jals.toml --no-default-features --features <release> \
            --out-dir versions/<release>/build/jals/expanded
```

first (the `jalsExpand` task in `common.gradle`), and its `sourceSets.main.java` is the tree that
produced. Lowering is length-preserving: a `#[cfg]`-disabled declaration is blanked in place rather
than deleted, so every line number in a stack trace is still the line in `src/main/java`.

So `./gradlew build` needs a `jals` executable. It is taken from `PATH`, or from `$JALS`, or from
`-Pjals.executable=…`. jals publishes no binaries yet, so build it once:

```sh
cargo install --locked --git https://github.com/topi-banana/jals jals-cli
```

CI does the same, cached on the revision pinned in `.github/workflows/build.yml`'s `JALS_REV`.

## Building with jals alone

`jals build` compiles the mod's classes without Gradle — useful as a fast check that a release still
compiles, and as the thing an editor's language server sees.

The classpath comes from two places. Minecraft, Mixin and MixinExtras come from the `minecraft`
dependency in `jals.toml`, which is the jals repository's `examples/minecraft` project: it fetches
the selected release (every download pinned by SHA-1), remaps it with the official Mojang mappings,
publishes the decompiled game as navigation sources, and puts the remapped jar — plus, from 1.18,
the libraries bundled beside it — on the compile classpath.

Carpet and the Fabric loader are not published anywhere jals can pin a digest against, so they are
read from `libs/` instead. Gradle has already resolved both, carpet remapped to Mojang names by
loom, so copy them over rather than fetching them again:

```sh
./gradlew :1.21.8:jalsLibs
jals build --no-default-features --features 1.21.8
```

Without `libs/` the build still lowers and still resolves everything Minecraft-shaped; `build.rhai`
warns, and `carpet.*` and `net.fabricmc.*` are the only unresolved references.

`jals lint` and the jals language server take the same `--features` flags, so an editor shows the
release you are working on: a `#[cfg]`-disabled declaration is inactive there, not an error.

## Checking a conversion

The lowered output is plain Java, so it can be diffed against anything. The conversion in this
repository was verified by lowering all 15 releases and comparing each one, token for token, with
what the old preprocessor emitted for the same release — including the `BaseComponent` →
`Component` rename that the mapping file used to apply from 1.19.2.

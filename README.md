## IntriCarpet

[![License](https://img.shields.io/github/license/Fallen-Breath/fabric-mod-template.svg)](http://www.gnu.org/licenses/lgpl-3.0.html)

Built with [`jals`](https://github.com/topi-banana/jals) — no Gradle, no Loom, no preprocessor.

This is a carpet extension that adds mainly stuff useful for TNT tech development.

## Features
- Improved explosion logger, which groups explosions by position: `/log explosions compact`
- Adds Interactions, which let you disable the interactions between your player and the world, per player.

## Interactions
- Blocks: Includes tripwire, pressure plates, trampling farmland, etc.
- Chunkloading: Toggles all chunkloading for the player, including teleport tickets.
- Entities: Makes all entities noclip through the player.
- Mob Spawning: Disables the player's effect on mob spawning. Does not disable your effects on the mobcap.
- Random Ticks: Disables the player's effect on random ticks.
- Updates: Suppresses all updates caused by the player's interactions with the world.

Command format:
- `/interaction`: Shows your current interaction settings.
- `/interaction <interaction>`: Displays the value of the specified interaction.
- `/interaction <interaction> <true|false>`: Changes the value of the specified interaction.

## Building

The whole build is `jals` and a JDK. There is no Gradle wrapper to run, no `gradle.properties` to
edit and no `versions/` tree: one source tree, one `jals.toml`, and a `--features` flag naming the
Minecraft release.

```sh
# Install the toolchain (or use the `Setup jals` action in CI — see .github/workflows/ci.yml)
cargo install --git https://github.com/topi-banana/jals jals-cli

jals build --features 26.2          # -> target/jals/remap/intricarpet-2.0.7.jar
jals lint  --features 26.2
jals fmt $(git ls-files -- '*.java')  # no --features: one answer for the whole tree
```

The tree is formatted by `jals fmt`'s own defaults. There is no `jalsfmt.toml`, which is the point:
the defaults are the rule set, so the `jals` a contributor runs is the whole specification, and CI
checks it with `jals fmt --check` over the tracked `.java` files. `jals fmt` reads `jals.toml` — not
for a release, but for the one rule that would write dialect syntax — and never resolves a name, so
it needs neither a JDK nor a game jar.

Exactly one version feature must be selected — there is deliberately no default, because a release
chooses the game jar, the Carpet jar and every conditional branch at once. The supported releases
are the version features in `jals.toml`:

`1.17.1` `1.18.2` `1.19.2` `1.19.3` `1.19.4` `1.20.1` `1.20.2` `1.21.1` `1.21.4` `1.21.5` `1.21.8`
`1.21.10` `1.21.11` `26.1.2` `26.2`

### How one source tree targets fifteen releases

It used to be the [ReplayMod preprocessor](https://github.com/Fallen-Breath/preprocessor): the
source carried `//#if MC >= 11800` comments and lines commented out with `//$$`, and Gradle ran a
text pass over the tree — plus `versions/mapping-1.18.2-1.19.2.txt`, a source-level *rename* of one
type — to produce fifteen private copies of the source before compiling any of them.

Now the source says it directly, in the jals dialect:

```java
#[cfg(feature = "since-1.21.5")]
@Shadow
private static double euclideanDistanceSquared(ChunkPos chunkPos, Vec3 vec) {
    return 0.0;
}

#[cfg(not(feature = "since-1.21.5"))]
@Shadow
private static double euclideanDistanceSquared(ChunkPos chunkPos, Entity entity) {
    return 0.0;
}
```

Every branch is *live source*: it is parsed, formatted and navigable in an editor whichever release
is selected, instead of being a comment until a text pass revives it. The compile frontend strips
the attributes before `javac` sees the file and blanks the disabled branches in place, so a line
number in a stack trace still names the line that was written.

The predicates are ordinary Cargo-style features. `jals.toml` declares two kinds:

- a **version feature** (`1.21.11`) — one release of the mod. It routes the same name into the
  Minecraft SDK, activates that release's Carpet jar, and enables the highest threshold feature the
  release satisfies.
- a **threshold feature** (`since-1.21.5`) — what the source tests. Each enables its predecessor, so
  naming the highest turns on the whole chain and `MC >= 11800` becomes `feature = "since-1.18"`,
  `MC < 11800` becomes `not(feature = "since-1.18")`.

Two things the preprocessor did are gone rather than translated. The one cross-version type rename
(`BaseComponent` → `Component`) is now just `Component`, the interface every supported release has.
And the access widener — a Loom artifact that existed only so `javac` would accept one
package-private call — is a Mixin `@Invoker` accessor
(`mixins/interactions/ChunkMapAccessor.java`), which needs nothing of the build tool.

### What replaced what

| Gradle / Loom                                    | now                                                                    |
| ------------------------------------------------ | ---------------------------------------------------------------------- |
| `preprocess { … }`, `//#if`, `//$$`               | `#[cfg(...)]` over `[features]` in `jals.toml`                          |
| `versions/*/gradle.properties`                    | the catalog in `build.rhai`                                             |
| `versions/mapping-1.18.2-1.19.2.txt`              | nothing — the source uses `Component`                                   |
| `versions/*/intricarpet.accesswidener`            | `ChunkMapAccessor`, a Mixin `@Invoker`                                  |
| Loom: fetch / bundler / remap / Mixin classpath   | `[dependencies] minecraft`, jals' Minecraft SDK                         |
| `modImplementation` Carpet, `fabric-loader`       | `[dependencies]`, one optional Carpet jar per release                   |
| `processResources { expand … }`                   | `templates/*.json` rendered by `build.rhai`                             |
| `JavaCompile { options … }`, `sourceCompatibility`| `build.add_javac_arg` in `build.rhai`                                   |
| `buildAndGather`, the matrix workflows            | one `--features` matrix in `.github/workflows/ci.yml`                   |

### What is not there yet

`jals build` reaches a finished jar for **26.1.2 and 26.2** only. Those two releases ship
deobfuscated: the game jar, Carpet and the mod all speak the same names, which is why the old
Gradle build applied plain `fabric-loom` there and `fabric-loom-remap` everywhere else.

For 1.17.1 through 1.21.11 the mod compiles against Mojang-named game classes while the Carpet
release jar is *intermediary*-named, and jals cannot yet read the Tiny v2 mappings that relate the
two — so `javac` stops at the first Carpet API whose signature mentions a Minecraft type. The same
gap means a jar for those releases could not be loaded by Fabric even if it compiled, because a
Fabric mod is distributed in intermediary names. Everything before that step is real and is
exercised by CI on every release: the feature routing, the build script, the SDK's fetch → bundler
→ remap, and the `#[cfg]` lowering, which `jals lint` gates for all fifteen.

`[features] reobf` packages the classes under the release's official (obfuscated) names instead,
which is what a plain Mixin launcher wants; it is not what Fabric wants, so it is off by default.

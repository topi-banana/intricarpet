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

# On the thirteen obfuscated releases, assert the jar carries the names Fabric loads it through.
python3 mappings/check-remap.py 1.21.11 target/jals/remap/intricarpet-2.0.7.jar
```

`jals build` shells out to `javac`, and *which* `javac` is the ordinary system resolution — `$JAVAC`,
then `$JAVA_HOME/bin`, then `PATH`. The JDK has to be at least the release the selection compiles at
(`--release 16` for 1.17.1 through `--release 25` for 26.x, derived in `build.rhai`), and may be
newer: one JDK 25 builds all fifteen. CI installs one per era instead — 17, 21 or 25, chosen by the
cell — so a release is compiled by a JDK no newer than it has to be. Three rather than four, because
`--release 16` needs a JDK 16 no more than it needs a JDK 25, and 17 keeps CI off an end-of-life
Temurin. `$JAVAC` wins over everything else, so overriding the JDK for one build is
`JAVAC=/path/to/jdk-17/bin/javac jals build --features 1.17.1`.

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
| Loom remapping Carpet to the project's namespace  | `[dependencies] remap` over `mappings/intermediary-*.tiny`              |
| `remapJar`, the intermediary-named output         | `[build] remap`, the same files read the other way round                |
| `processResources { expand … }`                   | `templates/*.json` rendered by `build.rhai`                             |
| `JavaCompile { options … }`, `sourceCompatibility`| `build.add_javac_arg` in `build.rhai`                                   |
| `buildAndGather`, the matrix workflows            | one `--features` matrix in `.github/workflows/ci.yml`                   |

### Three namespaces, one mapping file per release

26.1 onward ships deobfuscated: the game jar, Carpet, the mod and the runtime all speak the same
names, which is why the old Gradle build applied plain `fabric-loom` there and `fabric-loom-remap`
everywhere else. Nothing below applies to those two releases — they declare no mapping set, and
both remap steps are no-ops.

For 1.17.1 through 1.21.11 three namespaces meet, and the build has to reconcile them twice.

**Going in.** The mod compiles against Mojang-named game classes, while the Carpet release jar is
*intermediary*-named. As published the two cannot share a classpath: `javac` stops at the first
Carpet API whose signature mentions a Minecraft type. `[dependencies] remap` deobfuscates the
Carpet jar before it reaches the classpath.

**Coming out.** Fabric loads a mod through intermediary names, so the packaged classes cannot stay
Mojang-named — that is precisely the jar that throws `NoClassDefFoundError:
net/minecraft/commands/Commands` on world load. `[build] remap` rewrites them.

Those are one table read in two directions, so they are one `[[mappings.intermediary]]` alternative
per release rather than two. That is what the tiny v2 format buys: a tiny file names its namespaces
in a header, so `format = { type = "tiny-v2", from = "intermediary", to = "mojang" }` states which
renaming is meant, and each step reads it the way it needs. A ProGuard-style file could not — it
names one pair implicitly and is read in whichever direction the step hardcodes.

The files are generated rather than written, by `mappings/regenerate.py`: Fabric's intermediary
mappings for the release composed with the official mappings Mojang publishes for it. Neither half
is the whole game — the script's docstring says what it keeps — and neither omission is silent. A
missing Carpet type stops `javac` on the `class_NNNN` it could not resolve; a missing name of the
mod's own is what `mappings/check-remap.py` fails CI on, because a remapper leaves what it cannot
rename and would otherwise ship a jar that builds and dies at the first reference. Adding a release
means declaring its Carpet jar with `remap = "intermediary"`, adding its `[[mappings.intermediary]]`
alternative and re-running the script; it reads everything else out of `jals.toml` and Mojang's
version manifest.

### What is not there yet

**Mixin's own strings.** `@Inject(method = "checkFallDamage")`, `@At(target = "L…;fallOn(…)V")`,
`@Shadow`, `@Invoker("forEachBlockTickingChunk")` — every one of those names a Minecraft member as
a `String` or as a Java declaration, and none of them is remapped. Loom answered this with a
*refmap*, a side table its annotation processor generated; jals produces neither the refmap nor the
rewrite, so on the thirteen obfuscated releases Mixin cannot resolve its targets and the mod fails
at apply time even though every reference in its bytecode is correct. `mappings/check-remap.py`
skips annotations for exactly this reason: the known gap would drown the signal it is there to
give. **The 26.x jars are the loadable ones today.**

**The remap hierarchy.** `[build] remap` finds a member on a supertype only if the type declaring
it is in the class index jals builds from the compile classpath, and the game jar arrives there as
a build-task artifact rather than as a declared dependency. jals [#251] closes that; without it
`getUUID` and the like come back out of the remap still spelled the way the source spells them —
which the check above catches, so this is a red build rather than a broken jar.

[#251]: https://github.com/topi-banana/jals/pull/251

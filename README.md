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

# And that the refmaps still say what the source does — no network, no toolchain, all thirteen.
python3 mappings/regenerate.py --check
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

`jals lint` is the same shape, and now for the same reason: there is no `jalslint.toml` either, so
every rule runs at its built-in severity with none turned down. `jals lint` exits non-zero on any
finding that is not a hint, so a `warn` rule fails CI exactly as an `error` one does. Two
consequences are worth knowing before running it: the linter is offline and has no classpath, so
`cannot-resolve` reports names it cannot see rather than names this source got wrong; and a Mixin
injector's signature is fixed by the framework, so `unused-local` flags parameters the method is not
free to drop. Both are real findings of rules that are on, not exceptions the config hides.

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
| The Mixin annotation processor's refmap           | `mappings/refmap-*.json`, derived from those same files                 |
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

The other half of the reobfuscation — a member declared on a supertype, where `[build] remap` finds
it only if the declaring type is in the class index jals builds from the compile classpath, and the
game jar arrives there as a build-task artifact rather than as a declared dependency — was jals
[#251], and it is on `main`. It is named here because it is the reason this repository's
`JALS_VERSION` cannot go below it: an older jals returns `getUUID` and its like still spelled the way
the source spells them, which `mappings/check-remap.py` turns into a red build rather than a broken
jar.

[#251]: https://github.com/topi-banana/jals/pull/251

### Mixin's own strings

Rewriting the bytecode is most of what a mixin needs. A `@Mixin(Entity.class)` target is a `Type` in
the annotation, every call and field access is a constant-pool reference, and `[build] remap` turns
all of it `class_1297`-shaped along with the rest of the jar. Two things in a mixin are not
references, and each needs its own answer.

**Selectors, read at apply time.** `@Inject(method = "checkFallDamage")`, `@At(target =
"L…;fallOn(…)V")`, `@Invoker("forEachBlockTickingChunk")` — annotation *elements*, which is to say
`String`s, which a remapper has no business rewriting. Mixin resolves them when it applies the mixin,
against a game that has never heard of `checkFallDamage`. Its own answer is a **refmap**: a side
table from the string as written to the string as it should be read. `mappings/regenerate.py`
generates one per release beside the mapping set it derives it from, and `build.rhai` names it in
`intricarpet.mixins.json` on the thirteen obfuscated releases — and leaves the key out on 26.x, where
the source's names already are the runtime's.

Loom got its refmap from the Mixin annotation processor. This build does not, and could not: the
processor on the classpath here is SpongePowered's own, which reads SRG and TSRG and has never heard
of a tiny file. Loom's tiny support was `fabric-mixin-compile-extensions`, a Loom artifact. Deriving
the table from the mapping set is both smaller and checkable, which is what `--check` below is.

**`@Shadow`.** Not read out of the annotation at all. `MixinPreProcessorStandard` looks the member up
by the name the mixin class *declares* and never consults a refmap for it, so the name has to be
right in the bytecode — which makes this a mapping-set entry rather than a refmap one. The mixin
class gets an identity class line and a copy of its target's mapping for the member it shadows, and
`[build] remap` then renames the declaration and every use of it exactly as it renames a Minecraft
member. `ChunkMapMixin`'s `playerMap` comes out `field_18241`, which is what Loom's remapper did to
Carpet's own shadows.

`mappings/check-remap.py` sees neither half: it skips annotations whole, and a declaration's own name
is not a reference for it to check. The cover for both is

```sh
python3 mappings/regenerate.py --check
```

which re-derives every refmap from the committed mapping sets and the source tree and fails on a tree
that has moved on from them. It needs no network, no JDK and no jals, and CI runs it as its own job.

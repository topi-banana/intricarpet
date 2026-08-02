## IntriCarpet

[![License](https://img.shields.io/github/license/Fallen-Breath/fabric-mod-template.svg)](http://www.gnu.org/licenses/lgpl-3.0.html)

This mod uses Fallen's fabric mod template.

This is a carpet extension that adds mainly stuff useful for TNT tech development.

## Building

The build is [jals](https://github.com/topi-banana/jals): `jals.toml` is the manifest, `build.rhai`
is the build definition, and one source tree targets every supported Minecraft release through build
features — `#[cfg(feature = "mc-ge-1.18.2")]` where the source preprocessor used to have
`//#if MC >= 11800`.

```sh
cargo install --locked --git https://github.com/topi-banana/jals jals-cli   # once
scripts/fetch-libs.sh 26.2                                                  # carpet + the loader
jals package --no-default-features --features 26.2                          # → target/intricarpet.jar
```

Releases before 26.1 lower, lint and expand but do not compile yet — carpet is published in Fabric's
intermediary namespace there and nothing here can remap it. See [`docs/jals.md`](docs/jals.md).

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

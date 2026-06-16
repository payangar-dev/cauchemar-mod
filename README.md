# Cauchemar

A NeoForge mod for Minecraft 1.21.1.

## Environment

| Component   | Version    |
|-------------|------------|
| Minecraft   | 1.21.1     |
| NeoForge    | 21.1.233   |
| Java        | 21         |
| Toolchain   | ModDevGradle 2.0.141 |
| Mappings    | Mojmap + Parchment 1.21.1 (2024.11.17) |

## Development

```bash
# Build the mod jar (output in build/libs/)
./gradlew build

# Launch the client in a dev environment
./gradlew runClient

# Launch a dedicated server
./gradlew runServer

# Run data generators
./gradlew runData
```

The built jar is written to `build/libs/cauchemar-<version>.jar`.

## Project layout

```
src/main/java/com/payangar/cauchemar/
  Cauchemar.java          Main @Mod entry point, wires registries and config
  CauchemarClient.java    Client-only entry point
  Config.java             NeoForge ModConfigSpec
  registry/
    ModBlocks.java        DeferredRegister for blocks
    ModItems.java         DeferredRegister for items
src/main/resources/
  assets/cauchemar/lang/  Translations
src/main/templates/
  META-INF/neoforge.mods.toml   Mod metadata (tokens expanded at build time)
```

## Versioning

One Git branch per Minecraft version. The default branch `1.21.1` targets Minecraft 1.21.1.

## License

All Rights Reserved.

## Author

Payangar

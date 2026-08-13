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
| GeckoLib    | 4.8.4      |

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

## Design

What the mod is and where it is going lives in [DESIGN.md](DESIGN.md), including the project layout
and the technical conventions.

## Versioning

One Git branch per Minecraft version. The default branch `1.21.1` targets Minecraft 1.21.1.

## License

All Rights Reserved.

## Author

Payangar

# CurseForge / Modrinth upload checklist

## Build the release jar

```bash
./gradlew clean build
```

Upload only:

```text
build/libs/party-pulse-1.0.0.jar
```

Do **not** upload `-sources.jar` or `-dev.jar`.

## CurseForge project settings

- **Name:** Party Pulse
- **Game:** Minecraft
- **Loader:** Fabric
- **Game versions:** 1.20.1
- **License:** CC0-1.0
- **Categories:** HUD, Map and Information, Utility
- **Environment:** Client & Server (both needed for shared party/combat data)

### Dependencies (relations)

| Mod | Relation |
| --- | --- |
| Fabric API | Required |
| Spell Engine | Optional |
| Open Parties and Claims | Optional |
| Trinkets | Optional |

### Page content

- Short summary from `README.md`
- Screenshots: party frames in-game + `/pulse menu` settings
- Link GitHub source / issues once the repo exists

## GitHub

1. Create an empty public repo named `party-pulse` (or update `contact.sources` in `fabric.mod.json` to match).
2. Confirm `reference-mods/` is **not** staged (it is gitignored).
3. Push source, then paste the repo URL on CurseForge.

Suggested first commit message:

```text
Initial public release of Party Pulse for Fabric 1.20.1
```

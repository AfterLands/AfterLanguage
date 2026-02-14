# AfterLanguage

AfterLanguage is an enterprise i18n plugin for the AfterLands Minecraft ecosystem (Spigot 1.8.8, Java 21).

## Current Version

- Plugin: `1.5.2`
- Build target: `Java 21 --enable-preview`
- Hard dependency: `AfterCore` (built against `1.5.6`, recommended runtime `1.5.2+`)

## Core Capabilities

- Per-player language preference with async persistence
- Three-tier translation cache (L1 hot, L2 registry, L3 compiled templates)
- Hot-reload for namespace/all translations
- Dynamic translation API (CRUD + plural forms)
- Crowdin bidirectional sync (upload/download/sync/status/cleanup)
- ProtocolLib client locale detection (optional)
- PlaceholderAPI expansion and message placeholder processing (optional)
- Redis sync broadcast support (optional)

## Commands

### Player (`/lang`)

- `/lang`
- `/lang gui`
- `/lang set <language>`
- `/lang list`
- `/lang info`

### Admin (`/afterlang` or `/alang`)

- `/afterlang`
- `/afterlang reload [namespace]`
- `/afterlang stats`
- `/afterlang cache`
- `/afterlang dynamic create <namespace> <key> <language> <text>`
- `/afterlang dynamic update <namespace> <key> <language> <text>`
- `/afterlang dynamic delete <namespace> <key> <language>`
- `/afterlang dynamic list <namespace> [language]`
- `/afterlang dynamic reload <namespace>`
- `/afterlang export <namespace> [language] [outputDir]`
- `/afterlang import <file> <targetFile>`
- `/afterlang backup create <namespace>`
- `/afterlang backup list [namespace]`
- `/afterlang backup restore <backupId> <namespace>`
- `/afterlang backup delete <backupId>`
- `/afterlang crowdin sync [namespace]`
- `/afterlang crowdin upload [namespace]`
- `/afterlang crowdin download [namespace]`
- `/afterlang crowdin uploadtranslation <language> <namespace>`
- `/afterlang crowdin status`
- `/afterlang crowdin test`
- `/afterlang crowdin cleanup [--confirm]`

## Development Integration (AfterCore)

```java
MessageService messages = AfterCore.get().messages();

messages.send(player,
    MessageKey.of("myplugin", "welcome"),
    Placeholder.of("player", player.getName()));

String title = messages.getOrDefault(player, MessageKey.of("myplugin", "gui.main.title"), "Default GUI Title");
```

Register plugin namespace using AfterCore `MessageService` provider:

```java
messages.registerNamespace(this, "myplugin");
```

Expected source files in your plugin data folder:

- `messages.yml` -> extracted to `languages/<lang>/<namespace>/messages.yml`
- `inventories.yml` -> extracted to `languages/<lang>/<namespace>/gui.yml`

## Build

```bash
mvn clean package
```

Output:

- `target/AfterLanguage-1.5.2.jar`

## Documentation

- `docs/STATUS.md` - authoritative runtime status and risks
- `docs/ARCHITECTURE.md` - layers, flows, and boundaries
- `docs/TESTING.md` - manual validation checklist
- `docs/CROWDIN_TESTING.md` - Crowdin-specific validation plan
- `docs/wiki/` - GitHub wiki pages for operators and developers

## Performance Note

AfterLanguage is optimized for high concurrency.

Current status:

- Core path is cache-centric and mostly O(1) lookup.
- I/O paths are async (DB/filesystem/HTTP/Redis).
- Main-thread sensitive flows were hardened in `1.5.2`.

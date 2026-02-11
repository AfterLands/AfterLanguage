# AfterLanguage

Enterprise-grade i18n (internationalization) plugin for the AfterLands Minecraft ecosystem.

## 🎯 Features

### Core i18n System
- **Provider Pattern Integration**: Seamlessly integrates with AfterCore's MessageService
- **Three-Tier Caching**: L1 hot cache, L2 in-memory registry, L3 template cache
- **Hot-Reload**: Reload translations without server restart
- **Per-Player Languages**: Each player sees content in their preferred language
- **Fallback Chain**: player language → default language → key literal
- **Performance**: < 0.01ms L1 cache hits, 20 TPS @ 500+ CCU

### Advanced Features (v1.2.0+)
- **ICU Pluralization**: Full plural forms support (ZERO, ONE, TWO, FEW, MANY, OTHER)
- **Dynamic Content API**: Runtime translation management with CRUD operations
- **Export/Import**: YAML export/import with plural forms preservation
- **Backup System**: Automatic timestamped backups with rotation

### Crowdin Integration (v1.3.0) 🆕
- **Bidirectional Sync**: Automatic sync between local files and Crowdin
- **Webhook Support**: Real-time updates via Crowdin webhooks
- **Auto-Sync Scheduler**: Configurable periodic sync
- **Conflict Resolution**: Smart merge strategies (crowdin-wins/local-wins/manual)
- **Redis Multi-Server**: Sync events propagated across server network

### Integrations
- **Config Scanner**: Automatically extract translatable keys from YAML configs
- **ProtocolLib**: Auto-detect client locale on join
- **PlaceholderAPI**: `%afterlang_*%` placeholders for legacy plugins
- **MySQL Persistence**: Player language preferences and dynamic translations
- **Redis**: Multi-server sync support
- **Observability**: Full metrics integration with AfterCore MetricsService

## 📝 Commands

### Player Commands (`/lang`)
- `/lang` - Open GUI language selector
- `/lang set <language>` - Change your language
- `/lang list` - List available languages
- `/lang info` - Show current language info

### Admin Commands (`/afterlang`)

**Basic:**
- `/afterlang` - Show plugin statistics
- `/afterlang reload [namespace]` - Hot-reload translations
- `/afterlang stats` - Detailed statistics
- `/afterlang cache` - Cache hit/miss rates

**Dynamic Content:**
- `/afterlang dynamic create <namespace> <key> <language> <text>` - Create translation
- `/afterlang dynamic delete <namespace> <key> <language>` - Delete translation
- `/afterlang dynamic list <namespace> [language]` - List translations
- `/afterlang dynamic reload <namespace>` - Reload namespace

**Export/Import:**
- `/afterlang export <namespace> [language] [outputDir]` - Export to YAML
- `/afterlang import <namespace> <language> <file> [overwrite]` - Import from YAML

**Backup:**
- `/afterlang backup create <namespace>` - Create backup
- `/afterlang backup list [namespace]` - List backups
- `/afterlang backup restore <backupId> <namespace>` - Restore backup
- `/afterlang backup delete <backupId>` - Delete backup

**Crowdin (v1.3.0):** 🆕
- `/afterlang crowdin sync [namespace]` - Full bidirectional sync
- `/afterlang crowdin upload [namespace]` - Upload to Crowdin
- `/afterlang crowdin download [namespace]` - Download from Crowdin
- `/afterlang crowdin status` - Show integration status
- `/afterlang crowdin test` - Test API connection

## 👨‍💻 For Developers

### Using AfterLanguage via AfterCore

```java
MessageService messages = AfterCore.get().messages();

// Simple message
messages.send(player,
    MessageKey.of("myplugin", "welcome"),
    Placeholder.of("player", player.getName())
);

// With pluralization (v1.2.0+)
messages.send(player,
    MessageKey.of("myplugin", "items.count"),
    Placeholder.of("count", itemCount)
);

// Bulk messages
messages.sendBulk(player,
    MessageKey.of("myplugin", "line1"),
    MessageKey.of("myplugin", "line2")
);

// Broadcast to all players (in their language)
messages.broadcast(MessageKey.of("myplugin", "server.restart"));
```

### Translation Files

Create files in `plugins/AfterLanguage/languages/<lang>/<namespace>/`:

```yaml
# pt_br/myplugin/messages.yml
welcome: "&aWelcome {player}!"
goodbye: "&cGoodbye {player}!"

# Pluralization (v1.2.0+)
items:
  count:
    text: "{count} items"
    plural:
      one: "{count} item"
      other: "{count} items"
```

### In Inventory YAMLs

AfterCore's InventoryService supports `{lang:...}` pattern:

```yaml
items:
  welcome:
    name: "{lang:myplugin:items.welcome.name}"
    lore:
      - "{lang:myplugin:items.welcome.lore}"  # Multi-line support
```

### Dynamic Content API (v1.2.0+)

Runtime translation management:

```java
DynamicContentAPI api = PluginRegistry.get().getDynamicContentAPI();

// Create translation
api.createTranslation("myplugin", "dynamic.key", "en_us", "Dynamic text")
   .thenAccept(translation -> {
       plugin.getLogger().info("Translation created!");
   });

// Update translation
api.updateTranslation(updatedTranslation).join();

// Delete translation
api.deleteTranslation("myplugin", "dynamic.key", "en_us").join();

// Get all translations
List<Translation> all = api.getAllFromNamespace("myplugin").join();
```

### Crowdin API (v1.3.0) 🆕

Programmatic Crowdin sync:

```java
CrowdinAPI crowdin = PluginRegistry.get().getCrowdinAPI();

// Full sync
crowdin.sync(Optional.of("myplugin"))
       .thenAccept(result -> {
           logger.info("Uploaded: " + result.uploadedStrings());
           logger.info("Downloaded: " + result.downloadedStrings());
           logger.info("Conflicts: " + result.conflicts());
       });

// Upload only
crowdin.uploadTranslations("myplugin").join();

// Download only
crowdin.downloadTranslations("myplugin").join();
```

## 🔧 Configuration

### Basic Setup (`config.yml`)

```yaml
default-language: pt_br
enabled-languages:
  - pt_br
  - en_us
  - es_es

detection:
  auto-detect: true
  delay-ticks: 40

cache:
  l1:
    max-size: 10000
    ttl-minutes: 30
  l3:
    max-size: 5000
    ttl-minutes: 60

backup:
  enabled: true
  max-backups: 10
```

### Crowdin Setup (`crowdin.yml` or `config.yml`)

```yaml
crowdin:
  enabled: true
  project-id: "${CROWDIN_PROJECT_ID}"
  api-token: "${CROWDIN_API_TOKEN}"
  source-language: pt-BR

  auto-sync-interval-minutes: 30

  webhook:
    enabled: true
    port: 8432
    secret: "${CROWDIN_WEBHOOK_SECRET}"

  conflict-resolution: crowdin-wins  # crowdin-wins | local-wins | manual
  backup-before-sync: true
  approved-only: true
```

## 🏗️ Building

```bash
mvn clean package
# Output: target/AfterLanguage-1.3.0.jar
```

Auto-copies to `C:/Users/icega/OneDrive/Área de Trabalho/AfterCompiled/`

## 📊 Status

✅ **v1.3.0** - Crowdin Integration Complete (2026-02-07)

**Progress:**
- ✅ Core MVP (v1.0.0)
- ✅ Essential Integrations (v1.1.0)
- ✅ Dynamic Content & Tooling (v1.2.0)
- ✅ Crowdin Integration (v1.3.0)
- ⏸️ Translation Editor GUI (pending v1.4.0)

**What's Working:**
- ✅ Per-player i18n with hot-reload
- ✅ Three-tier caching (< 1ms resolution)
- ✅ ICU pluralization (6 categories)
- ✅ Dynamic Content API (15+ methods)
- ✅ Export/Import/Backup system
- ✅ Crowdin bidirectional sync
- ✅ Webhook server + auto-sync
- ✅ Redis multi-server sync
- ✅ ProtocolLib + PlaceholderAPI integration

**Pending:**
- ⏳ Crowdin integration tests (requires real project)
- ⏸️ Translation Editor GUI (11% - YAMLs ready)

## 📚 Documentation

- [Status](docs/STATUS.md) - Current project state (single source of truth)
- [Architecture](docs/ARCHITECTURE.md) - Technical architecture reference
- [Spec](docs/SPEC.md) - Original technical specification
- [Roadmap](docs/ROADMAP.md) - Future plans (v1.4.0+)
- [Testing Guide](docs/TESTING.md) - 25 manual test scenarios
- [Changelog](CHANGELOG.md) - Version history
- [Crowdin Setup](docs/guides/CROWDIN_SETUP.md) - Crowdin integration guide
- [Migration Guide](docs/guides/MIGRATION_v1.2.0_to_v1.3.0.md) - Version migration
- [CLAUDE.md](CLAUDE.md) - Development guidance for AI assistants

## 🔗 Dependencies

**Required:**
- AfterCore 1.5.2+
- Java 21
- MySQL 5.7+
- Spigot/Paper 1.8.8+

**Optional:**
- ProtocolLib 5.3.0+ (client locale detection)
- PlaceholderAPI 2.11.6+ (legacy plugin compat)
- Redis 6.0+ (multi-server sync)
- Crowdin account (translation management)

## 📜 License

Proprietary - AfterLands Network

---

Built with ❤️ for the AfterLands ecosystem

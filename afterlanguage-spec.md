# AfterLanguage — Especificação Técnica Completa

> **Documento de referência para geração de `implementation_plan.md` e `roadmap.md`**
> Plugin de internacionalização (i18n) enterprise-grade para o ecossistema AfterLands.

---

## 1. Visão Geral do Projeto

### 1.1 Contexto do Ecossistema

| Aspecto | Valor |
|---------|-------|
| **Servidor** | Minecraft 1.8.8 (Paper fork) |
| **Java** | 21 |
| **Meta de Performance** | 20 TPS constante @ 500+ CCU |
| **Biblioteca Base** | AfterCore (serviços compartilhados) |
| **Arquitetura** | Hexagonal (Ports & Adapters) via AfterTemplate |
| **Database** | MySQL (via HikariCP, shaded no AfterCore) |
| **Cache** | Caffeine (shaded no AfterCore) |

### 1.2 Identidade do Plugin

- **Nome:** AfterLanguage
- **Tipo:** Plugin standalone (NÃO integrado ao AfterCore)
- **Dependência hard:** AfterCore
- **Dependências soft:** PlaceholderAPI, ProtocolLib
- **Idioma fonte:** PT-BR (source language para Crowdin)
- **Padrão de código:** Classes, JavaDocs e código em Inglês; documentação e configs em PT-BR

### 1.3 Abordagem Híbrida (Provider Pattern)

AfterCore expõe a interface `MessageService` em sua API pública. AfterLanguage implementa e registra-se como provider. Plugins dependem **apenas** da interface do AfterCore — ganham features completas quando AfterLanguage está presente, mantendo fallback básico caso contrário.

```
┌─────────────────────────────────────────────────────────────┐
│                     AfterCore (API)                          │
│  ┌─────────────────────────────────────────────────────┐    │
│  │  MessageService (interface)                          │    │
│  │    send(), get(), getComponent()                     │    │
│  │    registerNamespace(), registerSchema()              │    │
│  │    setTranslation(), getStatus()                     │    │
│  └─────────────────────────────────────────────────────┘    │
└───────────────────────────┬─────────────────────────────────┘
                            │ implements
┌───────────────────────────▼─────────────────────────────────┐
│                   AfterLanguage (Provider)                    │
│  Translation loading, cache, Crowdin sync, GUI, scanning     │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│               Outros Plugins (Consumers)                     │
│  AfterQuests, AfterJournal, AfterGuilds, etc.               │
│  Dependem APENAS de AfterCore → MessageService              │
└─────────────────────────────────────────────────────────────┘
```

---

## 2. Arquitetura Hexagonal

Seguindo o AfterTemplate, o plugin deve ter separação estrita de camadas:

```
afterlanguage/
├── api/              # Interfaces, DTOs, exceções públicas
│   ├── model/        # MessageKey, Placeholder, Translation, TranslationStatus, etc.
│   └── service/      # Interfaces de serviço (se alguma além do MessageService)
├── core/             # Lógica de negócio pura (sem dependências de framework)
│   ├── cache/        # TranslationCacheManager
│   ├── scanner/      # ConfigScanner, TranslationSchema
│   ├── resolver/     # TranslationResolver (fallback chain)
│   └── template/     # CompiledMessage, TemplateEngine (placeholders)
├── infra/            # Implementações externas
│   ├── persistence/  # MySQL repositories
│   ├── crowdin/      # CrowdinSyncService
│   ├── redis/        # RedisSyncService
│   ├── papi/         # PlaceholderAPI expansion
│   ├── protocol/     # ProtocolLib locale detection
│   └── config/       # YamlConfigLoader
└── bootstrap/        # Wiring, plugin lifecycle, commands, listeners
    ├── AfterLanguagePlugin.java
    ├── command/
    └── listener/
```

### Regras de Dependência

```
api ← core ← infra ← bootstrap
│           │
│           └── core NÃO importa infra, bootstrap, ou Bukkit API
│
└── api NÃO importa nenhuma outra camada
```

---

## 3. Data Model

### 3.1 Modelos Públicos (em AfterCore `api/`)

```java
/**
 * Type-safe translation key with namespace.
 * Namespace isolates keys per plugin.
 */
public record MessageKey(String namespace, String path) {
    public static MessageKey of(String namespace, String path);
    public static MessageKey of(String path); // Uses "default" namespace
    public String fullKey(); // "namespace:path"
}

/**
 * Placeholder for message templates.
 * Uses {key} syntax (NOT %key% to avoid PAPI conflicts).
 */
public record Placeholder(String key, Object value) {
    public static Placeholder of(String key, Object value);
    public String pattern(); // "{key}"
}
```

### 3.2 Modelos Internos (em AfterLanguage)

```java
/**
 * A single translation entry, possibly with pluralization
 * and pre-parsed Adventure component.
 */
public record Translation(
    String key,
    String namespace,
    String language,
    String rawText,
    @Nullable String pluralText,       // .one / .other suffix
    @Nullable Component component,      // Pre-parsed Adventure (1.16+)
    Instant updatedAt
) {}

/**
 * Status of a translation across all languages.
 */
public record TranslationStatus(
    String namespace,
    String key,
    Map<String, TranslationState> states // lang -> state
) {}

public enum TranslationState {
    TRANSLATED,    // Existe e está atualizada
    PENDING,       // Sem tradução, usando fallback
    OUTDATED       // Source mudou, pode estar desatualizada
}

public record PendingTranslation(
    String namespace,
    String key,
    String sourceText,
    String language,
    TranslationState state,
    Instant createdAt
) {}
```

---

## 4. MessageService API

Interface pública exposta pelo AfterCore, implementada pelo AfterLanguage:

```java
public interface MessageService {

    // ══════════════════════════════════════════════
    // CORE MESSAGING
    // ══════════════════════════════════════════════

    /** Send translated message to player. */
    void send(Player player, MessageKey key, Placeholder... placeholders);

    /** Send with pluralization (selects .one or .other based on count). */
    void send(Player player, MessageKey key, int count, Placeholder... placeholders);

    /** Get translated string (resolved with player's language). */
    String get(Player player, MessageKey key, Placeholder... placeholders);

    /** Get with fallback if key doesn't exist (for config-scanner integration). */
    String getOrDefault(Player player, MessageKey key, String defaultValue, Placeholder... placeholders);

    /** Get as Adventure Component (pre-parsed, 1.16+ optimized). */
    Component getComponent(Player player, MessageKey key, Placeholder... placeholders);

    // ══════════════════════════════════════════════
    // BULK OPERATIONS
    // ══════════════════════════════════════════════

    /** Send multiple messages with shared placeholders. */
    void sendBatch(Player player, List<MessageKey> keys, Map<String, Object> sharedPlaceholders);

    /** Broadcast to all online players (each receives in their language). */
    void broadcast(MessageKey key, Placeholder... placeholders);

    /** Broadcast to players with specific permission. */
    void broadcast(MessageKey key, String permission, Placeholder... placeholders);

    // ══════════════════════════════════════════════
    // PLAYER LANGUAGE MANAGEMENT
    // ══════════════════════════════════════════════

    String getLanguage(UUID playerId);
    CompletableFuture<Void> setLanguage(UUID playerId, String language);
    List<String> getAvailableLanguages();
    String getDefaultLanguage();

    // ══════════════════════════════════════════════
    // NAMESPACE REGISTRATION (Static Content)
    // ══════════════════════════════════════════════

    /**
     * Register a translation namespace with default files.
     * AfterLanguage checks if translations exist in its languages/ dir.
     * If not, copies defaults from the plugin's folder.
     */
    void registerNamespace(String namespace, File defaultTranslationsFolder);

    /** Reload all translations for a namespace. */
    void reloadNamespace(String namespace);

    // ══════════════════════════════════════════════
    // CONFIG SCANNING (Extracting translatable content from configs)
    // ══════════════════════════════════════════════

    /**
     * Register a schema that declares which fields in a plugin's config
     * files contain translatable text. AfterLanguage will scan these files
     * and auto-generate translation entries.
     */
    void registerSchema(TranslationSchema schema);

    /**
     * Re-scan a previously registered schema.
     * Called after plugin reloads its configs (e.g., /afterjournal reload).
     * Detects new, changed, and removed translatable strings.
     */
    void rescan(String namespace);

    // ══════════════════════════════════════════════
    // DYNAMIC CONTENT (Programmatically created content)
    // ══════════════════════════════════════════════

    /**
     * Register dynamic content in the source language.
     * For content created via commands/API, NOT from config files.
     * Other languages show fallback until translated.
     */
    void registerDynamic(String namespace, String keyPrefix, Map<String, String> fields);

    /** Remove dynamic content from all languages. */
    void unregisterDynamic(String namespace, String keyPrefix);

    /** Update dynamic content (marks other languages as OUTDATED). */
    void updateDynamic(String namespace, String keyPrefix, Map<String, String> fields);

    /** Set translation for a specific language (admin manual translation). */
    void setTranslation(String namespace, String key, String language, String value);

    /** Get translation status for a key across all languages. */
    TranslationStatus getStatus(String namespace, String key);

    /** List all keys with pending translations for a namespace. */
    List<PendingTranslation> getPendingTranslations(String namespace);
}
```

---

## 5. Config Scanner — TranslationSchema

Sistema para extrair automaticamente strings traduzíveis de arquivos YAML de configuração de outros plugins.

### 5.1 Conceito

Plugins como AfterJournal têm configs (ex: `quests.yml`) onde dados de gameplay e texto visível ao jogador estão misturados no mesmo arquivo. O Config Scanner permite que o plugin declare quais campos contêm texto traduzível, e o AfterLanguage extrai automaticamente.

### 5.2 API do TranslationSchema

```java
public class TranslationSchema {

    public static Builder builder(String namespace);

    public static class Builder {
        /**
         * Declara quais arquivos escanear.
         * Suporta glob patterns: "quests/*.yml", "config.yml"
         * Caminho relativo ao dataFolder do plugin que registra.
         */
        Builder scanFile(String globPattern);

        /**
         * Declara um path no YAML que contém texto traduzível.
         * Suporta wildcards: "discoveries.*.name"
         * Múltiplos wildcards: "discoveries.*.stages.*.description"
         */
        Builder atPath(String yamlPath);

        /**
         * Para paths que apontam para listas de actions (rewards, etc.),
         * filtra apenas actions que contêm texto traduzível.
         * Deve ser chamado imediatamente após atPath().
         */
        Builder withActionFilter(String... actionTypes);

        TranslationSchema build();
    }
}
```

### 5.3 Exemplo de Uso: AfterJournal

O arquivo `quests.yml` do AfterJournal contém:

```yaml
discoveries:
  oinvasor:
    name: "&fO Invasor"                                    # ← traduzível
    description:
      - "&7Demetrius está precisando de ajuda."            # ← traduzível
    filter-category: ['not-started', 'side-quest']         # ← NÃO traduzível
    inventory_items:
      discovery_blocked:
        name: "&fMissão Bloqueada"                         # ← traduzível
        lore:
          - "&7Você ainda não desbloqueou esta missão."    # ← traduzível
    stages:
      obj-1:
        name: "&eO Invasor"                                # ← traduzível
        description:
          - "&7Encontre o 'invasor' no convés."            # ← traduzível
        click_actions:
          - "console_command: trackar %player%"            # ← NÃO traduzível
        rewards:
          on_discovery:
            - "console_command: npcutils show 148 %player%"  # ← NÃO traduzível
            - "playsound: LEVEL_UP;1;0.8"                    # ← NÃO traduzível
            - "title: 20;70;15;&7&lMissão Iniciada;&fName"   # ← traduzível (parcial)
            - "message: &7    Objetivo:"                     # ← traduzível
            - "centered_message: &e[Missão Secundária]"      # ← traduzível
```

Registro do schema:

```java
// AfterJournal.onEnable()
TranslationSchema schema = TranslationSchema.builder("afterjournal")
    .scanFile("quests/*.yml")

    // Discovery level
    .atPath("discoveries.*.name")
    .atPath("discoveries.*.description")
    .atPath("discoveries.*.inventory_items.discovery_blocked.name")
    .atPath("discoveries.*.inventory_items.discovery_blocked.lore")

    // Stage level
    .atPath("discoveries.*.stages.*.name")
    .atPath("discoveries.*.stages.*.description")
    .atPath("discoveries.*.stages.*.inventory_items.discovery_unlocked.name")
    .atPath("discoveries.*.stages.*.inventory_items.discovery_unlocked.lore")
    .atPath("discoveries.*.stages.*.inventory_items.discovery_blocked.name")
    .atPath("discoveries.*.stages.*.inventory_items.discovery_blocked.lore")

    // Rewards (filtro: só actions com texto visível)
    .atPath("discoveries.*.stages.*.rewards.on_discovery")
        .withActionFilter("title", "message", "centered_message")

    // Category-level defaults
    .atPath("inventory_items.discovery_blocked.name")
    .atPath("inventory_items.discovery_blocked.lore")

    .build();

languageService.registerSchema(schema);
```

### 5.4 Output do Scanner

O scanner lê o `quests.yml`, percorre os paths declarados, e gera automaticamente:

```yaml
# AUTO-GENERATED: languages/pt_br/afterjournal/quests_tutorial.yml
# Source: plugins/AfterJournal/quests/tutorial.yml
# Generated at: 2026-01-31T18:00:00Z

# ═══ Category defaults ═══
inventory_items.discovery_blocked.name: "&fMissão Bloqueada"
inventory_items.discovery_blocked.lore: "&7Você ainda não desbloqueou esta missão."

# ═══ Discovery: oinvasor ═══
discoveries.oinvasor.name: "&fO Invasor"
discoveries.oinvasor.description: "&7Demetrius está precisando de ajuda, fale com ele."
discoveries.oinvasor.inventory_items.discovery_blocked.name: "&fMissão Bloqueada"
discoveries.oinvasor.inventory_items.discovery_blocked.lore: "&7Você ainda não desbloqueou esta missão."

# ═══ Stage: oinvasor.obj-1 ═══
discoveries.oinvasor.stages.obj-1.name: "&eO Invasor"
discoveries.oinvasor.stages.obj-1.description: "&7Encontre o susposto 'invasor' no convés."
discoveries.oinvasor.stages.obj-1.rewards.title_1: "&7&lMissão Iniciada;&fO Invasor"
discoveries.oinvasor.stages.obj-1.rewards.message_1: "&e[Missão Secundária]"
discoveries.oinvasor.stages.obj-1.rewards.message_2: "&fO Invasor"
discoveries.oinvasor.stages.obj-1.rewards.message_3: "&7    Objetivo:"
discoveries.oinvasor.stages.obj-1.rewards.message_4: "&7    ▪ &fEncontre o susposto 'invasor' no convés."

# ═══ Stage: oinvasor.obj-2 ═══
discoveries.oinvasor.stages.obj-2.name: "&eO Invasor"
discoveries.oinvasor.stages.obj-2.description: "&7Decida-se o que fazer com o 'invasor'."
# ... etc para todos os stages e discoveries
```

### 5.5 Fluxo de Rescan

```
Admin edita quests.yml, adiciona "fail-3"
         │
         ▼
Admin executa: /afterjournal reload
         │
         ▼
AfterJournal recarrega config internamente
AfterJournal chama: languageService.rescan("afterjournal")
         │
         ▼
AfterLanguage re-escaneia com o schema registrado
         │
         ├── Detecta NOVAS keys (fail-3.name, fail-3.description, etc.)
         ├── Detecta keys REMOVIDAS (se algo foi deletado)
         ├── Detecta keys ALTERADAS (se texto source mudou)
         │
         ▼
   ┌─────────────────────────────────────────────────────────┐
   │  Para NOVAS keys:                                        │
   │   1. Adiciona ao languages/pt_br/ (source language)      │
   │   2. Marca PENDING para en_us, es_es, etc.               │
   │   3. Enfileira para Crowdin sync                         │
   │   4. Notifica admins online                              │
   │                                                          │
   │  Para keys ALTERADAS:                                    │
   │   1. Atualiza pt_br com novo texto                       │
   │   2. Marca OUTDATED para outras línguas (hash changed)   │
   │   3. Re-sync Crowdin                                     │
   │                                                          │
   │  Para keys REMOVIDAS:                                    │
   │   1. NÃO deleta imediatamente (marca como deprecated)    │
   │   2. Log de aviso para admin                             │
   └─────────────────────────────────────────────────────────┘
```

### 5.6 Resolução no Runtime

O plugin original continua lendo o YAML normalmente para dados de gameplay. Quando precisa exibir texto ao jogador, chama o MessageService:

```java
// AfterJournal, ao renderizar uma discovery
String name = messages.get(player,
    MessageKey.of("afterjournal", "discoveries." + discoveryId + ".name"));

// Para rewards com actions traduzíveis, o plugin resolve antes de executar:
void executeReward(Player player, String rewardLine, String discoveryId, String stageId) {
    if (rewardLine.startsWith("title:")) {
        String[] parts = parseTitle(rewardLine);
        String titleKey = "discoveries." + discoveryId + ".stages." + stageId + ".rewards.title_1";
        String title = messages.getOrDefault(player,
            MessageKey.of("afterjournal", titleKey), parts[3]);
        sendTitle(player, parts[0], parts[1], parts[2], title, subtitle);
    } else if (rewardLine.startsWith("message:") || rewardLine.startsWith("centered_message:")) {
        // Similar: tenta tradução, fallback para texto original
    } else {
        // console_command, playsound, firework → executa sem tradução
        executeRaw(rewardLine);
    }
}
```

---

## 6. Integração com GUIs (InventoryService)

### 6.1 Pattern `{lang:namespace:key}`

O InventoryService do AfterCore detecta o pattern `{lang:namespace:key}` em campos de título, nome e lore dos inventários. Resolve automaticamente via MessageService com base no idioma do jogador.

### 6.2 Ordem de Resolução

```
Player abre GUI
       │
       ▼
InventoryService lê inventories.yml
       │
       ▼
Detecta {lang:namespace:key} em title, name, lore
       │
       ▼
MessageService.get(player, key)  →  Tradução no idioma do jogador
       │                             Fallback: default-lang → key literal
       ▼
Resolve {placeholder} restantes (contexto do plugin)
       │
       ▼
Colorize (&codes, hex, MiniMessage)  →  Renderiza inventory
```

### 6.3 Tipos de Conteúdo em GUIs

| Tipo | Onde fica | Como traduzir |
|------|-----------|---------------|
| **Estático** (títulos, botões fixos) | `inventories.yml` com `{lang:...}` | InventoryService resolve automaticamente |
| **Dinâmico** (nome de quest, conquista) | Populado pelo plugin via código | Plugin chama `messages.get()` e passa como placeholder |

### 6.4 Exemplo: inventories.yml com Tradução

```yaml
# inventories.yml do AfterJournal (com AfterLanguage)
journal-menu:
  title: '{lang:afterjournal:gui.journal.title}'
  size: 54
  items:
    '4':
      material: BOOK
      name: '{lang:afterjournal:gui.journal.info_name}'
      lore:
        - '{lang:afterjournal:gui.journal.info_lore_1}'
        - '{lang:afterjournal:gui.journal.info_lore_2}'
    achievement-item:
      material: '{achievement_material}'
      name: '{achievement_name}'          # Vem traduzido do código
      lore: '{achievement_lore}'          # Vem traduzido do código (expande para lista)
      type: achievement-item
    '45':
      material: item:previous-page
    '53':
      material: item:next-page
    '0':
      material: item:filler
      duplicate: all
```

### 6.5 default-items com Tradução

- **Prioridade:** Se o plugin define `{lang:}` nos seus default-items, usa os do plugin.
- **Fallback:** Se o plugin não define, usa os do AfterCore (que também usam `{lang:}`).

```yaml
# default-items padrão do AfterCore (com AfterLanguage)
default-items:
  previous-page:
    material: "head: eyJ0ZXh0..."
    name: '{lang:aftercore:gui.pagination.previous}'
    lore:
      - '{lang:aftercore:gui.pagination.previous_lore}'
    actions:
      - 'sound: CLICK 1 1.5'
      - prev_page
  next-page:
    material: "head: eyJ0ZXh0..."
    name: '{lang:aftercore:gui.pagination.next}'
    lore:
      - '{lang:aftercore:gui.pagination.next_lore}'
    actions:
      - 'sound: CLICK 1 1.5'
      - next_page
  back:
    material: "head: eyJ0ZXh0..."
    name: '{lang:aftercore:gui.navigation.back}'
    lore:
      - '{lang:aftercore:gui.navigation.back_lore}'
  filler:
    material: STAINED_GLASS_PANE
    data: 15
    name: ' '
    type: filler
```

### 6.6 Lore Multilinha

Quando uma entrada de lore é uma única `{lang:...}`, o sistema suporta expansão para múltiplas linhas:

- **YAML list** no arquivo de tradução → cada item vira uma linha de lore
- **`\n` separator** em valores flat → splitado em linhas automaticamente
- Ambos formatos são suportados

```yaml
# Arquivo de tradução — Opção YAML list
gui.quest_item.lore:
  - "&7Progresso: &f{current}/{total}"
  - ""
  - "&aRecompensa: &f{reward}"

# Arquivo de tradução — Opção flat com \n
gui.quest_item.lore: "&7Progresso: &f{current}/{total}\n\n&aRecompensa: &f{reward}"
```

### 6.7 Validação de Título

Inventários 1.8.8 têm limite de 32 caracteres no título. O sistema deve **validar** (não truncar) — se o título traduzido exceder 32 chars, loggar warning e usar versão do idioma default.

---

## 7. Estrutura de Arquivos de Tradução

### 7.1 Diretório Centralizado

Todas as traduções ficam centralizadas dentro do AfterLanguage:

```
plugins/AfterLanguage/
├── config.yml
├── crowdin.yml
├── languages/
│   ├── pt_br/                          # Source language
│   │   ├── afterlanguage.yml           # Mensagens do próprio plugin
│   │   ├── aftercore.yml               # Mensagens do AfterCore
│   │   ├── afterjournal/               # Namespace com subpastas
│   │   │   ├── gui.yml                 # Strings de UI
│   │   │   ├── quests_tutorial.yml     # Auto-gerado pelo scanner
│   │   │   └── achievements/
│   │   │       ├── combat.yml
│   │   │       └── exploration.yml
│   │   └── afterguilds/
│   │       └── general.yml
│   ├── en_us/                          # Mirror da estrutura pt_br
│   │   ├── afterlanguage.yml
│   │   ├── aftercore.yml
│   │   └── afterjournal/
│   │       └── ...
│   └── es_es/
│       └── ...
└── cache/
    └── crowdin-state.json
```

### 7.2 Formato dos Arquivos de Tradução

**Flat keys** (não YAML aninhado), com suporte a:

```yaml
# ═══ Strings simples ═══
quest.started: "&aMissão iniciada: &f{quest_name}"
quest.completed: "&6Parabéns! Missão concluída: &f{quest_name}"

# ═══ Pluralização com .one / .other ═══
quest.items_collected.one: "&aVocê coletou &f1 &aitem!"
quest.items_collected.other: "&aVocê coletou &f{count} &aitens!"

# ═══ Multilinha como YAML list ═══
quest.tutorial:
  - "&7Bem-vindo ao sistema de missões!"
  - "&7Use &f/quests &7para ver suas missões."

# ═══ GUI elements ═══
gui.quests.title: "&6&lMissões"
gui.quest_item.lore:
  - "&7Progresso: &f{current}&7/&f{total}"
  - "&aRecompensa: &f{reward}"
```

### 7.3 Subpastas e Auto-Prefix

Subpastas dentro de um namespace geram prefix automático na key:

| Arquivo | Key no YAML | Key Resolvida |
|---------|-------------|---------------|
| `afterjournal/gui.yml` | `journal.title` | `afterjournal:gui.journal.title` |
| `afterjournal/achievements/combat.yml` | `first_kill.name` | `afterjournal:achievements.combat.first_kill.name` |

O Crowdin preserva a hierarquia via `preserve_hierarchy: true`.

### 7.4 Placeholder Syntax

`{placeholder}` — chaves simples. NÃO `%placeholder%` para evitar conflito com PlaceholderAPI.

---

## 8. Configuração

### 8.1 config.yml

```yaml
default-language: pt_br
enabled-languages: [pt_br, en_us, es_es]

language-names:
  pt_br: "Português (BR)"
  en_us: "English (US)"
  es_es: "Español (ES)"

detection:
  auto-detect: true
  delay-ticks: 60
  locale-mapping:
    en_gb: en_us
    es_ar: es_es
    pt_pt: pt_br

fallback:
  show-key-on-missing: true
  missing-format: "&c[Missing: {namespace}:{key}]"
  log-missing-keys: true

storage:
  table-name: afterlanguage_players
  use-aftercore-connection: true

cache:
  max-translations: 10000
  parsed-message-ttl-minutes: 5
  compiled-template-ttl-minutes: 60

crowdin:
  enabled: false
  config-file: crowdin.yml
  auto-sync-interval-minutes: 30
  webhook:
    enabled: false
    port: 8432
    secret: "change-me"
  hot-reload: true
  notify-admins: true
  approved-only: true

redis:
  enabled: false
  channel: afterlanguage:sync

placeholderapi:
  enabled: true

actions:
  first-join:
    - "[title] &6Bem-vindo!;&eEscolha seu idioma com /lang;10;40;20"
    - "[sound] LEVEL_UP 1.0 0.5"
  language-change:
    any:
      - "[sound] NOTE_PLING 1.0 1.2"
    pt_br:
      - "[message] &aIdioma definido para &fPortuguês&a!"
    en_us:
      - "[message] &aLanguage set to &fEnglish&a!"
    es_es:
      - "[message] &a¡Idioma configurado en &fEspañol&a!"

language-gui:
  title-key: "afterlanguage:gui.selector.title"
  size: 27
  languages:
    pt_br:
      slot: 11
      material: SKULL_ITEM
      skull-url: "textures.minecraft.net/texture/xxx"
      display-name: "&a&lPortuguês (BR)"
      lore:
        - "&7Clique para selecionar"
        - "&7Traduções: &f{translation_percent}%"
      glow-when-selected: true
    en_us:
      slot: 13
      material: SKULL_ITEM
      skull-url: "textures.minecraft.net/texture/yyy"
      display-name: "&a&lEnglish (US)"
      lore:
        - "&7Click to select"
        - "&7Translations: &f{translation_percent}%"
      glow-when-selected: true
    es_es:
      slot: 15
      material: SKULL_ITEM
      skull-url: "textures.minecraft.net/texture/zzz"
      display-name: "&a&lEspañol (ES)"
      lore:
        - "&7Haz clic para seleccionar"
        - "&7Traducciones: &f{translation_percent}%"
      glow-when-selected: true
  filler:
    enabled: true
    material: STAINED_GLASS_PANE
    data: 15
  info:
    slot: 4
    material: BOOK
    display-name-key: "afterlanguage:gui.selector.info_title"
    lore-key: "afterlanguage:gui.selector.info_lore"

performance:
  precompile-templates: true
  use-adventure-when-available: true

debug: false
```

### 8.2 crowdin.yml

```yaml
project-id: "${CROWDIN_PROJECT_ID}"
api-token: "${CROWDIN_API_TOKEN}"
source-language: pt-BR

files:
  source-pattern: "languages/pt_br/**/*.yml"
  translation-pattern: "languages/%locale%/**/%original_file_name%"

preserve-hierarchy: true

locale-mapping:
  "en": "en_us"
  "es-ES": "es_es"
  "fr": "fr_fr"
  "de": "de_de"
  "ja": "ja_jp"
  "zh-CN": "zh_cn"

sync-namespaces: []  # Empty = sync all
```

---

## 9. Database Schema

### 9.1 Player Preferences

```sql
CREATE TABLE IF NOT EXISTS afterlanguage_players (
    uuid          VARCHAR(36)  PRIMARY KEY,
    language      VARCHAR(10)  NOT NULL,
    auto_detected BOOLEAN      NOT NULL DEFAULT FALSE,
    first_join    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_language (language)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

Campo `auto_detected`: rastreia se idioma foi auto-detectado vs escolha manual. Permite re-detecção quando novos idiomas são adicionados.

### 9.2 Dynamic Translations

```sql
CREATE TABLE IF NOT EXISTS afterlanguage_dynamic (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    namespace    VARCHAR(64)  NOT NULL,
    key_path     VARCHAR(255) NOT NULL,
    language     VARCHAR(10)  NOT NULL,
    value        TEXT         NOT NULL,
    state        ENUM('TRANSLATED', 'PENDING', 'OUTDATED') NOT NULL DEFAULT 'TRANSLATED',
    source_hash  VARCHAR(32)  NULL,      -- MD5 do source text para detectar OUTDATED
    created_by   VARCHAR(36)  NULL,      -- UUID do admin que criou
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE INDEX idx_ns_key_lang (namespace, key_path, language),
    INDEX idx_state (state),
    INDEX idx_namespace (namespace)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

**Storage dinâmico:** Híbrido — MySQL como source of truth, com export periódico para YAML para backup e sync com Crowdin.

---

## 10. Cache Strategy

### 10.1 Three-Tier Caching

```java
// L1: Hot translations (most accessed, per-player resolved)
Cache<String, String> hotCache = Caffeine.newBuilder()
    .maximumSize(5000)
    .expireAfterAccess(30, TimeUnit.MINUTES)
    .recordStats()
    .build();
// Key format: "lang:namespace:key" → resolved text

// L2: Full registry (all loaded translations, in-memory)
Map<String, Map<String, Map<String, Translation>>> registry;
// Structure: language → namespace → key → Translation

// L3: Compiled templates (placeholder positions pre-calculated)
Cache<String, CompiledMessage> templateCache = Caffeine.newBuilder()
    .maximumSize(2000)
    .expireAfterWrite(1, TimeUnit.HOURS)
    .build();
```

### 10.2 Loading Strategy

**Lazy load com cache:**
- Traduções NÃO são pré-carregadas no join do player
- Carregadas sob demanda na primeira chamada `get()` para aquele namespace + idioma
- Após carregamento, ficam em memória (L2) indefinidamente até reload
- L1 (hot cache) evita lookups repetidos para mensagens frequentes

### 10.3 Performance Targets

| Operação | Target |
|----------|--------|
| `get()` com cache hit (L1) | < 0.01ms |
| `get()` com cache miss (L1 miss, L2 hit) | < 0.1ms |
| `send()` completo | < 0.2ms |
| Placeholder replacement | < 0.05ms |
| GUI translation (54 slots) | < 2ms |

---

## 11. Commands

### 11.1 Admin Commands (`/afterlang`)

```
/afterlang                                    → Info do plugin
/afterlang help                               → Lista de comandos
/afterlang set <player> <lang>                → Definir idioma do jogador
/afterlang get <player>                       → Mostrar idioma do jogador
/afterlang detect <player>                    → Forçar detecção de locale
/afterlang list                               → Listar idiomas disponíveis
/afterlang reload                             → Recarregar todas as traduções
/afterlang reload <namespace>                 → Recarregar namespace específico
/afterlang scan [namespace]                   → Forçar re-scan de configs
/afterlang sync                               → Forçar sync com Crowdin
/afterlang sync status                        → Status do último sync
/afterlang stats                              → Stats de cache e traduções carregadas
/afterlang debug <key>                        → Mostrar tradução em todos idiomas
/afterlang translate <namespace:key> <lang> <value> → Tradução manual
/afterlang editor [namespace]                 → GUI de traduções pendentes
/afterlang pending [namespace]                → Listar pendentes
/afterlang outdated [namespace]               → Listar desatualizadas
/afterlang export [namespace]                 → Exportar dinâmicas para YAML
```

**Permissões:** `afterlanguage.admin`, `afterlanguage.set`, `afterlanguage.sync`, `afterlanguage.reload`, `afterlanguage.translate`, `afterlanguage.editor`

### 11.2 Player Commands (`/lang`)

```
/lang                    → Abrir GUI selector
/lang <language>         → Definir idioma diretamente
/lang help               → Help básico
```

**Permissão:** `afterlanguage.use` (default: true)

---

## 12. Fluxos Críticos

### 12.1 Startup Sequence

1. Load `config.yml` e `crowdin.yml`
2. Conectar ao storage via AfterCore `SqlService`
3. Criar tabelas se não existirem (`afterlanguage_players`, `afterlanguage_dynamic`)
4. Carregar todos os arquivos de tradução para idiomas habilitados (registry L2)
5. Pre-compilar templates se `performance.precompile-templates: true`
6. Registrar próprio namespace ("afterlanguage")
7. Registrar PAPI expansion se disponível
8. Iniciar Crowdin auto-sync scheduler se habilitado
9. Registrar listeners (PlayerJoin, PlayerQuit)
10. Registrar comandos (`/afterlang`, `/lang`)

### 12.2 Player Join Flow

```
1. Player conecta
2. [Async] Carregar preferência do banco de dados
3. Se existe → Aplicar e cachear em memória
4. Se NÃO existe (first join):
   a. Definir default-language temporariamente
   b. Agendar detecção após delay-ticks
   c. Detectar locale do client via ProtocolLib (se disponível)
   d. Aplicar locale-mapping (en_gb → en_us, etc.)
   e. Salvar preferência no banco
   f. Executar actions.first-join (via AfterCore ActionService)
   g. Executar actions.language-change.<detected_lang>
```

### 12.3 Language Change Flow

```
1. Player muda idioma (comando, GUI, ou auto-detect)
2. Atualizar cache em memória
3. [Async] Salvar no banco de dados
4. Executar actions.language-change.any (se definido)
5. Executar actions.language-change.<new_lang> (se definido)
```

### 12.4 Plugin Namespace Registration

```java
// Em AfterQuests.onEnable()
MessageService messages = afterCore.messages();
messages.registerNamespace("afterquests", new File(getDataFolder(), "defaults/languages"));
```

```
1. Plugin chama registerNamespace("afterquests", defaultsFolder)
2. AfterLanguage verifica se languages/*/afterquests/ existe
3. Se NÃO → Copia defaults do plugin para languages/pt_br/afterquests/
4. Carrega todas as traduções do namespace em memória (L2)
5. Namespace disponível via MessageService
```

### 12.5 Crowdin Sync (Hot Reload)

```
1. Timer dispara OU admin executa /afterlang sync
2. [Async] Conectar à API do Crowdin
3. Verificar novas traduções desde último sync
4. Se sim → Download arquivos atualizados
5. Escrever em languages/ directory
6. Recarregar namespaces afetados em memória
7. Invalidar caches relevantes (L1 e L3) atomicamente
8. Notificar admins online (se configurado)
9. Atualizar crowdin-state.json
```

**Dual trigger:** Timer-based (30min default) + Webhook (event-driven). Ambos configuráveis.

---

## 13. PlaceholderAPI Compatibility

Expansion legada para backward compatibility com plugins que usam PAPI:

```
%afterlang_namespace:key%           → Mensagem traduzida
%afterlang_key%                     → Usa namespace "default"
%afterlang_player_language%         → Idioma atual do jogador
%afterlang_player_language_name%    → Nome display (ex: "Português (BR)")
```

---

## 14. Version Compatibility

O plugin deve suportar tanto Adventure API (1.16+) quanto legacy color codes (1.8+):

- **Auto-detecção:** Verifica versão do Paper na inicialização
- **1.8-1.15:** Usa legacy `&` color codes e `ChatColor`
- **1.16+:** Usa Adventure API com `Component`, hex colors, MiniMessage
- **Configurável:** `performance.use-adventure-when-available: true`

---

## 15. Requisitos Técnicos Transversais

| Requisito | Detalhe |
|-----------|---------|
| **Thread Safety** | Todos os caches e registries devem ser thread-safe (ConcurrentHashMap, Caffeine) |
| **Async I/O** | Todas as operações de banco e API Crowdin via CompletableFuture |
| **Fallback Chain** | Player language → default-language → key literal (formato configurável) |
| **Graceful Degradation** | Se Crowdin sync falhar, logar erro mas continuar com traduções em cache |
| **Hot Reload** | Reload traduções sem restart, invalidar caches atomicamente |
| **Zero TPS Impact** | Nenhuma operação bloqueante na main thread; tolerância zero para degradação de TPS |
| **Result Pattern** | Usar `CoreResult` do AfterCore para error handling (sem exceções para fluxos normais) |
| **Bounded Caches** | Todos os caches com `maximumSize` definido; sem memory leaks |

---

## 16. Prioridades de Implementação

A implementação deve seguir esta ordem, onde cada fase entrega valor funcional:

### Fase 1 — Core Messaging (MVP)
- MessageService interface no AfterCore
- MessageKey, Placeholder, Translation models
- Carregamento de arquivos YAML de tradução
- `send()`, `get()`, `getOrDefault()` com fallback chain
- Player language management (banco + cache)
- Namespace registration (`registerNamespace`)
- Comandos básicos: `/lang`, `/afterlang reload`, `/afterlang set`

### Fase 2 — GUI Integration
- Resolução de `{lang:namespace:key}` no InventoryService do AfterCore
- Lore multilinha (YAML list + `\n` split)
- default-items traduzidos com prioridade (plugin → AfterCore)
- Validação de título 32 chars
- Language selector GUI (`/lang`)

### Fase 3 — Config Scanner
- TranslationSchema API
- Scanner engine (YAML path traversal com wildcards)
- Action filter para rewards (title, message, centered_message)
- Auto-geração de arquivos de tradução source
- Rescan com detecção de diff (novas, alteradas, removidas)
- Comando `/afterlang scan`

### Fase 4 — Crowdin Sync
- Upload de source files
- Download de traduções
- Hot reload após sync
- Timer-based auto-sync
- Webhook support
- `crowdin-state.json` tracking
- Comandos: `/afterlang sync`, `/afterlang sync status`

### Fase 5 — Dynamic Content
- `registerDynamic()`, `unregisterDynamic()`, `updateDynamic()`
- Tabela `afterlanguage_dynamic` no MySQL
- `setTranslation()` para tradução manual
- TranslationStatus tracking (TRANSLATED, PENDING, OUTDATED)
- Source hash para detecção de OUTDATED
- Comandos: `/afterlang translate`, `/afterlang pending`, `/afterlang outdated`

### Fase 6 — Admin Tools
- GUI de traduções pendentes (`/afterlang editor`)
- Export dinâmicas para YAML (`/afterlang export`)
- Debug command (`/afterlang debug <key>`)
- Stats command (`/afterlang stats`)

### Fase 7 — Extras
- Pluralização (.one/.other)
- PlaceholderAPI expansion
- ProtocolLib locale detection
- Redis sync (multi-server)
- Adventure Component pre-parsing (1.16+)
- Pre-compilação de templates

---

## 17. Exemplo Completo de Uso

### AfterJournal com AfterLanguage

```java
public class AfterJournalPlugin extends JavaPlugin {

    private MessageService messages;

    @Override
    public void onEnable() {
        // 1. Obter MessageService do AfterCore
        messages = afterCore.messages();

        // 2. Registrar namespace com defaults
        messages.registerNamespace("afterjournal",
            new File(getDataFolder(), "defaults/languages"));

        // 3. Registrar schema para config scanning
        TranslationSchema schema = TranslationSchema.builder("afterjournal")
            .scanFile("quests/*.yml")
            .atPath("discoveries.*.name")
            .atPath("discoveries.*.description")
            .atPath("discoveries.*.stages.*.name")
            .atPath("discoveries.*.stages.*.description")
            .atPath("discoveries.*.stages.*.rewards.on_discovery")
                .withActionFilter("title", "message", "centered_message")
            .build();

        messages.registerSchema(schema);
    }

    // Renderizando discovery no Journal GUI
    public void showJournal(Player player) {
        List<Discovery> discoveries = getPlayerDiscoveries(player);

        List<GuiItem> items = discoveries.stream()
            .map(d -> {
                String name = messages.get(player,
                    MessageKey.of("afterjournal", "discoveries." + d.id() + ".name"));
                String desc = messages.get(player,
                    MessageKey.of("afterjournal", "discoveries." + d.id() + ".description"));

                return GuiItem.builder()
                    .placeholder("achievement_name", name)
                    .placeholder("achievement_lore", List.of(desc))
                    .placeholder("achievement_material", d.isUnlocked(player) ? "DIAMOND" : "COAL")
                    .build();
            })
            .toList();

        inventoryService.openPaginated(player, "journal-menu", items);
    }

    // Reload handler
    public void onReload() {
        reloadConfig();
        messages.rescan("afterjournal");  // Re-scan para detectar mudanças
    }
}
```

---

## 18. Notas para o Implementador

### 18.1 O que NÃO fazer
- **NÃO** usar I/O síncrono na main thread (banco, Crowdin API, leitura de arquivos grandes)
- **NÃO** usar exceções para fluxo normal (usar `CoreResult` do AfterCore)
- **NÃO** aninhar YAML nas traduções (usar flat keys: `quest.started`, não `quest: started:`)
- **NÃO** usar `%placeholder%` syntax (reservado para PAPI)
- **NÃO** truncar títulos de GUI que excedam 32 chars (validar e alertar)
- **NÃO** deletar keys removidas imediatamente no rescan (marcar deprecated, logar)

### 18.2 O que SEMPRE fazer
- **SEMPRE** usar `CompletableFuture` para operações de banco
- **SEMPRE** usar `Caffeine` para caches (já shaded no AfterCore)
- **SEMPRE** seguir arquitetura hexagonal (api → core → infra → bootstrap)
- **SEMPRE** thread-safe em caches e registries
- **SEMPRE** logar missing keys quando `fallback.log-missing-keys: true`
- **SEMPRE** invalidar cache atomicamente em reloads (sem window de inconsistência)
- **SEMPRE** copiar `<w:rPr>` — brincadeira, estamos em Minecraft, não DOCX. Mas SEMPRE copiar defaults do plugin se translations não existirem no primeiro load.

### 18.3 Compatibilidade com AfterCore
- Usar `SqlService` para conexão com banco (NÃO criar HikariCP próprio)
- Usar `ActionService` para executar actions de config (`first-join`, `language-change`)
- Usar `InventoryService` para GUIs (language selector, admin editor)
- Usar `SchedulerService` para tasks assíncronas
- Registrar como provider do `MessageService` na inicialização

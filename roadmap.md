# AfterLanguage — Roadmap

> Roadmap derivado de `AfterLanguage/afterlanguage-spec.md` (seção **16. Prioridades de Implementação**), com uma **Fase 0** adicional para scaffolding/infra.

## Status Geral: v1.1.0 (2026-02-01)

**Essential Integrations Completo** ✅ - Production Ready

- ✅ Fases 0, 1, 2, 3 concluídas (MVP Core)
- ✅ Fase 7 parcialmente implementada (ProtocolLib + PAPI + GUI + Actions)
- ⚠️ Fases 4, 5, 6 parcialmente implementadas (stubs/estrutura)
- 🎯 Performance targets atingidos
- 📦 Build: AfterLanguage-1.1.0.jar (90KB)

---

## 0. Princípios (Definition of Done global)

Qualquer fase só é considerada concluída quando:

- **Main thread sagrada**: nenhuma operação de I/O (DB, FS, HTTP, Redis) roda na main thread.
- **Degradação graciosa**: ausências/falhas de integrações opcionais não derrubam o plugin.
- **Caches limitados** (bounded) e invalidation consistente.
- **Logs** e mensagens administrativas suficientes para depurar (modo debug).

---

## Fase 0 — Setup & Fundação (scaffolding) ✅ COMPLETA

**Status:** Concluído em v1.0.0-SNAPSHOT (2026-02-01)

### Objetivo
Preparar o repositório do AfterLanguage para implementação rápida e segura.

### Entregáveis ✅
- ✅ Projeto Maven (Java 21) + build configurado
- ✅ Estrutura hexagonal (api/core/infra/bootstrap)
- ✅ `config.yml`, `messages.yml`, `crowdin.yml` completos
- ✅ Integração com AfterCore (hard-depend no `plugin.yml`)
- ✅ pom.xml com auto-copy para AfterCompiled

### Critérios de aceite ✅
- ✅ Plugin sobe sem erros
- ✅ Zero warnings de thread/blocking no startup
- ✅ Build SUCCESS: AfterLanguage-1.0.0-SNAPSHOT.jar (87KB)

### Dependências
- Nenhuma.

---

## Fase 1 — Core Messaging (MVP) ✅ COMPLETA

**Status:** Concluído em v1.0.0-SNAPSHOT (2026-02-01)

### Objetivo
Entregar o núcleo de i18n: `get()/send()` por jogador com fallback chain, idioma persistido e namespaces.

### Entregáveis (AfterLanguage) ✅
- ✅ **YamlTranslationLoader** - Loader de traduções YAML em `plugins/AfterLanguage/languages/`
- ✅ **TranslationRegistry** - Registry L2 (in-memory) com lookup eficiente por `lang/namespace/key`
- ✅ **MessageResolver** - Fallback chain: `player lang → default-language → key literal/missing-format`
- ✅ **Player language management**:
  - ✅ TranslationCache (L1/L3) em memória com Caffeine
  - ✅ PlayerLanguageRepository - Persistência MySQL (`afterlanguage_players`) via AfterCore SqlService (async)
- ✅ **NamespaceManager**:
  - ✅ `registerNamespace(namespace, defaultTranslationsFolder)`
  - ✅ Cópia de defaults se não existir source `pt_br/<namespace>/`
  - ✅ `reloadNamespace()` com hot-reload
- ✅ **Comandos básicos**:
  - ✅ `/lang set <language>` - Alterar idioma
  - ✅ `/lang list` - Listar idiomas disponíveis
  - ✅ `/lang info` - Informações do idioma atual
  - ✅ `/afterlang reload [namespace]` - Hot-reload de traduções
  - ✅ `/afterlang stats` - Estatísticas completas
  - ✅ `/afterlang cache` - Cache hit/miss rates

### Entregáveis (AfterCore — dependência) ✅
- ✅ **MessageService** - Interface evoluída com métodos i18n e provider pattern
  - ✅ Métodos legados marcados `@Deprecated`
  - ✅ Novos métodos com `MessageKey` e `Placeholder`
  - ✅ Player language management APIs
  - ✅ Bulk operations e broadcast
- ✅ **DefaultMessageService** - Fallback provider quando AfterLanguage não instalado
- ✅ **MessageKey** e **Placeholder** records type-safe

### Critérios de aceite ✅
- ✅ Com AfterLanguage: `messages.get(player, key)` resolve em idiomas distintos por player
- ✅ Sem AfterLanguage: chamadas retornam fallback sem crashes
- ✅ Idioma do player persiste após relog (DB async confirmado)
- ✅ `registerNamespace` cria/copia defaults corretamente no first-run
- ✅ Performance: L1 hit < 0.01ms, L2 hit < 0.1ms (targets atingidos)

### Riscos
- Conflito de API `MessageService` no AfterCore (mitigação: migração gradual com defaults/deprecations).

---

## Fase 2 — GUI Integration ✅ COMPLETA

**Status:** Concluído em v1.0.0-SNAPSHOT (2026-02-01)

### Objetivo
Traduzir automaticamente `inventories.yml` via pattern `{lang:namespace:key}` e entregar GUI seletora de idioma.

### Entregáveis (AfterCore — dependência) ✅
- ✅ **PlaceholderResolver** - Suporte `{lang:namespace:key}` em `InventoryService` para:
  - ✅ title (títulos de inventário)
  - ✅ item name (nomes de itens)
  - ✅ item lore (lore de itens)
- ✅ **ItemCompiler** - Lore multilinha:
  - ✅ YAML list vira múltiplas linhas
  - ✅ `\n` é splitado em múltiplas linhas
  - ✅ Ordem de resolução: `{lang:...}` → `{placeholder}` → `%papi%` → colorize
- ✅ **InventoryViewHolder** - Validação de título 32 chars (1.8.8):
  - ✅ Warning + fallback para default-language
  - ✅ Sem truncar (usa título do idioma padrão se exceder)

### Entregáveis (AfterLanguage) ✅
- ✅ **GUI selector** `/lang`: **Implementado em v1.1.0**
  - ✅ `/lang set <language>` - Comando funcional
  - ✅ `/lang list` - Lista idiomas via texto
  - ✅ `/lang` (sem args) - Abre GUI in-game
  - ✅ GUI com banners coloridos (pt_br, en_us, es_es)
  - ✅ Glow effect no idioma atual
  - ✅ Click-to-select com auto-close
- ✅ **Actions configuráveis**: **Implementado em v1.1.0**
  - ✅ `actions.first-join` - Executado no primeiro login
  - ✅ `actions.language-change.any` - Executado em qualquer mudança
  - ✅ `actions.language-change.<lang>` - Executado por idioma específico
  - ✅ Integração completa com AfterCore ActionService

### Critérios de aceite ✅
- ✅ Inventários com `{lang:...}` renderizam em idioma correto do player
- ✅ Título excedendo 32 chars gera warning e usa fallback
- ✅ `/lang set` altera idioma persistindo async no MySQL
- ✅ GUI selector funcional e traduzido (v1.1.0)
- ✅ Actions configuráveis funcionais (v1.1.0)

---

## Fase 3 — Config Scanner ✅ COMPLETA

**Status:** Concluído em v1.0.0-SNAPSHOT (2026-02-01) - Beta

### Objetivo
Extrair textos traduzíveis de YAMLs de outros plugins via `TranslationSchema` e gerar source files automaticamente.

### Entregáveis (AfterLanguage) ✅
- ✅ **TranslationSchema** - API `TranslationSchema.builder(namespace)`:
  - ✅ `scanFile(globPattern)` - Suporte a globs (ex: `inventories/**/*.yml`)
  - ✅ `atPath(yamlPath)` - Wildcards `*` (ex: `items.*.name`, `items.*.lore.*`)
  - ✅ `withActionFilter(...)` - Filtro para listas de actions traduzíveis
- ✅ **ConfigScanner** - Engine de scanner:
  - ✅ Traversal de YAML com múltiplos wildcards aninhados
  - ✅ Suporte a múltiplos arquivos via glob expansion
  - ✅ Output auto-generated em `languages/pt_br/<namespace>/AUTO-GENERATED_*.yml`
- ✅ **ScanResult** - Rescan/diff:
  - ✅ Detectar keys novas/alteradas/removidas
  - ✅ Atualizar pt_br (source) automaticamente
  - ✅ Marcar `PENDING/OUTDATED` em outras línguas (estrutura pronta)
  - ✅ Política de remoção: não deletar imediatamente (log apenas)
- ⚠️ **Comando**:
  - ❌ `/afterlang scan [namespace]` - Não exposto (API disponível via código)
  - ✅ API `MessageService.scanConfigs()` implementada

### Critérios de aceite ✅
- ✅ Plugin consumidor registra schema via API
- ✅ Scanner gera arquivo AUTO-GENERATED corretamente
- ✅ Rescan adiciona novas chaves preservando antigas
- ✅ Actions filter extrai somente partes traduzíveis (message, title, etc.)
- ⚠️ Comando `/afterlang scan` pendente (API funcional)

---

## Fase 4 — Crowdin Sync ⚠️ PARCIAL

**Status:** Estrutura criada, implementação pendente

### Objetivo
Sincronizar traduções com Crowdin e aplicar hot reload.

### Entregáveis (AfterLanguage) ⚠️
- ✅ Leitura `crowdin.yml` - Configuração pronta
- ✅ Config em `config.yml` - Seção `crowdin.*` presente
- ❌ **Sync** - Não implementado:
  - ❌ Upload source files (pt_br) via Crowdin API
  - ❌ Download traduções atualizadas
  - ❌ Hot reload de namespaces afetados (infraestrutura existe)
  - ✅ Invalidation atômica de cache (funcional via `reloadNamespace`)
- ❌ **Tracking de estado**:
  - ❌ `cache/crowdin-state.json` - Não implementado
- ❌ **Scheduler**:
  - ❌ Auto-sync interval - Não implementado
- ❌ **Webhook**:
  - ❌ Listener HTTP com secret/port - Não implementado
- ⚠️ **Comandos**:
  - ❌ `/afterlang sync` - Não implementado
  - ❌ `/afterlang sync status` - Não implementado

### Critérios de aceite ❌
- ❌ Sync manual não implementado
- ❌ Webhook não implementado
- ✅ Estrutura de config e hot-reload prontas para implementação futura

---

## Fase 5 — Dynamic Content ⚠️ PARCIAL

**Status:** Repository existe, API não implementada

### Objetivo
Permitir conteúdo traduzível criado programaticamente (não vindo de configs) com tracking de status.

### Entregáveis (AfterLanguage) ⚠️
- ⚠️ **API Dynamic Content**:
  - ❌ `registerDynamic(namespace, keyPrefix, fields)` - Não implementado
  - ❌ `unregisterDynamic(namespace, keyPrefix)` - Não implementado
  - ❌ `updateDynamic(namespace, keyPrefix, fields)` - Não implementado
  - ❌ `setTranslation(namespace, key, language, value)` - Não implementado
  - ❌ `getStatus(namespace, key)` - Não implementado
  - ❌ `getPendingTranslations(namespace)` - Não implementado
- ⚠️ **Persistência**:
  - ✅ Tabela `afterlanguage_dynamic` - Schema criado
  - ✅ DynamicTranslationRepository - Classe existe (stub)
  - ❌ `source_hash` para OUTDATED - Não implementado
- ⚠️ **Comandos**:
  - ❌ `/afterlang translate <namespace:key> <lang> <value>` - Não implementado
  - ❌ `/afterlang pending [namespace]` - Não implementado
  - ❌ `/afterlang outdated [namespace]` - Não implementado

### Critérios de aceite ❌
- ❌ API não exposta ainda
- ✅ Infraestrutura de persistência pronta
- ❌ Comandos admin não implementados

---

## Fase 6 — Admin Tools ⚠️ PARCIAL

**Status:** Comandos básicos implementados, GUI pendente

### Objetivo
Dar ferramentas de operação (GUI + debug + export + métricas).

### Entregáveis (AfterLanguage) ⚠️
- ⚠️ **Admin Tools**:
  - ❌ GUI de traduções pendentes: `/afterlang editor [namespace]` - Não implementado
  - ❌ Export dinâmicas para YAML: `/afterlang export [namespace]` - Não implementado
  - ❌ Debug tooling: `/afterlang debug <key>` - Não implementado
  - ✅ Stats: `/afterlang stats` - **Implementado e funcional**
    - ✅ Cache hit/miss rates (L1 Caffeine)
    - ✅ Registry stats (traduções carregadas, namespaces)
    - ✅ Player distribution by language
    - ✅ Performance metrics (avg get/send time)
  - ✅ Cache stats: `/afterlang cache` - **Implementado**

### Critérios de aceite ⚠️
- ❌ GUI editor não implementado
- ❌ Export YAML não implementado
- ✅ Stats e observabilidade funcionais
- ⚠️ Debug tools pendentes

---

## Fase 7 — Extras (otimizações + integrações) ⚠️ PARCIAL

**Status:** Parcialmente implementado em v1.1.0 - Essential Integrations

### Objetivo
Completar recursos avançados e melhorar performance/compatibilidade.

### Entregáveis ⚠️
- ❌ **Pluralização** `.one/.other` (count-based) - Não implementado
  - Sintaxe pronta na spec, engine não implementado
  - Planejado para v1.2.0+
- ✅ **PlaceholderAPI expansion** (compat) para plugins legados - **Implementado v1.1.0**:
  - ✅ `%afterlang_player_language%` - Código do idioma
  - ✅ `%afterlang_player_language_name%` - Nome do idioma
  - ✅ `%afterlang_namespace:key%` - Tradução completa
  - ✅ `%afterlang_key%` - Tradução do namespace afterlanguage
  - ✅ Graceful degradation sem PlaceholderAPI
- ✅ **ProtocolLib locale detection** (auto-detect) - **Implementado v1.1.0**:
  - ✅ Packet listener para ClientSettings
  - ✅ Auto-set idioma via client locale
  - ✅ Locale mapping configurável (en_GB → en_us, etc.)
  - ✅ Cache de players processados
  - ✅ Graceful degradation sem ProtocolLib
- ❌ **Redis sync** (multi-server):
  - ❌ Pub/Sub para reload events
  - ❌ Distribuição de language changes
  - **Nota**: Config presente, integração não implementada
  - Planejado para v1.3.0+
- ❌ **Adventure Component** pre-parsing (1.16+):
  - Não implementado (compatibilidade 1.8.8 apenas)
  - Planejado para v2.0.0+ (quando migrar para 1.16+)
- ✅ **Pre-compilação de templates (L3)** - **Implementado v1.0.0**:
  - ✅ CompiledMessage com offsets/slots
  - ✅ Cache L3 com Caffeine

### Critérios de aceite ⚠️
- ✅ Funciona em 1.8.8 (legacy) com performance targets atingidos
- ❌ Adventure Components não suportado (planejado v2.0+)
- ❌ Redis sync não implementado (planejado v1.3+)
- ✅ ProtocolLib integration funcional (v1.1.0)
- ✅ PlaceholderAPI expansion funcional (v1.1.0)
- ❌ Pluralization pendente (planejado v1.2+)

---

## Dependências e Ordem Recomendada

Ordem global sugerida:

1. Fase 0 (setup)
2. Fase 1 (MVP) — **bloqueia** as demais (base de i18n + provider no AfterCore)
3. Fase 2 (GUI integration) — depende de `{lang:...}` no InventoryService
4. Fase 3 (scanner)
5. Fase 4 (Crowdin)
6. Fase 5 (dynamic content)
7. Fase 6 (admin tools)
8. Fase 7 (extras)


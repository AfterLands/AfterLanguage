# AfterLanguage — Roadmap

> Roadmap derivado de `AfterLanguage/afterlanguage-spec.md` (seção **16. Prioridades de Implementação**), com uma **Fase 0** adicional para scaffolding/infra.

---

## 0. Princípios (Definition of Done global)

Qualquer fase só é considerada concluída quando:

- **Main thread sagrada**: nenhuma operação de I/O (DB, FS, HTTP, Redis) roda na main thread.
- **Degradação graciosa**: ausências/falhas de integrações opcionais não derrubam o plugin.
- **Caches limitados** (bounded) e invalidation consistente.
- **Logs** e mensagens administrativas suficientes para depurar (modo debug).

---

## Fase 0 — Setup & Fundação (scaffolding)

### Objetivo
Preparar o repositório do AfterLanguage para implementação rápida e segura.

### Entregáveis
- Projeto Maven (Java 21) + shaded/relocations conforme padrão AfterLands.
- Estrutura hexagonal (api/core/infra/bootstrap).
- `config.yml`, `messages.yml`, `crowdin.yml`, `inventories.yml` mínimos.
- Integração com AfterCore (hard-depend no `plugin.yml`).

### Critérios de aceite
- Plugin sobe e registra comandos vazios (stubs) sem erros.
- Sem warnings de thread/blocking no startup.

### Dependências
- Nenhuma.

---

## Fase 1 — Core Messaging (MVP)

### Objetivo
Entregar o núcleo de i18n: `get()/send()` por jogador com fallback chain, idioma persistido e namespaces.

### Entregáveis (AfterLanguage)
- Loader de traduções YAML em `plugins/AfterLanguage/languages/`.
- Registry L2 (in-memory) com lookup eficiente por `lang/namespace/key`.
- Implementação do fallback chain: `player lang → default-language → key literal/missing-format`.
- Player language management:
  - cache em memória por UUID
  - persistência MySQL (`afterlanguage_players`) via AfterCore SqlService (async)
- Namespace registration:
  - `registerNamespace(namespace, defaultTranslationsFolder)`
  - cópia de defaults se não existir source `pt_br/<namespace>/`
- Comandos básicos:
  - `/lang` (set direto e/ou abre GUI depois na fase 2)
  - `/afterlang reload`
  - `/afterlang set <player> <lang>`

### Entregáveis (AfterCore — dependência)
- Evolução do `MessageService` do AfterCore para suportar o contrato da spec e o provider pattern (ou introdução de um serviço i18n equivalente com ponte, conforme decisão em `implementation_plan.md`).
- Fallback provider no AfterCore quando AfterLanguage não estiver instalado.

### Critérios de aceite
- Com AfterLanguage: `messages.get(player, key)` resolve em idiomas distintos.
- Sem AfterLanguage: chamadas não crasham; retornam missing-format/key literal.
- Idioma do player persiste após relog (DB async).
- `registerNamespace` cria/copía defaults corretamente no first-run.

### Riscos
- Conflito de API `MessageService` no AfterCore (mitigação: migração gradual com defaults/deprecations).

---

## Fase 2 — GUI Integration

### Objetivo
Traduzir automaticamente `inventories.yml` via pattern `{lang:namespace:key}` e entregar GUI seletora de idioma.

### Entregáveis (AfterCore — dependência)
- Suporte `{lang:namespace:key}` em `InventoryService` para:
  - title
  - item name
  - item lore
- Lore multilinha:
  - YAML list vira múltiplas linhas
  - `\n` é splitado em múltiplas linhas
- Prioridade de default-items:
  - defaults do plugin > defaults do AfterCore
- Validação de título 32 chars (1.8.8):
  - warning + fallback para default-language
  - sem truncar

### Entregáveis (AfterLanguage)
- GUI selector `/lang` usando InventoryService:
  - items por idioma (slot/material/skull/lore com placeholders)
  - glow quando selecionado
  - placeholders: `{translation_percent}`, etc.
- Actions configuráveis:
  - `actions.first-join`
  - `actions.language-change.any`
  - `actions.language-change.<lang>`

### Critérios de aceite
- Um inventário com `{lang:...}` renderiza em idioma correto do player.
- Título excedendo 32 chars cai em fallback e gera log.
- `/lang` abre GUI e altera idioma (persistindo async).

---

## Fase 3 — Config Scanner

### Objetivo
Extrair textos traduzíveis de YAMLs de outros plugins via `TranslationSchema` e gerar source files automaticamente.

### Entregáveis (AfterLanguage)
- API `TranslationSchema.builder(namespace)` conforme spec:
  - `scanFile(globPattern)`
  - `atPath(yamlPath)` com wildcards `*`
  - `withActionFilter(...)` para listas de actions
- Engine de scanner:
  - traversal de YAML com múltiplos wildcards
  - suporte a múltiplos arquivos via glob
  - output auto-generated em `languages/pt_br/<namespace>/...`
- Rescan/diff:
  - detectar keys novas/alteradas/removidas
  - atualizar pt_br (source)
  - marcar `PENDING/OUTDATED` em outras línguas
  - política de remoção: não deletar imediatamente (deprecated)
- Comando:
  - `/afterlang scan [namespace]`

### Critérios de aceite
- Um plugin consumidor registra schema e o AfterLanguage gera o arquivo auto-generated.
- Rescan adiciona novas chaves sem apagar antigas imediatamente.
- Actions filter extrai somente partes traduzíveis de rewards/actions.

---

## Fase 4 — Crowdin Sync

### Objetivo
Sincronizar traduções com Crowdin e aplicar hot reload.

### Entregáveis (AfterLanguage)
- Leitura `crowdin.yml` e config em `config.yml`.
- Sync:
  - upload source files (pt_br)
  - download traduções atualizadas
  - hot reload de namespaces afetados
  - invalidation atômica de cache
- Tracking de estado:
  - `cache/crowdin-state.json`
- Scheduler:
  - auto-sync interval
- Webhook:
  - listener HTTP com secret e port configuráveis
- Comandos:
  - `/afterlang sync`
  - `/afterlang sync status`

### Critérios de aceite
- Sync manual baixa traduções e atualiza mensagens sem restart.
- Falha na API não derruba o plugin; logs adequados.
- Webhook dispara hot reload sem intervenção manual (quando habilitado).

---

## Fase 5 — Dynamic Content

### Objetivo
Permitir conteúdo traduzível criado programaticamente (não vindo de configs) com tracking de status.

### Entregáveis (AfterLanguage)
- Implementar:
  - `registerDynamic(namespace, keyPrefix, fields)`
  - `unregisterDynamic(namespace, keyPrefix)`
  - `updateDynamic(namespace, keyPrefix, fields)`
  - `setTranslation(namespace, key, language, value)`
  - `getStatus(namespace, key)`
  - `getPendingTranslations(namespace)`
- Persistência:
  - tabela `afterlanguage_dynamic`
  - `source_hash` para OUTDATED
- Comandos:
  - `/afterlang translate <namespace:key> <lang> <value>`
  - `/afterlang pending [namespace]`
  - `/afterlang outdated [namespace]`

### Critérios de aceite
- Conteúdo dinâmico aparece em pt_br imediatamente e fica pending em outras línguas.
- Atualizar source marca OUTDATED corretamente via hash.
- Tradução manual muda status para TRANSLATED.

---

## Fase 6 — Admin Tools

### Objetivo
Dar ferramentas de operação (GUI + debug + export + métricas).

### Entregáveis (AfterLanguage)
- GUI de traduções pendentes:
  - `/afterlang editor [namespace]`
- Export dinâmicas para YAML:
  - `/afterlang export [namespace]`
- Debug tooling:
  - `/afterlang debug <key>` (mostrar em todos idiomas)
- Stats:
  - `/afterlang stats` (caches, contagens, tempos)

### Critérios de aceite
- Admin consegue listar/editar pendências sem precisar abrir arquivos no disco.
- Export gera arquivos consistentes (sem perder dados).

---

## Fase 7 — Extras (otimizações + integrações)

### Objetivo
Completar recursos avançados e melhorar performance/compatibilidade.

### Entregáveis
- Pluralização `.one/.other` (count-based).
- PlaceholderAPI expansion (compat) para plugins legados.
- ProtocolLib locale detection (auto-detect).
- Redis sync (multi-server).
- Adventure Component pre-parsing (1.16+) quando disponível e habilitado.
- Pre-compilação de templates (L3) e warm-up opcional.

### Critérios de aceite
- Funciona em 1.8.8 (legacy) com performance dentro dos targets.
- Em servidores 1.16+, `Component` (quando habilitado) reduz custo de parse.
- Redis sync propaga mudanças entre servidores.

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


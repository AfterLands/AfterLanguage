# AfterLanguage Technical Specification (Current Baseline)

Last updated: 2026-02-14
Applies to: AfterLanguage 1.5.2

## Purpose

AfterLanguage provides enterprise i18n for the AfterLands ecosystem through the AfterCore `MessageService` provider model.

## Platform

- Minecraft: Spigot 1.8.8
- Java: 21 (`--enable-preview`)
- Runtime model: plugin-based, async persistence/integration

## Architectural Contract

- `api/`: public contracts only
- `core/`: business logic only
- `infra/`: adapters only
- `bootstrap/`: wiring and lifecycle only
- Dependency direction: `api <- core <- infra <- bootstrap`

## Functional Scope

- Per-player language selection and persistence
- Translation resolution with fallback chain
- Three-tier caching
- Namespace registration, extraction, and hot-reload
- Dynamic translation CRUD API
- Crowdin upload/download/sync/cleanup support
- Optional integrations: PlaceholderAPI, ProtocolLib, Redis

## Threading Rules

- No blocking I/O on main thread
- DB/filesystem/HTTP/Redis operations async
- Bukkit interactions on main thread
- CompletableFuture chains must include failure handling in fire-and-forget flows

## Data and Storage

Primary tables:

- `afterlanguage_player_language`
- `afterlanguage_dynamic_translations`
- `afterlanguage_crowdin_sync_log`

Migration registration is done by `PluginRegistry` during startup.

## Caching Contract

- L1: resolved message hot cache
- L2: translation registry snapshot
- L3: compiled template cache

On reload/mutation, invalidate in this order:

1. cache namespace
2. registry namespace
3. missing-key suppression tracker

## Integration Contract for Third-Party Plugins

Primary API surface is AfterCore `MessageService`.

Third-party plugins should:

1. Use `MessageKey.of(namespace, key)`
2. Register namespace via provider
3. Provide source files in plugin data folder (`messages.yml`, optional `inventories.yml`)

## Non-Goals (Current Line)

- No final GUI translation editor workflow yet
- No official 1000+ CCU SLA yet (needs formal load validation)
- No complete automated regression suite yet

## Canonical Documents

- Current state: `docs/STATUS.md`
- Architecture details: `docs/ARCHITECTURE.md`
- Testing checklist: `docs/TESTING.md`
- Crowdin testing: `docs/CROWDIN_TESTING.md`
- Release notes: `CHANGELOG.md`

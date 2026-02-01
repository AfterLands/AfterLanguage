# AfterLanguage

Enterprise-grade i18n (internationalization) plugin for the AfterLands Minecraft ecosystem.

## Overview

AfterLanguage provides comprehensive translation management with per-player language support, hot-reload capabilities, Crowdin integration, and automatic config scanning for translatable content.

## Features

- **Per-Player Language**: Each player can choose their preferred language
- **Hot Reload**: Update translations without server restart
- **Provider Pattern**: Integrates seamlessly with AfterCore's MessageService
- **Config Scanner**: Automatically extracts translatable strings from plugin configs
- **Crowdin Integration**: Professional translation workflow with auto-sync
- **Multi-tier Caching**: L1 (hot cache) + L2 (registry) + L3 (templates) for optimal performance
- **Locale Auto-Detection**: Automatically detects player locale via ProtocolLib
- **PlaceholderAPI**: Legacy compatibility for existing placeholder usage
- **Redis Sync**: Multi-server support with distributed cache invalidation

## Performance Targets

Designed for 20 TPS @ 500+ concurrent players:
- `get()` L1 hit: < 0.01ms
- `send()` complete: < 0.2ms
- GUI translation (54 slots): < 2ms

## Version

Current version: 1.0.0-SNAPSHOT
Status: In Development (FASE 2 Complete)

Last updated: 2026-01-31

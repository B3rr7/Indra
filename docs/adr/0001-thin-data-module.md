# ADR 0001 — Thin data module (per-feature ViewModels instead of a use-case layer)

**Status:** Accepted · **Date:** 2026-10-24

## Context

The 2026-10-24 full-codebase analysis found that the `:data` module's
`org.indra.android.domain.usecase` layer (`NetworkUseCases`, `PluginUseCases`)
was an unwired abstraction: every operator body was a TODO stub, two of them
returned hardcoded fake results (hosts `192.168.1.1/100`, ports `22/80/443`) in
direct conflict with the project's no-simulation principle, and no class
referenced them outside their own files. The app's real execution path is
ViewModels → `SmartToolBox`/`CoreManager` singletons in `:core`.

## Decision

1. **Keep the module split, delete the fake layer.** The `domain/usecase`
   package was removed; `PluginRepository` (an in-memory state holder with a
   write API nobody called) was removed as well.
2. **What remains in `:data`:** `data.model.*` (plain Kotlin models) and
   `NetworkRepository` (the one thin delegate actually injected, by
   `HomeViewModel`).
3. **Do not reintroduce a use-case layer speculatively.** If a screen needs
   logic shared by several ViewModels, lift it into the relevant `:core`
   singleton (that is where `SmartToolBox`, `CoreManager` and `PluginManager`
   already live and are unit-tested), or into a small `@HiltViewModel`-shared
   class only when the singleton would be wrong.

## Consequences

- No two competing "execution truths": `:core` singletons ARE the service layer.
- Fake results cannot regress into the tree (the stubs are deleted, not TODO'd).
- `:data` stays 2 files; it may grow with real persistence (captures index,
  scan history) when those features land — that is the trigger to revisit.

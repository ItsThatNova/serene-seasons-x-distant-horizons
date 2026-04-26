# Changelog

## 1.0.3 — Architecture refactor + delayed refresh scheduler

### DHCompat refactor

- Replaced reflection-based level access with DH public core interfaces:
  - `ILevelWrapper.getDhLevel()` replaces reflected `ClientLevelWrapper.dhLevel` field
  - `IClientLevelWrapper.clearBlockColorCache()` replaces reflected call on the concrete wrapper
  - `DhSectionPos.encode(byte, int, int)` called directly as a public static method
- Reflection is now reserved only for truly private internals:
  - `AbstractDhTintGetter.COLOR_BY_BLOCK_BIOME_PAIR` (static tint cache)
  - `AbstractDhTintGetter.BIOME_BY_RESOURCE_STRING` (static biome cache, newly cleared)
  - `DhClientLevel.reloadPos(long)` (soft-reload hook)

### Delayed refresh scheduler

- Introduced a generation-token scheduler to prevent stale delayed jobs from firing after
  a superseding event arrives.
- Live season change: caches cleared immediately, then a single delayed visible refresh
  fires after 12 ticks (Path C) or 35 ticks (Path B).
- World load / rejoin: caches cleared immediately, then a single delayed refresh fires
  after 50 ticks (Path C) or 35 ticks (Path B). No immediate GPU work on load.
- Caches are cleared a second time immediately before the delayed refresh executes, to
  flush any state accumulated during the wait window.

### Cache clearing

- Added `BIOME_BY_RESOURCE_STRING` clearing alongside the existing tint cache clear.
  Both fields are resolved from `AbstractDhTintGetter` at startup and cleared on every
  refresh cycle.

### SeasonMetaTexture

- Added `SeasonMetaTexture` singleton: registers two 1×1 textures (`ssdh:season_meta`
  and `ssdh:season_phase`) with the client texture manager.
- Updated every client tick by SSCompat; zeroed on world exit.
- Intended for custom shaders or resource packs that want to sample the current season
  and phase without parsing text.

---

## 1.0.0 — Initial public release

### Architecture

**Dual-path LOD refresh**

- Path C (soft reload): reflects into `ClientLevelWrapper.dhLevel` → `DhClientLevel.reloadPos(long)`
  to queue sections for in-place GPU buffer swap. No ring effect. Preferred path.
- Path B (hard rebuild): falls back to `IDhApiRenderProxy.clearRenderDataCache()` if Path C
  is unavailable. Produces brief ring effect but requires no DH internals.
- Automatic fallback — if any Path C reflection target is missing at startup, Path B is used
  silently. The mod never crashes on a DH version mismatch.

**Cache clearing**

- `AbstractDhTintGetter.COLOR_BY_BLOCK_BIOME_PAIR` cleared on every refresh — tint colors
  (grass, foliage, water) computed fresh from Serene Seasons after each clear.
- `ClientLevelWrapper.clearBlockColorCache()` called on the concrete wrapper instance.

**Reflection resolved once at startup**

- All reflection targets resolved and cached at `registerEvents()` time.
- Startup log shows ✓/✗ for each target individually.
- Summary line confirms which path is active.

**Season detection**

- Per-tick enum reference comparison (`current != lastKnownSubSeason`). Enum instances
  are singletons — this is an identity comparison with zero allocation cost.
- `lastKnownSubSeason` resets to null on world exit so re-entry fires a fresh clear.
- First tick after world join records the season without firing a refresh — the
  `DhApiLevelLoadEvent` handler covers that case.

**Level load event**

- `DhApiLevelLoadEvent` fires immediately when DH loads the overworld. Triggers a
  full cache clear and section reload on world join, server rejoin, and dimension
  re-entry. No countdown timer.
- Dimension filter: only acts on `minecraft:overworld` to avoid clearing on
  Nether/End loads.

**Section position computation**

- `DhSectionPos.encode(byte, int, int)` reflected as a static method.
- `DhSectionPos.SECTION_CHUNK_DETAIL_LEVEL` read as a reflected constant.
  Defaults to 4 (correct for 16-block chunks) if the field is inaccessible.
- Iterates a 128×128 chunk area centred on the player (radius 64). Positions outside
  DH's actual quadtree are silently discarded by `reloadPos`.

### Commands

- `/ssdh-client status` — season state, DH level availability, active reload path
- `/ssdh-client skip` — ticks to next sub-season + server command to skip
- `/ssdh-client reload` — manual refresh
- `/ssdh season <subseason>` — server-side season force (op required)

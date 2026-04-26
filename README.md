# Serene Seasons X Distant Horizons

A client-side Fabric mod that fixes LOD color mismatches between Serene Seasons and
Distant Horizons. When seasons change, DH's cached biome colors become stale and LODs
continue rendering the old seasonal palette. This mod detects season transitions and
triggers a seamless LOD color refresh.

---

## The problem

Distant Horizons bakes biome colors — grass tint, foliage, water — into GPU vertex
buffers when it generates LOD sections. Serene Seasons changes those colors globally
at the season transition, but DH's already-uploaded buffers are unaffected. The result
is a visible color mismatch at the LOD boundary: nearby terrain renders with the new
season's palette while distant LODs show the old one.

---

## How it works

On season change, world join, server rejoin, or overworld re-entry, the mod:

1. **Clears DH's tint color caches** — `AbstractDhTintGetter.COLOR_BY_BLOCK_BIOME_PAIR`
   and `AbstractDhTintGetter.BIOME_BY_RESOURCE_STRING` — so the next color lookup
   calls Serene Seasons fresh.

2. **Clears DH's block color cache** via `IClientLevelWrapper.clearBlockColorCache()`
   for the same reason.

3. **Triggers a delayed LOD buffer refresh** using one of two paths (see below).

### Refresh timing

Caches are cleared immediately when an event fires. The visible LOD refresh is
then delayed to allow the game to finish loading before queuing GPU work:

| Event | Path C delay | Path B delay |
|---|---|---|
| Season change | 12 ticks | 35 ticks |
| World load / rejoin | 50 ticks | 35 ticks |

A generation token prevents any stale delayed job from firing after a newer event
has superseded it.

### Refresh paths

**Path C — Soft reload (preferred)**

Calls `DhClientLevel.reloadPos(long)` for every section in the player's render range
(radius 64 chunks). DH keeps the existing GPU buffer visible until a new one finishes
uploading, then swaps atomically. The LODs update in place — no blanking, no expanding
ring effect.

This path requires reflection into one DH internal. At startup the mod logs whether it
resolved successfully. If it did, you will see seamless recoloring. If it did not, the
mod falls back to Path B automatically.

**Path B — Full rebuild (fallback)**

Calls `IDhApiRenderProxy.clearRenderDataCache()`, which is part of DH's public API.
This destroys all GPU buffers immediately and DH refills from player outward — producing
a brief expanding ring as sections reload. It is reliable regardless of DH version.

---

## What could break Path C (and how to tell)

Path C uses reflection to reach one thing inside DH that is not part of its public API.
If DH changes this, Path C will fail at startup and the mod falls back to Path B
automatically — it will not crash.

### `DhClientLevel.reloadPos(long)`

The class `com.seibel.distanthorizons.core.level.DhClientLevel` has a method
`reloadPos(long)` that queues a section position for in-place GPU buffer reload. If
this method is renamed, removed, or its signature changes, Path C fails.

The two cache fields on `AbstractDhTintGetter` are also reached via reflection (they
are private statics), but failing to clear them degrades color freshness rather than
breaking the refresh entirely — the mod logs individually whether each resolved.

### How to check if Path C is active

Look at the startup log after loading the mod:

```
[SSDH] Soft reload (Path C) available — LOD refresh will be seamless, no ring effect.
```

or

```
[SSDH] Soft reload (Path C) unavailable — falling back to full rebuild (Path B).
LOD refresh will produce a brief ring effect.
```

Each reflection target also logs individually with ✓ or ✗, so you can see exactly
which component broke on a new DH version.

### Confirmed working against

- Distant Horizons 2.4.5-b for Minecraft 1.21.1

---

## Shader metadata textures

The mod registers two 1×1 textures updated every tick for use in custom shaders or
resource packs that want to react to the current season.

| Texture ID | Format | Meaning |
|---|---|---|
| `ssdh:season_meta` | RGBA | One channel is 0xFF for the active season: R=autumn, G=summer, B=spring, A=winter |
| `ssdh:season_phase` | RGBA | One channel is 0xFF for the active phase: R=late, G=mid, B=early |

Both textures are zeroed when the player is not in a world.

---

## Triggers

| Event | What fires |
|---|---|
| Season transition | SSCompat detects via per-tick enum comparison |
| World join / server rejoin | `DhApiLevelLoadEvent` fires when DH loads the overworld |
| Overworld re-entry after dimension switch | Same — `DhApiLevelLoadEvent` fires again |

---

## Commands

| Command | Description |
|---|---|
| `/ssdh-client status` | Shows current season, cycle progress, DH state, and which reload path is active. |
| `/ssdh-client skip` | Shows ticks until next sub-season and the server command to skip there. |
| `/ssdh-client reload` | Manually triggers a LOD color refresh. |
| `/ssdh season <subseason>` | (Server, op required) Forces a specific sub-season. |

Valid sub-season names: `early_spring`, `mid_spring`, `late_spring`, `early_summer`,
`mid_summer`, `late_summer`, `early_autumn`, `mid_autumn`, `late_autumn`,
`early_winter`, `mid_winter`, `late_winter`.

---

## Building

Place the following jars in the `libs/` folder before building:

- A Serene Seasons Fabric jar for 1.21.1 (e.g. `SereneSeasons-fabric-1.21.1-*.jar`)
- A Distant Horizons jar for 1.21.1 (e.g. `DistantHorizons-*-1.21.1-fabric-neoforge.jar`)

Then:

```
./gradlew build
```

On Windows use `gradlew.bat build` instead.

---

## Dependencies

- Fabric Loader ≥ 0.16.5
- Fabric API
- Minecraft 1.21.1
- Serene Seasons (any version)
- Distant Horizons (any version — Path C confirmed on 2.4.5-b, falls back to Path B otherwise)

---

## Author

Made by [ItsThatNova](https://github.com/ItsThatNova).

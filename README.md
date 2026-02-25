# Serene Seasons X Distant Horizons

A Fabric compatibility mod for Minecraft 1.21.1 that fixes LOD color mismatches between
[Serene Seasons](https://modrinth.com/mod/serene-seasons) and
[Distant Horizons](https://modrinth.com/mod/distanthorizons).

## The Problem

When using Serene Seasons and Distant Horizons together, LODs (Level of Detail chunks
rendered by DH beyond vanilla render distance) display the wrong seasonal colors.
Trees appear orange at LOD distance in summer, or green in autumn. The mismatch
persists across play sessions and sometimes survives world rejoins.

This is a known issue ([SereneSeasons #530](https://github.com/Glitchfiend/SereneSeasons/issues/530))
that has not been fixed in either mod.

## How It Works

Distant Horizons caches per-block and per-(block, biome) tint colors internally
so it doesn't have to re-query them every frame. Serene Seasons hooks vanilla's
color resolver to apply seasonal tints, but DH's caches are never told a season
change happened — so they serve stale colors indefinitely.

This mod invalidates those caches at the right moments:

**On season change (gradual correction):**
Both of DH's color caches are cleared. DH will re-query colors through vanilla's
resolver (which Serene Seasons has hooked) the next time it builds render data for
each LOD section. Sections are rebuilt on demand as you move around, so colors
correct themselves silently over time without any visual disruption or forced rebuild.

**On world join / dimension return to overworld (immediate correction):**
Both caches are cleared immediately when the level loads to prevent stale colors
being used as a warm-start. After a short delay (to allow the server DH handshake
and Serene Seasons season sync to complete), the caches are cleared again and a
full LOD rebuild is triggered while you are still on the loading screen, so colors
are correct from the moment you load in.

## Requirements

- Minecraft 1.21.1 (Fabric)
- Fabric Loader >= 0.16.5
- Fabric API
- [Serene Seasons](https://modrinth.com/mod/serene-seasons) (Fabric 1.21.1)
- [Distant Horizons](https://modrinth.com/mod/distanthorizons) 2.4.5-b+

The mod is installed on the **client**. Installing it on a server as well unlocks
the `/ssdh season` admin command (see below), but has no other effect server-side.

## Installation

Drop the jar into your mods folder alongside Serene Seasons and Distant Horizons.
No configuration required.

## Commands

### Client-side (no permissions required)

| Command | Description |
|---|---|
| `/ssdh-client status` | Shows the current season and sub-season as reported by Serene Seasons. |
| `/ssdh-client skip` | Advances the season forward by one sub-season. Useful for testing. Requires the mod to also be installed on the server. |
| `/ssdh-client reload` | Manually clears both DH color caches and triggers a full LOD rebuild. Use this if colors are still wrong after a season change. |

### Server-side (operator level 2 required)

| Command | Description |
|---|---|
| `/ssdh season <subseason>` | Forcibly sets the server season to the specified sub-season. Valid values: `early_spring`, `mid_spring`, `late_spring`, `early_summer`, `mid_summer`, `late_summer`, `early_autumn`, `mid_autumn`, `late_autumn`, `early_winter`, `mid_winter`, `late_winter`. |

## Notes

- During a gradual color correction (season change), individual LOD sections update
  as DH rebuilds them on demand. Most sections update within a minute of normal play;
  occasionally a section may take longer depending on DH's internal rebuild queue
  priority. Use `/ssdh-client reload` to force an immediate full rebuild if needed.

- This mod clears DH's internal tint color cache via reflection, since the public
  DH API does not expose this. It does not mixin into DH internals. If a future DH
  update renames these internal classes the mod will log a warning and degrade
  gracefully — colors won't update automatically, but no crash will occur.

- The LOD render rebuild is performed via the official public DH API and should
  remain compatible across DH updates that do not break the API.

## License

MIT — see [LICENSE](LICENSE)

## Source

[github.com/ItsThatNova/serene-seasons-x-distant-horizons](https://github.com/ItsThatNova/serene-seasons-x-distant-horizons)
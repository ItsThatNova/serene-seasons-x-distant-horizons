package dev.itsthatnova.ssdh.compat;

import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import dev.itsthatnova.ssdh.SSDHClient;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import sereneseasons.api.season.ISeasonState;
import sereneseasons.api.season.Season;
import sereneseasons.api.season.SeasonHelper;

@Environment(EnvType.CLIENT)
public class SSCompat {

    private static Season.SubSeason lastKnownSubSeason = null;

    /**
     * Registers a client tick listener to detect Serene Seasons sub-season changes.
     *
     * We poll rather than using GlitchCore's event bus because:
     * - GlitchCore's event registration API is not documented for external mods.
     * - Polling every 100 ticks (5 seconds) is negligible overhead.
     * - Sub-seasons last 8 in-game days (192,000 ticks at vanilla speed, much
     *   longer with Better Days), so 100-tick polling resolution is more than fine.
     */
    public static void registerEvents() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.world == null) {
                // Reset tracking when not in a world.
                lastKnownSubSeason = null;
                return;
            }

            // Only check every 100 ticks to keep overhead negligible.
            if (client.world.getTime() % 100 != 0) {
                return;
            }

            ISeasonState seasonState = SeasonHelper.getSeasonState(client.world);
            if (seasonState == null) {
                return;
            }

            Season.SubSeason currentSubSeason = seasonState.getSubSeason();

            // First poll after joining a world. Trigger a cache clear so DH picks up
            // the server's current season as soon as SS has synced it — before the
            // deferred rebuild in DHCompat fires. Without this, the tint cache can
            // get repopulated with stale season colors during the 200-tick wait window.
            if (lastKnownSubSeason == null) {
                lastKnownSubSeason = currentSubSeason;
                SSDHClient.LOGGER.info("[SSDH] Season initialised after world join: {}. Clearing DH color cache.", currentSubSeason);
                onSeasonChanged();
                return;
            }

            if (currentSubSeason != lastKnownSubSeason) {
                SSDHClient.LOGGER.info("[SSDH] Season changed: {} -> {}. Clearing DH color cache.",
                        lastKnownSubSeason, currentSubSeason);

                lastKnownSubSeason = currentSubSeason;
                onSeasonChanged();
            }
        });
    }

    /**
     * Called when a sub-season transition is detected.
     *
     * Clears DH's block color cache so that LODs re-query colors through
     * vanilla's resolver (which Serene Seasons hooks for seasonal tinting)
     * the next time DH builds render data for any section.
     *
     * DH stores terrain as block + biome data, not pre-baked colors. Color is
     * computed at render-buffer-build time. LOD sections are rebuilt on-demand
     * as the player moves, so colors correct themselves gradually and silently
     * without any forced rebuild or visual disruption.
     *
     * Full rebuilds on world join and dimension return are handled separately
     * by DHCompat's level load event.
     */
    private static void onSeasonChanged() {
        IDhApiLevelWrapper overworldLevel = DHCompat.getOverworldLevel();
        if (overworldLevel == null) {
            SSDHClient.LOGGER.warn("[SSDH] Season changed but overworld level not available - skipping cache clear.");
            return;
        }

        DHCompat.clearColorCacheOnly(overworldLevel);
    }
}

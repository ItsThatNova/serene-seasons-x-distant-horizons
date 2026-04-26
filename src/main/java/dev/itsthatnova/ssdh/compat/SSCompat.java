package dev.itsthatnova.ssdh.compat;

import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import dev.itsthatnova.ssdh.SSDHClient;
import dev.itsthatnova.ssdh.texture.SeasonMetaTexture;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import sereneseasons.api.season.ISeasonState;
import sereneseasons.api.season.Season;
import sereneseasons.api.season.SeasonHelper;

/**
 * Detects Serene Seasons sub-season transitions on the client.
 *
 * Detection uses enum reference comparison (current != lastKnownSubSeason).
 * SubSeason instances are singletons so this is an identity comparison —
 * effectively free. No string allocations, no .equals() overhead.
 *
 * lastKnownSubSeason is reset to null when the player leaves the world,
 * so re-entry (rejoin or dimension return) always triggers a fresh cache
 * clear on the first tick back. The DhApiLevelLoadEvent in DHCompat handles
 * the DH-side trigger for those cases; SSCompat handles the ongoing
 * per-season-transition trigger during active play.
 */
@Environment(EnvType.CLIENT)
public class SSCompat {

    private static Season.SubSeason lastKnownSubSeason = null;

    public static void registerEvents() {
        ClientTickEvents.END_CLIENT_TICK.register(SSCompat::onEndTick);
    }

    private static void onEndTick(MinecraftClient client) {
        if (client.world == null) {
            // Reset on world exit so re-entry fires a fresh clear
            lastKnownSubSeason = null;
            SeasonMetaTexture.INSTANCE.clear();
            return;
        }

        ISeasonState seasonState;
        try {
            seasonState = SeasonHelper.getSeasonState(client.world);
        } catch (Exception e) {
            return;
        }
        if (seasonState == null) return;

        Season.SubSeason current = seasonState.getSubSeason();
        if (current == null) {
            SeasonMetaTexture.INSTANCE.clear();
            return;
        }

        SeasonMetaTexture.INSTANCE.update(current);

        if (lastKnownSubSeason == null) {
            // First tick after joining or re-entering the overworld.
            // DHCompat's level load event handles the cache clear for this case,
            // so we just record the current season without firing again.
            lastKnownSubSeason = current;
            return;
        }

        // Enum reference comparison — no allocation, no string cost
        if (current != lastKnownSubSeason) {
            Season.SubSeason previous = lastKnownSubSeason;
            lastKnownSubSeason = current;
            onSeasonChanged(current, previous);
        }
    }

    private static void onSeasonChanged(Season.SubSeason current, Season.SubSeason previous) {
        IDhApiLevelWrapper level = DHCompat.getOverworldLevel();
        if (level == null) {
            SSDHClient.LOGGER.warn("[SSDH] Season changed ({} → {}) but DH overworld level not available — skipping refresh.",
                    previous.name().toLowerCase(), current.name().toLowerCase());
            return;
        }

        SSDHClient.LOGGER.info("[SSDH] Season changed: {} → {}. Refreshing DH LOD colors.",
                previous.name().toLowerCase(), current.name().toLowerCase());

        DHCompat.scheduleSeasonChangeRefresh(level);
    }

    /** Returns the last sub-season seen by SSCompat, or null if not in a world. */
    public static Season.SubSeason getLastKnownSubSeason() {
        return lastKnownSubSeason;
    }
}

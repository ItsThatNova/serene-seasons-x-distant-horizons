package dev.itsthatnova.ssdh.compat;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.methods.events.DhApiEventRegister;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiLevelLoadEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import dev.itsthatnova.ssdh.SSDHClient;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

@Environment(EnvType.CLIENT)
public class DHCompat {

    /**
     * Ticks remaining until deferred rebuild fires. -1 means idle.
     *
     * On every level load we immediately wipe both color caches (so DH can't
     * repopulate them with stale season data during the wait), then schedule
     * the render rebuild to fire after a delay long enough for the server to
     * finish its DH handshake and for SS to sync the current season.
     *
     * We wipe the caches a second time just before firing the rebuild, because
     * DH will have been re-populating them from scratch during the wait — and
     * those new entries were computed with the now-correct season in place.
     * That second wipe is the one that actually matters for color correctness;
     * the first wipe just prevents a large stale cache from being used as a
     * warm-start when the rebuild begins.
     */
    private static int rebuildCountdown = -1;

    /**
     * 200 ticks (10s) — enough for the DH non-keyed→keyed level swap (~2s)
     * plus SS season sync from the server to arrive and for SSCompat's 100-tick
     * poll to have fired at least once with the correct season.
     */
    private static final int REBUILD_DELAY_TICKS = 200;

    public static void registerEvents() {
        DhApiEventRegister.on(DhApiLevelLoadEvent.class, new DhApiLevelLoadEvent() {
            @Override
            public void onLevelLoad(DhApiEventParam<DhApiLevelLoadEvent.EventParam> event) {
                if (!event.value.levelWrapper.getDimensionName().equals("minecraft:overworld")) {
                    return;
                }
                // Immediately clear both caches so DH doesn't use stale season
                // colors to warm-start the cache during the wait window.
                clearAllColorCaches(event.value.levelWrapper);
                SSDHClient.LOGGER.info("[SSDH] Overworld level loaded — caches wiped, rebuild in {} ticks.", REBUILD_DELAY_TICKS);
                // Reset countdown — the non-keyed→keyed double-fire resets the
                // timer so we always wait from the last (keyed) load event.
                rebuildCountdown = REBUILD_DELAY_TICKS;
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (rebuildCountdown < 0) return;
            if (client.world == null) { rebuildCountdown = -1; return; }
            rebuildCountdown--;
            if (rebuildCountdown == 0) {
                rebuildCountdown = -1;
                IDhApiLevelWrapper level = getOverworldLevel();
                if (level == null) {
                    SSDHClient.LOGGER.warn("[SSDH] Deferred rebuild: overworld level gone — skipping.");
                    return;
                }
                // Wipe again now that SS has synced the correct season, so the
                // rebuild uses fresh colors from the current season.
                SSDHClient.LOGGER.info("[SSDH] Deferred rebuild firing — wiping caches and rebuilding LODs.");
                clearAllColorCaches(level);
                triggerRenderRebuild();
            }
        });
    }

    /**
     * Called on season change: clear both color caches, no forced rebuild.
     * LOD sections recolor naturally as DH rebuilds them on demand while
     * the player moves around.
     */
    public static void clearColorCacheOnly(IDhApiLevelWrapper level) {
        clearAllColorCaches(level);
    }

    /**
     * Clears both of DH's color caches.
     *
     * Cache 1 — ClientLevelWrapper.blockColorCacheByBlockState (instance):
     *   Maps BlockState → base untinted texture color.
     *   Cleared via clearBlockColorCache() on the level object itself.
     *   NOTE: do NOT use getWrappedMcObject() — that returns the raw MC
     *   ClientLevel, not DH's wrapper; clearBlockColorCache() doesn't exist there.
     *
     * Cache 2 — AbstractDhTintGetter.COLOR_BY_BLOCK_BIOME_PAIR (static):
     *   Maps (BlockState, Biome) → final tinted ARGB color.
     *   THIS is where seasonal foliage/grass tints are stored. SS hooks
     *   BlockColors.getColor(), which populates this cache. Clearing it forces
     *   DH to re-query through SS with the current season's tint on next use.
     *   clearBlockColorCache() does NOT touch this map — previous builds were
     *   clearing only Cache 1, which is why colors never changed.
     */
    public static void clearAllColorCaches(IDhApiLevelWrapper level) {
        clearBlockColorCache(level);
        clearTintColorCache();
    }

    private static void clearBlockColorCache(IDhApiLevelWrapper level) {
        try {
            Method m = level.getClass().getMethod("clearBlockColorCache");
            m.invoke(level);
            SSDHClient.LOGGER.info("[SSDH] Block color cache cleared.");
        } catch (NoSuchMethodException e) {
            SSDHClient.LOGGER.warn("[SSDH] clearBlockColorCache not found on {} — DH version mismatch?",
                    level.getClass().getName());
        } catch (Exception e) {
            SSDHClient.LOGGER.error("[SSDH] Failed to clear block color cache: {}", e.getMessage());
        }
    }

    private static void clearTintColorCache() {
        String[] candidates = {
            "loaderCommon.fabric.com.seibel.distanthorizons.common.wrappers.block.AbstractDhTintGetter",
            "loaderCommon.neoforge.com.seibel.distanthorizons.common.wrappers.block.AbstractDhTintGetter",
        };
        for (String className : candidates) {
            try {
                Class<?> clazz = Class.forName(className);
                Field f = clazz.getDeclaredField("COLOR_BY_BLOCK_BIOME_PAIR");
                f.setAccessible(true);
                ConcurrentHashMap<?, ?> cache = (ConcurrentHashMap<?, ?>) f.get(null);
                int before = cache.size();
                cache.clear();
                SSDHClient.LOGGER.info("[SSDH] Tint color cache cleared ({} entries).", before);
                return;
            } catch (ClassNotFoundException ignored) {
            } catch (Exception e) {
                SSDHClient.LOGGER.error("[SSDH] Failed to clear tint cache from {}: {}", className, e.getMessage());
                return;
            }
        }
        SSDHClient.LOGGER.warn("[SSDH] AbstractDhTintGetter not found — DH version mismatch?");
    }

    public static void triggerRenderRebuild() {
        try {
            if (DhApi.Delayed.renderProxy == null) {
                SSDHClient.LOGGER.warn("[SSDH] renderProxy not available — skipping rebuild.");
                return;
            }
            DhApi.Delayed.renderProxy.clearRenderDataCache();
            SSDHClient.LOGGER.info("[SSDH] LOD render rebuild triggered.");
        } catch (Exception e) {
            SSDHClient.LOGGER.error("[SSDH] Failed to trigger LOD rebuild: {}", e.getMessage());
        }
    }

    public static IDhApiLevelWrapper getOverworldLevel() {
        try {
            if (DhApi.Delayed.worldProxy == null) return null;
            for (IDhApiLevelWrapper level : DhApi.Delayed.worldProxy.getAllLoadedLevelWrappers()) {
                if (level.getDimensionName().equals("minecraft:overworld")) {
                    return level;
                }
            }
        } catch (Exception e) {
            SSDHClient.LOGGER.debug("[SSDH] Could not get overworld level: {}", e.getMessage());
        }
        return null;
    }
}

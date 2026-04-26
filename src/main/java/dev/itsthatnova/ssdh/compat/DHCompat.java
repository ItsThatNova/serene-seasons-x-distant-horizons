package dev.itsthatnova.ssdh.compat;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiRenderProxy;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiWorldProxy;
import com.seibel.distanthorizons.api.methods.events.DhApiEventRegister;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiLevelLoadEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import dev.itsthatnova.ssdh.SSDHClient;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages all Distant Horizons interaction.
 *
 * Design goal:
 *   - Use DH core internals directly where DH already exposes stable-ish interfaces
 *     (ILevelWrapper, IClientLevelWrapper, DhSectionPos).
 *   - Reserve reflection only for truly private internals (static tint caches)
 *     and the non-interface soft-reload hook DhClientLevel.reloadPos(long).
 *
 * Timing policy:
 *   - Live season change: clear caches immediately, then schedule a single delayed refresh.
 *   - World load/rejoin: arm a longer delayed refresh; do not refresh immediately.
 *   - Path B fallback: single delayed hard rebuild only.
 */
@Environment(EnvType.CLIENT)
public class DHCompat {

    /** Whether Path C (soft reload) is currently available. */
    private static boolean softReloadAvailable = false;

    /** AbstractDhTintGetter.COLOR_BY_BLOCK_BIOME_PAIR — static ConcurrentHashMap */
    private static Field tintCacheField = null;

    /** AbstractDhTintGetter.BIOME_BY_RESOURCE_STRING — static ConcurrentHashMap */
    private static Field biomeCacheField = null;

    /** DhClientLevel.reloadPos(long) — reflected for looser compatibility across DH versions */
    private static Method reloadPosMethod = null;

    /** Chunk-sized DH section detail level. */
    private static byte sectionChunkDetailLevel = DhSectionPos.SECTION_CHUNK_DETAIL_LEVEL;

    private enum RefreshReason {
        WORLD_LOAD,
        SEASON_CHANGE
    }

    private static long refreshGeneration = 0L;
    private static long scheduledGeneration = 0L;
    private static int scheduledTicksRemaining = -1;
    private static RefreshReason scheduledReason = null;

    private static final int SOFT_DELAY_SEASON_TICKS = 12;
    private static final int SOFT_DELAY_WORLD_LOAD_TICKS = 50;
    private static final int HARD_DELAY_TICKS = 35;

    public static void registerEvents() {
        resolveReflectionTargets();
        ClientTickEvents.END_CLIENT_TICK.register(DHCompat::onEndClientTick);

        DhApiEventRegister.on(DhApiLevelLoadEvent.class, new DhApiLevelLoadEvent() {
            @Override
            public void onLevelLoad(DhApiEventParam<DhApiLevelLoadEvent.EventParam> eventParam) {
                if (eventParam.value == null) return;
                IDhApiLevelWrapper level = eventParam.value.levelWrapper;
                if (level == null) return;
                if (!"minecraft:overworld".equals(level.getDimensionName())) return;

                SSDHClient.LOGGER.info("[SSDH] DH overworld loaded — arming delayed LOD color refresh.");
                scheduleDelayedRefresh(level, RefreshReason.WORLD_LOAD);
            }
        });
    }

    private static void onEndClientTick(MinecraftClient client) {
        if (client.world == null) {
            scheduledTicksRemaining = -1;
            scheduledReason = null;
            return;
        }

        if (scheduledTicksRemaining < 0 || scheduledReason == null) return;

        scheduledTicksRemaining--;
        if (scheduledTicksRemaining > 0) return;

        long generationToRun = scheduledGeneration;
        RefreshReason reasonToRun = scheduledReason;
        scheduledTicksRemaining = -1;
        scheduledReason = null;

        executeScheduledRefresh(generationToRun, reasonToRun);
    }

    private static void resolveReflectionTargets() {
        resolveTintCacheFields();
        resolveReloadPosMethod();

        softReloadAvailable = reloadPosMethod != null;
        if (softReloadAvailable) {
            SSDHClient.LOGGER.info("[SSDH] Soft reload (Path C) available — LOD refresh will be seamless, no ring effect.");
        } else {
            SSDHClient.LOGGER.warn("[SSDH] Soft reload (Path C) unavailable — falling back to full rebuild (Path B). " +
                    "LOD refresh will produce a brief ring effect. See README for details.");
        }
    }

    private static void resolveTintCacheFields() {
        String[] tintGetterCandidates = {
                "loaderCommon.fabric.com.seibel.distanthorizons.common.wrappers.block.AbstractDhTintGetter",
                "loaderCommon.neoforge.com.seibel.distanthorizons.common.wrappers.block.AbstractDhTintGetter"
        };

        for (String className : tintGetterCandidates) {
            try {
                Class<?> clazz = Class.forName(className);

                if (tintCacheField == null) {
                    try {
                        tintCacheField = clazz.getDeclaredField("COLOR_BY_BLOCK_BIOME_PAIR");
                        tintCacheField.setAccessible(true);
                        SSDHClient.LOGGER.info("[SSDH] ✓ Resolved tint cache via {}", className);
                    } catch (NoSuchFieldException e) {
                        SSDHClient.LOGGER.warn("[SSDH] ✗ COLOR_BY_BLOCK_BIOME_PAIR not found on {} — tint cache will not be cleared.", className);
                    }
                }

                if (biomeCacheField == null) {
                    try {
                        biomeCacheField = clazz.getDeclaredField("BIOME_BY_RESOURCE_STRING");
                        biomeCacheField.setAccessible(true);
                        SSDHClient.LOGGER.info("[SSDH] ✓ Resolved biome cache via {}", className);
                    } catch (NoSuchFieldException e) {
                        SSDHClient.LOGGER.warn("[SSDH] ✗ BIOME_BY_RESOURCE_STRING not found on {} — biome cache will not be cleared.", className);
                    }
                }

                if (tintCacheField != null || biomeCacheField != null) {
                    break;
                }
            } catch (ClassNotFoundException ignored) {
            }
        }

        if (tintCacheField == null) {
            SSDHClient.LOGGER.warn("[SSDH] ✗ AbstractDhTintGetter.COLOR_BY_BLOCK_BIOME_PAIR could not be resolved — tint cache will not be cleared.");
        }
        if (biomeCacheField == null) {
            SSDHClient.LOGGER.warn("[SSDH] ✗ AbstractDhTintGetter.BIOME_BY_RESOURCE_STRING could not be resolved — biome cache will not be cleared.");
        }
    }

    private static void resolveReloadPosMethod() {
        try {
            Class<?> dhClientLevelClass = Class.forName("com.seibel.distanthorizons.core.level.DhClientLevel");
            reloadPosMethod = dhClientLevelClass.getMethod("reloadPos", long.class);
            reloadPosMethod.setAccessible(true);
            SSDHClient.LOGGER.info("[SSDH] ✓ Resolved DhClientLevel.reloadPos(long)");
        } catch (ClassNotFoundException e) {
            SSDHClient.LOGGER.warn("[SSDH] ✗ DhClientLevel not found — soft reload (Path C) unavailable. Falling back to Path B.");
        } catch (NoSuchMethodException e) {
            SSDHClient.LOGGER.warn("[SSDH] ✗ DhClientLevel.reloadPos(long) not found — soft reload (Path C) unavailable. Falling back to Path B.");
        } catch (Exception e) {
            SSDHClient.LOGGER.warn("[SSDH] ✗ Could not resolve reloadPos(long): {}", e.getMessage());
        }
    }

    /** Immediate/manual refresh entry point. */
    public static void onRefreshNeeded(IDhApiLevelWrapper level) {
        clearDhTintCaches();
        clearBlockColorCache(level);

        if (softReloadAvailable) {
            if (!trySoftReload(level)) {
                SSDHClient.LOGGER.warn("[SSDH] Soft reload failed at runtime — falling back to full rebuild.");
                triggerHardRebuild();
            }
        } else {
            triggerHardRebuild();
        }
    }

    public static void scheduleSeasonChangeRefresh(IDhApiLevelWrapper level) {
        scheduleDelayedRefresh(level, RefreshReason.SEASON_CHANGE);
    }

    private static void scheduleDelayedRefresh(IDhApiLevelWrapper level, RefreshReason reason) {
        refreshGeneration++;
        scheduledGeneration = refreshGeneration;
        scheduledReason = reason;
        scheduledTicksRemaining = getDelayTicksFor(reason);

        // Immediate invisible clear to flush stale process-local state as soon as the event is observed.
        clearDhTintCaches();
        clearBlockColorCache(level);

        SSDHClient.LOGGER.info("[SSDH] Scheduled {} refresh in {} ticks (generation={}).",
                reason.name().toLowerCase(), scheduledTicksRemaining, scheduledGeneration);
    }

    private static int getDelayTicksFor(RefreshReason reason) {
        if (!softReloadAvailable) return HARD_DELAY_TICKS;
        return reason == RefreshReason.WORLD_LOAD ? SOFT_DELAY_WORLD_LOAD_TICKS : SOFT_DELAY_SEASON_TICKS;
    }

    private static void executeScheduledRefresh(long generation, RefreshReason reason) {
        if (generation != refreshGeneration) {
            SSDHClient.LOGGER.debug("[SSDH] Skipping stale scheduled refresh (generation={} current={}).", generation, refreshGeneration);
            return;
        }

        IDhApiLevelWrapper level = getOverworldLevel();
        if (level == null) {
            SSDHClient.LOGGER.warn("[SSDH] Scheduled {} refresh fired, but DH overworld level was unavailable.", reason.name().toLowerCase());
            return;
        }

        SSDHClient.LOGGER.info("[SSDH] Executing delayed {} refresh (generation={}).",
                reason.name().toLowerCase(), generation);

        // Second invisible clear immediately before the single visible refresh.
        clearDhTintCaches();
        clearBlockColorCache(level);

        if (softReloadAvailable) {
            if (!trySoftReload(level)) {
                SSDHClient.LOGGER.warn("[SSDH] Delayed soft reload failed at runtime — falling back to full rebuild.");
                triggerHardRebuild();
            }
        } else {
            triggerHardRebuild();
        }
    }

    private static void clearDhTintCaches() {
        clearConcurrentMapField(tintCacheField, "Tint cache");
        clearConcurrentMapField(biomeCacheField, "Biome cache");
    }

    private static void clearConcurrentMapField(Field field, String label) {
        if (field == null) return;
        try {
            Object cache = field.get(null);
            if (cache instanceof ConcurrentHashMap<?, ?> map) {
                int count = map.size();
                map.clear();
                SSDHClient.LOGGER.info("[SSDH] {} cleared ({} entries).", label, count);
            }
        } catch (Exception e) {
            SSDHClient.LOGGER.error("[SSDH] Failed to clear {}: {}", label.toLowerCase(), e.getMessage());
        }
    }

    private static void clearBlockColorCache(IDhApiLevelWrapper level) {
        if (!(level instanceof IClientLevelWrapper clientLevelWrapper)) {
            SSDHClient.LOGGER.warn("[SSDH] Level wrapper is not an IClientLevelWrapper — block color cache was not cleared.");
            return;
        }

        try {
            clientLevelWrapper.clearBlockColorCache();
            SSDHClient.LOGGER.info("[SSDH] Block color cache cleared.");
        } catch (Exception e) {
            SSDHClient.LOGGER.error("[SSDH] Failed to clear block color cache: {}", e.getMessage());
        }
    }

    /**
     * Queues each section in the player's render range for an in-place reload.
     * DH keeps the old GPU buffer visible until the new one is ready, then swaps.
     */
    private static boolean trySoftReload(IDhApiLevelWrapper level) {
        try {
            if (!(level instanceof ILevelWrapper levelWrapper)) {
                SSDHClient.LOGGER.warn("[SSDH] Level wrapper is not an ILevelWrapper — soft reload skipped.");
                return false;
            }

            IDhLevel dhLevel = levelWrapper.getDhLevel();
            if (dhLevel == null) {
                SSDHClient.LOGGER.warn("[SSDH] getDhLevel() returned null — soft reload skipped.");
                return false;
            }

            if (reloadPosMethod == null || !reloadPosMethod.getDeclaringClass().isInstance(dhLevel)) {
                SSDHClient.LOGGER.warn("[SSDH] Reflected reloadPos target does not match current DH level type ({}) — soft reload skipped.",
                        dhLevel.getClass().getName());
                return false;
            }

            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null) {
                SSDHClient.LOGGER.warn("[SSDH] No player — soft reload skipped.");
                return false;
            }

            int playerChunkX = client.player.getChunkPos().x;
            int playerChunkZ = client.player.getChunkPos().z;

            // Preserve the base behavior for now; timing experiment only.
            int radius = 64;
            int count = 0;
            for (int cx = playerChunkX - radius; cx <= playerChunkX + radius; cx++) {
                for (int cz = playerChunkZ - radius; cz <= playerChunkZ + radius; cz++) {
                    long sectionPos = DhSectionPos.encode(sectionChunkDetailLevel, cx, cz);
                    reloadPosMethod.invoke(dhLevel, sectionPos);
                    count++;
                }
            }

            SSDHClient.LOGGER.info("[SSDH] Soft reload queued for {} sections (radius={} chunks).", count, radius);
            return true;
        } catch (Exception e) {
            SSDHClient.LOGGER.error("[SSDH] Soft reload error: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Destroys all GPU buffers and DH rebuilds from player outward.
     */
    private static void triggerHardRebuild() {
        try {
            IDhApiRenderProxy renderProxy = DhApi.Delayed.renderProxy;
            if (renderProxy == null) {
                SSDHClient.LOGGER.warn("[SSDH] Render proxy not available — skipping rebuild. Colors will update as DH naturally rebuilds sections.");
                return;
            }
            renderProxy.clearRenderDataCache();
            SSDHClient.LOGGER.info("[SSDH] Full LOD rebuild triggered (Path B).");
        } catch (Exception e) {
            SSDHClient.LOGGER.error("[SSDH] Failed to trigger rebuild: {}", e.getMessage());
        }
    }

    /**
     * Returns the DH level wrapper for the overworld, or null if not available.
     * Used by SSCompat and the manual reload command.
     */
    public static IDhApiLevelWrapper getOverworldLevel() {
        try {
            IDhApiWorldProxy worldProxy = DhApi.Delayed.worldProxy;
            if (worldProxy == null) return null;
            for (IDhApiLevelWrapper level : worldProxy.getAllLoadedLevelWrappers()) {
                if ("minecraft:overworld".equals(level.getDimensionName())) return level;
            }
        } catch (Exception e) {
            SSDHClient.LOGGER.debug("[SSDH] Could not get overworld level: {}", e.getMessage());
        }
        return null;
    }

    public static boolean isSoftReloadAvailable() {
        return softReloadAvailable;
    }
}

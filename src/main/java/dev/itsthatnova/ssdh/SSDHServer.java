package dev.itsthatnova.ssdh;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.world.World;
import sereneseasons.api.season.ISeasonState;
import sereneseasons.api.season.Season;
import sereneseasons.api.season.SeasonHelper;
import sereneseasons.init.ModAPI;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class SSDHServer implements ModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("ssdh");

    @Override
    public void onInitialize() {
        LOGGER.info("[SSDH] Common initializing...");
        registerServerCommands();
        LOGGER.info("[SSDH] Common initialized.");
    }

    private static void registerServerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(
                CommandManager.literal("ssdh")
                    .requires(source -> source.hasPermissionLevel(2))
                    .then(CommandManager.literal("season")
                        .then(CommandManager.argument("subseason", StringArgumentType.word())
                            .suggests((ctx, builder) -> {
                                for (Season.SubSeason s : Season.SubSeason.values()) {
                                    builder.suggest(s.name().toLowerCase());
                                }
                                return builder.buildFuture();
                            })
                            .executes(ctx -> {
                                String input = StringArgumentType.getString(ctx, "subseason").toUpperCase();
                                Season.SubSeason target;
                                try {
                                    target = Season.SubSeason.valueOf(input);
                                } catch (IllegalArgumentException e) {
                                    ctx.getSource().sendFeedback(
                                        () -> Text.literal("[SSDH] Unknown sub-season: '" + input + "'. Valid: " + buildSubSeasonList()),
                                        false
                                    );
                                    return 0;
                                }
                                return setServerSeason(ctx.getSource(), target);
                            })))
            )
        );
    }

    /**
     * Sets the season by directly writing to SS's SeasonSavedData via reflection,
     * then calls sendSeasonUpdate to sync to all clients.
     *
     * Reflection targets (sereneseasons 10.x for 1.21.1):
     *   ModAPI.SEASON_HANDLER          -> SeasonHandler instance
     *   SeasonHandler.getSeasonSavedData(ServerLevel) -> SeasonSavedData
     *   SeasonSavedData.seasonCycleTicks               -> int field to set
     *   SeasonHandler.sendSeasonUpdate(ServerLevel)    -> syncs to clients
     */
    private static int setServerSeason(ServerCommandSource source, Season.SubSeason target) {
        try {
            ServerWorld overworld = source.getServer().getWorld(World.OVERWORLD);
            if (overworld == null) {
                source.sendFeedback(() -> Text.literal("[SSDH] Overworld not loaded."), false);
                return 0;
            }

            // Read current state for tick math
            ISeasonState state = SeasonHelper.getSeasonState(overworld);
            if (state == null) {
                source.sendFeedback(() -> Text.literal("[SSDH] Serene Seasons state unavailable."), false);
                return 0;
            }

            int targetTick = target.ordinal() * state.getSubSeasonDuration();

            // Step 1: get ModAPI.SEASON_HANDLER (static field)
            Field handlerField = ModAPI.class.getDeclaredField("SEASON_HANDLER");
            handlerField.setAccessible(true);
            Object seasonHandler = handlerField.get(null);

            // Step 2: find and call getSeasonSavedData(Level) by name scan
            // (takes net.minecraft.world.level.Level / class_1937 - a supertype of ServerWorld)
            Method getSeasonSavedDataMethod = null;
            for (Method m : seasonHandler.getClass().getDeclaredMethods()) {
                if (m.getName().equals("getSeasonSavedData") && m.getParameterCount() == 1) {
                    getSeasonSavedDataMethod = m;
                    break;
                }
            }

            if (getSeasonSavedDataMethod == null) {
                source.sendFeedback(() -> Text.literal(
                    "[SSDH] Could not find getSeasonSavedData — SS version mismatch?"), false);
                return 0;
            }

            getSeasonSavedDataMethod.setAccessible(true);
            Object savedData = getSeasonSavedDataMethod.invoke(seasonHandler, overworld);

            // Step 3: set seasonCycleTicks
            Field ticksField = savedData.getClass().getDeclaredField("seasonCycleTicks");
            ticksField.setAccessible(true);
            ticksField.setInt(savedData, targetTick);

            // Step 4: mark dirty so it persists on next save.
            // SeasonSavedData extends net/minecraft/class_18 (PersistentState in yarn mappings).
            // markDirty() is inherited from PersistentState, but SS is compiled with intermediary
            // names so it appears as "method_80" at runtime, not "markDirty".
            // We walk the full method list of the object looking for either name (no-arg, void)
            // so this works regardless of whether Fabric has remapped the SS jar or not.
            {
                Method markDirtyMethod = null;
                Class<?> klass = savedData.getClass();
                outer:
                while (klass != null) {
                    for (Method m : klass.getDeclaredMethods()) {
                        if (m.getParameterCount() == 0
                                && m.getReturnType() == void.class
                                && (m.getName().equals("markDirty") || m.getName().equals("method_80"))) {
                            markDirtyMethod = m;
                            break outer;
                        }
                    }
                    klass = klass.getSuperclass();
                }
                if (markDirtyMethod != null) {
                    markDirtyMethod.setAccessible(true);
                    markDirtyMethod.invoke(savedData);
                } else {
                    LOGGER.warn("[SSDH] Could not find markDirty/method_80 on PersistentState — season change may not persist across restarts.");
                }
            }

            // Step 5: sync to all clients
            Method sendUpdate = null;
            for (Method m : seasonHandler.getClass().getDeclaredMethods()) {
                if (m.getName().equals("sendSeasonUpdate") && m.getParameterCount() == 1) {
                    sendUpdate = m;
                    break;
                }
            }

            if (sendUpdate != null) {
                sendUpdate.setAccessible(true);
                sendUpdate.invoke(seasonHandler, overworld);
            } else {
                LOGGER.warn("[SSDH] sendSeasonUpdate not found - season set but clients may not sync until next tick.");
            }

            int finalTargetTick = targetTick;
            source.sendFeedback(() -> Text.literal(
                "[SSDH] Season set to " + target.name() + " (cycle tick: " + finalTargetTick + ")"), true);
            LOGGER.info("[SSDH] Season forcibly set to {} (tick {})", target.name(), targetTick);
            return 1;

        } catch (NoSuchFieldException e) {
            LOGGER.error("[SSDH] Reflection target not found - SS version mismatch: {}", e.getMessage());
            source.sendFeedback(() -> Text.literal(
                "[SSDH] Failed: SS internals not found (" + e.getMessage() + "). SS version mismatch?"), false);
            return 0;
        } catch (Exception e) {
            LOGGER.error("[SSDH] Failed to set season: {}", e.getMessage());
            source.sendFeedback(() -> Text.literal("[SSDH] Failed: " + e.getMessage()), false);
            return 0;
        }
    }

    private static String buildSubSeasonList() {
        StringBuilder sb = new StringBuilder();
        Season.SubSeason[] values = Season.SubSeason.values();
        for (int i = 0; i < values.length; i++) {
            sb.append(values[i].name().toLowerCase());
            if (i < values.length - 1) sb.append(", ");
        }
        return sb.toString();
    }
}

package dev.itsthatnova.ssdh;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sereneseasons.api.season.ISeasonState;
import sereneseasons.api.season.Season;
import sereneseasons.api.season.SeasonHelper;
import sereneseasons.init.ModAPI;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Server-side entrypoint. Registers /ssdh season <subseason> for ops.
 *
 * Reflection is used only for SS internals that have no public API equivalent
 * (seasonCycleTicks, getSeasonSavedData). All failures produce clear feedback.
 */
public class SSDHServer implements ModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("ssdh");

    @Override
    public void onInitialize() {
        LOGGER.info("[SSDH] Common initializing...");
        registerServerCommands();
        LOGGER.info("[SSDH] Common initialized.");
    }

    private static void registerServerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(CommandManager.literal("ssdh")
                .requires(source -> source.hasPermissionLevel(2))
                .then(CommandManager.literal("season")
                    .then(CommandManager.argument("subseason", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            for (Season.SubSeason s : Season.SubSeason.values()) {
                                builder.suggest(s.name().toLowerCase());
                            }
                            return builder.buildFuture();
                        })
                        .executes(ctx -> setServerSeason(
                                ctx.getSource(),
                                StringArgumentType.getString(ctx, "subseason")))))));
    }

    private static int setServerSeason(net.minecraft.server.command.ServerCommandSource source,
                                       String subSeasonName) {
        Season.SubSeason target;
        try {
            target = Season.SubSeason.valueOf(subSeasonName.toUpperCase());
        } catch (IllegalArgumentException e) {
            String valid = Arrays.stream(Season.SubSeason.values())
                    .map(s -> s.name().toLowerCase())
                    .collect(Collectors.joining(", "));
            source.sendFeedback(() -> Text.literal("[SSDH] Unknown sub-season: '" + subSeasonName
                    + "'. Valid: " + valid), false);
            return 0;
        }

        ServerWorld overworld = source.getServer().getWorld(World.OVERWORLD);
        if (overworld == null) {
            source.sendFeedback(() -> Text.literal("[SSDH] Overworld not loaded."), false);
            return 0;
        }

        ISeasonState state;
        try {
            state = SeasonHelper.getSeasonState(overworld);
        } catch (Exception e) {
            source.sendFeedback(() -> Text.literal("[SSDH] Serene Seasons state unavailable."), false);
            return 0;
        }
        if (state == null) {
            source.sendFeedback(() -> Text.literal("[SSDH] Serene Seasons state unavailable."), false);
            return 0;
        }

        int targetTick = target.ordinal() * state.getSubSeasonDuration();

        try {
            Field handlerField = ModAPI.class.getDeclaredField("SEASON_HANDLER");
            handlerField.setAccessible(true);
            Object seasonHandler = handlerField.get(null);
            if (seasonHandler == null) {
                source.sendFeedback(() -> Text.literal("[SSDH] Season handler is null."), false);
                return 0;
            }

            Object savedData = getSeasonSavedData(seasonHandler, overworld);
            if (savedData == null) {
                source.sendFeedback(() -> Text.literal("[SSDH] Could not retrieve season saved data — SS version mismatch?"), false);
                return 0;
            }

            Field ticksField = savedData.getClass().getDeclaredField("seasonCycleTicks");
            ticksField.setAccessible(true);
            ticksField.setInt(savedData, targetTick);

            markDirty(savedData);
            sendSeasonUpdate(seasonHandler, overworld);

            LOGGER.info("[SSDH] Season set to {} (tick {})", target.name(), targetTick);
            final int tick = targetTick;
            source.sendFeedback(() -> Text.literal(
                    "[SSDH] Season set to " + target.name().toLowerCase()
                    + " (cycle tick: " + tick + ")"), true);
            return 1;

        } catch (NoSuchFieldException e) {
            source.sendFeedback(() -> Text.literal(
                    "[SSDH] SS internals not found (" + e.getMessage()
                    + "). SS version mismatch?"), false);
            return 0;
        } catch (Exception e) {
            source.sendFeedback(() -> Text.literal("[SSDH] Failed to set season: " + e.getMessage()), false);
            return 0;
        }
    }

    // -------------------------------------------------------------------------
    // SS internal helpers
    // -------------------------------------------------------------------------

    private static Object getSeasonSavedData(Object seasonHandler, ServerWorld world) {
        for (Method m : getAllMethods(seasonHandler.getClass())) {
            if ("getSeasonSavedData".equals(m.getName()) && m.getParameterCount() == 1) {
                m.setAccessible(true);
                try {
                    return m.invoke(seasonHandler, world);
                } catch (Exception e) {
                    LOGGER.warn("[SSDH] getSeasonSavedData invocation failed: {}", e.getMessage());
                    return null;
                }
            }
        }
        LOGGER.warn("[SSDH] Could not find getSeasonSavedData — SS version mismatch?");
        return null;
    }

    private static void markDirty(Object savedData) {
        for (Method m : getAllMethods(savedData.getClass())) {
            if (("markDirty".equals(m.getName()) || "method_80".equals(m.getName()))
                    && m.getParameterCount() == 0
                    && m.getReturnType() == Void.TYPE) {
                try {
                    m.setAccessible(true);
                    m.invoke(savedData);
                    return;
                } catch (Exception ignored) {}
            }
        }
        LOGGER.warn("[SSDH] markDirty not found — season change may not persist across restarts.");
    }

    private static void sendSeasonUpdate(Object seasonHandler, ServerWorld world) {
        for (Method m : getAllMethods(seasonHandler.getClass())) {
            if ("sendSeasonUpdate".equals(m.getName()) && m.getParameterCount() == 1) {
                try {
                    m.setAccessible(true);
                    m.invoke(seasonHandler, world);
                    return;
                } catch (Exception ignored) {}
            }
        }
        LOGGER.warn("[SSDH] sendSeasonUpdate not found — clients may not sync until next tick.");
    }

    private static List<Method> getAllMethods(Class<?> clazz) {
        List<Method> methods = new ArrayList<>();
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            methods.addAll(Arrays.asList(current.getDeclaredMethods()));
            current = current.getSuperclass();
        }
        return methods;
    }
}

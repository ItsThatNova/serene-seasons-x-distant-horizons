package dev.itsthatnova.ssdh;

import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import dev.itsthatnova.ssdh.compat.DHCompat;
import dev.itsthatnova.ssdh.compat.SSCompat;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import sereneseasons.api.season.ISeasonState;
import sereneseasons.api.season.Season;
import sereneseasons.api.season.SeasonHelper;

/**
 * Client-side commands:
 *   /ssdh-client status  — season info, DH state, and which reload path is active
 *   /ssdh-client skip    — ticks until next sub-season, server command to skip
 *   /ssdh-client reload  — manually trigger LOD color refresh
 */
@Environment(EnvType.CLIENT)
public class SSDHCommands {

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(ClientCommandManager.literal("ssdh-client")
                .then(ClientCommandManager.literal("status")
                    .executes(ctx -> executeStatus(ctx.getSource())))
                .then(ClientCommandManager.literal("skip")
                    .executes(ctx -> executeSkip(ctx.getSource())))
                .then(ClientCommandManager.literal("reload")
                    .executes(ctx -> executeReload(ctx.getSource())))));
    }

    private static int executeStatus(FabricClientCommandSource source) {
        MinecraftClient client = source.getClient();
        if (client.world == null) {
            source.sendFeedback(Text.literal("[SSDH] Not in a world."));
            return 0;
        }

        ISeasonState state;
        try {
            state = SeasonHelper.getSeasonState(client.world);
        } catch (Exception e) {
            source.sendFeedback(Text.literal("[SSDH] Serene Seasons state unavailable."));
            return 0;
        }
        if (state == null) {
            source.sendFeedback(Text.literal("[SSDH] Serene Seasons state unavailable."));
            return 0;
        }

        Season.SubSeason subSeason = state.getSubSeason();
        Season season = state.getSeason();
        int cycleTicks = state.getSeasonCycleTicks();
        int subSeasonDuration = state.getSubSeasonDuration();
        int cycleDuration = state.getCycleDuration();
        int day = state.getDay();

        Season.SubSeason[] subSeasons = Season.SubSeason.values();
        int idx = subSeason.ordinal();
        int ticksIntoSubSeason = cycleTicks - (idx * subSeasonDuration);
        int ticksUntilNext = subSeasonDuration - ticksIntoSubSeason;
        Season.SubSeason next = subSeasons[(idx + 1) % subSeasons.length];

        IDhApiLevelWrapper overworldLevel = DHCompat.getOverworldLevel();
        String reloadPath = DHCompat.isSoftReloadAvailable() ? "C (soft — no ring)" : "B (hard rebuild — ring effect)";

        source.sendFeedback(Text.literal(
                "[SSDH] ===== Status =====" +
                "\nSeason:          " + season.name().toLowerCase() + " (" + subSeason.name().toLowerCase() + ")" +
                "\nDay:             " + day +
                "\nCycle ticks:     " + cycleTicks + " / " + cycleDuration +
                "\nSub-season:      " + ticksIntoSubSeason + " / " + subSeasonDuration + " ticks elapsed" +
                "\nNext sub-season: " + next.name().toLowerCase() + " in " + ticksUntilNext + " ticks" +
                "\n>> /ssdh season " + next.name().toLowerCase() + "  (server command, requires op)" +
                "\nDH overworld:    " + (overworldLevel != null ? "ready" : "not loaded") +
                "\nReload path:     " + reloadPath
        ));
        return 1;
    }

    private static int executeSkip(FabricClientCommandSource source) {
        MinecraftClient client = source.getClient();
        if (client.world == null) {
            source.sendFeedback(Text.literal("[SSDH] Not in a world."));
            return 0;
        }

        ISeasonState state;
        try {
            state = SeasonHelper.getSeasonState(client.world);
        } catch (Exception e) {
            source.sendFeedback(Text.literal("[SSDH] Serene Seasons state unavailable."));
            return 0;
        }
        if (state == null) {
            source.sendFeedback(Text.literal("[SSDH] Serene Seasons state unavailable."));
            return 0;
        }

        Season.SubSeason[] subSeasons = Season.SubSeason.values();
        Season.SubSeason current = state.getSubSeason();
        Season.SubSeason next = subSeasons[(current.ordinal() + 1) % subSeasons.length];
        int ticksUntil = state.getSubSeasonDuration()
                - (state.getSeasonCycleTicks() - current.ordinal() * state.getSubSeasonDuration());

        source.sendFeedback(Text.literal(
                "[SSDH] Next sub-season: " + next.name().toLowerCase() + " in " + ticksUntil + " ticks." +
                "\nRun in chat: /ssdh season " + next.name().toLowerCase() + "  (requires op)"
        ));
        return 1;
    }

    private static int executeReload(FabricClientCommandSource source) {
        IDhApiLevelWrapper level = DHCompat.getOverworldLevel();
        if (level == null) {
            source.sendFeedback(Text.literal("[SSDH] DH overworld level not available."));
            return 0;
        }

        String path = DHCompat.isSoftReloadAvailable() ? "Path C (soft reload)" : "Path B (full rebuild)";
        source.sendFeedback(Text.literal("[SSDH] Triggering LOD color refresh via " + path + "..."));
        DHCompat.onRefreshNeeded(level);
        source.sendFeedback(Text.literal("[SSDH] Refresh queued. LODs will update over the next few seconds."));
        return 1;
    }
}

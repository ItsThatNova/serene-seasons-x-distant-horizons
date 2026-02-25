package dev.itsthatnova.ssdh;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import dev.itsthatnova.ssdh.compat.DHCompat;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;
import sereneseasons.api.season.ISeasonState;
import sereneseasons.api.season.Season;
import sereneseasons.api.season.SeasonHelper;

@Environment(EnvType.CLIENT)
public class SSDHCommands {

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            // IMPORTANT: Do NOT register anything under "ssdh" here.
            //
            // The server also registers /ssdh via CommandRegistrationCallback and syncs its command
            // tree to the client. Fabric's client command API intercepts ANY /ssdh command before it
            // reaches the server if a client-side "ssdh" literal exists — even if the specific
            // subcommand (e.g. "season") is not registered on the client side. This causes
            // "Incorrect argument for command at position 5" because Brigadier fails to match
            // "season" against the client-only subtree (status/skip/reload) and never forwards
            // the command to the server.
            //
            // Solution: client-only utility commands live under a separate root literal "ssdh-client"
            // so they never collide with the server-side /ssdh tree.
            dispatcher.register(
                ClientCommandManager.literal("ssdh-client")
                    .then(ClientCommandManager.literal("status")
                        .executes(SSDHCommands::executeStatus))
                    .then(ClientCommandManager.literal("skip")
                        .executes(SSDHCommands::executeSkip))
                    .then(ClientCommandManager.literal("reload")
                        .executes(SSDHCommands::executeReload))
            );
        });
    }

    /**
     * /ssdh-client status
     * Prints current season, day, tick position, and how many ticks until next sub-season.
     */
    private static int executeStatus(CommandContext<FabricClientCommandSource> ctx) {
        var client = ctx.getSource().getClient();
        if (client.world == null) {
            ctx.getSource().sendFeedback(Text.literal("[SSDH] Not in a world."));
            return 0;
        }

        ISeasonState state = SeasonHelper.getSeasonState(client.world);
        if (state == null) {
            ctx.getSource().sendFeedback(Text.literal("[SSDH] Serene Seasons state unavailable (wrong dimension?)."));
            return 0;
        }

        Season.SubSeason subSeason = state.getSubSeason();
        Season season = state.getSeason();
        int cycleTicks = state.getSeasonCycleTicks();
        int subSeasonDuration = state.getSubSeasonDuration();
        int cycleDuration = state.getCycleDuration();
        int day = state.getDay();

        int ticksIntoSubSeason = cycleTicks % subSeasonDuration;
        int ticksUntilNextSubSeason = subSeasonDuration - ticksIntoSubSeason;

        Season.SubSeason[] subSeasons = Season.SubSeason.values();
        Season.SubSeason nextSubSeason = subSeasons[(subSeason.ordinal() + 1) % subSeasons.length];

        boolean dhLoaded = DhApi.Delayed.worldProxy != null && DhApi.Delayed.worldProxy.worldLoaded();
        IDhApiLevelWrapper overworldLevel = DHCompat.getOverworldLevel();

        ctx.getSource().sendFeedback(Text.literal(
            "[SSDH] ===== Status =====" +
            "\n  Season:          " + season.name() + " (" + subSeason.name() + ")" +
            "\n  Day:             " + day +
            "\n  Cycle ticks:     " + cycleTicks + " / " + cycleDuration +
            "\n  Sub-season:      " + ticksIntoSubSeason + " / " + subSeasonDuration + " ticks elapsed" +
            "\n  Next sub-season: " + nextSubSeason.name() + " in " + ticksUntilNextSubSeason + " ticks" +
            "\n  >> /ssdh season " + nextSubSeason.name().toLowerCase() + "  (server command, requires op)" +
            "\n  DH loaded:       " + dhLoaded +
            "\n  DH overworld:    " + (overworldLevel != null ? "found" : "not found")
        ));

        return 1;
    }

    /**
     * /ssdh-client skip
     * Prints how many ticks until the next sub-season and the exact command to jump there.
     */
    private static int executeSkip(CommandContext<FabricClientCommandSource> ctx) {
        var client = ctx.getSource().getClient();
        if (client.world == null) {
            ctx.getSource().sendFeedback(Text.literal("[SSDH] Not in a world."));
            return 0;
        }

        ISeasonState state = SeasonHelper.getSeasonState(client.world);
        if (state == null) {
            ctx.getSource().sendFeedback(Text.literal("[SSDH] Serene Seasons state unavailable."));
            return 0;
        }

        int ticksUntilNext = state.getSubSeasonDuration() - (state.getSeasonCycleTicks() % state.getSubSeasonDuration());
        Season.SubSeason[] subSeasons = Season.SubSeason.values();
        Season.SubSeason nextSubSeason = subSeasons[(state.getSubSeason().ordinal() + 1) % subSeasons.length];

        ctx.getSource().sendFeedback(Text.literal(
            "[SSDH] Next sub-season: " + nextSubSeason.name() + " in " + ticksUntilNext + " ticks." +
            "\n  Run in chat: /ssdh season " + nextSubSeason.name().toLowerCase() + "  (requires op)"
        ));

        return 1;
    }

    /**
     * /ssdh-client reload
     * Manually triggers a DH color cache clear + full LOD rebuild.
     */
    private static int executeReload(CommandContext<FabricClientCommandSource> ctx) {
        IDhApiLevelWrapper level = DHCompat.getOverworldLevel();
        if (level == null) {
            ctx.getSource().sendFeedback(Text.literal("[SSDH] DH overworld level not available."));
            return 0;
        }

        ctx.getSource().sendFeedback(Text.literal("[SSDH] Clearing DH color cache and triggering LOD rebuild..."));
        DHCompat.clearColorCacheOnly(level);
        DHCompat.triggerRenderRebuild();
        ctx.getSource().sendFeedback(Text.literal("[SSDH] Done. LODs will rebuild over the next few seconds."));
        return 1;
    }

}

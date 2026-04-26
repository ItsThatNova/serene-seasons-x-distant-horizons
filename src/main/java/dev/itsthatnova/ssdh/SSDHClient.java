package dev.itsthatnova.ssdh;

import dev.itsthatnova.ssdh.compat.DHCompat;
import dev.itsthatnova.ssdh.compat.SSCompat;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Environment(EnvType.CLIENT)
public class SSDHClient implements ClientModInitializer {

    public static final String MOD_ID = "ssdh";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        LOGGER.info("[SSDH] Serene Seasons X Distant Horizons initializing...");

        // Resolve DH reflection targets at startup and register level load event.
        // Startup log will confirm whether soft reload (Path C) is available.
        DHCompat.registerEvents();

        // Register per-tick season change detection
        SSCompat.registerEvents();

        // Register client commands
        SSDHCommands.register();

        LOGGER.info("[SSDH] Initialized successfully.");
    }
}

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

        // Register DH level load event - triggers full LOD rebuild on world join
        // and dimension return, where the loading screen hides any visual disruption.
        DHCompat.registerEvents();

        // Register Serene Seasons season change listener - clears DH's color cache
        // so LODs repaint with correct seasonal colors as chunks naturally refresh.
        SSCompat.registerEvents();

        // Register debug commands (/ssdh status, /ssdh skip, /ssdh reload)
        SSDHCommands.register();

        LOGGER.info("[SSDH] Initialized successfully.");
    }
}

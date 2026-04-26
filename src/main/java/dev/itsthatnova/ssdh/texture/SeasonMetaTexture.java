package dev.itsthatnova.ssdh.texture;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import sereneseasons.api.season.Season;

/**
 * Publishes two 1x1 metadata textures for shader sampling.
 * season_meta RGBA = spring/summer/autumn/winter
 * season_phase RGB = early/mid/late
 */
public final class SeasonMetaTexture {
    public static final SeasonMetaTexture INSTANCE = new SeasonMetaTexture();
    public static final Identifier ID = Identifier.of("ssdh", "season_meta");
    public static final Identifier PHASE_ID = Identifier.of("ssdh", "season_phase");

    private NativeImageBackedTexture texture;
    private NativeImageBackedTexture phaseTexture;
    private int lastPacked = Integer.MIN_VALUE;
    private int lastPhasePacked = Integer.MIN_VALUE;

    private SeasonMetaTexture() {}

    private void ensureInitialized() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getTextureManager() == null) return;

        if (texture == null) {
            NativeImage image = new NativeImage(NativeImage.Format.RGBA, 1, 1, false);
            texture = new NativeImageBackedTexture(image);
            client.getTextureManager().registerTexture(ID, texture);
        }
        if (phaseTexture == null) {
            NativeImage image = new NativeImage(NativeImage.Format.RGBA, 1, 1, false);
            phaseTexture = new NativeImageBackedTexture(image);
            client.getTextureManager().registerTexture(PHASE_ID, phaseTexture);
        }
    }

    public void clear() {
        ensureInitialized();
        setPackedColors(0x00000000, 0x00000000);
    }

    public void update(Season.SubSeason subSeason) {
        if (subSeason == null) {
            clear();
            return;
        }

        int seasonPacked = 0x00000000;
        int phasePacked = 0x00000000;
        switch (subSeason) {
            case EARLY_SPRING -> { seasonPacked = 0x000000FF; phasePacked = 0x000000FF; }
            case MID_SPRING   -> { seasonPacked = 0x000000FF; phasePacked = 0x0000FF00; }
            case LATE_SPRING  -> { seasonPacked = 0x000000FF; phasePacked = 0x00FF0000; }
            case EARLY_SUMMER -> { seasonPacked = 0x0000FF00; phasePacked = 0x000000FF; }
            case MID_SUMMER   -> { seasonPacked = 0x0000FF00; phasePacked = 0x0000FF00; }
            case LATE_SUMMER  -> { seasonPacked = 0x0000FF00; phasePacked = 0x00FF0000; }
            case EARLY_AUTUMN -> { seasonPacked = 0x00FF0000; phasePacked = 0x000000FF; }
            case MID_AUTUMN   -> { seasonPacked = 0x00FF0000; phasePacked = 0x0000FF00; }
            case LATE_AUTUMN  -> { seasonPacked = 0x00FF0000; phasePacked = 0x00FF0000; }
            case EARLY_WINTER -> { seasonPacked = 0xFF000000; phasePacked = 0x000000FF; }
            case MID_WINTER   -> { seasonPacked = 0xFF000000; phasePacked = 0x0000FF00; }
            case LATE_WINTER  -> { seasonPacked = 0xFF000000; phasePacked = 0x00FF0000; }
        }

        setPackedColors(seasonPacked, phasePacked);
    }

    private void setPackedColors(int packed, int phasePacked) {
        ensureInitialized();
        if (texture != null && packed != lastPacked) {
            texture.getImage().setColor(0, 0, packed);
            texture.upload();
            lastPacked = packed;
        }
        if (phaseTexture != null && phasePacked != lastPhasePacked) {
            phaseTexture.getImage().setColor(0, 0, phasePacked);
            phaseTexture.upload();
            lastPhasePacked = phasePacked;
        }
    }
}

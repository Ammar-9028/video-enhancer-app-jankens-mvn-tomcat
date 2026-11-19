package com.reelboost.core;

import java.util.function.Function;

/** Validated user options. Only enums and clamped numbers reach the FFmpeg command line. */
public final class Options {
    public static final int[] HEIGHTS = {0, 720, 1080, 1440, 2160};

    public final Preset preset;
    public final int height;      // target short side, 0 = keep
    public final Quality quality;
    public final int denoise;     // 0..3
    public final int sharpen;     // 0..3
    public final int color;       // 0..3
    public final boolean stabilize;
    public final boolean audio;   // clean + normalise audio

    public Options(Preset preset, int height, Quality quality, int denoise, int sharpen, int color,
                   boolean stabilize, boolean audio) {
        this.preset = preset;
        this.height = height;
        this.quality = quality;
        this.denoise = clamp(denoise);
        this.sharpen = clamp(sharpen);
        this.color = clamp(color);
        this.stabilize = stabilize;
        this.audio = audio;
    }

    public static Options parse(Function<String, String> in) {
        Preset p = Preset.from(in.apply("preset"));
        return new Options(p, height(in.apply("height")), Quality.from(in.apply("quality")),
                level(in.apply("denoise"), p.denoise), level(in.apply("sharpen"), p.sharpen),
                level(in.apply("color"), p.color), flag(in.apply("stabilize")), flag(in.apply("audio")));
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(3, v));
    }

    private static int level(String s, int def) {
        if (s == null || s.isBlank()) return def;
        try {
            return clamp(Integer.parseInt(s.trim()));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static int height(String s) {
        try {
            int h = Integer.parseInt(s == null ? "0" : s.trim());
            for (int allowed : HEIGHTS) {
                if (allowed == h) return h;
            }
        } catch (NumberFormatException ignored) {
            // fall through
        }
        return 0;
    }

    private static boolean flag(String s) {
        return s != null && (s.equals("1") || s.equalsIgnoreCase("true") || s.equalsIgnoreCase("on"));
    }
}

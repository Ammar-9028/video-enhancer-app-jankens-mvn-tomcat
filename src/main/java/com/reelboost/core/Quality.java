package com.reelboost.core;

/** Encoder quality vs speed. */
public enum Quality {
    FAST("veryfast", 22), BALANCED("medium", 19), BEST("slow", 17);

    public final String x264Preset;
    public final int crf;

    Quality(String x264Preset, int crf) {
        this.x264Preset = x264Preset;
        this.crf = crf;
    }

    public static Quality from(String s) {
        if (s != null) {
            for (Quality q : values()) {
                if (q.name().equalsIgnoreCase(s.trim())) return q;
            }
        }
        return BALANCED;
    }
}

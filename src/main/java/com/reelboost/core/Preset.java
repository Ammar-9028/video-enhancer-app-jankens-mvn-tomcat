package com.reelboost.core;

/** Look presets. Levels are 0 (off) to 3 (strong). Grade values are the "medium" colour settings. */
public enum Preset {
    AUTO("Auto Enhance", "Balanced clean-up: light denoise, sharper detail, richer colour.", 1, 2, 2, 1.05, 1.10, 0.00, 1.00),
    CINEMATIC("Cinematic", "Film look: teal shadows, warm highlights, soft vignette.", 1, 1, 2, 1.12, 0.98, -0.01, 1.00),
    VIVID("Vivid Pop", "Punchy colours and contrast for social media.", 1, 2, 2, 1.08, 1.28, 0.01, 1.00),
    LOWLIGHT("Low-light Rescue", "Brightens dark footage and removes grain.", 3, 1, 2, 1.06, 1.08, 0.04, 1.22),
    CLEAN("Clean + Upscale", "Natural look, best combined with a higher resolution.", 2, 2, 1, 1.02, 1.04, 0.00, 1.00);

    public final String label;
    public final String description;
    public final int denoise;
    public final int sharpen;
    public final int color;
    public final double contrast;
    public final double saturation;
    public final double brightness;
    public final double gamma;

    Preset(String label, String description, int denoise, int sharpen, int color,
           double contrast, double saturation, double brightness, double gamma) {
        this.label = label;
        this.description = description;
        this.denoise = denoise;
        this.sharpen = sharpen;
        this.color = color;
        this.contrast = contrast;
        this.saturation = saturation;
        this.brightness = brightness;
        this.gamma = gamma;
    }

    public static Preset from(String s) {
        if (s != null) {
            for (Preset p : values()) {
                if (p.name().equalsIgnoreCase(s.trim())) return p;
            }
        }
        return AUTO;
    }
}

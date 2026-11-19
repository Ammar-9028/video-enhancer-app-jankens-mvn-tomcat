package com.reelboost.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Builds the FFmpeg video and audio filter chains and full command lines. */
public final class FilterBuilder {
    public static final String TRF = "stab.trf";

    private FilterBuilder() {
    }

    private static String n(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }

    public static String video(Options o, MediaInfo m, boolean stab) {
        List<String> f = new ArrayList<>();
        if (stab) f.add("vidstabtransform=input=" + TRF + ":smoothing=20");

        switch (o.denoise) {
            case 1 -> f.add("hqdn3d=2:1.5:3:3");
            case 2 -> f.add("hqdn3d=4:3:6:5");
            case 3 -> f.add("hqdn3d=7:5:10:8");
            default -> { }
        }

        if (o.height > 0 && m.shortSide() > 0 && m.shortSide() < o.height) {
            // target applies to the SHORT side, so portrait clips (1080x1920) work too
            f.add("scale=w='if(gt(iw,ih),-2," + o.height + ")':h='if(gt(iw,ih)," + o.height + ",-2)':flags=lanczos");
        }

        if (o.color > 0) {
            double k = new double[]{0, .6, 1.0, 1.5}[o.color];
            Preset p = o.preset;
            f.add("eq=contrast=" + n(1 + (p.contrast - 1) * k) + ":saturation=" + n(1 + (p.saturation - 1) * k)
                    + ":brightness=" + n(p.brightness * k) + ":gamma=" + n(1 + (p.gamma - 1) * k));
            if (p == Preset.CINEMATIC) {
                f.add("colorbalance=rs=" + n(-0.06 * k) + ":bs=" + n(0.07 * k) + ":rh=" + n(0.07 * k) + ":bh=" + n(-0.06 * k));
                f.add("vignette=0.7854");
            }
            if (p == Preset.AUTO) f.add("normalize=strength=0.5:smoothing=30");
        }

        switch (o.sharpen) {
            case 1 -> f.add("unsharp=5:5:0.5:5:5:0.0");
            case 2 -> f.add("unsharp=5:5:0.9:5:5:0.0");
            case 3 -> f.add("unsharp=5:5:1.4:5:5:0.0");
            default -> { }
        }
        f.add("format=yuv420p");
        return String.join(",", f);
    }

    public static String audio() {
        return "highpass=f=70,afftdn=nf=-28,loudnorm=I=-16:TP=-1.5:LRA=11";
    }

    private static List<String> base(Config c) {
        return new ArrayList<>(List.of(c.ffmpeg, "-y", "-nostdin", "-hide_banner", "-loglevel", "error", "-nostats", "-progress", "pipe:1"));
    }

    /** Pass 1 of stabilisation: motion analysis only. */
    public static List<String> detect(Config c, String input) {
        List<String> cmd = base(c);
        cmd.addAll(List.of("-i", input, "-vf", "vidstabdetect=shakiness=5:accuracy=15:result=" + TRF, "-f", "null", "-"));
        return cmd;
    }

    public static List<String> render(Config c, Options o, MediaInfo m, String input, boolean stab) {
        List<String> cmd = base(c);
        cmd.addAll(List.of("-i", input, "-map", "0:v:0", "-vf", video(o, m, stab),
                "-c:v", "libx264", "-preset", o.quality.x264Preset, "-crf", String.valueOf(o.quality.crf),
                "-profile:v", "high", "-pix_fmt", "yuv420p"));
        if (m.hasAudio()) {
            cmd.addAll(List.of("-map", "0:a:0"));
            if (o.audio) cmd.addAll(List.of("-af", audio()));
            cmd.addAll(List.of("-c:a", "aac", "-b:a", "192k"));
        } else {
            cmd.add("-an");
        }
        cmd.addAll(List.of("-movflags", "+faststart", "-max_muxing_queue_size", "1024", "output.mp4"));
        return cmd;
    }

    /** One preview frame (scaled to 720px high) at time t. */
    public static List<String> frame(Config c, String input, double t, String out) {
        List<String> cmd = base(c);
        cmd.addAll(List.of("-ss", n(t), "-i", input, "-frames:v", "1", "-vf", "scale=-2:720:flags=lanczos", "-q:v", "2", out));
        return cmd;
    }

    /** Parses ffmpeg -progress output. Returns seconds, or NaN if the line is not a time line. */
    public static double progressSeconds(String line) {
        if (line.startsWith("out_time_us=") || line.startsWith("out_time_ms=")) {
            try {
                return Long.parseLong(line.substring(line.indexOf('=') + 1).trim()) / 1_000_000.0;
            } catch (NumberFormatException e) {
                return Double.NaN;
            }
        }
        return Double.NaN;
    }
}

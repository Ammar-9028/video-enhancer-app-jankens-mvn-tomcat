package com.reelboost.core;

import java.io.IOException;
import java.util.List;

/** ffprobe wrapper + parser (plain key=value output, no JSON library needed). */
public final class Probe {
    private Probe() {
    }

    public static MediaInfo run(Config cfg, String file) throws IOException, InterruptedException {
        String v = Exec.capture(List.of(cfg.ffprobe, "-v", "error", "-select_streams", "v:0",
                "-show_entries", "stream=width,height,avg_frame_rate,r_frame_rate,duration:format=duration,size",
                "-of", "default=noprint_wrappers=1", file), 20_000);
        String a = Exec.capture(List.of(cfg.ffprobe, "-v", "error", "-select_streams", "a:0",
                "-show_entries", "stream=codec_name", "-of", "csv=p=0", file), 20_000);
        return parse(v, !a.isBlank() && !a.contains("rror") && !a.contains("nvalid"));
    }

    static MediaInfo parse(String out, boolean hasAudio) {
        int w = 0, h = 0;
        double fps = 0, dur = 0;
        long size = 0;
        for (String line : out.split("\\R")) {
            int i = line.indexOf('=');
            if (i < 0) continue;
            String k = line.substring(0, i).trim(), val = line.substring(i + 1).trim();
            switch (k) {
                case "width" -> w = (int) num(val);
                case "height" -> h = (int) num(val);
                case "avg_frame_rate", "r_frame_rate" -> {
                    double f = rate(val);
                    if (f > 0 && (fps == 0 || k.equals("avg_frame_rate"))) fps = f;
                }
                case "duration" -> dur = Math.max(dur, num(val));
                case "size" -> size = (long) num(val);
                default -> { }
            }
        }
        return new MediaInfo(w, h, fps, dur, hasAudio, size);
    }

    private static double num(String s) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double rate(String s) {
        int i = s.indexOf('/');
        if (i < 0) return num(s);
        double d = num(s.substring(i + 1));
        return d == 0 ? 0 : num(s.substring(0, i)) / d;
    }
}

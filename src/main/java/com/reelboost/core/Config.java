package com.reelboost.core;

import java.nio.file.Path;
import java.nio.file.Paths;

/** Runtime settings. Override with env vars (REELBOOST_*) or -Dreelboost.* system properties. */
public final class Config {
    public final Path dataDir;
    public final String ffmpeg;
    public final String ffprobe;
    public final int maxUploadMb;
    public final int maxDurationSec;
    public final int workers;
    public final int queueSize;
    public final int retentionHours;
    public final int jobTimeoutMin;

    public Config(Path dataDir, String ffmpeg, String ffprobe, int maxUploadMb, int maxDurationSec,
                  int workers, int queueSize, int retentionHours, int jobTimeoutMin) {
        this.dataDir = dataDir;
        this.ffmpeg = ffmpeg;
        this.ffprobe = ffprobe;
        this.maxUploadMb = maxUploadMb;
        this.maxDurationSec = maxDurationSec;
        this.workers = workers;
        this.queueSize = queueSize;
        this.retentionHours = retentionHours;
        this.jobTimeoutMin = jobTimeoutMin;
    }

    public static Config load() {
        String def = Paths.get(System.getProperty("java.io.tmpdir"), "reelboost").toString();
        return new Config(
                Paths.get(get("DATA_DIR", def)),
                get("FFMPEG", "ffmpeg"),
                get("FFPROBE", "ffprobe"),
                num("MAX_UPLOAD_MB", 500),
                num("MAX_DURATION_SEC", 900),
                Math.max(1, num("WORKERS", 1)),
                Math.max(1, num("QUEUE_SIZE", 10)),
                Math.max(1, num("RETENTION_HOURS", 6)),
                Math.max(1, num("JOB_TIMEOUT_MIN", 120)));
    }

    private static String get(String key, String def) {
        String v = System.getProperty("reelboost." + key.toLowerCase().replace('_', '.'));
        if (v == null || v.isBlank()) v = System.getenv("REELBOOST_" + key);
        return v == null || v.isBlank() ? def : v.trim();
    }

    private static int num(String key, int def) {
        try {
            return Integer.parseInt(get(key, String.valueOf(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }
}

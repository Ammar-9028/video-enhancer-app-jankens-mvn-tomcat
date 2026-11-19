package com.reelboost.core;

/** What ffprobe tells us about a video. */
public record MediaInfo(int width, int height, double fps, double duration, boolean hasAudio, long bytes) {
    public int shortSide() {
        return Math.min(width, height);
    }
}

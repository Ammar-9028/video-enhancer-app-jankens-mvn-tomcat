package com.reelboost.core;

import java.util.concurrent.Future;

/** One enhancement job. State is updated by the worker thread and read by HTTP threads. */
public final class Job {
    public enum State { QUEUED, PROCESSING, DONE, FAILED, CANCELED }

    public final String id;
    public final String name;          // original file name (sanitised)
    public final String inputName;     // file name inside the job directory
    public final Options options;
    public final MediaInfo info;
    public final long createdAt = System.currentTimeMillis();

    private volatile State state = State.QUEUED;
    private volatile String stage = "Waiting in queue";
    private volatile double progress;
    private volatile String error;
    private volatile long startedAt;
    private volatile long finishedAt;
    private volatile MediaInfo output;
    private volatile long outputBytes;
    volatile Process process;
    volatile Future<?> future;
    private volatile boolean canceled;

    public Job(String id, String name, String inputName, Options options, MediaInfo info) {
        this.id = id;
        this.name = name;
        this.inputName = inputName;
        this.options = options;
        this.info = info;
    }

    public State state() {
        return state;
    }

    public boolean isCanceled() {
        return canceled;
    }

    public long finishedAt() {
        return finishedAt;
    }

    synchronized void start() {
        if (state == State.QUEUED) {
            state = State.PROCESSING;
            startedAt = System.currentTimeMillis();
            stage = "Starting";
        }
    }

    void stage(String s) {
        stage = s;
    }

    void progress(double p) {
        if (p > progress) progress = Math.min(99.5, p);
    }

    synchronized void done(MediaInfo out, long bytes) {
        output = out;
        outputBytes = bytes;
        progress = 100;
        stage = "Done";
        state = State.DONE;
        finishedAt = System.currentTimeMillis();
    }

    synchronized void fail(String message) {
        if (state == State.CANCELED) return;
        error = message;
        stage = "Failed";
        state = State.FAILED;
        finishedAt = System.currentTimeMillis();
    }

    public synchronized void cancel() {
        if (state == State.DONE || state == State.FAILED || state == State.CANCELED) return;
        canceled = true;
        state = State.CANCELED;
        stage = "Canceled";
        finishedAt = System.currentTimeMillis();
        Process p = process;
        if (p != null) p.destroyForcibly();
        Future<?> f = future;
        if (f != null) f.cancel(false);
    }

    public String toJson() {
        StringBuilder b = new StringBuilder(512);
        double eta = -1;
        if (state == State.PROCESSING && progress > 3 && startedAt > 0) {
            double elapsed = (System.currentTimeMillis() - startedAt) / 1000.0;
            eta = elapsed * (100 - progress) / progress;
        }
        b.append("{\"id\":").append(Json.quote(id))
                .append(",\"name\":").append(Json.quote(name))
                .append(",\"state\":").append(Json.quote(state.name()))
                .append(",\"stage\":").append(Json.quote(stage))
                .append(",\"progress\":").append(Json.num(progress))
                .append(",\"eta\":").append(Json.num(eta))
                .append(",\"error\":").append(Json.quote(error))
                .append(",\"input\":{\"width\":").append(info.width()).append(",\"height\":").append(info.height())
                .append(",\"fps\":").append(Json.num(info.fps())).append(",\"duration\":").append(Json.num(info.duration()))
                .append(",\"bytes\":").append(info.bytes()).append("}");
        MediaInfo o = output;
        if (o != null) {
            b.append(",\"output\":{\"width\":").append(o.width()).append(",\"height\":").append(o.height())
                    .append(",\"fps\":").append(Json.num(o.fps())).append(",\"bytes\":").append(outputBytes).append("}");
        }
        b.append(",\"options\":{\"preset\":").append(Json.quote(options.preset.name()))
                .append(",\"height\":").append(options.height)
                .append(",\"quality\":").append(Json.quote(options.quality.name()))
                .append(",\"denoise\":").append(options.denoise).append(",\"sharpen\":").append(options.sharpen)
                .append(",\"color\":").append(options.color).append(",\"stabilize\":").append(options.stabilize)
                .append(",\"audio\":").append(options.audio).append("}}");
        return b.toString();
    }
}

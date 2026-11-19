package com.reelboost.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Owns the worker pool, the job store and the on-disk job directories. */
public final class JobManager {
    public static final String ATTR = "reelboost.jobManager";
    private static final Pattern PROGRESS_LINE = Pattern.compile("^[a-z_0-9]+=\\S*$");
    private static final Pattern ID = Pattern.compile("^[a-f0-9]{16}$");

    public record Caps(boolean ffmpeg, String version, boolean vidstab, boolean x264) { }

    private static final class Canceled extends Exception {
        private static final long serialVersionUID = 1L;
    }

    private final Config cfg;
    private final Path root;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor pool;
    private final ScheduledExecutorService sched;
    private final SecureRandom rnd = new SecureRandom();
    private final Caps caps;

    public JobManager(Config cfg) throws IOException {
        this.cfg = cfg;
        this.root = cfg.dataDir.resolve("jobs");
        Files.createDirectories(root);
        pool = new ThreadPoolExecutor(cfg.workers, cfg.workers, 0L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(cfg.queueSize), r -> {
                    Thread t = new Thread(r, "reelboost-worker");
                    t.setDaemon(true);
                    return t;
                });
        sched = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "reelboost-maint");
            t.setDaemon(true);
            return t;
        });
        caps = detect();
        purgeOld();
        sched.scheduleWithFixedDelay(this::purgeOld, 10, 15, TimeUnit.MINUTES);
    }

    public Config config() {
        return cfg;
    }

    public Caps caps() {
        return caps;
    }

    public int jobCount() {
        return jobs.size();
    }

    public static boolean validId(String id) {
        return id != null && ID.matcher(id).matches();
    }

    public String newId() {
        byte[] b = new byte[8];
        rnd.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    public Path dir(String id) {
        return root.resolve(id);
    }

    public Job get(String id) {
        return validId(id) ? jobs.get(id) : null;
    }

    public boolean hasCapacity() {
        return pool.getQueue().remainingCapacity() > 0 || pool.getActiveCount() < pool.getMaximumPoolSize();
    }

    /** Returns false if the queue is full. */
    public boolean submit(Job job) {
        jobs.put(job.id, job);
        try {
            job.future = pool.submit(() -> run(job));
            return true;
        } catch (RejectedExecutionException e) {
            jobs.remove(job.id);
            return false;
        }
    }

    public void delete(String id) {
        Job j = get(id);
        if (j != null) {
            j.cancel();
            jobs.remove(id);
        }
        if (validId(id)) deleteTree(dir(id));
    }

    public void shutdown() {
        jobs.values().forEach(Job::cancel);
        pool.shutdownNow();
        sched.shutdownNow();
    }

    // ---------------------------------------------------------------- worker

    private void run(Job j) {
        if (j.isCanceled()) return;
        j.start();
        Path dir = dir(j.id);
        try {
            boolean stab = j.options.stabilize && caps.vidstab();
            double dur = Math.max(0.1, j.info.duration());
            double split = stab ? 30 : 0;
            if (stab) {
                j.stage("Stabilizing: analysing motion");
                ffmpeg(j, dir, FilterBuilder.detect(cfg, j.inputName), dur, 0, split);
            }
            j.stage("Enhancing video");
            ffmpeg(j, dir, FilterBuilder.render(cfg, j.options, j.info, j.inputName, stab), dur, split, 97);
            if (j.isCanceled()) throw new Canceled();

            j.stage("Creating before/after preview");
            double t = Math.min(1.5, dur / 2);
            try {
                ffmpeg(j, dir, FilterBuilder.frame(cfg, j.inputName, t, "before.jpg"), dur, 97, 98.5);
                ffmpeg(j, dir, FilterBuilder.frame(cfg, "output.mp4", t, "after.jpg"), dur, 98.5, 99.4);
            } catch (IOException previewFailure) {
                // previews are optional, the enhanced video is what matters
            }
            Path out = dir.resolve("output.mp4");
            if (!Files.isRegularFile(out) || Files.size(out) == 0) throw new IOException("FFmpeg produced no output file.");
            MediaInfo oi;
            try {
                oi = Probe.run(cfg, out.toString());
            } catch (IOException e) {
                oi = j.info;
            }
            j.done(oi, Files.size(out));
            Files.deleteIfExists(dir.resolve(j.inputName)); // original is no longer needed on the server
            Files.deleteIfExists(dir.resolve(FilterBuilder.TRF));
        } catch (Canceled c) {
            j.cancel();
        } catch (Exception e) {
            if (j.isCanceled()) return;
            j.fail(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private void ffmpeg(Job j, Path dir, List<String> cmd, double duration, double from, double to)
            throws IOException, InterruptedException, Canceled {
        if (j.isCanceled()) throw new Canceled();
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        j.process = p;
        ScheduledFuture<?> watchdog = sched.schedule(p::destroyForcibly, cfg.jobTimeoutMin, TimeUnit.MINUTES);
        Deque<String> tail = new ArrayDeque<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                double sec = FilterBuilder.progressSeconds(line);
                if (!Double.isNaN(sec)) {
                    j.progress(from + (to - from) * Math.min(1.0, sec / duration));
                } else if (!PROGRESS_LINE.matcher(line).matches()) {
                    if (tail.size() >= 12) tail.removeFirst();
                    tail.addLast(line);
                }
            }
        } finally {
            watchdog.cancel(false);
        }
        int code = p.waitFor();
        j.process = null;
        if (j.isCanceled()) throw new Canceled();
        if (code != 0) {
            throw new IOException("FFmpeg failed (exit " + code + "). " + String.join(" | ", tail));
        }
    }

    // ---------------------------------------------------------------- maintenance

    private Caps detect() {
        try {
            String v = Exec.capture(List.of(cfg.ffmpeg, "-hide_banner", "-version"), 15_000);
            String first = v.lines().findFirst().orElse("ffmpeg");
            if (!first.toLowerCase().contains("ffmpeg")) return new Caps(false, "", false, false);
            String filters = Exec.capture(List.of(cfg.ffmpeg, "-hide_banner", "-filters"), 15_000);
            String encoders = Exec.capture(List.of(cfg.ffmpeg, "-hide_banner", "-encoders"), 15_000);
            return new Caps(true, first, filters.contains(" vidstabdetect "), encoders.contains("libx264"));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new Caps(false, "", false, false);
        }
    }

    private void purgeOld() {
        long cutoff = System.currentTimeMillis() - cfg.retentionHours * 3_600_000L;
        jobs.values().removeIf(j -> {
            long ref = j.finishedAt() > 0 ? j.finishedAt() : j.createdAt;
            boolean old = ref < cutoff && j.state() != Job.State.PROCESSING;
            if (old) deleteTree(dir(j.id));
            return old;
        });
        try (Stream<Path> s = Files.list(root)) {
            s.filter(p -> validId(p.getFileName().toString()) && !jobs.containsKey(p.getFileName().toString()))
                    .filter(p -> p.toFile().lastModified() < cutoff)
                    .forEach(JobManager::deleteTree);
        } catch (IOException ignored) {
            // root not listable right now
        }
    }

    static void deleteTree(Path p) {
        if (!Files.exists(p)) return;
        try (Stream<Path> s = Files.walk(p)) {
            s.sorted(Comparator.reverseOrder()).forEach(x -> {
                try {
                    Files.deleteIfExists(x);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }
}

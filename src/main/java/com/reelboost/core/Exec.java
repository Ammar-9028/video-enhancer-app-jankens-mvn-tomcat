package com.reelboost.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Runs a short command (ffprobe, ffmpeg -version) with a hard timeout and returns its output. */
final class Exec {
    private Exec() {
    }

    static String capture(List<String> cmd, long timeoutMs) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        StringBuilder sb = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null && sb.length() < 2_000_000) {
                    sb.append(line).append('\n');
                }
            } catch (IOException ignored) {
                // process was killed
            }
        }, "exec-reader");
        reader.setDaemon(true);
        reader.start();
        reader.join(timeoutMs);
        if (reader.isAlive()) {
            p.destroyForcibly();
            reader.join(2000);
            throw new IOException("Command timed out: " + cmd.get(0));
        }
        p.waitFor();
        return sb.toString();
    }
}

package com.reelboost.web;

import com.reelboost.core.Config;
import com.reelboost.core.Job;
import com.reelboost.core.JobManager;
import com.reelboost.core.Json;
import com.reelboost.core.MediaInfo;
import com.reelboost.core.Options;
import com.reelboost.core.Preset;
import com.reelboost.core.Probe;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Set;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;

/**
 * REST-style API (mapped to /api/* in web.xml, with multipart enabled):
 *   GET    /api/capabilities
 *   POST   /api/jobs                      multipart: video + options
 *   GET    /api/jobs/{id}
 *   GET    /api/jobs/{id}/output[?download=1] | before.jpg | after.jpg
 *   POST   /api/jobs/{id}/cancel
 *   DELETE /api/jobs/{id}
 */
public class ApiServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    private static final Set<String> EXT = Set.of("mp4", "mov", "mkv", "avi", "webm", "m4v", "mpg", "mpeg", "3gp");

    private JobManager jm;

    @Override
    public void init() {
        jm = (JobManager) getServletContext().getAttribute(JobManager.ATTR);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        if (jm == null) {
            json(resp, 503, "{\"error\":\"Server is starting or failed to start. Check Tomcat logs.\"}");
            return;
        }
        String[] p = parts(req);
        if (p.length == 1 && p[0].equals("capabilities")) {
            json(resp, 200, capabilities());
            return;
        }
        if (p.length >= 2 && p[0].equals("jobs")) {
            Job j = jm.get(p[1]);
            if (j == null) {
                json(resp, 404, "{\"error\":\"Job not found or expired.\"}");
                return;
            }
            if (p.length == 2) {
                json(resp, 200, j.toJson());
                return;
            }
            if (p.length == 3) {
                Path dir = jm.dir(j.id);
                switch (p[2]) {
                    case "output" -> {
                        if (j.state() != Job.State.DONE) {
                            json(resp, 409, "{\"error\":\"Video is not ready yet.\"}");
                            return;
                        }
                        String dl = "1".equals(req.getParameter("download")) ? base(j.name) + "-enhanced.mp4" : null;
                        sendFile(req, resp, dir.resolve("output.mp4"), "video/mp4", dl);
                    }
                    case "before.jpg" -> sendFile(req, resp, dir.resolve("before.jpg"), "image/jpeg", null);
                    case "after.jpg" -> sendFile(req, resp, dir.resolve("after.jpg"), "image/jpeg", null);
                    default -> resp.sendError(HttpServletResponse.SC_NOT_FOUND);
                }
                return;
            }
        }
        resp.sendError(HttpServletResponse.SC_NOT_FOUND);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        if (jm == null) {
            json(resp, 503, "{\"error\":\"Server is starting or failed to start. Check Tomcat logs.\"}");
            return;
        }
        String[] p = parts(req);
        if (p.length == 1 && p[0].equals("jobs")) {
            create(req, resp);
            return;
        }
        if (p.length == 3 && p[0].equals("jobs") && p[2].equals("cancel")) {
            Job j = jm.get(p[1]);
            if (j == null) {
                json(resp, 404, "{\"error\":\"Job not found.\"}");
                return;
            }
            j.cancel();
            json(resp, 200, j.toJson());
            return;
        }
        resp.sendError(HttpServletResponse.SC_NOT_FOUND);
    }

    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String[] p = parts(req);
        if (jm != null && p.length == 2 && p[0].equals("jobs") && JobManager.validId(p[1])) {
            jm.delete(p[1]);
            json(resp, 200, "{\"deleted\":true}");
            return;
        }
        resp.sendError(HttpServletResponse.SC_NOT_FOUND);
    }

    // ------------------------------------------------------------ create job

    private void create(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {
        Config cfg = jm.config();
        if (!jm.caps().ffmpeg() || !jm.caps().x264()) {
            json(resp, 503, "{\"error\":\"FFmpeg (with libx264) is not installed on the server.\"}");
            return;
        }
        String ct = req.getContentType();
        if (ct == null || !ct.toLowerCase(Locale.ROOT).startsWith("multipart/")) {
            json(resp, 400, "{\"error\":\"Send the video as multipart/form-data (field: video).\"}");
            return;
        }
        if (!jm.hasCapacity()) {
            json(resp, 503, "{\"error\":\"Server is busy. Please try again in a few minutes.\"}");
            return;
        }
        Part part;
        try {
            part = req.getPart("video");
        } catch (IllegalStateException e) {
            json(resp, 413, "{\"error\":\"File is too large.\"}");
            return;
        }
        if (part == null || part.getSize() <= 0) {
            json(resp, 400, "{\"error\":\"Please choose a video file.\"}");
            return;
        }
        if (part.getSize() > cfg.maxUploadMb * 1024L * 1024L) {
            json(resp, 413, "{\"error\":\"Max upload size is " + cfg.maxUploadMb + " MB.\"}");
            return;
        }
        String original = clean(part.getSubmittedFileName());
        String ext = original.contains(".") ? original.substring(original.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        if (!EXT.contains(ext)) {
            json(resp, 400, "{\"error\":\"Unsupported file type. Use MP4, MOV, MKV, AVI, WEBM or M4V.\"}");
            return;
        }

        String id = jm.newId();
        Path dir = jm.dir(id);
        String inputName = "input." + ext;
        try {
            Files.createDirectories(dir);
            try (InputStream in = part.getInputStream()) {
                Files.copy(in, dir.resolve(inputName), StandardCopyOption.REPLACE_EXISTING);
            }
            MediaInfo info = Probe.run(cfg, dir.resolve(inputName).toString());
            if (info.width() <= 0 || info.height() <= 0 || info.duration() <= 0) {
                jm.delete(id);
                json(resp, 400, "{\"error\":\"This file does not look like a playable video.\"}");
                return;
            }
            if (info.duration() > cfg.maxDurationSec) {
                jm.delete(id);
                json(resp, 400, "{\"error\":\"Video is too long. Max " + (cfg.maxDurationSec / 60) + " minutes.\"}");
                return;
            }
            Options opts = Options.parse(req::getParameter);
            Job job = new Job(id, original, inputName, opts, info);
            if (!jm.submit(job)) {
                jm.delete(id);
                json(resp, 503, "{\"error\":\"Server is busy. Please try again in a few minutes.\"}");
                return;
            }
            json(resp, 202, job.toJson());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            jm.delete(id);
            json(resp, 500, "{\"error\":\"Interrupted.\"}");
        } catch (IOException e) {
            jm.delete(id);
            json(resp, 500, "{\"error\":" + Json.quote("Could not read the video: " + e.getMessage()) + "}");
        }
    }

    private String capabilities() {
        Config c = jm.config();
        StringBuilder b = new StringBuilder("{\"ffmpeg\":").append(jm.caps().ffmpeg())
                .append(",\"vidstab\":").append(jm.caps().vidstab())
                .append(",\"maxUploadMb\":").append(c.maxUploadMb)
                .append(",\"maxDurationSec\":").append(c.maxDurationSec)
                .append(",\"retentionHours\":").append(c.retentionHours)
                .append(",\"presets\":[");
        boolean first = true;
        for (Preset p : Preset.values()) {
            if (!first) b.append(',');
            first = false;
            b.append("{\"id\":").append(Json.quote(p.name())).append(",\"label\":").append(Json.quote(p.label))
                    .append(",\"description\":").append(Json.quote(p.description))
                    .append(",\"denoise\":").append(p.denoise).append(",\"sharpen\":").append(p.sharpen)
                    .append(",\"color\":").append(p.color).append('}');
        }
        return b.append("]}").toString();
    }

    // ------------------------------------------------------------ helpers

    private static String[] parts(HttpServletRequest req) {
        String path = req.getPathInfo();
        if (path == null || path.equals("/")) return new String[0];
        return path.substring(1).split("/");
    }

    private static void json(HttpServletResponse resp, int status, String body) throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("Cache-Control", "no-store");
        resp.getWriter().write(body);
    }

    private static String clean(String name) {
        if (name == null) return "video";
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        String n = name.substring(slash + 1).replaceAll("[^A-Za-z0-9._-]", "_");
        return n.isBlank() ? "video" : (n.length() > 80 ? n.substring(n.length() - 80) : n);
    }

    private static String base(String name) {
        int dot = name.lastIndexOf('.');
        String b = dot > 0 ? name.substring(0, dot) : name;
        return b.isBlank() ? "video" : b;
    }

    /** Streams a file with HTTP Range support so the browser can seek inside the enhanced video. */
    private static void sendFile(HttpServletRequest req, HttpServletResponse resp, Path f, String type, String downloadName)
            throws IOException {
        if (!Files.isRegularFile(f)) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        long len = Files.size(f), start = 0, end = len - 1;
        resp.setHeader("Accept-Ranges", "bytes");
        resp.setContentType(type);
        String range = req.getHeader("Range");
        if (range != null && range.startsWith("bytes=")) {
            try {
                String[] r = range.substring(6).split("-", 2);
                if (!r[0].isBlank()) {
                    start = Long.parseLong(r[0].trim());
                    if (r.length > 1 && !r[1].isBlank()) end = Math.min(end, Long.parseLong(r[1].trim()));
                } else {
                    start = Math.max(0, len - Long.parseLong(r[1].trim()));
                }
            } catch (NumberFormatException e) {
                start = len; // forces 416 below
            }
            if (start > end || start >= len) {
                resp.setHeader("Content-Range", "bytes */" + len);
                resp.setStatus(416);
                return;
            }
            resp.setStatus(206);
            resp.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + len);
        }
        long count = end - start + 1;
        resp.setContentLengthLong(count);
        if (downloadName != null) resp.setHeader("Content-Disposition", "attachment; filename=\"" + downloadName + "\"");
        else resp.setHeader("Cache-Control", "private, max-age=3600");
        try (InputStream in = Files.newInputStream(f); OutputStream out = resp.getOutputStream()) {
            in.skipNBytes(start);
            byte[] buf = new byte[64 * 1024];
            long left = count;
            while (left > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) break;
                out.write(buf, 0, n);
                left -= n;
            }
        } catch (IOException clientAborted) {
            // browser closed the connection (normal while seeking)
        }
    }
}

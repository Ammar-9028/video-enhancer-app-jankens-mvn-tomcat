package com.reelboost.web;

import com.reelboost.core.JobManager;
import java.io.IOException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/** GET /health  (used by Jenkins to verify a deployment). */
public class HealthServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        JobManager jm = (JobManager) getServletContext().getAttribute(JobManager.ATTR);
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("Cache-Control", "no-store");
        if (jm == null) {
            resp.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            resp.getWriter().write("{\"status\":\"down\"}");
            return;
        }
        resp.getWriter().write("{\"status\":\"ok\",\"ffmpeg\":" + jm.caps().ffmpeg()
                + ",\"vidstab\":" + jm.caps().vidstab() + ",\"jobs\":" + jm.jobCount() + "}");
    }
}

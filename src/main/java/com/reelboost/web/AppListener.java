package com.reelboost.web;

import com.reelboost.core.Config;
import com.reelboost.core.JobManager;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

/** Creates the JobManager when Tomcat starts the app and stops it on shutdown. */
public class AppListener implements ServletContextListener {

    @Override
    public void contextInitialized(ServletContextEvent e) {
        ServletContext ctx = e.getServletContext();
        try {
            JobManager jm = new JobManager(Config.load());
            ctx.setAttribute(JobManager.ATTR, jm);
            ctx.log("ReelBoost ready. data=" + jm.config().dataDir + " ffmpeg=" + jm.caps().ffmpeg()
                    + " vidstab=" + jm.caps().vidstab());
        } catch (Exception ex) {
            ctx.log("ReelBoost failed to start", ex);
        }
    }

    @Override
    public void contextDestroyed(ServletContextEvent e) {
        Object jm = e.getServletContext().getAttribute(JobManager.ATTR);
        if (jm instanceof JobManager) ((JobManager) jm).shutdown();
    }
}

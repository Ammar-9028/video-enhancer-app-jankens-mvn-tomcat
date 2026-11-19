# ReelBoost - Video Enhancer
Upload a video, pick a style, get an enhanced MP4. Java 21 + Maven WAR on Tomcat 9, FFmpeg does the processing.

**Pipeline (per video):** [stabilize] -> denoise (hqdn3d) -> upscale to 720p/1080p/1440p/4K (Lanczos) -> colour grade (eq, colorbalance, normalize) -> sharpen (unsharp) -> H.264 (libx264) + AAC with audio clean (highpass, afftdn, loudnorm).
**Styles:** Auto Enhance, Cinematic, Vivid Pop, Low-light Rescue, Clean + Upscale. Everything is tunable in the UI.
**UI:** drag and drop, live progress with ETA, before/after slider, side-by-side players, download.
This is classic signal-processing enhancement, not AI super-resolution. Results depend on the source footage.

## Server setup (Ubuntu EC2, Tomcat 9 on :9090, Jenkins already installed)
```bash
sudo apt update && sudo apt install -y ffmpeg unzip
sudo mkdir -p /var/lib/reelboost && sudo chown tomcat:tomcat /var/lib/reelboost
sudo systemctl edit tomcat      # add the 2 lines below, save, then:
#   [Service]
#   Environment=REELBOOST_DATA_DIR=/var/lib/reelboost
sudo systemctl daemon-reload && sudo systemctl restart tomcat
```
Optional settings (same `Environment=` style): `REELBOOST_MAX_UPLOAD_MB` (500), `REELBOOST_MAX_DURATION_SEC` (900), `REELBOOST_WORKERS` (1), `REELBOOST_QUEUE_SIZE` (10), `REELBOOST_RETENTION_HOURS` (6), `REELBOOST_JOB_TIMEOUT_MIN` (120), `REELBOOST_FFMPEG`, `REELBOOST_FFPROBE`.
Tomcat's own 500 MB upload cap lives in `WEB-INF/web.xml` (`multipart-config`). Raise both if you need bigger files.

## Jenkins + GitHub
1. Push this repo (files at repo root: `pom.xml`, `Jenkinsfile`, `src/`).
2. Jenkins: New Item > Pipeline > Pipeline script from SCM > your repo > branch `*/main` > `Jenkinsfile`. Poll SCM `H/2 * * * *` or add a GitHub webhook to `http://<EC2-IP>:8080/github-webhook/`.
3. Jenkins user needs: JDK 21, Maven, `unzip`, write access to `/opt/tomcat9/webapps` (same setup as the earlier projects).
4. Open `http://<EC2-IP>:9090/reelboost/` (health: `/reelboost/health` shows `"ffmpeg":true`).

## Local
    mvn clean package && cp target/reelboost.war $TOMCAT_HOME/webapps/      # http://localhost:8080/reelboost/
    ./scripts/make-sample-video.sh                                          # a bad-looking test clip

## Sizing and safety
- Encoding is CPU heavy. One job at a time by default. Use at least 2 vCPU (c5.large or t3.large); 4K and "Best" quality are slow.
- There is no login. Do not expose it publicly without auth (nginx basic auth, or an IP allow-list), or anyone can burn your CPU.
- Uploaded originals are deleted after processing; results are deleted after `REELBOOST_RETENTION_HOURS`.
- If you put nginx in front: `client_max_body_size 500m; proxy_request_buffering off; proxy_read_timeout 600s;`

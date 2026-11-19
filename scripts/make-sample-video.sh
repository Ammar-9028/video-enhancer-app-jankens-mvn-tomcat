#!/usr/bin/env bash
# Makes a deliberately "bad" 6 second clip (blurry, noisy, dull colours) so you can see the enhancer work.
set -euo pipefail
ffmpeg -y -loglevel error -f lavfi -i "testsrc2=size=640x360:rate=24:duration=6" -f lavfi -i "sine=frequency=440:duration=6" \
  -vf "gblur=sigma=1.6,noise=alls=22:allf=t,eq=contrast=0.75:saturation=0.6:brightness=-0.06" \
  -c:v libx264 -crf 24 -pix_fmt yuv420p -c:a aac -shortest sample-bad.mp4
echo "created sample-bad.mp4"

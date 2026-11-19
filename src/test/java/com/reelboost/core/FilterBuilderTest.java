package com.reelboost.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FilterBuilderTest {
    private static final MediaInfo SD = new MediaInfo(640, 360, 24, 4, true, 1000);
    private static final MediaInfo UHD = new MediaInfo(3840, 2160, 30, 4, true, 1000);

    private static Options opts(Map<String, String> m) {
        return Options.parse(m::get);
    }

    @Test
    void upscalesShortSideOnlyWhenSourceIsSmaller() {
        String up = FilterBuilder.video(opts(Map.of("height", "1080")), SD, false);
        assertTrue(up.contains("scale=w='if(gt(iw,ih),-2,1080)':h='if(gt(iw,ih),1080,-2)':flags=lanczos"));
        String same = FilterBuilder.video(opts(Map.of("height", "1080")), UHD, false);
        assertFalse(same.contains("scale="));
    }

    @Test
    void stabilizeComesFirstAndFormatLast() {
        String v = FilterBuilder.video(opts(Map.of("stabilize", "1")), SD, true);
        assertTrue(v.startsWith("vidstabtransform=input=stab.trf"));
        assertTrue(v.endsWith("format=yuv420p"));
    }

    @Test
    void zeroLevelsRemoveFilters() {
        String v = FilterBuilder.video(opts(Map.of("denoise", "0", "sharpen", "0", "color", "0")), SD, false);
        assertEquals("format=yuv420p", v);
    }

    @Test
    void decimalPointIsLocaleIndependent() {
        Locale old = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            String v = FilterBuilder.video(opts(Map.of("preset", "VIVID")), SD, false);
            assertTrue(v.contains("saturation=1."));
            assertFalse(v.contains("saturation=1,"));
        } finally {
            Locale.setDefault(old);
        }
    }

    @Test
    void renderCommandHandlesMissingAudio() {
        Config cfg = new Config(java.nio.file.Paths.get("."), "ffmpeg", "ffprobe", 500, 900, 1, 10, 6, 10);
        MediaInfo silent = new MediaInfo(640, 360, 24, 4, false, 1000);
        assertTrue(FilterBuilder.render(cfg, opts(Map.of()), silent, "input.mp4", false).contains("-an"));
        assertTrue(FilterBuilder.render(cfg, opts(Map.of("audio", "1")), SD, "input.mp4", false).contains("-af"));
    }

    @Test
    void progressLinesAreParsed() {
        assertEquals(1.5, FilterBuilder.progressSeconds("out_time_us=1500000"), 0.0001);
        assertTrue(Double.isNaN(FilterBuilder.progressSeconds("out_time_us=N/A")));
        assertTrue(Double.isNaN(FilterBuilder.progressSeconds("frame=12")));
    }
}

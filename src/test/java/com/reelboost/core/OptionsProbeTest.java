package com.reelboost.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class OptionsProbeTest {
    @Test
    void presetDefaultsAndValidation() {
        Options o = Options.parse(Map.of("preset", "lowlight")::get);
        assertEquals(Preset.LOWLIGHT, o.preset);
        assertEquals(3, o.denoise);
        Options bad = Options.parse(Map.of("height", "999", "denoise", "99", "sharpen", "x")::get);
        assertEquals(0, bad.height);
        assertEquals(3, bad.denoise);
        assertEquals(Preset.AUTO.sharpen, bad.sharpen);
        assertFalse(bad.stabilize);
        assertTrue(Options.parse(Map.of("stabilize", "true")::get).stabilize);
    }

    @Test
    void ffprobeOutputIsParsed() {
        String out = "width=1920\nheight=1080\nr_frame_rate=30000/1001\navg_frame_rate=30000/1001\nduration=N/A\nduration=12.50\nsize=987654\n";
        MediaInfo m = Probe.parse(out, true);
        assertEquals(1920, m.width());
        assertEquals(1080, m.height());
        assertEquals(29.97, m.fps(), 0.01);
        assertEquals(12.5, m.duration(), 0.001);
        assertEquals(987654L, m.bytes());
        assertTrue(m.hasAudio());
    }

    @Test
    void jsonQuotingEscapesSpecialCharacters() {
        assertEquals("\"a\\\"b\\\\c\\n\"", Json.quote("a\"b\\c\n"));
    }
}

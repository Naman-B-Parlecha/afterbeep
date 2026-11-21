package dev.afterbeep.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SilenceGateTest {
    static final int CHUNK = 3200; // 100 ms, same as Engine.record

    /** 100 ms of a 440 Hz tone at [amp], or near-silence (comfort-noise level) when amp is small. */
    static byte[] chunk(int amp) {
        byte[] b = new byte[CHUNK];
        for (int i = 0; i < CHUNK / 2; i++) {
            short s = (short) (Math.sin(2 * Math.PI * 440 * i / 16000.0) * amp);
            b[2 * i] = (byte) s;
            b[2 * i + 1] = (byte) (s >> 8);
        }
        return b;
    }

    static final byte[] SPEECH = chunk(8000);  // ~-15 dBFS
    static final byte[] QUIET = chunk(50);     // ~-59 dBFS

    /** Feeds [ms] of [audio]; returns the chunk index (from 1) the gate hung up on, or 0 if it never did. */
    static int feed(SilenceGate g, byte[] audio, int ms) {
        for (int i = 1; i <= ms / 100; i++) if (g.feed(audio, CHUNK) != null) return i;
        return 0;
    }

    static SilenceGate gate() { return new SilenceGate(-45, 10_000, 10_000); }

    @Test public void robocallHangsUpAfterNoSpeech() {
        SilenceGate g = gate();
        int at = feed(g, QUIET, 30_000);
        assertEquals("hangs up just past 10 s", 101, at);
        assertFalse(g.spoke());
    }

    @Test public void hangsUpTenSecondsAfterCallerStops() {
        SilenceGate g = gate();
        assertEquals(0, feed(g, SPEECH, 3_000));
        assertTrue(g.spoke());
        assertEquals(101, feed(g, QUIET, 30_000));
    }

    @Test public void pauseShorterThanLimitKeepsRecording() {
        SilenceGate g = gate();
        feed(g, SPEECH, 2_000);
        assertEquals(0, feed(g, QUIET, 9_000));
        assertEquals(0, feed(g, SPEECH, 2_000));
    }

    @Test public void singleClickIsNotSpeech() {
        SilenceGate g = gate();
        feed(g, QUIET, 1_000);
        assertNull(g.feed(SPEECH, CHUNK));
        feed(g, QUIET, 1_000);
        assertFalse(g.spoke());
    }

    @Test public void trimsTrailingSilenceKeepingHalfSecond() {
        SilenceGate g = gate();
        feed(g, SPEECH, 3_000);
        feed(g, QUIET, 30_000);
        assertEquals(3_000 * SilenceGate.BYTES_PER_MS + SilenceGate.TAIL_KEEP_BYTES, g.keepBytes());
    }
}

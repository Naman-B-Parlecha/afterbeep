package dev.afterbeep.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RepeatCallersTest {
    static final long T0 = 1_000_000;

    @Test public void firstCallIsNotARepeat() {
        assertFalse(new RepeatCallers().isRecent("+919876543210", T0));
    }

    @Test public void secondCallWithinThreeMinutesRingsThrough() {
        RepeatCallers r = new RepeatCallers();
        r.remember("+919876543210", T0);
        assertTrue(r.isRecent("+919876543210", T0 + 2 * 60_000));
        assertTrue(r.isRecent("+919876543210", T0 + RepeatCallers.WINDOW_MS));
    }

    @Test public void callAfterWindowGoesToVoicemail() {
        RepeatCallers r = new RepeatCallers();
        r.remember("+919876543210", T0);
        assertFalse(r.isRecent("+919876543210", T0 + RepeatCallers.WINDOW_MS + 1));
    }

    @Test public void otherNumbersDontCount() {
        RepeatCallers r = new RepeatCallers();
        r.remember("+919876543210", T0);
        assertFalse(r.isRecent("+919999999999", T0 + 1000));
    }

    @Test public void hiddenNumbersNeverCount() {
        RepeatCallers r = new RepeatCallers();
        r.remember("", T0);
        r.remember(null, T0);
        assertFalse(r.isRecent("", T0 + 1000));
        assertFalse(r.isRecent(null, T0 + 1000));
    }

    @Test public void differentFormatsOfTheSameNumberMatch() {
        RepeatCallers r = new RepeatCallers();
        r.remember("+91 98765 43210", T0);
        assertTrue(r.isRecent("098765-43210", T0 + 1000));
        assertEquals("9876543210", RepeatCallers.digitsTail("+91 (98765) 43210"));
    }
}

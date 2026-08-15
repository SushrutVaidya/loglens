package dev.sushrut.loglens;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link Level}. The ordering is not cosmetic - it IS the comparison
 * behind --level, so a reordering of the enum would silently change which lines
 * a threshold shows.
 */
class LevelTest {

    @Test
    @DisplayName("common spellings map onto the fixed set")
    void spellings() {
        assertEquals(Level.WARN, Level.from("WARNING"));
        assertEquals(Level.WARN, Level.from("warn"));
        assertEquals(Level.ERROR, Level.from("SEVERE"));
        assertEquals(Level.ERROR, Level.from("err"));
        assertEquals(Level.FATAL, Level.from("CRITICAL"));
        assertEquals(Level.FATAL, Level.from("panic"));
        assertEquals(Level.INFO, Level.from("NOTICE"));
    }

    @Test
    @DisplayName("syslog-style numerics map by severity")
    void numerics() {
        assertEquals(Level.FATAL, Level.from("0"));
        assertEquals(Level.ERROR, Level.from("1"));
        assertEquals(Level.WARN, Level.from("2"));
        assertEquals(Level.INFO, Level.from("3"));
    }

    @Test
    @DisplayName("unrecognised input is UNKNOWN, never an exception")
    void unrecognised() {
        assertEquals(Level.UNKNOWN, Level.from(null));
        assertEquals(Level.UNKNOWN, Level.from(""));
        assertEquals(Level.UNKNOWN, Level.from("  "));
        assertEquals(Level.UNKNOWN, Level.from("BANANA"));
    }

    @Test
    @DisplayName("ordering supports --level as a single threshold")
    void thresholdOrdering() {
        assertTrue(Level.ERROR.compareTo(Level.WARN) > 0);
        assertTrue(Level.FATAL.compareTo(Level.ERROR) > 0);
        assertTrue(Level.DEBUG.compareTo(Level.INFO) < 0);
    }

    /**
     * UNKNOWN sits below TRACE deliberately: a level we failed to parse must not
     * outrank a real one. It is separately exempted from filtering in LogLens, so
     * a line whose severity is unreadable is not hidden by a threshold it might
     * well have exceeded.
     */
    @Test
    @DisplayName("UNKNOWN sorts below every real level")
    void unknownSortsLowest() {
        for (Level level : Level.values()) {
            if (level != Level.UNKNOWN) {
                assertTrue(Level.UNKNOWN.compareTo(level) < 0,
                    "UNKNOWN must sort below " + level);
            }
        }
        assertFalse(Level.UNKNOWN.compareTo(Level.TRACE) > 0);
    }
}

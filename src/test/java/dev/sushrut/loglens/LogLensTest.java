package dev.sushrut.loglens;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogLensTest {

    @Test
    void versionFlagAcceptsLowerAndUpperV() {
        assertTrue(new CommandLine(new LogLens()).parseArgs("-v").isVersionHelpRequested());
        assertTrue(new CommandLine(new LogLens()).parseArgs("-V").isVersionHelpRequested());
        assertTrue(new CommandLine(new LogLens()).parseArgs("--version").isVersionHelpRequested());
    }

    @Test
    void helpFlagStillWorks() {
        assertTrue(new CommandLine(new LogLens()).parseArgs("-h").isUsageHelpRequested());
        assertTrue(new CommandLine(new LogLens()).parseArgs("--help").isUsageHelpRequested());
    }

    @Test
    void colourExplicitFlagsWin() {
        assertTrue(Ansi.resolve(Boolean.TRUE).enabled());
        assertFalse(Ansi.resolve(Boolean.FALSE).enabled());
    }

    @Test
    void colourDefaultsOnUnlessNoColorSet() {
        // No --color/--no-color: on by default, off only when NO_COLOR is present.
        assertEquals(System.getenv("NO_COLOR") == null, Ansi.resolve(null).enabled());
    }

    @Test
    void versionComesFromTheBuild() throws Exception {
        String v = new VersionProvider().getVersion()[0];
        assertTrue(v.startsWith("loglens "), v);
        assertTrue(v.matches("loglens \\d+\\.\\d+.*"), v);   // e.g. "loglens 0.2.3"
    }
}

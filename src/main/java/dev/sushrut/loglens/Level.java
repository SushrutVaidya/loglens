package dev.sushrut.loglens;

/**
 * Log severity, ordered so callers can filter with a single threshold
 * ({@code --level WARN} shows WARN and ERROR).
 *
 * {@link #UNKNOWN} sorts below everything and is what a line gets when it has
 * no recognisable level field — it should never be hidden by a level filter,
 * only by an explicit match, so it sits at the bottom of the ordering.
 */
public enum Level {
    UNKNOWN,
    TRACE,
    DEBUG,
    INFO,
    WARN,
    ERROR,
    FATAL;

    /**
     * Maps the many spellings real loggers emit onto our fixed set:
     * "warning" → WARN, "err"/"severe" → ERROR, "critical" → FATAL, numeric
     * syslog-style levels, etc. Case-insensitive. Returns {@link #UNKNOWN}
     * rather than throwing, because an unrecognised level is data, not an error.
     */
    static Level from(Object raw) {
        if (raw == null) return UNKNOWN;
        String s = raw.toString().trim().toUpperCase();
        if (s.isEmpty()) return UNKNOWN;
        return switch (s) {
            case "TRACE", "TRC", "5" -> TRACE;
            case "DEBUG", "DBG", "4" -> DEBUG;
            case "INFO", "INFORMATION", "NOTICE", "3" -> INFO;
            case "WARN", "WARNING", "WRN", "2" -> WARN;
            case "ERROR", "ERR", "SEVERE", "1" -> ERROR;
            case "FATAL", "CRITICAL", "CRIT", "PANIC", "0" -> FATAL;
            default -> UNKNOWN;
        };
    }
}

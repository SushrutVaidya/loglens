package dev.sushrut.loglens;

/**
 * Minimal ANSI styling.
 *
 * A dedicated helper rather than a library because it's ~30 lines and we want
 * one authority on the enabled/disabled decision. When disabled, every method
 * returns its input unchanged, so call sites never branch on colour.
 *
 * Honours the NO_COLOR convention (https://no-color.org) and switches off
 * automatically when stdout isn't a terminal, so `loglens app.log | grep foo`
 * doesn't get escape codes spliced into it.
 */
final class Ansi {
    private final boolean on;

    private Ansi(boolean on) {
        this.on = on;
    }

    /** @param force null = auto-detect; TRUE/FALSE = explicit --color/--no-color */
    static Ansi resolve(Boolean force) {
        if (force != null) return new Ansi(force);
        if (System.getenv("NO_COLOR") != null) return new Ansi(false);
        // System.console() is null when stdout is piped or redirected.
        return new Ansi(System.console() != null);
    }

    boolean enabled() {
        return on;
    }

    private String wrap(String code, String s) {
        return on ? "[" + code + "m" + s + "[0m" : s;
    }

    String dim(String s)    { return wrap("2", s); }
    String bold(String s)   { return wrap("1", s); }
    String red(String s)    { return wrap("31", s); }
    String green(String s)  { return wrap("32", s); }
    String yellow(String s) { return wrap("33", s); }
    String blue(String s)   { return wrap("34", s); }
    String cyan(String s)   { return wrap("36", s); }

    /** Reverse-video, used to make a matched trace id impossible to miss. */
    String highlight(String s) { return wrap("7", s); }
}

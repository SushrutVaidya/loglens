package dev.sushrut.loglens;

import java.util.regex.Pattern;

/**
 * Collapses a log message into a template so near-identical lines group together.
 *
 * This is what turns "412 errors" into "3 distinct problems". Without it a
 * summary is useless, because these are the same failure:
 *
 *   Connection to 10.0.3.14:5432 timed out after 30012ms (request 9f2c-a1)
 *   Connection to 10.0.7.82:5432 timed out after 29984ms (request 3e81-b7)
 *
 * Both collapse to:
 *
 *   Connection to <ip>:<num> timed out after <num>ms (request <id>)
 *
 * The approach is the same idea as Drain/logreduce — replace the parts that vary
 * between occurrences with placeholders — but done with an ordered set of
 * substitutions rather than a parse tree, which is enough for log messages and
 * costs nothing per line.
 *
 * Order matters: the most specific patterns must run first, or a UUID gets
 * chewed up by the hex-and-number rules before it's recognised as an id.
 */
final class MessageTemplate {

    private record Rule(Pattern pattern, String replacement) {}

    private static final Rule[] RULES = {
        // Structured identifiers, most specific first.
        new Rule(Pattern.compile("\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b"), "<uuid>"),
        new Rule(Pattern.compile("\\b\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:Z|[+-]\\d{2}:?\\d{2})?"), "<ts>"),
        new Rule(Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b"), "<ip>"),
        new Rule(Pattern.compile("\\b[\\w.+-]+@[\\w-]+\\.[\\w.]+\\b"), "<email>"),
        new Rule(Pattern.compile("\\bhttps?://\\S+"), "<url>"),
        // Java object identity: com.foo.Bar@4f2a — the hash varies every run.
        new Rule(Pattern.compile("@[0-9a-fA-F]{4,}\\b"), "@<hash>"),
        // Kubernetes pod suffixes: backend-7d9f-x4k2 -> backend-<pod>
        new Rule(Pattern.compile("-[0-9a-f]{8,10}-[0-9a-z]{5}\\b"), "-<pod>"),
        // Filesystem paths, before the number rule eats digits inside them.
        new Rule(Pattern.compile("(?<=\\s)/(?:[\\w.-]+/)+[\\w.-]+"), "<path>"),
        // Long hex blobs (hashes, tokens) before generic numbers.
        new Rule(Pattern.compile("\\b[0-9a-fA-F]{16,}\\b"), "<hex>"),
        // Short alphanumeric correlation ids like 9f2c-a1.
        new Rule(Pattern.compile("\\b[0-9a-f]{4,}-[0-9a-z]{2,}\\b"), "<id>"),
        // Quoted values vary per occurrence far more often than they identify a
        // distinct problem.
        new Rule(Pattern.compile("'[^']{0,80}'"), "'<str>'"),
        new Rule(Pattern.compile("\"[^\"]{0,80}\""), "\"<str>\""),
        // Normalise the many spellings of "no value" so services that disagree
        // (None / null / nil / NULL) still group with each other.
        // Note this deliberately does NOT group with <num>: "queue depth: 0" and
        // "queue depth: None" mean different things, and conflating every number
        // with every null to merge one recurring Airflow event would over-collapse
        // templates everywhere else.
        new Rule(Pattern.compile("\\b(?:None|null|nil|NULL)\\b"), "<null>"),
        // Numbers last, so every rule above gets first refusal on its digits.
        // No trailing \b: durations and sizes carry unit suffixes ("30012ms",
        // "512MiB") and \b fails between a digit and a letter, which left the
        // varying number in the template and stopped identical failures from
        // grouping. The leading \b still prevents chewing digits out of
        // identifiers like "x4k2".
        new Rule(Pattern.compile("\\b\\d+(?:\\.\\d+)?"), "<num>"),
    };

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private MessageTemplate() {}

    static String collapse(String message) {
        String out = message;
        for (Rule rule : RULES) {
            out = rule.pattern().matcher(out).replaceAll(rule.replacement());
        }
        // Normalise whitespace so wrapping differences don't split a group, and
        // cap length: two messages agreeing for 160 chars are the same problem.
        out = WHITESPACE.matcher(out).replaceAll(" ").strip();
        return out.length() > 160 ? out.substring(0, 160) : out;
    }
}

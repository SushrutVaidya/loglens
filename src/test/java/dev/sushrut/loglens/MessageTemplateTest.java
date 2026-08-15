package dev.sushrut.loglens;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Tests for message templating - the surface that decides whether --stats says
 * "3 distinct problems" or "412 problems", and therefore whether the feature
 * works at all.
 *
 * The grouping tests matter more than the placeholder tests: what callers
 * actually depend on is that two occurrences of the same failure produce the
 * SAME template, not that the template reads a particular way.
 */
class MessageTemplateTest {

    @Nested
    @DisplayName("grouping: equivalent failures must collapse together")
    class Grouping {

        @Test
        @DisplayName("timeouts differing only by IP and duration group")
        void timeoutsGroup() {
            String a = MessageTemplate.collapse("Connection to 10.0.3.14:5432 timed out after 30012ms");
            String b = MessageTemplate.collapse("Connection to 10.0.7.82:5432 timed out after 29984ms");
            assertEquals(a, b);
        }

        /**
         * Regression: the number rule was \b\d+(\.\d+)?\b. The trailing \b never
         * matches in "30012ms" because a digit followed by a letter is not a word
         * boundary, so the duration survived into the template and identical
         * failures never grouped - three Postgres timeouts showed as three
         * separate 1x rows instead of one 3x finding.
         */
        @Test
        @DisplayName("a number with a unit suffix is templated (the \\b regression)")
        void numberWithUnitSuffixIsCollapsed() {
            assertEquals("took <num>ms", MessageTemplate.collapse("took 30012ms"));
            assertEquals("heap <num>MiB", MessageTemplate.collapse("heap 512MiB"));
            assertEquals("waited <num>s", MessageTemplate.collapse("waited 30s"));
        }

        @Test
        @DisplayName("differing spellings of null group with each other")
        void nullSpellingsGroup() {
            String none = MessageTemplate.collapse("resource_version: None");
            String nil = MessageTemplate.collapse("resource_version: nil");
            String nul = MessageTemplate.collapse("resource_version: null");
            assertEquals(none, nil);
            assertEquals(none, nul);
        }

        /**
         * A number and a null are NOT the same value, so they get different
         * placeholders and do not group. The cost is real and known: Airflow
         * alternates "resource_version: 0" and "resource_version: None" for one
         * recurring watch event, so --stats reports it as two findings rather than
         * one. Accepted deliberately - conflating every number with every null to
         * merge that case would over-collapse templates everywhere else.
         */
        @Test
        @DisplayName("a number and a null are kept distinct")
        void numberAndNullDoNotGroup() {
            assertNotEquals(
                MessageTemplate.collapse("resource_version: 0"),
                MessageTemplate.collapse("resource_version: None"));
        }

        @Test
        @DisplayName("distinct failures stay distinct")
        void differentFailuresDoNotGroup() {
            assertNotEquals(
                MessageTemplate.collapse("Connection to 10.0.3.14:5432 timed out"),
                MessageTemplate.collapse("Authentication failed for user admin"));
        }

        @Test
        @DisplayName("whitespace differences do not split a group")
        void whitespaceNormalised() {
            assertEquals(
                MessageTemplate.collapse("disk  quota\texceeded"),
                MessageTemplate.collapse("disk quota exceeded"));
        }
    }

    @Nested
    @DisplayName("placeholders: specific patterns win over general ones")
    class Placeholders {

        /**
         * Rule order is load-bearing. A UUID reaching the number rule first would
         * be shredded into <num>-<num>-... and never group.
         */
        @Test
        @DisplayName("a UUID is one placeholder, not a pile of numbers")
        void uuidNotShredded() {
            assertEquals("user <uuid> not found",
                MessageTemplate.collapse("user 9f2c1a3b-4d5e-6f70-8a9b-0c1d2e3f4a5b not found"));
        }

        @Test
        @DisplayName("IPs are recognised before their digits are")
        void ipBeforeNumbers() {
            assertEquals("dial <ip>", MessageTemplate.collapse("dial 192.168.1.1"));
        }

        @Test
        @DisplayName("embedded timestamps collapse")
        void timestampCollapsed() {
            assertEquals("started at <ts>",
                MessageTemplate.collapse("started at 2026-08-15T09:00:49.306Z"));
        }

        @Test
        @DisplayName("Java object identity hashes collapse")
        void objectHashCollapsed() {
            assertEquals("closing org.postgresql.jdbc.PgConnection@<hash>",
                MessageTemplate.collapse("closing org.postgresql.jdbc.PgConnection@4f2a1b9c"));
        }

        @Test
        @DisplayName("quoted values collapse, since they vary per occurrence")
        void quotedValuesCollapsed() {
            assertEquals(
                MessageTemplate.collapse("constraint \"users_pkey\" violated"),
                MessageTemplate.collapse("constraint \"orders_pkey\" violated"));
        }

        @Test
        @DisplayName("identifiers keep their digits")
        void identifiersNotChewed() {
            // Leading \b prevents matching mid-identifier; "x4k2" has no boundary
            // before the 4, so it must survive intact.
            assertEquals("pod x4k2 ready", MessageTemplate.collapse("pod x4k2 ready"));
        }
    }

    @Nested
    @DisplayName("robustness")
    class Robustness {

        @Test
        @DisplayName("empty message is handled")
        void emptyMessage() {
            assertEquals("", MessageTemplate.collapse(""));
        }

        @Test
        @DisplayName("templates are capped so long messages still group")
        void longMessageCapped() {
            String long1 = "prefix ".repeat(60) + "tail A";
            String long2 = "prefix ".repeat(60) + "tail B";
            // Beyond the cap the messages are treated as the same problem.
            assertEquals(MessageTemplate.collapse(long1), MessageTemplate.collapse(long2));
        }
    }
}

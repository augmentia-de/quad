package de.augmentia.quad.core.workflow.schedule;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.BitSet;
import java.util.Optional;

/**
 * Minimal 5-field cron parser (minute, hour, day-of-month, month, day-of-week) —
 * a Java port of the Python reference's croniter usage for automation next-run
 * computation. Supports numbers, ranges, steps and lists; numeric day-of-week
 * 0 and 7 both mean Sunday. Named fields (MON, JAN) are not supported.
 *
 * <p>Follows cron semantics: when both day-of-month and day-of-week are
 * restricted, the job fires when EITHER matches (Vixie-cron OR rule).
 */
public final class CronExpression {

    private CronExpression() {
    }

    public static boolean isValid(String expr) {
        if (expr == null || expr.isBlank()) {
            return false;
        }
        try {
            parse(expr);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Next fire time strictly after {@code after}, or empty if it never matches. */
    public static Optional<Instant> next(String expr, Instant after, ZoneId zone) {
        try {
            return next(parse(expr), after, zone);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static final int MAX_YEARS = 5;

    private static Optional<Instant> next(Parsed p, Instant after, ZoneId zone) {
        LocalDateTime cur = LocalDateTime.ofInstant(after, zone);
        cur = cur.plusMinutes(1).withSecond(0).withNano(0);
        LocalDateTime limit = cur.plusYears(MAX_YEARS);
        while (cur.isBefore(limit)) {
            if (!p.months.get(cur.getMonthValue())) {
                cur = cur.plusMonths(1).withDayOfMonth(1).withHour(0).withMinute(0);
                continue;
            }
            if (!p.dayMatches(cur)) {
                cur = cur.plusDays(1).withHour(0).withMinute(0);
                continue;
            }
            if (!p.hours.get(cur.getHour())) {
                cur = cur.plusHours(1).withMinute(0);
                continue;
            }
            if (!p.minutes.get(cur.getMinute())) {
                cur = cur.plusMinutes(1);
                continue;
            }
            return Optional.of(cur.atZone(zone).toInstant());
        }
        return Optional.empty();
    }

    private static Parsed parse(String expr) {
        String[] parts = expr.trim().split("\\s+");
        if (parts.length != 5) {
            throw new IllegalArgumentException("cron needs exactly 5 fields");
        }
        BitSet minutes = parseField(parts[0], 0, 59);
        BitSet hours = parseField(parts[1], 0, 23);
        BitSet dom = parseField(parts[2], 1, 31);
        BitSet months = parseField(parts[3], 1, 12);
        BitSet dow = parseField(parts[4], 0, 7);
        if (dow.get(7)) {
            dow.set(0);
            dow.clear(7);
        }
        return new Parsed(minutes, hours, dom, months, dow, isRestricted(parts[2]), isRestricted(parts[4]));
    }

    private static boolean isRestricted(String field) {
        String f = field.trim();
        return !"*".equals(f) || f.contains("/");
    }

    private static BitSet parseField(String field, int min, int max) {
        BitSet set = new BitSet(max + 1);
        boolean any = false;
        for (String token : field.split(",")) {
            token = token.trim();
            if (token.isEmpty()) {
                throw new IllegalArgumentException("empty field token in " + field);
            }
            int step = 1;
            int slash = token.indexOf('/');
            if (slash >= 0) {
                step = Integer.parseInt(token.substring(slash + 1).trim());
                if (step <= 0) {
                    throw new IllegalArgumentException("step must be positive");
                }
                token = token.substring(0, slash).trim();
            }
            boolean wildcard = token.equals("*");
            int lo;
            int hi;
            if (wildcard) {
                lo = min;
                hi = max;
            } else {
                int dash = token.indexOf('-');
                if (dash >= 0) {
                    lo = Integer.parseInt(token.substring(0, dash).trim());
                    hi = Integer.parseInt(token.substring(dash + 1).trim());
                } else {
                    lo = Integer.parseInt(token.trim());
                    hi = (slash >= 0) ? max : lo;
                }
                if (lo < min || lo > max || hi < min || hi > max || lo > hi) {
                    throw new IllegalArgumentException("value out of range in " + field);
                }
            }
            for (int v = lo; v <= hi; v += step) {
                set.set(v);
            }
            any = true;
        }
        if (!any) {
            throw new IllegalArgumentException("empty field " + field);
        }
        return set;
    }

    private static final class Parsed {
        final BitSet minutes;
        final BitSet hours;
        final BitSet dom;
        final BitSet months;
        final BitSet dow;
        final boolean domRestricted;
        final boolean dowRestricted;

        Parsed(BitSet minutes, BitSet hours, BitSet dom, BitSet months, BitSet dow,
               boolean domRestricted, boolean dowRestricted) {
            this.minutes = minutes;
            this.hours = hours;
            this.dom = dom;
            this.months = months;
            this.dow = dow;
            this.domRestricted = domRestricted;
            this.dowRestricted = dowRestricted;
        }

        boolean dayMatches(LocalDateTime date) {
            boolean domMatch = dom.get(date.getDayOfMonth());
            boolean dowMatch = dow.get(date.getDayOfWeek().getValue() % 7); // Sun(7) -> 0
            if (domRestricted && dowRestricted) {
                return domMatch || dowMatch;
            }
            if (domRestricted) {
                return domMatch;
            }
            return dowRestricted ? dowMatch : true;
        }
    }
}

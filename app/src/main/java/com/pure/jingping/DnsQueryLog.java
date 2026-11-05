package com.pure.jingping;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Keeps a small in-memory ring of recently resolved/requested hostnames so the
 * accessibility service can correlate an ad on screen with the domains that
 * were just contacted. Nothing here leaves the device.
 */
final class DnsQueryLog {
    private static final int MAX_ENTRIES = 600;
    private static final ArrayDeque<Entry> ENTRIES = new ArrayDeque<>();

    private DnsQueryLog() {
    }

    static synchronized void record(String domain, boolean blocked) {
        String clean = normalize(domain);
        if (clean.isEmpty()) {
            return;
        }
        ENTRIES.addLast(new Entry(clean, System.currentTimeMillis(), blocked));
        while (ENTRIES.size() > MAX_ENTRIES) {
            ENTRIES.removeFirst();
        }
    }

    static synchronized List<Entry> recent(long windowMs) {
        long since = System.currentTimeMillis() - Math.max(0L, windowMs);
        List<Entry> result = new ArrayList<>();
        for (Entry entry : ENTRIES) {
            if (entry.at >= since) {
                result.add(entry);
            }
        }
        return result;
    }

    private static String normalize(String domain) {
        if (domain == null) {
            return "";
        }
        String clean = domain.trim().toLowerCase(Locale.ROOT);
        while (clean.endsWith(".")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        if (clean.length() < 3 || clean.length() > 253
                || !clean.matches("[a-z0-9][a-z0-9.-]*")) {
            return "";
        }
        return clean;
    }

    static final class Entry {
        final String domain;
        final long at;
        final boolean blocked;

        Entry(String domain, long at, boolean blocked) {
            this.domain = domain;
            this.at = at;
            this.blocked = blocked;
        }
    }
}

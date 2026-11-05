package com.pure.jingping;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

final class DnsRules {
    private static final String TAG = "JingPingDns";
    private final Set<String> blocked;

    private DnsRules(Set<String> blocked) {
        this.blocked = blocked;
    }

    static DnsRules load(Context context) {
        LinkedHashSet<String> rules = new LinkedHashSet<>();
        try (InputStream input = context.getAssets().open("ad_domains.txt");
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                addRule(rules, line);
            }
        } catch (Exception e) {
            Log.w(TAG, "built-in rules failed", e);
        }
        for (String rule : Prefs.getDnsCustomRules(context)) {
            addRule(rules, rule);
        }
        for (String rule : Prefs.getDnsLearnedRules(context)) {
            addRule(rules, rule);
        }
        return new DnsRules(rules);
    }

    private static void addRule(Set<String> rules, String raw) {
        String rule = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (rule.isEmpty() || rule.startsWith("#") || rule.startsWith("!")
                || rule.startsWith("/") || rule.contains(" ")) {
            return;
        }
        if (rule.startsWith("||")) {
            rule = rule.substring(2);
        }
        if (rule.startsWith("*.")) {
            rule = rule.substring(2);
        }
        while (rule.startsWith(".")) {
            rule = rule.substring(1);
        }
        if (rule.endsWith("^")) {
            rule = rule.substring(0, rule.length() - 1);
        }
        if (rule.endsWith(".")) {
            rule = rule.substring(0, rule.length() - 1);
        }
        if (rule.length() < 3 || rule.length() > 253
                || !rule.matches("[a-z0-9][a-z0-9.-]*")) {
            return;
        }
        rules.add(rule);
    }

    boolean isBlocked(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        String name = host.toLowerCase(Locale.ROOT);
        while (name.endsWith(".")) {
            name = name.substring(0, name.length() - 1);
        }
        for (String rule : blocked) {
            if (name.equals(rule) || name.endsWith("." + rule)) {
                return true;
            }
        }
        return false;
    }

    int size() {
        return blocked.size();
    }
}

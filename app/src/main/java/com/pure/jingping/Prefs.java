package com.pure.jingping;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Prefs {
    private static final Pattern TITLE_WITH_EPISODE = Pattern.compile(
            "^(.{3,80}?)\\s*第\\s*(\\d{1,4})\\s*集$");
    private static final Pattern EPISODE_ONLY = Pattern.compile(
            "^第\\s*(\\d{1,4})\\s*集$");
    private static final String FILE = "jingping";
    private static final String AUTO_SKIP = "auto_skip";
    private static final String AUTO_RETURN = "auto_return";
    private static final String COVER_ADS = "cover_ads";
    private static final String FORCE_SKIP_COUNTDOWN = "force_skip_countdown";
    private static final String CLOSE_REWARD_WIDGETS = "close_reward_widgets";
    private static final String GLOBAL_MODE = "global_mode";
    private static final String TARGETS = "targets";
    private static final String SKIPPED_COUNT = "skipped_count";
    private static final String RETURN_COUNT = "return_count";
    private static final String LAST_EVENT = "last_event";
    private static final String DNS_FILTER = "dns_filter";
    private static final String DNS_QUERY_COUNT = "dns_query_count";
    private static final String DNS_BLOCKED_COUNT = "dns_blocked_count";
    private static final String DNS_LAST_EVENT = "dns_last_event";
    private static final String DNS_CUSTOM_RULES = "dns_custom_rules";
    private static final String DNS_LEARNED_RULES = "dns_learned_rules";
    private static final String DNS_PENDING_RULES = "dns_pending_rules";
    private static final String SERIES_TITLE = "series_title";
    private static final String SERIES_EPISODE = "series_episode";
    private static final String SERIES_SEASON = "series_season";
    private static final String FORCE_SKIP_TITLE = "force_skip_title";
    private static final String FORCE_SKIP_EPISODE = "force_skip_episode";
    private static final String FORCE_SKIP_SEASON = "force_skip_season";
    private static final String FORCE_SKIP_STARTED_WALL = "force_skip_started_wall";

    private Prefs() {
    }

    private static SharedPreferences get(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static boolean autoSkip(Context context) {
        return get(context).getBoolean(AUTO_SKIP, true);
    }

    static void setAutoSkip(Context context, boolean value) {
        get(context).edit().putBoolean(AUTO_SKIP, value).apply();
    }

    static boolean autoReturn(Context context) {
        return get(context).getBoolean(AUTO_RETURN, true);
    }

    static void setAutoReturn(Context context, boolean value) {
        get(context).edit().putBoolean(AUTO_RETURN, value).apply();
    }

    static boolean coverAds(Context context) {
        return get(context).getBoolean(COVER_ADS, true);
    }

    static void setCoverAds(Context context, boolean value) {
        get(context).edit().putBoolean(COVER_ADS, value).apply();
    }

    static boolean forceSkipCountdown(Context context) {
        return get(context).getBoolean(FORCE_SKIP_COUNTDOWN, true);
    }

    static void setForceSkipCountdown(Context context, boolean value) {
        get(context).edit().putBoolean(FORCE_SKIP_COUNTDOWN, value).apply();
    }

    static boolean closeRewardWidgets(Context context) {
        return get(context).getBoolean(CLOSE_REWARD_WIDGETS, true);
    }

    static void setCloseRewardWidgets(Context context, boolean value) {
        get(context).edit().putBoolean(CLOSE_REWARD_WIDGETS, value).apply();
    }

    static boolean globalMode(Context context) {
        return get(context).getBoolean(GLOBAL_MODE, true);
    }

    static void setGlobalMode(Context context, boolean value) {
        get(context).edit().putBoolean(GLOBAL_MODE, value).apply();
    }

    static Set<String> getTargets(Context context) {
        String stored = get(context).getString(TARGETS, null);
        if (stored == null) {
            return new HashSet<>(Arrays.asList(TargetApps.PACKAGES));
        }
        Set<String> result = new HashSet<>();
        for (String item : stored.split(",")) {
            if (!item.isEmpty()) {
                result.add(item);
            }
        }
        return result;
    }

    static void setTarget(Context context, String pkg, boolean enabled) {
        Set<String> targets = getTargets(context);
        if (enabled) {
            targets.add(pkg);
        } else {
            targets.remove(pkg);
        }
        get(context).edit().putString(TARGETS, String.join(",", targets)).apply();
    }

    static int skippedCount(Context context) {
        return get(context).getInt(SKIPPED_COUNT, 0);
    }

    static int returnCount(Context context) {
        return get(context).getInt(RETURN_COUNT, 0);
    }

    static String lastEvent(Context context) {
        return get(context).getString(LAST_EVENT, "");
    }

    static void bumpSkipped(Context context) {
        synchronized (Prefs.class) {
            int value = skippedCount(context) + 1;
            get(context).edit().putInt(SKIPPED_COUNT, value).apply();
        }
    }

    static void bumpReturned(Context context) {
        synchronized (Prefs.class) {
            int value = returnCount(context) + 1;
            get(context).edit().putInt(RETURN_COUNT, value).apply();
        }
    }

    static void recordEvent(Context context, String message) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(new Date());
        get(context).edit().putString(LAST_EVENT, time + "  " + message).apply();
    }

    static boolean dnsFilter(Context context) {
        return get(context).getBoolean(DNS_FILTER, false);
    }

    static void setDnsFilter(Context context, boolean value) {
        get(context).edit().putBoolean(DNS_FILTER, value).apply();
    }

    static int dnsQueryCount(Context context) {
        return get(context).getInt(DNS_QUERY_COUNT, 0);
    }

    static int dnsBlockedCount(Context context) {
        return get(context).getInt(DNS_BLOCKED_COUNT, 0);
    }

    static String dnsLastEvent(Context context) {
        return get(context).getString(DNS_LAST_EVENT, "");
    }

    static void recordDnsQuery(Context context, boolean blocked, String domain) {
        synchronized (Prefs.class) {
            SharedPreferences prefs = get(context);
            SharedPreferences.Editor editor = prefs.edit();
            editor.putInt(DNS_QUERY_COUNT, prefs.getInt(DNS_QUERY_COUNT, 0) + 1);
            if (blocked) {
                editor.putInt(DNS_BLOCKED_COUNT, prefs.getInt(DNS_BLOCKED_COUNT, 0) + 1);
                String time = new SimpleDateFormat("HH:mm:ss", Locale.CHINA)
                        .format(new Date());
                editor.putString(DNS_LAST_EVENT, time + "  已拦截 " + domain);
            } else if (prefs.getInt(DNS_QUERY_COUNT, 0) % 40 == 0) {
                String time = new SimpleDateFormat("HH:mm:ss", Locale.CHINA)
                        .format(new Date());
                editor.putString(DNS_LAST_EVENT, time + "  已放行 " + domain);
            }
            editor.apply();
        }
    }

    static Set<String> getDnsCustomRules(Context context) {
        String stored = get(context).getString(DNS_CUSTOM_RULES, "");
        Set<String> result = new LinkedHashSet<>();
        if (stored == null || stored.isEmpty()) {
            return result;
        }
        for (String item : stored.split(",")) {
            if (!item.isEmpty()) {
                result.add(item);
            }
        }
        return result;
    }

    static void addDnsCustomRule(Context context, String domain) {
        Set<String> rules = getDnsCustomRules(context);
        if (rules.add(domain)) {
            get(context).edit().putString(DNS_CUSTOM_RULES, String.join(",", rules)).apply();
        }
    }

    static Set<String> getDnsLearnedRules(Context context) {
        return splitRules(get(context).getString(DNS_LEARNED_RULES, ""));
    }

    static void addDnsLearnedRule(Context context, String domain) {
        Set<String> rules = getDnsLearnedRules(context);
        if (rules.add(domain)) {
            get(context).edit()
                    .putString(DNS_LEARNED_RULES, String.join(",", rules))
                    .apply();
        }
    }

    static void removeDnsLearnedRule(Context context, String domain) {
        Set<String> rules = getDnsLearnedRules(context);
        if (rules.remove(domain)) {
            get(context).edit()
                    .putString(DNS_LEARNED_RULES, String.join(",", rules))
                    .apply();
        }
    }

    static Set<String> getDnsPendingRules(Context context) {
        return splitRules(get(context).getString(DNS_PENDING_RULES, ""));
    }

    static void addDnsPendingRules(Context context, Set<String> domains) {
        if (domains == null || domains.isEmpty()) {
            return;
        }
        Set<String> rules = getDnsPendingRules(context);
        for (String domain : domains) {
            if (rules.size() >= 120) {
                break;
            }
            rules.add(domain);
        }
        get(context).edit()
                .putString(DNS_PENDING_RULES, String.join(",", rules))
                .apply();
    }

    static void removeDnsPendingRule(Context context, String domain) {
        Set<String> rules = getDnsPendingRules(context);
        if (rules.remove(domain)) {
            get(context).edit()
                    .putString(DNS_PENDING_RULES, String.join(",", rules))
                    .apply();
        }
    }

    private static Set<String> splitRules(String stored) {
        Set<String> result = new LinkedHashSet<>();
        if (stored == null || stored.isEmpty()) {
            return result;
        }
        for (String item : stored.split(",")) {
            if (!item.isEmpty()) {
                result.add(item);
            }
        }
        return result;
    }

    static String lastSeriesTitle(Context context) {
        return cleanSeriesTitle(get(context).getString(SERIES_TITLE, ""));
    }

    static int lastSeriesEpisode(Context context) {
        int episode = get(context).getInt(SERIES_EPISODE, -1);
        Matcher matcher = TITLE_WITH_EPISODE.matcher(
                get(context).getString(SERIES_TITLE, "").trim());
        if (matcher.matches()) {
            try {
                episode = Math.max(episode, Integer.parseInt(matcher.group(2)));
            } catch (NumberFormatException ignored) {
            }
        }
        return episode;
    }

    static int lastSeriesSeason(Context context) {
        return get(context).getInt(SERIES_SEASON, -1);
    }

    static void rememberSeriesContext(Context context, String title, int episode,
                                      int season) {
        SharedPreferences.Editor editor = get(context).edit();
        String cleanTitle = cleanSeriesTitle(title);
        if (!cleanTitle.isEmpty()) {
            String previousTitle = get(context).getString(SERIES_TITLE, "");
            if (!cleanTitle.equals(previousTitle)) {
                editor.remove(SERIES_SEASON);
            }
            editor.putString(SERIES_TITLE, cleanTitle);
        }
        if (episode > 0) {
            editor.putInt(SERIES_EPISODE, episode);
        }
        if (season > 0) {
            editor.putInt(SERIES_SEASON, season);
        }
        editor.apply();
    }

    static String cleanSeriesTitle(String title) {
        if (title == null) {
            return "";
        }
        String clean = title.trim();
        Matcher matcher = TITLE_WITH_EPISODE.matcher(clean);
        if (matcher.matches()) {
            clean = matcher.group(1).trim();
        } else if (EPISODE_ONLY.matcher(clean).matches()) {
            clean = "";
        }
        return clean;
    }

    static void clearSeriesContext(Context context) {
        get(context).edit()
                .remove(SERIES_TITLE)
                .remove(SERIES_EPISODE)
                .remove(SERIES_SEASON)
                .apply();
    }

    static void rememberForceSkip(Context context, String title, int episode,
                                  int season, long startedWallMs) {
        String cleanTitle = cleanSeriesTitle(title);
        if (cleanTitle.isEmpty() || episode <= 0 || startedWallMs <= 0) {
            clearForceSkip(context);
            return;
        }
        get(context).edit()
                .putString(FORCE_SKIP_TITLE, cleanTitle)
                .putInt(FORCE_SKIP_EPISODE, episode)
                .putInt(FORCE_SKIP_SEASON, season)
                .putLong(FORCE_SKIP_STARTED_WALL, startedWallMs)
                .commit();
    }

    static String forceSkipTitle(Context context) {
        return cleanSeriesTitle(get(context).getString(FORCE_SKIP_TITLE, ""));
    }

    static int forceSkipEpisode(Context context) {
        return get(context).getInt(FORCE_SKIP_EPISODE, -1);
    }

    static int forceSkipSeason(Context context) {
        return get(context).getInt(FORCE_SKIP_SEASON, -1);
    }

    static long forceSkipStartedWall(Context context) {
        return get(context).getLong(FORCE_SKIP_STARTED_WALL, 0L);
    }

    static void clearForceSkip(Context context) {
        get(context).edit()
                .remove(FORCE_SKIP_TITLE)
                .remove(FORCE_SKIP_EPISODE)
                .remove(FORCE_SKIP_SEASON)
                .remove(FORCE_SKIP_STARTED_WALL)
                .commit();
    }
}

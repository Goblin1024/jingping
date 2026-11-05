package com.pure.jingping;

import android.content.Context;
import android.util.Log;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Learns ad hostnames from the domains contacted shortly before an ad appears.
 *
 * Only hostnames whose labels look like a known ad exchange/SDK are blocked
 * automatically. Everything else is put in the "pending" list for the user to
 * confirm, because video CDNs often live on the same parent domains as ads.
 */
final class AdDomainLearner {
    private static final String TAG = "JingPingLearn";
    private static final long WINDOW_MS = 120_000L;
    private static final int MAX_NEW_PENDING = 24;

    private static final Pattern AD_LABEL = Pattern.compile(
            "(^|[.-])(ad|ads|adx|advert|advertising|adload|adlog|adapi|"
                    + "adservice|adserver|pangle|pangolin|gdt|dsp|mobads|"
                    + "oceanengine|doubleclick|googlesyndication|adcolony|"
                    + "applovin|unityads|vungle|chartboost|inmobi|mintegral|"
                    + "ironsrc|supersonicads|fyber|tapjoy|mopub|moatads|"
                    + "criteo|pubmatic|rubiconproject|openx|adnxs|"
                    + "indexexchange|smaato)(\\d*)([.-]|$)");
    private static final Pattern SUSPICIOUS_LABEL = Pattern.compile(
            "(^|[.-])(track|tracking|stat|stats|statistics|analytics|metric|"
                    + "monitor|collect|promo|promotion|union)(\\d*)([.-]|$)");

    private AdDomainLearner() {
    }

    static boolean learn(Context context) {
        DnsRules rules = DnsRules.load(context);
        Set<String> learned = Prefs.getDnsLearnedRules(context);
        Set<String> pending = Prefs.getDnsPendingRules(context);
        Set<String> custom = Prefs.getDnsCustomRules(context);
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        LinkedHashSet<String> auto = new LinkedHashSet<>();
        LinkedHashSet<String> suggest = new LinkedHashSet<>();

        for (DnsQueryLog.Entry entry : DnsQueryLog.recent(WINDOW_MS)) {
            String domain = entry.domain;
            if (entry.blocked || !seen.add(domain)) {
                continue;
            }
            if (rules.isBlocked(domain) || learned.contains(domain)
                    || pending.contains(domain) || custom.contains(domain)) {
                continue;
            }
            String lower = domain.toLowerCase(Locale.ROOT);
            if (AD_LABEL.matcher(lower).find()) {
                auto.add(domain);
            } else if (suggest.size() < MAX_NEW_PENDING
                    && SUSPICIOUS_LABEL.matcher(lower).find()) {
                suggest.add(domain);
            }
        }

        for (String domain : auto) {
            Prefs.addDnsLearnedRule(context, domain);
        }
        Prefs.addDnsPendingRules(context, suggest);
        if (!auto.isEmpty() || !suggest.isEmpty()) {
            Log.i(TAG, "learned auto=" + auto.size() + " pending=" + suggest.size()
                    + " autoDomains=" + auto);
            Prefs.recordEvent(context, "已自动学习 " + auto.size()
                    + " 个广告域名，待确认 " + suggest.size() + " 个");
        }
        return !auto.isEmpty();
    }
}

package com.pure.jingping;

import android.content.Context;
import android.content.pm.PackageManager;

import java.util.LinkedHashMap;
import java.util.Map;

final class TargetApps {
    static final String PHOENIX = "com.phoenix.read";
    static final String KYLIN = "com.kylin.read";
    static final String DRAGON = "com.dragon.read";

    static final String[] PACKAGES = {PHOENIX, KYLIN, DRAGON};
    static final String[] LABELS = {"红果短剧", "红果漫剧", "番茄小说 / 短剧"};

    private TargetApps() {
    }

    static boolean isKnown(String pkg) {
        if (pkg == null) {
            return false;
        }
        for (String target : PACKAGES) {
            if (target.equals(pkg)) {
                return true;
            }
        }
        return false;
    }

    static String labelFor(String pkg) {
        for (int i = 0; i < PACKAGES.length; i++) {
            if (PACKAGES[i].equals(pkg)) {
                return LABELS[i];
            }
        }
        return pkg;
    }

    static boolean isAllowed(Context context, String pkg) {
        return isKnown(pkg) && Prefs.getTargets(context).contains(pkg);
    }

    static Map<String, Boolean> installedMap(Context context) {
        Map<String, Boolean> result = new LinkedHashMap<>();
        PackageManager pm = context.getPackageManager();
        for (String pkg : PACKAGES) {
            boolean installed;
            try {
                pm.getPackageInfo(pkg, 0);
                installed = true;
            } catch (PackageManager.NameNotFoundException e) {
                installed = false;
            }
            result.put(pkg, installed);
        }
        return result;
    }
}

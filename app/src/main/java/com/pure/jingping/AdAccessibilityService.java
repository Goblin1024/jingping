package com.pure.jingping;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.accessibilityservice.GestureDescription;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AdAccessibilityService extends AccessibilityService {
    private static final String TAG = "JingPing";
    private static final long SCAN_INTERVAL_MS = 280L;
    private static final long ACTION_COOLDOWN_MS = 1600L;
    private static final long MAX_GLOBAL_LAUNCH_MS = 15000L;
    private static final long FORCE_SKIP_TIMEOUT_MS = 50000L;
    private static final long FORCE_SKIP_STAGE_TIMEOUT_MS = 19000L;
    private static final long FORCE_CATALOG_LOAD_GRACE_MS = 2600L;
    private static final long FORCE_CONFIRM_RETRY_MS = 1400L;
    private static final long FORCE_TARGET_SETTLE_MS = 1800L;
    private static final long FORCE_SKIP_COOLDOWN_MS = 6500L;
    private static final long AD_TRANSITION_LOCK_MS = 5200L;
    private static final long AD_CLEAR_WAIT_MS = 5000L;
    private static final long VISUAL_SCAN_INTERVAL_MS = 700L;
    private static final long VISUAL_AD_COOLDOWN_MS = 8000L;
    private static final long SERIES_CONTEXT_TTL_MS = 4 * 60 * 60 * 1000L;
    private static final long BLANK_AD_GRACE_MS = 5000L;
    private static final long BLANK_AD_COOLDOWN_MS = 15000L;
    private static final long REWARD_PAUSE_SETTLE_MS = 4200L;
    private static final long REWARD_ACTION_SETTLE_MS = 1800L;
    private static final long REWARD_ACTION_COOLDOWN_MS = 900L;
    private static final long REWARD_RETRY_MS = 1800L;
    private static final long REWARD_SUCCESS_COOLDOWN_MS = 10000L;
    private static final long REWARD_FAILURE_COOLDOWN_MS = 30000L;
    private static final int MAX_NODES = 2600;
    private static final int MAX_DEPTH = 48;
    private static final int FORCE_IDLE = 0;
    private static final int FORCE_WAIT_HISTORY = 1;
    private static final int FORCE_WAIT_PLAYER = 2;
    private static final int FORCE_OPEN_CATALOG = 3;
    private static final int FORCE_SELECT_CATALOG_EPISODE = 4;
    private static final int FORCE_CONFIRM_EPISODE = 5;
    private static final int REWARD_IDLE = 0;
    private static final int REWARD_PAUSING = 1;
    private static final int REWARD_CLOSING = 2;
    private static final int REWARD_CONFIRMING = 3;

    private static final Pattern SKIP_PATTERN = Pattern.compile(
            "(?iu)^(跳过|跳过广告|跳过\\s*\\d+\\s*[s秒]?|\\d+\\s*[s秒]?\\s*跳过|"
                    + "跳过广告\\s*\\d+\\s*[s秒]?|关闭广告|关闭\\s*\\d+\\s*[s秒]?|"
                    + "skip(?:\\s*ad)?|close|×|✕|x)$");
    private static final Pattern NUMBER_COUNTDOWN = Pattern.compile(
            "(\\d{1,2})\\s*(?:s|秒)\\s*(?:后|可|即可)");
    private static final Pattern SKIP_COUNTDOWN = Pattern.compile(
            "(?:跳过|关闭)\\s*(\\d{1,2})\\s*(?:s|秒)?");
    private static final Pattern CHINESE_COUNTDOWN = Pattern.compile(
            "([一二三四五六七八九十]{1,2})\\s*秒\\s*(?:后|可|即可)");
    private static final Pattern EPISODE_NUMBER = Pattern.compile(
            "^第\\s*(\\d{1,4})\\s*集$");
    private static final Pattern TITLE_WITH_EPISODE = Pattern.compile(
            "^(.{3,80}?)\\s*第\\s*(\\d{1,4})\\s*集$");
    private static final Pattern EPISODE_PROGRESS = Pattern.compile(
            "^\\d{1,4}\\s*集\\s*/\\s*\\d{1,4}\\s*集$");
    private static final Pattern EPISODE_RANGE = Pattern.compile(
            "^\\d{1,4}\\s*-\\s*\\d{1,4}$");
    private static final Pattern CATALOG_RANGE = Pattern.compile(
            "^(\\d{1,4})\\s*-\\s*(\\d{1,4})$");
    private static final Pattern CATALOG_TOTAL = Pattern.compile(
            "^(?:(?:已完结|全|更新至)\\s*)?共?\\s*(\\d{1,4})\\s*集$");
    private static final Pattern SEASON_NUMBER = Pattern.compile(
            "第\\s*(\\d{1,3}|[一二三四五六七八九十]{1,3})\\s*季");
    private static final Pattern SEASON_LABEL = Pattern.compile(
            "^第\\s*(\\d{1,3}|[一二三四五六七八九十]{1,3})\\s*季$");
    private static final Pattern REWARD_WIDGET_LABEL = Pattern.compile(
            "^(?:立即领取|听读赚钱|看视频领取|再领金币|"
                    + "\\d[\\d,，]*\\s*金币|金币\\s*[+＋]?\\s*\\d[\\d,，]*)$");
    private static final Pattern REWARD_DIALOG_TITLE = Pattern.compile(
            "关闭(?:金币红包|播放器)挂件");
    private static final Pattern REWARD_PERMANENT_CLOSE = Pattern.compile(
            "^(?:关闭|隐藏|确认关闭|永久关闭)$");

    private static final Set<String> JUMP_PACKAGES = new HashSet<>(Arrays.asList(
            "com.tencent.mm",
            "com.tencent.mobileqq",
            "com.vivo.browser",
            "mark.via",
            "com.quark.browser",
            "org.mozilla.firefox",
            "com.android.chrome",
            "com.bbk.appstore",
            "com.android.vending",
            "com.taobao.taobao",
            "com.jingdong.app.mall",
            "com.xunmeng.pinduoduo",
            "com.sankuai.meituan",
            "com.dianping.v1",
            "com.autonavi.minimap",
            "com.baidu.searchbox",
            "com.ss.android.ugc.aweme",
            "com.xingin.xhs",
            "com.zhihu.android",
            "com.eg.android.AlipayGphone"
    ));

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable scanRunnable = new Runnable() {
        @Override
        public void run() {
            scanScheduled = false;
            scanNow();
        }
    };
    private final Runnable heartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            refreshForegroundFromActiveRoot();
            scanNow();
            handler.postDelayed(this, 1200L);
        }
    };

    private boolean scanScheduled;
    private boolean scanningTargetPackage;
    private String foregroundPackage = "";
    private String foregroundActivity = "";
    private long foregroundSince;
    private long lastTouchAt;
    private long lastClickAt;
    private long lastActionAt;
    private long lastAutoReturnAt;
    private String lastTargetPackage = "";
    private long lastTargetAt;
    private long adFirstSeenAt;
    private long blankAdSince;
    private long lastBlankAdSwipeAt;
    private long suppressBlindSwipeUntil;
    private long awaitingEpisodeSince;
    private long forceSkipStageStartedAt;
    private long lastStableEpisodeAt;
    private String lastDetectionLog = "";
    private boolean waitForAdClear;
    private long waitForAdClearSince;
    private int adSwipeAttempts;
    private long adSwipeAttemptAt;
    private int inPlayerSwipeAttempts;
    private long inPlayerSwipeAt;
    private boolean inPlayerSwipeArmed;
    private int inPlayerSwipeFromEpisode = -1;
    private long countdownFirstSeenAt;
    private long lastAdLearnAt;
    private int forceSkipStage = FORCE_IDLE;
    private long forceSkipStartedAt;
    private long lastForceSkipAt;
    private String lastSeriesTitle = "";
    private long lastSeriesTitleAt;
    private int lastEpisodeNumber = -1;
    private int lastSeriesSeason = -1;
    private String lastLoggedTitle = "";
    private String forceSkipTitle = "";
    private int forceSkipTargetEpisode = -1;
    private int forceSkipSeason = -1;
    private int forceSkipCatalogScrolls;
    private boolean forceSkipCatalogSectionClicked;
    private boolean forceSkipCatalogPickerOpened;
    private int forceSkipSearchAttempts;
    private int forceSkipConfirmRetries;
    private int forceSkipBackAttempts;
    private int forceSkipCatalogBackAttempts;
    private int forceSkipObservedEpisode = -1;
    private long forceSkipObservedAt;
    private int awaitingEpisodeFrom = -1;
    private int lastStableEpisode = -1;
    private boolean screenshotInFlight;
    private long lastVisualScanAt;
    private long lastVisualAdActionAt;
    private long lastScreenshotFailureLogAt;
    private TextRecognizer textRecognizer;
    private int rewardState = REWARD_IDLE;
    private long rewardStateAt;
    private long lastRewardActionAt;
    private long suppressRewardUntil;
    private boolean rewardPauseStartedByUs;
    private int rewardPauseTaps;
    private int rewardCloseTries;
    private int rewardConfirmTries;

    private WindowManager windowManager;
    private View coverView;
    private TextView coverTitle;
    private TextView coverDetail;
    private int coverSeconds = -1;
    private boolean coverFailed;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                    | AccessibilityEvent.TYPE_WINDOWS_CHANGED
                    | AccessibilityEvent.TYPE_VIEW_CLICKED
                    | AccessibilityEvent.TYPE_TOUCH_INTERACTION_START
                    | AccessibilityEvent.TYPE_TOUCH_INTERACTION_END;
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                    | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                    | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
            info.notificationTimeout = 75;
            setServiceInfo(info);
        }

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        long now = SystemClock.uptimeMillis();
        String storedTitle = Prefs.lastSeriesTitle(this);
        int storedEpisode = Prefs.lastSeriesEpisode(this);
        int storedSeason = Prefs.lastSeriesSeason(this);
        int storedTitleEpisode = episodeFromCombinedTitle(storedTitle);
        if (isEpisodeNavigationLabel(storedTitle)
                || looksLikePromotionalCopy(storedTitle)) {
            Prefs.clearSeriesContext(this);
            lastSeriesTitle = "";
            lastEpisodeNumber = -1;
            storedEpisode = -1;
        } else {
            lastSeriesTitle = normalizeSeriesTitle(storedTitle);
            lastEpisodeNumber = Math.max(storedEpisode, storedTitleEpisode);
            lastSeriesSeason = seasonNumber(lastSeriesTitle) > 0
                    ? seasonNumber(lastSeriesTitle) : storedSeason;
            lastSeriesTitleAt = now;
        }
        lastStableEpisode = lastEpisodeNumber > 0 ? lastEpisodeNumber : -1;
        lastStableEpisodeAt = lastStableEpisode > 0 ? now : 0;
        restoreForceSkip(now);
        awaitingEpisodeFrom = -1;
        awaitingEpisodeSince = 0;
        lastTouchAt = now;
        lastClickAt = now;
        Log.i(TAG, "accessibility service connected, restored title="
                + lastSeriesTitle + " season=" + lastSeriesSeason
                + " episode=" + lastStableEpisode);
        scheduleScan(250);
        handler.removeCallbacks(heartbeatRunnable);
        handler.postDelayed(heartbeatRunnable, 900);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }

        long now = SystemClock.uptimeMillis();
        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START
                || type == AccessibilityEvent.TYPE_TOUCH_INTERACTION_END) {
            lastTouchAt = now;
        }
        if (type == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            lastClickAt = now;
            lastTouchAt = now;
        }

        String pkg = asString(event.getPackageName());
        String activity = asString(event.getClassName());
        if (type == AccessibilityEvent.TYPE_VIEW_CLICKED
                && !pkg.isEmpty()
                && !pkg.equals(getPackageName())
                && !isSystemUiPackage(pkg)) {
            captureClickedCardSeason(event.getSource());
        }
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && !pkg.isEmpty()
                && !pkg.equals(getPackageName())
                && !isSystemUiPackage(pkg)) {
            onForegroundChanged(pkg, activity, now);
        }

        if (shouldScanPackage(pkg)) {
            scheduleScan(type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ? 130 : 45);
        }
    }

    @Override
    public void onInterrupt() {
        hideCover();
        resetRewardWidgetState();
        handler.removeCallbacksAndMessages(null);
    }

    @Override
    public void onDestroy() {
        hideCover();
        resetRewardWidgetState();
        handler.removeCallbacksAndMessages(null);
        if (textRecognizer != null) {
            textRecognizer.close();
            textRecognizer = null;
        }
        super.onDestroy();
    }

    private void onForegroundChanged(String pkg, String activity, long now) {
        if (isSystemUiPackage(pkg)) {
            return;
        }
        String previous = foregroundPackage;
        boolean previousTarget = TargetApps.isAllowed(this, previous);

        if (!pkg.equals(previous)) {
            foregroundActivity = "";
            waitForAdClear = false;
            waitForAdClearSince = 0;
            resetRewardWidgetState();
            if (previousTarget) {
                lastTargetPackage = previous;
                lastTargetAt = now;
            }

            if (previousTarget
                    && !TargetApps.isAllowed(this, pkg)
                    && isJumpPackage(pkg)
                    && now - foregroundSince > 900
                    && now - lastTouchAt > 1100
                    && now - lastClickAt > 1100) {
                armAutoReturn(pkg, now);
            }

            foregroundPackage = pkg;
            foregroundSince = now;
            Log.i(TAG, "foreground=" + pkg + " activity=" + activity);
            if (TargetApps.isAllowed(this, pkg)) {
                lastTargetPackage = pkg;
                lastTargetAt = now;
            }
            if (!TargetApps.isAllowed(this, pkg)) {
                hideCover();
                resetAdState();
                resetForceSkip();
                lastStableEpisode = -1;
                lastStableEpisodeAt = 0;
            }
        }

        if (!activity.isEmpty() && !activity.startsWith("android.")) {
            foregroundActivity = activity;
        }
    }

    private void captureClickedCardSeason(AccessibilityNodeInfo clicked) {
        if (clicked == null || lastSeriesTitle.isEmpty()) {
            return;
        }
        AccessibilityNodeInfo node = clicked;
        for (int i = 0; node != null && i < 8; i++) {
            int season = findSeasonNearMatchingTitle(node, lastSeriesTitle);
            if (season > 0) {
                if (season != lastSeriesSeason) {
                    lastEpisodeNumber = -1;
                    lastStableEpisode = -1;
                    lastStableEpisodeAt = 0;
                    lastSeriesSeason = season;
                    if (Prefs.lastSeriesSeason(this) > 0
                            && Prefs.lastSeriesSeason(this) != season) {
                        Prefs.clearSeriesContext(this);
                    }
                    Prefs.rememberSeriesContext(this, lastSeriesTitle,
                            lastEpisodeNumber, lastSeriesSeason);
                    Log.i(TAG, "captured clicked card season=" + season
                            + " for " + lastSeriesTitle);
                }
                return;
            }
            node = node.getParent();
        }
    }

    private void armAutoReturn(final String jumpedPackage, long now) {
        if (!Prefs.autoReturn(this) || now - lastAutoReturnAt < 3500) {
            return;
        }
        lastAutoReturnAt = now;
        final String from = lastTargetPackage;
        handler.postDelayed(() -> {
            if (!jumpedPackage.equals(foregroundPackage)) {
                return;
            }
            performGlobalAction(GLOBAL_ACTION_BACK);
            Prefs.bumpReturned(this);
            Prefs.recordEvent(this,
                    "已返回 " + TargetApps.labelFor(from) + "，拦截摇一摇跳转");
            Log.i(TAG, "auto-return from " + jumpedPackage + " to " + from);
            handler.postDelayed(() -> {
                refreshForegroundFromActiveRoot();
                if (jumpedPackage.equals(foregroundPackage)) {
                    performGlobalAction(GLOBAL_ACTION_BACK);
                    Log.i(TAG, "second back for " + jumpedPackage);
                }
            }, 1600);
        }, 320);
    }

    private boolean shouldScanPackage(String pkg) {
        if (pkg == null || pkg.isEmpty() || pkg.equals(getPackageName())) {
            return false;
        }
        if (TargetApps.isAllowed(this, pkg)) {
            return true;
        }
        return Prefs.globalMode(this)
                && !isSystemUiPackage(pkg)
                && SystemClock.uptimeMillis() - foregroundSince <= MAX_GLOBAL_LAUNCH_MS;
    }

    private boolean isSystemUiPackage(String pkg) {
        return pkg.equals("android")
                || pkg.equals("com.android.systemui")
                || pkg.equals("com.vivo.smartmultiwindow")
                || pkg.contains("inputmethod")
                || pkg.contains("permissioncontroller")
                || pkg.contains("systemui");
    }

    private boolean isJumpPackage(String pkg) {
        return JUMP_PACKAGES.contains(pkg);
    }

    private void scheduleScan(long delayMs) {
        if (scanScheduled) {
            return;
        }
        scanScheduled = true;
        handler.postDelayed(scanRunnable, delayMs);
    }

    private void scanNow() {
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager != null && !powerManager.isInteractive()) {
            hideCover();
            resetAdState();
            return;
        }
        if (!Prefs.autoSkip(this)) {
            hideCover();
            resetAdState();
            resetRewardWidgetState();
            return;
        }

        String pkg = foregroundPackage;
        refreshForegroundFromActiveRoot();
        pkg = foregroundPackage;
        if (pkg.isEmpty()) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                pkg = asString(root.getPackageName());
                if (!pkg.isEmpty() && !pkg.equals(getPackageName())) {
                    foregroundPackage = pkg;
                    foregroundSince = SystemClock.uptimeMillis();
                }
            }
        }
        if (pkg.isEmpty() || pkg.equals(getPackageName())) {
            hideCover();
            return;
        }

        boolean target = TargetApps.isAllowed(this, pkg);
        long now = SystemClock.uptimeMillis();
        boolean inLaunchWindow = now - foregroundSince <= MAX_GLOBAL_LAUNCH_MS;
        if (!target && !(Prefs.globalMode(this) && inLaunchWindow)) {
            hideCover();
            resetAdState();
            resetRewardWidgetState();
            return;
        }

        if (advanceForceSkip(target, now)) {
            return;
        }

        ScanResult result = scanPackage(pkg);
        boolean resultShowsAd = result.hasAdBadge
                || result.hasCountdown
                || result.hasSwipeHint;
        if (resultShowsAd) {
            result.seriesTitle = "";
            result.fallbackSeriesTitle = "";
        }
        boolean seriesContextChanged = false;
        boolean seriesTitleChanged = false;
        int resultSeason = result.seasonNumber > 0
                ? result.seasonNumber : seasonNumber(result.seriesTitle);
        if (!result.seriesTitle.isEmpty()) {
            seriesTitleChanged = !result.seriesTitle.equals(lastSeriesTitle);
            seriesContextChanged = seriesTitleChanged;
            lastSeriesTitle = result.seriesTitle;
            lastSeriesTitleAt = now;
            if (!lastSeriesTitle.equals(lastLoggedTitle)) {
                lastLoggedTitle = lastSeriesTitle;
                Log.i(TAG, "series title=" + lastSeriesTitle
                        + " season=" + resultSeason);
            }
            if (resultSeason > 0 && resultSeason != lastSeriesSeason) {
                if (lastSeriesSeason > 0 && seasonNumber(result.seriesTitle) <= 0) {
                    Log.i(TAG, "ignoring card-only season change while browsing: "
                            + lastSeriesSeason + " -> " + resultSeason);
                } else {
                    if (lastSeriesSeason > 0) {
                        Log.i(TAG, "series season changed from " + lastSeriesSeason
                                + " to " + resultSeason
                                + ", resetting episode context");
                        lastEpisodeNumber = -1;
                        lastStableEpisode = -1;
                        lastStableEpisodeAt = 0;
                        Prefs.clearSeriesContext(this);
                    }
                    lastSeriesSeason = resultSeason;
                    seriesContextChanged = true;
                }
            } else if (resultSeason > 0 && lastSeriesSeason <= 0) {
                lastSeriesSeason = resultSeason;
                seriesContextChanged = true;
            }
        } else if (resultSeason > 0 && lastSeriesSeason <= 0
                && !lastSeriesTitle.isEmpty()) {
            lastSeriesSeason = resultSeason;
            seriesContextChanged = true;
            Log.i(TAG, "captured card season=" + resultSeason
                    + " for " + lastSeriesTitle);
        }
        if (seriesTitleChanged) {
            lastStableEpisode = -1;
            lastStableEpisodeAt = 0;
        }
        if (result.episodeNumber > 0
                && result.episodeNumber != lastEpisodeNumber) {
            lastEpisodeNumber = result.episodeNumber;
            seriesContextChanged = true;
        }
        if (result.episodeNumber > 0
                && (!result.seriesTitle.isEmpty() || result.hasPlayerUi)
                && !result.hasAdBadge
                && !result.hasCountdown) {
            lastStableEpisode = result.episodeNumber;
            lastStableEpisodeAt = now;
        }
        if (seriesContextChanged) {
            Prefs.rememberSeriesContext(this, lastSeriesTitle, lastEpisodeNumber,
                    lastSeriesSeason);
        }
        boolean hasActionableAd = result.hasAdBadge
                || result.hasCountdown
                || (result.bestSkip != null && (target
                || result.bestSkip.explicitAd
                || (Prefs.globalMode(this) && !result.bestSkip.genericClose)));
        if (result.hasCountdown) {
            if (countdownFirstSeenAt == 0) {
                countdownFirstSeenAt = now;
            }
        } else {
            countdownFirstSeenAt = 0;
        }
        maybeLearnAdDomains(result, target, now);
        maybeCaptureVisualAd(pkg, result, target, now);

        String detection = pkg + "|badge=" + result.hasAdBadge
                + "|count=" + result.countdownSeconds
                + "|swipe=" + result.hasSwipeHint
                + "|player=" + result.hasPlayerUi
                + "|ep=" + result.episodeNumber
                + "|stable=" + lastStableEpisode
                + "|reward=" + result.hasRewardWidget
                + "|rewardX=" + (result.rewardCloseNode != null)
                + "|rewardDialog=" + result.hasRewardCloseDialog
                + "|skip=" + (result.bestSkip == null ? "-" : result.bestSkip.label);
        if (!detection.equals(lastDetectionLog)) {
            lastDetectionLog = detection;
            Log.i(TAG, "detected " + detection);
        }

        if (holdForAdTransition(result, now)) {
            return;
        }

        if (maybeSwipeAdInPlayer(result, target, now)) {
            return;
        }

        if (tryPreciseInterstitialSkip(result, target, now)) {
            return;
        }

        if (handleRewardWidget(target, result, now)) {
            return;
        }

        if (!hasActionableAd && handleBlankAd(result, target, now)) {
            return;
        }
        if (!hasActionableAd) {
            hideCover();
            resetAdState();
            return;
        }
        blankAdSince = 0;

        if (result.hasAdBadge && waitForAdClear
                && now - waitForAdClearSince < AD_CLEAR_WAIT_MS) {
            hideCover();
            String locked = "locked:" + pkg;
            if (!locked.equals(lastDetectionLog)) {
                lastDetectionLog = locked;
                Log.i(TAG, "waiting for ad marker to clear");
            }
            scheduleScan(SCAN_INTERVAL_MS);
            return;
        }

        if (!result.hasAdBadge) {
            waitForAdClear = false;
            waitForAdClearSince = 0;
        } else if (waitForAdClear) {
            Log.i(TAG, "ad marker remained past wait window, allowing fallback");
            waitForAdClear = false;
            waitForAdClearSince = 0;
        }

        if (result.hasAdBadge) {
            if (adFirstSeenAt == 0) {
                adFirstSeenAt = now;
            }
            if (Prefs.coverAds(this) && result.hasCountdown) {
                showCover(result);
            }
        }

        if (!result.hasCountdown) {
            hideCover();
        }

        SkipCandidate skip = result.bestSkip;
        if (skip != null && skip.genericClose && !result.hasAdBadge
                && !result.hasCountdown && !result.hasSwipeHint) {
            skip = null;
        }
        if (skip != null && !target && !inLaunchWindow) {
            skip = null;
        }

        boolean forcedCountdown = result.hasCountdown
                && result.countdownSeconds > 1;
        if (!forcedCountdown && skip != null
                && now - lastActionAt >= ACTION_COOLDOWN_MS) {
            if (tryClick(skip)) {
                lastActionAt = now;
                Prefs.bumpSkipped(this);
                Prefs.recordEvent(this, "已点击跳过广告 · " + skip.label);
                Log.i(TAG, "clicked skip: " + skip.label);
                hideCover();
                waitForAdClear = true;
                waitForAdClearSince = now;
                scheduleScan(650);
                return;
            }
        }

        if (result.hasCountdown) {
            scheduleScan(SCAN_INTERVAL_MS);
            return;
        }

        if (now < suppressBlindSwipeUntil) {
            scheduleScan(SCAN_INTERVAL_MS);
            return;
        }

        if (now - lastActionAt < ACTION_COOLDOWN_MS || adFirstSeenAt == 0) {
            scheduleScan(SCAN_INTERVAL_MS);
            return;
        }

        if (now - adFirstSeenAt < 700) {
            scheduleScan(SCAN_INTERVAL_MS);
            return;
        }

        if (adSwipeAttempts >= 1) {
            scheduleScan(SCAN_INTERVAL_MS);
            return;
        }

        if (result.hasAdBadge
                && (result.hasSwipeHint || isSeriesActivity(foregroundActivity))) {
            if (swipeToNext()) {
                lastActionAt = now;
                adSwipeAttempts++;
                adSwipeAttemptAt = now;
                waitForAdClear = true;
                waitForAdClearSince = now;
                armAdTransition(result, now);
                Prefs.bumpSkipped(this);
                Prefs.recordEvent(this, "已上滑离开广告页");
                Log.i(TAG, "swiped away ad page in " + foregroundActivity);
                scheduleScan(260);
            } else {
                scheduleScan(SCAN_INTERVAL_MS);
            }
            return;
        }

        if (target && result.hasAdBadge) {
            if (swipeToNext()) {
                lastActionAt = now;
                adSwipeAttempts++;
                adSwipeAttemptAt = now;
                waitForAdClear = true;
                waitForAdClearSince = now;
                armAdTransition(result, now);
                Prefs.bumpSkipped(this);
                Prefs.recordEvent(this, "已离开广告画面");
                scheduleScan(260);
            } else {
                scheduleScan(SCAN_INTERVAL_MS);
            }
            return;
        }

        scheduleScan(SCAN_INTERVAL_MS);
    }

    private boolean handleRewardWidget(boolean target, ScanResult result, long now) {
        boolean rewardContext = isSeriesActivity(foregroundActivity)
                || ((result.hasPlayerUi || result.episodeNumber > 0)
                && (result.hasRewardWidget
                || result.hasRewardCloseDialog
                || result.rewardCloseNode != null
                || rewardState != REWARD_IDLE));
        if (!target || !Prefs.closeRewardWidgets(this) || !rewardContext) {
            if (rewardState != REWARD_IDLE) {
                resetRewardWidgetState();
            }
            return false;
        }
        if ((result.hasAdBadge || result.hasCountdown || result.hasSwipeHint)
                && !result.hasRewardCloseDialog
                && rewardState == REWARD_IDLE) {
            return false;
        }

        if (result.hasRewardCloseDialog) {
            if (rewardState == REWARD_IDLE) {
                rewardStateAt = now;
                rewardCloseTries = 0;
                rewardConfirmTries = 0;
                rewardPauseStartedByUs = false;
            }
            rewardState = REWARD_CONFIRMING;

            boolean retryReady = rewardConfirmTries == 0
                    || now - rewardStateAt >= REWARD_RETRY_MS;
            if (result.rewardPermanentCloseButton != null
                    && rewardConfirmTries < 2
                    && retryReady
                    && now - lastRewardActionAt >= REWARD_ACTION_COOLDOWN_MS
                    && tryClickNode(result.rewardPermanentCloseButton.node,
                    result.rewardPermanentCloseButton.bounds)) {
                lastRewardActionAt = now;
                rewardStateAt = now;
                rewardConfirmTries++;
                Log.i(TAG, "clicked permanent reward widget close");
                scheduleScan(700);
                return true;
            }

            if (rewardConfirmTries > 0 && now - rewardStateAt > 7000L) {
                performGlobalAction(GLOBAL_ACTION_BACK);
                Log.w(TAG, "reward close dialog did not dismiss; backed out");
                resetRewardWidgetState();
                suppressRewardUntil = now + REWARD_FAILURE_COOLDOWN_MS;
            }
            scheduleScan(320);
            return true;
        }

        if (result.rewardCloseNode != null) {
            if (rewardState == REWARD_IDLE) {
                rewardState = REWARD_CLOSING;
                rewardStateAt = now;
                rewardCloseTries = 0;
                rewardConfirmTries = 0;
                rewardPauseStartedByUs = false;
            }

            boolean retryReady = rewardCloseTries == 0
                    || now - rewardStateAt >= REWARD_RETRY_MS;
            if (rewardState != REWARD_CONFIRMING
                    && rewardCloseTries < 2
                    && retryReady
                    && now - lastRewardActionAt >= REWARD_ACTION_COOLDOWN_MS
                    && tryClickNode(result.rewardCloseNode,
                    result.rewardCloseBounds)) {
                rewardState = REWARD_CLOSING;
                rewardStateAt = now;
                lastRewardActionAt = now;
                rewardCloseTries++;
                Log.i(TAG, "clicked reward widget close X");
                scheduleScan(650);
                return true;
            }
            scheduleScan(320);
            return true;
        }

        if (result.hasRewardWidget) {
            if (now < suppressRewardUntil && rewardState == REWARD_IDLE) {
                scheduleScan(1200);
                return true;
            }

            if (rewardState == REWARD_IDLE) {
                if (!isMediaPlaying()) {
                    scheduleScan(900);
                    return true;
                }
                if (now - lastRewardActionAt >= REWARD_ACTION_COOLDOWN_MS
                        && tapVideoCenter()) {
                    rewardState = REWARD_PAUSING;
                    rewardStateAt = now;
                    lastRewardActionAt = now;
                    lastActionAt = now;
                    rewardPauseStartedByUs = true;
                    rewardPauseTaps = 1;
                    rewardCloseTries = 0;
                    rewardConfirmTries = 0;
                    Log.i(TAG, "reward widget detected; paused video to reveal close X");
                }
                scheduleScan(380);
                return true;
            }

            if (rewardState == REWARD_PAUSING) {
                if (now - rewardStateAt < 1500L) {
                    scheduleScan(320);
                    return true;
                }
                if (isMediaPlaying() && rewardPauseTaps < 2
                        && tapVideoCenter()) {
                    rewardStateAt = now;
                    lastRewardActionAt = now;
                    lastActionAt = now;
                    rewardPauseTaps++;
                    scheduleScan(450);
                    return true;
                }
                if (now - rewardStateAt < REWARD_PAUSE_SETTLE_MS) {
                    scheduleScan(420);
                    return true;
                }

                boolean resume = rewardPauseStartedByUs && !isMediaPlaying();
                resetRewardWidgetState();
                suppressRewardUntil = now + REWARD_FAILURE_COOLDOWN_MS;
                if (resume) {
                    tapVideoCenter();
                }
                Log.i(TAG, "reward close X was not exposed; stopped pausing");
                scheduleScan(800);
                return true;
            }

            if (now - rewardStateAt > 7000L) {
                boolean resume = rewardPauseStartedByUs && !isMediaPlaying();
                resetRewardWidgetState();
                suppressRewardUntil = now + REWARD_FAILURE_COOLDOWN_MS;
                if (resume) {
                    tapVideoCenter();
                }
                Log.w(TAG, "reward widget did not close; backing off");
            }
            scheduleScan(420);
            return true;
        }

        if (rewardState == REWARD_CLOSING
                || rewardState == REWARD_CONFIRMING) {
            if (now - rewardStateAt < REWARD_ACTION_SETTLE_MS) {
                scheduleScan(360);
                return true;
            }
            boolean resume = rewardPauseStartedByUs && !isMediaPlaying();
            Prefs.bumpSkipped(this);
            Prefs.recordEvent(this, "已永久关闭红果金币 / 红包挂件");
            Log.i(TAG, "reward widget closed permanently; resume=" + resume);
            resetRewardWidgetState();
            suppressRewardUntil = now + REWARD_SUCCESS_COOLDOWN_MS;
            if (resume) {
                tapVideoCenter();
            }
            scheduleScan(760);
            return true;
        }

        if (rewardState == REWARD_PAUSING) {
            if (now - rewardStateAt < REWARD_PAUSE_SETTLE_MS) {
                scheduleScan(420);
                return true;
            }
            boolean resume = rewardPauseStartedByUs && !isMediaPlaying();
            resetRewardWidgetState();
            suppressRewardUntil = now + REWARD_FAILURE_COOLDOWN_MS;
            if (resume) {
                tapVideoCenter();
            }
            Log.i(TAG, "reward widget disappeared before its close X appeared");
            scheduleScan(800);
            return true;
        }

        return false;
    }

    private boolean isMediaPlaying() {
        AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        return audioManager != null && audioManager.isMusicActive();
    }

    private boolean tapVideoCenter() {
        Rect screen = currentDisplayBounds();
        return tapScreen(screen.left + screen.width() * 0.5f,
                screen.top + screen.height() * 0.45f);
    }

    private boolean tapScreen(float x, float y) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 70))
                .build();
        return dispatchGesture(gesture, null, null);
    }

    private void resetRewardWidgetState() {
        rewardState = REWARD_IDLE;
        rewardStateAt = 0;
        rewardPauseStartedByUs = false;
        rewardPauseTaps = 0;
        rewardCloseTries = 0;
        rewardConfirmTries = 0;
    }

    private void maybeLearnAdDomains(ScanResult result, boolean target, long now) {
        if (!target
                || !(result.hasAdBadge || result.hasCountdown || result.hasSwipeHint)
                || now - lastAdLearnAt < 15000L) {
            return;
        }
        lastAdLearnAt = now;
        if (AdDomainLearner.learn(this)) {
            DnsFilterVpnService.reloadRules();
            Log.i(TAG, "applied newly learned ad domains");
        }
    }

    private void maybeCaptureVisualAd(String pkg, ScanResult result,
                                      boolean target, long now) {
        if (Build.VERSION.SDK_INT < 30
                || !target
                || !Prefs.forceSkipCountdown(this)
                || forceSkipStage != FORCE_IDLE
                || screenshotInFlight
                || now - lastVisualScanAt < VISUAL_SCAN_INTERVAL_MS
                || result.hasAdBadge
                || result.hasCountdown
                || !isLandscapeDisplay()) {
            return;
        }

        lastVisualScanAt = now;
        screenshotInFlight = true;
        try {
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY, getMainExecutor(),
                    new TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(ScreenshotResult screenshot) {
                            processVisualAdScreenshot(pkg, screenshot);
                        }

                        @Override
                        public void onFailure(int errorCode) {
                            screenshotInFlight = false;
                            if (now - lastScreenshotFailureLogAt > 15000L) {
                                lastScreenshotFailureLogAt = now;
                                Log.w(TAG, "visual ad screenshot failed: " + errorCode);
                            }
                            scheduleScan(SCAN_INTERVAL_MS);
                        }
                    });
        } catch (Exception e) {
            screenshotInFlight = false;
            if (now - lastScreenshotFailureLogAt > 15000L) {
                lastScreenshotFailureLogAt = now;
                Log.w(TAG, "visual ad screenshot unavailable", e);
            }
        }
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.R)
    private void processVisualAdScreenshot(String pkg, ScreenshotResult screenshot) {
        Bitmap software = null;
        Bitmap crop = null;
        try {
            android.hardware.HardwareBuffer buffer = screenshot.getHardwareBuffer();
            Bitmap hardware = Bitmap.wrapHardwareBuffer(buffer, screenshot.getColorSpace());
            if (hardware == null) {
                buffer.close();
                screenshotInFlight = false;
                scheduleScan(SCAN_INTERVAL_MS);
                return;
            }
            software = hardware.copy(Bitmap.Config.ARGB_8888, false);
            hardware.recycle();
            buffer.close();
            if (software == null) {
                screenshotInFlight = false;
                scheduleScan(SCAN_INTERVAL_MS);
                return;
            }

            int left = 0;
            int top = software.getHeight() * 2 / 100;
            int right = software.getWidth();
            int bottom = software.getHeight() * 36 / 100;
            crop = Bitmap.createBitmap(software, left, top,
                    Math.max(1, right - left), Math.max(1, bottom - top));
            final Bitmap ocrBitmap = crop;

            TextRecognizer recognizer = getTextRecognizer();
            recognizer.process(InputImage.fromBitmap(ocrBitmap, 0))
                    .addOnSuccessListener(text -> {
                        ocrBitmap.recycle();
                        VisualTextInfo info = analyzeVisualText(text);
                        screenshotInFlight = false;
                        if (info.seriesTitle != null
                                && !info.seriesTitle.isEmpty()
                                && info.episodeNumber > 0) {
                            handler.post(() -> applyVisualSeriesContext(info));
                        }
                        if (info.visualAd) {
                            Log.i(TAG, "visual countdown ad detected in fullscreen");
                            handler.post(() -> handleVisualAdDetected(pkg, info));
                        } else {
                            scheduleScan(SCAN_INTERVAL_MS);
                        }
                    })
                    .addOnFailureListener(error -> {
                        ocrBitmap.recycle();
                        screenshotInFlight = false;
                        if (SystemClock.uptimeMillis() - lastScreenshotFailureLogAt > 15000L) {
                            lastScreenshotFailureLogAt = SystemClock.uptimeMillis();
                            Log.w(TAG, "visual ad OCR failed", error);
                        }
                        scheduleScan(SCAN_INTERVAL_MS);
                    });
        } catch (Exception e) {
            if (crop != null) {
                crop.recycle();
                crop = null;
            }
            screenshotInFlight = false;
            if (SystemClock.uptimeMillis() - lastScreenshotFailureLogAt > 15000L) {
                lastScreenshotFailureLogAt = SystemClock.uptimeMillis();
                Log.w(TAG, "visual ad screenshot processing failed", e);
            }
            scheduleScan(SCAN_INTERVAL_MS);
        } finally {
            if (software != null) {
                software.recycle();
            }
        }
    }

    private TextRecognizer getTextRecognizer() {
        if (textRecognizer == null) {
            textRecognizer = TextRecognition.getClient(
                    new ChineseTextRecognizerOptions.Builder().build());
        }
        return textRecognizer;
    }

    private VisualTextInfo analyzeVisualText(Text text) {
        VisualTextInfo info = new VisualTextInfo();
        for (Text.TextBlock block : text.getTextBlocks()) {
            String blockText = block.getText().replaceAll("\\s+", "").trim();
            if (isVisualAdLabel(blockText)) {
                info.visualAd = true;
            }
            Matcher blockTitle = TITLE_WITH_EPISODE.matcher(blockText);
            if (blockTitle.matches()) {
                String title = normalizeSeriesTitle(blockTitle.group(1));
                int episode = safeInt(blockTitle.group(2));
                if (!title.isEmpty() && hanCharacterCount(title) >= 3) {
                    info.seriesTitle = title;
                    info.episodeNumber = episode;
                }
            }
            for (Text.Line line : block.getLines()) {
                String compact = line.getText().replaceAll("\\s+", "");
                if (isVisualAdLabel(compact)) {
                    info.visualAd = true;
                }
                Matcher lineTitle = TITLE_WITH_EPISODE.matcher(compact);
                if (lineTitle.matches()) {
                    String title = normalizeSeriesTitle(lineTitle.group(1));
                    int episode = safeInt(lineTitle.group(2));
                    if (!title.isEmpty() && hanCharacterCount(title) >= 3) {
                        info.seriesTitle = title;
                        info.episodeNumber = episode;
                    }
                }
            }
        }
        return info;
    }

    private boolean isVisualAdLabel(String compact) {
        if (compact == null || compact.isEmpty()) {
            return false;
        }
        return compact.contains("后可继续上滑观看")
                || compact.contains("继续上滑观看")
                || compact.contains("上滑继续观看")
                || compact.contains("上滑跳过广告")
                || compact.contains("倒计时")
                || (compact.contains("广告") && compact.length() <= 10);
    }

    private void applyVisualSeriesContext(VisualTextInfo info) {
        if (info.seriesTitle == null
                || info.seriesTitle.isEmpty()
                || info.episodeNumber <= 0) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        boolean changed = !info.seriesTitle.equals(lastSeriesTitle)
                || info.episodeNumber != lastEpisodeNumber;
        lastSeriesTitle = info.seriesTitle;
        lastSeriesTitleAt = now;
        lastEpisodeNumber = info.episodeNumber;
        lastStableEpisode = info.episodeNumber;
        lastStableEpisodeAt = now;
        Prefs.rememberSeriesContext(this, lastSeriesTitle, lastStableEpisode,
                seasonNumber(lastSeriesTitle) > 0
                        ? seasonNumber(lastSeriesTitle) : lastSeriesSeason);
        if (changed) {
            Log.i(TAG, "visual series context=" + lastSeriesTitle
                    + " episode=" + lastStableEpisode);
        }
    }

    private void handleVisualAdDetected(String pkg, VisualTextInfo info) {
        long now = SystemClock.uptimeMillis();
        if (!pkg.equals(foregroundPackage)
                || !TargetApps.isAllowed(this, pkg)
                || !Prefs.autoSkip(this)
                || !Prefs.forceSkipCountdown(this)
                || forceSkipStage != FORCE_IDLE
                || !isLandscapeDisplay()
                || now - lastForceSkipAt < FORCE_SKIP_COOLDOWN_MS
                || now - lastVisualAdActionAt < VISUAL_AD_COOLDOWN_MS) {
            scheduleScan(SCAN_INTERVAL_MS);
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        int visibleEpisode = readVisibleEpisode(root);
        int sourceEpisode = lastStableEpisode > 0
                ? lastStableEpisode
                : (visibleEpisode > 0 ? visibleEpisode : info.episodeNumber);
        int target = sourceEpisode + 1;
        String title = resolveSeriesTitle();
        if (title.isEmpty() || sourceEpisode <= 0) {
            Log.i(TAG, "visual ad detected without episode context, waiting safely");
            scheduleScan(SCAN_INTERVAL_MS);
            return;
        }

        String reason = "visual target=" + target
                + " stable=" + lastStableEpisode
                + " visible=" + visibleEpisode
                + " ocr=" + info.episodeNumber;
        if (beginForceSkipToEpisode(now, title, target, reason)) {
            lastVisualAdActionAt = now;
            Prefs.recordEvent(this, "已识别横屏广告，正在定位第 " + target + " 集片头");
        }
    }

    private boolean beginForceSkip(long now, int sourceEpisode) {
        if (sourceEpisode <= 0) {
            return false;
        }
        return beginForceSkipToEpisode(now, lastSeriesTitle, sourceEpisode + 1,
                "from=" + sourceEpisode);
    }

    private boolean tryPreciseInterstitialSkip(ScanResult result, boolean target,
                                               long now) {
        if (!target
                || forceSkipStage != FORCE_IDLE
                || !Prefs.forceSkipCountdown(this)
                || now - lastForceSkipAt < FORCE_SKIP_COOLDOWN_MS
                || (result.hasCountdown
                && (countdownFirstSeenAt == 0
                || now - countdownFirstSeenAt < 90000L))) {
            return false;
        }

        boolean interstitial = result.hasAdBadge
                || (result.hasSwipeHint && isSeriesActivity(foregroundActivity))
                || (result.hasCountdown
                && !result.hasPlayerUi
                && isSeriesActivity(foregroundActivity));
        if (!interstitial) {
            return false;
        }

        int sourceEpisode = resolveSourceEpisode(result);
        String title = resolveSeriesTitle();
        if (sourceEpisode <= 0 || title.isEmpty()) {
            return false;
        }
        int season = seasonNumber(title);
        if (season <= 0) {
            season = lastSeriesSeason;
        }
        if (season > 0 && Prefs.lastSeriesSeason(this) > 0
                && season != Prefs.lastSeriesSeason(this)) {
            Log.i(TAG, "refusing force skip across seasons: current="
                    + season + " stored=" + Prefs.lastSeriesSeason(this));
            return false;
        }
        if (now - lastSeriesTitleAt >= SERIES_CONTEXT_TTL_MS) {
            return false;
        }
        if (!beginForceSkipToEpisode(now, title, sourceEpisode + 1,
                "precise source=" + sourceEpisode)) {
            return false;
        }

        Prefs.recordEvent(this, "检测到剧集广告，正在定位第 "
                + (sourceEpisode + 1) + " 集片头");
        return true;
    }

    private int resolveSourceEpisode(ScanResult result) {
        if (lastStableEpisode > 0) {
            return lastStableEpisode;
        }
        if (result != null && result.episodeNumber > 0) {
            return result.episodeNumber;
        }
        return Prefs.lastSeriesEpisode(this);
    }

    private String resolveSeriesTitle() {
        String title = normalizeSeriesTitle(lastSeriesTitle);
        if (!title.isEmpty()) {
            return title;
        }
        title = normalizeSeriesTitle(Prefs.lastSeriesTitle(this));
        if (!title.isEmpty()) {
            lastSeriesTitle = title;
            lastSeriesTitleAt = SystemClock.uptimeMillis();
        }
        return title;
    }

    private boolean beginForceSkipToEpisode(long now, String title, int targetEpisode,
                                            String reason) {
        title = normalizeSeriesTitle(title);
        if (title.isEmpty() || targetEpisode <= 0) {
            return false;
        }

        hideCover();
        forceSkipTitle = title;
        forceSkipTargetEpisode = targetEpisode;
        forceSkipSeason = seasonNumber(title) > 0
                ? seasonNumber(title) : lastSeriesSeason;
        if (forceSkipSeason <= 0) {
            Log.i(TAG, "refusing automatic recovery without a confirmed season");
            Prefs.recordEvent(this, "季号未确认，已停止自动跳集");
            return false;
        }
        forceSkipCatalogScrolls = 0;
        forceSkipCatalogSectionClicked = false;
        forceSkipCatalogPickerOpened = false;
        forceSkipSearchAttempts = 0;
        forceSkipConfirmRetries = 0;
        forceSkipBackAttempts = 1;
        forceSkipCatalogBackAttempts = 0;
        resetForceSkipObservation();
        forceSkipStartedAt = now;
        forceSkipStageStartedAt = now;
        lastForceSkipAt = now;
        awaitingEpisodeFrom = -1;
        awaitingEpisodeSince = 0;
        forceSkipStage = FORCE_WAIT_HISTORY;
        Prefs.rememberForceSkip(this, title, targetEpisode, forceSkipSeason,
                System.currentTimeMillis());
        boolean wentBack = performGlobalAction(GLOBAL_ACTION_BACK);
        if (!wentBack) {
            resetForceSkip();
            return false;
        }

        Prefs.bumpSkipped(this);
        Prefs.recordEvent(this, "广告已退出，正在精确定位第 "
                + forceSkipTargetEpisode + " 集");
        Log.i(TAG, "force skip start, title=" + title
                + " season=" + forceSkipSeason + " " + reason
                + " target=" + forceSkipTargetEpisode);
        scheduleScan(280);
        return true;
    }

    private boolean handleBlankAd(ScanResult result, boolean target, long now) {
        boolean blankAd = target
                && (isSeriesActivity(foregroundActivity)
                || !lastSeriesTitle.isEmpty())
                && result.episodeNumber > 0
                && lastStableEpisode > 0
                && result.episodeNumber == lastStableEpisode
                && now - lastStableEpisodeAt < SERIES_CONTEXT_TTL_MS
                && awaitingEpisodeFrom <= 0
                && result.seriesTitle.isEmpty()
                && !result.hasPlayerUi
                && !result.hasAdBadge
                && !result.hasCountdown
                && !result.hasSwipeHint
                && result.bestSkip == null;
        if (!blankAd) {
            blankAdSince = 0;
            return false;
        }

        if (blankAdSince == 0) {
            blankAdSince = now;
            scheduleScan(300);
            return true;
        }
        if (now - blankAdSince < BLANK_AD_GRACE_MS
                || now - lastBlankAdSwipeAt < BLANK_AD_COOLDOWN_MS) {
            scheduleScan(300);
            return true;
        }
        if (!swipeToNext()) {
            return false;
        }

        blankAdSince = 0;
        lastBlankAdSwipeAt = now;
        lastActionAt = now;
        armAdTransition(result, now);
        Prefs.bumpSkipped(this);
        Prefs.recordEvent(this, "已离开空白广告页");
        Log.i(TAG, "swiped away blank ad container at episode "
                + result.episodeNumber);
        scheduleScan(320);
        return true;
    }

    private boolean advanceForceSkip(boolean target, long now) {
        if (forceSkipStage == FORCE_IDLE) {
            return false;
        }
        if (!target) {
            Log.i(TAG, "force skip stopped after leaving target");
            resetForceSkip();
            return false;
        }
        if (now - forceSkipStartedAt > FORCE_SKIP_TIMEOUT_MS) {
            failForceSkip("精确定位总超时", now);
            return true;
        }

        if (forceSkipStage == FORCE_WAIT_HISTORY) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null
                    && clickHistoryTitle(root, "下次再说", new int[]{0})) {
                Log.i(TAG, "force skip dismissed favorite prompt");
                scheduleScan(280);
                return true;
            }
            if (root != null && advanceSearchRecovery(root, now)) {
                return true;
            }
            if (root != null && clickCatalogNearSeries(root, forceSkipTitle)) {
                forceSkipStage = FORCE_OPEN_CATALOG;
                forceSkipStageStartedAt = now;
                Log.i(TAG, "force skip opened catalog beside series " + forceSkipTitle);
                scheduleScan(360);
                return true;
            }
            if (root != null
                    && clickHistoryTitle(root, forceSkipTitle,
                    forceSkipSeason, new int[]{0})) {
                forceSkipStage = FORCE_WAIT_PLAYER;
                forceSkipStageStartedAt = now;
                Log.i(TAG, "force skip clicked series title: " + forceSkipTitle
                        + " activity=" + foregroundActivity);
                scheduleScan(420);
                return true;
            }
            if (root != null
                    && forceSkipSearchAttempts < 2
                    && now - forceSkipStageStartedAt > 700
                    && submitSeriesSearch(root, forceSkipTitle)) {
                forceSkipSearchAttempts++;
                forceSkipStageStartedAt = now;
                Log.i(TAG, "force skip submitted search for " + forceSkipTitle);
                scheduleScan(520);
                return true;
            }
            if (isLandscapeDisplay()
                    && forceSkipBackAttempts < 2
                    && now - forceSkipStageStartedAt > 900
                    && performGlobalAction(GLOBAL_ACTION_BACK)) {
                forceSkipBackAttempts++;
                forceSkipStageStartedAt = now;
                Log.i(TAG, "force skip second back from fullscreen ad");
                scheduleScan(260);
                return true;
            }
            if (root != null
                    && hasVisibleAdMarker(root, new int[]{0}, 0)
                    && forceSkipBackAttempts < 3
                    && now - forceSkipStageStartedAt > 650
                    && performGlobalAction(GLOBAL_ACTION_BACK)) {
                forceSkipBackAttempts++;
                forceSkipStageStartedAt = now;
                Log.i(TAG, "force skip backed out of portrait ad page");
                scheduleScan(260);
                return true;
            }
            if (now - forceSkipStageStartedAt > FORCE_SKIP_STAGE_TIMEOUT_MS) {
                failForceSkip("未找到历史记录中的剧名", now);
                return true;
            }
            scheduleScan(220);
            return true;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (forceSkipStage == FORCE_WAIT_PLAYER) {
            int settledEpisode = settledCleanEpisode(root, now);
            if (settledEpisode == forceSkipTargetEpisode) {
                completeForceSkip(now, "history already resumed next episode");
                return true;
            }
            if (root != null && advanceSearchRecovery(root, now)) {
                return true;
            }
            if (root != null && clickCatalogNearSeries(root, forceSkipTitle)) {
                forceSkipStage = FORCE_OPEN_CATALOG;
                forceSkipStageStartedAt = now;
                resetForceSkipObservation();
                Log.i(TAG, "force skip opened catalog beside series");
                scheduleScan(320);
                return true;
            }
            if (root != null
                    && !hasVisibleInterstitialSignal(root, new int[]{0}, 0)
                    && clickHistoryTitle(root, "选集", new int[]{0})) {
                forceSkipStage = FORCE_OPEN_CATALOG;
                forceSkipStageStartedAt = now;
                resetForceSkipObservation();
                Log.i(TAG, "force skip tapped catalog from resumed player");
                scheduleScan(320);
                return true;
            }
            if (root != null
                    && hasVisibleAdMarker(root, new int[]{0}, 0)
                    && forceSkipBackAttempts < 3
                    && now - forceSkipStageStartedAt > 650
                    && performGlobalAction(GLOBAL_ACTION_BACK)) {
                forceSkipBackAttempts++;
                forceSkipStage = FORCE_WAIT_HISTORY;
                forceSkipStageStartedAt = now;
                Log.i(TAG, "force skip left an ad after reopening series");
                scheduleScan(260);
                return true;
            }
            if (now - forceSkipStageStartedAt > FORCE_SKIP_STAGE_TIMEOUT_MS) {
                failForceSkip("播放器未提供选集入口", now);
                return true;
            }
            scheduleScan(240);
            return true;
        }

        if (forceSkipStage == FORCE_OPEN_CATALOG) {
            if (root != null && hasEpisodeCatalog(root)) {
                forceSkipStage = FORCE_SELECT_CATALOG_EPISODE;
                forceSkipStageStartedAt = now;
                resetForceSkipObservation();
                scheduleScan(260);
                return true;
            }
            int settledEpisode = settledCleanEpisode(root, now);
            if (settledEpisode == forceSkipTargetEpisode) {
                completeForceSkip(now, "player advanced before catalog opened");
                return true;
            }
            if (root != null && clickHistoryTitle(root, "选集", new int[]{0})) {
                forceSkipStageStartedAt = now;
                scheduleScan(320);
                return true;
            }
            if (now - forceSkipStageStartedAt > FORCE_SKIP_STAGE_TIMEOUT_MS) {
                failForceSkip("选集面板没有打开", now);
                return true;
            }
            scheduleScan(260);
            return true;
        }

        if (forceSkipStage == FORCE_SELECT_CATALOG_EPISODE) {
            if (root == null) {
                scheduleScan(220);
                return true;
            }
            if (!hasEpisodeCatalog(root)) {
                int settledEpisode = settledCleanEpisode(root, now);
                if (settledEpisode == forceSkipTargetEpisode) {
                    completeForceSkip(now, "episode changed after catalog tap");
                    return true;
                }
                if (settledEpisode > forceSkipTargetEpisode) {
                    forceSkipStage = FORCE_OPEN_CATALOG;
                    forceSkipStageStartedAt = now;
                    resetForceSkipObservation();
                    Log.i(TAG, "force skip recovered from wrong resumed episode "
                            + settledEpisode);
                    scheduleScan(320);
                    return true;
                }
                if (now - forceSkipStageStartedAt > 3000
                        && clickHistoryTitle(root, "选集", new int[]{0})) {
                    forceSkipStageStartedAt = now;
                    scheduleScan(320);
                    return true;
                }
                if (now - forceSkipStageStartedAt > FORCE_SKIP_STAGE_TIMEOUT_MS) {
                    failForceSkip("选集面板意外关闭", now);
                    return true;
                }
                scheduleScan(240);
                return true;
            }

            AccessibilityNodeInfo targetNode = findCatalogEpisode(
                    root, forceSkipTargetEpisode, new int[]{0}, 0);
            if (targetNode != null && clickNodeOrParent(targetNode, 6)) {
                forceSkipStage = FORCE_CONFIRM_EPISODE;
                forceSkipStageStartedAt = now;
                Log.i(TAG, "force skip selected episode "
                        + forceSkipTargetEpisode + " from catalog");
                scheduleScan(480);
                return true;
            }

            if (!forceSkipCatalogSectionClicked) {
                AccessibilityNodeInfo sectionTab = findCatalogSectionTab(
                        root, forceSkipTargetEpisode, new int[]{0}, 0);
                if (sectionTab != null && clickNodeOrParent(sectionTab, 4)) {
                    forceSkipCatalogSectionClicked = true;
                    forceSkipCatalogScrolls = 0;
                    forceSkipStageStartedAt = now;
                    Log.i(TAG, "force skip switched catalog section for episode "
                            + forceSkipTargetEpisode);
                    scheduleScan(380);
                    return true;
                }
            }
            if (!forceSkipCatalogPickerOpened) {
                AccessibilityNodeInfo totalLabel = findCatalogTotal(
                        root, new int[]{0}, 0);
                if (totalLabel != null && clickNodeOrParent(totalLabel, 6)) {
                    forceSkipCatalogPickerOpened = true;
                    forceSkipStageStartedAt = now;
                    Log.i(TAG, "force skip opened ranged episode picker");
                    scheduleScan(420);
                    return true;
                }
            }
            if (forceSkipCatalogScrolls < 24 && scrollCatalogForward(root)) {
                forceSkipCatalogScrolls++;
                forceSkipStageStartedAt = now;
                scheduleScan(380);
                return true;
            }
            if (now - forceSkipStageStartedAt < FORCE_CATALOG_LOAD_GRACE_MS) {
                scheduleScan(240);
                return true;
            }
            failForceSkip("选集列表中没有找到第 " + forceSkipTargetEpisode + " 集", now);
            return true;
        }

        if (forceSkipStage == FORCE_CONFIRM_EPISODE) {
            if (root == null) {
                scheduleScan(240);
                return true;
            }
            if (hasEpisodeCatalog(root)) {
                if (forceSkipCatalogBackAttempts < 2
                        && now - forceSkipStageStartedAt >= 700
                        && isCatalogEpisodeSelected(
                        root, forceSkipTargetEpisode)) {
                    if (performGlobalAction(GLOBAL_ACTION_BACK)) {
                        forceSkipCatalogBackAttempts++;
                        forceSkipStageStartedAt = now;
                        Log.i(TAG, "force skip closed catalog after selecting episode "
                                + forceSkipTargetEpisode);
                        scheduleScan(520);
                        return true;
                    }
                }
                if (forceSkipConfirmRetries < 2
                        && now - forceSkipStageStartedAt >= FORCE_CONFIRM_RETRY_MS) {
                    AccessibilityNodeInfo targetNode = findCatalogEpisode(
                            root, forceSkipTargetEpisode, new int[]{0}, 0);
                    if (targetNode != null && tapNodeCenter(targetNode)) {
                        forceSkipConfirmRetries++;
                        forceSkipStageStartedAt = now;
                        Log.i(TAG, "force skip retried episode "
                                + forceSkipTargetEpisode + " tap");
                        scheduleScan(420);
                        return true;
                    }
                    if (clickHistoryTitle(root, "继续播放", new int[]{0})) {
                        forceSkipConfirmRetries++;
                        forceSkipStageStartedAt = now;
                        Log.i(TAG, "force skip tapped continue after catalog selection");
                        scheduleScan(420);
                        return true;
                    }
                }
                if (now - forceSkipStageStartedAt > FORCE_SKIP_STAGE_TIMEOUT_MS) {
                    failForceSkip("选集后播放器没有打开", now);
                    return true;
                }
                scheduleScan(240);
                return true;
            }
            int settledEpisode = settledCleanEpisode(root, now);
            if (settledEpisode == forceSkipTargetEpisode) {
                completeForceSkip(now, "confirmed episode from catalog");
                return true;
            }
            if (settledEpisode > forceSkipTargetEpisode) {
                forceSkipStage = FORCE_OPEN_CATALOG;
                forceSkipStageStartedAt = now;
                resetForceSkipObservation();
                Log.i(TAG, "force skip re-opening catalog after wrong episode "
                        + settledEpisode);
                scheduleScan(320);
                return true;
            }
            if (now - forceSkipStageStartedAt > FORCE_SKIP_STAGE_TIMEOUT_MS) {
                failForceSkip("选集后未确认目标集数", now);
                return true;
            }
            scheduleScan(260);
            return true;
        }

        resetForceSkip();
        return false;
    }

    private void completeForceSkip(long now, String reason) {
        int target = forceSkipTargetEpisode;
        Log.i(TAG, "force skip complete at episode " + target + ": " + reason);
        lastEpisodeNumber = target;
        lastStableEpisode = target;
        lastStableEpisodeAt = now;
        Prefs.rememberSeriesContext(this, forceSkipTitle, target, forceSkipSeason);
        Prefs.recordEvent(this, "已从选集开始播放第 " + target + " 集");
        resetForceSkip();
        waitForAdClear = true;
        waitForAdClearSince = now;
        scheduleScan(760);
    }

    private void failForceSkip(String reason, long now) {
        Log.i(TAG, "force skip failed: " + reason);
        suppressBlindSwipeUntil = now + FORCE_SKIP_COOLDOWN_MS;
        Prefs.recordEvent(this, "自动定位超时，已停止继续翻页");
        resetForceSkip();
        hideCover();
        scheduleScan(620);
    }

    private boolean holdForAdTransition(ScanResult result, long now) {
        if (awaitingEpisodeFrom <= 0) {
            return false;
        }
        boolean confirmed = result.episodeNumber > awaitingEpisodeFrom
                && !result.seriesTitle.isEmpty()
                && !result.hasAdBadge;
        if (confirmed) {
            awaitingEpisodeFrom = -1;
            awaitingEpisodeSince = 0;
            return false;
        }
        if (now - awaitingEpisodeSince < AD_TRANSITION_LOCK_MS) {
            scheduleScan(320);
            return true;
        }
        Log.i(TAG, "ad transition lock expired without episode confirmation");
        awaitingEpisodeFrom = -1;
        awaitingEpisodeSince = 0;
        return false;
    }

    private void armAdTransition(ScanResult result, long now) {
        int from = lastStableEpisode > 0
                ? lastStableEpisode : result.episodeNumber;
        if (from > 0) {
            awaitingEpisodeFrom = from;
            awaitingEpisodeSince = now;
        }
    }

    private int readVisibleEpisode(AccessibilityNodeInfo root) {
        return findVisibleEpisode(root, new int[]{0}, 0);
    }

    private int settledCleanEpisode(AccessibilityNodeInfo root, long now) {
        int episode = readVisibleEpisode(root);
        if (episode <= 0
                || hasVisibleInterstitialSignal(root, new int[]{0}, 0)) {
            resetForceSkipObservation();
            return -1;
        }
        if (episode != forceSkipObservedEpisode) {
            forceSkipObservedEpisode = episode;
            forceSkipObservedAt = now;
            return -1;
        }
        if (now - forceSkipObservedAt < FORCE_TARGET_SETTLE_MS) {
            return -1;
        }
        return episode;
    }

    private boolean hasVisibleInterstitialSignal(AccessibilityNodeInfo node,
                                                 int[] counter, int depth) {
        if (node == null || depth > MAX_DEPTH || counter[0] >= MAX_NODES) {
            return false;
        }
        counter[0]++;
        if (isNodeVisibleToUser(node)) {
            String text = asString(node.getText()).trim();
            String desc = asString(node.getContentDescription()).trim();
            if (isAdBadge(text) || isSwipeHint(text)
                    || isAdBadge(desc) || isSwipeHint(desc)
                    || parseCountdownSeconds(text) >= 0
                    || parseCountdownSeconds(desc) >= 0) {
                return true;
            }
        }
        int children = node.getChildCount();
        for (int i = 0; i < children; i++) {
            AccessibilityNodeInfo child = null;
            try {
                child = node.getChild(i);
            } catch (Exception e) {
                Log.w(TAG, "interstitial signal child read failed", e);
            }
            if (child != null
                    && hasVisibleInterstitialSignal(child, counter, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    private int findVisibleEpisode(AccessibilityNodeInfo node, int[] counter, int depth) {
        if (node == null || depth > MAX_DEPTH || counter[0] >= MAX_NODES) {
            return -1;
        }
        counter[0]++;
        if (isNodeVisibleToUser(node)) {
            String text = asString(node.getText()).trim();
            Matcher matcher = EPISODE_NUMBER.matcher(text);
            if (matcher.matches()) {
                return safeInt(matcher.group(1));
            }
        }
        int children = node.getChildCount();
        for (int i = 0; i < children; i++) {
            AccessibilityNodeInfo child = null;
            try {
                child = node.getChild(i);
            } catch (Exception e) {
                Log.w(TAG, "episode child read failed", e);
            }
            int episode = findVisibleEpisode(child, counter, depth + 1);
            if (episode > 0) {
                return episode;
            }
        }
        return -1;
    }

    private boolean hasEpisodeCatalog(AccessibilityNodeInfo node) {
        return hasCatalogMarker(node, new int[]{0}, 0);
    }

    private boolean hasCatalogMarker(AccessibilityNodeInfo node,
                                     int[] counter, int depth) {
        if (node == null || depth > MAX_DEPTH || counter[0] >= MAX_NODES) {
            return false;
        }
        counter[0]++;
        if (isNodeVisibleToUser(node)) {
            String text = asString(node.getText()).trim();
            String viewId = "";
            try {
                viewId = asString(node.getViewIdResourceName());
            } catch (Exception ignored) {
            }
            if (CATALOG_RANGE.matcher(text).matches()
                    || asString(node.getClassName()).endsWith("GridView")
                    || viewId.endsWith(":id/ji2")) {
                return true;
            }
        }
        int children = node.getChildCount();
        for (int i = 0; i < children; i++) {
            AccessibilityNodeInfo child = null;
            try {
                child = node.getChild(i);
            } catch (Exception e) {
                Log.w(TAG, "catalog marker child read failed", e);
            }
            if (child != null && hasCatalogMarker(child, counter, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    private AccessibilityNodeInfo findCatalogEpisode(AccessibilityNodeInfo node,
                                                     int targetEpisode,
                                                     int[] counter, int depth) {
        if (node == null || depth > MAX_DEPTH || counter[0] >= MAX_NODES) {
            return null;
        }
        counter[0]++;
        if (isNodeVisibleToUser(node)
                && asString(node.getText()).trim().equals(String.valueOf(targetEpisode))
                && isCatalogEpisodeNode(node)) {
            return node;
        }
        int children = node.getChildCount();
        for (int i = 0; i < children; i++) {
            AccessibilityNodeInfo child = null;
            try {
                child = node.getChild(i);
            } catch (Exception e) {
                Log.w(TAG, "catalog episode child read failed", e);
            }
            AccessibilityNodeInfo found = findCatalogEpisode(
                    child, targetEpisode, counter, depth + 1);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private boolean isCatalogEpisodeSelected(AccessibilityNodeInfo root,
                                             int targetEpisode) {
        AccessibilityNodeInfo target = findCatalogEpisode(
                root, targetEpisode, new int[]{0}, 0);
        if (target == null) {
            return false;
        }
        AccessibilityNodeInfo tile = target.getParent();
        if (tile == null) {
            return false;
        }
        int children = tile.getChildCount();
        for (int i = 0; i < children; i++) {
            AccessibilityNodeInfo child = null;
            try {
                child = tile.getChild(i);
            } catch (Exception e) {
                Log.w(TAG, "catalog selection child read failed", e);
            }
            if (child == null) {
                continue;
            }
            if (asString(child.getClassName()).endsWith("ImageView")) {
                return true;
            }
            int grandchildren = child.getChildCount();
            for (int j = 0; j < grandchildren; j++) {
                AccessibilityNodeInfo grandchild = null;
                try {
                    grandchild = child.getChild(j);
                } catch (Exception e) {
                    Log.w(TAG, "catalog selection grandchild read failed", e);
                }
                if (grandchild != null
                        && asString(grandchild.getClassName()).endsWith("ImageView")) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isCatalogEpisodeNode(AccessibilityNodeInfo node) {
        String viewId = "";
        try {
            viewId = asString(node.getViewIdResourceName());
        } catch (Exception ignored) {
        }
        if (viewId.endsWith(":id/ji2")) {
            return true;
        }

        AccessibilityNodeInfo current = node.getParent();
        for (int i = 0; current != null && i < 4; i++) {
            if (asString(current.getClassName()).endsWith("GridView")) {
                return true;
            }
            current = current.getParent();
        }

        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        return bounds.top > screenHeight * 0.58f
                && bounds.bottom <= screenHeight + dp(4);
    }

    private AccessibilityNodeInfo findCatalogSectionTab(AccessibilityNodeInfo node,
                                                        int targetEpisode,
                                                        int[] counter, int depth) {
        if (node == null || depth > MAX_DEPTH || counter[0] >= MAX_NODES) {
            return null;
        }
        counter[0]++;
        if (isNodeVisibleToUser(node)) {
            Matcher matcher = CATALOG_RANGE.matcher(asString(node.getText()).trim());
            if (matcher.matches()) {
                int start = safeInt(matcher.group(1));
                int end = safeInt(matcher.group(2));
                if (start > 0 && targetEpisode >= start && targetEpisode <= end) {
                    return node;
                }
            }
        }
        int children = node.getChildCount();
        for (int i = 0; i < children; i++) {
            AccessibilityNodeInfo child = null;
            try {
                child = node.getChild(i);
            } catch (Exception e) {
                Log.w(TAG, "catalog section child read failed", e);
            }
            AccessibilityNodeInfo found = findCatalogSectionTab(
                    child, targetEpisode, counter, depth + 1);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private AccessibilityNodeInfo findCatalogTotal(AccessibilityNodeInfo node,
                                                   int[] counter, int depth) {
        if (node == null || depth > MAX_DEPTH || counter[0] >= MAX_NODES) {
            return null;
        }
        counter[0]++;
        if (isNodeVisibleToUser(node)
                && CATALOG_TOTAL.matcher(asString(node.getText()).trim()).matches()) {
            return node;
        }
        int children = node.getChildCount();
        for (int i = 0; i < children; i++) {
            AccessibilityNodeInfo child = null;
            try {
                child = node.getChild(i);
            } catch (Exception e) {
                Log.w(TAG, "catalog total child read failed", e);
            }
            AccessibilityNodeInfo found = findCatalogTotal(
                    child, counter, depth + 1);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private boolean scrollCatalogForward(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo scroller = findCatalogScroller(root);
        if (scroller == null) {
            return false;
        }
        try {
            if (scroller.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                Log.i(TAG, "force skip scrolled catalog with accessibility action");
                return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "catalog scroll action failed", e);
        }

        Rect bounds = new Rect();
        scroller.getBoundsInScreen(bounds);
        if (bounds.width() < dp(120) || bounds.height() < dp(40)) {
            return false;
        }
        Path path = new Path();
        path.moveTo(bounds.right - dp(36), bounds.centerY());
        path.lineTo(bounds.left + dp(36), bounds.centerY());
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 340))
                .build();
        Log.i(TAG, "force skip scrolled catalog with horizontal gesture");
        return dispatchGesture(gesture, null, null);
    }

    private AccessibilityNodeInfo findCatalogScroller(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo marker = findCatalogEpisodeMarker(
                root, new int[]{0}, 0);
        if (marker == null) {
            return null;
        }

        AccessibilityNodeInfo current = marker.getParent();
        for (int i = 0; current != null && i < 8; i++) {
            String className = asString(current.getClassName());
            Rect bounds = new Rect();
            current.getBoundsInScreen(bounds);
            boolean horizontalContainer = className.endsWith("RecyclerView")
                    || className.endsWith("HorizontalScrollView")
                    || className.endsWith("GridView");
            if (isNodeVisibleToUser(current)
                    && horizontalContainer
                    && bounds.width() > bounds.height() * 1.4f) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    private AccessibilityNodeInfo findCatalogEpisodeMarker(AccessibilityNodeInfo node,
                                                           int[] counter, int depth) {
        if (node == null || depth > MAX_DEPTH || counter[0] >= MAX_NODES) {
            return null;
        }
        counter[0]++;
        if (isNodeVisibleToUser(node)) {
            String viewId = "";
            try {
                viewId = asString(node.getViewIdResourceName());
            } catch (Exception ignored) {
            }
            if (viewId.endsWith(":id/ji2")
                    && !asString(node.getText()).trim().isEmpty()) {
                return node;
            }
        }
        int children = node.getChildCount();
        for (int i = 0; i < children; i++) {
            AccessibilityNodeInfo child = null;
            try {
                child = node.getChild(i);
            } catch (Exception e) {
                Log.w(TAG, "catalog marker child read failed", e);
            }
            AccessibilityNodeInfo found = findCatalogEpisodeMarker(
                    child, counter, depth + 1);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private boolean clickCatalogNearSeries(AccessibilityNodeInfo root, String title) {
        if (root == null || title == null || title.isEmpty()) {
            return false;
        }
        return clickCatalogNearSeries(root, normalizeSeriesTitle(title),
                new int[]{0}, 0);
    }

    private boolean clickCatalogNearSeries(AccessibilityNodeInfo node, String title,
                                           int[] counter, int depth) {
        if (node == null || depth > MAX_DEPTH || counter[0] >= MAX_NODES) {
            return false;
        }
        counter[0]++;

        String text = asString(node.getText()).trim();
        int expectedSeason = forceSkipSeason > 0
                ? forceSkipSeason : seasonNumber(title);
        int textSeason = seasonNumber(text);
        boolean titleMatches = isSameSeriesBaseTitle(text, title);
        boolean seasonMatches = textSeason > 0
                ? expectedSeason > 0 && textSeason == expectedSeason
                : expectedSeason <= 0 || seasonLabeledCardMatches(node, title);
        if (titleMatches && seasonMatches && isNodeVisibleToUser(node)) {
            Rect titleBounds = new Rect();
            node.getBoundsInScreen(titleBounds);
            AccessibilityNodeInfo ancestor = node;
            for (int i = 0; ancestor != null && i < 10; i++) {
                AccessibilityNodeInfo catalog = findVisibleExactLabel(
                        ancestor, "选集", new int[]{0}, 0);
                if (catalog != null) {
                    Rect catalogBounds = new Rect();
                    catalog.getBoundsInScreen(catalogBounds);
                    int screenHeight = getResources().getDisplayMetrics().heightPixels;
                    if (Math.abs(catalogBounds.centerY() - titleBounds.centerY())
                            <= screenHeight * 0.45f
                            && tapNodeCenter(catalog)) {
                        return true;
                    }
                }
                ancestor = ancestor.getParent();
            }
        }

        int children = node.getChildCount();
        for (int i = 0; i < children; i++) {
            AccessibilityNodeInfo child = null;
            try {
                child = node.getChild(i);
            } catch (Exception e) {
                Log.w(TAG, "catalog sibling child read failed", e);
            }
            if (child != null
                    && clickCatalogNearSeries(child, title, counter, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    private AccessibilityNodeInfo findVisibleExactLabel(AccessibilityNodeInfo node,
                                                        String label,
                                                        int[] counter, int depth) {
        if (node == null || depth > MAX_DEPTH || counter[0] >= MAX_NODES) {
            return null;
        }
        counter[0]++;
        if (isNodeVisibleToUser(node)
                && asString(node.getText()).trim().equals(label)) {
            return node;
        }
        int children = node.getChildCount();
        for (int i = 0; i < children; i++) {
            AccessibilityNodeInfo child = null;
            try {
                child = node.getChild(i);
            } catch (Exception e) {
                Log.w(TAG, "exact label child read failed", e);
            }
            AccessibilityNodeInfo found = findVisibleExactLabel(
                    child, label, counter, depth + 1);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private boolean tapNodeCenter(AccessibilityNodeInfo node) {
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.width() <= 0 || bounds.height() <= 0) {
            return false;
        }
        Path path = new Path();
        path.moveTo(bounds.centerX(), bounds.centerY());
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 70))
                .build();
        return dispatchGesture(gesture, null, null);
    }

    private boolean clickExactLabelByTap(AccessibilityNodeInfo root, String label) {
        AccessibilityNodeInfo node = findVisibleExactLabel(
                root, label, new int[]{0}, 0);
        return node != null && tapNodeCenter(node);
    }

    private boolean isMatchingSeriesTitle(String text, String title) {
        if (text == null || title == null || text.isEmpty() || title.isEmpty()) {
            return false;
        }
        String cleanText = text.trim();
        String cleanTitle = title.trim();
        String compactText = cleanText
                .replace("…", "").replace("...", "").trim();
        String compactTitle = cleanTitle
                .replace("…", "").replace("...", "").trim();
        if (compactText.isEmpty() || compactTitle.isEmpty()) {
            return false;
        }

        int textSeason = seasonNumber(cleanText);
        int titleSeason = seasonNumber(cleanTitle);
        if (textSeason > 0 && titleSeason > 0 && textSeason != titleSeason) {
            return false;
        }
        if (textSeason > 0 && titleSeason < 0) {
            return false;
        }
        if (titleSeason > 0 && textSeason < 0) {
            return false;
        }
        return isSameSeriesBaseTitle(cleanText, cleanTitle);
    }

    private boolean isSameSeriesBaseTitle(String text, String title) {
        if (text == null || title == null) {
            return false;
        }
        String cleanText = stripSeasonNumber(text.trim());
        String cleanTitle = stripSeasonNumber(title.trim());
        String compactText = cleanText
                .replace("…", "").replace("...", "").trim();
        String compactTitle = cleanTitle
                .replace("…", "").replace("...", "").trim();
        if (compactText.isEmpty() || compactTitle.isEmpty()) {
            return false;
        }
        if (compactText.equals(compactTitle)) {
            return true;
        }

        boolean truncated = cleanText.contains("…")
                || cleanText.contains("...")
                || compactText.length() < compactTitle.length();
        if (!truncated) {
            return false;
        }
        return (compactText.length() >= 6
                && compactTitle.startsWith(compactText))
                || (compactTitle.length() > 8
                && compactText.startsWith(compactTitle.substring(0, 8)));
    }

    private String stripSeasonNumber(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return SEASON_NUMBER.matcher(value).replaceAll("").trim();
    }

    private int seasonNumber(String title) {
        if (title == null || title.isEmpty()) {
            return -1;
        }
        Matcher matcher = SEASON_NUMBER.matcher(title);
        if (!matcher.find()) {
            return -1;
        }
        String value = matcher.group(1);
        if (value.matches("\\d+")) {
            return safeInt(value);
        }
        return chineseNumber(value);
    }

    private boolean clickHistoryTitle(AccessibilityNodeInfo node, String title, int[] counter) {
        return clickHistoryTitle(node, title, -1, counter);
    }

    private boolean clickHistoryTitle(AccessibilityNodeInfo node, String title,
                                      int season, int[] counter) {
        if (node == null || title.isEmpty() || counter[0] >= MAX_NODES) {
            return false;
        }
        counter[0]++;

        if (isEditableTextNode(node)) {
            return false;
        }
        String text = asString(node.getText()).trim();
        int expectedSeason = season > 0 ? season : seasonNumber(title);
        int textSeason = seasonNumber(text);
        boolean titleMatches = isSameSeriesBaseTitle(text, title);
        boolean seasonMatches = textSeason > 0
                ? expectedSeason > 0 && textSeason == expectedSeason
                : expectedSeason <= 0 || seasonLabeledCardMatches(node, title);
        if (titleMatches && seasonMatches && isNodeVisibleToUser(node)) {
            if (clickNodeOrParent(node, 6)) {
                return true;
            }
        } else if (titleMatches && !seasonMatches) {
            Log.i(TAG, "history season mismatch, ignored text=" + text
                    + " expected=" + expectedSeason);
        }

        int children = node.getChildCount();
        for (int i = 0; i < children; i++) {
            AccessibilityNodeInfo child = null;
            try {
                child = node.getChild(i);
            } catch (Exception e) {
                Log.w(TAG, "history child read failed", e);
            }
            if (child != null && clickHistoryTitle(child, title, season, counter)) {
                return true;
            }
        }
        return false;
    }

    private boolean isEditableTextNode(AccessibilityNodeInfo node) {
        try {
            if (node.isEditable()) {
                return true;
            }
        } catch (Exception ignored) {
        }
        return asString(node.getClassName()).endsWith("EditText");
    }

    private boolean submitSeriesSearch(AccessibilityNodeInfo root, String title) {
        if (root == null || title == null || title.isEmpty()) {
            return false;
        }
        if (!isSearchPage(root)) {
            return false;
        }

        AccessibilityNodeInfo input = findSearchInput(root, new int[]{0}, 0);
        if (input == null) {
            return false;
        }
        String currentText = asString(input.getText()).trim();
        if (!title.equals(currentText)) {
            Bundle arguments = new Bundle();
            arguments.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    title);
            try {
                input.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                if (!input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
                    return false;
                }
            } catch (Exception e) {
                Log.w(TAG, "search text action failed", e);
                return false;
            }
        }

        AccessibilityNodeInfo searchButton = findVisibleExactLabel(
                root, "搜索", new int[]{0}, 0);
        if (searchButton == null) {
            return false;
        }
        if (clickNodeOrParent(searchButton, 4)) {
            return true;
        }
        return tapNodeCenter(searchButton);
    }

    private boolean isSearchPage(AccessibilityNodeInfo root) {
        if (root == null) {
            return false;
        }
        String activity = foregroundActivity == null
                ? "" : foregroundActivity.toLowerCase(Locale.ROOT);
        return activity.contains("search")
                || (findSearchInput(root, new int[]{0}, 0) != null
                && findVisibleExactLabel(root, "搜索", new int[]{0}, 0) != null)
                || hasVisibleLabel(root, new String[]{"搜索历史", "猜你想搜"},
                new int[]{0}, 0);
    }

    private boolean advanceSearchRecovery(AccessibilityNodeInfo root, long now) {
        if (!isSearchPage(root)) {
            return false;
        }
        if (clickSeasonResult(root, forceSkipTitle)) {
            forceSkipStage = FORCE_WAIT_PLAYER;
            forceSkipStageStartedAt = now;
            resetForceSkipObservation();
            Log.i(TAG, "force skip selected matching season from search results");
            scheduleScan(420);
            return true;
        }
        if (forceSkipSearchAttempts < 2
                && now - forceSkipStageStartedAt > 700
                && submitSeriesSearch(root, forceSkipTitle)) {
            forceSkipSearchAttempts++;
            forceSkipStage = FORCE_WAIT_HISTORY;
            forceSkipStageStartedAt = now;
            Log.i(TAG, "force skip submitted search for " + forceSkipTitle);
            scheduleScan(520);
            return true;
        }
        if (clickHistoryTitle(root, forceSkipTitle, forceSkipSeason,
                new int[]{0})) {
            forceSkipStage = FORCE_WAIT_PLAYER;
            forceSkipStageStartedAt = now;
            resetForceSkipObservation();
            Log.i(TAG, "force skip clicked search result: " + forceSkipTitle);
            scheduleScan(420);
            return true;
        }
        return false;
    }

    private boolean clickSeasonResult(AccessibilityNodeInfo root, String title) {
        int season = seasonNumber(title);
        if (season <= 0) {
            season = forceSkipSeason;
        }
        if (root == null || season <= 0) {
            return false;
        }
        return clickSeasonResult(root, season, title, new int[]{0}, 0);
    }

    private boolean clickSeasonResult(AccessibilityNodeInfo node, int season,
                                      String title, int[] counter, int depth) {
        if (node == null || depth > MAX_DEPTH || counter[0] >= MAX_NODES) {
            return false;
        }
        counter[0]++;
        String text = asString(node.getText()).trim();
        if (isNodeVisibleToUser(node)
                && seasonLabelNumber(text) == season
                && seasonLabeledCardMatches(node, title)) {
            if (clickNodeOrParent(node, 5)) {
                return true;
            }
        }
        int children = node.getChildCount();
        for (int i = 0; i < children; i++) {
            AccessibilityNodeInfo child = null;
            try {
                child = node.getChild(i);
            } catch (Exception e) {
                Log.w(TAG, "season result child read failed", e);
            }
            if (child != null
                    && clickSeasonResult(child, season, title, counter,
                    depth + 1)) {
                return true;
            }
        }
        return false;
    }

    private boolean seasonLabeledCardMatches(AccessibilityNodeInfo node,
                                             String title) {
        if (title == null || title.isEmpty()) {
            return false;
        }
        AccessibilityNodeInfo ancestor = node;
        for (int i = 0; ancestor != null && i < 6; i++) {
            AccessibilityNodeInfo child = null;
            int[] counter = new int[]{0};
            int childCount = ancestor.getChildCount();
            for (int j = 0; j < childCount; j++) {
                try {
                    child = ancestor.getChild(j);
                } catch (Exception ignored) {
                    child = null;
                }
                if (child != null
                        && containsMatchingSeriesTitle(child, title, counter)) {
                    return true;
                }
            }
            ancestor = ancestor.getParent();
        }
        return false;
    }

    private boolean containsMatchingSeriesTitle(AccessibilityNodeInfo node,
                                                String title, int[] counter) {
        if (node == null || counter[0] >= MAX_NODES) {
            return false;
        }
        counter[0]++;
        if (isSameSeriesBaseTitle(asString(node.getText()).trim(), title)) {
            return true;
        }
        int children = node.getChildCount();
        for (int i = 0; i < children; i++) {
            AccessibilityNodeInfo child = null;
            try {
                child = node.getChild(i);
            } catch (Exception ignored) {
                child = null;
            }
            if (child != null
                    && containsMatchingSeriesTitle(child, title, counter)) {
                return true;
            }
        }
        return false;
    }

    private int seasonLabelNumber(String label) {
        Matcher matcher = SEASON_LABEL.matcher(label == null ? "" : label.trim());
        if (!matcher.matches()) {
            return -1;
        }
        String value = matcher.group(1);
        return value.matches("\\d+") ? safeInt(value) : chineseNumber(value);
    }

    private AccessibilityNodeInfo findSearchInput(AccessibilityNodeInfo node,
                                                  int[] counter, int depth) {
        if (node == null || depth > MAX_DEPTH || counter[0] >= MAX_NODES) {
            return null;
        }
        counter[0]++;
        if (isNodeVisibleToUser(node) && node.isEditable()) {
            return node;
        }
        int children = node.getChildCount();
        for (int i = 0; i < children; i++) {
            AccessibilityNodeInfo child = null;
            try {
                child = node.getChild(i);
            } catch (Exception e) {
                Log.w(TAG, "search input child read failed", e);
            }
            AccessibilityNodeInfo found = findSearchInput(
                    child, counter, depth + 1);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private boolean isHistoryPage(AccessibilityNodeInfo root) {
        if (isMainActivity(foregroundActivity)) {
            return true;
        }
        return hasVisibleLabel(root, new String[]{"历史", "追更"}, new int[]{0}, 0);
    }

    private boolean hasVisibleLabel(AccessibilityNodeInfo node, String[] labels,
                                    int[] counter, int depth) {
        if (node == null || depth > MAX_DEPTH || counter[0] >= MAX_NODES) {
            return false;
        }
        counter[0]++;
        if (isNodeVisibleToUser(node)) {
            String text = asString(node.getText()).trim();
            String desc = asString(node.getContentDescription()).trim();
            for (String label : labels) {
                if (label.equals(text) || label.equals(desc)) {
                    return true;
                }
            }
        }
        int children = node.getChildCount();
        for (int i = 0; i < children; i++) {
            AccessibilityNodeInfo child = null;
            try {
                child = node.getChild(i);
            } catch (Exception e) {
                Log.w(TAG, "history page child read failed", e);
            }
            if (child != null && hasVisibleLabel(child, labels, counter, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasVisibleAdMarker(AccessibilityNodeInfo node,
                                       int[] counter, int depth) {
        if (node == null || depth > MAX_DEPTH || counter[0] >= MAX_NODES) {
            return false;
        }
        counter[0]++;
        if (isNodeVisibleToUser(node)) {
            String text = asString(node.getText()).trim();
            String desc = asString(node.getContentDescription()).trim();
            if (isAdBadge(text) || isSwipeHint(text)
                    || isAdBadge(desc) || isSwipeHint(desc)) {
                return true;
            }
        }
        int children = node.getChildCount();
        for (int i = 0; i < children; i++) {
            AccessibilityNodeInfo child = null;
            try {
                child = node.getChild(i);
            } catch (Exception e) {
                Log.w(TAG, "ad marker child read failed", e);
            }
            if (child != null && hasVisibleAdMarker(child, counter, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    private boolean clickNodeOrParent(AccessibilityNodeInfo node, int maxDepth) {
        AccessibilityNodeInfo current = node;
        for (int i = 0; current != null && i < maxDepth; i++) {
            if (current.isEnabled() && current.isClickable()) {
                try {
                    if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        return true;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "ancestor click failed", e);
                }
            }
            current = current.getParent();
        }

        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.width() <= 0 || bounds.height() <= 0) {
            return false;
        }
        Path path = new Path();
        path.moveTo(bounds.centerX(), bounds.centerY());
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 70))
                .build();
        return dispatchGesture(gesture, null, null);
    }

    private void resetForceSkip() {
        forceSkipStage = FORCE_IDLE;
        forceSkipStartedAt = 0;
        forceSkipStageStartedAt = 0;
        forceSkipTitle = "";
        forceSkipTargetEpisode = -1;
        forceSkipSeason = -1;
        forceSkipCatalogScrolls = 0;
        forceSkipCatalogSectionClicked = false;
        forceSkipCatalogPickerOpened = false;
        forceSkipSearchAttempts = 0;
        forceSkipConfirmRetries = 0;
        forceSkipBackAttempts = 0;
        forceSkipCatalogBackAttempts = 0;
        resetForceSkipObservation();
        Prefs.clearForceSkip(this);
    }

    private void resetForceSkipObservation() {
        forceSkipObservedEpisode = -1;
        forceSkipObservedAt = 0;
    }

    private void restoreForceSkip(long now) {
        String savedTitle = Prefs.forceSkipTitle(this);
        int savedTarget = Prefs.forceSkipEpisode(this);
        long savedWall = Prefs.forceSkipStartedWall(this);
        long age = System.currentTimeMillis() - savedWall;
        if (savedTitle.isEmpty()
                || savedTarget <= 0
                || Prefs.forceSkipSeason(this) <= 0
                || savedWall <= 0
                || age < 0
                || age >= FORCE_SKIP_TIMEOUT_MS) {
            if (savedWall > 0 || savedTarget > 0 || !savedTitle.isEmpty()) {
                Prefs.clearForceSkip(this);
            }
            return;
        }

        forceSkipTitle = savedTitle;
        forceSkipTargetEpisode = savedTarget;
        forceSkipSeason = Prefs.forceSkipSeason(this);
        forceSkipStartedAt = now - age;
        forceSkipStageStartedAt = now;
        forceSkipStage = FORCE_WAIT_HISTORY;
        forceSkipCatalogScrolls = 0;
        forceSkipCatalogSectionClicked = false;
        forceSkipCatalogPickerOpened = false;
        forceSkipSearchAttempts = 0;
        forceSkipConfirmRetries = 0;
        forceSkipBackAttempts = 1;
        forceSkipCatalogBackAttempts = 0;
        resetForceSkipObservation();
        Log.i(TAG, "force skip restored after service rebind, target="
                + savedTarget + " season=" + forceSkipSeason + " age=" + age);
    }

    private ScanResult scanPackage(String pkg) {
        ScanResult result = new ScanResult();
        int[] counter = new int[]{0};
        boolean scanned = false;
        scanningTargetPackage = TargetApps.isAllowed(this, pkg);

        try {
            AccessibilityNodeInfo activeRoot = getRootInActiveWindow();
            if (activeRoot != null && pkg.equals(asString(activeRoot.getPackageName()))) {
                scanned = true;
                inspectRoot(activeRoot, result, counter);
            }
        } catch (Exception e) {
            Log.w(TAG, "active root scan failed", e);
        }

        if (!scanned) {
            try {
                AccessibilityNodeInfo firstRoot = null;
                AccessibilityNodeInfo activeWindowRoot = null;
                for (AccessibilityWindowInfo window : getWindows()) {
                    if (window == null) {
                        continue;
                    }
                    int windowType = window.getType();
                    if (windowType != AccessibilityWindowInfo.TYPE_APPLICATION
                            && windowType != AccessibilityWindowInfo.TYPE_SYSTEM) {
                        continue;
                    }
                    AccessibilityNodeInfo root = window.getRoot();
                    if (root == null
                            || !pkg.equals(asString(root.getPackageName()))) {
                        continue;
                    }
                    if (firstRoot == null) {
                        firstRoot = root;
                    }
                    if (window.isActive() || window.isFocused()) {
                        activeWindowRoot = root;
                        break;
                    }
                }
                AccessibilityNodeInfo selectedRoot = activeWindowRoot != null
                        ? activeWindowRoot : firstRoot;
                if (selectedRoot != null) {
                    scanned = true;
                    inspectRoot(selectedRoot, result, counter);
                }
                if (activeWindowRoot != null) {
                    Log.i(TAG, "using active fallback window for " + pkg);
                }
            } catch (Exception e) {
                Log.w(TAG, "window scan failed", e);
            }
        }

        if (!scanned) {
            try {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root != null && pkg.equals(asString(root.getPackageName()))) {
                    inspectRoot(root, result, counter);
                }
            } catch (Exception e) {
                Log.w(TAG, "active root scan failed", e);
            }
        }

        if (result.bestSkip != null && result.bestSkip.genericClose
                && !result.hasAdBadge && !result.hasCountdown
                && !result.hasSwipeHint) {
            result.bestSkip = null;
        }
        if (result.seriesTitle.isEmpty()
                && !result.hasAdBadge
                && !result.hasCountdown
                && !result.hasSwipeHint) {
            result.seriesTitle = result.fallbackSeriesTitle;
        }
        scanningTargetPackage = false;
        return result;
    }

    private void inspectRoot(AccessibilityNodeInfo root, ScanResult result,
                             int[] counter) {
        result.searchPage = isSearchPage(root);
        inspectTree(root, result, counter, 0);
        if (result.seasonNumber > 0 || result.searchPage) {
            return;
        }
        String title = !lastSeriesTitle.isEmpty()
                ? lastSeriesTitle
                : (!result.seriesTitle.isEmpty()
                ? result.seriesTitle : result.fallbackSeriesTitle);
        if (!title.isEmpty()) {
            result.seasonNumber = findSeasonNearMatchingTitle(root, title);
        }
    }

    private int findSeasonNearMatchingTitle(AccessibilityNodeInfo root,
                                            String title) {
        Set<Integer> seasons = new HashSet<>();
        collectSeasonsNearMatchingTitles(root, title, seasons, new int[]{0}, 0);
        return seasons.size() == 1
                ? seasons.iterator().next() : -1;
    }

    private void collectSeasonsNearMatchingTitles(AccessibilityNodeInfo node,
                                                  String title,
                                                  Set<Integer> seasons,
                                                  int[] counter, int depth) {
        if (node == null || depth > MAX_DEPTH || counter[0] >= MAX_NODES) {
            return;
        }
        counter[0]++;
        String text = asString(node.getText()).trim();
        if (isNodeVisibleToUser(node) && isSameSeriesBaseTitle(text, title)) {
            int inlineSeason = seasonNumber(text);
            if (inlineSeason > 0) {
                seasons.add(inlineSeason);
            } else {
                Set<Integer> nearby = new HashSet<>();
                AccessibilityNodeInfo ancestor = node;
                for (int i = 0; ancestor != null && i < 6; i++) {
                    collectSeasonLabels(ancestor, nearby, new int[]{0}, 0);
                    if (!nearby.isEmpty()) {
                        break;
                    }
                    ancestor = ancestor.getParent();
                }
                seasons.addAll(nearby);
            }
        }
        int children = node.getChildCount();
        for (int i = 0; i < children; i++) {
            AccessibilityNodeInfo child = null;
            try {
                child = node.getChild(i);
            } catch (Exception ignored) {
                child = null;
            }
            collectSeasonsNearMatchingTitles(child, title, seasons,
                    counter, depth + 1);
        }
    }

    private void collectSeasonLabels(AccessibilityNodeInfo node,
                                     Set<Integer> seasons,
                                     int[] counter, int depth) {
        if (node == null || depth > MAX_DEPTH || counter[0] >= MAX_NODES) {
            return;
        }
        counter[0]++;
        if (isNodeVisibleToUser(node)) {
            int season = seasonLabelNumber(asString(node.getText()).trim());
            if (season > 0) {
                seasons.add(season);
            }
        }
        int children = node.getChildCount();
        for (int i = 0; i < children; i++) {
            AccessibilityNodeInfo child = null;
            try {
                child = node.getChild(i);
            } catch (Exception ignored) {
                child = null;
            }
            collectSeasonLabels(child, seasons, counter, depth + 1);
        }
    }

    private void inspectTree(AccessibilityNodeInfo node, ScanResult result,
                             int[] counter, int depth) {
        if (node == null || depth > MAX_DEPTH || counter[0] >= MAX_NODES) {
            return;
        }
        counter[0]++;

        String text = asString(node.getText()).trim();
        String desc = asString(node.getContentDescription()).trim();
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        String viewId = "";
        try {
            viewId = asString(node.getViewIdResourceName());
        } catch (Exception ignored) {
        }

        inspectRewardCloseNode(node, viewId, bounds, result);
        inspectLabel(text, node, bounds, result);
        if (!desc.isEmpty() && !desc.equals(text)) {
            inspectLabel(desc, node, bounds, result);
        }

        int children = node.getChildCount();
        for (int i = 0; i < children; i++) {
            if (counter[0] >= MAX_NODES) {
                return;
            }
            AccessibilityNodeInfo child = null;
            try {
                child = node.getChild(i);
            } catch (Exception e) {
                Log.w(TAG, "child read failed", e);
            }
            if (child != null) {
                inspectTree(child, result, counter, depth + 1);
            }
        }
    }

    private void inspectLabel(String label, AccessibilityNodeInfo node,
                              Rect bounds, ScanResult result) {
        if (label.isEmpty() || label.length() > 140) {
            return;
        }
        if (!isNodeVisibleToUser(node)) {
            return;
        }
        inspectRewardLabel(label, node, bounds, result);

        if (isAdBadge(label)) {
            result.hasAdBadge = true;
            result.adBadgeLabel = label;
        }
        String viewId = "";
        try {
            viewId = asString(node.getViewIdResourceName());
        } catch (Exception ignored) {
        }
        Matcher combinedTitle = TITLE_WITH_EPISODE.matcher(label);
        boolean combinedMatch = combinedTitle.matches();
        boolean captureSeriesContext = !result.searchPage;
        String titleFromLabel = combinedMatch
                ? normalizeSeriesTitle(combinedTitle.group(1)) : "";
        int episodeFromLabel = combinedMatch
                ? safeInt(combinedTitle.group(2)) : -1;
        if (captureSeriesContext
                && combinedMatch
                && !titleFromLabel.isEmpty()
                && !isAdBadge(titleFromLabel)
                && !isSwipeHint(titleFromLabel)
                && !looksLikePromotionalCopy(titleFromLabel)
                && parseCountdownSeconds(label) < 0) {
            result.seriesTitle = titleFromLabel;
            result.episodeNumber = episodeFromLabel;
        } else if (captureSeriesContext
                && viewId.endsWith(":id/d4")
                && !isAdBadge(label)
                && !isSwipeHint(label)
                && !looksLikePromotionalCopy(label)
                && parseCountdownSeconds(label) < 0) {
            String cleanTitle = normalizeSeriesTitle(label);
            if (!cleanTitle.isEmpty()) {
                result.seriesTitle = cleanTitle;
            }
        } else if (captureSeriesContext
                && (isSeriesActivity(foregroundActivity) || scanningTargetPackage)
                && result.fallbackSeriesTitle.isEmpty()
                && looksLikeSeriesTitle(label, bounds)
                && !looksLikePromotionalCopy(label)) {
            result.fallbackSeriesTitle = normalizeSeriesTitle(label);
        }
        if (isSwipeHint(label)) {
            result.hasSwipeHint = true;
        }
        if (label.contains("全屏观看")) {
            result.hasPlayerUi = true;
        }
        if (captureSeriesContext && episodeFromLabel > 0) {
            result.episodeNumber = episodeFromLabel;
        } else if (captureSeriesContext && result.episodeNumber <= 0) {
            Matcher episodeMatcher = EPISODE_NUMBER.matcher(label);
            if (episodeMatcher.matches()) {
                result.episodeNumber = safeInt(episodeMatcher.group(1));
            }
        }

        int seconds = parseCountdownSeconds(label);
        if (seconds >= 0) {
            result.hasCountdown = true;
            if (result.countdownSeconds < 0 || seconds < result.countdownSeconds) {
                result.countdownSeconds = seconds;
            }
            result.countdownLabel = label;
        }

        SkipCandidate candidate = parseSkipCandidate(label, node, bounds);
        if (candidate != null && (result.bestSkip == null
                || candidate.score > result.bestSkip.score)) {
            result.bestSkip = candidate;
        }
    }

    private void inspectRewardLabel(String label, AccessibilityNodeInfo node,
                                    Rect bounds, ScanResult result) {
        if (!scanningTargetPackage) {
            return;
        }
        String compact = label.trim().replaceAll("\\s+", "");
        if (compact.isEmpty()) {
            return;
        }

        if (REWARD_DIALOG_TITLE.matcher(compact).find()
                || compact.equals("仅今日关闭")
                || compact.equals("仅今日隐藏")) {
            result.hasRewardCloseDialog = true;
            result.rewardDialogLabel = compact;
        }

        if (REWARD_PERMANENT_CLOSE.matcher(compact).matches()
                && isNodeOrParentClickable(node)) {
            int score = compact.startsWith("永久")
                    || compact.startsWith("确认") ? 30 : 20;
            if (result.rewardPermanentCloseButton == null
                    || score > result.rewardPermanentCloseButton.score) {
                result.rewardPermanentCloseButton =
                        new NodeCandidate(compact, node, bounds, score);
            }
        }

        if (!REWARD_WIDGET_LABEL.matcher(compact).matches()) {
            return;
        }
        Rect screen = currentDisplayBounds();
        if (bounds.centerX() < screen.left + screen.width() * 0.55f
                || bounds.top < screen.top + screen.height() * 0.18f
                || bounds.bottom > screen.top + screen.height() * 0.72f) {
            return;
        }
        result.hasRewardWidget = true;
        if (result.rewardWidgetLabel.isEmpty()) {
            result.rewardWidgetLabel = compact;
        }
    }

    private void inspectRewardCloseNode(AccessibilityNodeInfo node,
                                        String viewId, Rect bounds,
                                        ScanResult result) {
        if (!scanningTargetPackage
                || !viewId.endsWith(":id/eyr")
                || !isNodeVisibleToUser(node)
                || !isLikelyRewardClose(node, bounds)) {
            return;
        }
        result.rewardCloseNode = node;
        result.rewardCloseBounds = new Rect(bounds);
    }

    private boolean isLikelyRewardClose(AccessibilityNodeInfo node, Rect closeBounds) {
        Rect screen = currentDisplayBounds();
        if (closeBounds.centerX() < screen.left + screen.width() * 0.70f
                || closeBounds.centerY() < screen.top + screen.height() * 0.18f
                || closeBounds.centerY() > screen.top + screen.height() * 0.72f) {
            return false;
        }

        AccessibilityNodeInfo current = node.getParent();
        for (int i = 0; current != null && i < 5; i++) {
            Rect container = new Rect();
            current.getBoundsInScreen(container);
            if (container.left >= screen.left + screen.width() * 0.50f
                    && container.width() <= screen.width() * 0.45f
                    && container.height() >= closeBounds.height()
                    && container.height() <= screen.height() * 0.40f) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    private boolean isAdBadge(String value) {
        String s = value.trim().toLowerCase(Locale.ROOT);
        return s.equals("广告")
                || s.equals("ad")
                || s.equals("advertisement")
                || s.startsWith("广告 ")
                || s.startsWith("广告·")
                || s.startsWith("广告|")
                || s.equals("广告 |");
    }

    private boolean isSwipeHint(String value) {
        String compact = value.replace(" ", "");
        return compact.contains("上滑继续观看")
                || compact.contains("上滑观看")
                || compact.contains("继续观看短剧")
                || compact.contains("上滑继续");
    }

    private int parseCountdownSeconds(String value) {
        String compact = value.replace(" ", "");
        if (compact.isEmpty() || compact.length() > 80) {
            return -1;
        }
        if (!compact.contains("后")
                && !compact.contains("跳过")
                && !compact.contains("关闭")) {
            return -1;
        }

        Matcher matcher = NUMBER_COUNTDOWN.matcher(compact);
        if (matcher.find()) {
            return safeInt(matcher.group(1));
        }
        matcher = SKIP_COUNTDOWN.matcher(compact);
        if (matcher.find()) {
            return safeInt(matcher.group(1));
        }
        matcher = CHINESE_COUNTDOWN.matcher(compact);
        if (matcher.find()) {
            return chineseNumber(matcher.group(1));
        }
        return -1;
    }

    private SkipCandidate parseSkipCandidate(String label,
                                             AccessibilityNodeInfo node,
                                             Rect bounds) {
        String compact = label.trim().replaceAll("\\s+", " ");
        if (compact.isEmpty() || !SKIP_PATTERN.matcher(compact).matches()) {
            return null;
        }

        String lower = compact.toLowerCase(Locale.ROOT);
        boolean explicitAd = lower.contains("广告") || lower.contains("ad");
        boolean genericClose = compact.equals("关闭") || lower.equals("close")
                || compact.equals("×") || compact.equals("✕")
                || lower.equals("x");
        int score = 10;
        if (lower.contains("跳过广告")) {
            score = 110;
        } else if (lower.startsWith("跳过")) {
            score = 100;
        } else if (lower.contains("关闭广告")) {
            score = 80;
        } else if (genericClose) {
            score = 35;
        } else if (lower.contains("跳过")) {
            score = 90;
        }

        boolean clickable = node.isClickable() || hasClickableParent(node);
        if (clickable) {
            score += 18;
        }
        if (isTopRight(bounds)) {
            score += 9;
        }
        if (bounds.width() > 0 && bounds.height() > 0
                && bounds.width() < bounds.height() * 6) {
            score += 3;
        }
        return new SkipCandidate(compact, node, bounds, score, explicitAd, genericClose);
    }

    private boolean isTopRight(Rect bounds) {
        Rect screen = currentDisplayBounds();
        int width = screen.width();
        int height = screen.height();
        return bounds.left > width / 2 && bounds.top < height / 3;
    }

    private boolean tryClick(SkipCandidate candidate) {
        return tryClickNode(candidate.node, candidate.bounds);
    }

    private boolean tryClickNode(AccessibilityNodeInfo node, Rect bounds) {
        AccessibilityNodeInfo current = node;
        for (int i = 0; current != null && i < 4; i++) {
            if (current.isEnabled() && current.isClickable()) {
                try {
                    if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        return true;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "node click failed", e);
                }
            }
            current = current.getParent();
        }

        if (bounds.width() <= 0 || bounds.height() <= 0) {
            return false;
        }
        Path path = new Path();
        path.moveTo(bounds.centerX(), bounds.centerY());
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 60))
                .build();
        return dispatchGesture(gesture, null, null);
    }

    private boolean isNodeOrParentClickable(AccessibilityNodeInfo node) {
        return node.isClickable() || hasClickableParent(node);
    }

    private boolean hasClickableParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node.getParent();
        for (int i = 0; current != null && i < 3; i++) {
            if (current.isClickable() && current.isEnabled()) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    private boolean swipeToNext() {
        Rect screen = currentDisplayBounds();
        int width = screen.width();
        int height = screen.height();
        Path path = new Path();
        path.moveTo(width * 0.5f, height * 0.82f);
        path.lineTo(width * 0.5f, height * 0.20f);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 360))
                .build();
        return dispatchGesture(gesture, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                hideCover();
                scheduleScan(220);
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                hideCover();
                scheduleScan(350);
            }
        }, handler);
    }

    private boolean isSeriesActivity(String activity) {
        if (activity == null) {
            return false;
        }
        String lower = activity.toLowerCase(Locale.ROOT);
        return lower.contains("shortseries")
                || lower.contains("shortvideo")
                || lower.contains("short_series")
                || lower.contains("fullscreen");
    }

    private boolean isFullscreenSeriesActivity(String activity) {
        if (activity == null) {
            return false;
        }
        String lower = activity.toLowerCase(Locale.ROOT);
        return lower.contains("shortseriesland")
                || lower.contains("fullscreen");
    }

    private boolean isLandscapeDisplay() {
        Rect bounds = currentDisplayBounds();
        return bounds.width() > bounds.height();
    }

    private boolean isMainActivity(String activity) {
        if (activity == null) {
            return false;
        }
        String lower = activity.toLowerCase(Locale.ROOT);
        return lower.contains("mainfragmentactivity")
                || lower.contains("mainactivity");
    }

    private boolean looksLikeSeriesTitle(String value, Rect bounds) {
        if (value.length() < 4 || value.length() > 60) {
            return false;
        }
        if (isEpisodeNavigationLabel(value)) {
            return false;
        }
        if (hanCharacterCount(value) < 5) {
            return false;
        }
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        return bounds.top > screenHeight * 0.64f
                && bounds.bottom < screenHeight * 0.90f
                && !value.startsWith("第")
                && !value.startsWith("作者声明")
                && !value.equals("选集")
                && !value.equals("展开")
                && !value.equals("分享");
    }

    private boolean looksLikePromotionalCopy(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        if (value.length() > 42
                && (value.contains("！") || value.contains("，")
                || value.contains("《") || value.contains("》"))) {
            return true;
        }
        return value.contains("福利版")
                || value.contains("点击领取")
                || value.contains("立即下载")
                || value.contains("广告素材")
                || value.contains("专属礼包");
    }

    private int episodeFromCombinedTitle(String value) {
        if (value == null) {
            return -1;
        }
        Matcher matcher = TITLE_WITH_EPISODE.matcher(value.trim());
        if (!matcher.matches()) {
            return -1;
        }
        return safeInt(matcher.group(2));
    }

    private String normalizeSeriesTitle(String value) {
        if (value == null) {
            return "";
        }
        String title = value.trim();
        Matcher matcher = TITLE_WITH_EPISODE.matcher(title);
        if (matcher.matches()) {
            title = matcher.group(1).trim();
        }
        if (title.isEmpty() || EPISODE_NUMBER.matcher(title).matches()) {
            return "";
        }
        return title;
    }

    private Rect currentDisplayBounds() {
        Rect bounds = new Rect();
        if (windowManager != null) {
            try {
                if (Build.VERSION.SDK_INT >= 30) {
                    bounds.set(windowManager.getCurrentWindowMetrics().getBounds());
                } else {
                    android.util.DisplayMetrics metrics =
                            getResources().getDisplayMetrics();
                    bounds.set(0, 0, metrics.widthPixels, metrics.heightPixels);
                }
            } catch (Exception e) {
                Log.w(TAG, "display bounds read failed", e);
            }
            try {
                if (bounds.width() > 0 && bounds.height() > 0) {
                    android.view.Display display = windowManager.getDefaultDisplay();
                    int rotation = display.getRotation();
                    boolean rotatedLandscape = rotation
                            == android.view.Surface.ROTATION_90
                            || rotation == android.view.Surface.ROTATION_270;
                    if (rotatedLandscape && bounds.width() < bounds.height()) {
                        int width = bounds.width();
                        bounds.right = bounds.left + bounds.height();
                        bounds.bottom = bounds.top + width;
                    } else if (!rotatedLandscape && bounds.width() > bounds.height()) {
                        int width = bounds.width();
                        bounds.right = bounds.left + bounds.height();
                        bounds.bottom = bounds.top + width;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        if (bounds.width() <= 0 || bounds.height() <= 0) {
            android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
            bounds.set(0, 0, metrics.widthPixels, metrics.heightPixels);
        }
        return bounds;
    }

    private boolean isEpisodeNavigationLabel(String value) {
        return EPISODE_PROGRESS.matcher(value).matches()
                || EPISODE_RANGE.matcher(value).matches();
    }

    private int hanCharacterCount(String value) {
        int count = 0;
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) {
                count++;
            }
            offset += Character.charCount(codePoint);
        }
        return count;
    }

    private void showCover(ScanResult result) {
        if (coverFailed || windowManager == null
                || !Prefs.coverAds(this) || !Prefs.autoSkip(this)) {
            return;
        }

        try {
            if (coverView == null) {
                FrameLayout frame = new FrameLayout(this);
                frame.setBackgroundColor(Color.rgb(9, 14, 27));

                LinearLayout panel = new LinearLayout(this);
                panel.setOrientation(LinearLayout.VERTICAL);
                panel.setGravity(Gravity.CENTER);
                panel.setPadding(dp(26), dp(24), dp(26), dp(24));

                TextView badge = new TextView(this);
                badge.setText("净屏");
                badge.setTextSize(13);
                badge.setTypeface(Typeface.DEFAULT_BOLD);
                badge.setTextColor(Color.rgb(15, 23, 42));
                badge.setGravity(Gravity.CENTER);
                GradientDrawable badgeBg = new GradientDrawable();
                badgeBg.setColor(Color.rgb(52, 211, 153));
                badgeBg.setCornerRadius(dp(99));
                badge.setBackground(badgeBg);
                LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(dp(58), dp(30));
                badgeParams.gravity = Gravity.CENTER_HORIZONTAL;
                panel.addView(badge, badgeParams);

                coverTitle = new TextView(this);
                coverTitle.setTextSize(24);
                coverTitle.setTextColor(Color.WHITE);
                coverTitle.setTypeface(Typeface.DEFAULT_BOLD);
                coverTitle.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                titleParams.topMargin = dp(18);
                panel.addView(coverTitle, titleParams);

                coverDetail = new TextView(this);
                coverDetail.setTextSize(14);
                coverDetail.setTextColor(Color.rgb(203, 213, 225));
                coverDetail.setGravity(Gravity.CENTER);
                coverDetail.setLineSpacing(dp(3), 1f);
                LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                detailParams.topMargin = dp(10);
                panel.addView(coverDetail, detailParams);

                FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER);
                frame.addView(panel, panelParams);

                WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT);
                params.gravity = Gravity.TOP | Gravity.START;
                if (Build.VERSION.SDK_INT >= 30) {
                    params.setFitInsetsTypes(0);
                    params.layoutInDisplayCutoutMode =
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
                }
                windowManager.addView(frame, params);
                coverView = frame;
            }

            int seconds = result.hasCountdown ? result.countdownSeconds : -1;
            coverTitle.setText("广告已拦截");
            if (seconds > 0) {
                coverDetail.setText("等待广告倒计时约 " + seconds + " 秒\n结束后自动继续");
            } else if (result.hasSwipeHint) {
                coverDetail.setText("正在自动翻页…");
            } else {
                coverDetail.setText("正在自动离开广告页…");
            }
            coverSeconds = seconds;
            coverView.setVisibility(View.VISIBLE);
        } catch (Exception e) {
            coverFailed = true;
            Log.w(TAG, "cover failed", e);
        }
    }

    private void hideCover() {
        if (coverView == null || windowManager == null) {
            return;
        }
        try {
            windowManager.removeView(coverView);
        } catch (Exception e) {
            Log.w(TAG, "cover remove failed", e);
        }
        coverView = null;
        coverTitle = null;
        coverDetail = null;
        coverSeconds = -1;
    }

    private void resetAdState() {
        adFirstSeenAt = 0;
        blankAdSince = 0;
        lastDetectionLog = "";
        waitForAdClear = false;
        waitForAdClearSince = 0;
        adSwipeAttempts = 0;
        adSwipeAttemptAt = 0;
        inPlayerSwipeAttempts = 0;
        inPlayerSwipeAt = 0;
        inPlayerSwipeArmed = false;
        inPlayerSwipeFromEpisode = -1;
        countdownFirstSeenAt = 0;
        lastAdLearnAt = 0;
        suppressBlindSwipeUntil = 0;
        awaitingEpisodeFrom = -1;
        awaitingEpisodeSince = 0;
    }

    private boolean maybeSwipeAdInPlayer(ScanResult result, boolean target, long now) {
        if (result.hasCountdown) {
            inPlayerSwipeArmed = false;
            return false;
        }
        boolean playerAd = target
                && result.hasAdBadge
                && (result.hasPlayerUi || result.episodeNumber > 0
                || isSeriesActivity(foregroundActivity));
        if (!playerAd) {
            inPlayerSwipeArmed = false;
            return false;
        }
        int sourceEpisode = lastStableEpisode > 0 ? lastStableEpisode
                : (result.episodeNumber > 0 ? result.episodeNumber : -1);
        if (inPlayerSwipeArmed && sourceEpisode > 0
                && sourceEpisode == inPlayerSwipeFromEpisode) {
            if (now - inPlayerSwipeAt < 3200L) {
                scheduleScan(320);
                return true;
            }
            inPlayerSwipeArmed = false;
        }
        if (inPlayerSwipeAttempts >= 3
                || now - lastActionAt < ACTION_COOLDOWN_MS
                || now - inPlayerSwipeAt < 3200L
                || now - adFirstSeenAt < 900L) {
            return false;
        }
        if (!isSeriesActivity(foregroundActivity) && result.episodeNumber <= 0) {
            return false;
        }
        if (!swipeToNext()) {
            return false;
        }
        inPlayerSwipeAttempts++;
        inPlayerSwipeAt = now;
        inPlayerSwipeFromEpisode = sourceEpisode;
        inPlayerSwipeArmed = sourceEpisode > 0;
        lastActionAt = now;
        adSwipeAttempts++;
        adSwipeAttemptAt = now;
        waitForAdClear = true;
        waitForAdClearSince = now;
        armAdTransition(result, now);
        Prefs.bumpSkipped(this);
        Prefs.recordEvent(this, "已在播放页上滑离开广告（第 "
                + inPlayerSwipeAttempts + " 次）");
        Log.i(TAG, "swiped ad inside player, attempt=" + inPlayerSwipeAttempts
                + " fromEpisode=" + sourceEpisode);
        scheduleScan(280);
        return true;
    }

    private boolean isNodeVisibleToUser(AccessibilityNodeInfo node) {
        try {
            return node.isVisibleToUser();
        } catch (Exception e) {
            return false;
        }
    }

    private void refreshForegroundFromActiveRoot() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) {
                return;
            }
            String pkg = asString(root.getPackageName());
            if (pkg.isEmpty() || pkg.equals(getPackageName()) || isSystemUiPackage(pkg)) {
                return;
            }
            if (!pkg.equals(foregroundPackage)) {
                long now = SystemClock.uptimeMillis();
                onForegroundChanged(pkg, foregroundActivity, now);
                Log.i(TAG, "foreground from root=" + pkg);
            }
        } catch (Exception e) {
            Log.w(TAG, "refresh foreground failed", e);
        }
    }

    private int safeInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return -1;
        }
    }

    private int chineseNumber(String value) {
        if (value.equals("十")) {
            return 10;
        }
        if (value.length() == 1) {
            return "一二三四五六七八九".indexOf(value) + 1;
        }
        if (value.startsWith("十")) {
            return 10 + Math.max(0, "一二三四五六七八九".indexOf(value.charAt(1)) + 1);
        }
        if (value.endsWith("十")) {
            return Math.max(0, "一二三四五六七八九".indexOf(value.charAt(0)) + 1) * 10;
        }
        return -1;
    }

    private String asString(CharSequence value) {
        return value == null ? "" : value.toString();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class ScanResult {
        boolean hasAdBadge;
        boolean hasCountdown;
        boolean hasSwipeHint;
        boolean hasPlayerUi;
        boolean hasRewardWidget;
        boolean hasRewardCloseDialog;
        boolean searchPage;
        int countdownSeconds = -1;
        int episodeNumber = -1;
        int seasonNumber = -1;
        String adBadgeLabel = "";
        String countdownLabel = "";
        String seriesTitle = "";
        String fallbackSeriesTitle = "";
        String rewardWidgetLabel = "";
        String rewardDialogLabel = "";
        AccessibilityNodeInfo rewardCloseNode;
        Rect rewardCloseBounds;
        NodeCandidate rewardPermanentCloseButton;
        SkipCandidate bestSkip;
    }

    private static final class VisualTextInfo {
        boolean visualAd;
        int episodeNumber = -1;
        String seriesTitle = "";
    }

    private static final class SkipCandidate {
        final String label;
        final AccessibilityNodeInfo node;
        final Rect bounds;
        final int score;
        final boolean explicitAd;
        final boolean genericClose;

        SkipCandidate(String label, AccessibilityNodeInfo node, Rect bounds,
                      int score, boolean explicitAd, boolean genericClose) {
            this.label = label;
            this.node = node;
            this.bounds = new Rect(bounds);
            this.score = score;
            this.explicitAd = explicitAd;
            this.genericClose = genericClose;
        }
    }

    private static final class NodeCandidate {
        final String label;
        final AccessibilityNodeInfo node;
        final Rect bounds;
        final int score;

        NodeCandidate(String label, AccessibilityNodeInfo node,
                      Rect bounds, int score) {
            this.label = label;
            this.node = node;
            this.bounds = new Rect(bounds);
            this.score = score;
        }
    }
}

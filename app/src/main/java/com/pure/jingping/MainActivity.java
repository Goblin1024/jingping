package com.pure.jingping;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MainActivity extends Activity {
    private static final int REQUEST_VPN = 2107;
    private static final int REQUEST_NOTIFICATIONS = 2108;
    private static final int BG = Color.rgb(245, 247, 250);
    private static final int CARD = Color.WHITE;
    private static final int TEXT = Color.rgb(15, 23, 42);
    private static final int MUTED = Color.rgb(100, 116, 139);
    private static final int GREEN = Color.rgb(22, 163, 74);
    private static final int GREEN_LIGHT = Color.rgb(220, 252, 231);
    private static final int BLUE = Color.rgb(37, 99, 235);
    private static final int DARK = Color.rgb(11, 16, 32);
    private static final int WARN_BG = Color.rgb(255, 251, 235);
    private static final int WARN_TEXT = Color.rgb(146, 64, 14);

    private TextView statusTitle;
    private TextView statusDetail;
    private Button statusButton;
    private TextView skippedValue;
    private TextView returnedValue;
    private TextView lastEvent;
    private TextView dnsStatusTitle;
    private TextView dnsStatusDetail;
    private TextView dnsLastEvent;
    private TextView dnsBlockedValue;
    private Button dnsButton;
    private Button dnsLearnButton;
    private TextView dnsLearnSummary;
    private Button dnsAddRule;
    private EditText dnsRuleInput;
    private CheckBox autoSkip;
    private CheckBox autoReturn;
    private CheckBox coverAds;
    private CheckBox forceSkipCountdown;
    private CheckBox closeRewardWidgets;
    private CheckBox globalMode;
    private final Map<String, CheckBox> targetChecks = new LinkedHashMap<>();
    private boolean updating;
    private final Runnable targetRestartRunnable = this::restartDnsForTargets;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureWindow();
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        if (statusTitle != null) {
            statusTitle.postDelayed(this::refresh, 900);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && statusTitle != null) {
            refresh();
        }
    }

    private void configureWindow() {
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= 26) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        getWindow().getDecorView().setSystemUiVisibility(flags);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        }
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(22), dp(22), dp(30));
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        if (Build.VERSION.SDK_INT >= 21) {
            scroll.setOnApplyWindowInsetsListener((view, insets) -> {
                int top;
                int bottom;
                if (Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                    top = bars.top;
                    bottom = bars.bottom;
                } else {
                    top = insets.getSystemWindowInsetTop();
                    bottom = insets.getSystemWindowInsetBottom();
                }
                view.setPadding(0, top, 0, bottom);
                return insets;
            });
        }

        addHeader(root);
        addStatusCard(root);
        addStats(root);
        addDnsCard(root);

        TextView rulesTitle = text("防护规则", 18, TEXT, true);
        add(root, rulesTitle, 0, dp(24), 0, dp(8));

        LinearLayout rules = card(CARD);
        autoSkip = addToggle(rules, "自动跳过广告",
                "识别“跳过”“关闭广告”等按钮，并自动离开插页广告");
        coverAds = addToggle(rules, "遮挡广告画面",
                "倒计时期间显示净屏页，结束后自动继续，避免看到广告内容");
        forceSkipCountdown = addToggle(rules, "广告秒退 · 精确选集",
                "识别广告页后退出，通过播放器选集直接从头播放下一集");
        closeRewardWidgets = addToggle(rules, "关闭金币 / 红包挂件",
                "在红果播放器内识别“立即领取”“听读赚钱”浮层，自动暂停、点 X 并选择永久关闭");
        autoReturn = addToggle(rules, "摇一摇误跳自动返回",
                "检测无触摸的第三方跳转并返回原 App；手动点击的跳转不会拦截");
        globalMode = addToggle(rules, "所有 App 的开屏广告",
                "对非重点应用只点击非常明确的“跳过广告”类按钮");
        add(root, rules, 0, 0, 0, dp(4));

        TextView targetsTitle = text("重点应用", 18, TEXT, true);
        add(root, targetsTitle, 0, dp(24), 0, dp(8));

        LinearLayout targets = card(CARD);
        Map<String, Boolean> installed = TargetApps.installedMap(this);
        for (int i = 0; i < TargetApps.PACKAGES.length; i++) {
            String pkg = TargetApps.PACKAGES[i];
            boolean isInstalled = Boolean.TRUE.equals(installed.get(pkg));
            String label = TargetApps.LABELS[i] + (isInstalled ? "" : "（未安装）")
                    + "\n" + pkg;
            CheckBox checkBox = addToggle(targets, label, null);
            checkBox.setEnabled(isInstalled);
            targetChecks.put(pkg, checkBox);
        }
        add(root, targets, 0, 0, 0, dp(4));

        LinearLayout limits = new LinearLayout(this);
        limits.setOrientation(LinearLayout.VERTICAL);
        limits.setPadding(dp(16), dp(15), dp(16), dp(15));
        limits.setBackground(rounded(WARN_BG, 18));

        TextView limitTitle = text("无 Root 的系统硬限制", 14, WARN_TEXT, true);
        limits.addView(limitTitle);
        TextView limitBody = text(
                "本地过滤会在 DNS、HTTPDNS 和 TLS 主机名阶段阻断已知广告请求，"
                        + "并保持目标 App 的正常联网判断。"
                        + " 与业务共用域名、DoH/QUIC 或无域名 IP 直连仍可能漏过；"
                        + "视频内烧录广告也无法移除，漏网广告会继续自动跳过、遮挡或精确选集。"
                        + " 要从代码层彻底删除广告，仍需要 Root/LSPosed。",
                13, WARN_TEXT, false);
        limitBody.setLineSpacing(dp(3), 1f);
        add(limits, limitBody, 0, dp(8), 0, 0);
        add(root, limits, 0, dp(20), 0, 0);

        TextView footer = text("净屏 v1.1.8  ·  本地请求过滤，不连接第三方代理", 12, MUTED, false);
        footer.setGravity(Gravity.CENTER);
        add(root, footer, 0, dp(22), 0, 0);

        setContentView(scroll);
        wireActions();
    }

    private void addHeader(LinearLayout root) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView mark = text("净", 25, Color.rgb(52, 211, 153), true);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(rounded(DARK, 16));
        row.addView(mark, new LinearLayout.LayoutParams(dp(52), dp(52)));

        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setPadding(dp(14), 0, 0, 0);
        TextView title = text("净屏", 30, TEXT, true);
        words.addView(title);
        TextView subtitle = text("本地广告请求拦截 · 开屏自动跳过 · 精确选集",
                12, MUTED, false);
        words.addView(subtitle);
        row.addView(words, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        add(root, row, 0, 0, 0, dp(22));
    }

    private void addStatusCard(LinearLayout root) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(18), dp(20), dp(18));
        card.setBackground(rounded(DARK, 22));

        statusTitle = text("", 21, Color.WHITE, true);
        card.addView(statusTitle);
        statusDetail = text("", 13, Color.rgb(203, 213, 225), false);
        statusDetail.setLineSpacing(dp(2), 1f);
        add(card, statusDetail, 0, dp(6), 0, dp(14));

        statusButton = new Button(this);
        statusButton.setAllCaps(false);
        statusButton.setTextSize(14);
        statusButton.setTextColor(Color.WHITE);
        statusButton.setTypeface(Typeface.DEFAULT_BOLD);
        statusButton.setBackground(rounded(GREEN, 14));
        statusButton.setPadding(dp(16), dp(9), dp(16), dp(9));
        statusButton.setMinHeight(0);
        statusButton.setMinWidth(0);
        statusButton.setStateListAnimator(null);
        card.addView(statusButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46)));

        add(root, card, 0, 0, 0, dp(14));
    }

    private void addStats(LinearLayout root) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout left = statCard("已跳过", value -> skippedValue = value);
        LinearLayout middle = statCard("请求阻断", value -> dnsBlockedValue = value);
        LinearLayout right = statCard("已拦截误跳", value -> returnedValue = value);
        LinearLayout.LayoutParams leftParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        leftParams.rightMargin = dp(5);
        LinearLayout.LayoutParams middleParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        middleParams.leftMargin = dp(5);
        middleParams.rightMargin = dp(5);
        LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        rightParams.leftMargin = dp(5);
        row.addView(left, leftParams);
        row.addView(middle, middleParams);
        row.addView(right, rightParams);
        add(root, row, 0, 0, 0, dp(2));

        lastEvent = text("暂无拦截记录", 12, MUTED, false);
        lastEvent.setPadding(dp(4), dp(8), dp(4), 0);
        add(root, lastEvent, 0, 0, 0, 0);
    }

    private interface ValueSink {
        void accept(TextView view);
    }

    private LinearLayout statCard(String label, ValueSink sink) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(rounded(CARD, 18));
        TextView value = text("0", 25, TEXT, true);
        sink.accept(value);
        card.addView(value);
        card.addView(text(label, 12, MUTED, false));
        return card;
    }

    private void addDnsCard(LinearLayout root) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        card.setBackground(rounded(CARD, 20));

        TextView heading = text("本地广告请求拦截", 18, TEXT, true);
        card.addView(heading);

        dnsStatusTitle = text("", 15, BLUE, true);
        add(card, dnsStatusTitle, 0, dp(10), 0, 0);

        dnsStatusDetail = text("", 12, MUTED, false);
        dnsStatusDetail.setLineSpacing(dp(3), 1f);
        add(card, dnsStatusDetail, 0, dp(5), 0, dp(12));

        dnsButton = new Button(this);
        dnsButton.setAllCaps(false);
        dnsButton.setTextSize(14);
        dnsButton.setTextColor(Color.WHITE);
        dnsButton.setTypeface(Typeface.DEFAULT_BOLD);
        dnsButton.setBackground(rounded(BLUE, 13));
        dnsButton.setPadding(dp(14), dp(8), dp(14), dp(8));
        dnsButton.setMinHeight(0);
        dnsButton.setMinWidth(0);
        dnsButton.setStateListAnimator(null);
        card.addView(dnsButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44)));

        dnsLastEvent = text("暂无请求阻断记录", 12, MUTED, false);
        dnsLastEvent.setPadding(0, dp(10), 0, 0);
        card.addView(dnsLastEvent);

        TextView customLabel = text("自定义广告域名", 14, TEXT, true);
        add(card, customLabel, 0, dp(16), 0, dp(6));

        LinearLayout customRow = new LinearLayout(this);
        customRow.setOrientation(LinearLayout.HORIZONTAL);
        customRow.setGravity(Gravity.CENTER_VERTICAL);

        dnsRuleInput = new EditText(this);
        dnsRuleInput.setSingleLine(true);
        dnsRuleInput.setTextSize(13);
        dnsRuleInput.setTextColor(TEXT);
        dnsRuleInput.setHintTextColor(MUTED);
        dnsRuleInput.setHint("例如 ads.example.com");
        dnsRuleInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_URI);
        dnsRuleInput.setPadding(0, dp(7), dp(8), dp(7));
        customRow.addView(dnsRuleInput, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        dnsAddRule = new Button(this);
        dnsAddRule.setText("添加");
        dnsAddRule.setAllCaps(false);
        dnsAddRule.setTextSize(13);
        dnsAddRule.setTextColor(TEXT);
        dnsAddRule.setBackground(rounded(GREEN_LIGHT, 11));
        dnsAddRule.setMinHeight(0);
        dnsAddRule.setMinWidth(0);
        dnsAddRule.setStateListAnimator(null);
        customRow.addView(dnsAddRule, new LinearLayout.LayoutParams(dp(68), dp(40)));
        card.addView(customRow);

        TextView customHint = text("后缀匹配；添加后立即生效，适合排查未覆盖的广告域名。",
                11, MUTED, false);
        customHint.setPadding(0, dp(5), 0, 0);
        card.addView(customHint);

        dnsLearnSummary = text("自动学习：暂无记录", 12, MUTED, false);
        dnsLearnSummary.setPadding(0, dp(14), 0, dp(6));
        card.addView(dnsLearnSummary);

        dnsLearnButton = new Button(this);
        dnsLearnButton.setAllCaps(false);
        dnsLearnButton.setTextSize(13);
        dnsLearnButton.setTextColor(TEXT);
        dnsLearnButton.setBackground(rounded(GREEN_LIGHT, 11));
        dnsLearnButton.setMinHeight(0);
        dnsLearnButton.setMinWidth(0);
        dnsLearnButton.setStateListAnimator(null);
        dnsLearnButton.setText("查看学习记录");
        card.addView(dnsLearnButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(40)));

        add(root, card, 0, dp(18), 0, 0);
    }

    private void showLearnedDomains() {
        Set<String> learned = Prefs.getDnsLearnedRules(this);
        Set<String> pending = Prefs.getDnsPendingRules(this);

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        list.setPadding(pad, pad, pad, pad);

        if (!pending.isEmpty()) {
            list.addView(text("待确认（点击屏蔽）", 14, TEXT, true));
            for (final String domain : pending) {
                list.addView(domainRow(domain, "屏蔽", () -> {
                    Prefs.addDnsCustomRule(this, domain);
                    Prefs.removeDnsPendingRule(this, domain);
                    DnsFilterVpnService.reloadRules();
                    refresh();
                }));
            }
        }

        if (!learned.isEmpty()) {
            TextView label = text("已自动屏蔽", 14, TEXT, true);
            label.setPadding(0, dp(14), 0, 0);
            list.addView(label);
            for (final String domain : learned) {
                list.addView(domainRow(domain, "取消", () -> {
                    Prefs.removeDnsLearnedRule(this, domain);
                    DnsFilterVpnService.reloadRules();
                    refresh();
                }));
            }
        }

        if (pending.isEmpty() && learned.isEmpty()) {
            list.addView(text("暂无学习记录。广告出现后会自动记录相关域名。",
                    13, MUTED, false));
        }

        TextView hint = text("自动学习只自动屏蔽高置信广告域名；"
                + "其余需要你确认，避免误伤视频 CDN。", 11, MUTED, false);
        hint.setPadding(0, dp(14), 0, 0);
        list.addView(hint);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);
        new AlertDialog.Builder(this)
                .setTitle("广告域名学习")
                .setView(scroll)
                .setPositiveButton("关闭", null)
                .show();
    }

    private View domainRow(String domain, String action, Runnable onAction) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));

        TextView name = text(domain, 12, TEXT, false);
        row.addView(name, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(action);
        button.setTextSize(12);
        button.setTextColor(TEXT);
        button.setBackground(rounded(GREEN_LIGHT, 10));
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setStateListAnimator(null);
        button.setOnClickListener(view -> {
            onAction.run();
            row.setVisibility(View.GONE);
        });
        row.addView(button, new LinearLayout.LayoutParams(dp(64), dp(36)));
        return row;
    }

    private CheckBox addToggle(LinearLayout parent, String title, String subtitle) {
        CheckBox check = new CheckBox(this);
        check.setText(title);
        check.setTextSize(15);
        check.setTextColor(TEXT);
        check.setButtonTintList(ColorStateList.valueOf(GREEN));
        check.setPadding(0, dp(9), 0, dp(9));
        parent.addView(check);

        if (subtitle != null) {
            TextView detail = text(subtitle, 12, MUTED, false);
            detail.setPadding(dp(42), 0, dp(8), dp(9));
            parent.addView(detail);
        }
        return check;
    }

    private void wireActions() {
        dnsButton.setOnClickListener(view -> toggleDnsFilter());
        dnsAddRule.setOnClickListener(view -> addCustomDnsRule());
        dnsLearnButton.setOnClickListener(view -> showLearnedDomains());

        statusButton.setOnClickListener(view -> {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (Exception e) {
                Toast.makeText(this, "无法打开无障碍设置", Toast.LENGTH_SHORT).show();
            }
        });

        autoSkip.setOnCheckedChangeListener((button, checked) -> {
            if (!updating) {
                Prefs.setAutoSkip(this, checked);
            }
        });
        coverAds.setOnCheckedChangeListener((button, checked) -> {
            if (!updating) {
                Prefs.setCoverAds(this, checked);
            }
        });
        forceSkipCountdown.setOnCheckedChangeListener((button, checked) -> {
            if (!updating) {
                Prefs.setForceSkipCountdown(this, checked);
            }
        });
        closeRewardWidgets.setOnCheckedChangeListener((button, checked) -> {
            if (!updating) {
                Prefs.setCloseRewardWidgets(this, checked);
            }
        });
        autoReturn.setOnCheckedChangeListener((button, checked) -> {
            if (!updating) {
                Prefs.setAutoReturn(this, checked);
            }
        });
        globalMode.setOnCheckedChangeListener((button, checked) -> {
            if (!updating) {
                Prefs.setGlobalMode(this, checked);
            }
        });
        for (Map.Entry<String, CheckBox> entry : targetChecks.entrySet()) {
            entry.getValue().setOnCheckedChangeListener((button, checked) -> {
                if (!updating) {
                    Prefs.setTarget(this, entry.getKey(), checked);
                    scheduleDnsTargetRestart();
                }
            });
        }
    }

    private void toggleDnsFilter() {
        if (DnsFilterVpnService.isRunning()) {
            Prefs.setDnsFilter(this, false);
            Intent stop = new Intent(this, DnsFilterVpnService.class)
                    .setAction(DnsFilterVpnService.ACTION_STOP);
            startService(stop);
            dnsButton.postDelayed(this::refresh, 350);
            return;
        }

        Prefs.setDnsFilter(this, true);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATIONS);
            return;
        }
        requestVpnPermission();
    }

    private void requestVpnPermission() {
        Intent prepare = VpnService.prepare(this);
        if (prepare != null) {
            try {
                startActivityForResult(prepare, REQUEST_VPN);
            } catch (Exception e) {
                Prefs.setDnsFilter(this, false);
                Toast.makeText(this, "无法打开 VPN 授权页", Toast.LENGTH_SHORT).show();
            }
        } else {
            startDnsService();
        }
    }

    private void startDnsService() {
        try {
            Intent service = new Intent(this, DnsFilterVpnService.class);
            startForegroundService(service);
            Toast.makeText(this, "本地广告过滤已开启", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Prefs.setDnsFilter(this, false);
            Toast.makeText(this, "本地过滤服务启动失败：" + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
        dnsButton.postDelayed(this::refresh, 600);
    }

    private void scheduleDnsTargetRestart() {
        if (dnsButton == null || !DnsFilterVpnService.isRunning()) {
            return;
        }
        dnsButton.removeCallbacks(targetRestartRunnable);
        dnsButton.postDelayed(targetRestartRunnable, 700);
    }

    private void restartDnsForTargets() {
        if (!DnsFilterVpnService.isRunning()) {
            refresh();
            return;
        }
        Intent stop = new Intent(this, DnsFilterVpnService.class)
                .setAction(DnsFilterVpnService.ACTION_STOP);
        startService(stop);
        if (Prefs.getTargets(this).isEmpty()) {
            Toast.makeText(this, "未选择重点应用，本地过滤已停止",
                    Toast.LENGTH_SHORT).show();
            dnsButton.postDelayed(this::refresh, 500);
            return;
        }
        dnsButton.postDelayed(() -> {
            Prefs.setDnsFilter(this, true);
            startDnsService();
        }, 650);
    }

    private void addCustomDnsRule() {
        String raw = dnsRuleInput.getText().toString().trim().toLowerCase(Locale.ROOT);
        String domain = raw;
        if (raw.contains("://")) {
            try {
                domain = Uri.parse(raw).getHost();
            } catch (Exception ignored) {
                domain = null;
            }
        } else {
            int slash = domain.indexOf('/');
            if (slash >= 0) {
                domain = domain.substring(0, slash);
            }
        }
        if (domain != null) {
            domain = domain.trim();
            while (domain.startsWith(".")) {
                domain = domain.substring(1);
            }
            if (domain.startsWith("*.")) {
                domain = domain.substring(2);
            }
            while (domain.endsWith(".")) {
                domain = domain.substring(0, domain.length() - 1);
            }
        }
        if (domain == null || !domain.matches("[a-z0-9][a-z0-9.-]{2,252}")) {
            Toast.makeText(this, "请输入有效域名，例如 ads.example.com",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        Prefs.addDnsCustomRule(this, domain);
        DnsFilterVpnService.reloadRules();
        dnsRuleInput.setText("");
        Toast.makeText(this, "已添加并立即生效：" + domain, Toast.LENGTH_SHORT).show();
        refresh();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_VPN) {
            if (resultCode == RESULT_OK) {
                startDnsService();
            } else {
                Prefs.setDnsFilter(this, false);
                Toast.makeText(this, "未授权 VPN，本地广告过滤没有开启",
                        Toast.LENGTH_SHORT).show();
                refresh();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_NOTIFICATIONS) {
            requestVpnPermission();
        }
    }

    private void refresh() {
        updating = true;
        autoSkip.setChecked(Prefs.autoSkip(this));
        coverAds.setChecked(Prefs.coverAds(this));
        forceSkipCountdown.setChecked(Prefs.forceSkipCountdown(this));
        closeRewardWidgets.setChecked(Prefs.closeRewardWidgets(this));
        autoReturn.setChecked(Prefs.autoReturn(this));
        globalMode.setChecked(Prefs.globalMode(this));

        for (Map.Entry<String, CheckBox> entry : targetChecks.entrySet()) {
            entry.getValue().setChecked(Prefs.getTargets(this).contains(entry.getKey()));
        }

        boolean running = accessibilityEnabled();
        if (running) {
            statusTitle.setText("净屏正在运行");
            statusTitle.setTextColor(Color.rgb(52, 211, 153));
            statusDetail.setText("无障碍服务已连接。开屏广告、插页广告和红果金币/红包挂件会被自动处理。");
            statusButton.setText("查看无障碍设置");
        } else {
            statusTitle.setText("无障碍服务未开启");
            statusTitle.setTextColor(Color.WHITE);
            statusDetail.setText("开启“净屏 · 本地广告过滤”后才会生效。"
                    + "无障碍数据只在本机处理，不上传界面内容。");
            statusButton.setText("立即开启");
        }

        skippedValue.setText(String.valueOf(Prefs.skippedCount(this)));
        returnedValue.setText(String.valueOf(Prefs.returnCount(this)));
        String recent = Prefs.lastEvent(this);
        lastEvent.setText(recent.isEmpty() ? "暂无拦截记录" : "最近：" + recent);

        boolean dnsRunning = DnsFilterVpnService.isRunning();
        if (dnsRunning) {
            dnsStatusTitle.setText("● 本地过滤运行中");
            dnsStatusTitle.setTextColor(GREEN);
            dnsStatusDetail.setText("仅为已勾选的重点应用在本机透明转发；"
                    + "广告请求按域名规则阻断，其他 App 直接联网。"
                    + "不连接第三方 VPN 或代理服务器。");
            dnsButton.setText("停止本地过滤");
        } else {
            dnsStatusTitle.setText("○ 本地过滤未开启");
            dnsStatusTitle.setTextColor(BLUE);
            dnsStatusDetail.setText("需授权系统 VPN。只接管已勾选的重点应用，"
                    + "流量只在本机转发，"
                    + "不会发送到第三方 VPN 或代理服务器。");
            dnsButton.setText(Prefs.dnsFilter(this) ? "重新开启本地过滤" : "开启本地过滤");
        }
        dnsBlockedValue.setText(String.valueOf(Prefs.dnsBlockedCount(this)));
        String dnsRecent = Prefs.dnsLastEvent(this);
        dnsLastEvent.setText(dnsRecent.isEmpty() ? "暂无请求阻断记录" : dnsRecent);
        int learnedCount = Prefs.getDnsLearnedRules(this).size();
        int pendingCount = Prefs.getDnsPendingRules(this).size();
        dnsLearnSummary.setText("自动学习：已屏蔽 " + learnedCount
                + " 个，待确认 " + pendingCount + " 个");
        dnsLearnButton.setText(pendingCount > 0
                ? "查看待确认域名 (" + pendingCount + ")"
                : "查看学习记录");
        dnsLearnButton.setEnabled(learnedCount + pendingCount > 0);
        updating = false;
    }

    private boolean accessibilityEnabled() {
        AccessibilityManager manager = (AccessibilityManager)
                getSystemService(ACCESSIBILITY_SERVICE);
        if (manager != null) {
            for (AccessibilityServiceInfo info :
                    manager.getEnabledAccessibilityServiceList(
                            AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
                if (info.getResolveInfo() == null || info.getResolveInfo().serviceInfo == null) {
                    continue;
                }
                if (getPackageName().equals(info.getResolveInfo().serviceInfo.packageName)
                        && AdAccessibilityService.class.getName()
                        .equals(info.getResolveInfo().serviceInfo.name)) {
                    return true;
                }
            }
        }

        String enabled = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) {
            return false;
        }
        String expected = new ComponentName(this, AdAccessibilityService.class)
                .flattenToString();
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabled);
        while (splitter.hasNext()) {
            if (expected.equalsIgnoreCase(splitter.next())) {
                return true;
            }
        }
        return false;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return view;
    }

    private GradientDrawable rounded(int color, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private LinearLayout card(int color) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(6), dp(12), dp(6));
        card.setBackground(rounded(color, 18));
        return card;
    }

    private void add(LinearLayout parent, View child, int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(left, top, right, bottom);
        parent.addView(child, params);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

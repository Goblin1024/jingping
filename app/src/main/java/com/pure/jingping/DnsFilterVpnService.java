package com.pure.jingping;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.system.OsConstants;
import android.util.Log;

import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import engine.Engine;
import engine.Key;
import go.Seq;

public final class DnsFilterVpnService extends VpnService {
    static final String ACTION_STOP = "com.pure.jingping.action.STOP_DNS";
    static final String ACTION_RELOAD = "com.pure.jingping.action.RELOAD_DNS";
    private static final String TAG = "JingPingDns";
    private static final String PROXY_TAG = "JingPingProxy";
    private static final String CHANNEL_ID = "jingping_dns";
    private static final int NOTIFICATION_ID = 2107;
    private static final String VPN_ADDRESS = "10.111.0.2";
    private static final String VPN_ADDRESS_V6 = "fd00:111::2";
    private static final int MTU = 1500;
    private static final String[] FALLBACK_DNS = new String[]{
            "223.5.5.5", "223.6.6.6", "119.29.29.29"
    };

    private static volatile boolean running;
    private static volatile DnsFilterVpnService instance;

    private volatile DnsRules rules;
    private volatile List<InetAddress> upstreamDns = Collections.emptyList();
    private volatile Network underlyingNetwork;
    private boolean underlyingHasIpv6;
    private Socks5Server socksServer;
    private boolean engineStarted;
    private int tunnelFd = -1;
    private volatile boolean stopping;

    private final Socks5Server.Host proxyHost = new Socks5Server.Host() {
        @Override
        public boolean protect(Socket socket) {
            return DnsFilterVpnService.this.protect(socket);
        }

        @Override
        public boolean protect(DatagramSocket socket) {
            return DnsFilterVpnService.this.protect(socket);
        }

        @Override
        public void bind(Socket socket) {
            Network network = underlyingNetwork;
            if (network == null) {
                return;
            }
            try {
                network.bindSocket(socket);
            } catch (Exception e) {
                Log.w(PROXY_TAG, "TCP bind failed", e);
            }
        }

        @Override
        public void bind(DatagramSocket socket) {
            Network network = underlyingNetwork;
            if (network == null) {
                return;
            }
            try {
                network.bindSocket(socket);
            } catch (Exception e) {
                Log.w(PROXY_TAG, "UDP bind failed", e);
            }
        }

        @Override
        public List<InetAddress> dnsServers() {
            return upstreamDns;
        }
    };

    static boolean isRunning() {
        return running;
    }

    static void reloadRules() {
        DnsFilterVpnService service = instance;
        if (service != null) {
            DnsRules updated = DnsRules.load(service);
            service.rules = updated;
            if (service.socksServer != null) {
                service.socksServer.setRules(updated);
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "" : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            Prefs.setDnsFilter(this, false);
            stopFiltering();
            return START_NOT_STICKY;
        }
        if (ACTION_RELOAD.equals(action)) {
            reloadRules();
            return START_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildNotification());
        if (running) {
            return START_STICKY;
        }

        stopping = false;
        Prefs.setDnsFilter(this, true);
        rules = DnsRules.load(this);
        discoverUnderlyingNetwork();

        try {
            socksServer = new Socks5Server(this, proxyHost, rules);
            int proxyPort = socksServer.start();
            if (!startEngine(proxyPort)) {
                throw new IllegalStateException("本地转发引擎启动失败");
            }
            running = true;
            Log.i(TAG, "ad filter started, rules=" + rules.size()
                    + ", dns=" + upstreamDns.size());
            return START_STICKY;
        } catch (Throwable e) {
            Log.e(TAG, "ad filter start failed", e);
            Prefs.setDnsFilter(this, false);
            stopFiltering();
            return START_NOT_STICKY;
        }
    }

    @Override
    public void onRevoke() {
        Prefs.setDnsFilter(this, false);
        stopFiltering();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        stopFiltering();
        instance = null;
        super.onDestroy();
    }

    private boolean startEngine(int proxyPort) throws Exception {
        ParcelFileDescriptor descriptor = null;
        try {
            Builder builder = new Builder();
            builder.setSession("净屏本地广告过滤")
                    .setMtu(MTU)
                    .setBlocking(true)
                    .addAddress(VPN_ADDRESS, 32)
                    .addRoute("0.0.0.0", 0)
                    .allowFamily(OsConstants.AF_INET);
            if (underlyingHasIpv6) {
                builder.addAddress(VPN_ADDRESS_V6, 128)
                        .addRoute("::", 0)
                        .allowFamily(OsConstants.AF_INET6);
            }
            Log.i(TAG, "tunnel ipv6=" + (underlyingHasIpv6 ? "enabled" : "disabled"));
            for (InetAddress dns : upstreamDns) {
                if (dns instanceof Inet4Address) {
                    builder.addDnsServer(dns);
                }
            }
            if (underlyingNetwork != null) {
                builder.setUnderlyingNetworks(new Network[]{underlyingNetwork});
            }
            int allowedApps = 0;
            Set<String> targets = Prefs.getTargets(this);
            for (String pkg : TargetApps.PACKAGES) {
                if (!targets.contains(pkg)) {
                    continue;
                }
                try {
                    getPackageManager().getPackageInfo(pkg, 0);
                    builder.addAllowedApplication(pkg);
                    allowedApps++;
                } catch (Exception e) {
                    Log.w(TAG, "target app unavailable: " + pkg);
                }
            }
            if (allowedApps == 0) {
                try {
                    builder.addAllowedApplication(getPackageName());
                    Log.i(TAG, "no target apps selected; tunnel left idle");
                } catch (Exception e) {
                    Log.w(TAG, "could not scope idle tunnel to own package", e);
                }
            }
            Log.i(TAG, "tunnel apps=" + allowedApps);

            descriptor = builder.establish();
            if (descriptor == null) {
                return false;
            }
            tunnelFd = descriptor.detachFd();
            descriptor = null;

            Seq.setContext(getApplicationContext());
            Key key = new Key();
            key.setMTU(MTU);
            key.setProxy("socks5://127.0.0.1:" + proxyPort);
            key.setDevice("fd://" + tunnelFd);
            key.setLogLevel("info");
            key.setTCPModerateReceiveBuffer(true);
            Engine.insert(key);
            Engine.start();
            engineStarted = true;
            return true;
        } finally {
            if (descriptor != null) {
                try {
                    descriptor.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void discoverUnderlyingNetwork() {
        ConnectivityManager manager = (ConnectivityManager)
                getSystemService(CONNECTIVITY_SERVICE);
        List<Network> networks = new ArrayList<>();
        if (manager != null) {
            try {
                for (Network network : manager.getAllNetworks()) {
                    NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
                    if (capabilities == null
                            || !capabilities.hasCapability(
                            NetworkCapabilities.NET_CAPABILITY_INTERNET)
                            || !capabilities.hasCapability(
                            NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                            || capabilities.hasTransport(
                            NetworkCapabilities.TRANSPORT_VPN)) {
                        continue;
                    }
                    networks.add(network);
                }
                networks.sort((left, right) -> Integer.compare(
                        networkRank(manager.getNetworkCapabilities(left)),
                        networkRank(manager.getNetworkCapabilities(right))));
            } catch (Exception e) {
                Log.w(TAG, "network discovery failed", e);
            }
        }

        underlyingNetwork = networks.isEmpty() ? null : networks.get(0);
        underlyingHasIpv6 = false;
        List<InetAddress> dns = new ArrayList<>();
        if (underlyingNetwork != null && manager != null) {
            LinkProperties properties = manager.getLinkProperties(underlyingNetwork);
            if (properties != null) {
                for (LinkAddress linkAddress : properties.getLinkAddresses()) {
                    InetAddress address = linkAddress.getAddress();
                    if (address instanceof Inet6Address
                            && !address.isLoopbackAddress()
                            && !address.isLinkLocalAddress()) {
                        underlyingHasIpv6 = true;
                    }
                }
                for (InetAddress address : properties.getDnsServers()) {
                    if (address instanceof Inet4Address && !dns.contains(address)) {
                        dns.add(address);
                    }
                }
            }
        }
        for (String fallback : FALLBACK_DNS) {
            try {
                InetAddress address = InetAddress.getByName(fallback);
                if (!dns.contains(address)) {
                    dns.add(address);
                }
            } catch (Exception ignored) {
            }
        }
        upstreamDns = dns;
    }

    private int networkRank(NetworkCapabilities capabilities) {
        if (capabilities == null) {
            return 4;
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return 0;
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            return 1;
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            return 2;
        }
        return 3;
    }

    private void stopFiltering() {
        if (stopping) {
            return;
        }
        stopping = true;
        running = false;
        try {
            if (socksServer != null) {
                socksServer.close();
                socksServer = null;
            }
        } catch (Exception e) {
            Log.w(TAG, "proxy close failed", e);
        }
        boolean engineClosed = false;
        try {
            if (engineStarted) {
                Engine.stop();
                engineClosed = true;
            }
        } catch (Throwable e) {
            Log.w(TAG, "engine stop failed", e);
        }
        engineStarted = false;
        if (tunnelFd >= 0) {
            if (!engineClosed) {
                try {
                    ParcelFileDescriptor.adoptFd(tunnelFd).close();
                } catch (Exception ignored) {
                }
            }
            tunnelFd = -1;
        }
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (Exception ignored) {
        }
        stopSelf();
        Log.i(TAG, "ad filter stopped");
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "本地广告过滤", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("仅转发已选重点应用的流量并在本机阻断广告请求");
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        PendingIntent pending = PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder.setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("净屏本地广告过滤运行中")
                .setContentText("仅接管已选重点应用，所有流量都在本机处理")
                .setContentIntent(pending)
                .setOngoing(true)
                .build();
    }
}

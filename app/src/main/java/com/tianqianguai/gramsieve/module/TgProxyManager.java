package com.tianqianguai.gramsieve.module;

import android.content.Context;

import com.tianqianguai.gramsieve.core.FilterConfig;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TG proxy auto-management (Build #20, user chose auto-add + auto-switch
 * to fastest on 2026-10-25, with a custom expiry time and select-all
 * delete of expired proxies).
 *
 * Verified against Conso 2.0.0 dex (classes9.dex, not assumed from stock
 * Telegram):
 * - SharedConfig$ProxyInfo(address, port, username, password, secret),
 *   fields address/port/username/password/secret/ping/available;
 * - SharedConfig.addProxy(ProxyInfo), deleteProxy(ProxyInfo),
 *   saveProxyList(), loadProxyList(), static fields currentProxy and
 *   proxyList;
 * - ConnectionsManager.setProxySettings(boolean, String, int, String,
 *   String, String) and checkProxy(String, int, String, String, String,
 *   RequestTimeDelegate) where RequestTimeDelegate.run(long) is the ping.
 *
 * Source honesty: no public proxy feed was verified as stable and
 * machine-readable during research, so NO third-party proxy or list URL
 * is hardcoded here. The user configures a source page URL they trust
 * (any page containing t.me/proxy or tg://proxy links) and/or pastes
 * links directly. The manager then fetches, parses, tests, adds and
 * (if enabled) switches - it does not invent proxies.
 *
 * Privacy note (shown in Settings too): a proxy operator sees the
 * device IP and Telegram connection timing/volume, but MTProto content
 * stays encrypted between app and Telegram servers.
 */
final class TgProxyManager {
    private TgProxyManager() {
    }

    interface Callback {
        void onDone(Result result);
    }

    static final class Result {
        FilterConfig updated;
        int fetched = 0;
        int tested = 0;
        int availableCount = 0;
        FilterConfig.ProxyEntry fastest;
        boolean switched = false;
        String error = "";
    }

    private static final Pattern PROXY_LINK = Pattern.compile(
            "(?:https://t\\.me/proxy|tg://proxy)\\?([^\\s\"'<>]+)");
    private static volatile long lastAutoAttemptMs = 0L;

    static boolean isExpired(FilterConfig.ProxyEntry entry, FilterConfig config) {
        if (entry == null) {
            return true;
        }
        if (!entry.available) {
            return true;
        }
        long expiryMs = (config == null ? 24L : Math.max(1, config.proxyExpiryHours)) * 3600_000L;
        return entry.lastTestedMs <= 0L || System.currentTimeMillis() - entry.lastTestedMs > expiryMs;
    }

    static List<FilterConfig.ProxyEntry> parseLinks(String text, String source) {
        Map<String, FilterConfig.ProxyEntry> found = new LinkedHashMap<>();
        if (text == null) {
            return new ArrayList<>();
        }
        Matcher matcher = PROXY_LINK.matcher(text);
        while (matcher.find()) {
            try {
                String query = matcher.group(1);
                String server = null;
                int port = 0;
                String secret = null;
                for (String part : query.split("&")) {
                    int eq = part.indexOf('=');
                    if (eq < 0) {
                        continue;
                    }
                    String key = URLDecoder.decode(part.substring(0, eq), StandardCharsets.UTF_8.name());
                    String value = URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8.name());
                    if ("server".equals(key)) {
                        server = value;
                    } else if ("port".equals(key)) {
                        port = Integer.parseInt(value);
                    } else if ("secret".equals(key)) {
                        secret = value;
                    }
                }
                if (server == null || server.isBlank() || port < 1 || port > 65535
                        || secret == null || secret.isBlank()) {
                    continue;
                }
                FilterConfig.ProxyEntry entry = new FilterConfig.ProxyEntry();
                entry.address = server.trim();
                entry.port = port;
                entry.username = "";
                entry.password = "";
                entry.secret = secret.trim();
                entry.addedMs = System.currentTimeMillis();
                entry.source = source == null ? "" : source;
                found.putIfAbsent(entry.key(), entry);
            } catch (Throwable ignored) {
            }
        }
        return new ArrayList<>(found.values());
    }

    static String fetchText(String urlString) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(20000);
        conn.setRequestProperty("User-Agent", "GramSieve-Conso/1.0");
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new Exception("http " + code);
        }
        InputStream in = conn.getInputStream();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null && sb.length() < 2_000_000) {
                sb.append(line).append('\n');
            }
        }
        conn.disconnect();
        return sb.toString();
    }

    /** Test one proxy. Tries Telegram's own checkProxy first (real
     * Telegram handshake ping); falls back to TCP connect latency so a
     * missing checkProxy path never blocks the whole refresh. */
    static long testProxy(Context context, FilterConfig.ProxyEntry entry) {
        long checkPing = tryCheckProxy(context, entry);
        if (checkPing >= 0) {
            return checkPing;
        }
        long start = System.currentTimeMillis();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(entry.address, entry.port), 5000);
            return System.currentTimeMillis() - start;
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    private static long tryCheckProxy(Context context, FilterConfig.ProxyEntry entry) {
        try {
            if (context == null) {
                return -1L;
            }
            ClassLoader cl = context.getClassLoader();
            Class<?> cmClass = Class.forName("org.telegram.tgnet.ConnectionsManager", false, cl);
            Method getInstance = cmClass.getDeclaredMethod("getInstance", int.class);
            getInstance.setAccessible(true);
            Object cm = getInstance.invoke(null, 0);
            if (cm == null) {
                return -1L;
            }
            Class<?> delegateClass = Class.forName("org.telegram.tgnet.RequestTimeDelegate", false, cl);
            final long[] pingBox = new long[]{-1L};
            final CountDownLatch latch = new CountDownLatch(1);
            Object delegate = Proxy.newProxyInstance(cl, new Class<?>[]{delegateClass},
                    (InvocationHandler) (proxy, method, args) -> {
                        if ("run".equals(method.getName()) && args != null && args.length > 0
                                && args[0] instanceof Long) {
                            pingBox[0] = (Long) args[0];
                            latch.countDown();
                        }
                        return null;
                    });
            Method checkProxy = cmClass.getDeclaredMethod("checkProxy",
                    String.class, int.class, String.class, String.class, String.class, delegateClass);
            checkProxy.setAccessible(true);
            checkProxy.invoke(cm, entry.address, entry.port, entry.username, entry.password, entry.secret, delegate);
            boolean done = latch.await(8, TimeUnit.SECONDS);
            return done ? pingBox[0] : -1L;
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    /** Synchronous refresh. Caller must run this off the UI thread.
     * Fetches the configured source + pasted links, merges with existing
     * entries, tests (max 20, fastest first by previous ping), sorts by
     * ping, and if auto-switch is on, applies the fastest available
     * proxy to the host. Returns the updated config to save. */
    static Result refresh(Context context, FilterConfig config) {
        Result result = new Result();
        FilterConfig updated = config == null ? FilterConfig.createDefault() : config.deepCopy().sanitize();
        List<FilterConfig.ProxyEntry> merged = new ArrayList<>();
        Map<String, FilterConfig.ProxyEntry> byKey = new LinkedHashMap<>();
        if (updated.proxyEntries != null) {
            for (FilterConfig.ProxyEntry entry : updated.proxyEntries) {
                if (entry != null) {
                    byKey.put(entry.key(), entry.deepCopy());
                }
            }
        }
        List<FilterConfig.ProxyEntry> fresh = new ArrayList<>();
        if (updated.proxySourceUrl != null && !updated.proxySourceUrl.isBlank()) {
            try {
                String text = fetchText(updated.proxySourceUrl.trim());
                fresh.addAll(parseLinks(text, updated.proxySourceUrl.trim()));
            } catch (Throwable throwable) {
                result.error = throwable.getClass().getSimpleName();
            }
        }
        if (updated.proxyPastedLinks != null && !updated.proxyPastedLinks.isBlank()) {
            fresh.addAll(parseLinks(updated.proxyPastedLinks, "pasted"));
        }
        result.fetched = fresh.size();
        for (FilterConfig.ProxyEntry entry : fresh) {
            FilterConfig.ProxyEntry existing = byKey.get(entry.key());
            if (existing != null) {
                entry.pingMs = existing.pingMs;
                entry.lastTestedMs = existing.lastTestedMs;
                entry.available = existing.available;
                entry.addedMs = existing.addedMs;
            }
            byKey.put(entry.key(), entry);
        }
        merged.addAll(byKey.values());
        // Test at most 20: previously fastest first, then untested, so a
        // refresh does not spend minutes on a huge stale list.
        merged.sort((a, b) -> {
            long pa = a.pingMs < 0 ? Long.MAX_VALUE : a.pingMs;
            long pb = b.pingMs < 0 ? Long.MAX_VALUE : b.pingMs;
            return Long.compare(pa, pb);
        });
        int toTest = Math.min(20, merged.size());
        for (int i = 0; i < toTest; i++) {
            FilterConfig.ProxyEntry entry = merged.get(i);
            long ping = testProxy(context, entry);
            entry.lastTestedMs = System.currentTimeMillis();
            if (ping >= 0) {
                entry.available = true;
                entry.pingMs = ping;
            } else {
                entry.available = false;
                entry.pingMs = -1L;
            }
            result.tested++;
        }
        List<FilterConfig.ProxyEntry> available = new ArrayList<>();
        for (FilterConfig.ProxyEntry entry : merged) {
            if (entry.available && entry.pingMs >= 0) {
                available.add(entry);
            }
        }
        available.sort((a, b) -> Long.compare(a.pingMs, b.pingMs));
        result.availableCount = available.size();
        if (!available.isEmpty()) {
            result.fastest = available.get(0);
        }
        updated.proxyEntries = merged;
        updated.proxyLastRefreshMs = System.currentTimeMillis();
        updated.updatedAtEpochMs = System.currentTimeMillis();
        result.updated = updated;
        if (updated.proxyAutoAdd && updated.proxyAutoSwitch && result.fastest != null) {
            result.switched = applyToHost(context, result.fastest);
        }
        return result;
    }

    /** Write one proxy into Conso's own proxy list and make it current.
     * All reflection, all failures are silent false so a host API change
     * never crashes the module. */
    static boolean applyToHost(Context context, FilterConfig.ProxyEntry entry) {
        try {
            if (context == null || entry == null) {
                return false;
            }
            ClassLoader cl = context.getClassLoader();
            Class<?> sharedConfig = Class.forName("org.telegram.messenger.SharedConfig", false, cl);
            Class<?> proxyInfoClass = Class.forName("org.telegram.messenger.SharedConfig$ProxyInfo", false, cl);
            Method load = sharedConfig.getDeclaredMethod("loadProxyList");
            load.setAccessible(true);
            load.invoke(null);
            java.lang.reflect.Constructor<?> ctor = proxyInfoClass.getDeclaredConstructor(
                    String.class, int.class, String.class, String.class, String.class);
            ctor.setAccessible(true);
            Object info = ctor.newInstance(entry.address, entry.port,
                    entry.username == null ? "" : entry.username,
                    entry.password == null ? "" : entry.password,
                    entry.secret);
            Method addProxy = sharedConfig.getDeclaredMethod("addProxy", proxyInfoClass);
            addProxy.setAccessible(true);
            Object added = addProxy.invoke(null, info);
            if (added != null) {
                info = added;
            }
            java.lang.reflect.Field currentField = sharedConfig.getDeclaredField("currentProxy");
            currentField.setAccessible(true);
            currentField.set(null, info);
            Method save = sharedConfig.getDeclaredMethod("saveProxyList");
            save.setAccessible(true);
            save.invoke(null);
            Class<?> cmClass = Class.forName("org.telegram.tgnet.ConnectionsManager", false, cl);
            Method getInstance = cmClass.getDeclaredMethod("getInstance", int.class);
            getInstance.setAccessible(true);
            Object cm = getInstance.invoke(null, 0);
            if (cm != null) {
                Method setProxy = cmClass.getDeclaredMethod("setProxySettings",
                        boolean.class, String.class, int.class, String.class, String.class, String.class);
                setProxy.setAccessible(true);
                setProxy.invoke(cm, true, entry.address, entry.port,
                        entry.username == null ? "" : entry.username,
                        entry.password == null ? "" : entry.password,
                        entry.secret);
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean deleteFromHost(Context context, FilterConfig.ProxyEntry entry) {
        try {
            if (context == null || entry == null) {
                return false;
            }
            ClassLoader cl = context.getClassLoader();
            Class<?> sharedConfig = Class.forName("org.telegram.messenger.SharedConfig", false, cl);
            Class<?> proxyInfoClass = Class.forName("org.telegram.messenger.SharedConfig$ProxyInfo", false, cl);
            Method load = sharedConfig.getDeclaredMethod("loadProxyList");
            load.setAccessible(true);
            load.invoke(null);
            java.lang.reflect.Field listField = sharedConfig.getDeclaredField("proxyList");
            listField.setAccessible(true);
            Object listObj = listField.get(null);
            if (!(listObj instanceof List)) {
                return false;
            }
            List<?> hostList = (List<?>) listObj;
            Object toDelete = null;
            for (Object info : hostList) {
                try {
                    java.lang.reflect.Field addressField = proxyInfoClass.getDeclaredField("address");
                    addressField.setAccessible(true);
                    java.lang.reflect.Field portField = proxyInfoClass.getDeclaredField("port");
                    portField.setAccessible(true);
                    String address = (String) addressField.get(info);
                    int port = (Integer) portField.get(info);
                    if (entry.address.equals(address) && entry.port == port) {
                        toDelete = info;
                        break;
                    }
                } catch (Throwable ignored) {
                }
            }
            if (toDelete == null) {
                return false;
            }
            Method delete = sharedConfig.getDeclaredMethod("deleteProxy", proxyInfoClass);
            delete.setAccessible(true);
            delete.invoke(null, toDelete);
            Method save = sharedConfig.getDeclaredMethod("saveProxyList");
            save.setAccessible(true);
            save.invoke(null);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Background auto-refresh guard for DialogsActivity createView:
     * only when auto-add is on and the refresh interval has passed, and
     * at most once per process-hour (static guard) so opening the chat
     * list does not spam the source. Caller saves the returned config. */
    static void maybeAutoRefresh(Context context, FilterConfig config, Callback callback) {
        try {
            if (config == null || !config.proxyAutoAdd) {
                return;
            }
            long intervalMs = Math.max(1, config.proxyRefreshHours) * 3600_000L;
            long now = System.currentTimeMillis();
            if (now - config.proxyLastRefreshMs < intervalMs) {
                return;
            }
            if (now - lastAutoAttemptMs < 3600_000L) {
                return;
            }
            lastAutoAttemptMs = now;
            new Thread(() -> {
                try {
                    Result result = refresh(context, config);
                    if (callback != null) {
                        callback.onDone(result);
                    }
                } catch (Throwable ignored) {
                }
            }).start();
        } catch (Throwable ignored) {
        }
    }
}

package com.tianqianguai.gramsieve.module;

import android.content.Context;

import com.tianqianguai.gramsieve.config.ModuleLogger;
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
        int folderFetched = 0;
        String folderDiag = "";
        int tested = 0;
        int availableCount = 0;
        int imported = 0;
        int addedToHost = 0;
        FilterConfig.ProxyEntry fastest;
        boolean switched = false;
        String error = "";
    }

    private static final Pattern PROXY_LINK = Pattern.compile(
            "(?:https://t\\.me/proxy|tg://proxy)\\?([^\\s\"'<>]+)");
    private static volatile long lastAutoAttemptMs = 0L;
    static volatile String lastFolderScanDiag = "";

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

    private static Object proxyFieldValue(Object obj, String name) {
        if (obj == null) {
            return null;
        }
        try {
            java.lang.reflect.Field field = obj.getClass().getField(name);
            return field.get(obj);
        } catch (Throwable ignored) {
        }
        try {
            java.lang.reflect.Field field = obj.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(obj);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Read Conso's own proxy list (Build #23 fix for "already-added
     * proxies not recognised"): the module list and the host list were
     * separate, so proxies added in Conso settings or by tapping a
     * t.me/proxy link in Telegram were invisible to the module. Host
     * entries are returned with source "host" and the host's last ping /
     * availability when available, so refresh can test them and will not
     * duplicate them. Never throws. */
    static List<FilterConfig.ProxyEntry> importHostProxies(Context context) {
        List<FilterConfig.ProxyEntry> out = new ArrayList<>();
        if (context == null) {
            return out;
        }
        try {
            ClassLoader cl = context.getClassLoader();
            Class<?> sharedConfig = Class.forName("org.telegram.messenger.SharedConfig", false, cl);
            java.lang.reflect.Method load = sharedConfig.getDeclaredMethod("loadProxyList");
            load.setAccessible(true);
            load.invoke(null);
            java.lang.reflect.Field listField = sharedConfig.getDeclaredField("proxyList");
            listField.setAccessible(true);
            Object listObj = listField.get(null);
            if (!(listObj instanceof List)) {
                return out;
            }
            long now = System.currentTimeMillis();
            for (Object info : (List<?>) listObj) {
                try {
                    Object addressObj = proxyFieldValue(info, "address");
                    Object portObj = proxyFieldValue(info, "port");
                    Object secretObj = proxyFieldValue(info, "secret");
                    if (!(addressObj instanceof String) || ((String) addressObj).isBlank()
                            || !(portObj instanceof Number) || !(secretObj instanceof String)
                            || ((String) secretObj).isBlank()) {
                        continue;
                    }
                    FilterConfig.ProxyEntry entry = new FilterConfig.ProxyEntry();
                    entry.address = ((String) addressObj).trim();
                    entry.port = ((Number) portObj).intValue();
                    Object usernameObj = proxyFieldValue(info, "username");
                    Object passwordObj = proxyFieldValue(info, "password");
                    entry.username = usernameObj instanceof String ? (String) usernameObj : "";
                    entry.password = passwordObj instanceof String ? (String) passwordObj : "";
                    entry.secret = ((String) secretObj).trim();
                    Object pingObj = proxyFieldValue(info, "ping");
                    entry.pingMs = pingObj instanceof Number ? ((Number) pingObj).longValue() : -1L;
                    Object availableObj = proxyFieldValue(info, "available");
                    entry.available = availableObj instanceof Boolean ? (Boolean) availableObj : entry.pingMs >= 0;
                    entry.lastTestedMs = entry.available ? now : 0L;
                    entry.addedMs = now;
                    entry.source = "host";
                    out.add(entry);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** Scan recent messages from chats in ONE folder (Build #23, user
     * specified "节点" on 2026-10-25) and extract t.me/proxy links.
     * Uses the same isolated getHistory adapter as BackgroundMessageLoader
     * (never MessagesController.loadMessages, so Telegram pagination state
     * is untouched). At most 20 chats, 50 messages each, 30s total wait;
     * per-chat failures are isolated, partial results are still returned.
     * Returns parsed entries with source "folder:<name>". Never throws. */
    static List<FilterConfig.ProxyEntry> scanFolderChats(Context context, String folderName) {
        List<FilterConfig.ProxyEntry> out = new ArrayList<>();
        if (context == null || folderName == null || folderName.isBlank()) {
            return out;
        }
        try {
            TelegramHistoryApiAdapter.ProbeResult probe =
                    TelegramHistoryApiAdapter.probe(context.getClassLoader());
            if (probe == null || !probe.isSupported() || probe.adapter == null) {
                lastFolderScanDiag = "历史接口不支持";
                return out;
            }
            TelegramHistoryApiAdapter adapter = probe.adapter;
            // Build #28: use the selected account, not hardcoded 0.
            // If the user's selected account is not 0, account 0's
            // folders do not contain 节点, so the scan silently finds
            // nothing even though all proxy channels are in 节点.
            int selectedAccount = 0;
            try {
                Class<?> userConfigClass = context.getClassLoader().loadClass("org.telegram.messenger.UserConfig");
                Object selected = Reflect.staticField(userConfigClass, "selectedAccount");
                if (selected instanceof Number) {
                    selectedAccount = ((Number) selected).intValue();
                }
            } catch (Throwable ignored) {
            }
            Object controller = adapter.getController(selectedAccount);
            if (controller == null) {
                lastFolderScanDiag = "控制器为空";
                return out;
            }
            Object filtersObj = Reflect.invokeIfExists(controller, "getDialogFilters", new Class<?>[0]);
            if (!(filtersObj instanceof List)) {
                lastFolderScanDiag = "文件夹列表为空";
                return out;
            }
            List<Long> dialogIds = null;
            Object matchedFilter = null;
            Object matchedDialogsRaw = null;
            for (Object filter : (List<?>) filtersObj) {
                String name = Reflect.asString(Reflect.field(filter, "name"));
                if (name != null && name.trim().equals(folderName.trim())) {
                    matchedFilter = filter;
                    matchedDialogsRaw = Reflect.field(filter, "dialogs");
                    // Build #36: Conso 2.0.0 membership lives in
                    // alwaysShow minus neverShow (2026-10-26 08:20 log:
                    // 节点 dialogs=0, alwaysShow=38). Fall back to
                    // dialogs only when alwaysShow is absent.
                    dialogIds = new ArrayList<>();
                    java.util.Set<Long> excluded = new java.util.HashSet<>();
                    Object neverShow = Reflect.field(filter, "neverShow");
                    if (neverShow instanceof List) {
                        for (Object idObj : (List<?>) neverShow) {
                            long id = Reflect.asLong(idObj, 0L);
                            if (id != 0L) {
                                excluded.add(id);
                            }
                        }
                    }
                    Object alwaysShow = Reflect.field(filter, "alwaysShow");
                    Object members = alwaysShow instanceof List ? alwaysShow : matchedDialogsRaw;
                    if (members instanceof List) {
                        for (Object idObj : (List<?>) members) {
                            long id = Reflect.asLong(idObj, 0L);
                            if (id != 0L && !excluded.contains(id)) {
                                dialogIds.add(id);
                            }
                        }
                    }
                    break;
                }
            }
            // Build #30: when the folder is found but yields no chats,
            // dump field shapes only (counts and types, never dialog
            // ids or other user data). The 23:01 log proved the 节点
            // filter was found by name yet its dialogs list read as
            // empty, while the UI tab shows chats, so the raw list
            // size vs the converted size and the element type are
            // exactly the facts needed to find where Conso really
            // exposes folder chats.
            if (matchedFilter != null && (dialogIds == null || dialogIds.isEmpty())) {
                try {
                    StringBuilder dump = new StringBuilder();
                    dump.append("folder scan fields: account=").append(selectedAccount);
                    dump.append(" target=").append(folderName.trim());
                    dump.append(" rawDialogs=").append(matchedDialogsRaw instanceof List
                            ? String.valueOf(((List<?>) matchedDialogsRaw).size())
                            : (matchedDialogsRaw == null ? "null" : matchedDialogsRaw.getClass().getSimpleName()));
                    Object dumpAlways = Reflect.field(matchedFilter, "alwaysShow");
                    Object dumpNever = Reflect.field(matchedFilter, "neverShow");
                    dump.append(" alwaysShow=").append(dumpAlways instanceof List
                            ? String.valueOf(((List<?>) dumpAlways).size()) : "null");
                    dump.append(" neverShow=").append(dumpNever instanceof List
                            ? String.valueOf(((List<?>) dumpNever).size()) : "null");
                    dump.append(" converted=").append(dialogIds == null ? "null" : String.valueOf(dialogIds.size()));
                    dump.append(" filters=[");
                    for (Object f : (List<?>) filtersObj) {
                        String n = Reflect.asString(Reflect.field(f, "name"));
                        Object d = Reflect.field(f, "dialogs");
                        dump.append(n == null ? "?" : n.trim()).append(":dialogs=")
                                .append(d instanceof List ? String.valueOf(((List<?>) d).size())
                                        : (d == null ? "null" : d.getClass().getSimpleName())).append(";");
                    }
                    dump.append("] matchedFields=[");
                    for (java.lang.reflect.Field field : matchedFilter.getClass().getDeclaredFields()) {
                        try {
                            field.setAccessible(true);
                            Object value = field.get(matchedFilter);
                            dump.append(field.getName()).append(":");
                            if (value instanceof List) {
                                List<?> list = (List<?>) value;
                                dump.append("List(size=").append(list.size());
                                if (!list.isEmpty() && list.get(0) != null) {
                                    dump.append(",first=").append(list.get(0).getClass().getSimpleName());
                                }
                                dump.append(")");
                            } else {
                                dump.append(value == null ? "null" : value.getClass().getSimpleName());
                            }
                            dump.append(";");
                        } catch (Throwable ignored) {
                            dump.append(field.getName()).append(":?;");
                        }
                    }
                    dump.append("]");
                    ModuleLogger.hook("TgProxy", dump.toString());
                } catch (Throwable ignored) {
                }
            }
            if (dialogIds == null) {
                lastFolderScanDiag = "文件夹 " + folderName.trim() + " 未找到";
                return out;
            }
            if (dialogIds.isEmpty()) {
                lastFolderScanDiag = "文件夹 " + folderName.trim() + " 里没有聊天";
                return out;
            }
            // Build #25: sort folder chats by last message date, newest
            // first, so an active proxy channel is scanned even when the
            // folder has more chats than the cap. Cap raised 20 -> 30.
            try {
                final Object sortController = controller;
                dialogIds.sort((a, b) -> {
                    long dateA = 0L;
                    long dateB = 0L;
                    try {
                        Object dialogA = Reflect.invokeIfExists(sortController, "getDialog",
                                new Class<?>[]{long.class}, a);
                        Object dateObjA = dialogA == null ? null : Reflect.field(dialogA, "lastMessageDate");
                        if (dateObjA instanceof Number) dateA = ((Number) dateObjA).longValue();
                    } catch (Throwable ignored) {
                    }
                    try {
                        Object dialogB = Reflect.invokeIfExists(sortController, "getDialog",
                                new Class<?>[]{long.class}, b);
                        Object dateObjB = dialogB == null ? null : Reflect.field(dialogB, "lastMessageDate");
                        if (dateObjB instanceof Number) dateB = ((Number) dateObjB).longValue();
                    } catch (Throwable ignored) {
                    }
                    return Long.compare(dateB, dateA);
                });
            } catch (Throwable ignored) {
            }
            if (dialogIds.size() > 30) {
                dialogIds = new ArrayList<>(dialogIds.subList(0, 30));
            }
            Object connectionsManager = adapter.getConnectionsManager(controller);
            if (connectionsManager == null) {
                return out;
            }
            StringBuilder allText = new StringBuilder();
            final int[] historyOk = new int[]{0};
            final int[] messageCount = new int[]{0};
            final int totalChats = dialogIds.size();
            java.util.concurrent.CountDownLatch latch =
                    new java.util.concurrent.CountDownLatch(dialogIds.size());
            for (long dialogId : dialogIds) {
                try {
                    Object peer = Reflect.invokeIfExists(controller, "getInputPeer",
                            new Class<?>[]{long.class}, dialogId);
                    if (peer == null) {
                        peer = Reflect.invokeIfExists(controller, "getInputPeer",
                                new Class<?>[]{Long.class}, Long.valueOf(dialogId));
                    }
                    if (peer == null) {
                        latch.countDown();
                        continue;
                    }
                    Object request = adapter.newHistoryRequest(peer, 50);
                    Object delegate = adapter.newRequestDelegate((proxy, method, args) -> {
                        try {
                            if ("run".equals(method.getName())) {
                                try {
                                Object response = args != null && args.length > 0 ? args[0] : null;
                                Object error = args != null && args.length > 1 ? args[1] : null;
                                if (error == null && response != null) {
                                    synchronized (allText) {
                                        historyOk[0]++;
                                    }
                                    Object messages = Reflect.field(response, "messages");
                                    if (messages instanceof List) {
                                        synchronized (allText) {
                                            messageCount[0] += ((List<?>) messages).size();
                                        }
                                        for (Object msg : (List<?>) messages) {
                                            try {
                                                Object text = Reflect.field(msg, "message");
                                                if (text instanceof String && !((String) text).isBlank()) {
                                                    synchronized (allText) {
                                                        allText.append((String) text).append("\n");
                                                    }
                                                }
                                                // Build #25: proxy links are often NOT in
                                                // the visible text - they hide in a text
                                                // link entity ("点击获取") or an inline
                                                // button URL. Extract both, otherwise a
                                                // channel full of proxy buttons scans as 0.
                                                try {
                                                    Object entities = Reflect.field(msg, "entities");
                                                    if (entities instanceof List) {
                                                        for (Object entity : (List<?>) entities) {
                                                            Object url = Reflect.field(entity, "url");
                                                            if (url instanceof String && !((String) url).isBlank()) {
                                                                synchronized (allText) {
                                                                    allText.append((String) url).append("\n");
                                                                }
                                                            }
                                                        }
                                                    }
                                                } catch (Throwable ignored) {
                                                }
                                                try {
                                                    Object markup = Reflect.field(msg, "reply_markup");
                                                    if (markup != null) {
                                                        Object rows = Reflect.field(markup, "rows");
                                                        if (rows instanceof List) {
                                                            for (Object row : (List<?>) rows) {
                                                                Object buttons = Reflect.field(row, "buttons");
                                                                if (buttons instanceof List) {
                                                                    for (Object button : (List<?>) buttons) {
                                                                        Object url = Reflect.field(button, "url");
                                                                        if (url instanceof String && !((String) url).isBlank()) {
                                                                            synchronized (allText) {
                                                                                allText.append((String) url).append("\n");
                                                                            }
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                } catch (Throwable ignored) {
                                                }
                                                Object media = Reflect.field(msg, "media");
                                                if (media != null) {
                                                    Object caption = Reflect.field(media, "caption");
                                                    if (caption instanceof String && !((String) caption).isBlank()) {
                                                        synchronized (allText) {
                                                            allText.append((String) caption).append("\n");
                                                        }
                                                    }
                                                }
                                            } catch (Throwable ignored) {
                                            }
                                        }
                                    }
                                }
                                } finally {
                                    latch.countDown();
                                }
                            }
                            if ("toString".equals(method.getName())) {
                                return "FolderProxyScanDelegate";
                            }
                            if ("hashCode".equals(method.getName())) {
                                return System.identityHashCode(proxy);
                            }
                            if ("equals".equals(method.getName())) {
                                return proxy == (args != null && args.length > 0 ? args[0] : null);
                            }
                        } catch (Throwable ignored) {
                        }
                        return null;
                    });
                    int requestId = adapter.sendRequest(connectionsManager, request, delegate);
                    if (requestId <= 0) {
                        latch.countDown();
                    }
                } catch (Throwable ignored) {
                    latch.countDown();
                }
            }
            try {
                latch.await(45, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Throwable ignored) {
            }
            lastFolderScanDiag = "文件夹 " + folderName.trim() + "：聊天 " + totalChats + " 个，历史成功 "
                    + historyOk[0] + " 个，消息 " + messageCount[0] + " 条，文本 " + allText.length() + " 字";
            if (allText.length() > 0) {
                out.addAll(parseLinks(allText.toString(), "folder:" + folderName.trim()));
            }
        } catch (Throwable ignored) {
        }
        return out;
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
        // Build #23: first merge Conso's own already-added proxies so
        // they are recognised, tested and not duplicated, then add the
        // folder scan (only the user-specified folder, default "节点").
        for (FilterConfig.ProxyEntry hostEntry : importHostProxies(context)) {
            FilterConfig.ProxyEntry existing = byKey.get(hostEntry.key());
            if (existing == null) {
                // Build #24: same address+port with a different secret
                // format is the SAME proxy (host secret may carry a
                // prefix or different case). The host entry is
                // authoritative because it is the one actually in use.
                FilterConfig.ProxyEntry sameHost = null;
                for (FilterConfig.ProxyEntry candidate : byKey.values()) {
                    if (candidate != null && candidate.address != null
                            && candidate.address.equalsIgnoreCase(hostEntry.address)
                            && candidate.port == hostEntry.port) {
                        sameHost = candidate;
                        break;
                    }
                }
                if (sameHost != null) {
                    sameHost.secret = hostEntry.secret;
                    if (sameHost.pingMs < 0 && hostEntry.pingMs >= 0) {
                        sameHost.pingMs = hostEntry.pingMs;
                        sameHost.available = hostEntry.available;
                        sameHost.lastTestedMs = hostEntry.lastTestedMs;
                    }
                    if (sameHost.source == null || sameHost.source.isEmpty()) {
                        sameHost.source = "host";
                    }
                } else {
                    byKey.put(hostEntry.key(), hostEntry);
                }
            } else if (existing.source == null || existing.source.isEmpty()) {
                existing.source = "host";
            }
        }
        List<FilterConfig.ProxyEntry> fresh = new ArrayList<>();
        if (updated.proxyFolderScan && updated.proxyFolderName != null && !updated.proxyFolderName.isBlank()) {
            try {
                List<FilterConfig.ProxyEntry> folderEntries = scanFolderChats(context, updated.proxyFolderName.trim());
                result.folderFetched = folderEntries.size();
                result.folderDiag = lastFolderScanDiag == null ? "" : lastFolderScanDiag;
                // Build #29: persist the diag to the log file, so a
                // truncated toast cannot hide it anymore.
                try {
                    ModuleLogger.hook("TgProxy", "folder scan: " + result.folderDiag
                            + ", fetched=" + result.folderFetched);
                } catch (Throwable ignored) {
                }
                fresh.addAll(folderEntries);
            } catch (Throwable ignored) {
            }
        }
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
        // Build #39: keys scanned this round that were not already in
        // the list (and not already in Conso itself) are new imports.
        // They are only kept if this refresh actually tests them good;
        // untested or failed new entries stay out and will be
        // re-scanned on a later refresh, instead of filling the list
        // as "expired". Host entries and previously imported entries
        // are exempt: they stay, marked by their test result.
        java.util.Set<String> newFreshKeys = new java.util.HashSet<>();
        for (FilterConfig.ProxyEntry entry : fresh) {
            if (entry != null && !byKey.containsKey(entry.key())) {
                newFreshKeys.add(entry.key());
            }
        }
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
        // Test at most 20: fresh imports first (their import is being
        // decided now), then previously fastest, then untested old
        // ones, so a refresh does not spend minutes on a huge list.
        merged.sort((a, b) -> {
            boolean aNew = newFreshKeys.contains(a.key());
            boolean bNew = newFreshKeys.contains(b.key());
            if (aNew != bNew) {
                return aNew ? -1 : 1;
            }
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
        // Build #39: drop new imports that did not test good.
        java.util.Iterator<FilterConfig.ProxyEntry> dropIt = merged.iterator();
        while (dropIt.hasNext()) {
            FilterConfig.ProxyEntry entry = dropIt.next();
            if (newFreshKeys.contains(entry.key())) {
                if (entry.available && entry.pingMs >= 0) {
                    result.imported++;
                } else {
                    dropIt.remove();
                }
            }
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
        // Build #41: until now only the single fastest proxy ever
        // reached Conso's own proxy list (applyToHost); every other
        // scanned proxy lived only in the module config, so Conso's
        // proxy management never showed them. When auto-add is on,
        // sync every tested-good entry into the host list (deduped
        // by address+port, host version wins). This never changes
        // the currently used proxy; switching to fastest stays a
        // separate step below, gated by both switches as before.
        if (updated.proxyAutoAdd) {
            result.addedToHost = syncAvailableToHost(context, available);
        }
        if (updated.proxyAutoAdd && updated.proxyAutoSwitch && result.fastest != null) {
            result.switched = applyToHost(context, result.fastest);
        }
        return result;
    }

    /** Add every tested-good entry to Conso's own proxy list, so
     * they show up in Conso's proxy management, not just in the
     * module's list. Returns how many were newly added. Never
     * touches currentProxy or the live connection: picking the
     * fastest stays with applyToHost. All reflection, all failures
     * are silent 0 so a host API change never crashes refresh. */
    static int syncAvailableToHost(Context context, List<FilterConfig.ProxyEntry> available) {
        if (context == null || available == null || available.isEmpty()) {
            return 0;
        }
        try {
            ClassLoader cl = context.getClassLoader();
            Class<?> sharedConfig = Class.forName("org.telegram.messenger.SharedConfig", false, cl);
            Class<?> proxyInfoClass = Class.forName("org.telegram.messenger.SharedConfig$ProxyInfo", false, cl);
            Method load = sharedConfig.getDeclaredMethod("loadProxyList");
            load.setAccessible(true);
            load.invoke(null);
            java.util.Set<String> hostKeys = new java.util.HashSet<>();
            for (FilterConfig.ProxyEntry hostEntry : importHostProxies(context)) {
                if (hostEntry != null && hostEntry.address != null) {
                    hostKeys.add(hostEntry.address.toLowerCase(java.util.Locale.ROOT) + ":" + hostEntry.port);
                }
            }
            Method addProxy = sharedConfig.getDeclaredMethod("addProxy", proxyInfoClass);
            addProxy.setAccessible(true);
            java.lang.reflect.Constructor<?> ctor = proxyInfoClass.getDeclaredConstructor(
                    String.class, int.class, String.class, String.class, String.class);
            ctor.setAccessible(true);
            int added = 0;
            for (FilterConfig.ProxyEntry entry : available) {
                if (entry == null || entry.address == null) {
                    continue;
                }
                String key = entry.address.toLowerCase(java.util.Locale.ROOT) + ":" + entry.port;
                if (hostKeys.contains(key)) {
                    continue;
                }
                Object info = ctor.newInstance(entry.address, entry.port,
                        entry.username == null ? "" : entry.username,
                        entry.password == null ? "" : entry.password,
                        entry.secret);
                addProxy.invoke(null, info);
                hostKeys.add(key);
                added++;
            }
            if (added > 0) {
                Method save = sharedConfig.getDeclaredMethod("saveProxyList");
                save.setAccessible(true);
                save.invoke(null);
            }
            return added;
        } catch (Throwable ignored) {
            return 0;
        }
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

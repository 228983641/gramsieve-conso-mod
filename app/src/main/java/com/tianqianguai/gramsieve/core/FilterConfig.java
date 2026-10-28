package com.tianqianguai.gramsieve.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class FilterConfig {
    public static final int CURRENT_SCHEMA_VERSION = 2;
    public static final String APP_LANGUAGE_SYSTEM = "";
    public static final String APP_LANGUAGE_ENGLISH = "en";
    public static final String APP_LANGUAGE_SIMPLIFIED_CHINESE = "zh-CN";

    public int schemaVersion = CURRENT_SCHEMA_VERSION;
    public boolean enabled = true;
    public boolean debugLogging = false;
    public Action action = Action.HIDE;
    // Conso adaptation: force Simplified Chinese UI by default.
    public String appLanguageTag = APP_LANGUAGE_SIMPLIFIED_CHINESE;
    // Enhancement features merged from NoAdsTelegram / TeleVip (Build #5).
    // All default OFF: the user enables them one by one in Settings, so a
    // Conso behaviour change is never a surprise after an update.
    public boolean disableSponsoredMessages = false;
    public boolean disableVideoAds = false;
    public boolean enableForwardSave = false;
    public boolean enableSaveStories = false;
    public boolean preventMessageDelete = false;
    public boolean boostDownloadSpeed = false;
    public boolean localPremium = false;
    public boolean autoTranslateToChinese = false;
    // Dialog list features (Build #14, user-selected low-risk route).
    // All default OFF. classifyFilter: 0=全部, 1=个人, 2=群聊, 3=频道.
    public boolean highlightPinnedDialogs = false;
    public boolean showClassifyBar = false;
    public int classifyFilter = 0;
    // Classify per category (Build #23, user requested 2026-10-25:
    // the All/Personal/Groups/Channels bar must work under All, Archive
    // and each user folder separately). Key is the category name as the
    // user sees it ("所有" for the main list, "归档" for Archive, or a
    // folder name such as "节点"); value is 0=全部, 1=个人, 2=群聊, 3=频道.
    // classifyFilter above remains as the fallback for categories with
    // no entry yet, so old configs keep working.
    public Map<String, Integer> classifyFiltersByCategory = new LinkedHashMap<>();
    // Pluggable AI analysis backend (Build #19). Default is local only.
    // aiAnalysisMode: 0=local rules only, 1=LLM (user key) with local fallback.
    // aiApiKey is stored ONLY on device in module private config, never in
    // the repo, never logged, and only sent to aiApiBaseUrl when the user
    // taps smart analysis (never for automatic per-message filtering).
    public int aiAnalysisMode = 0;
    public String aiApiBaseUrl = "https://api.openai.com/v1";
    public String aiApiModel = "gpt-4o-mini";
    public String aiApiKey = "";
    public boolean aiFallbackToLocal = true;
    // TG proxy auto-management (Build #20, user chose auto-add + auto-switch
    // to fastest on 2026-10-25). Defaults are OFF in code so an upgrade never
    // changes network routing by surprise; the user turns the switches on in
    // Settings, or the panel's first-open defaults (see HostConfigPanel) do.
    // proxyExpiryHours is the user-custom time: an entry whose last
    // successful test is older than this is "expired" and can be
    // select-all-deleted in the management dialog.
    public boolean proxyAutoAdd = false;
    public boolean proxyAutoSwitch = false;
    public int proxyRefreshHours = 6;
    // Refresh interval in minutes. -1 means "never set": sanitize()
    // migrates the old hours value (hours * 60) once, so an existing
    // user's interval does not silently change when the unit changes.
    public int proxyRefreshMinutes = -1;
    public int proxyExpiryHours = 24;
    public String proxySourceUrl = "";
    public String proxyPastedLinks = "";
    // Proxy folder scan (Build #23, user chose 2026-10-25: scan ONLY one
    // specified folder, confirmed 2026-10-25 as "节点"). Refresh reads
    // recent messages from chats in this folder and extracts t.me/proxy
    // links, in addition to the web URL / pasted links above. Blank means
    // do not scan any folder. Matched by folder NAME (not id) so it
    // survives folder recreation.
    public String proxyFolderName = "节点";
    public boolean proxyFolderScan = true;
    public long proxyLastRefreshMs = 0L;
    public boolean proxyConfigured = false;
    public List<ProxyEntry> proxyEntries = new ArrayList<>();
    // Folder-archive sync (Build #22, user requested 2026-10-25:
    // chats in a folder are auto-archived, chats removed from ALL folders
    // are auto-unarchived). Default ON because the user asked for this
    // behaviour; turn it off in the Chat List card to stop it.
    // autoArchivedDialogIds records ONLY dialogs this module archived, so
    // a chat the user archived manually is never unarchived by us, and a
    // chat the user unarchived manually is not re-archived on restart
    // (see FolderArchiveSync logic in TelegramHookInstaller).
    public boolean autoArchiveFolderChats = true;
    public List<Long> autoArchivedDialogIds = new ArrayList<>();
    // Dialogs the user manually unarchived after we auto-archived them.
    // They are never re-archived automatically (across restarts too);
    // removing them from all folders clears the opt-out, so re-adding
    // to a folder archives them again.
    public List<Long> folderArchiveOptOutDialogIds = new ArrayList<>();
    public List<RuleSpec> globalRules = new ArrayList<>();
    public List<RuleSpec> globalExclusions = new ArrayList<>();
    public Map<String, ChatRuleSet> chatRules = new LinkedHashMap<>();
    public long updatedAtEpochMs = System.currentTimeMillis();

    public static FilterConfig createDefault() {
        return new FilterConfig();
    }

    public static String chatKey(long dialogId) {
        return Long.toString(dialogId);
    }

    public ChatRuleSet getChatRuleSet(long dialogId) {
        return chatRules.get(chatKey(dialogId));
    }

    public ChatRuleSet getOrCreateChatRuleSet(long dialogId) {
        String key = chatKey(dialogId);
        ChatRuleSet existing = chatRules.get(key);
        if (existing != null) {
            return existing;
        }
        ChatRuleSet created = new ChatRuleSet();
        chatRules.put(key, created);
        return created;
    }

    public FilterConfig sanitize() {
        if (action == null) {
            action = Action.HIDE;
        }
        appLanguageTag = normalizeAppLanguageTag(appLanguageTag);
        if (globalRules == null) {
            globalRules = new ArrayList<>();
        }
        if (globalExclusions == null) {
            globalExclusions = new ArrayList<>();
        }
        if (chatRules == null) {
            chatRules = new LinkedHashMap<>();
        }
        globalRules = sanitizeRules(globalRules);
        globalExclusions = sanitizeRules(globalExclusions);
        Map<String, ChatRuleSet> sanitizedChats = new LinkedHashMap<>();
        for (Map.Entry<String, ChatRuleSet> entry : chatRules.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            ChatRuleSet chatRuleSet = entry.getValue() == null ? new ChatRuleSet() : entry.getValue().sanitize();
            sanitizedChats.put(entry.getKey(), chatRuleSet);
        }
        chatRules = sanitizedChats;
        if (proxyRefreshHours < 1) proxyRefreshHours = 1;
        if (proxyRefreshHours > 72) proxyRefreshHours = 72;
        if (proxyRefreshMinutes < 0) {
            proxyRefreshMinutes = proxyRefreshHours * 60;
        }
        if (proxyRefreshMinutes < 1) proxyRefreshMinutes = 1;
        if (proxyRefreshMinutes > 4320) proxyRefreshMinutes = 4320;
        if (proxyExpiryHours < 1) proxyExpiryHours = 1;
        if (proxyExpiryHours > 168) proxyExpiryHours = 168;
        if (proxySourceUrl == null) proxySourceUrl = "";
        if (proxyPastedLinks == null) proxyPastedLinks = "";
        if (proxyFolderName == null) proxyFolderName = "节点";
        if (classifyFiltersByCategory == null) {
            classifyFiltersByCategory = new LinkedHashMap<>();
        } else {
            Map<String, Integer> cleanedClassify = new LinkedHashMap<>();
            for (Map.Entry<String, Integer> entry : classifyFiltersByCategory.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
                    continue;
                }
                int v = entry.getValue();
                if (v < 0) v = 0;
                if (v > 3) v = 3;
                cleanedClassify.put(entry.getKey(), v);
            }
            classifyFiltersByCategory = cleanedClassify;
        }
        if (autoArchivedDialogIds == null) {
            autoArchivedDialogIds = new ArrayList<>();
        } else {
            List<Long> cleanedIds = new ArrayList<>();
            for (Long id : autoArchivedDialogIds) {
                if (id != null && !cleanedIds.contains(id)) cleanedIds.add(id);
            }
            autoArchivedDialogIds = cleanedIds;
        }
        if (folderArchiveOptOutDialogIds == null) {
            folderArchiveOptOutDialogIds = new ArrayList<>();
        } else {
            List<Long> cleanedOptOut = new ArrayList<>();
            for (Long id : folderArchiveOptOutDialogIds) {
                if (id != null && !cleanedOptOut.contains(id)) cleanedOptOut.add(id);
            }
            folderArchiveOptOutDialogIds = cleanedOptOut;
        }
        if (proxyEntries == null) {
            proxyEntries = new ArrayList<>();
        } else {
            List<ProxyEntry> cleaned = new ArrayList<>();
            for (ProxyEntry entry : proxyEntries) {
                if (entry == null || entry.address == null || entry.address.isBlank()
                        || entry.port < 1 || entry.port > 65535) {
                    continue;
                }
                // Build #24: a host SOCKS5 proxy has no MTProto secret but
                // has username/password; dropping it made Conso-added
                // SOCKS5 proxies invisible. Only drop entries with no
                // secret AND no auth at all (an unusable MTProto entry).
                if ((entry.secret == null || entry.secret.isBlank())
                        && (entry.username == null || entry.username.isBlank())
                        && (entry.password == null || entry.password.isBlank())) {
                    continue;
                }
                entry.address = entry.address.trim();
                entry.secret = entry.secret.trim();
                if (entry.username == null) entry.username = "";
                if (entry.password == null) entry.password = "";
                cleaned.add(entry);
            }
            proxyEntries = cleaned;
        }
        if (updatedAtEpochMs <= 0L) {
            updatedAtEpochMs = System.currentTimeMillis();
        }
        return this;
    }

    public FilterConfig deepCopy() {
        FilterConfig copy = new FilterConfig();
        copy.schemaVersion = schemaVersion;
        copy.enabled = enabled;
        copy.debugLogging = debugLogging;
        copy.action = action;
        copy.appLanguageTag = appLanguageTag;
        copy.disableSponsoredMessages = disableSponsoredMessages;
        copy.disableVideoAds = disableVideoAds;
        copy.enableForwardSave = enableForwardSave;
        copy.enableSaveStories = enableSaveStories;
        copy.preventMessageDelete = preventMessageDelete;
        copy.boostDownloadSpeed = boostDownloadSpeed;
        copy.localPremium = localPremium;
        copy.autoTranslateToChinese = autoTranslateToChinese;
        copy.highlightPinnedDialogs = highlightPinnedDialogs;
        copy.showClassifyBar = showClassifyBar;
        copy.classifyFilter = classifyFilter;
        copy.classifyFiltersByCategory = new LinkedHashMap<>();
        if (classifyFiltersByCategory != null) {
            for (Map.Entry<String, Integer> entry : classifyFiltersByCategory.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    copy.classifyFiltersByCategory.put(entry.getKey(), entry.getValue());
                }
            }
        }
        copy.aiAnalysisMode = aiAnalysisMode;
        copy.aiApiBaseUrl = aiApiBaseUrl;
        copy.aiApiModel = aiApiModel;
        copy.aiApiKey = aiApiKey;
        copy.aiFallbackToLocal = aiFallbackToLocal;
        copy.proxyAutoAdd = proxyAutoAdd;
        copy.proxyAutoSwitch = proxyAutoSwitch;
        copy.proxyRefreshHours = proxyRefreshHours;
        copy.proxyRefreshMinutes = proxyRefreshMinutes;
        copy.proxyExpiryHours = proxyExpiryHours;
        copy.proxySourceUrl = proxySourceUrl;
        copy.proxyPastedLinks = proxyPastedLinks;
        copy.proxyFolderName = proxyFolderName;
        copy.proxyFolderScan = proxyFolderScan;
        copy.proxyLastRefreshMs = proxyLastRefreshMs;
        copy.proxyConfigured = proxyConfigured;
        copy.autoArchiveFolderChats = autoArchiveFolderChats;
        copy.autoArchivedDialogIds = new ArrayList<>();
        if (autoArchivedDialogIds != null) {
            for (Long id : autoArchivedDialogIds) {
                if (id != null && !copy.autoArchivedDialogIds.contains(id)) copy.autoArchivedDialogIds.add(id);
            }
        }
        copy.folderArchiveOptOutDialogIds = new ArrayList<>();
        if (folderArchiveOptOutDialogIds != null) {
            for (Long id : folderArchiveOptOutDialogIds) {
                if (id != null && !copy.folderArchiveOptOutDialogIds.contains(id)) copy.folderArchiveOptOutDialogIds.add(id);
            }
        }
        copy.proxyEntries = new ArrayList<>();
        if (proxyEntries != null) {
            for (ProxyEntry entry : proxyEntries) {
                if (entry != null) copy.proxyEntries.add(entry.deepCopy());
            }
        }
        copy.updatedAtEpochMs = updatedAtEpochMs;
        for (RuleSpec rule : globalRules) {
            copy.globalRules.add(rule.deepCopy());
        }
        for (RuleSpec rule : globalExclusions) {
            copy.globalExclusions.add(rule.deepCopy());
        }
        for (Map.Entry<String, ChatRuleSet> entry : chatRules.entrySet()) {
            copy.chatRules.put(entry.getKey(), entry.getValue().deepCopy());
        }
        return copy;
    }

    public static String normalizeAppLanguageTag(String appLanguageTag) {
        if (appLanguageTag == null || appLanguageTag.isBlank()) {
            return APP_LANGUAGE_SYSTEM;
        }
        if (APP_LANGUAGE_ENGLISH.equalsIgnoreCase(appLanguageTag)) {
            return APP_LANGUAGE_ENGLISH;
        }
        if ("zh".equalsIgnoreCase(appLanguageTag)
                || "zh-CN".equalsIgnoreCase(appLanguageTag)
                || "zh-Hans".equalsIgnoreCase(appLanguageTag)
                || "zh-Hans-CN".equalsIgnoreCase(appLanguageTag)) {
            return APP_LANGUAGE_SIMPLIFIED_CHINESE;
        }
        return APP_LANGUAGE_SYSTEM;
    }

    private static List<RuleSpec> sanitizeRules(List<RuleSpec> rules) {
        List<RuleSpec> sanitized = new ArrayList<>();
        for (RuleSpec rule : rules) {
            if (rule == null) {
                continue;
            }
            RuleSpec normalized = rule.sanitize();
            if (normalized.pattern.isBlank()) {
                continue;
            }
            sanitized.add(normalized);
        }
        return sanitized;
    }

    public enum Action {
        HIDE,
        COLLAPSE,
        DEBUG_MARK
    }

    public enum RuleMode {
        KEYWORD,
        REGEX
    }

    public enum RuleTarget {
        ANY(""),
        TEXT("text:"),
        CAPTION("caption:"),
        BUTTONS("button:"),
        SENDER("sender:"),
        CHAT("chat:");

        private final String prefix;

        RuleTarget(String prefix) {
            this.prefix = prefix;
        }

        public String prefix() {
            return prefix;
        }
    }

    public static final class RuleSpec {
        public String id = "";
        public boolean enabled = true;
        public RuleMode mode = RuleMode.KEYWORD;
        public RuleTarget target = RuleTarget.ANY;
        public String pattern = "";
        public boolean caseSensitive = false;

        public RuleSpec sanitize() {
            if (id == null) {
                id = "";
            }
            if (mode == null) {
                mode = RuleMode.KEYWORD;
            }
            if (target == null) {
                target = RuleTarget.ANY;
            }
            if (pattern == null) {
                pattern = "";
            }
            pattern = pattern.trim();
            return this;
        }

        public RuleSpec deepCopy() {
            RuleSpec copy = new RuleSpec();
            copy.id = id;
            copy.enabled = enabled;
            copy.mode = mode;
            copy.target = target;
            copy.pattern = pattern;
            copy.caseSensitive = caseSensitive;
            return copy;
        }
    }

    public static final class ChatRuleSet {
        public boolean enabled = true;
        public boolean excludeFromGlobal = false;
        public List<RuleSpec> rules = new ArrayList<>();
        public List<RuleSpec> exclusions = new ArrayList<>();

        public ChatRuleSet sanitize() {
            if (rules == null) {
                rules = new ArrayList<>();
            }
            if (exclusions == null) {
                exclusions = new ArrayList<>();
            }
            rules = sanitizeRules(rules);
            exclusions = sanitizeRules(exclusions);
            return this;
        }

        public ChatRuleSet deepCopy() {
            ChatRuleSet copy = new ChatRuleSet();
            copy.enabled = enabled;
            copy.excludeFromGlobal = excludeFromGlobal;
            for (RuleSpec rule : rules) {
                copy.rules.add(rule.deepCopy());
            }
            for (RuleSpec rule : exclusions) {
                copy.exclusions.add(rule.deepCopy());
            }
            return copy;
        }

        public boolean isSemanticallyEmpty() {
            return enabled && !excludeFromGlobal && rules.isEmpty() && exclusions.isEmpty();
        }
    }
    public static final class ProxyEntry {
        public String address = "";
        public int port = 0;
        public String username = "";
        public String password = "";
        public String secret = "";
        public long pingMs = -1L;
        public long lastTestedMs = 0L;
        public long addedMs = 0L;
        public boolean available = false;
        public String source = "";

        public ProxyEntry deepCopy() {
            ProxyEntry copy = new ProxyEntry();
            copy.address = address;
            copy.port = port;
            copy.username = username;
            copy.password = password;
            copy.secret = secret;
            copy.pingMs = pingMs;
            copy.lastTestedMs = lastTestedMs;
            copy.addedMs = addedMs;
            copy.available = available;
            copy.source = source;
            return copy;
        }

        public String key() {
            return (address == null ? "" : address.toLowerCase(java.util.Locale.ROOT)) + ":" + port + ":" + (secret == null ? "" : secret.trim().toLowerCase(java.util.Locale.ROOT));
        }

        public String display() {
            return (address == null ? "" : address) + ":" + port;
        }
    }
}

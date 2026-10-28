package com.tianqianguai.gramsieve.core;

import java.util.Locale;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MessageRuleFactory {
    private static final Pattern DOMAIN = Pattern.compile(
            "(?i)\\b(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+(?:com|net|org|cc|top|vip|bet|ag|app|io|me|tv|xyz|site|shop|club|info|pro|live|win|casino)\\b"
    );
    private static final Pattern TELEGRAM_LINK = Pattern.compile("(?i)\\b(?:https?://)?(?:t\\.me|telegram\\.me)/[a-z0-9_+/-]{3,}");
    private static final Pattern HANDLE = Pattern.compile("(?i)(?<![\\w@])@[a-z0-9_]{4,32}\\b");
    // IP landing page, e.g. https://153.43.183.234:18029/?id=... -- the
    // strongest stable identifier when a spam message hides behind an IP
    // instead of a domain. The rule stores the IP alone (no scheme/port),
    // so a port rotation does not evade it.
    private static final Pattern IP_ADDRESS = Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b");
    // Local strengthening (Build #19): contact / payment identifiers that
    // are stable enough to become candidates (user confirms in dialog).
    private static final Pattern QQ_NUMBER = Pattern.compile("(?i)\\bQQ\\s*[:：]?\\s*(\\d{5,12})\\b");
    private static final Pattern USDT_ETH = Pattern.compile("\\b0x[0-9a-fA-F]{40}\\b");
    private static final Pattern USDT_TRON = Pattern.compile("\\bT[0-9A-Za-z]{33}\\b");
    // Chinese brand token: a Latin/digit prefix + a brand suffix, or the
    // reverse, e.g. 122体育, N1国际. Deliberately NOT any digit+Han
    // sequence: game names and amounts (极速糖果1000, 特码49) would
    // otherwise become useless brand rules. Only suffixes that mark
    // an operator brand are accepted.
    private static final Pattern CHINESE_BRAND = Pattern.compile("[A-Za-z0-9]{2,}(体育|娱乐城|娱乐|国际|平台)|(体育|娱乐城|娱乐|国际|平台)[A-Za-z0-9]{2,}");
    private static final Pattern BRANDISH_TOKEN = Pattern.compile("(?i)\\b[a-z0-9][a-z0-9._-]{2,}[a-z0-9]\\b");

    private MessageRuleFactory() {
    }

    private static String deobfuscate(String value) {
        if (value == null) return "";
        String out = value;
        out = out.replace("[.]", ".").replace("(.)", ".").replace("[dot]", ".").replace("【.】", ".");
        out = out.replace("。", ".");
        out = out.replaceAll("(?i)\\bhxxps?://", "https://");
        // "122 . com" -> "122.com": remove spaces around dots between alphanumerics.
        out = out.replaceAll("(?<=[A-Za-z0-9])\\s+\\.\\s+(?=[A-Za-z0-9])", ".");
        return out;
    }

    public static List<FilterConfig.RuleSpec> automaticRules(MessageSnapshot snapshot) {
        Map<String, FilterConfig.RuleSpec> rules = new LinkedHashMap<>();
        addRule(rules, exactContentRule(snapshot));
        if (snapshot == null) {
            return new ArrayList<>(rules.values());
        }
        String visible = snapshot.combinedVisibleContent();
        addStableTokens(rules, visible);
        return new ArrayList<>(rules.values());
    }

    /**
     * Smart-block candidates for one message, as GLOBAL rules (not per-chat).
     * Order: gambling/user keywords first, then domains / t.me links / handles /
     * brand tokens from automaticRules, then the sender name as a SENDER rule.
     * Exact full-text regex rules are intentionally excluded: they only block
     * the identical message, not similar ones, which is what smart-block is for.
     * Already-duplicated or blank candidates are removed by the caller via
     * containsEquivalentRule against the global rules.
     */
    public static List<FilterConfig.RuleSpec> smartRules(MessageSnapshot snapshot) {
        Map<String, FilterConfig.RuleSpec> rules = new LinkedHashMap<>();
        if (snapshot == null) {
            return new ArrayList<>(rules.values());
        }
        for (String term : FilterEngine.matchedGamblingTerms(snapshot)) {
            addRule(rules, keywordRule(FilterConfig.RuleTarget.TEXT, term));
        }
        // IP landing pages first, before domains: they are the actual host
        // when the message uses an IP URL instead of a domain name.
        if (snapshot != null) {
            String visibleForIp = snapshot.combinedVisibleContent();
            Matcher ipMatcher = IP_ADDRESS.matcher(visibleForIp);
            while (ipMatcher.find()) {
                String ip = ipMatcher.group();
                boolean valid = true;
                for (String octet : ip.split("\\.")) {
                    try {
                        if (Integer.parseInt(octet) > 255) {
                            valid = false;
                        }
                    } catch (NumberFormatException ignored) {
                        valid = false;
                    }
                }
                if (valid) {
                    addRule(rules, keywordRule(FilterConfig.RuleTarget.TEXT, ip));
                }
            }
            Matcher brandMatcher = CHINESE_BRAND.matcher(visibleForIp);
            while (brandMatcher.find()) {
                String token = brandMatcher.group();
                if (token.length() >= 4 && token.length() <= 10) {
                    addRule(rules, keywordRule(FilterConfig.RuleTarget.TEXT, token));
                }
            }
        }
        // De-obfuscated URLs (Build #19): spam hides domains as
        // "122 [.] com", "122。com", "hxxps://122.com" or "122 . com".
        // De-obfuscate a copy of the text, then re-run domain/IP/link
        // extraction on it, so those identifiers become candidates too.
        if (snapshot != null) {
            String deob = deobfuscate(snapshot.combinedVisibleContent());
            if (!deob.equals(snapshot.combinedVisibleContent())) {
                addStableTokens(rules, deob);
                Matcher ip2 = IP_ADDRESS.matcher(deob);
                while (ip2.find()) {
                    String ip = ip2.group();
                    boolean valid = true;
                    for (String octet : ip.split("\\.")) {
                        try { if (Integer.parseInt(octet) > 255) valid = false; } catch (NumberFormatException ignored) { valid = false; }
                    }
                    if (valid) addRule(rules, keywordRule(FilterConfig.RuleTarget.TEXT, ip));
                }
            }
            // Contact / payment identifiers.
            Matcher qq = QQ_NUMBER.matcher(snapshot.combinedVisibleContent());
            while (qq.find()) {
                String num = qq.group(1);
                if (num != null && num.length() >= 5) addRule(rules, keywordRule(FilterConfig.RuleTarget.TEXT, "QQ" + num));
            }
            Matcher eth = USDT_ETH.matcher(snapshot.combinedVisibleContent());
            while (eth.find()) addRule(rules, keywordRule(FilterConfig.RuleTarget.TEXT, eth.group()));
            Matcher tron = USDT_TRON.matcher(snapshot.combinedVisibleContent());
            while (tron.find()) addRule(rules, keywordRule(FilterConfig.RuleTarget.TEXT, tron.group()));
        }
        for (FilterConfig.RuleSpec rule : automaticRules(snapshot)) {
            if (rule != null && rule.mode == FilterConfig.RuleMode.KEYWORD) {
                addRule(rules, rule);
            }
        }
        if (snapshot.senderName != null && !snapshot.senderName.isBlank()
                && snapshot.senderName.trim().length() >= 2) {
            addRule(rules, keywordRule(FilterConfig.RuleTarget.SENDER, snapshot.senderName.trim()));
        }
        return new ArrayList<>(rules.values());
    }

    /**
     * Confirmation-dialog order for smart-block candidates: Telegram
     * handles (@usernames) first, then website links and domains, then
     * everything else (keywords, IPs, sender names, contact/payment
     * tokens). Stable: candidates inside one rank keep the order
     * smartRules produced, so a re-sort never shuffles equal items.
     * Returns a new list; the input is not mutated.
     */
    public static List<FilterConfig.RuleSpec> sortSmartCandidates(List<FilterConfig.RuleSpec> candidates) {
        List<FilterConfig.RuleSpec> sorted = new ArrayList<>();
        if (candidates != null) {
            sorted.addAll(candidates);
        }
        sorted.sort((left, right) -> Integer.compare(rankSmartCandidate(left), rankSmartCandidate(right)));
        return sorted;
    }

    private static int rankSmartCandidate(FilterConfig.RuleSpec rule) {
        if (rule == null || rule.pattern == null) {
            return 3;
        }
        String pattern = rule.pattern.trim().toLowerCase(Locale.ROOT);
        if (pattern.startsWith("@")) {
            return 0;
        }
        if (pattern.startsWith("t.me/") || pattern.startsWith("telegram.me/")) {
            return 1;
        }
        if (IP_ADDRESS.matcher(pattern).matches()) {
            return 2;
        }
        if (DOMAIN.matcher(pattern).matches() || pattern.contains(".")) {
            return 1;
        }
        return 3;
    }

    public static FilterConfig.RuleSpec exactContentRule(MessageSnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        Candidate best = bestCandidate(snapshot);
        if (best == null) {
            return null;
        }
        FilterConfig.RuleSpec rule = new FilterConfig.RuleSpec();
        rule.mode = FilterConfig.RuleMode.REGEX;
        rule.target = best.target;
        rule.pattern = "^" + Pattern.quote(best.value) + "$";
        rule.caseSensitive = false;
        rule.id = buildId(rule.mode, rule.target, rule.pattern);
        return rule.sanitize();
    }

    public static boolean containsEquivalentRule(
            Iterable<FilterConfig.RuleSpec> rules,
            FilterConfig.RuleSpec candidate
    ) {
        if (rules == null || candidate == null) {
            return false;
        }
        FilterConfig.RuleTarget target = candidate.target == null ? FilterConfig.RuleTarget.ANY : candidate.target;
        FilterConfig.RuleMode mode = candidate.mode == null ? FilterConfig.RuleMode.KEYWORD : candidate.mode;
        boolean caseSensitive = candidate.caseSensitive;
        String pattern = comparablePattern(candidate.pattern, caseSensitive);
        for (FilterConfig.RuleSpec existing : rules) {
            if (existing == null) {
                continue;
            }
            FilterConfig.RuleTarget existingTarget = existing.target == null ? FilterConfig.RuleTarget.ANY : existing.target;
            FilterConfig.RuleMode existingMode = existing.mode == null ? FilterConfig.RuleMode.KEYWORD : existing.mode;
            if (existingTarget == target
                    && existingMode == mode
                    && existing.caseSensitive == caseSensitive
                    && comparablePattern(existing.pattern, caseSensitive).equals(pattern)) {
                return true;
            }
        }
        return false;
    }

    private static void addStableTokens(Map<String, FilterConfig.RuleSpec> rules, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        addPatternMatches(rules, DOMAIN, value, MessageRuleFactory::normalizeDomain);
        addPatternMatches(rules, TELEGRAM_LINK, value, MessageRuleFactory::normalizeTelegramLink);
        addPatternMatches(rules, HANDLE, value, MessageRuleFactory::normalizeHandle);
        Matcher matcher = BRANDISH_TOKEN.matcher(value);
        while (matcher.find()) {
            String token = matcher.group();
            if (isStableBrandToken(token)) {
                addRule(rules, keywordRule(FilterConfig.RuleTarget.TEXT, token));
            }
        }
    }

    private interface TokenNormalizer {
        String normalize(String value);
    }

    private static void addPatternMatches(
            Map<String, FilterConfig.RuleSpec> rules,
            Pattern pattern,
            String value,
            TokenNormalizer normalizer
    ) {
        Matcher matcher = pattern.matcher(value);
        while (matcher.find()) {
            String token = normalizer.normalize(matcher.group());
            if (!token.isBlank()) {
                addRule(rules, keywordRule(FilterConfig.RuleTarget.TEXT, token));
            }
        }
    }

    private static FilterConfig.RuleSpec keywordRule(FilterConfig.RuleTarget target, String pattern) {
        String normalized = normalize(pattern);
        if (normalized.isBlank()) {
            return null;
        }
        FilterConfig.RuleSpec rule = new FilterConfig.RuleSpec();
        rule.mode = FilterConfig.RuleMode.KEYWORD;
        rule.target = target;
        rule.pattern = normalized;
        rule.caseSensitive = false;
        rule.id = buildId(rule.mode, rule.target, rule.pattern);
        return rule.sanitize();
    }

    private static void addRule(Map<String, FilterConfig.RuleSpec> rules, FilterConfig.RuleSpec rule) {
        if (rule == null) {
            return;
        }
        FilterConfig.RuleTarget target = rule.target == null ? FilterConfig.RuleTarget.ANY : rule.target;
        FilterConfig.RuleMode mode = rule.mode == null ? FilterConfig.RuleMode.KEYWORD : rule.mode;
        String key = target.name() + "|" + mode.name() + "|" + normalize(rule.pattern).toLowerCase(Locale.ROOT);
        rules.putIfAbsent(key, rule);
    }

    private static String normalizeDomain(String value) {
        String normalized = normalize(value).toLowerCase(Locale.ROOT);
        while (normalized.endsWith(".") || normalized.endsWith(",") || normalized.endsWith(";") || normalized.endsWith(":")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if ("t.me".equals(normalized) || "telegram.me".equals(normalized)) {
            return "";
        }
        return normalized;
    }

    private static String normalizeTelegramLink(String value) {
        String normalized = normalize(value).toLowerCase(Locale.ROOT);
        if (normalized.startsWith("https://")) {
            normalized = normalized.substring("https://".length());
        } else if (normalized.startsWith("http://")) {
            normalized = normalized.substring("http://".length());
        }
        return normalized;
    }

    private static String normalizeHandle(String value) {
        return normalize(value).toLowerCase(Locale.ROOT);
    }

    private static boolean isStableBrandToken(String value) {
        String normalized = normalize(value);
        if (normalized.length() < 4 || normalized.length() > 48) {
            return false;
        }
        if (DOMAIN.matcher(normalized).matches()) {
            return true;
        }
        boolean hasLetter = false;
        boolean hasDigit = false;
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            hasLetter |= Character.isLetter(c);
            hasDigit |= Character.isDigit(c);
        }
        return hasLetter && hasDigit;
    }

    private static Candidate bestCandidate(MessageSnapshot snapshot) {
        Candidate best = null;
        best = better(best, FilterConfig.RuleTarget.TEXT, snapshot.text);
        best = better(best, FilterConfig.RuleTarget.CAPTION, snapshot.caption);
        best = better(best, FilterConfig.RuleTarget.BUTTONS, snapshot.buttonText);
        return best;
    }

    private static Candidate better(Candidate current, FilterConfig.RuleTarget target, String value) {
        String normalized = normalize(value);
        if (normalized.isBlank()) {
            return current;
        }
        Candidate candidate = new Candidate(target, normalized);
        if (current == null || candidate.score() > current.score()) {
            return candidate;
        }
        return current;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static String comparablePattern(String value, boolean caseSensitive) {
        String normalized = normalize(value);
        return caseSensitive ? normalized : normalized.toLowerCase(Locale.ROOT);
    }

    private static String buildId(FilterConfig.RuleMode mode, FilterConfig.RuleTarget target, String pattern) {
        String normalized = pattern.toLowerCase(Locale.ROOT);
        return "manual_" + mode.name().charAt(0) + "_" + target.name() + "_" + Integer.toUnsignedString(normalized.hashCode(), 16);
    }

    private static final class Candidate {
        final FilterConfig.RuleTarget target;
        final String value;

        Candidate(FilterConfig.RuleTarget target, String value) {
            this.target = target;
            this.value = value;
        }

        int score() {
            return value.length();
        }
    }
}

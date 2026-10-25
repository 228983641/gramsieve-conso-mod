package com.tianqianguai.gramsieve.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class FilterEngine {
    private static final String[] GAMBLING_KEYWORDS = new String[]{
            "娱乐城",
            "赌场",
            "博彩",
            "网投",
            "棋牌",
            "真人荷官",
            "体育博彩",
            "电竞投注",
            "验资",
            "上分",
            "下分",
            "包杀",
            "包赔",
            "赔率"
    };
    // Conso adaptation: user-facing gambling terms that the upstream list misses.
    // Upstream GAMBLING_KEYWORDS has no 赌博 / 百家乐 / 彩票, yet those are the
    // terms the user actually wants filtered, so smart-block checks them too.
    // Expanded 2026-10-25 from two real spam samples supplied by the user:
    // sample 1 (78.COM / IP landing page): 六合彩, 特码, 连码, 三中三, 捕鱼,
    // 德州, 十三水, 真人视讯, 返水, 出款, 提款, 派彩, 爆奖, 一拉, 占成, 打码,
    // 返佣, 担保, 飞投, 充提, 万U, 代理, 盘总.
    // sample 2 (122体育 / n10086.com): 返水, 出款, 提款, 彩金, 流水, 存款,
    // 存取款, 免实名, 免绑卡, 捕鱼, 一拉, 一注, USDT.
    // These are STRONG terms: specific enough to become a global keyword rule.
    // Generic promo cues (http://, https://, .com, 官方, 下载, 网址, 注册,
    // 开户) are deliberately NOT returned here: as a rule they would match
    // almost every normal message. They still count for isLikelyGamblingPromotion
    // scoring, just not as rule candidates.
    private static final String[] USER_GAMBLING_KEYWORDS = new String[]{
            "赌博",
            "百家乐",
            "彩票",
            "投注",
            "下注",
            "开奖",
            "六合彩",
            "特码",
            "连码",
            "三中三",
            "捕鱼",
            "德州",
            "十三水",
            "真人视讯",
            "返水",
            "出款",
            "提款",
            "派彩",
            "爆奖",
            "一拉",
            "一注",
            "占成",
            "打码",
            "返佣",
            "彩金",
            "流水",
            "存款",
            "存取款",
            "免实名",
            "免绑卡",
            "担保",
            "飞投",
            "充提",
            "代理",
            "盘总",
            "USDT",
            "万U",
    };

    // Weak terms: appear in gambling promos, but also in normal chats.
    // They are offered in the smart-block dialog UNCHECKED by default, so the
    // user can tick them deliberately instead of blocking sports / e-goods /
    // red-packet chats by accident.
    static final String[] WEAK_GAMBLING_KEYWORDS = new String[]{
            "体育",
            "电子",
            "红包",
    };

    private static final String[] GAMBLING_PROMO_CUES = new String[]{
            "http://",
            "https://",
            ".com",
            ".ag",
            ".bet",
            ".vip",
            ".cc",
            ".top",
            "网址",
            "官方",
            "注册",
            "开户",
            "下载",
            "代理",
            "放心赢",
            "立即加入",
            "联系客服",
            "usdt"
    };

    private long compiledToken = Long.MIN_VALUE;
    private List<CompiledRule> globalRules = List.of();
    private List<CompiledRule> globalExclusions = List.of();

    public synchronized FilterDecision evaluate(FilterConfig config, MessageSnapshot snapshot) {
        if (config == null || snapshot == null || !config.enabled) {
            return FilterDecision.allow();
        }

        FilterConfig.ChatRuleSet chatRuleSet = config.getChatRuleSet(snapshot.dialogId);
        if (chatRuleSet != null && !chatRuleSet.enabled) {
            return FilterDecision.allow();
        }

        ensureCompiled(config);

        List<CompiledRule> activeRules = new ArrayList<>();
        List<CompiledRule> activeExclusions = new ArrayList<>();
        boolean useGlobal = chatRuleSet == null || !chatRuleSet.excludeFromGlobal;
        if (useGlobal) {
            activeRules.addAll(globalRules);
            activeExclusions.addAll(globalExclusions);
        }
        if (chatRuleSet != null) {
            activeRules.addAll(compile(filterChatScopedRules(chatRuleSet.rules)));
            activeExclusions.addAll(compile(filterChatScopedRules(chatRuleSet.exclusions)));
        }

        CompiledRule exclusion = firstMatch(activeExclusions, snapshot);
        if (exclusion != null) {
            return FilterDecision.excluded(exclusion.id, "excluded by " + exclusion.id);
        }

        CompiledRule hardRule = firstMatch(filterByTarget(activeRules, true), snapshot);
        if (hardRule != null) {
            return FilterDecision.matched(config.action, hardRule.id, "hard match " + hardRule.id);
        }

        CompiledRule regexRule = firstMatch(filterByModeAndTarget(activeRules, FilterConfig.RuleMode.REGEX, false), snapshot);
        if (regexRule != null) {
            return FilterDecision.matched(config.action, regexRule.id, "regex match " + regexRule.id);
        }

        CompiledRule keywordRule = firstMatch(filterByModeAndTarget(activeRules, FilterConfig.RuleMode.KEYWORD, false), snapshot);
        if (keywordRule != null) {
            return FilterDecision.matched(config.action, keywordRule.id, "keyword match " + keywordRule.id);
        }

        return FilterDecision.allow();
    }

    private void ensureCompiled(FilterConfig config) {
        if (compiledToken == config.updatedAtEpochMs) {
            return;
        }
        globalRules = compile(config.globalRules);
        globalExclusions = compile(config.globalExclusions);
        compiledToken = config.updatedAtEpochMs;
    }

    private static List<CompiledRule> filterByTarget(List<CompiledRule> rules, boolean senderOrChatOnly) {
        List<CompiledRule> filtered = new ArrayList<>();
        for (CompiledRule rule : rules) {
            if (senderOrChatOnly) {
                if (rule.target == FilterConfig.RuleTarget.SENDER || rule.target == FilterConfig.RuleTarget.CHAT) {
                    filtered.add(rule);
                }
            }
        }
        return filtered;
    }

    private static List<CompiledRule> filterByModeAndTarget(List<CompiledRule> rules, FilterConfig.RuleMode mode, boolean senderOrChatOnly) {
        List<CompiledRule> filtered = new ArrayList<>();
        for (CompiledRule rule : rules) {
            if (rule.mode != mode) {
                continue;
            }
            boolean hardTarget = rule.target == FilterConfig.RuleTarget.SENDER || rule.target == FilterConfig.RuleTarget.CHAT;
            if (senderOrChatOnly == hardTarget) {
                filtered.add(rule);
            }
        }
        return filtered;
    }

    private static List<CompiledRule> compile(List<FilterConfig.RuleSpec> rules) {
        List<CompiledRule> compiled = new ArrayList<>();
        for (FilterConfig.RuleSpec rule : rules) {
            if (rule == null || !rule.enabled || rule.pattern == null || rule.pattern.isBlank()) {
                continue;
            }
            compiled.add(new CompiledRule(rule));
        }
        return compiled;
    }

    private static List<FilterConfig.RuleSpec> filterChatScopedRules(List<FilterConfig.RuleSpec> rules) {
        List<FilterConfig.RuleSpec> filtered = new ArrayList<>();
        for (FilterConfig.RuleSpec rule : rules) {
            if (rule == null) {
                continue;
            }
            if (rule.target == FilterConfig.RuleTarget.CHAT) {
                continue;
            }
            filtered.add(rule);
        }
        return filtered;
    }

    private static CompiledRule firstMatch(List<CompiledRule> rules, MessageSnapshot snapshot) {
        for (CompiledRule rule : rules) {
            if (rule.matches(snapshot)) {
                return rule;
            }
        }
        return null;
    }

    /**
     * Returns the gambling terms actually present in the snapshot, in
     * first-seen order, deduplicated. Strong terms first, then weak terms.
     * Matching is done against both the raw text and an emoji-stripped
     * version, because spam inserts emoji (e.g. 🤩) as separators and
     * sometimes around a keyword; the returned term itself is the clean
     * keyword, so the resulting rule matches clean future messages.
     * Promo cues (http://, .com, 官方, 下载, ...) are NOT returned: they
     * are scoring signals for isLikelyGamblingPromotion, not safe rules.
     */
    public static List<String> matchedGamblingTerms(MessageSnapshot snapshot) {
        List<String> matched = new ArrayList<>();
        if (snapshot == null) {
            return matched;
        }
        String content = snapshot.combinedVisibleContent();
        if (content == null || content.isBlank()) {
            return matched;
        }
        String raw = content.toLowerCase(Locale.ROOT);
        String cleaned = raw.replaceAll("[\\p{So}\\p{Sk}\\p{Cf}\\uFE0F]", "");
        // 2026-10-25 optimisation for Build #7 feedback: spam splits a
        // keyword with emoji, spaces, dots or other separators
        // ("百🤩家乐", "百 家 乐", "百.家.乐"). Compact both sides to
        // letters/digits only, so the clean keyword still matches.
        String compacted = compactLettersDigits(raw);
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String[] group : new String[][]{GAMBLING_KEYWORDS, USER_GAMBLING_KEYWORDS, WEAK_GAMBLING_KEYWORDS}) {
            for (String term : group) {
                if (term == null || term.isBlank()) {
                    continue;
                }
                String key = term.toLowerCase(Locale.ROOT);
                String keyCompact = compactLettersDigits(key);
                if (!seen.contains(key) && (raw.contains(key) || cleaned.contains(key)
                        || (!keyCompact.isBlank() && compacted.contains(keyCompact)))) {
                    seen.add(key);
                    matched.add(term);
                }
            }
        }
        return matched;

    /** Letters/digits only (Han counts as a letter), for separator-proof matching. */
    static String compactLettersDigits(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                out.append(c);
            }
        }
        return out.toString();
    }
    }

    public static boolean isWeakGamblingTerm(String term) {
        if (term == null) {
            return false;
        }
        String key = term.toLowerCase(Locale.ROOT);
        for (String weak : WEAK_GAMBLING_KEYWORDS) {
            if (weak.toLowerCase(Locale.ROOT).equals(key)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isLikelyGamblingPromotion(MessageSnapshot snapshot) {
        if (snapshot == null) {
            return false;
        }
        String content = snapshot.combinedVisibleContent();
        if (content.isBlank()) {
            return false;
        }
        String normalized = content.toLowerCase(Locale.ROOT);
        int gamblingHits = keywordHits(normalized, GAMBLING_KEYWORDS);
        if (gamblingHits == 0) {
            return false;
        }
        int promoHits = keywordHits(normalized, GAMBLING_PROMO_CUES);
        if (snapshot.hasInlineButtons) {
            promoHits++;
        }
        if (normalized.contains("://")) {
            promoHits++;
        }
        return gamblingHits >= 2 || (gamblingHits >= 1 && promoHits >= 2);
    }

    private static int keywordHits(String value, String[] keywords) {
        int hits = 0;
        for (String keyword : keywords) {
            if (value.contains(keyword.toLowerCase(Locale.ROOT))) {
                hits++;
            }
        }
        return hits;
    }

    private static final class CompiledRule {
        final String id;
        final FilterConfig.RuleMode mode;
        final FilterConfig.RuleTarget target;
        final String rawPattern;
        final String normalizedKeyword;
        final Pattern regex;
        final boolean caseSensitive;

        CompiledRule(FilterConfig.RuleSpec rule) {
            id = rule.id == null ? "" : rule.id;
            mode = rule.mode == null ? FilterConfig.RuleMode.KEYWORD : rule.mode;
            target = rule.target == null ? FilterConfig.RuleTarget.ANY : rule.target;
            rawPattern = rule.pattern.trim();
            caseSensitive = rule.caseSensitive;
            if (mode == FilterConfig.RuleMode.REGEX) {
                Pattern compiled = null;
                try {
                    int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
                    compiled = Pattern.compile(rawPattern, flags);
                } catch (PatternSyntaxException ignored) {
                    compiled = null;
                }
                regex = compiled;
                normalizedKeyword = "";
            } else {
                regex = null;
                normalizedKeyword = caseSensitive ? rawPattern : rawPattern.toLowerCase(Locale.ROOT);
            }
        }

        boolean matches(MessageSnapshot snapshot) {
            List<String> values = snapshot.valuesFor(target);
            for (String value : values) {
                if (mode == FilterConfig.RuleMode.REGEX) {
                    if (regex != null && regex.matcher(value).find()) {
                        return true;
                    }
                } else {
                    String haystack = caseSensitive ? value : value.toLowerCase(Locale.ROOT);
                    if (haystack.contains(normalizedKeyword)) {
                        return true;
                    }
                    // Separator-proof fallback (see matchedGamblingTerms):
                    // a rule for 百家乐 must also catch 百🤩家乐 / 百 家 乐.
                    String compactKeyword = compactLettersDigits(normalizedKeyword);
                    if (compactKeyword.length() >= 2
                            && compactLettersDigits(haystack).contains(compactKeyword)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }
}

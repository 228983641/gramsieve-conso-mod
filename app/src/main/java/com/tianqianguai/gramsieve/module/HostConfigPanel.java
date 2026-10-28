package com.tianqianguai.gramsieve.module;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import com.tianqianguai.gramsieve.config.ModuleConfigStore;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.tianqianguai.gramsieve.R;
import com.tianqianguai.gramsieve.config.AntiRecallConfigStore;
import com.tianqianguai.gramsieve.core.FilterConfig;
import com.tianqianguai.gramsieve.core.RuleDraftMatrix;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

@SuppressLint("UseSwitchCompatOrMaterialCode")
final class HostConfigPanel {
    interface ConfigSaver {
        FilterConfig save(FilterConfig updated);
    }

    private static final FilterConfig.RuleTarget[] GLOBAL_TARGETS = new FilterConfig.RuleTarget[]{
            FilterConfig.RuleTarget.ANY,
            FilterConfig.RuleTarget.TEXT,
            FilterConfig.RuleTarget.CAPTION,
            FilterConfig.RuleTarget.BUTTONS,
            FilterConfig.RuleTarget.SENDER,
            FilterConfig.RuleTarget.CHAT
    };
    private static final FilterConfig.RuleTarget[] CHAT_TARGETS = new FilterConfig.RuleTarget[]{
            FilterConfig.RuleTarget.ANY,
            FilterConfig.RuleTarget.TEXT,
            FilterConfig.RuleTarget.CAPTION,
            FilterConfig.RuleTarget.BUTTONS,
            FilterConfig.RuleTarget.SENDER
    };

    private final Context context;
    private final ViewGroup root;
    private FilterConfig baseConfig;
    private final boolean chatMode;
    private final long dialogId;
    private final String chatTitle;
    private final AntiRecallConfigStore antiRecallConfigStore;
    private final BackgroundMessageLoader backgroundMessageLoader;
    private final ConfigSaver saver;
    private final Runnable afterSave;
    private final boolean chinese;
    private final int backgroundColor;
    private final int cardColor;
    private final int primaryTextColor;
    private final int secondaryTextColor;
    private final int strokeColor;
    private final int accentColor;
    private final int switchOnColor;
    private final int switchOffColor;
    private final int switchKnobColor;
    private final float screenWidthDp;
    private final int toolbarColor;
    private final int toolbarTextColor;
    private final Map<FilterConfig.RuleTarget, RuleInputs> ruleInputs =
            new EnumMap<>(FilterConfig.RuleTarget.class);

    private FrameLayout overlay;
    private OnBackInvokedDispatcher backDispatcher;
    private OnBackInvokedCallback backCallback;
    private IosSwitchView enabledSwitch;
    private IosSwitchView debugLoggingSwitch;
    private IosSwitchView excludeChatSwitch;
    private IosSwitchView chatAntiRecallSwitch;
    private IosSwitchView sponsoredSwitch;
    private IosSwitchView videoAdsSwitch;
    private IosSwitchView forwardSaveSwitch;
    private IosSwitchView saveStoriesSwitch;
    private IosSwitchView preventDeleteSwitch;
    private IosSwitchView downloadSpeedSwitch;
    private IosSwitchView localPremiumSwitch;
    private IosSwitchView autoTranslateSwitch;
    private IosSwitchView pinnedHighlightSwitch;
    private IosSwitchView aiLlmSwitch;
    private IosSwitchView aiFallbackSwitch;
    private IosSwitchView proxyAutoAddSwitch;
    private IosSwitchView proxyAutoSwitchSwitch;
    private EditText proxyRefreshInput;
    private EditText proxyExpiryInput;
    private EditText proxyFolderInput;
    private TextView proxySummary;
    private String lastProxyFolderDiag = "";
    private java.util.List<FilterConfig.ProxyEntry> proxyWorkingEntries;
    private EditText aiBaseUrlInput;
    private EditText aiModelInput;
    private EditText aiKeyInput;
    private android.widget.TextView languageSelector;
    private android.widget.TextView actionSelector;
    private Object[] languageValues;
    private Object[] actionValues;
    private Object languageSelected;
    private Object actionSelected;

    private HostConfigPanel(
            Context context,
            ViewGroup root,
            FilterConfig baseConfig,
            boolean chatMode,
            long dialogId,
            String chatTitle,
            AntiRecallConfigStore antiRecallConfigStore,
            BackgroundMessageLoader backgroundMessageLoader,
            ConfigSaver saver,
            Runnable afterSave
    ) {
        this.context = context;
        android.util.DisplayMetrics dm = context.getResources().getDisplayMetrics();
        screenWidthDp = dm.widthPixels / dm.density;
        this.root = root;
        this.baseConfig = baseConfig == null ? FilterConfig.createDefault() : baseConfig.deepCopy().sanitize();
        this.chatMode = chatMode;
        this.dialogId = dialogId;
        this.chatTitle = chatTitle == null ? "" : chatTitle;
        this.antiRecallConfigStore = antiRecallConfigStore;
        this.backgroundMessageLoader = backgroundMessageLoader;
        this.saver = saver;
        this.afterSave = afterSave;
        // Conso adaptation: force Chinese UI for all host panels, regardless of host locale.
        this.chinese = true;
        int androidBackground = resolveThemeColor(android.R.attr.colorBackground, Color.rgb(18, 18, 18));
        int androidPrimaryText = resolveThemeColor(android.R.attr.textColorPrimary, Color.WHITE);
        int androidSecondaryText = resolveThemeColor(
                android.R.attr.textColorSecondary,
                adjustAlpha(androidPrimaryText, 0.68f)
        );
        int androidAccent = resolveThemeColor(android.R.attr.colorAccent, Color.rgb(42, 171, 238));
        this.backgroundColor = telegramThemeColor("key_windowBackgroundWhite", androidBackground);
        this.cardColor = telegramThemeColor("key_windowBackgroundGray", backgroundColor);
        this.primaryTextColor = telegramThemeColor("key_windowBackgroundWhiteBlackText", androidPrimaryText);
        // Contrast guard (Build #18, from user screenshots): Telegram's
        // secondary gray can resolve too dark against a dark card, and the
        // dialog dimming makes it worse, so info text looked unreadable.
        // If secondary vs card contrast is below 4.5:1, blend secondary
        // toward primary until it passes, so info text is always readable
        // without changing the theme's hue. Single assignment because the
        // field is final (Build #19 fix: double assignment failed compile).
        this.secondaryTextColor = ensureContrast(
                telegramThemeColor("key_windowBackgroundWhiteGrayText2", androidSecondaryText),
                cardColor, primaryTextColor);
        this.strokeColor = telegramThemeColor("key_divider", adjustAlpha(primaryTextColor, 0.14f));
        this.toolbarColor = telegramThemeColor("key_actionBarDefault", backgroundColor);
        this.toolbarTextColor = telegramThemeColor("key_actionBarDefaultTitle", primaryTextColor);
        this.accentColor = android.graphics.Color.parseColor("#007AFF"); // Build #46: HTML v2 iOS primary blue
        switchOnColor = android.graphics.Color.parseColor("#34C759"); // Build #46: HTML v2 switch-on green
        switchOffColor = blend(backgroundColor, primaryTextColor, 0.14f);
        switchKnobColor = android.graphics.Color.WHITE;
    }

    static boolean show(
            Context context,
            ViewGroup root,
            FilterConfig config,
            boolean chatMode,
            long dialogId,
            String chatTitle,
            AntiRecallConfigStore antiRecallConfigStore,
            BackgroundMessageLoader backgroundMessageLoader,
            ConfigSaver saver,
            Runnable afterSave
    ) {
        if (context == null || root == null || saver == null) {
            return false;
        }
        closeExisting(root);
        HostConfigPanel panel = new HostConfigPanel(
                context,
                root,
                config,
                chatMode,
                dialogId,
                chatTitle,
                antiRecallConfigStore,
                backgroundMessageLoader,
                saver,
                afterSave
        );
        panel.attach();
        return true;
    }

    static boolean closeExisting(ViewGroup root) {
        if (root == null) {
            return false;
        }
        View existing = root.findViewById(R.id.gramsieve_host_config_panel_id);
        if (existing == null) {
            return false;
        }
        Object owner = existing.getTag();
        if (owner instanceof HostConfigPanel) {
            ((HostConfigPanel) owner).close();
            return true;
        }
        ViewGroup parent = (ViewGroup) existing.getParent();
        if (parent != null) {
            parent.removeView(existing);
            return true;
        }
        return false;
    }

    static FilterConfig applyGlobalDraft(
            FilterConfig original,
            boolean enabled,
            boolean debugLogging,
            String appLanguageTag,
            FilterConfig.Action action,
            RuleDraftMatrix matchMatrix,
            RuleDraftMatrix exclusionMatrix
    ) {
        FilterConfig updated = original == null ? FilterConfig.createDefault() : original.deepCopy();
        updated.enabled = enabled;
        updated.debugLogging = debugLogging;
        updated.appLanguageTag = appLanguageTag;
        updated.action = action == null ? FilterConfig.Action.HIDE : action;
        updated.globalRules = exportRules(matchMatrix, false);
        updated.globalExclusions = exportRules(exclusionMatrix, false);
        updated.updatedAtEpochMs = System.currentTimeMillis();
        return updated.sanitize();
    }

    static FilterConfig applyChatDraft(
            FilterConfig original,
            long dialogId,
            boolean enabled,
            boolean excludeFromGlobal,
            RuleDraftMatrix matchMatrix,
            RuleDraftMatrix exclusionMatrix
    ) {
        FilterConfig updated = original == null ? FilterConfig.createDefault() : original.deepCopy();
        FilterConfig.ChatRuleSet chatRuleSet = updated.getOrCreateChatRuleSet(dialogId);
        chatRuleSet.enabled = enabled;
        chatRuleSet.excludeFromGlobal = excludeFromGlobal;
        chatRuleSet.rules = exportRules(matchMatrix, true);
        chatRuleSet.exclusions = exportRules(exclusionMatrix, true);
        chatRuleSet.sanitize();
        if (chatRuleSet.isSemanticallyEmpty()) {
            updated.chatRules.remove(FilterConfig.chatKey(dialogId));
        }
        updated.updatedAtEpochMs = System.currentTimeMillis();
        return updated.sanitize();
    }

    private static List<FilterConfig.RuleSpec> exportRules(RuleDraftMatrix source, boolean chatMode) {
        RuleDraftMatrix exported = new RuleDraftMatrix();
        RuleDraftMatrix safeSource = source == null ? new RuleDraftMatrix() : source;
        for (FilterConfig.RuleTarget target : editorTargets(chatMode)) {
            exported.set(target, FilterConfig.RuleMode.KEYWORD, safeSource.get(target, FilterConfig.RuleMode.KEYWORD));
            exported.set(target, FilterConfig.RuleMode.REGEX, safeSource.get(target, FilterConfig.RuleMode.REGEX));
        }
        return exported.toRules();
    }

    private static FilterConfig.RuleTarget[] editorTargets(boolean chatMode) {
        return chatMode ? CHAT_TARGETS : GLOBAL_TARGETS;
    }

    private void attach() {
        overlay = new FrameLayout(context);
        overlay.setId(R.id.gramsieve_host_config_panel_id);
        overlay.setTag(this);
        overlay.setClickable(true);
        overlay.setFocusable(true);
        overlay.setFocusableInTouchMode(true);
        overlay.setBackgroundColor(cardColor);
        overlay.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            view.setPadding(0, bars.top, 0, bars.bottom);
            return insets;
        });
        overlay.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    close();
                }
                return true;
            }
            return false;
        });
        overlay.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View view) {
            }

            @Override
            public void onViewDetachedFromWindow(View view) {
                unregisterSystemBackHandler();
            }
        });

        LinearLayout screen = new LinearLayout(context);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setBackgroundColor(cardColor);
        overlay.addView(screen, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        screen.addView(createToolbar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(56)
        ));
        View toolbarDivider = new View(context);
        toolbarDivider.setBackgroundColor(strokeColor);
        screen.addView(toolbarDivider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Math.max(1, dp(1))
        ));

        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(false);
        scrollView.setBackgroundColor(cardColor);
        scrollView.setClipToPadding(false);
        scrollView.setVerticalScrollBarEnabled(false);
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        // Keep the grouped background visible around solid cards so
        // the page has a clear rhythm without adding another surface.
        container.setBackgroundColor(cardColor);
        container.setPadding(dp(16), dp(16), dp(16), dp(32));
        scrollView.addView(container, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        screen.addView(scrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));

        buildGeneralCard(container);
        // HTML v2: global panel is three cards (basic / language /
        // action); chat mode is one card. Cards are [header, card]
        // pairs, so the cards sit at -1, -3, -5.
        if (chatMode) {
            styleIosCard((LinearLayout) container.getChildAt(container.getChildCount() - 1), 1);
        } else {
            styleIosCard((LinearLayout) container.getChildAt(container.getChildCount() - 1), 1);
            styleIosCard((LinearLayout) container.getChildAt(container.getChildCount() - 3), 1);
            styleIosCard((LinearLayout) container.getChildAt(container.getChildCount() - 5), gridCols(2));
        }
        if (chatMode) {
            buildChatAntiRecallCard(container);
            styleIosCard((LinearLayout) container.getChildAt(container.getChildCount() - 1), gridCols(2));
        } else {
            buildEnhancementCard(container);
            styleIosCard((LinearLayout) container.getChildAt(container.getChildCount() - 1), gridCols(3));
            buildDialogListCard(container);
            styleIosCard((LinearLayout) container.getChildAt(container.getChildCount() - 1), gridCols(2));
            buildAiCard(container);
            styleIosCard((LinearLayout) container.getChildAt(container.getChildCount() - 1), gridCols(2));
            buildProxyCard(container);
            styleIosCard((LinearLayout) container.getChildAt(container.getChildCount() - 1), gridCols(2));
            buildTranslateCard(container);
            styleIosCard((LinearLayout) container.getChildAt(container.getChildCount() - 1), 1);
        }
        buildRulesCard(container);
        styleIosCard((LinearLayout) container.getChildAt(container.getChildCount() - 1), gridCols(2), false);
        if (!chatMode) {
            buildBackupCard(container);
            styleIosCard((LinearLayout) container.getChildAt(container.getChildCount() - 1), 1);
        } else {
            // Build #31: the backup card (and its log export) only
            // existed in the global panel, so a user opening settings
            // from inside a chat could never find log export at all.
            // Log export is mode-independent, so chat mode gets its
            // own log card; full backup stays global-only, because
            // importing a full backup from a chat panel could replace
            // global settings from the wrong context.
            buildLogExportCard(container);
            styleIosCard((LinearLayout) container.getChildAt(container.getChildCount() - 1), 1);
        }

        TextView footerNote = new TextView(context);
        footerNote.setText("GramSieve Conso · 本地过滤与增强工具");
        footerNote.setTextColor(secondaryTextColor);
        footerNote.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        footerNote.setGravity(Gravity.CENTER);
        footerNote.setPadding(0, dp(16), 0, 0);
        container.addView(footerNote, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        buildLinksFooter(container);

        root.addView(overlay, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        overlay.bringToFront();
        overlay.requestApplyInsets();
        overlay.requestFocus();
        overlay.requestFocusFromTouch();
        registerSystemBackHandler();
    }

    private View createToolbar() {
        LinearLayout toolbar = new LinearLayout(context);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(12), 0, dp(12), 0);
        toolbar.setBackgroundColor(backgroundColor);

        Button backButton = toolbarButton(t("返回", "Back"));
        backButton.setContentDescription(t("关闭设置", "Close settings"));
        backButton.setOnClickListener(v -> close());
        toolbar.addView(backButton, new LinearLayout.LayoutParams(dp(72), dp(44)));

        TextView title = new TextView(context);
        title.setText(chatMode ? t("GramSieve Conso 聊天设置", "GramSieve Conso Chat Settings") : t("GramSieve Conso 设置", "GramSieve Conso Settings"));
        title.setTextColor(toolbarTextColor);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setSingleLine(true);
        title.setGravity(Gravity.CENTER);
        try { title.setLetterSpacing(0.01f); } catch (Throwable ignored) {}
        toolbar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        Button saveButton = toolbarPrimaryButton(t("保存", "Save"));
        saveButton.setContentDescription(t("保存设置", "Save settings"));
        saveButton.setOnClickListener(v -> save());
        toolbar.addView(saveButton, new LinearLayout.LayoutParams(dp(72), dp(40)));
        return toolbar;
    }

    private void buildGeneralCard(LinearLayout container) {
        FilterConfig.ChatRuleSet chatRuleSet = chatMode
                ? baseConfig.getOrCreateChatRuleSet(dialogId).deepCopy().sanitize()
                : null;

        if (chatMode) {
            LinearLayout card = addGroup(container, t("当前聊天", "Current Chat"));
            enabledSwitch = addSwitch(card, t("启用过滤", "Enable filtering"));
            enabledSwitch.setChecked(chatRuleSet.enabled);
            excludeChatSwitch = addSwitch(card, t("这个聊天不使用全局规则", "Do not apply global rules to this chat"));
            excludeChatSwitch.setChecked(chatRuleSet.excludeFromGlobal);
            String scope = chatTitle.isBlank()
                    ? t("当前聊天 ID：", "Current chat ID: ") + dialogId
                    : t("当前聊天：", "Current chat: ") + chatTitle;
            addInfo(card, scope);
            return;
        }

        // Build #46: HTML v2 splits the old general card into
        // three cards: basic switches, language radios, action radios.
        LinearLayout card = addGroup(container, t("基础设置", "Basic Settings"));
        enabledSwitch = addSwitch(card, t("启用过滤", "Enable filtering"));
        enabledSwitch.setChecked(baseConfig.enabled);
        debugLoggingSwitch = addSwitch(card, t("详细调试日志", "Verbose debug logging"));
        debugLoggingSwitch.setChecked(baseConfig.debugLogging);

        // Language and match action sit side by side, each as a
        // dropdown inside its own card, instead of two full-width
        // radio grids. Columns are weights, never fixed dp, so the
        // pair cannot overflow a narrow screen.
        LinearLayout sideRow = new LinearLayout(context);
        sideRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout langCol = new LinearLayout(context);
        langCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout actionCol = new LinearLayout(context);
        actionCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams langColParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        langColParams.rightMargin = dp(8);
        LinearLayout.LayoutParams actionColParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        actionColParams.leftMargin = dp(8);
        sideRow.addView(langCol, langColParams);
        sideRow.addView(actionCol, actionColParams);
        container.addView(sideRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout langCard = addGroup(langCol, t("界面语言", "Interface language"));
        languageValues = new Object[]{
                FilterConfig.APP_LANGUAGE_SYSTEM,
                FilterConfig.APP_LANGUAGE_ENGLISH,
                FilterConfig.APP_LANGUAGE_SIMPLIFIED_CHINESE};
        languageSelected = FilterConfig.normalizeAppLanguageTag(baseConfig.appLanguageTag);
        languageSelector = addDropdown(langCard,
                new String[]{t("跟随系统", "Follow system"), "English", t("简体中文", "Simplified Chinese")},
                languageValues,
                languageSelected,
                value -> languageSelected = value);

        LinearLayout actionCard = addGroup(actionCol, t("匹配后的动作", "Match action"));
        actionValues = new Object[]{
                FilterConfig.Action.HIDE,
                FilterConfig.Action.COLLAPSE,
                FilterConfig.Action.DEBUG_MARK};
        actionSelected = baseConfig.action == null ? FilterConfig.Action.HIDE : baseConfig.action;
        actionSelector = addDropdown(actionCard,
                new String[]{t("仅本地隐藏", "Hide locally"), t("仅本地折叠", "Collapse locally"), t("调试标记", "Debug mark")},
                actionValues,
                actionSelected,
                value -> actionSelected = value);
    }

    private void buildEnhancementCard(LinearLayout container) {
        LinearLayout card = addGroup(container, t("增强功能", "Enhancements"));
        addInfo(card, t("从 NoAdsTelegram / TeleVip 移植的功能，按分类开关，默认全部关闭，开启后保存即生效（部分需重新打开聊天列表）。", "Features ported from NoAdsTelegram / TeleVip, grouped by category. All off by default; saving applies them (some need the chat list reopened)."));

        sponsoredSwitch = addSwitch(card, t("去赞助消息", "Remove sponsored messages"));
        sponsoredSwitch.setChecked(baseConfig.disableSponsoredMessages);
        videoAdsSwitch = addSwitch(card, t("去视频广告", "Remove video ads"));
        videoAdsSwitch.setChecked(baseConfig.disableVideoAds);

        forwardSaveSwitch = addSwitch(card, t("允许转发和保存", "Allow forwarding and saving"));
        forwardSaveSwitch.setChecked(baseConfig.enableForwardSave);
        preventDeleteSwitch = addSwitch(card, t("防止消息删除", "Prevent message deletion"));
        preventDeleteSwitch.setChecked(baseConfig.preventMessageDelete);
        setRowDesc(preventDeleteSwitch, t("删除后仍可查看", "Still viewable after deletion"));
        addInfo(card, t("防删除与主动加载防撤回是两套机制，可同时开；防删除会直接拦下服务端的删除更新。", "Delete prevention is a second mechanism alongside proactive-loading anti-recall; it blocks the server delete update itself."));

        saveStoriesSwitch = addSwitch(card, t("允许保存动态", "Allow saving stories"));
        saveStoriesSwitch.setChecked(baseConfig.enableSaveStories);
        addInfo(card, t("本版为简化移植：允许动态截图、启用已有的保存菜单项；若 Conso 的动态菜单里本来就没有保存项，本开关单独不起作用，需后续再补。", "Simplified port in this build: allows story screenshots and enables an existing save menu item. If Conso's story menu has no save item at all, this switch alone will not add one."));

        downloadSpeedSwitch = addSwitch(card, t("提升下载速度", "Boost download speed"));
        downloadSpeedSwitch.setChecked(baseConfig.boostDownloadSpeed);
        setRowDesc(downloadSpeedSwitch, t("多线程加速", "Multi-threaded speedup"));
        addInfo(card, t("只改客户端下载并行度（大文件分块 512 KiB、并行 8 路）；服务端限速仍在。", "Only changes client download parallelism (512 KiB chunks, 8 parallel requests); server limits still apply."));

        localPremiumSwitch = addSwitch(card, t("本地 Premium 解锁", "Enable local Premium"));
        localPremiumSwitch.setChecked(baseConfig.localPremium);
        setRowDesc(localPremiumSwitch, t("解锁部分官方功能", "Unlocks some official features"));
        addInfo(card, t("仅本地生效：让客户端认为你是 Premium，可突破置顶聊天数等客户端检查（实测为准，服务端仍可能限制）。服务端 Premium 功能（大文件上传、语音转文字等）仍需官方订阅，且开启本地 Premium 有封号风险，请自行决定。", "Local only: makes the client treat you as Premium, unlocking client-side checks such as the pinned-chat limit (verify on device; the server may still limit). Server Premium features still need a real subscription, and local Premium carries a ban risk."));
    }

    private void buildDialogListCard(LinearLayout container) {
        LinearLayout card = addGroup(container, t("聊天列表", "Chat List"));
        addInfo(card, t("只影响聊天列表的外观，不改动聊天内容与置顶本身。", "Only affects the chat list appearance, not chat content or pinning itself."));
        pinnedHighlightSwitch = addSwitch(card, t("置顶聊天区分显示（左侧色条 + 浅色背景）", "Highlight pinned chats (left colour bar + light background)"));
        pinnedHighlightSwitch.setChecked(baseConfig.highlightPinnedDialogs);
        setRowDesc(pinnedHighlightSwitch, t("色条 + 浅色背景", "Color bar + light background"));
    }

    private void buildAiCard(LinearLayout container) {
        LinearLayout card = addGroup(container, t("智能分析（AI）", "Smart Analysis (AI)"));
        addInfo(card, t("默认是本地规则分析：离线、免费、私密、即时，关键词、域名、IP、@、混淆网址、QQ、USDT 地址都能识别。开启大模型后，智能分析会变成可插拔后端：本地先出候选，大模型再补充建议，两者去重后一起让你确认；大模型失败时按下面开关回退本地。", "Default is local rule analysis: offline, free, private, instant, covering keywords, domains, IPs, @handles, obfuscated URLs, QQ and USDT addresses. With the LLM on, smart analysis becomes a pluggable backend: local candidates first, LLM suggestions added and deduplicated, all confirmed by you; on LLM failure it falls back to local if the switch below is on."));
        aiLlmSwitch = addSwitch(card, t("启用大模型智能分析", "Enable LLM smart analysis"));
        aiLlmSwitch.setChecked(baseConfig.aiAnalysisMode == 1);
        setRowDesc(aiLlmSwitch, t("需自备 API Key", "Bring your own API key"));
        addInfo(card, t("只在你点“智能分析并屏蔽”时才调用大模型，不会对每条消息自动过滤。每次只发消息正文/说明/按钮文字（最多 2000 字），不发发送者名、聊天 id、用户 id。消息会发给上面地址的服务方，按该服务方计费，并受其隐私政策约束；Key 只存在本机模块配置里，不进代码仓库，不打进日志。", "Only called when you tap smart analysis, never for automatic per-message filtering. Each call sends only message text / caption / button text (max 2000 chars), no sender name, chat id or user id. Messages go to the service at the base URL above, billed by that provider under its privacy policy; the key lives only in this device's module config, never in the repo, never in logs."));
        aiFallbackSwitch = addSwitch(card, t("大模型失败 / 超时时回退本地规则", "Fall back to local rules when the LLM fails or times out"));
        aiFallbackSwitch.setChecked(baseConfig.aiFallbackToLocal);
        // Build #46: HTML v2 shows the AI settings only while the
        // AI toggle is on (the reference hides #aiSettings).
        LinearLayout aiSettings = new LinearLayout(context);
        aiSettings.setOrientation(LinearLayout.VERTICAL);
        addView(card, aiSettings, 0);
        aiBaseUrlInput = addInput(aiSettings, t("API 地址（OpenAI 兼容）", "API base URL (OpenAI-compatible)"), baseConfig.aiApiBaseUrl == null || baseConfig.aiApiBaseUrl.isBlank() ? "https://api.openai.com/v1" : baseConfig.aiApiBaseUrl);
        EditText[] aiModelKeyInputs = addInputPair(aiSettings,
                t("模型", "Model"), baseConfig.aiApiModel == null || baseConfig.aiApiModel.isBlank() ? "gpt-4o-mini" : baseConfig.aiApiModel,
                t("API Key", "API key"), baseConfig.aiApiKey == null ? "" : baseConfig.aiApiKey);
        aiModelInput = aiModelKeyInputs[0];
        aiKeyInput = aiModelKeyInputs[1];
        try {
            aiModelInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            aiKeyInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        } catch (Throwable ignored) {}
        LinearLayout modelActions = new LinearLayout(context);
        modelActions.setOrientation(LinearLayout.HORIZONTAL);
        modelActions.setGravity(Gravity.CENTER_VERTICAL);
        Button fetchModelsButton = borderedButton(t("获取模型列表", "Fetch models"));
        fetchModelsButton.setOnClickListener(v -> fetchAiModels(fetchModelsButton));
        modelActions.addView(fetchModelsButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView modelHint = new TextView(context);
        modelHint.setText(t("从 API 的 /models 获取，可继续手动填写模型名", "Loads models from the API /models endpoint; manual model names are still supported"));
        modelHint.setTextColor(secondaryTextColor);
        modelHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        modelHint.setPadding(dp(10), 0, 0, 0);
        modelActions.addView(modelHint, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        addView(aiSettings, modelActions, 4);
        aiSettings.setVisibility(aiLlmSwitch.isChecked() ? View.VISIBLE : View.GONE);
        aiLlmSwitch.setOnCheckedChangeListener((button, isChecked) ->
                aiSettings.setVisibility(isChecked ? View.VISIBLE : View.GONE));
        styleBodyDividers(aiSettings);
    }

    private void fetchAiModels(Button button) {
        Activity activity = activityFromContext(context);
        if (activity == null || aiBaseUrlInput == null || aiModelInput == null) {
            Toast.makeText(context, t("无法打开模型列表", "Cannot open model list"), Toast.LENGTH_SHORT).show();
            return;
        }
        FilterConfig request = baseConfig == null ? FilterConfig.createDefault() : baseConfig.deepCopy();
        request.aiApiBaseUrl = valueOf(aiBaseUrlInput).trim();
        request.aiApiKey = aiKeyInput == null ? "" : valueOf(aiKeyInput).trim();
        if (request.aiApiBaseUrl.isEmpty()) {
            Toast.makeText(context, t("请先填写 API 地址", "Enter the API base URL first"), Toast.LENGTH_SHORT).show();
            return;
        }
        button.setEnabled(false);
        button.setText(t("获取中…", "Loading…"));
        new Thread(() -> {
            LlmAiAnalyzer.ModelsResult result = LlmAiAnalyzer.fetchModels(request);
            activity.runOnUiThread(() -> {
                button.setEnabled(true);
                button.setText(t("获取模型列表", "Fetch models"));
                if (activity.isFinishing()) {
                    return;
                }
                if (result == null || result.modelIds.isEmpty()) {
                    String suffix = result == null || result.error == null || result.error.isBlank()
                            ? "" : "：" + result.error;
                    Toast.makeText(context, t("未获取到模型" + suffix, "No models returned" + suffix), Toast.LENGTH_LONG).show();
                    return;
                }
                String current = valueOf(aiModelInput).trim();
                int[] selected = new int[]{Math.max(0, result.modelIds.indexOf(current))};
                AlertDialog picker = new AlertDialog.Builder(activity)
                        .setTitle(t("选择模型", "Choose model"))
                        .setSingleChoiceItems(
                                singleChoiceAdapter(result.modelIds.toArray(new String[0])),
                                selected[0],
                                (dialog, which) -> selected[0] = which
                        )
                        .setNegativeButton(t("取消", "Cancel"), null)
                        .setPositiveButton(t("使用", "Use"), (dialog, which) -> {
                            if (selected[0] >= 0 && selected[0] < result.modelIds.size()) {
                                aiModelInput.setText(result.modelIds.get(selected[0]));
                                aiModelInput.setSelection(aiModelInput.length());
                            }
                        })
                        .create();
                picker.show();
                DialogTheme.styleAlertDialog(picker, context);
            });
        }, "gramsieve-ai-models").start();
    }

    private void buildProxyCard(LinearLayout container) {
        LinearLayout card = addGroup(container, t("代理（TG 直链）", "Proxy (TG Links)"));
        addInfo(card, t("自动抓取、测速、自动添加并切换到最快代理：模块按刷新间隔扫描下面这个文件夹里的聊天（消息里含 t.me/proxy 链接即算），测速后把最快的设为当前代理，不再从外部来源地址或粘贴链接抓取。公开代理的运营者能看到你的 IP 和 Telegram 连接时间信息，但 MTProto 内容本身是加密的。", "Auto-fetch, test, auto-add and switch to the fastest proxy: on the refresh interval the module scans chats in the folder below (messages containing t.me/proxy links count), tests them, and sets the fastest as current. No external source URL or pasted links are fetched anymore. A proxy operator can see your IP and Telegram connection timing, but MTProto content stays encrypted."));
        boolean firstOpen = !baseConfig.proxyConfigured;
        proxyAutoAddSwitch = addSwitch(card, t("自动添加新代理", "Auto-add new proxies"));
        proxyAutoAddSwitch.setChecked(firstOpen || baseConfig.proxyAutoAdd);
        proxyAutoSwitchSwitch = addSwitch(card, t("自动切换到最快代理", "Auto-switch to the fastest proxy"));
        proxyAutoSwitchSwitch.setChecked(firstOpen || baseConfig.proxyAutoSwitch);
        int refreshMinutes = firstOpen ? 360
                : (baseConfig.proxyRefreshMinutes >= 0 ? baseConfig.proxyRefreshMinutes
                : Math.max(1, baseConfig.proxyRefreshHours) * 60);
        EditText[] proxyIntervalInputs = addInputPair(card,
                t("刷新间隔（分钟，默认 360）", "Refresh interval (minutes, default 360)"), Integer.toString(refreshMinutes),
                t("失效时间（小时，默认 24）", "Expiry time (hours, default 24)"), Integer.toString(firstOpen ? 24 : baseConfig.proxyExpiryHours));
        proxyRefreshInput = proxyIntervalInputs[0];
        proxyExpiryInput = proxyIntervalInputs[1];
        // Build #46: HTML v2 folder row: label + [field + link].
        TextView proxyFolderLabel = new TextView(context);
        proxyFolderLabel.setTag("gramsieve_row_label");
        proxyFolderLabel.setText(t("代理文件夹（只扫这个文件夹里的聊天，默认 节点）", "Proxy folder (only scan chats in this folder, default 节点)"));
        proxyFolderLabel.setTextColor(secondaryTextColor);
        proxyFolderLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        addView(card, proxyFolderLabel, 6);
        LinearLayout proxyFolderRow = new LinearLayout(context);
        proxyFolderRow.setOrientation(LinearLayout.HORIZONTAL);
        proxyFolderRow.setGravity(Gravity.CENTER_VERTICAL);
        proxyFolderInput = new EditText(context);
        proxyFolderInput.setText(firstOpen ? "节点" : (baseConfig.proxyFolderName == null ? "节点" : baseConfig.proxyFolderName));
        proxyFolderInput.setTextColor(primaryTextColor);
        proxyFolderInput.setHintTextColor(secondaryTextColor);
        proxyFolderInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        try {
            proxyFolderInput.setBackground(rounded(adjustAlpha(primaryTextColor, 0.04f), dp(8), strokeColor));
            proxyFolderInput.setPadding(dp(12), dp(8), dp(12), dp(8));
        } catch (Throwable ignored) {}
        proxyFolderInput.setMinLines(1);
        proxyFolderInput.setMaxLines(1);
        proxyFolderRow.addView(proxyFolderInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button proxyFolderPickButton = toolbarButton(t("选择文件夹", "Pick folder"));
        proxyFolderPickButton.setOnClickListener(v -> showProxyFolderPicker());
        proxyFolderRow.addView(proxyFolderPickButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(card, proxyFolderRow, 0);
        addInfo(card, t("自动维护：只要 Conso 在运行，“自动添加”开着时，每到刷新间隔就会自动跑一轮：扫描代理文件夹、测速、切到最快、把测通的同步进 Conso 代理列表，并把本轮测死的（且是模块同步进去的）从客户端列表里剔除；Conso 被彻底关掉后，下次打开聊天列表时会补跑一次。", "Auto-maintenance: while Conso is running and auto-add is on, every refresh interval runs a full round: scan the proxy folder, test, switch to fastest, sync tested-good entries into Conso's proxy list, and prune entries this round tested dead (and synced by the module) from the client list. If Conso was fully closed, the next chat-list open catches up once."));
        // Build #28: folder picker instead of typing. Tapping the
        // field (or the button below) lists existing user folders and
        // fills the field with the picked one, so a typo or a renamed
        // folder cannot silently make the scan find nothing.
        try {
            proxyFolderInput.setFocusable(false);
            proxyFolderInput.setFocusableInTouchMode(false);
            proxyFolderInput.setOnClickListener(v -> showProxyFolderPicker());
        } catch (Throwable ignored) {}
        addInfo(card, t("文件夹来源：刷新时会读取上面这个文件夹里各聊天最近的消息，提取其中的 t.me/proxy 链接；只扫这一个文件夹，不扫其他文件夹。文件夹不存在时，只用网址和粘贴来源，不会报错。", "Folder source: refresh reads recent messages from chats in the folder above and extracts t.me/proxy links; only this folder is scanned, not others. If the folder does not exist, only the URL and pasted sources are used, without error."));
        proxyWorkingEntries = new java.util.ArrayList<>();
        if (baseConfig.proxyEntries != null) {
            for (FilterConfig.ProxyEntry entry : baseConfig.proxyEntries) {
                if (entry != null) proxyWorkingEntries.add(entry.deepCopy());
            }
        }
        proxySummary = new TextView(context);
        proxySummary.setTextColor(secondaryTextColor);
        proxySummary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        proxySummary.setBackgroundColor(cardColor);
        proxySummary.setPadding(dp(12), dp(8), dp(12), dp(8));
        addView(card, proxySummary, 0);
        updateProxySummary();
        LinearLayout buttonRow = new LinearLayout(context);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setPadding(dp(12), dp(12), dp(12), dp(12));
        Button refreshButton = secondaryButton(t("立即刷新", "Refresh now"));
        refreshButton.setOnClickListener(v -> {
            refreshButton.setEnabled(false);
            refreshButton.setText(t("刷新中…", "Refreshing…"));
            FilterConfig temp = baseConfig.deepCopy().sanitize();
            applyProxyUiToConfig(temp);
            new Thread(() -> {
                TgProxyManager.Result result = TgProxyManager.refresh(context, temp);
                root.post(() -> {
                    try {
                        FilterConfig saved = result.updated == null ? temp : result.updated;
                        saver.save(saved);
                        proxyWorkingEntries = new java.util.ArrayList<>();
                        if (saved.proxyEntries != null) {
                            for (FilterConfig.ProxyEntry entry : saved.proxyEntries) {
                                if (entry != null) proxyWorkingEntries.add(entry.deepCopy());
                            }
                        }
                        lastProxyFolderDiag = result.folderDiag == null ? "" : result.folderDiag;
                        updateProxySummary();
                        String msg = result.error != null && !result.error.isEmpty() && result.fetched == 0
                                ? t("刷新失败（来源不可用），已保留旧列表", "Refresh failed (source unavailable), old list kept")
                                : t("刷新完成：抓到 " + result.fetched + " 个（文件夹 " + result.folderFetched + " 个），测通 " + result.availableCount + " 个，新进 " + result.imported + " 个" + (result.addedToHost > 0 ? "，已同步到客户端 " + result.addedToHost + " 个" : "") + (result.prunedFromHost > 0 ? "，已剔除客户端失效 " + result.prunedFromHost + " 个" : "") + (result.switched && result.fastest != null ? "，已切到最快 " + result.fastest.display() + "（" + result.fastest.pingMs + " ms）" : "") + (result.folderFetched == 0 && result.folderDiag != null && !result.folderDiag.isEmpty() ? "；" + result.folderDiag : ""), "Refresh done: fetched " + result.fetched + " (folder " + result.folderFetched + "), available " + result.availableCount + ", newly imported " + result.imported + (result.addedToHost > 0 ? ", synced to client " + result.addedToHost : "") + (result.prunedFromHost > 0 ? ", pruned dead from client " + result.prunedFromHost : "") + (result.switched && result.fastest != null ? ", switched to fastest " + result.fastest.display() + " (" + result.fastest.pingMs + " ms)" : "") + (result.folderFetched == 0 && result.folderDiag != null && !result.folderDiag.isEmpty() ? "; " + result.folderDiag : ""));
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show();
                    } catch (Throwable ignored) {
                    } finally {
                        refreshButton.setEnabled(true);
                        refreshButton.setText(t("立即刷新", "Refresh now"));
                    }
                });
            }).start();
        });
        LinearLayout.LayoutParams refreshParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        refreshParams.rightMargin = dp(5);
        buttonRow.addView(refreshButton, refreshParams);
        Button manageButton = primaryButton(t("管理代理", "Manage proxies"));
        manageButton.setOnClickListener(v -> showProxyManageDialog());
        LinearLayout.LayoutParams manageParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        manageParams.leftMargin = dp(5);
        buttonRow.addView(manageButton, manageParams);
        addView(card, buttonRow, 0);
    }

    private void buildLogExportCard(LinearLayout container) {
        LinearLayout card = addGroup(container, t("日志导出", "Export log"));
        addInfo(card, t("导出最近的日志（最多约 2000 行），点“导出日志”后选“导出为文件”，会保存到下载目录，成功后弹框里会写明文件名和位置；也可以点“复制”或“分享”发文字版。日志里不含大模型 Key，发给排查时用。", "Export the recent log (up to about 2000 lines). Tap Export log, then Export as file to save it as a .log file under Download/GramSieve and share it; or tap Copy or Share for the text version. The log does not contain the LLM key."));
        LinearLayout buttonRow = new LinearLayout(context);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        Button exportLogButton = toolbarButton(t("导出日志", "Export log"));
        exportLogButton.setOnClickListener(v -> showExportLogDialog());
        buttonRow.addView(exportLogButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(card, buttonRow, 4);
    }

    private void buildBackupCard(LinearLayout container) {
        LinearLayout card = addGroup(container, t("数据备份", "Data backup"));
        addInfo(card, t("“导出规则 / 导入规则”只管规则，导入时合并，不会改动其他设置，已有的相同规则不会重复。“导出完整备份 / 导入完整备份”管全部设置：开关、规则、分类、翻译、代理列表、大模型地址 / 模型 / Key、文件夹设置，一起备份和恢复；导入完整备份会替换当前全部设置，不是合并。完整备份里有你的大模型 Key 和代理信息，不要发给别人，只存到你自己的地方。", "Export / Import rules only handles rules, merged on import without changing other settings, and identical rules are not duplicated. Export / Import full backup covers ALL settings: switches, rules, classify, translate, proxy list, LLM base URL / model / key, folder settings, backed up and restored together; importing a full backup REPLACES all current settings, it does not merge. A full backup contains your LLM key and proxy info, do not send it to anyone, keep it in your own place."));
        // Build #46: HTML v2 btn-group-3: gray buttons in a grid,
        // 2 columns on narrow screens, 3 on wide ones.
        java.util.ArrayList<Button> backupButtons = new java.util.ArrayList<>();
        Button exportButton = secondaryButton(t("导出规则", "Export rules"));
        exportButton.setOnClickListener(v -> showExportRulesDialog());
        backupButtons.add(exportButton);
        Button importButton = secondaryButton(t("导入规则", "Import rules"));
        importButton.setOnClickListener(v -> showImportRulesDialog());
        backupButtons.add(importButton);
        Button exportFullButton = secondaryButton(t("导出完整备份", "Export full backup"));
        exportFullButton.setOnClickListener(v -> showExportFullDialog());
        backupButtons.add(exportFullButton);
        Button importFullButton = secondaryButton(t("导入完整备份", "Import full backup"));
        importFullButton.setOnClickListener(v -> showImportFullDialog());
        backupButtons.add(importFullButton);
        Button exportLogButton = secondaryButton(t("导出日志", "Export log"));
        exportLogButton.setOnClickListener(v -> showExportLogDialog());
        backupButtons.add(exportLogButton);
        int btnCols = Math.max(2, gridCols(3));
        LinearLayout btnContainer = new LinearLayout(context);
        btnContainer.setOrientation(LinearLayout.VERTICAL);
        btnContainer.setPadding(dp(12), dp(12), dp(12), dp(12));
        for (int start = 0; start < backupButtons.size(); start += btnCols) {
            LinearLayout btnRow = new LinearLayout(context);
            btnRow.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < btnCols; c++) {
                int idx = start + c;
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                if (c > 0) {
                    p.leftMargin = dp(5);
                }
                if (c < btnCols - 1) {
                    p.rightMargin = dp(5);
                }
                if (idx < backupButtons.size()) {
                    btnRow.addView(backupButtons.get(idx), p);
                } else {
                    btnRow.addView(new View(context), p);
                }
            }
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (start + btnCols < backupButtons.size()) {
                rowParams.bottomMargin = dp(10);
            }
            btnContainer.addView(btnRow, rowParams);
        }
        addView(card, btnContainer, 0);
        addInfo(card, t("导出日志会取最近的日志（最多约 2000 行），里面有代理文件夹扫描的诊断（例如文件夹里几个聊天、历史成功几个、消息几条），发给排查时用；日志里不含大模型 Key。", "Export log takes the recent log (up to about 2000 lines), including the proxy folder scan diagnosis (for example how many chats in the folder, how many histories succeeded, how many messages); it does not contain the LLM key."));
    }

    private String rulesExportJson() {
        try {
            FilterConfig export = new FilterConfig();
            FilterConfig source = baseConfig == null ? FilterConfig.createDefault() : baseConfig.deepCopy();
            export.globalRules = new java.util.ArrayList<>();
            if (source.globalRules != null) {
                for (FilterConfig.RuleSpec rule : source.globalRules) {
                    if (rule != null) export.globalRules.add(rule.deepCopy());
                }
            }
            export.globalExclusions = new java.util.ArrayList<>();
            if (source.globalExclusions != null) {
                for (FilterConfig.RuleSpec rule : source.globalExclusions) {
                    if (rule != null) export.globalExclusions.add(rule.deepCopy());
                }
            }
            export.chatRules = new java.util.LinkedHashMap<>();
            if (source.chatRules != null) {
                for (java.util.Map.Entry<String, FilterConfig.ChatRuleSet> entry : source.chatRules.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        export.chatRules.put(entry.getKey(), entry.getValue().deepCopy());
                    }
                }
            }
            return ModuleConfigStore.toJson(export);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private void showExportRulesDialog() {
        try {
            android.app.Activity activity = activityFromContext(context);
            if (activity == null || activity.isFinishing()) {
                Toast.makeText(context, t("无法打开导出界面", "Cannot open export dialog"), Toast.LENGTH_SHORT).show();
                return;
            }
            String json = rulesExportJson();
            if (json == null || json.isEmpty()) {
                Toast.makeText(context, t("导出失败", "Export failed"), Toast.LENGTH_SHORT).show();
                return;
            }
            LinearLayout dialogView = new LinearLayout(context);
            dialogView.setOrientation(LinearLayout.VERTICAL);
            dialogView.setPadding(dp(16), dp(16), dp(16), dp(16)); // Build #49: spacing token L
            // Build #34: dialogView stays transparent; the glass card is
            // the dialog window background set by styleAlertDialog after show(),
            // so there is exactly one card edge, not two stacked ones.
            dialogView.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            TextView title = new TextView(context);
            title.setText(t("导出规则", "Export rules"));
            title.setTextColor(primaryTextColor);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
            title.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
            dialogView.addView(title, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView hint = new TextView(context);
            hint.setText(t("下面就是全部规则的备份文本，点“复制”后粘贴到你自己的笔记或文件里保存；也可以点“导出为文件”存到下载目录，或点“分享”发给自己。", "Below is the full rules backup text. Tap Copy, then paste it into your own notes or a file to keep it; or tap Export as file to save it to Download, or Share to send it to yourself."));
            hint.setTextColor(secondaryTextColor);
            hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            dialogView.addView(hint, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            EditText jsonView = new EditText(context);
            jsonView.setText(json);
            jsonView.setTextColor(primaryTextColor);
            jsonView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            jsonView.setTextIsSelectable(true);
            jsonView.setBackground(rounded(adjustAlpha(primaryTextColor, 0.07f), dp(8), strokeColor));
            android.widget.ScrollView scroll = new android.widget.ScrollView(context);
            scroll.addView(jsonView, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(240)));
            dialogView.addView(scroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(activity)
                    .setView(dialogView)
                    .create();
            LinearLayout buttonRow = new LinearLayout(context);
            buttonRow.setOrientation(LinearLayout.HORIZONTAL);
            Button copyButton = toolbarButton(t("复制", "Copy"));
            copyButton.setOnClickListener(v -> {
                try {
                    ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText("GramSieve Conso rules", json));
                        Toast.makeText(context, t("已复制，请粘贴到笔记或文件里保存", "Copied, paste it into notes or a file to keep it"), Toast.LENGTH_SHORT).show();
                    }
                } catch (Throwable ignored) {}
            });
            buttonRow.addView(copyButton);
            Button shareButton = toolbarButton(t("分享", "Share"));
            shareButton.setOnClickListener(v -> {
                try {
                    Intent share = new Intent(Intent.ACTION_SEND);
                    share.setType("text/plain");
                    share.putExtra(Intent.EXTRA_TEXT, json);
                    context.startActivity(Intent.createChooser(share, t("分享规则备份", "Share rules backup")));
                } catch (Throwable ignored) {}
            });
            buttonRow.addView(shareButton);
            Button fileButton = toolbarButton(t("导出为文件", "Export as file"));
            fileButton.setOnClickListener(v -> exportLogToFile(json, timestampedFileName("gramsieve-rules-", ".json"), "rules"));
            buttonRow.addView(fileButton);
            Button closeButton = toolbarButton(t("关闭", "Close"));
            closeButton.setOnClickListener(v -> dialog.dismiss());
            buttonRow.addView(closeButton);
            dialogView.addView(buttonRow, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            dialog.show();
            DialogTheme.styleAlertDialog(dialog, context);
            DialogTheme.ensureCardWindow(dialog, DialogTheme.resolve(context));
        } catch (Throwable throwable) {
            Toast.makeText(context, t("导出失败", "Export failed"), Toast.LENGTH_SHORT).show();
        }
    }

    private static String ruleMergeKey(FilterConfig.RuleSpec rule) {
        if (rule == null) return "";
        return rule.mode + "|" + rule.target + "|" + (rule.pattern == null ? "" : rule.pattern) + "|" + rule.caseSensitive;
    }

    private void showImportRulesDialog() {
        try {
            android.app.Activity activity = activityFromContext(context);
            if (activity == null || activity.isFinishing()) {
                Toast.makeText(context, t("无法打开导入界面", "Cannot open import dialog"), Toast.LENGTH_SHORT).show();
                return;
            }
            LinearLayout dialogView = new LinearLayout(context);
            dialogView.setOrientation(LinearLayout.VERTICAL);
            dialogView.setPadding(dp(16), dp(16), dp(16), dp(16)); // Build #49: spacing token L
            // Build #34: dialogView stays transparent; the glass card is
            // the dialog window background set by styleAlertDialog after show(),
            // so there is exactly one card edge, not two stacked ones.
            dialogView.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            TextView title = new TextView(context);
            title.setText(t("导入规则", "Import rules"));
            title.setTextColor(primaryTextColor);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
            title.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
            dialogView.addView(title, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView hint = new TextView(context);
            hint.setText(t("粘贴之前导出的规则备份文本，点导入后会合并到现有规则里，相同的规则不会重复；也可以点“从文件导入”选一个备份文件，内容会读进上面的框，再点导入。导入后面板会关闭，如需继续编辑请重新打开设置。", "Paste a previously exported rules backup, then tap Import to merge it into the existing rules; identical rules are not duplicated. Or tap Import from file to pick a backup file: its content is read into the box above, then tap Import. The panel closes after import; reopen Settings to edit further."));
            hint.setTextColor(secondaryTextColor);
            hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            dialogView.addView(hint, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            EditText input = new EditText(context);
            input.setHint(t("粘贴规则备份 JSON", "Paste rules backup JSON"));
            input.setTextColor(primaryTextColor);
            input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            input.setBackground(rounded(adjustAlpha(primaryTextColor, 0.07f), dp(8), strokeColor));
            input.setMinLines(6);
            android.widget.ScrollView scroll = new android.widget.ScrollView(context);
            scroll.addView(input, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(240)));
            dialogView.addView(scroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(activity)
                    .setView(dialogView)
                    .create();
            LinearLayout buttonRow = new LinearLayout(context);
            buttonRow.setOrientation(LinearLayout.HORIZONTAL);
            Button importButton = toolbarButton(t("导入", "Import"));
            importButton.setOnClickListener(v -> {
                try {
                    String pasted = valueOf(input);
                    if (pasted == null || pasted.isBlank() || !pasted.contains("globalRules")) {
                        Toast.makeText(context, t("不是有效的规则备份（里面应该有 globalRules）", "Not a valid rules backup (it should contain globalRules)"), Toast.LENGTH_LONG).show();
                        return;
                    }
                    FilterConfig imported = ModuleConfigStore.fromJson(pasted);
                    if (imported == null) {
                        Toast.makeText(context, t("导入失败：无法解析", "Import failed: cannot parse"), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    FilterConfig updated = (baseConfig == null ? FilterConfig.createDefault() : baseConfig.deepCopy()).sanitize();
                    int addedGlobal = 0;
                    int addedKeep = 0;
                    int addedChat = 0;
                    java.util.Set<String> existingGlobal = new java.util.HashSet<>();
                    if (updated.globalRules != null) {
                        for (FilterConfig.RuleSpec rule : updated.globalRules) existingGlobal.add(ruleMergeKey(rule));
                    } else {
                        updated.globalRules = new java.util.ArrayList<>();
                    }
                    if (imported.globalRules != null) {
                        for (FilterConfig.RuleSpec rule : imported.globalRules) {
                            if (rule == null) continue;
                            FilterConfig.RuleSpec clean = rule.sanitize();
                            if (clean.pattern.isBlank()) continue;
                            if (existingGlobal.add(ruleMergeKey(clean))) {
                                updated.globalRules.add(clean.deepCopy());
                                addedGlobal++;
                            }
                        }
                    }
                    java.util.Set<String> existingKeep = new java.util.HashSet<>();
                    if (updated.globalExclusions != null) {
                        for (FilterConfig.RuleSpec rule : updated.globalExclusions) existingKeep.add(ruleMergeKey(rule));
                    } else {
                        updated.globalExclusions = new java.util.ArrayList<>();
                    }
                    if (imported.globalExclusions != null) {
                        for (FilterConfig.RuleSpec rule : imported.globalExclusions) {
                            if (rule == null) continue;
                            FilterConfig.RuleSpec clean = rule.sanitize();
                            if (clean.pattern.isBlank()) continue;
                            if (existingKeep.add(ruleMergeKey(clean))) {
                                updated.globalExclusions.add(clean.deepCopy());
                                addedKeep++;
                            }
                        }
                    }
                    if (updated.chatRules == null) updated.chatRules = new java.util.LinkedHashMap<>();
                    if (imported.chatRules != null) {
                        for (java.util.Map.Entry<String, FilterConfig.ChatRuleSet> entry : imported.chatRules.entrySet()) {
                            if (entry.getKey() == null || entry.getValue() == null) continue;
                            FilterConfig.ChatRuleSet target = updated.chatRules.get(entry.getKey());
                            if (target == null) {
                                updated.chatRules.put(entry.getKey(), entry.getValue().deepCopy().sanitize());
                                addedChat += entry.getValue().rules.size() + entry.getValue().exclusions.size();
                                continue;
                            }
                            java.util.Set<String> existingRules = new java.util.HashSet<>();
                            for (FilterConfig.RuleSpec rule : target.rules) existingRules.add(ruleMergeKey(rule));
                            for (FilterConfig.RuleSpec rule : entry.getValue().rules) {
                                if (rule == null) continue;
                                FilterConfig.RuleSpec clean = rule.sanitize();
                                if (clean.pattern.isBlank()) continue;
                                if (existingRules.add(ruleMergeKey(clean))) {
                                    target.rules.add(clean.deepCopy());
                                    addedChat++;
                                }
                            }
                            java.util.Set<String> existingExclusions = new java.util.HashSet<>();
                            for (FilterConfig.RuleSpec rule : target.exclusions) existingExclusions.add(ruleMergeKey(rule));
                            for (FilterConfig.RuleSpec rule : entry.getValue().exclusions) {
                                if (rule == null) continue;
                                FilterConfig.RuleSpec clean = rule.sanitize();
                                if (clean.pattern.isBlank()) continue;
                                if (existingExclusions.add(ruleMergeKey(clean))) {
                                    target.exclusions.add(clean.deepCopy());
                                    addedChat++;
                                }
                            }
                        }
                    }
                    if (addedGlobal == 0 && addedKeep == 0 && addedChat == 0) {
                        Toast.makeText(context, t("备份里没有新规则（都已存在）", "No new rules in the backup (all already exist)"), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    updated.updatedAtEpochMs = System.currentTimeMillis();
                    saver.save(updated);
                    if (afterSave != null) afterSave.run();
                    dialog.dismiss();
                    Toast.makeText(context, t("已导入：全局 " + addedGlobal + " 条，保留 " + addedKeep + " 条，单聊 " + addedChat + " 条，面板已关闭", "Imported: global " + addedGlobal + ", keep " + addedKeep + ", per-chat " + addedChat + ", panel closed"), Toast.LENGTH_LONG).show();
                    close();
                } catch (Throwable throwable) {
                    Toast.makeText(context, t("导入失败：请检查粘贴的内容", "Import failed: check the pasted content"), Toast.LENGTH_SHORT).show();
                }
            });
            buttonRow.addView(importButton);
            Button fileButton = toolbarButton(t("从文件导入", "Import from file"));
            fileButton.setOnClickListener(v -> {
                try {
                    if (!ImportFilePicker.launch(activity, text -> {
                        if (text != null && !text.isEmpty()) {
                            input.setText(text);
                        }
                    })) {
                        Toast.makeText(context, t("无法打开文件选择器，请用粘贴方式", "Cannot open the file picker, paste instead"), Toast.LENGTH_SHORT).show();
                    }
                } catch (Throwable ignored) {
                    Toast.makeText(context, t("无法打开文件选择器，请用粘贴方式", "Cannot open the file picker, paste instead"), Toast.LENGTH_SHORT).show();
                }
            });
            buttonRow.addView(fileButton);
            Button cancelButton = toolbarButton(t("取消", "Cancel"));
            cancelButton.setOnClickListener(v -> dialog.dismiss());
            buttonRow.addView(cancelButton);
            dialogView.addView(buttonRow, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            dialog.show();
            DialogTheme.styleAlertDialog(dialog, context);
            DialogTheme.ensureCardWindow(dialog, DialogTheme.resolve(context));
        } catch (Throwable throwable) {
            Toast.makeText(context, t("导入失败", "Import failed"), Toast.LENGTH_SHORT).show();
        }
    }

    private void showCopyShareDialog(String titleText, String hintText, String content,
            String clipLabel, String shareTitle) {
        showCopyShareDialogInternal(titleText, hintText, content, clipLabel, shareTitle, null, null, null);
    }

    private void showCopyShareDialogInternal(String titleText, String hintText, String content,
            String clipLabel, String shareTitle, String fileName, String fileContent, String fileKind) {
        try {
            android.app.Activity activity = activityFromContext(context);
            if (activity == null || activity.isFinishing()) {
                Toast.makeText(context, t("无法打开导出界面", "Cannot open export dialog"), Toast.LENGTH_SHORT).show();
                return;
            }
            LinearLayout dialogView = new LinearLayout(context);
            dialogView.setOrientation(LinearLayout.VERTICAL);
            dialogView.setPadding(dp(16), dp(16), dp(16), dp(16)); // Build #49: spacing token L
            // Build #34: dialogView stays transparent; the glass card is
            // the dialog window background set by styleAlertDialog after show(),
            // so there is exactly one card edge, not two stacked ones.
            dialogView.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            TextView title = new TextView(context);
            title.setText(titleText);
            title.setTextColor(primaryTextColor);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
            title.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
            dialogView.addView(title, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView hint = new TextView(context);
            hint.setText(hintText);
            hint.setTextColor(secondaryTextColor);
            hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            dialogView.addView(hint, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            EditText contentView = new EditText(context);
            contentView.setText(content);
            contentView.setTextColor(primaryTextColor);
            contentView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            contentView.setTextIsSelectable(true);
            contentView.setBackground(rounded(adjustAlpha(primaryTextColor, 0.07f), dp(8), strokeColor));
            // Build #38: scroll area takes remaining space (weight 1)
            // so the button row below always stays on screen, no
            // matter how long the log is; the log scrolls inside.
            android.widget.ScrollView scroll = new android.widget.ScrollView(context);
            scroll.addView(contentView, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            dialogView.addView(scroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(activity)
                    .setView(dialogView)
                    .create();
            LinearLayout buttonRow = new LinearLayout(context);
            buttonRow.setOrientation(LinearLayout.HORIZONTAL);
            Button copyButton = toolbarButton(t("复制", "Copy"));
            copyButton.setOnClickListener(v -> {
                try {
                    ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText(clipLabel, content));
                        Toast.makeText(context, t("已复制，请粘贴到笔记或文件里保存", "Copied, paste it into notes or a file to keep it"), Toast.LENGTH_SHORT).show();
                    }
                } catch (Throwable ignored) {}
            });
            buttonRow.addView(copyButton);
            Button shareButton = toolbarButton(t("分享", "Share"));
            shareButton.setOnClickListener(v -> {
                try {
                    Intent share = new Intent(Intent.ACTION_SEND);
                    share.setType("text/plain");
                    share.putExtra(Intent.EXTRA_TEXT, content);
                    context.startActivity(Intent.createChooser(share, shareTitle));
                } catch (Throwable ignored) {}
            });
            buttonRow.addView(shareButton);
            if (fileContent != null && !fileContent.isEmpty()) {
                Button fileButton = toolbarButton(t("导出为文件", "Export as file"));
                fileButton.setOnClickListener(v -> exportLogToFile(fileContent, fileName, fileKind));
                buttonRow.addView(fileButton);
            }
            Button closeButton = toolbarButton(t("关闭", "Close"));
            closeButton.setOnClickListener(v -> dialog.dismiss());
            buttonRow.addView(closeButton);
            dialogView.addView(buttonRow, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            dialog.show();
            DialogTheme.styleAlertDialog(dialog, context);
            DialogTheme.ensureCardWindow(dialog, DialogTheme.resolve(context));
        } catch (Throwable throwable) {
            Toast.makeText(context, t("导出失败", "Export failed"), Toast.LENGTH_SHORT).show();
        }
    }

    private String fullExportJson() {
        try {
            FilterConfig source = baseConfig == null ? FilterConfig.createDefault() : baseConfig.deepCopy();
            return "{\"fullBackup\":true,\"config\":" + ModuleConfigStore.toJson(source) + "}";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private void showExportFullDialog() {
        String json = fullExportJson();
        if (json == null || json.isEmpty()) {
            Toast.makeText(context, t("导出失败", "Export failed"), Toast.LENGTH_SHORT).show();
            return;
        }
        showCopyShareDialogInternal(
                t("导出完整备份", "Export full backup"),
                t("下面是全部设置的完整备份，点“复制”后粘贴到你自己的笔记或文件里保存；也可以点“导出为文件”存到下载目录，或点“分享”发给自己。注意：里面有你的大模型 Key 和代理信息，不要发给别人，导出为文件也一样。", "Below is the full backup of all settings. Tap Copy, then paste it into your own notes or a file to keep it; or tap Export as file to save it to Download, or Share to send it to yourself. Note: it contains your LLM key and proxy info, do not send it to anyone, including as a file."),
                json, "GramSieve Conso full backup", t("分享完整备份", "Share full backup"),
                timestampedFileName("gramsieve-backup-", ".json"), json, "backup");
    }

    private static String extractFullConfigJson(String pasted) {
        if (pasted == null || !pasted.contains("\"fullBackup\"")) {
            return null;
        }
        int configIdx = pasted.indexOf("\"config\"");
        if (configIdx < 0) {
            return null;
        }
        int braceIdx = pasted.indexOf('{', configIdx);
        if (braceIdx < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = braceIdx; i < pasted.length(); i++) {
            char c = pasted.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
            } else {
                if (c == '"') {
                    inString = true;
                } else if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        return pasted.substring(braceIdx, i + 1);
                    }
                }
            }
        }
        return null;
    }

    private void showImportFullDialog() {
        try {
            android.app.Activity activity = activityFromContext(context);
            if (activity == null || activity.isFinishing()) {
                Toast.makeText(context, t("无法打开导入界面", "Cannot open import dialog"), Toast.LENGTH_SHORT).show();
                return;
            }
            LinearLayout dialogView = new LinearLayout(context);
            dialogView.setOrientation(LinearLayout.VERTICAL);
            dialogView.setPadding(dp(16), dp(16), dp(16), dp(16)); // Build #49: spacing token L
            // Build #34: dialogView stays transparent; the glass card is
            // the dialog window background set by styleAlertDialog after show(),
            // so there is exactly one card edge, not two stacked ones.
            dialogView.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            TextView title = new TextView(context);
            title.setText(t("导入完整备份", "Import full backup"));
            title.setTextColor(primaryTextColor);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
            title.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
            dialogView.addView(title, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView hint = new TextView(context);
            hint.setText(t("粘贴之前导出的完整备份文本，也可以点“从文件导入”选文件读进上面的框，再点导入；点导入后会替换当前全部设置（开关、规则、分类、翻译、代理、大模型 Key），不是合并；导入后面板会关闭，如需继续编辑请重新打开设置。只认完整备份，规则备份请走“导入规则”。", "Paste a previously exported full backup, then tap Import to REPLACE all current settings (switches, rules, classify, translate, proxies, LLM key), not merge; the panel closes after import, reopen Settings to edit further. Only full backups are accepted here, use Import rules for a rules backup."));
            hint.setTextColor(secondaryTextColor);
            hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            dialogView.addView(hint, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            EditText input = new EditText(context);
            input.setHint(t("粘贴完整备份文本", "Paste full backup text"));
            input.setTextColor(primaryTextColor);
            input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            input.setBackground(rounded(adjustAlpha(primaryTextColor, 0.07f), dp(8), strokeColor));
            android.widget.ScrollView scroll = new android.widget.ScrollView(context);
            scroll.addView(input, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(240)));
            dialogView.addView(scroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(activity)
                    .setView(dialogView)
                    .create();
            LinearLayout buttonRow = new LinearLayout(context);
            buttonRow.setOrientation(LinearLayout.HORIZONTAL);
            Button importButton = toolbarButton(t("导入", "Import"));
            importButton.setOnClickListener(v -> {
                try {
                    String pasted = valueOf(input);
                    String configJson = extractFullConfigJson(pasted);
                    if (configJson == null) {
                        Toast.makeText(context, t("不是有效的完整备份（里面应该有 fullBackup 和 config）", "Not a valid full backup (it should contain fullBackup and config)"), Toast.LENGTH_LONG).show();
                        return;
                    }
                    FilterConfig imported = ModuleConfigStore.fromJson(configJson);
                    if (imported == null) {
                        Toast.makeText(context, t("导入失败：无法解析", "Import failed: cannot parse"), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    imported.updatedAtEpochMs = System.currentTimeMillis();
                    saver.save(imported);
                    if (afterSave != null) afterSave.run();
                    dialog.dismiss();
                    Toast.makeText(context, t("完整备份已恢复，全部设置已替换，面板已关闭", "Full backup restored, all settings replaced, panel closed"), Toast.LENGTH_LONG).show();
                    close();
                } catch (Throwable throwable) {
                    Toast.makeText(context, t("导入失败：请检查粘贴的内容", "Import failed: check the pasted content"), Toast.LENGTH_SHORT).show();
                }
            });
            buttonRow.addView(importButton);
            Button fileButton = toolbarButton(t("从文件导入", "Import from file"));
            fileButton.setOnClickListener(v -> {
                try {
                    if (!ImportFilePicker.launch(activity, text -> {
                        if (text != null && !text.isEmpty()) {
                            input.setText(text);
                        }
                    })) {
                        Toast.makeText(context, t("无法打开文件选择器，请用粘贴方式", "Cannot open the file picker, paste instead"), Toast.LENGTH_SHORT).show();
                    }
                } catch (Throwable ignored) {
                    Toast.makeText(context, t("无法打开文件选择器，请用粘贴方式", "Cannot open the file picker, paste instead"), Toast.LENGTH_SHORT).show();
                }
            });
            buttonRow.addView(fileButton);
            Button cancelButton = toolbarButton(t("取消", "Cancel"));
            cancelButton.setOnClickListener(v -> dialog.dismiss());
            buttonRow.addView(cancelButton);
            dialogView.addView(buttonRow, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            dialog.show();
            DialogTheme.styleAlertDialog(dialog, context);
            DialogTheme.ensureCardWindow(dialog, DialogTheme.resolve(context));
        } catch (Throwable throwable) {
            Toast.makeText(context, t("导入失败", "Import failed"), Toast.LENGTH_SHORT).show();
        }
    }

    private String readLogTail() {
        try {
            java.io.File externalDir = context.getExternalFilesDir(null);
            java.io.File internalDir = context.getFilesDir();
            java.util.List<java.io.File> candidates = new java.util.ArrayList<>();
            if (externalDir != null) {
                candidates.add(new java.io.File(new java.io.File(externalDir, "GramSieve"), "gramsieve.log"));
            }
            if (internalDir != null) {
                candidates.add(new java.io.File(internalDir, "gramsieve.log"));
            }
            for (java.io.File file : candidates) {
                if (file == null || !file.exists() || file.length() == 0) {
                    continue;
                }
                java.util.ArrayDeque<String> tail = new java.util.ArrayDeque<>();
                java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(file));
                try {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        tail.addLast(line);
                        while (tail.size() > 2000) {
                            tail.removeFirst();
                        }
                    }
                } finally {
                    reader.close();
                }
                if (!tail.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    sb.append(t("日志文件：", "Log file: ")).append(file.getAbsolutePath()).append("\n");
                    for (String line : tail) {
                        sb.append(line).append("\n");
                    }
                    return sb.toString();
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static String timestampedFileName(String prefix, String suffix) {
        return prefix + new java.text.SimpleDateFormat(
                "yyyyMMdd-HHmmss", java.util.Locale.US).format(new java.util.Date()) + suffix;
    }

    // Build #43: fileKind is "log", "rules" or "backup". The writer
    // itself is generic (app GramSieve dir first, then Downloads);
    // only the user-facing words change, so a rules/backup file is
    // never described as a log and never suggested to be sent on.
    private void exportLogToFile(String content, String fileName, String fileKind) {
        // Build #33: the old version only tried MediaStore and
        // swallowed every failure into one generic toast, so on a
        // device where the MediaStore insert or the share chooser
        // fails, the user got no file and no reason. Now the file
        // is ALWAYS written first to the app's own GramSieve
        // directory (the same place the log itself lives, so it is
        // writable whenever logging works), and only then is a
        // MediaStore copy + share attempted. If sharing fails, the
        // toast names the local file so the user can still find it
        // in a file manager and send it.
        String name = (fileName == null || fileName.isEmpty()) ? "gramsieve-log.log" : fileName;
        try {
            java.io.File externalDir = context.getExternalFilesDir(null);
            java.io.File dir = externalDir != null
                    ? new java.io.File(externalDir, "GramSieve")
                    : new java.io.File(context.getFilesDir(), "GramSieve");
            if (!dir.exists()) {
                dir.mkdirs();
            }
            java.io.File localFile = new java.io.File(dir, name);
            java.io.FileOutputStream out = new java.io.FileOutputStream(localFile);
            try {
                out.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } finally {
                out.close();
            }
        } catch (Throwable localError) {
            Toast.makeText(context, t("导出文件失败（本地写入失败：" + localError.getClass().getSimpleName()
                    + "），请用复制方式", "File export failed (local write failed: "
                    + localError.getClass().getSimpleName() + "), use Copy instead"),
                    Toast.LENGTH_LONG).show();
            return;
        }
        try {
            android.content.ContentValues values = new android.content.ContentValues();
            values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE,
                    name.endsWith(".json") ? "application/json" : "text/plain");
            // Build #35: Downloads root, not a GramSieve subfolder,
            // so the file is visible the moment Downloads is opened.
            values.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/");
            android.net.Uri uri = context.getContentResolver().insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IllegalStateException("MediaStore insert returned null");
            }
            java.io.OutputStream out = context.getContentResolver().openOutputStream(uri);
            if (out == null) {
                throw new IllegalStateException("MediaStore openOutputStream returned null");
            }
            try {
                out.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } finally {
                out.close();
            }
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            String shareTitle = "log".equals(fileKind) ? t("分享日志文件", "Share log file")
                    : "rules".equals(fileKind) ? t("分享规则文件", "Share rules file")
                    : t("分享完整备份文件", "Share full backup file");
            Intent chooser = Intent.createChooser(share, shareTitle);
            if (!(context instanceof android.app.Activity)) {
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            // Build #37: a toast alone is too easy to miss behind the
            // share chooser, which is exactly what happened on #35
            // ("no location shown"). Confirm persistently in a dialog
            // that names the file and both locations; Share opens the
            // chooser from there, and closing the dialog still leaves
            // the file in Downloads.
            if (context instanceof android.app.Activity) {
                android.app.AlertDialog savedDialog = new android.app.AlertDialog.Builder(context)
                        .setTitle(t("已保存到下载目录", "Saved to Download"))
                        .setMessage("log".equals(fileKind)
                                ? t("文件名：" + name + "\n位置：手机下载目录\n应用 GramSieve 目录里也有一份同名副本，可在文件管理里找到它发给我。",
                                        "File: " + name + "\nLocation: phone Download folder\nA copy with the same name is also in the app GramSieve folder; find it in a file manager and send it.")
                                : t("文件名：" + name + "\n位置：手机下载目录\n应用 GramSieve 目录里也有一份同名副本，可在文件管理里找到它。",
                                        "File: " + name + "\nLocation: phone Download folder\nA copy with the same name is also in the app GramSieve folder; find it in a file manager."))
                        .setPositiveButton(t("分享", "Share"), (d, w) -> {
                            try {
                                context.startActivity(chooser);
                            } catch (Throwable ignored) {
                            }
                        })
                        .setNegativeButton(t("知道了", "OK"), null)
                        .create();
                savedDialog.show();
                DialogTheme.styleAlertDialog(savedDialog, context);
            } else {
                context.startActivity(chooser);
                Toast.makeText(context, "log".equals(fileKind)
                        ? t("日志文件已保存到下载目录", "Log file saved to Download")
                        : t("文件已保存到下载目录", "File saved to Download"), Toast.LENGTH_LONG).show();
            }
        } catch (Throwable shareError) {
            Toast.makeText(context, "log".equals(fileKind)
                    ? t("分享打开失败，但文件已保存到 GramSieve 目录：" + name
                            + "，可以在文件管理里找到它发给我", "Share failed, but the file is saved in the GramSieve folder: "
                            + name + ", find it in a file manager and send it")
                    : t("分享打开失败，但文件已保存到 GramSieve 目录：" + name
                            + "，可以在文件管理里找到它", "Share failed, but the file is saved in the GramSieve folder: "
                            + name + ", find it in a file manager"),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void showExportLogDialog() {
        String log = readLogTail();
        if (log == null || log.isEmpty()) {
            Toast.makeText(context, t("没有找到日志文件：请先打开“详细调试日志”，复现一次问题后再导出", "No log file found: turn on Verbose debug logging, reproduce the problem, then export again"), Toast.LENGTH_LONG).show();
            return;
        }
        String fileName = timestampedFileName("gramsieve-log-", ".log");
        showCopyShareDialogInternal(
                t("导出日志", "Export log"),
                t("下面是最近的日志，点“导出为文件”会保存到下载目录，成功后弹框里会写明文件名和位置；也可以点“复制”或“分享”发文字版。日志里不含大模型 Key。", "Below is the recent log. Tap Export as file to save it as a .log file in Download; a dialog will then confirm the file name and location. Or tap Copy or Share for the text version. The log does not contain the LLM key."),
                log, "GramSieve Conso log", t("分享日志", "Share log"), fileName, log, "log");
    }

    private void applyProxyUiToConfig(FilterConfig config) {
        if (config == null) return;
        config.proxyAutoAdd = proxyAutoAddSwitch != null && proxyAutoAddSwitch.isChecked();
        config.proxyAutoSwitch = proxyAutoSwitchSwitch != null && proxyAutoSwitchSwitch.isChecked();
        config.proxyRefreshMinutes = proxyInt(proxyRefreshInput, 360);
        config.proxyExpiryHours = proxyInt(proxyExpiryInput, 24);
        // Source URL and pasted links were removed from the panel:
        // saving clears any value an old config still carries, so
        // the old sources stop being fetched.
        config.proxySourceUrl = "";
        config.proxyPastedLinks = "";
        if (proxyFolderInput != null) {
            config.proxyFolderName = valueOf(proxyFolderInput).trim();
            config.proxyFolderScan = !config.proxyFolderName.isEmpty();
        }
        config.proxyConfigured = true;
    }

    private int proxyInt(EditText input, int fallback) {
        try {
            int value = Integer.parseInt(valueOf(input).trim());
            return value > 0 ? value : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private void updateProxySummary() {
        if (proxySummary == null) return;
        int total = proxyWorkingEntries == null ? 0 : proxyWorkingEntries.size();
        int available = 0;
        FilterConfig.ProxyEntry fastest = null;
        if (proxyWorkingEntries != null) {
            for (FilterConfig.ProxyEntry entry : proxyWorkingEntries) {
                if (entry != null && entry.available && entry.pingMs >= 0) {
                    available++;
                    if (fastest == null || entry.pingMs < fastest.pingMs) fastest = entry;
                }
            }
        }
        String text = total == 0
                ? t("未保存代理：先在代理文件夹里放含代理链接的聊天，再点“立即刷新”。", "No proxies saved: put chats with proxy links in the proxy folder, then tap Refresh now.")
                : t("已保存 " + total + " 个代理，可用 " + available + " 个" + (fastest != null ? "，最快 " + fastest.display() + "（" + fastest.pingMs + " ms）" : ""), "Saved " + total + " proxies, " + available + " available" + (fastest != null ? ", fastest " + fastest.display() + " (" + fastest.pingMs + " ms)" : ""));
        // Build #29: the folder-scan diag lives in the summary, not
        // only in a toast that gets truncated.
        if (lastProxyFolderDiag != null && !lastProxyFolderDiag.isEmpty()) {
            text += "\n" + t("上次文件夹扫描：" + lastProxyFolderDiag, "Last folder scan: " + lastProxyFolderDiag);
        }
        proxySummary.setText(text);
    }

    private void showProxyFolderPicker() {
        try {
            android.app.Activity activity = activityFromContext(context);
            if (activity == null || activity.isFinishing()) {
                Toast.makeText(context, t("无法打开文件夹选择", "Cannot open folder picker"), Toast.LENGTH_SHORT).show();
                return;
            }
            java.util.List<String> folderNames = new java.util.ArrayList<>();
            try {
                ClassLoader cl = context.getClassLoader();
                Class<?> userConfigClass = cl.loadClass("org.telegram.messenger.UserConfig");
                Object selected = Reflect.staticField(userConfigClass, "selectedAccount");
                int account = selected instanceof Number ? ((Number) selected).intValue() : 0;
                Class<?> mcClass = cl.loadClass("org.telegram.messenger.MessagesController");
                Object controller = Reflect.invokeStatic(mcClass, "getInstance", new Class<?>[]{int.class}, account);
                if (controller != null) {
                    Object filtersObj = Reflect.invokeIfExists(controller, "getDialogFilters", new Class<?>[0]);
                    if (filtersObj instanceof java.util.List) {
                        for (Object filter : (java.util.List<?>) filtersObj) {
                            String name = Reflect.asString(Reflect.field(filter, "name"));
                            if (name == null || name.isBlank() || "ALL_CHATS".equalsIgnoreCase(name.trim())) {
                                continue;
                            }
                            folderNames.add(name.trim());
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            if (folderNames.isEmpty()) {
                Toast.makeText(context, t("没有找到文件夹，请先在 Telegram 里建一个文件夹", "No folders found, create one in Telegram first"), Toast.LENGTH_LONG).show();
                return;
            }
            LinearLayout dialogView = new LinearLayout(context);
            dialogView.setOrientation(LinearLayout.VERTICAL);
            dialogView.setPadding(dp(16), dp(16), dp(16), dp(16)); // Build #49: spacing token L
            // Build #34: dialogView stays transparent; the glass card is
            // the dialog window background set by styleAlertDialog after show(),
            // so there is exactly one card edge, not two stacked ones.
            dialogView.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            TextView title = new TextView(context);
            title.setText(t("选择代理文件夹", "Pick proxy folder"));
            title.setTextColor(primaryTextColor);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
            title.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
            dialogView.addView(title, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView hint = new TextView(context);
            hint.setText(t("只扫选中的这一个文件夹里的聊天，其他文件夹不扫。", "Only chats in the picked folder are scanned, not other folders."));
            hint.setTextColor(secondaryTextColor);
            hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            dialogView.addView(hint, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(activity)
                    .setView(dialogView)
                    .create();
            for (String folderName : folderNames) {
                Button itemButton = new Button(context);
                itemButton.setAllCaps(false);
                itemButton.setText(folderName);
                itemButton.setTextColor(primaryTextColor);
                itemButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
                itemButton.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                itemButton.setBackgroundColor(Color.TRANSPARENT);
                itemButton.setOnClickListener(v -> {
                    try {
                        if (proxyFolderInput != null) {
                            proxyFolderInput.setText(folderName);
                        }
                    } catch (Throwable ignored) {}
                    dialog.dismiss();
                });
                dialogView.addView(itemButton, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            Button cancelButton = toolbarButton(t("取消", "Cancel"));
            cancelButton.setOnClickListener(v -> dialog.dismiss());
            dialogView.addView(cancelButton, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            dialog.show();
            DialogTheme.styleAlertDialog(dialog, context);
            DialogTheme.ensureCardWindow(dialog, DialogTheme.resolve(context));
        } catch (Throwable throwable) {
            Toast.makeText(context, t("打开文件夹选择失败", "Failed to open folder picker"), Toast.LENGTH_SHORT).show();
        }
    }

    // One place for the four manage-dialog row states, so a test
    // run can refresh the same text the list was built with.
    // Build #40: untested entries are not dead, they just never got
    // a test slot (max 20 per refresh), and must read as 未测.
    private String proxyStatusText(FilterConfig.ProxyEntry entry, FilterConfig refConfig) {
        if (entry == null) {
            return t("未测", "untested");
        }
        boolean expired = TgProxyManager.isExpired(entry, refConfig);
        if (entry.available && !expired) {
            return entry.pingMs + " ms";
        } else if (entry.available) {
            return t("已过期", "expired");
        } else if (entry.lastTestedMs > 0L) {
            return t("测不通", "unreachable");
        } else {
            return t("未测", "untested");
        }
    }

    // Sort for the manage dialog: tested-good first by latency
    // ascending, then tested-dead, then untested last (the same
    // convention as TgProxyManager's refresh ordering, where an
    // untested ping counts as slowest).
    private void sortProxyEntriesByLatency(java.util.List<FilterConfig.ProxyEntry> entries) {
        entries.sort((a, b) -> {
            int rankA = a.pingMs >= 0 ? 0 : (a.lastTestedMs > 0L ? 1 : 2);
            int rankB = b.pingMs >= 0 ? 0 : (b.lastTestedMs > 0L ? 1 : 2);
            if (rankA != rankB) {
                return Integer.compare(rankA, rankB);
            }
            if (rankA == 0 && a.pingMs != b.pingMs) {
                return Long.compare(a.pingMs, b.pingMs);
            }
            String addressA = a.address == null ? "" : a.address;
            String addressB = b.address == null ? "" : b.address;
            int byAddress = addressA.compareToIgnoreCase(addressB);
            return byAddress != 0 ? byAddress : Integer.compare(a.port, b.port);
        });
    }

    // Rebuild the manage-dialog rows from the current entry order,
    // preserving each row's tick by entry key. Used by the initial
    // build and by the latency sort, so a sort never loses ticks.
    private void rebuildProxyRows(LinearLayout container,
            java.util.List<FilterConfig.ProxyEntry> entries,
            java.util.List<android.widget.CheckBox> boxes,
            FilterConfig refConfig) {
        java.util.Set<String> checkedKeys = new java.util.HashSet<>();
        for (android.widget.CheckBox box : boxes) {
            if (box.isChecked() && box.getTag() instanceof FilterConfig.ProxyEntry) {
                checkedKeys.add(((FilterConfig.ProxyEntry) box.getTag()).key());
            }
        }
        container.removeAllViews();
        boxes.clear();
        for (FilterConfig.ProxyEntry entry : entries) {
            android.widget.CheckBox box = new android.widget.CheckBox(context);
            box.setMinHeight(dp(52));
            box.setText(entry.display() + "（" + proxyStatusText(entry, refConfig) + "）");
            box.setTextColor(primaryTextColor);
            box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
            box.setTag(entry);
            box.setChecked(checkedKeys.contains(entry.key()));
            if (!boxes.isEmpty()) {
                // Build #43: a 1px inset divider between proxy
                // rows, so 300+ rows read as a list, not a wall.
                View divider = new View(context);
                divider.setBackgroundColor(strokeColor);
                LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
                dividerParams.leftMargin = dp(16);
                container.addView(divider, dividerParams);
            }
            boxes.add(box);
            LinearLayout.LayoutParams boxParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            boxParams.topMargin = dp(8);
            boxParams.bottomMargin = dp(8);
            container.addView(box, boxParams);
        }
    }

    // One test at a time, across dialog instances: a test started
    // from the manage dialog keeps running after the dialog is
    // closed, so a second dialog must not start a competing test
    // over the same entries.
    private static final java.util.concurrent.atomic.AtomicBoolean proxyTestRunning =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    // Background-safe result persistence for a running test: same
    // file write as saveProxyEntries, but without touching panel
    // views (updateProxySummary must only run on the UI thread)
    // and without replacing the panel's baseConfig snapshot, which
    // the UI thread owns. The file is the source of truth: a
    // reopened dialog reads fresh results from it.
    private void saveProxyTestResultsInBackground(java.util.List<FilterConfig.ProxyEntry> entries) {
        try {
            FilterConfig updated = baseConfig.deepCopy().sanitize();
            applyProxyUiToConfig(updated);
            java.util.List<FilterConfig.ProxyEntry> saved = new java.util.ArrayList<>();
            for (FilterConfig.ProxyEntry entry : entries) {
                if (entry != null) {
                    saved.add(entry.deepCopy());
                }
            }
            updated.proxyEntries = saved;
            updated.updatedAtEpochMs = System.currentTimeMillis();
            saver.save(updated);
            java.util.List<FilterConfig.ProxyEntry> working = new java.util.ArrayList<>();
            for (FilterConfig.ProxyEntry entry : saved) {
                working.add(entry.deepCopy());
            }
            proxyWorkingEntries = working;
        } catch (Throwable ignored) {
        }
    }

    // Test only the ticked proxies, on a background thread, then
    // refresh the row texts in place and persist the results. The
    // same test gives both speed (pingMs) and connectivity
    // (available): the two buttons differ only in what they report.
    private void testCheckedProxies(android.app.Activity activity,
            java.util.List<FilterConfig.ProxyEntry> entries,
            java.util.List<android.widget.CheckBox> boxes,
            FilterConfig refConfig, boolean showConnectivitySummary,
            TextView progressText, android.widget.ProgressBar progressBar,
            java.util.List<Button> controls) {
        java.util.List<Integer> selected = new java.util.ArrayList<>();
        for (int i = 0; i < entries.size() && i < boxes.size(); i++) {
            if (boxes.get(i).isChecked()) {
                selected.add(i);
            }
        }
        if (selected.isEmpty()) {
            Toast.makeText(context, t("先勾选要测的代理", "Tick the proxies to test first"), Toast.LENGTH_SHORT).show();
            return;
        }
        if (!proxyTestRunning.compareAndSet(false, true)) {
            Toast.makeText(context, t("测试正在后台进行，完成后结果会自动保存", "A test is already running in the background; results will be saved when it finishes"), Toast.LENGTH_SHORT).show();
            return;
        }
        // Progress display, on the UI thread before the worker
        // starts: every ticked row reads 测试中…, the header shows
        // the running count, and the test/sort buttons are disabled
        // so a second tap or a sort cannot move rows under the test.
        // Without this the dialog looked dead until the whole batch
        // finished, because rows only refreshed at the end.
        int total = selected.size();
        if (progressText != null) {
            progressText.setVisibility(View.VISIBLE);
            progressText.setText(t("测试中 0/" + total, "Testing 0/" + total));
        }
        if (progressBar != null) {
            progressBar.setVisibility(View.VISIBLE);
            progressBar.setMax(total);
            progressBar.setProgress(0);
        }
        if (controls != null) {
            for (Button button : controls) {
                button.setEnabled(false);
            }
        }
        for (int index : selected) {
            if (index < boxes.size()) {
                boxes.get(index).setText(entries.get(index).display() + "（" + t("测试中…", "Testing…") + "）");
            }
        }
        new Thread(() -> {
            int ok = 0;
            int fail = 0;
            try {
            for (int done = 0; done < selected.size(); done++) {
                int index = selected.get(done);
                FilterConfig.ProxyEntry entry = entries.get(index);
                long ping = TgProxyManager.testProxy(context, entry);
                entry.pingMs = ping;
                entry.available = ping >= 0;
                entry.lastTestedMs = System.currentTimeMillis();
                if (ping >= 0) ok++; else fail++;
                // Background running: persist after every proxy, not
                // only at the end, and never gated on the dialog or
                // activity still being alive. Closing the dialog or
                // leaving Conso no longer loses the test: the worker
                // thread keeps going, the file already holds each
                // finished result, and a reopened dialog reads it.
                saveProxyTestResultsInBackground(entries);
                int doneCount = done + 1;
                int rowIndex = index;
                FilterConfig.ProxyEntry doneEntry = entry;
                String result = ping >= 0 ? ping + " ms" : t("测不通", "unreachable");
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing()) {
                        return;
                    }
                    if (rowIndex < boxes.size()) {
                        boxes.get(rowIndex).setText(doneEntry.display()
                                + "（" + proxyStatusText(doneEntry, refConfig) + "）");
                    }
                    if (progressBar != null) {
                        progressBar.setProgress(doneCount);
                    }
                    if (progressText != null) {
                        progressText.setText(t("测试中 " + doneCount + "/" + total + "：" + doneEntry.display() + "（" + result + "）",
                                "Testing " + doneCount + "/" + total + ": " + doneEntry.display() + " (" + result + ")"));
                    }
                });
            }
            } finally {
                proxyTestRunning.set(false);
            }
            int okCount = ok;
            int failCount = fail;
            // Final persistence on the worker thread, unconditionally.
            // The old code saved inside the UI runnable behind an
            // isFinishing check, so closing the dialog discarded the
            // whole batch even though the tests had already run.
            saveProxyTestResultsInBackground(entries);
            // Auto-add after the test: the 10 lowest-latency
            // tested-good proxies from this batch go into Conso's
            // own proxy list. syncAvailableToHost dedupes by
            // address:port and never switches the current proxy, so
            // a repeat test adds nothing twice. This runs on the
            // worker thread (the refresh path already calls the
            // same helper off the UI thread), so it also happens
            // when the dialog was closed mid-test; only the toast
            // below needs a live activity. It is deliberately not
            // gated on the auto-add switch: the user asked for the
            // test itself to end with this step.
            java.util.List<FilterConfig.ProxyEntry> testedGood = new java.util.ArrayList<>();
            for (int index : selected) {
                FilterConfig.ProxyEntry tested = entries.get(index);
                if (tested != null && tested.pingMs >= 0) {
                    testedGood.add(tested);
                }
            }
            testedGood.sort((a, b) -> Long.compare(a.pingMs, b.pingMs));
            java.util.List<FilterConfig.ProxyEntry> top10 = testedGood.subList(
                    0, Math.min(10, testedGood.size()));
            int addedToHost = TgProxyManager.syncAvailableToHost(context, top10);
            int topCount = top10.size();
            final String autoAddNote = topCount == 0
                    ? t("，没有测通的代理可添加", ", no reachable proxy to add")
                    : addedToHost > 0
                    ? t("，已添加 " + addedToHost + " 个到 Conso 代理列表", ", added " + addedToHost + " to Conso's proxy list")
                    : t("，延迟最低的前 " + topCount + " 个已在 Conso 代理列表里", ", the " + topCount + " fastest are already in Conso's proxy list");
            activity.runOnUiThread(() -> {
                if (activity.isFinishing()) {
                    return;
                }
                for (int i = 0; i < entries.size() && i < boxes.size(); i++) {
                    boxes.get(i).setText(entries.get(i).display()
                            + "（" + proxyStatusText(entries.get(i), refConfig) + "）");
                }
                if (progressText != null) {
                    progressText.setVisibility(View.GONE);
                }
                if (progressBar != null) {
                    progressBar.setVisibility(View.GONE);
                }
                if (controls != null) {
                    for (Button button : controls) {
                        button.setEnabled(true);
                    }
                }
                saveProxyEntries(entries);
                if (showConnectivitySummary) {
                    Toast.makeText(context,
                            t("联通 " + okCount + " 个，不通 " + failCount + " 个" + autoAddNote,
                                    "Reachable " + okCount + ", unreachable " + failCount + autoAddNote),
                            Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(context, t("测速完成" + autoAddNote, "Speed test done" + autoAddNote), Toast.LENGTH_SHORT).show();
                }
            });
        }, "gramsieve-proxy-test").start();
    }

    // Open a link in the browser / Telegram. Used by the footer
    // links card only; failure is a toast, never a crash.
    private void openExternalLink(String url) {
        try {
            android.content.Intent intent = new android.content.Intent(
                    android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url));
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Throwable ignored) {
            Toast.makeText(context, t("无法打开链接", "Cannot open link"), Toast.LENGTH_SHORT).show();
        }
    }

    // Footer links card, below the footer note: this project's
    // open-source address, the upstream address (confirmed by
    // the author 2026-10-28), and the author's Telegram channel
    // with a jump button.
    private void buildLinksFooter(LinearLayout container) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        try {
            card.setBackground(rounded(adjustAlpha(primaryTextColor, 0.04f), dp(16), strokeColor));
        } catch (Throwable ignored) {}
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.topMargin = dp(16);
        container.addView(card, cardParams);
        TextView title = new TextView(context);
        title.setText(t("开源与社区", "Open source & community"));
        title.setTextColor(primaryTextColor);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        title.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        card.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView desc = new TextView(context);
        desc.setText(t("本项目基于上游 GramSieve（GPL-3.0）改编，适配 Conso 客户端；过滤本身在本地执行，不上传你的消息。下面是本项目的开源地址、上游开源地址和作者的 Telegram 频道，点开即用浏览器或 Telegram 打开；新版本安装包与更新说明也会发在频道里。", "This project is adapted from upstream GramSieve (GPL-3.0) for the Conso client; filtering itself runs locally and does not upload your messages. Below are this project's open-source address, the upstream release page, and the author's Telegram channel; tapping opens them in the browser or Telegram. New releases and update notes are also posted in the channel."));
        desc.setTextColor(secondaryTextColor);
        desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        try { desc.setLineSpacing(dp(3), 1.35f); } catch (Throwable ignored) {}
        LinearLayout.LayoutParams descParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        descParams.topMargin = dp(8);
        descParams.bottomMargin = dp(12);
        card.addView(desc, descParams);
        LinearLayout linkRow = new LinearLayout(context);
        linkRow.setOrientation(LinearLayout.HORIZONTAL);
        Button projectButton = manageActionButton(t("本项目开源地址", "Project source"), false);
        projectButton.setOnClickListener(v -> openExternalLink("https://github.com/228983641/gramsieve-conso-mod"));
        addManageButton(linkRow, projectButton);
        Button upstreamButton = manageActionButton(t("上游开源地址", "Upstream source"), false);
        upstreamButton.setOnClickListener(v -> openExternalLink("https://github.com/Xposed-Modules-Repo/com.tianqianguai.gramsieve"));
        addManageButton(linkRow, upstreamButton);
        card.addView(linkRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Button channelButton = manageActionButton(t("进入社区频道", "Join community channel"), true);
        channelButton.setOnClickListener(v -> openExternalLink("https://t.me/shenlinzhibiao"));
        LinearLayout.LayoutParams channelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        channelParams.topMargin = dp(8);
        card.addView(channelButton, channelParams);
        TextView channelNote = new TextView(context);
        channelNote.setText(t("频道：https://t.me/shenlinzhibiao", "Channel: https://t.me/shenlinzhibiao"));
        channelNote.setTextColor(secondaryTextColor);
        channelNote.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        channelNote.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams channelNoteParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        channelNoteParams.topMargin = dp(8);
        card.addView(channelNote, channelNoteParams);
    }

    // Floating hint for the manage-proxies dialog: a scrollable
    // dialog, not an inline paragraph above the list.
    private void showProxyManageHint(android.app.Activity activity) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        android.widget.ScrollView scroll = new android.widget.ScrollView(context);
        TextView hint = new TextView(context);
        hint.setText(t("勾选后点下面的按钮：\n测速 / 检查联通：实测勾选的代理并更新每条后面的状态。测试时每条后面会显示“测试中…”，顶部有进度和当前测的代理，测速、检查联通、按延迟排序在测试结束前不可点。测试在后台线程跑，关掉弹窗或切出 Conso 也会继续，每测完一条就保存一次，重新打开管理代理就能看到结果，不用一直守在前台。测试结束后自动把本批测通里延迟最低的前 10 个加进 Conso 代理列表（已在列表里的不重复加，不切换当前代理）。\n按延迟排序：把测通的按延迟从低到高排前面，测不通的其次，未测的最后；勾选状态排完不变。\n添加到软件：把勾选的代理加进 Conso 自己的代理列表，只加列表，不切换当前代理。\n删除所选：删勾选的；删除失效：只删测不通和已过期的，不删未测的。\n\n每条后面的状态分四种：延迟毫秒是测通的；“已过期”是以前测通过但超过上面自定义的失效时间了；“测不通”是测过但连不上；“未测”是还没轮到测（一次刷新最多测 20 个，没轮到的下次刷新再测）。\n\n删除只影响模块列表，宿主当前正在用的代理不会因此断开，如需换掉请在 Conso 代理设置里手动切换或等下次自动刷新切到最快。", "Tick, then use the buttons below:\nTest speed / Check connectivity: actually tests the ticked proxies and updates each row's status. While testing, each row reads Testing…, the header shows progress and the proxy being tested, and test/sort buttons are disabled until it finishes. Testing runs on a background thread: closing the dialog or leaving Conso does not stop it, each finished result is saved immediately, and reopening the dialog shows the results, so there is no need to stay in the foreground. When the test finishes, the 10 lowest-latency reachable proxies from this batch are added to Conso's proxy list automatically (already-listed ones are not duplicated, and the current proxy is not switched).\nSort by latency: tested-good first by latency ascending, then unreachable, then untested last; ticks survive the sort.\nAdd to app: adds the ticked proxies to Conso's own proxy list (list only, it does not switch the current proxy).\nDelete selected: deletes the ticked ones; Delete expired: removes only unreachable and expired ones, not untested ones.\n\nRow status has four kinds: milliseconds means tested good; expired means tested good before but past the custom expiry time above; unreachable means tested but not connectable; untested means no test slot yet (max 20 per refresh).\n\nDeleting only affects the module list; the host's currently used proxy is not disconnected by this, switch manually in Conso proxy settings or wait for the next auto-refresh to switch to fastest."));
        hint.setTextColor(primaryTextColor);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        try { hint.setLineSpacing(dp(3), 1.35f); } catch (Throwable ignored) {}
        scroll.addView(hint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(16));
        content.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        android.app.AlertDialog hintDialog = new android.app.AlertDialog.Builder(activity)
                .setTitle(t("管理代理说明", "Manage proxies help"))
                .setView(content)
                .setNegativeButton(t("关闭", "Close"), null)
                .create();
        hintDialog.show();
        DialogTheme.styleAlertDialog(hintDialog, context);
    }

    // Manage-dialog action button: equal-width rounded buttons,
    // filled for the primary action, outlined for the rest, so the
    // two button rows read as a grid instead of a run of text links.
    private Button manageActionButton(String text, boolean filled) {
        Button button = new Button(context);
        button.setAllCaps(false);
        button.setText(text);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        button.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        button.setMinHeight(dp(48));
        button.setPadding(dp(8), dp(10), dp(8), dp(10));
        button.setGravity(Gravity.CENTER);
        try {
            if (filled) {
                button.setTextColor(android.graphics.Color.WHITE);
                button.setBackground(rounded(accentColor, dp(10), accentColor));
            } else {
                button.setTextColor(accentColor);
                button.setBackground(rounded(adjustAlpha(primaryTextColor, 0.04f), dp(10), strokeColor));
            }
        } catch (Throwable ignored) {}
        return button;
    }

    private void addManageButton(LinearLayout row, Button button) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.leftMargin = dp(4);
        params.rightMargin = dp(4);
        row.addView(button, params);
    }

    private void showProxyManageDialog() {
        try {
            android.app.Activity activity = activityFromContext(context);
            if (activity == null || activity.isFinishing()) {
                Toast.makeText(context, t("无法打开管理界面", "Cannot open management dialog"), Toast.LENGTH_SHORT).show();
                return;
            }
            LinearLayout dialogView = new LinearLayout(context);
            dialogView.setOrientation(LinearLayout.VERTICAL);
            dialogView.setPadding(dp(16), dp(16), dp(16), dp(16)); // Build #49: spacing token L
            // Build #34: dialogView stays transparent; the glass card is
            // the dialog window background set by styleAlertDialog after show(),
            // so there is exactly one card edge, not two stacked ones.
            dialogView.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            // The long hint no longer sits above the list: the title
            // row carries a "说明" button, and the hint only shows as
            // a floating dialog when the user taps it.
            LinearLayout titleRow = new LinearLayout(context);
            titleRow.setOrientation(LinearLayout.HORIZONTAL);
            titleRow.setGravity(Gravity.CENTER_VERTICAL);
            TextView title = new TextView(context);
            title.setText(t("管理代理", "Manage proxies"));
            title.setTextColor(primaryTextColor);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
            title.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
            titleRow.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            Button hintButton = toolbarButton(t("说明", "About"));
            hintButton.setOnClickListener(v -> showProxyManageHint(activity));
            titleRow.addView(hintButton, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            dialogView.addView(titleRow, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            // Build #24: import Conso's own proxies when the dialog
            // opens, not only on refresh, so a proxy added in Conso
            // settings after the last refresh is visible immediately.
            // Merge by address+port (host authoritative on secret).
            try {
                for (FilterConfig.ProxyEntry hostEntry : TgProxyManager.importHostProxies(context)) {
                    boolean found = false;
                    if (proxyWorkingEntries != null) {
                        for (FilterConfig.ProxyEntry existing : proxyWorkingEntries) {
                            if (existing != null && existing.address != null
                                    && existing.address.equalsIgnoreCase(hostEntry.address)
                                    && existing.port == hostEntry.port) {
                                existing.secret = hostEntry.secret;
                                if (existing.pingMs < 0 && hostEntry.pingMs >= 0) {
                                    existing.pingMs = hostEntry.pingMs;
                                    existing.available = hostEntry.available;
                                    existing.lastTestedMs = hostEntry.lastTestedMs;
                                }
                                found = true;
                                break;
                            }
                        }
                    }
                    if (!found) {
                        if (proxyWorkingEntries == null) proxyWorkingEntries = new java.util.ArrayList<>();
                        proxyWorkingEntries.add(hostEntry.deepCopy());
                    }
                }
            } catch (Throwable ignored) {}
            // Build #39: the checkbox list used to be added straight
            // to the dialog, so with 300+ proxies it grew past the
            // screen and could not be scrolled at all. Same fix as
            // #38: list scrolls inside a weighted area, buttons stay.
            LinearLayout proxyListContainer = new LinearLayout(context);
            proxyListContainer.setOrientation(LinearLayout.VERTICAL);
            android.widget.ScrollView proxyScroll = new android.widget.ScrollView(context);
            proxyScroll.addView(proxyListContainer, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            java.util.List<android.widget.CheckBox> boxes = new java.util.ArrayList<>();
            java.util.List<FilterConfig.ProxyEntry> entries = proxyWorkingEntries == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(proxyWorkingEntries);
            if (entries.isEmpty()) {
                TextView empty = new TextView(context);
                empty.setText(t("暂无代理", "No proxies"));
                empty.setTextColor(primaryTextColor);
                empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
                proxyListContainer.addView(empty);
            }
            FilterConfig refConfig = baseConfig.deepCopy().sanitize();
            applyProxyUiToConfig(refConfig);
            if (!entries.isEmpty()) {
                rebuildProxyRows(proxyListContainer, entries, boxes, refConfig);
            }
            dialogView.addView(proxyScroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            // Sort row + test progress, between the list and the
            // action buttons. The progress views stay GONE until a
            // test starts; the sort button joins the test buttons in
            // the disabled-while-testing set, so a sort cannot move
            // rows under a running test (row indices are the test's
            // only link to entries).
            LinearLayout sortRow = new LinearLayout(context);
            sortRow.setOrientation(LinearLayout.HORIZONTAL);
            sortRow.setGravity(Gravity.END);
            Button sortButton = toolbarButton(t("按延迟排序", "Sort by latency"));
            sortButton.setOnClickListener(v -> {
                sortProxyEntriesByLatency(entries);
                rebuildProxyRows(proxyListContainer, entries, boxes, refConfig);
            });
            sortRow.addView(sortButton, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams sortRowParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sortRowParams.topMargin = dp(8);
            dialogView.addView(sortRow, sortRowParams);
            TextView progressText = new TextView(context);
            progressText.setTextColor(secondaryTextColor);
            progressText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            progressText.setVisibility(View.GONE);
            // A test started from an earlier dialog keeps running
            // after that dialog closed; say so on reopen instead of
            // showing a dead list, and let the running flag block a
            // second test until it finishes and its results are saved.
            if (proxyTestRunning.get()) {
                progressText.setVisibility(View.VISIBLE);
                progressText.setText(t("后台测试进行中，完成后结果会自动保存，重新打开即可看到", "A test is running in the background; results will be saved when it finishes, reopen to see them"));
            }
            LinearLayout.LayoutParams progressTextParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            progressTextParams.topMargin = dp(8);
            dialogView.addView(progressText, progressTextParams);
            android.widget.ProgressBar progressBar = new android.widget.ProgressBar(
                    context, null, android.R.attr.progressBarStyleHorizontal);
            progressBar.setVisibility(View.GONE);
            try {
                progressBar.setProgressTintList(new android.content.res.ColorStateList(
                        new int[][]{{}}, new int[]{accentColor}));
            } catch (Throwable ignored) {}
            LinearLayout.LayoutParams progressBarParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            progressBarParams.topMargin = dp(4);
            dialogView.addView(progressBar, progressBarParams);
            java.util.List<Button> testControls = new java.util.ArrayList<>();
            testControls.add(sortButton);
            android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(activity)
                    .setView(dialogView)
                    .create();
            // Buttons in two rows instead of one long row, so the
            // manage dialog does not overflow on a narrow screen.
            LinearLayout buttonRow = new LinearLayout(context);
            buttonRow.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout buttonRow2 = new LinearLayout(context);
            buttonRow2.setOrientation(LinearLayout.HORIZONTAL);
            Button selectAll = manageActionButton(t("全选", "Select all"), false);
            selectAll.setOnClickListener(v -> { for (android.widget.CheckBox b : boxes) b.setChecked(true); });
            addManageButton(buttonRow, selectAll);
            Button testSpeed = manageActionButton(t("测速", "Test speed"), false);
            testControls.add(testSpeed);
            testSpeed.setOnClickListener(v -> testCheckedProxies(activity, entries, boxes, refConfig, false, progressText, progressBar, testControls));
            addManageButton(buttonRow, testSpeed);
            Button checkConnectivity = manageActionButton(t("检查联通", "Check connectivity"), false);
            testControls.add(checkConnectivity);
            checkConnectivity.setOnClickListener(v -> testCheckedProxies(activity, entries, boxes, refConfig, true, progressText, progressBar, testControls));
            addManageButton(buttonRow, checkConnectivity);
            Button addToHost = manageActionButton(t("添加到软件", "Add to app"), true);
            addToHost.setOnClickListener(v -> {
                java.util.List<FilterConfig.ProxyEntry> selected = new java.util.ArrayList<>();
                for (int i = 0; i < entries.size() && i < boxes.size(); i++) {
                    if (boxes.get(i).isChecked()) {
                        selected.add(entries.get(i));
                    }
                }
                if (selected.isEmpty()) {
                    Toast.makeText(context, t("先勾选要添加的代理", "Tick the proxies to add first"), Toast.LENGTH_SHORT).show();
                    return;
                }
                int added = TgProxyManager.syncAvailableToHost(context, selected);
                Toast.makeText(context,
                        added > 0
                                ? t("已添加 " + added + " 个到 Conso 代理列表", "Added " + added + " to Conso's proxy list")
                                : t("勾选的代理已在 Conso 代理列表里", "Ticked proxies are already in Conso's proxy list"),
                        Toast.LENGTH_SHORT).show();
            });
            addManageButton(buttonRow, addToHost);
            Button deleteSelected = manageActionButton(t("删除所选", "Delete selected"), false);
            deleteSelected.setOnClickListener(v -> {
                java.util.List<FilterConfig.ProxyEntry> remaining = new java.util.ArrayList<>();
                int deleted = 0;
                for (int i = 0; i < entries.size(); i++) {
                    if (boxes.get(i).isChecked()) deleted++;
                    else remaining.add(entries.get(i));
                }
                saveProxyEntries(remaining);
                Toast.makeText(context, t("已删除 " + deleted + " 个", "Deleted " + deleted), Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            });
            addManageButton(buttonRow2, deleteSelected);
            Button deleteExpired = manageActionButton(t("删除失效", "Delete expired"), false);
            deleteExpired.setOnClickListener(v -> {
                java.util.List<FilterConfig.ProxyEntry> remaining = new java.util.ArrayList<>();
                int deleted = 0;
                for (FilterConfig.ProxyEntry entry : entries) {
                    // Build #40: "删除失效" only removes tested-dead and
                    // stale entries; untested ones are not dead and stay
                    // for a later refresh to test.
                    boolean dead = !entry.available && entry.lastTestedMs > 0L;
                    boolean stale = entry.available && TgProxyManager.isExpired(entry, refConfig);
                    if (dead || stale) deleted++;
                    else remaining.add(entry);
                }
                saveProxyEntries(remaining);
                Toast.makeText(context, t("已删除失效 " + deleted + " 个", "Deleted expired " + deleted), Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            });
            addManageButton(buttonRow2, deleteExpired);
            Button close = manageActionButton(t("关闭", "Close"), false);
            close.setOnClickListener(v -> dialog.dismiss());
            addManageButton(buttonRow2, close);
            LinearLayout.LayoutParams buttonRowParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            buttonRowParams.topMargin = dp(12);
            dialogView.addView(buttonRow, buttonRowParams);
            LinearLayout.LayoutParams buttonRow2Params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            buttonRow2Params.topMargin = dp(8);
            dialogView.addView(buttonRow2, buttonRow2Params);
            dialog.show();
            DialogTheme.styleAlertDialog(dialog, context);
            DialogTheme.ensureCardWindow(dialog, DialogTheme.resolve(context));
        } catch (Throwable throwable) {
            Toast.makeText(context, t("管理界面打开失败", "Failed to open management dialog"), Toast.LENGTH_SHORT).show();
        }
    }

    private void saveProxyEntries(java.util.List<FilterConfig.ProxyEntry> remaining) {
        try {
            FilterConfig updated = baseConfig.deepCopy().sanitize();
            applyProxyUiToConfig(updated);
            updated.proxyEntries = remaining == null ? new java.util.ArrayList<>() : remaining;
            updated.updatedAtEpochMs = System.currentTimeMillis();
            saver.save(updated);
            proxyWorkingEntries = new java.util.ArrayList<>();
            for (FilterConfig.ProxyEntry entry : updated.proxyEntries) {
                if (entry != null) proxyWorkingEntries.add(entry.deepCopy());
            }
            baseConfig = updated.deepCopy().sanitize();
            updateProxySummary();
        } catch (Throwable ignored) {
        }
    }

    private void buildTranslateCard(LinearLayout container) {
        LinearLayout card = addGroup(container, t("翻译", "Translation"));
        addInfo(card, t("使用 Conso 自带的翻译服务，不经过第三方翻译网站。开启后只显示聊天右上角的当前聊天翻译入口，并显示消息菜单里的翻译按钮；不会在打开聊天时自动翻译。", "Uses Conso's built-in translation service, not a third-party website. When on, it only exposes the current-chat translation entry in the top-right and the message-menu Translate action; it does not auto-translate when a chat opens."));
        autoTranslateSwitch = addSwitch(card, t("显示翻译入口", "Show translation entry points"));
        autoTranslateSwitch.setChecked(baseConfig.autoTranslateToChinese);
        setRowDesc(autoTranslateSwitch, t("右上角开启当前聊天翻译；点击消息可翻译单条消息", "Top-right toggles current-chat translation; message menu translates one message"));
        addInfo(card, t("目标语言固定为简体中文。翻译由 Telegram 服务端完成；如果宿主仍受 Premium 限制，只能使用消息菜单的单条翻译或开启“增强功能”里的本地 Premium。", "The target language is Simplified Chinese and translation is handled by Telegram's servers. If the host still gates whole-chat translation behind Premium, use the message-menu translation or enable Local Premium in Enhancements."));
    }

    private void buildChatAntiRecallCard(LinearLayout container) {
        if (antiRecallConfigStore == null || dialogId == 0L) {
            return;
        }
        LinearLayout card = addGroup(container, t("当前聊天主动加载", "Current Chat Proactive Loading"));
        chatAntiRecallSwitch = addSwitch(card, t("为这个聊天启用主动加载/防撤回", "Enable proactive loading for this chat"));
        chatAntiRecallSwitch.setChecked(isChatAntiRecallEnabled());
        addInfo(card, t("保存后会立即同步到宿主里的后台加载器。", "Saving updates the host background loader immediately."));
    }

    private void buildRulesCard(LinearLayout container) {
        // Rules are message-text keywords only. The old "高级规则"
        // section (message-text regex, plus caption / buttons /
        // sender / chat / any-field keywords and regex) was removed
        // outright: it is no longer built, and because collectMatrix
        // only reads the inputs built here, saving from this panel
        // drops any advanced rules an old config still carries.
        LinearLayout card = addGroup(container, t("规则内容", "Rules"));
        addInfo(card, chatMode
                ? t("这里只影响当前聊天。关键词用“、”分隔，不用一行一条；点“编辑”进关键词编辑界面，外部只显示个数，不直接显示关键词内容。", "This editor only affects the current chat. Keywords are separated by 、, not one per line; tap Edit to open the keyword editor, which shows only the count outside, not the keywords themselves.")
                : t("关键词用“、”分隔，不用一行一条；点“编辑”进关键词编辑界面，外部只显示个数，不直接显示关键词内容。", "Keywords are separated by 、, not one per line; tap Edit to open the keyword editor, which shows only the count outside, not the keywords themselves."));

        FilterConfig.ChatRuleSet chatRuleSet = chatMode
                ? baseConfig.getOrCreateChatRuleSet(dialogId).deepCopy().sanitize()
                : null;
        RuleDraftMatrix matchMatrix = RuleDraftMatrix.fromRules(chatMode ? chatRuleSet.rules : baseConfig.globalRules);
        RuleDraftMatrix exclusionMatrix = RuleDraftMatrix.fromRules(chatMode ? chatRuleSet.exclusions : baseConfig.globalExclusions);

        RuleInputs inputs = new RuleInputs();
        addSectionLabel(card, t("消息文字", "Message text"));
        addInfo(card, t("只检查消息正文。", "Only checks message text."));
        inputs.matchKeywords = addKeywordEditorRow(card, t("过滤关键词", "Filter keywords"), matchMatrix.get(FilterConfig.RuleTarget.TEXT, FilterConfig.RuleMode.KEYWORD));
        inputs.keepKeywords = addKeywordEditorRow(card, t("保留关键词（白名单）", "Keep keywords (allowlist)"), exclusionMatrix.get(FilterConfig.RuleTarget.TEXT, FilterConfig.RuleMode.KEYWORD));
        ruleInputs.put(FilterConfig.RuleTarget.TEXT, inputs);
    }

    private void save() {
        try {
            RuleDraftMatrix matchMatrix = collectMatrix(0);
            RuleDraftMatrix exclusionMatrix = collectMatrix(1);
            FilterConfig updated;
            if (chatMode) {
                updated = applyChatDraft(
                        baseConfig,
                        dialogId,
                        enabledSwitch != null && enabledSwitch.isChecked(),
                        excludeChatSwitch != null && excludeChatSwitch.isChecked(),
                        matchMatrix,
                        exclusionMatrix
                );
                persistChatAntiRecall();
            } else {
                updated = applyGlobalDraft(
                        baseConfig,
                        enabledSwitch != null && enabledSwitch.isChecked(),
                        debugLoggingSwitch != null && debugLoggingSwitch.isChecked(),
                        selectedLanguageTag(),
                        selectedAction(),
                        matchMatrix,
                        exclusionMatrix
                );
                updated.disableSponsoredMessages = sponsoredSwitch != null && sponsoredSwitch.isChecked();
                updated.disableVideoAds = videoAdsSwitch != null && videoAdsSwitch.isChecked();
                updated.enableForwardSave = forwardSaveSwitch != null && forwardSaveSwitch.isChecked();
                updated.enableSaveStories = saveStoriesSwitch != null && saveStoriesSwitch.isChecked();
                updated.preventMessageDelete = preventDeleteSwitch != null && preventDeleteSwitch.isChecked();
                updated.boostDownloadSpeed = downloadSpeedSwitch != null && downloadSpeedSwitch.isChecked();
                updated.localPremium = localPremiumSwitch != null && localPremiumSwitch.isChecked();
                updated.autoTranslateToChinese = autoTranslateSwitch != null && autoTranslateSwitch.isChecked();
                updated.highlightPinnedDialogs = pinnedHighlightSwitch != null && pinnedHighlightSwitch.isChecked();
                updated.aiAnalysisMode = aiLlmSwitch != null && aiLlmSwitch.isChecked() ? 1 : 0;
                updated.aiFallbackToLocal = aiFallbackSwitch == null || aiFallbackSwitch.isChecked();
                if (aiBaseUrlInput != null) updated.aiApiBaseUrl = valueOf(aiBaseUrlInput).trim();
                if (aiModelInput != null) updated.aiApiModel = valueOf(aiModelInput).trim();
                if (aiKeyInput != null) updated.aiApiKey = valueOf(aiKeyInput).trim();
                updated.proxyAutoAdd = proxyAutoAddSwitch != null && proxyAutoAddSwitch.isChecked();
                updated.proxyAutoSwitch = proxyAutoSwitchSwitch != null && proxyAutoSwitchSwitch.isChecked();
                updated.proxyRefreshMinutes = proxyInt(proxyRefreshInput, 360);
                updated.proxyExpiryHours = proxyInt(proxyExpiryInput, 24);
                updated.proxySourceUrl = "";
                updated.proxyPastedLinks = "";
                if (proxyFolderInput != null) {
                    updated.proxyFolderName = valueOf(proxyFolderInput).trim();
                    updated.proxyFolderScan = !updated.proxyFolderName.isEmpty();
                }
                updated.proxyConfigured = true;
                if (proxyWorkingEntries != null) {
                    updated.proxyEntries = new java.util.ArrayList<>();
                    for (FilterConfig.ProxyEntry entry : proxyWorkingEntries) {
                        if (entry != null) updated.proxyEntries.add(entry.deepCopy());
                    }
                }
            }
            saver.save(updated);
            if (afterSave != null) {
                afterSave.run();
            }
            Toast.makeText(context, t("已保存并立即生效", "Saved and applied"), Toast.LENGTH_SHORT).show();
            close();
        } catch (Throwable throwable) {
            Toast.makeText(
                    context,
                    t("保存失败：", "Save failed: ") + throwable.getClass().getSimpleName(),
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private RuleDraftMatrix collectMatrix(int kind) {
        RuleDraftMatrix matrix = new RuleDraftMatrix();
        for (Map.Entry<FilterConfig.RuleTarget, RuleInputs> entry : ruleInputs.entrySet()) {
            RuleInputs inputs = entry.getValue();
            if (kind == 0) {
                matrix.set(entry.getKey(), FilterConfig.RuleMode.KEYWORD, valueOf(inputs.matchKeywords));
                matrix.set(entry.getKey(), FilterConfig.RuleMode.REGEX, valueOf(inputs.matchRegex));
            } else {
                matrix.set(entry.getKey(), FilterConfig.RuleMode.KEYWORD, valueOf(inputs.keepKeywords));
                matrix.set(entry.getKey(), FilterConfig.RuleMode.REGEX, valueOf(inputs.keepRegex));
            }
        }
        return matrix;
    }

    private boolean isChatAntiRecallEnabled() {
        if (backgroundMessageLoader != null) {
            return backgroundMessageLoader.isChatEnabled(dialogId);
        }
        return antiRecallConfigStore != null && antiRecallConfigStore.isChatEnabled(dialogId);
    }

    private void persistChatAntiRecall() {
        if (chatAntiRecallSwitch == null || antiRecallConfigStore == null || dialogId == 0L) {
            return;
        }
        boolean enabled = chatAntiRecallSwitch.isChecked();
        if (backgroundMessageLoader != null) {
            if (enabled) {
                backgroundMessageLoader.enableChat(dialogId);
            } else {
                backgroundMessageLoader.disableChat(dialogId);
            }
            return;
        }
        antiRecallConfigStore.setChatEnabled(dialogId, enabled);
    }

    private void close() {
        if (overlay == null) {
            return;
        }
        FrameLayout panel = overlay;
        overlay = null;
        unregisterSystemBackHandler();
        ViewGroup parent = (ViewGroup) panel.getParent();
        if (parent != null) {
            parent.removeView(panel);
        }
    }

    private void registerSystemBackHandler() {
        Activity activity = activityFromContext(context);
        if (activity == null || backCallback != null) {
            return;
        }
        try {
            backDispatcher = activity.getOnBackInvokedDispatcher();
            backCallback = this::close;
            backDispatcher.registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_OVERLAY,
                    backCallback
            );
        } catch (RuntimeException ignored) {
            backDispatcher = null;
            backCallback = null;
        }
    }

    private void unregisterSystemBackHandler() {
        OnBackInvokedDispatcher dispatcher = backDispatcher;
        OnBackInvokedCallback callback = backCallback;
        backDispatcher = null;
        backCallback = null;
        if (dispatcher == null || callback == null) {
            return;
        }
        try {
            dispatcher.unregisterOnBackInvokedCallback(callback);
        } catch (RuntimeException ignored) {
            // The host may already be tearing down its window.
        }
    }

    private static Activity activityFromContext(Context context) {
        Context current = context;
        for (int i = 0; i < 8 && current != null; i++) {
            if (current instanceof Activity) {
                return (Activity) current;
            }
            if (!(current instanceof ContextWrapper)) {
                break;
            }
            Context next = ((ContextWrapper) current).getBaseContext();
            if (next == current) {
                break;
            }
            current = next;
        }
        return null;
    }

    // Build #32: iOS-style grouped list. A "group" is a section
    // header (small secondary text above the block) plus one
    // full-width solid block (Telegram windowBackgroundWhite, so it
    // follows light/dark mode) with thin inset dividers between
    // rows. The old rounded-card addCard is kept only as a legacy
    // helper; panel builders now use addGroup.
    private final java.util.Map<LinearLayout, TextView> groupHeaderHelp = new java.util.HashMap<>();

    private LinearLayout addGroup(LinearLayout parent, String title) {
        // Build #46: HTML v2: section header OUTSIDE the card
        // (13sp gray title + gray "?" circle, like the reference),
        // white 12dp card below, no collapsing, body padding 0
        // (cells carry their own padding, hairlines separate them).
        // Returns the BODY, so builders keep adding to it.
        LinearLayout headerRow = new LinearLayout(context);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        headerRow.setPadding(dp(4), dp(22), dp(4), dp(10));
        View marker = new View(context);
        marker.setBackground(rounded(adjustAlpha(accentColor, 0.9f), dp(2), adjustAlpha(accentColor, 0.9f)));
        LinearLayout.LayoutParams markerParams = new LinearLayout.LayoutParams(dp(3), dp(18));
        markerParams.rightMargin = dp(8);
        headerRow.addView(marker, markerParams);
        TextView titleView = new TextView(context);
        titleView.setText(title);
        titleView.setTextColor(primaryTextColor);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        titleView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        titleView.setMaxLines(1);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        headerRow.addView(titleView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView sectionHelp = createSectionHelpButton();
        headerRow.addView(sectionHelp, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        parent.addView(headerRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setTag("gramsieve_card");
        android.graphics.drawable.GradientDrawable cardBg = new android.graphics.drawable.GradientDrawable();
        cardBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        cardBg.setCornerRadius(dp(16));
        cardBg.setColor(backgroundColor);
        // Build #49: same 1px divider stroke as the dialog card.
        cardBg.setStroke(1, strokeColor);
        card.setBackground(cardBg);
        // Build #47: clip children to the rounded card outline, so
        // no row, status bar or button can draw past the card's
        // own background (user: text must not exceed the card).
        try { card.setClipToOutline(true); } catch (Throwable ignored) {}
        try { card.setElevation(dp(1)); } catch (Throwable ignored) {}
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = dp(22);
        parent.addView(card, cardParams);
        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setTag("gramsieve_body");
        card.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        groupHeaderHelp.put(body, sectionHelp);
        return body;
    }

    private TextView createSectionHelpButton() {
        // HTML v2 section "?": 18dp gray circle, white 12sp "?".
        // Hidden until addInfo assigns the section introduction.
        TextView help = new TextView(context);
        help.setText("?");
        help.setTextColor(accentColor);
        help.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        help.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        help.setGravity(Gravity.CENTER);
        help.setVisibility(View.GONE);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        bg.setColor(adjustAlpha(accentColor, 0.12f));
        bg.setStroke(1, adjustAlpha(accentColor, 0.28f));
        help.setBackground(bg);
        help.setMinWidth(dp(24));
        help.setMinHeight(dp(24));
        help.setContentDescription(t("查看本分区说明", "Show section details"));
        return help;
    }

    // Build #32: called by attach() right after each panel builder
    // finishes a group. Gives every visible row uniform horizontal
    // inset and vertical padding, then inserts a 1px divider (inset
    // 16dp from the left, iOS style) between consecutive rows. A
    // divider is never inserted after a label/section view, so a
    // label stays attached to the input or row it describes, and
    // hidden (GONE) views are ignored entirely.
    // Build #32: delegates to DialogTheme, the one system shared
    // with every dialog, so panel groups and dialog rows can no
    // longer drift apart.
    private void styleGroup(LinearLayout group) {
        DialogTheme.styleGroupRows(group, DialogTheme.resolve(context), context);
    }

    private LinearLayout addCard(LinearLayout parent) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        card.setBackground(rounded(cardColor, dp(16), strokeColor));
        try { card.setElevation(dp(2)); } catch (Throwable ignored) {}
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = dp(16);
        parent.addView(card, params);
        return card;
    }

    private TextView addTitle(LinearLayout parent, String text) {
        TextView title = new TextView(context);
        title.setText(text);
        title.setTextColor(primaryTextColor);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        try { title.setLetterSpacing(0.02f); } catch (Throwable ignored) {}
        addView(parent, title, 8);
        return title;
    }

    private TextView addSectionLabel(LinearLayout parent, String text) {
        // Build #45: HTML reference section labels are small gray
        // text, not bold accent. Spacing comes from the body
        // styling (16dp gaps, 4dp after labels), so no top margin
        // here.
        TextView label = new TextView(context);
        label.setTag("gramsieve_section");
        label.setText(text);
        label.setTextColor(secondaryTextColor);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        try { label.setLineSpacing(dp(2), 1.25f); } catch (Throwable ignored) {}
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = dp(10);
        params.bottomMargin = dp(10);
        parent.addView(label, params);
        return label;
    }

    private TextView addInfo(LinearLayout parent, String text) {
        // Build #43: explanations are hidden by default (user
        // 2026-10-26). The text is created GONE with its own
        // padding (styleGroupRows skips GONE views, so it keeps
        // this padding when expanded), and a "?" button beside the
        // item it explains toggles it. If no row in this group has
        // a free "?" slot (card-top introductions, button rows), a
        // standalone "?" row is added right here instead.
        TextView info = new TextView(context);
        info.setTag("gramsieve_info");
        info.setText(text);
        info.setTextColor(secondaryTextColor);
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        try { info.setLineSpacing(dp(3), 1.35f); } catch (Throwable ignored) {}
        info.setPadding(dp(16), dp(12), dp(16), dp(12));
        info.setVisibility(View.GONE);
        // Build #44: a card's first explanation is its general
        // introduction, so it belongs on the header "?" (next to
        // the card title), not on a standalone row inside the card.
        // Only when the group is still empty is this safe to assume.
        if (parent.getChildCount() == 0) {
            TextView headerHelp = groupHeaderHelp.get(parent);
            if (headerHelp != null && headerHelp.getVisibility() == View.GONE) {
                headerHelp.setVisibility(View.VISIBLE);
                headerHelp.setOnClickListener(v -> {
                    info.setVisibility(info.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
                });
                parent.addView(info, 0, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                return info;
            }
        }
        TextView helpButton = findFreeHelpButton(parent);
        if (helpButton != null) {
            helpButton.setVisibility(View.VISIBLE);
            helpButton.setOnClickListener(v -> {
                info.setVisibility(info.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            });
            ViewGroup row = (ViewGroup) helpButton.getParent();
            int index = parent.indexOfChild(row);
            parent.addView(info, index >= 0 ? index + 1 : parent.getChildCount(),
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            LinearLayout helpRow = new LinearLayout(context);
            helpRow.setOrientation(LinearLayout.HORIZONTAL);
            TextView standalone = createHelpButton();
            standalone.setVisibility(View.VISIBLE);
            standalone.setOnClickListener(v -> {
                info.setVisibility(info.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            });
            helpRow.addView(standalone, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView more = new TextView(context);
            more.setText(t("说明", "About"));
            more.setTextColor(secondaryTextColor);
            more.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            helpRow.addView(more, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            addView(parent, helpRow, 2);
            addView(parent, info, 6);
        }
        return info;
    }

    private TextView createHelpButton() {
        // Build #43: the "?" button beside a settings item. Hidden
        // until addInfo assigns an explanation to its row.
        TextView help = new TextView(context);
        help.setText("?");
        help.setTextColor(accentColor);
        help.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        help.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        help.setGravity(Gravity.CENTER);
        help.setMinWidth(dp(28));
        help.setMinHeight(dp(28));
        help.setPadding(0, 0, 0, 0);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        bg.setColor(adjustAlpha(accentColor, 0.10f));
        bg.setStroke(1, adjustAlpha(accentColor, 0.24f));
        help.setBackground(bg);
        help.setContentDescription(t("查看说明", "Show details"));
        help.setVisibility(View.GONE);
        return help;
    }

    private TextView findFreeHelpButton(LinearLayout parent) {
        // The most recent row (switch row or input label row) in
        // this group whose "?" button is still hidden and unused.
        try {
            for (int i = parent.getChildCount() - 1; i >= 0; i--) {
                View child = parent.getChildAt(i);
                if (!(child instanceof LinearLayout)) {
                    continue;
                }
                Object tag = child.getTag();
                if (!"gramsieve_row".equals(tag) && !"gramsieve_row_label".equals(tag)) {
                    continue;
                }
                LinearLayout row = (LinearLayout) child;
                for (int j = 0; j < row.getChildCount(); j++) {
                    View inner = row.getChildAt(j);
                    if (inner instanceof TextView && "?".equals(((TextView) inner).getText().toString())
                            && inner.getVisibility() == View.GONE) {
                        return (TextView) inner;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // Build #46: iOS-style toggle from the HTML v2 reference:
    // 46x28dp track, white 24dp knob, green #34c759 when on,
    // gray #e9e9eb when off. A plain View, so no theme tint
    // surprises. Same method names as CheckBox (setChecked,
    // isChecked, setOnCheckedChangeListener) so all existing
    // switch fields and save logic keep working.
    static class IosSwitchView extends View {
        interface OnCheckedChangeListener {
            void onCheckedChanged(IosSwitchView view, boolean isChecked);
        }
        private boolean checked;
        private OnCheckedChangeListener listener;
        private final android.graphics.Paint trackPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint knobPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final float density;
        private final int onTrackColor;
        private final int offTrackColor;
        private final int knobColor;

        IosSwitchView(android.content.Context context) {
            this(context, 0xFF34C759, 0xFFE9E9EB, android.graphics.Color.WHITE);
        }

        IosSwitchView(android.content.Context context, int onTrackColor, int offTrackColor, int knobColor) {
            super(context);
            density = context.getResources().getDisplayMetrics().density;
            this.onTrackColor = onTrackColor;
            this.offTrackColor = offTrackColor;
            this.knobColor = knobColor;
            knobPaint.setColor(knobColor);
            knobPaint.setShadowLayer(3f * density, 0f, 1f * density, 0x33000000);
            setLayerType(LAYER_TYPE_SOFTWARE, null);
            setClickable(true);
            setFocusable(true);
        }
        void setOnCheckedChangeListener(OnCheckedChangeListener l) {
            listener = l;
        }
        void setChecked(boolean value) {
            if (checked == value) {
                return;
            }
            checked = value;
            invalidate();
            if (listener != null) {
                listener.onCheckedChanged(this, value);
            }
        }
        boolean isChecked() {
            return checked;
        }
        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            setMeasuredDimension(Math.round(46 * density), Math.round(28 * density));
        }
        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            trackPaint.setColor(checked ? onTrackColor : offTrackColor);
            android.graphics.RectF track = new android.graphics.RectF(0, 0, w, h);
            canvas.drawRoundRect(track, h / 2, h / 2, trackPaint);
            float knobD = 24 * density;
            float knobY = (h - knobD) / 2;
            float knobX = checked ? w - knobD - 2 * density : 2 * density;
            knobPaint.setColor(knobColor);
            canvas.drawCircle(knobX + knobD / 2, knobY + knobD / 2, knobD / 2, knobPaint);
        }

        @Override
        public boolean performClick() {
            super.performClick();
            setChecked(!checked);
            return true;
        }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent event) {
            if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
                return performClick();
            }
            return super.onTouchEvent(event);
        }
    }

    private IosSwitchView addSwitch(LinearLayout parent, String text) {
        // Build #46: HTML v2 switch-item: label left (14sp, flex 1,
        // with an optional 11sp gray desc line), iOS toggle right,
        // "?" between label and toggle when an explanation is
        // attached. Row padding 13/14, min 54dp, no bottom margin:
        // hairlines from the card styling separate rows.
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setTag("gramsieve_row");
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        row.setMinimumHeight(dp(56));
        LinearLayout labelBox = new LinearLayout(context);
        labelBox.setOrientation(LinearLayout.VERTICAL);
        labelBox.setTag("gramsieve_row_labelbox");
        TextView label = new TextView(context);
        label.setText(text);
        label.setTextColor(primaryTextColor);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        try { label.setLineSpacing(dp(2), 1.25f); } catch (Throwable ignored) {}
        label.setMaxLines(2);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        labelBox.addView(label, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(labelBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(createHelpButton(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        IosSwitchView toggle = new IosSwitchView(context, switchOnColor, switchOffColor, switchKnobColor);
        toggle.setContentDescription(text);
        row.addView(toggle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setOnClickListener(v -> toggle.setChecked(!toggle.isChecked()));
        addView(parent, row, 0);
        return toggle;
    }

    private void setRowDesc(IosSwitchView toggle, String desc) {
        // HTML v2 item-desc: 11sp gray second line under the label.
        try {
            android.view.ViewParent p = toggle.getParent();
            if (!(p instanceof LinearLayout)) {
                return;
            }
            LinearLayout row = (LinearLayout) p;
            for (int i = 0; i < row.getChildCount(); i++) {
                View child = row.getChildAt(i);
                if (child instanceof LinearLayout && "gramsieve_row_labelbox".equals(child.getTag())) {
                    TextView d = new TextView(context);
                    d.setText(desc);
                    d.setTextColor(secondaryTextColor);
                    d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
                    try { d.setLineSpacing(dp(2), 1.25f); } catch (Throwable ignored) {}
                    d.setPadding(0, dp(3), 0, 0);
                    d.setMaxLines(2);
                    d.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    ((LinearLayout) child).addView(d, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                    return;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private Button secondaryButton(String text) {
        // Secondary actions use a theme-aware surface instead of a
        // hard-coded light gray, so dark Telegram themes stay balanced.
        Button button = new Button(context);
        button.setAllCaps(false);
        button.setText(text);
        button.setTextColor(primaryTextColor);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        button.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        try {
            button.setBackground(rounded(blend(backgroundColor, primaryTextColor, 0.06f), dp(10), strokeColor));
        } catch (Throwable ignored) {}
        button.setPadding(dp(12), dp(10), dp(12), dp(10));
        button.setMaxLines(1);
        button.setEllipsize(android.text.TextUtils.TruncateAt.END);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(42));
        return button;
    }

    private Button primaryButton(String text) {
        // HTML v2 btn-primary: iOS blue fill, white text.
        Button button = new Button(context);
        button.setAllCaps(false);
        button.setText(text);
        button.setTextColor(android.graphics.Color.WHITE);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        button.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        try {
            button.setBackground(rounded(accentColor, dp(10), accentColor));
        } catch (Throwable ignored) {}
        button.setPadding(dp(12), dp(10), dp(12), dp(10));
        button.setMaxLines(1);
        button.setEllipsize(android.text.TextUtils.TruncateAt.END);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(42));
        try { button.setElevation(dp(1)); } catch (Throwable ignored) {}
        return button;
    }

    // Keyword editor row (2026-10-25 UI change): keywords are NOT shown
    // directly in the rules card. The row shows only a count ("已设置 N 个
    // 关键词") plus an Edit button; tapping it opens a dialog with a
    // symbol-separated EditText. The actual value lives in a hidden
    // EditText, so save()/collectMatrix() work unchanged.
    private EditText addKeywordEditorRow(LinearLayout parent, String label, String initialValue) {
        // Build #46: HTML v2 rule-item cell: [label + count desc]
        // on the left, Edit link on the right. Tapping anywhere on
        // the cell opens the editor. The value still lives in a
        // hidden EditText, so save()/collectMatrix() work unchanged.
        LinearLayout cell = new LinearLayout(context);
        cell.setOrientation(LinearLayout.HORIZONTAL);
        cell.setGravity(Gravity.CENTER_VERTICAL);
        cell.setTag("gramsieve_rule_item");
        cell.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout textBox = new LinearLayout(context);
        textBox.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(context);
        title.setText(label);
        title.setTextColor(primaryTextColor);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        textBox.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        EditText hidden = new EditText(context);
        hidden.setText(initialValue == null ? "" : initialValue);
        hidden.setVisibility(View.GONE);
        TextView summary = new TextView(context);
        summary.setTextColor(secondaryTextColor);
        summary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        updateKeywordSummary(summary, valueOf(hidden));
        textBox.addView(summary, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        cell.addView(textBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        cell.addView(hidden, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Button editButton = new Button(context);
        editButton.setAllCaps(false);
        editButton.setText(t("编辑", "Edit"));
        editButton.setTextColor(accentColor);
        editButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        editButton.setMinHeight(0);
        editButton.setMinimumHeight(0);
        editButton.setPadding(dp(8), dp(4), dp(8), dp(4));
        editButton.setBackgroundColor(Color.TRANSPARENT);
        editButton.setOnClickListener(v -> showKeywordEditorDialog(hidden, summary));
        cell.addView(editButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        cell.setOnClickListener(v -> showKeywordEditorDialog(hidden, summary));
        addView(parent, cell, 0);
        return hidden;
    }

    // Keyword editor dialog (Build #16, UI sync fix): the old version used
    // the system AlertDialog title/buttons, which did not match the panel's
    // unified style (16dp rounded cards, theme-gray, 17sp bold titles,
    // 13sp bold accent labels, 14sp accent buttons) and showed no parsed
    // preview, so the dialog and the panel summary looked out of sync.
    // This version builds the whole dialog in panel style, with a live
    // preview that parses the input on every keystroke with the same
    // RuleTextCodec save() uses, so the preview count, the panel summary
    // after OK, and the actually saved rules are always the same.
    private void showKeywordEditorDialog(EditText hidden, TextView summary) {
        LinearLayout dialogView = new LinearLayout(context);
        dialogView.setOrientation(LinearLayout.VERTICAL);
        dialogView.setPadding(dp(16), dp(16), dp(16), dp(16)); // Build #49: spacing token L
        try {
            // Build #34: dialogView stays transparent; the glass card is
            // the dialog window background set by styleAlertDialog after show(),
            // so there is exactly one card edge, not two stacked ones.
            dialogView.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        } catch (Throwable ignored) {}
        TextView title = new TextView(context);
        title.setText(t("编辑关键词", "Edit keywords"));
        title.setTextColor(primaryTextColor);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        dialogView.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView hint = new TextView(context);
        hint.setText(t("用“、”分隔多个关键词，例如：百家乐、彩票、USDT。换行、中文逗号、英文逗号、分号也会被当成分隔符。关键词本身不要含这些分隔符。", "Separate keywords with 、, e.g. 百家乐、彩票、USDT. Newlines, Chinese/English commas and semicolons also work as separators. Do not use these separators inside a keyword."));
        hint.setTextColor(secondaryTextColor);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        try { hint.setLineSpacing(dp(2), 1.15f); } catch (Throwable ignored) {}
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintParams.topMargin = dp(8);
        dialogView.addView(hint, hintParams);
        TextView inputLabel = new TextView(context);
        inputLabel.setText(t("关键词", "Keywords"));
        inputLabel.setTextColor(accentColor);
        inputLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        inputLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelParams.topMargin = dp(12);
        labelParams.bottomMargin = dp(4);
        dialogView.addView(inputLabel, labelParams);
        EditText input = new EditText(context);
        input.setText(valueOf(hidden));
        input.setTextColor(primaryTextColor);
        input.setHintTextColor(secondaryTextColor);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        try {
            input.setBackground(rounded(adjustAlpha(primaryTextColor, 0.04f), dp(8), strokeColor));
            input.setPadding(dp(12), dp(12), dp(12), dp(12));
        } catch (Throwable ignored) {}
        input.setMinLines(3);
        input.setMaxLines(6);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setHorizontallyScrolling(false);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        dialogView.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView previewLabel = new TextView(context);
        previewLabel.setText(t("预览（与保存时解析结果一致）", "Preview (same as saved parsing)"));
        previewLabel.setTextColor(accentColor);
        previewLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        previewLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams previewLabelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        previewLabelParams.topMargin = dp(12);
        previewLabelParams.bottomMargin = dp(4);
        dialogView.addView(previewLabel, previewLabelParams);
        TextView preview = new TextView(context);
        preview.setTextColor(secondaryTextColor);
        preview.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        try { preview.setLineSpacing(dp(2), 1.15f); } catch (Throwable ignored) {}
        dialogView.addView(preview, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Runnable refreshPreview = () -> {
            String canonical = canonicalKeywords(valueOf(input));
            int count = countKeywords(canonical);
            if (count == 0) {
                preview.setText("未设置");
            } else {
                preview.setText("共 " + count + " 个：" + canonical);
            }
        };
        refreshPreview.run();
        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) { refreshPreview.run(); }
        });
        LinearLayout buttonRow = new LinearLayout(context);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams buttonRowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        buttonRowParams.topMargin = dp(12);
        dialogView.addView(buttonRow, buttonRowParams);
        AlertDialog dialog = new AlertDialog.Builder(context).setView(dialogView).create();
        Button cancelButton = toolbarButton(t("取消", "Cancel"));
        cancelButton.setOnClickListener(v -> dialog.dismiss());
        buttonRow.addView(cancelButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Button clearButton = toolbarButton(t("清空", "Clear"));
        clearButton.setOnClickListener(v -> {
            hidden.setText("");
            updateKeywordSummary(summary, "");
            dialog.dismiss();
        });
        buttonRow.addView(clearButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Button okButton = toolbarButton(t("确定", "OK"));
        okButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        okButton.setOnClickListener(v -> {
            hidden.setText(canonicalKeywords(valueOf(input)));
            updateKeywordSummary(summary, valueOf(hidden));
            dialog.dismiss();
        });
        buttonRow.addView(okButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        dialog.show();
        DialogTheme.styleAlertDialog(dialog, context);
        // Build #43: no flat square override here anymore (it killed
        // the glass window's rounded corners and rim). The glass
        // background from styleAlertDialog stays, with a rounded
        // fallback if it ever failed to apply.
        DialogTheme.ensureCardWindow(dialog, DialogTheme.resolve(context));
    }

    private static String canonicalKeywords(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return com.tianqianguai.gramsieve.core.RuleTextCodec.format(
                com.tianqianguai.gramsieve.core.RuleTextCodec.parse(
                        raw, com.tianqianguai.gramsieve.core.FilterConfig.RuleMode.KEYWORD));
    }

    private static void updateKeywordSummary(TextView summary, String raw) {
        int count = countKeywords(raw);
        if (count == 0) {
            summary.setText("未设置");
        } else {
            summary.setText("已设置 " + count + " 个关键词");
        }
    }

    private static int countKeywords(String raw) {
        // UI sync (Build #15): count with the same parser save() uses, so
        // the outside count always equals the number of rules that will
        // actually be saved, not an independent split estimate.
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        return com.tianqianguai.gramsieve.core.RuleTextCodec.parse(
                raw, com.tianqianguai.gramsieve.core.FilterConfig.RuleMode.KEYWORD).size();
    }

    private Button borderedButton(String text) {
        // Bordered actions remain quiet but still use the current theme
        // surface, which makes them legible in both light and dark mode.
        Button button = new Button(context);
        button.setAllCaps(false);
        button.setText(text);
        button.setTextColor(primaryTextColor);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        try {
            button.setBackground(rounded(blend(backgroundColor, primaryTextColor, 0.035f), dp(10), strokeColor));
        } catch (Throwable ignored) {}
        button.setPadding(dp(12), dp(8), dp(12), dp(8));
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(42));
        return button;
    }

    private EditText[] addInputPair(LinearLayout parent, String label1, String initial1,
            String label2, String initial2) {
        // Build #45: HTML reference 2-column inputs (proxy refresh
        // interval + expiry side by side, 12dp gap).
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        EditText[] result = new EditText[2];
        String[] labels = new String[]{label1, label2};
        String[] initials = new String[]{initial1, initial2};
        for (int i = 0; i < 2; i++) {
            LinearLayout col = new LinearLayout(context);
            col.setOrientation(LinearLayout.VERTICAL);
            TextView title = new TextView(context);
            title.setText(labels[i]);
            title.setTextColor(secondaryTextColor);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            col.addView(title, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            EditText input = new EditText(context);
            input.setText(initials[i] == null ? "" : initials[i]);
            input.setTextColor(primaryTextColor);
            input.setHintTextColor(secondaryTextColor);
            input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
            try {
                input.setBackground(rounded(adjustAlpha(primaryTextColor, 0.04f), dp(8), strokeColor));
                input.setPadding(dp(12), dp(10), dp(12), dp(10));
            } catch (Throwable ignored) {}
            input.setMinLines(1);
            input.setMaxLines(1);
            input.setInputType(InputType.TYPE_CLASS_NUMBER);
            LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            inputParams.topMargin = dp(4);
            col.addView(input, inputParams);
            result[i] = input;
            LinearLayout.LayoutParams colParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i == 0) {
                colParams.rightMargin = dp(6);
            } else {
                colParams.leftMargin = dp(6);
            }
            row.addView(col, colParams);
        }
        addView(parent, row, 0);
        return result;
    }

    private EditText addInput(LinearLayout parent, String label, String initialValue) {
        // Build #43: the label sits in a horizontal row with a
        // "?" slot, so an explanation added after this input lands
        // beside the field name. The row keeps the label tag, so
        // styleGroupRows still treats it as a label (no divider
        // between the label and its input, label padding kept).
        LinearLayout labelRow = new LinearLayout(context);
        labelRow.setOrientation(LinearLayout.HORIZONTAL);
        labelRow.setTag("gramsieve_row_label");
        TextView title = new TextView(context);
        title.setText(label);
        title.setTextColor(secondaryTextColor);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        labelRow.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        labelRow.addView(createHelpButton(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(parent, labelRow, 6);

        EditText input = new EditText(context);
        input.setText(initialValue == null ? "" : initialValue);
        input.setTextColor(primaryTextColor);
        input.setHintTextColor(secondaryTextColor);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        try {
            input.setBackground(rounded(adjustAlpha(primaryTextColor, 0.04f), dp(8), strokeColor));
            input.setPadding(dp(12), dp(12), dp(12), dp(12));
        } catch (Throwable ignored) {}
        input.setMinLines(1);
        input.setMaxLines(4);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setHorizontallyScrolling(false);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        addView(parent, input, 0);
        return input;
    }

    // Build #43: small on/off items in the same category share a
    // row, two per row, instead of one long single column (user
    // 2026-10-26). Only adjacent checkbox rows with short labels
    // (at most 10 characters) are paired, so long labels never get
    // squeezed, sections and inputs break a run, and full-width
    // rows stay full-width. Widths are weights, never fixed dp, so
    // nothing can overflow a narrow screen; longer labels wrap.

    private TextView rowHelpButton(View row) {
        try {
            if (!(row instanceof LinearLayout)) {
                return null;
            }
            LinearLayout container = (LinearLayout) row;
            for (int i = 0; i < container.getChildCount(); i++) {
                View inner = container.getChildAt(i);
                if (inner instanceof TextView && "?".equals(((TextView) inner).getText().toString())) {
                    return (TextView) inner;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }


    private LinearLayout findCardBody(LinearLayout card) {
        if (card == null) {
            return null;
        }
        if ("gramsieve_body".equals(card.getTag())) {
            return card;
        }
        for (int i = 0; i < card.getChildCount(); i++) {
            View child = card.getChildAt(i);
            if ("gramsieve_body".equals(child.getTag())) {
                return (LinearLayout) child;
            }
        }
        return null;
    }

    // Build #46: HTML v2 card styling. Body padding 0 (cells pad
    // themselves); switch rows and rule cells are gridded, then
    // hairline dividers separate visible rows (never after the
    // last row, never after a label row hugging its input).
    private void styleIosCard(LinearLayout card, int switchCols) {
        styleIosCard(card, switchCols, true);
    }

    // Same styling, but rule items stay full-width instead of
    // being paired into two half-width cells. The Rules card's
    // keyword rows have long titles ("保留关键词（白名单）")
    // and count summaries that wrap mid-word in a half cell and
    // sit 10dp from the card's rounded bottom edge, reading as
    // text about to overflow the background. Full-width rows
    // keep each title and summary on one line.
    private void styleIosCard(LinearLayout card, int switchCols, boolean pairRules) {
        try {
            LinearLayout body = findCardBody(card);
            if (body == null) {
                return;
            }
            body.setPadding(0, 0, 0, 0);
            pairSwitchRows(body, switchCols);
            if (pairRules) {
                pairRuleItems(body, gridCols(2));
            }
            styleBodyDividers(body);
        } catch (Throwable ignored) {
        }
    }

    private void styleBodyDividers(LinearLayout body) {
        try {
            java.util.ArrayList<View> visible = new java.util.ArrayList<>();
            for (int i = 0; i < body.getChildCount(); i++) {
                View child = body.getChildAt(i);
                if (child.getVisibility() != View.GONE) {
                    visible.add(child);
                }
            }
            for (int i = 0; i < visible.size(); i++) {
                View child = visible.get(i);
                ViewGroup.LayoutParams lp = child.getLayoutParams();
                LinearLayout.LayoutParams params;
                if (lp instanceof LinearLayout.LayoutParams) {
                    params = (LinearLayout.LayoutParams) lp;
                } else {
                    params = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    child.setLayoutParams(params);
                }
                boolean isLabel = "gramsieve_row_label".equals(child.getTag());
                params.bottomMargin = isLabel ? dp(10) : 0;
                params.topMargin = 0;
                if (i < visible.size() - 1 && !isLabel) {
                    View divider = new View(context);
                    divider.setBackgroundColor(strokeColor);
                    body.addView(divider, body.indexOfChild(child) + 1, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private boolean isSwitchRow(View view) {
        if (!(view instanceof LinearLayout) || !"gramsieve_row".equals(view.getTag())) {
            return false;
        }
        LinearLayout row = (LinearLayout) view;
        for (int i = 0; i < row.getChildCount(); i++) {
            if (row.getChildAt(i) instanceof IosSwitchView) {
                return true;
            }
        }
        return false;
    }

    private void gridRow(LinearLayout body, java.util.List<View> cells, int cols, int insertIndex) {
        // One grid row: cells at weight 1, hairline vertical
        // dividers between them (HTML cell border-right, kept for
        // every column except the last, including a lone cell in
        // a short last row). Short rows are padded with spacers
        // so cells keep column width.
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setTag("gramsieve_grid_row");
        for (int c = 0; c < cols; c++) {
            if (c < cells.size()) {
                row.addView(cells.get(c), new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                if (c < cols - 1) {
                    View divider = new View(context);
                    divider.setBackgroundColor(strokeColor);
                    row.addView(divider, new LinearLayout.LayoutParams(
                            Math.max(1, dp(1)), ViewGroup.LayoutParams.MATCH_PARENT));
                }
            } else {
                row.addView(new View(context), new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
        }
        body.addView(row, insertIndex, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void pairSwitchRows(LinearLayout body, int cols) {
        try {
            if (body == null || cols <= 1) {
                return;
            }
            java.util.ArrayList<View> visible = new java.util.ArrayList<>();
            for (int i = 0; i < body.getChildCount(); i++) {
                View child = body.getChildAt(i);
                if (child.getVisibility() != View.GONE) {
                    visible.add(child);
                }
            }
            java.util.ArrayList<View> run = new java.util.ArrayList<>();
            for (int i = 0; i <= visible.size(); i++) {
                View child = i < visible.size() ? visible.get(i) : null;
                if (child != null && isSwitchRow(child)) {
                    run.add(child);
                    continue;
                }
                // Only runs of 2+ are gridded; a lone switch
                // (e.g. the rules card's "show advanced rules")
                // stays full-width, like the reference.
                if (run.size() > 1) {
                    for (int s = 0; s < run.size(); s += cols) {
                        java.util.List<View> chunk = run.subList(s, Math.min(s + cols, run.size()));
                        int index = body.indexOfChild(chunk.get(0));
                        for (View cell : chunk) {
                            body.removeView(cell);
                        }
                        gridRow(body, new java.util.ArrayList<>(chunk), cols, index < 0 ? body.getChildCount() : index);
                    }
                }
                run.clear();
            }
        } catch (Throwable ignored) {
        }
    }

    private void pairRuleItems(LinearLayout body, int cols) {
        try {
            if (body == null || cols <= 1) {
                return;
            }
            java.util.ArrayList<View> items = new java.util.ArrayList<>();
            for (int i = 0; i < body.getChildCount(); i++) {
                View child = body.getChildAt(i);
                if (child instanceof LinearLayout && "gramsieve_rule_item".equals(child.getTag())
                        && child.getVisibility() != View.GONE) {
                    items.add(child);
                }
            }
            for (int s = 0; s + 1 < items.size(); s += cols) {
                java.util.List<View> chunk = items.subList(s, Math.min(s + cols, items.size()));
                if (chunk.size() < 2) {
                    break;
                }
                int index = body.indexOfChild(chunk.get(0));
                for (View cell : chunk) {
                    body.removeView(cell);
                }
                gridRow(body, new java.util.ArrayList<>(chunk), cols, index < 0 ? body.getChildCount() : index);
            }
        } catch (Throwable ignored) {
        }
    }

    private void addDivider(LinearLayout parent) {
        View divider = new View(context);
        divider.setBackgroundColor(strokeColor);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Math.max(1, dp(1))
        );
        params.topMargin = dp(6);
        params.bottomMargin = dp(6);
        parent.addView(divider, params);
    }

    private void addView(LinearLayout parent, View view, int bottomMarginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = dp(bottomMarginDp);
        parent.addView(view, params);
    }

    private Button toolbarButton(String text) {
        Button button = new Button(context);
        button.setAllCaps(false);
        button.setText(text);
        button.setTextColor(accentColor);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        button.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setMinWidth(dp(52));
        button.setPadding(dp(8), dp(6), dp(8), dp(6));
        button.setGravity(Gravity.CENTER);
        button.setBackgroundColor(Color.TRANSPARENT);
        return button;
    }

    private Button toolbarPrimaryButton(String text) {
        Button button = toolbarButton(text);
        button.setTextColor(Color.WHITE);
        button.setMinWidth(dp(64));
        button.setMinHeight(dp(40));
        button.setPadding(dp(10), dp(6), dp(10), dp(6));
        button.setBackground(rounded(accentColor, dp(10), accentColor));
        try { button.setElevation(dp(1)); } catch (Throwable ignored) {}
        return button;
    }

    // Single-choice items coloured with the panel text colour
    // explicitly: the dialog window is styled dark by DialogTheme,
    // while the default item text comes from the host theme and
    // reads dark-on-dark.
    private android.widget.ArrayAdapter<String> singleChoiceAdapter(String[] labels) {
        return new android.widget.ArrayAdapter<String>(
                context, android.R.layout.select_dialog_singlechoice, labels) {
            @Override
            public android.view.View getView(int position, android.view.View convertView, android.view.ViewGroup parentView) {
                android.view.View view = super.getView(position, convertView, parentView);
                if (view instanceof android.widget.TextView) {
                    ((android.widget.TextView) view).setTextColor(primaryTextColor);
                    ((android.widget.TextView) view).setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
                }
                return view;
            }
        };
    }

    // Dropdown selector, without a native Spinner popup. The
    // Spinner version failed on device: its popup window kept the
    // host's light background while the item text was coloured with
    // the panel's (dark-mode) primary colour, so the open list read
    // as white-on-white. This row shows the current value with a
    // dropdown arrow, and tapping it opens a single-choice dialog
    // styled by DialogTheme (the same pattern as the model picker),
    // which reads in both themes. `values` must parallel `labels`;
    // `onPicked` receives the newly picked value.
    private interface DropdownPickListener {
        void onPicked(Object value);
    }

    private android.widget.TextView addDropdown(LinearLayout parent, String[] labels, Object[] values,
            Object current, DropdownPickListener onPicked) {
        int selected = 0;
        if (values != null) {
            for (int i = 0; i < values.length; i++) {
                if (current == null ? values[i] == null : current.equals(values[i])) {
                    selected = i;
                    break;
                }
            }
        }
        final int[] selectedIndex = new int[]{selected};
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        try {
            row.setBackground(rounded(adjustAlpha(primaryTextColor, 0.04f), dp(8), strokeColor));
        } catch (Throwable ignored) {}
        row.setPadding(dp(12), dp(12), dp(12), dp(12));
        row.setMinimumHeight(dp(48));
        android.widget.TextView valueView = new android.widget.TextView(context);
        valueView.setText(labels.length > 0 ? labels[selectedIndex[0]] : "");
        valueView.setTextColor(primaryTextColor);
        valueView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        row.addView(valueView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        android.widget.TextView arrow = new android.widget.TextView(context);
        arrow.setText("▾");
        arrow.setTextColor(secondaryTextColor);
        arrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        row.addView(arrow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setOnClickListener(v -> {
            android.app.Activity activity = activityFromContext(context);
            if (activity == null || activity.isFinishing()) {
                // No activity to host a dialog: cycle to the next
                // option, so the selector still works.
                if (labels.length > 0 && values != null && values.length > 0) {
                    selectedIndex[0] = (selectedIndex[0] + 1) % labels.length;
                    valueView.setText(labels[selectedIndex[0]]);
                    if (onPicked != null) {
                        onPicked.onPicked(values[selectedIndex[0]]);
                    }
                }
                return;
            }
            int[] picked = new int[]{selectedIndex[0]};
            android.widget.ArrayAdapter<String> choiceAdapter = singleChoiceAdapter(labels);
            android.app.AlertDialog picker = new android.app.AlertDialog.Builder(activity)
                    .setSingleChoiceItems(choiceAdapter, selectedIndex[0],
                            (dialog, which) -> picked[0] = which)
                    .setNegativeButton(t("取消", "Cancel"), null)
                    .setPositiveButton(t("确定", "OK"), (dialog, which) -> {
                        selectedIndex[0] = picked[0];
                        valueView.setText(labels[selectedIndex[0]]);
                        if (onPicked != null && values != null && selectedIndex[0] < values.length) {
                            onPicked.onPicked(values[selectedIndex[0]]);
                        }
                    })
                    .create();
            picker.show();
            DialogTheme.styleAlertDialog(picker, context);
        });
        addView(parent, row, 0);
        return valueView;
    }

    // Build #45: single-choice groups are radios again, per the
    // HTML reference (user 2026-10-26: radios in a 2-column grid).
    // Exclusion lives on the buttons themselves, not on a
    // RadioGroup, so pairing them into grid rows later is safe.
    // Semantics: exactly one checked, at least one stays on.
    private void addRadioChoice(LinearLayout group, String text, Object value) {
        android.widget.RadioButton box = new android.widget.RadioButton(context);
        box.setMinHeight(dp(44));
        box.setText(text);
        box.setTextColor(primaryTextColor);
        box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        box.setMaxLines(2);
        box.setEllipsize(android.text.TextUtils.TruncateAt.END);
        box.setTag(value);
        try {
            int[][] states = new int[][]{{android.R.attr.state_checked}, {}};
            int[] boxColors = new int[]{accentColor, adjustAlpha(secondaryTextColor, 0.6f)};
            box.setButtonTintList(new ColorStateList(states, boxColors));
        } catch (Throwable ignored) {}
        box.setOnCheckedChangeListener((button, isChecked) -> {
            if (!isChecked) {
                boolean anyChecked = false;
                for (int i = 0; i < group.getChildCount(); i++) {
                    View sibling = group.getChildAt(i);
                    if (sibling instanceof android.widget.RadioButton
                            && ((android.widget.RadioButton) sibling).isChecked()) {
                        anyChecked = true;
                        break;
                    }
                }
                if (!anyChecked) {
                    box.setChecked(true);
                }
                return;
            }
            for (int i = 0; i < group.getChildCount(); i++) {
                View sibling = group.getChildAt(i);
                if (sibling instanceof android.widget.RadioButton && sibling != box) {
                    ((android.widget.RadioButton) sibling).setChecked(false);
                }
            }
        });
        group.addView(box, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
    }

    private void checkTaggedChoice(LinearLayout group, Object value) {
        if (group == null) {
            return;
        }
        View target = null;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (!(child instanceof android.widget.RadioButton)) {
                continue;
            }
            Object tag = child.getTag();
            if (value == null ? tag == null : value.equals(tag)) {
                target = child;
                break;
            }
        }
        if (target == null && group.getChildCount() > 0
                && group.getChildAt(0) instanceof android.widget.RadioButton) {
            target = group.getChildAt(0);
        }
        if (target != null) {
            ((android.widget.RadioButton) target).setChecked(true);
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child instanceof android.widget.RadioButton && child != target) {
                    ((android.widget.RadioButton) child).setChecked(false);
                }
            }
        }
    }

    private void pairBareChoices(LinearLayout group, int cols) {
        // HTML v2 radio grids: rows of `cols` cells with hairline
        // vertical dividers; a short last row is padded with
        // spacers so cells keep column width.
        try {
            if (group == null || cols <= 1) {
                return;
            }
            java.util.ArrayList<View> items = new java.util.ArrayList<>();
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child instanceof android.widget.RadioButton) {
                    items.add(child);
                }
            }
            for (int s = 0; s < items.size(); s += cols) {
                java.util.List<View> chunk = items.subList(s, Math.min(s + cols, items.size()));
                int index = group.indexOfChild(chunk.get(0));
                if (index < 0) {
                    continue;
                }
                for (View cell : chunk) {
                    group.removeView(cell);
                }
                gridRow(group, new java.util.ArrayList<>(chunk), cols, index);
            }
        } catch (Throwable ignored) {
        }
    }

    private String selectedLanguageTag() {
        return languageSelected instanceof String
                ? (String) languageSelected : FilterConfig.APP_LANGUAGE_SYSTEM;
    }

    private FilterConfig.Action selectedAction() {
        return actionSelected instanceof FilterConfig.Action
                ? (FilterConfig.Action) actionSelected : FilterConfig.Action.HIDE;
    }

    private Object selectedChoiceTag(LinearLayout group) {
        if (group == null) {
            return null;
        }
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof android.widget.RadioButton
                    && ((android.widget.RadioButton) child).isChecked()) {
                return child.getTag();
            }
        }
        return null;
    }

    private String targetLabel(FilterConfig.RuleTarget target) {
        switch (target == null ? FilterConfig.RuleTarget.ANY : target) {
            case TEXT:
                return t("消息文字", "Message text");
            case CAPTION:
                return t("媒体说明", "Caption");
            case BUTTONS:
                return t("按钮", "Buttons");
            case SENDER:
                return t("发送者", "Sender");
            case CHAT:
                return t("聊天", "Chat");
            case ANY:
            default:
                return t("全字段", "Any field");
        }
    }

    private String targetScope(FilterConfig.RuleTarget target) {
        switch (target == null ? FilterConfig.RuleTarget.ANY : target) {
            case TEXT:
                return t("只检查消息文字。", "Only checks message text.");
            case CAPTION:
                return t("只检查媒体说明。", "Only checks media captions.");
            case BUTTONS:
                return t("只检查按钮文字或按钮链接。", "Only checks button labels or URLs.");
            case SENDER:
                return t("只检查发送者名称或发送者 ID。", "Only checks sender names or IDs.");
            case CHAT:
                return t("只检查聊天名称或聊天 ID。", "Only checks chat names or IDs.");
            case ANY:
            default:
                return t("检查消息文字、媒体说明、按钮、发送者、聊天名或对应 ID。", "Checks message text, captions, buttons, senders, chat names, or matching IDs.");
        }
    }

    private String t(String zh, String en) {
        return chinese ? zh : en;
    }

    // Build #46: HTML v2 responsive breakpoints. Column counts
    // adapt to the panel width: under 420dp everything stacks
    // (except backup buttons, clamped to 2 by their caller),
    // 420-640dp three/four-column grids drop to two columns.
    private int gridCols(int requested) {
        if (screenWidthDp < 420) {
            return 1;
        }
        if (screenWidthDp < 640) {
            return Math.min(requested, 2);
        }
        return requested;
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                context.getResources().getDisplayMetrics()
        ));
    }

    private int resolveThemeColor(int attr, int fallback) {
        TypedValue value = new TypedValue();
        if (!context.getTheme().resolveAttribute(attr, value, true)) {
            return fallback;
        }
        if (value.resourceId != 0) {
            try {
                return context.getColor(value.resourceId);
            } catch (Resources.NotFoundException ignored) {
                try {
                    ColorStateList colors = context.getColorStateList(value.resourceId);
                    return colors == null ? fallback : colors.getDefaultColor();
                } catch (Resources.NotFoundException ignoredAgain) {
                    return fallback;
                }
            }
        }
        if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT && value.type <= TypedValue.TYPE_LAST_COLOR_INT) {
            return value.data;
        }
        return fallback;
    }

    private static int ensureContrast(int foreground, int background, int primary) {
        if (contrastRatio(foreground, background) >= 4.5f) {
            return foreground;
        }
        int result = foreground;
        for (int i = 0; i < 10; i++) {
            result = blend(result, primary, 0.3f);
            if (contrastRatio(result, background) >= 4.5f) {
                break;
            }
        }
        return result;
    }

    private static int blend(int from, int to, float factor) {
        return Color.argb(
                Math.round(Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * factor),
                Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * factor),
                Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * factor),
                Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * factor));
    }

    private static float contrastRatio(int foreground, int background) {
        float l1 = luminance(foreground);
        float l2 = luminance(background);
        float light = Math.max(l1, l2);
        float dark = Math.min(l1, l2);
        return (light + 0.05f) / (dark + 0.05f);
    }

    private static float luminance(int color) {
        float r = Color.red(color) / 255f;
        float g = Color.green(color) / 255f;
        float b = Color.blue(color) / 255f;
        r = r <= 0.03928f ? r / 12.92f : (float) Math.pow((r + 0.055f) / 1.055f, 2.4);
        g = g <= 0.03928f ? g / 12.92f : (float) Math.pow((g + 0.055f) / 1.055f, 2.4);
        b = b <= 0.03928f ? b / 12.92f : (float) Math.pow((b + 0.055f) / 1.055f, 2.4);
        return 0.2126f * r + 0.7152f * g + 0.0722f * b;
    }

    private int telegramThemeColor(String keyFieldName, int fallback) {
        try {
            ClassLoader classLoader = context.getClassLoader();
            Class<?> themeClass = Class.forName("org.telegram.ui.ActionBar.Theme", false, classLoader);
            Field keyField = themeClass.getDeclaredField(keyFieldName);
            keyField.setAccessible(true);
            int key = keyField.getInt(null);
            Method getColor = themeClass.getDeclaredMethod("getColor", int.class);
            getColor.setAccessible(true);
            Object color = getColor.invoke(null, key);
            return color instanceof Integer ? (Integer) color : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static GradientDrawable rounded(int color, int radius, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        drawable.setStroke(1, strokeColor);
        return drawable;
    }

    private static int adjustAlpha(int color, float factor) {
        int alpha = Math.round(Color.alpha(color) * factor);
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private static boolean isChineseLocale(Context context) {
        try {
            Locale locale = context.getResources().getConfiguration().locale;
            return locale != null && "zh".equalsIgnoreCase(locale.getLanguage());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String valueOf(EditText editText) {
        return editText == null || editText.getText() == null ? "" : editText.getText().toString();
    }

    private static final class RuleInputs {
        EditText matchKeywords;
        EditText matchRegex;
        EditText keepKeywords;
        EditText keepRegex;
    }
}

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
    private final int toolbarColor;
    private final int toolbarTextColor;
    private final Map<FilterConfig.RuleTarget, RuleInputs> ruleInputs =
            new EnumMap<>(FilterConfig.RuleTarget.class);

    private FrameLayout overlay;
    private OnBackInvokedDispatcher backDispatcher;
    private OnBackInvokedCallback backCallback;
    private android.widget.CheckBox enabledSwitch;
    private android.widget.CheckBox debugLoggingSwitch;
    private android.widget.CheckBox excludeChatSwitch;
    private android.widget.CheckBox chatAntiRecallSwitch;
    private android.widget.CheckBox sponsoredSwitch;
    private android.widget.CheckBox videoAdsSwitch;
    private android.widget.CheckBox forwardSaveSwitch;
    private android.widget.CheckBox saveStoriesSwitch;
    private android.widget.CheckBox preventDeleteSwitch;
    private android.widget.CheckBox downloadSpeedSwitch;
    private android.widget.CheckBox localPremiumSwitch;
    private android.widget.CheckBox autoTranslateSwitch;
    private android.widget.CheckBox pinnedHighlightSwitch;
    private android.widget.CheckBox classifyBarSwitch;
    private android.widget.CheckBox autoArchiveFolderSwitch;
    private android.widget.CheckBox aiLlmSwitch;
    private android.widget.CheckBox aiFallbackSwitch;
    private android.widget.CheckBox proxyAutoAddSwitch;
    private android.widget.CheckBox proxyAutoSwitchSwitch;
    private EditText proxySourceInput;
    private EditText proxyRefreshInput;
    private EditText proxyExpiryInput;
    private EditText proxyPastedInput;
    private EditText proxyFolderInput;
    private TextView proxySummary;
    private String lastProxyFolderDiag = "";
    private java.util.List<FilterConfig.ProxyEntry> proxyWorkingEntries;
    private EditText aiBaseUrlInput;
    private EditText aiModelInput;
    private EditText aiKeyInput;
    private RadioGroup languageGroup;
    private RadioGroup actionGroup;

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
        this.accentColor = telegramThemeColor("key_actionBarDefaultIcon", androidAccent);
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

        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(false);
        scrollView.setBackgroundColor(cardColor);
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        // Build #32: iOS grouped-list screen: the gray grouped
        // background fills the screen, groups are full-width solid
        // blocks, so the container has no horizontal padding.
        container.setBackgroundColor(cardColor);
        container.setPadding(0, dp(8), 0, dp(24));
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
        pairCheckboxRows((LinearLayout) container.getChildAt(container.getChildCount() - 1)); styleGroup((LinearLayout) container.getChildAt(container.getChildCount() - 1));
        if (chatMode) {
            buildChatAntiRecallCard(container);
            pairCheckboxRows((LinearLayout) container.getChildAt(container.getChildCount() - 1)); styleGroup((LinearLayout) container.getChildAt(container.getChildCount() - 1));
        } else {
            buildEnhancementCard(container);
            pairCheckboxRows((LinearLayout) container.getChildAt(container.getChildCount() - 1)); styleGroup((LinearLayout) container.getChildAt(container.getChildCount() - 1));
            buildDialogListCard(container);
            pairCheckboxRows((LinearLayout) container.getChildAt(container.getChildCount() - 1)); styleGroup((LinearLayout) container.getChildAt(container.getChildCount() - 1));
            buildAiCard(container);
            pairCheckboxRows((LinearLayout) container.getChildAt(container.getChildCount() - 1)); styleGroup((LinearLayout) container.getChildAt(container.getChildCount() - 1));
            buildProxyCard(container);
            pairCheckboxRows((LinearLayout) container.getChildAt(container.getChildCount() - 1)); styleGroup((LinearLayout) container.getChildAt(container.getChildCount() - 1));
            buildTranslateCard(container);
            pairCheckboxRows((LinearLayout) container.getChildAt(container.getChildCount() - 1)); styleGroup((LinearLayout) container.getChildAt(container.getChildCount() - 1));
        }
        buildRulesCard(container);
        pairCheckboxRows((LinearLayout) container.getChildAt(container.getChildCount() - 1)); styleGroup((LinearLayout) container.getChildAt(container.getChildCount() - 1));
        if (!chatMode) {
            buildBackupCard(container);
            pairCheckboxRows((LinearLayout) container.getChildAt(container.getChildCount() - 1)); styleGroup((LinearLayout) container.getChildAt(container.getChildCount() - 1));
        } else {
            // Build #31: the backup card (and its log export) only
            // existed in the global panel, so a user opening settings
            // from inside a chat could never find log export at all.
            // Log export is mode-independent, so chat mode gets its
            // own log card; full backup stays global-only, because
            // importing a full backup from a chat panel could replace
            // global settings from the wrong context.
            buildLogExportCard(container);
            pairCheckboxRows((LinearLayout) container.getChildAt(container.getChildCount() - 1)); styleGroup((LinearLayout) container.getChildAt(container.getChildCount() - 1));
        }

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
        toolbar.setPadding(dp(8), 0, dp(8), 0);
        toolbar.setBackgroundColor(toolbarColor);

        Button backButton = toolbarButton(t("返回", "Back"));
        backButton.setOnClickListener(v -> close());
        toolbar.addView(backButton, new LinearLayout.LayoutParams(dp(80), ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(context);
        title.setText(chatMode ? t("GramSieve 聊天设置", "GramSieve Chat Settings") : t("GramSieve 设置", "GramSieve Settings"));
        title.setTextColor(toolbarTextColor);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        Button saveButton = toolbarButton(t("保存", "Save"));
        saveButton.setOnClickListener(v -> save());
        toolbar.addView(saveButton, new LinearLayout.LayoutParams(dp(80), ViewGroup.LayoutParams.WRAP_CONTENT));
        return toolbar;
    }

    private void buildGeneralCard(LinearLayout container) {
        LinearLayout card = addGroup(container, chatMode ? t("当前聊天", "Current Chat") : t("全局配置", "Global Settings"));

        FilterConfig.ChatRuleSet chatRuleSet = chatMode
                ? baseConfig.getOrCreateChatRuleSet(dialogId).deepCopy().sanitize()
                : null;

        enabledSwitch = addSwitch(card, t("启用过滤", "Enable filtering"));
        enabledSwitch.setChecked(chatMode ? chatRuleSet.enabled : baseConfig.enabled);

        if (chatMode) {
            excludeChatSwitch = addSwitch(card, t("这个聊天不使用全局规则", "Do not apply global rules to this chat"));
            excludeChatSwitch.setChecked(chatRuleSet.excludeFromGlobal);
            String scope = chatTitle.isBlank()
                    ? t("当前聊天 ID：", "Current chat ID: ") + dialogId
                    : t("当前聊天：", "Current chat: ") + chatTitle;
            addInfo(card, scope);
            return;
        }

        debugLoggingSwitch = addSwitch(card, t("详细调试日志", "Verbose debug logging"));
        debugLoggingSwitch.setChecked(baseConfig.debugLogging);

        addSectionLabel(card, t("界面语言", "Interface language"));
        languageGroup = new RadioGroup(context);
        languageGroup.setOrientation(RadioGroup.VERTICAL);
        addRadio(languageGroup, t("跟随系统", "Follow system"), FilterConfig.APP_LANGUAGE_SYSTEM);
        addRadio(languageGroup, "English", FilterConfig.APP_LANGUAGE_ENGLISH);
        addRadio(languageGroup, "简体中文", FilterConfig.APP_LANGUAGE_SIMPLIFIED_CHINESE);
        checkTaggedRadio(languageGroup, FilterConfig.normalizeAppLanguageTag(baseConfig.appLanguageTag));
        addView(card, languageGroup, 8);

        addSectionLabel(card, t("匹配后的动作", "Match action"));
        actionGroup = new RadioGroup(context);
        actionGroup.setOrientation(RadioGroup.VERTICAL);
        addRadio(actionGroup, t("仅本地隐藏", "Hide locally"), FilterConfig.Action.HIDE);
        addRadio(actionGroup, t("仅本地折叠", "Collapse locally"), FilterConfig.Action.COLLAPSE);
        addRadio(actionGroup, t("调试标记", "Debug mark"), FilterConfig.Action.DEBUG_MARK);
        checkTaggedRadio(actionGroup, baseConfig.action == null ? FilterConfig.Action.HIDE : baseConfig.action);
        addView(card, actionGroup, 0);
    }

    private void buildEnhancementCard(LinearLayout container) {
        LinearLayout card = addGroup(container, t("增强功能", "Enhancements"));
        addInfo(card, t("从 NoAdsTelegram / TeleVip 移植的功能，按分类开关，默认全部关闭，开启后保存即生效（部分需重新打开聊天列表）。", "Features ported from NoAdsTelegram / TeleVip, grouped by category. All off by default; saving applies them (some need the chat list reopened)."));

        addSectionLabel(card, t("广告", "Ads"));
        sponsoredSwitch = addSwitch(card, t("去赞助消息", "Remove sponsored messages"));
        sponsoredSwitch.setChecked(baseConfig.disableSponsoredMessages);
        videoAdsSwitch = addSwitch(card, t("去视频广告", "Remove video ads"));
        videoAdsSwitch.setChecked(baseConfig.disableVideoAds);

        addSectionLabel(card, t("消息", "Messages"));
        forwardSaveSwitch = addSwitch(card, t("允许转发和保存", "Allow forwarding and saving"));
        forwardSaveSwitch.setChecked(baseConfig.enableForwardSave);
        preventDeleteSwitch = addSwitch(card, t("防止消息删除", "Prevent message deletion"));
        preventDeleteSwitch.setChecked(baseConfig.preventMessageDelete);
        addInfo(card, t("防删除与主动加载防撤回是两套机制，可同时开；防删除会直接拦下服务端的删除更新。", "Delete prevention is a second mechanism alongside proactive-loading anti-recall; it blocks the server delete update itself."));

        addSectionLabel(card, t("动态", "Stories"));
        saveStoriesSwitch = addSwitch(card, t("允许保存动态", "Allow saving stories"));
        saveStoriesSwitch.setChecked(baseConfig.enableSaveStories);
        addInfo(card, t("本版为简化移植：允许动态截图、启用已有的保存菜单项；若 Conso 的动态菜单里本来就没有保存项，本开关单独不起作用，需后续再补。", "Simplified port in this build: allows story screenshots and enables an existing save menu item. If Conso's story menu has no save item at all, this switch alone will not add one."));

        addSectionLabel(card, t("性能", "Performance"));
        downloadSpeedSwitch = addSwitch(card, t("提升下载速度", "Boost download speed"));
        downloadSpeedSwitch.setChecked(baseConfig.boostDownloadSpeed);
        addInfo(card, t("只改客户端下载并行度（大文件分块 512 KiB、并行 8 路）；服务端限速仍在。", "Only changes client download parallelism (512 KiB chunks, 8 parallel requests); server limits still apply."));

        addSectionLabel(card, t("本地 Premium", "Local Premium"));
        localPremiumSwitch = addSwitch(card, t("本地 Premium 解锁", "Enable local Premium"));
        localPremiumSwitch.setChecked(baseConfig.localPremium);
        addInfo(card, t("仅本地生效：让客户端认为你是 Premium，可突破置顶聊天数等客户端检查（实测为准，服务端仍可能限制）。服务端 Premium 功能（大文件上传、语音转文字等）仍需官方订阅，且开启本地 Premium 有封号风险，请自行决定。", "Local only: makes the client treat you as Premium, unlocking client-side checks such as the pinned-chat limit (verify on device; the server may still limit). Server Premium features still need a real subscription, and local Premium carries a ban risk."));
    }

    private void buildDialogListCard(LinearLayout container) {
        LinearLayout card = addGroup(container, t("聊天列表", "Chat List"));
        addInfo(card, t("只影响聊天列表的外观，不改动聊天内容与置顶本身。", "Only affects the chat list appearance, not chat content or pinning itself."));
        pinnedHighlightSwitch = addSwitch(card, t("置顶聊天区分显示（左侧色条 + 浅色背景）", "Highlight pinned chats (left colour bar + light background)"));
        pinnedHighlightSwitch.setChecked(baseConfig.highlightPinnedDialogs);
        autoArchiveFolderSwitch = addSwitch(card, t("文件夹聊天自动归档", "Auto-archive chats in folders"));
        autoArchiveFolderSwitch.setChecked(baseConfig.autoArchiveFolderChats);
        addInfo(card, t("进文件夹的聊天自动归档（主列表不再出现，只在文件夹和归档里看）；从所有文件夹移出后自动取消归档回到主列表。只动模块自己归档的聊天：你手动归档的聊天不会被自动取消归档，你手动取消归档的文件夹聊天也不会被重新归档（除非把它移出文件夹再加回来）。", "Chats in a folder are auto-archived (hidden from the main list, visible in the folder and in Archive); removed from all folders, they auto-unarchive back to the main list. Only chats this module archived are touched: a chat you archived manually is never auto-unarchived, and a folder chat you unarchived manually is not re-archived (unless you remove it from the folder and add it back)."));
    }

    private void buildAiCard(LinearLayout container) {
        LinearLayout card = addGroup(container, t("智能分析（AI）", "Smart Analysis (AI)"));
        addInfo(card, t("默认是本地规则分析：离线、免费、私密、即时，关键词、域名、IP、@、混淆网址、QQ、USDT 地址都能识别。开启大模型后，智能分析会变成可插拔后端：本地先出候选，大模型再补充建议，两者去重后一起让你确认；大模型失败时按下面开关回退本地。", "Default is local rule analysis: offline, free, private, instant, covering keywords, domains, IPs, @handles, obfuscated URLs, QQ and USDT addresses. With the LLM on, smart analysis becomes a pluggable backend: local candidates first, LLM suggestions added and deduplicated, all confirmed by you; on LLM failure it falls back to local if the switch below is on."));
        aiLlmSwitch = addSwitch(card, t("启用大模型智能分析（需自备 API Key）", "Enable LLM smart analysis (bring your own API key)"));
        aiLlmSwitch.setChecked(baseConfig.aiAnalysisMode == 1);
        addInfo(card, t("只在你点“智能分析并屏蔽”时才调用大模型，不会对每条消息自动过滤。每次只发消息正文/说明/按钮文字（最多 2000 字），不发发送者名、聊天 id、用户 id。消息会发给上面地址的服务方，按该服务方计费，并受其隐私政策约束；Key 只存在本机模块配置里，不进代码仓库，不打进日志。", "Only called when you tap smart analysis, never for automatic per-message filtering. Each call sends only message text / caption / button text (max 2000 chars), no sender name, chat id or user id. Messages go to the service at the base URL above, billed by that provider under its privacy policy; the key lives only in this device's module config, never in the repo, never in logs."));
        aiBaseUrlInput = addInput(card, t("API 地址（OpenAI 兼容，形如 https://api.openai.com/v1）", "API base URL (OpenAI-compatible, e.g. https://api.openai.com/v1)"), baseConfig.aiApiBaseUrl == null || baseConfig.aiApiBaseUrl.isBlank() ? "https://api.openai.com/v1" : baseConfig.aiApiBaseUrl);
        aiModelInput = addInput(card, t("模型", "Model"), baseConfig.aiApiModel == null || baseConfig.aiApiModel.isBlank() ? "gpt-4o-mini" : baseConfig.aiApiModel);
        aiKeyInput = addInput(card, t("API Key（输入时掩码显示，保存后不回显在日志中）", "API key (masked while typing, never echoed in logs)"), baseConfig.aiApiKey == null ? "" : baseConfig.aiApiKey);
        try {
            aiKeyInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        } catch (Throwable ignored) {}
        aiFallbackSwitch = addSwitch(card, t("大模型失败 / 超时时回退本地规则", "Fall back to local rules when the LLM fails or times out"));
        aiFallbackSwitch.setChecked(baseConfig.aiFallbackToLocal);
    }

    private void buildProxyCard(LinearLayout container) {
        LinearLayout card = addGroup(container, t("代理（TG 直链）", "Proxy (TG Links)"));
        addInfo(card, t("自动抓取、测速、自动添加并切换到最快代理。来源不会硬编码某个第三方：填一个你信任的代理列表页地址（页面里含 t.me/proxy 链接即可），或在下面直接粘贴链接；模块按刷新间隔自动抓取，测速后把最快的设为当前代理。公开代理的运营者能看到你的 IP 和 Telegram 连接时间信息，但 MTProto 内容本身是加密的。", "Auto-fetch, test, auto-add and switch to the fastest proxy. No third-party source is hardcoded: fill a trusted proxy list page URL (containing t.me/proxy links) or paste links below; the module fetches on the refresh interval, tests, and sets the fastest as current. A proxy operator can see your IP and Telegram connection timing, but MTProto content stays encrypted."));
        boolean firstOpen = !baseConfig.proxyConfigured;
        proxyAutoAddSwitch = addSwitch(card, t("自动添加新代理", "Auto-add new proxies"));
        proxyAutoAddSwitch.setChecked(firstOpen || baseConfig.proxyAutoAdd);
        proxyAutoSwitchSwitch = addSwitch(card, t("自动切换到最快代理", "Auto-switch to the fastest proxy"));
        proxyAutoSwitchSwitch.setChecked(firstOpen || baseConfig.proxyAutoSwitch);
        proxySourceInput = addInput(card, t("代理来源地址（可空，页面需含 t.me/proxy 链接）", "Proxy source URL (optional, page must contain t.me/proxy links)"), baseConfig.proxySourceUrl == null ? "" : baseConfig.proxySourceUrl);
        proxyRefreshInput = addInput(card, t("刷新间隔（小时，默认 6）", "Refresh interval (hours, default 6)"), Integer.toString(firstOpen ? 6 : baseConfig.proxyRefreshHours));
        proxyExpiryInput = addInput(card, t("失效时间（小时，默认 24：超过此时间未测通即为失效）", "Expiry time (hours, default 24: untested longer than this counts as expired)"), Integer.toString(firstOpen ? 24 : baseConfig.proxyExpiryHours));
        proxyPastedInput = addInput(card, t("直接粘贴代理链接（每行一个 t.me/proxy 或 tg://proxy 链接）", "Paste proxy links directly (one t.me/proxy or tg://proxy link per line)"), baseConfig.proxyPastedLinks == null ? "" : baseConfig.proxyPastedLinks);
        proxyFolderInput = addInput(card, t("代理文件夹（只扫这个文件夹里的聊天，默认 节点）", "Proxy folder (only scan chats in this folder, default 节点)"), firstOpen ? "节点" : (baseConfig.proxyFolderName == null ? "节点" : baseConfig.proxyFolderName));
        addInfo(card, t("自动维护：只要 Conso 在运行，“自动添加”开着时，每到刷新间隔就会自动跑一轮：扫描上面这些来源、测速、切到最快、把测通的同步进 Conso 代理列表，并把本轮测死的（且是模块同步进去的）从客户端列表里剔除；Conso 被彻底关掉后，下次打开聊天列表时会补跑一次。", "Auto-maintenance: while Conso is running and auto-add is on, every refresh interval runs a full round: scan these sources, test, switch to fastest, sync tested-good entries into Conso's proxy list, and prune entries this round tested dead (and synced by the module) from the client list. If Conso was fully closed, the next chat-list open catches up once."));
        // Build #28: folder picker instead of typing. Tapping the
        // field (or the button below) lists existing user folders and
        // fills the field with the picked one, so a typo or a renamed
        // folder cannot silently make the scan find nothing.
        try {
            proxyFolderInput.setFocusable(false);
            proxyFolderInput.setFocusableInTouchMode(false);
            proxyFolderInput.setOnClickListener(v -> showProxyFolderPicker());
        } catch (Throwable ignored) {}
        Button proxyFolderPickButton = toolbarButton(t("选择文件夹", "Pick folder"));
        proxyFolderPickButton.setOnClickListener(v -> showProxyFolderPicker());
        addView(card, proxyFolderPickButton, 4);
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
        addView(card, proxySummary, 8);
        updateProxySummary();
        LinearLayout buttonRow = new LinearLayout(context);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        Button refreshButton = toolbarButton(t("立即刷新", "Refresh now"));
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
        buttonRow.addView(refreshButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Button manageButton = toolbarButton(t("管理代理", "Manage proxies"));
        manageButton.setOnClickListener(v -> showProxyManageDialog());
        buttonRow.addView(manageButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(card, buttonRow, 4);
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
        LinearLayout buttonRow = new LinearLayout(context);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        Button exportButton = toolbarButton(t("导出规则", "Export rules"));
        exportButton.setOnClickListener(v -> showExportRulesDialog());
        buttonRow.addView(exportButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Button importButton = toolbarButton(t("导入规则", "Import rules"));
        importButton.setOnClickListener(v -> showImportRulesDialog());
        buttonRow.addView(importButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(card, buttonRow, 4);
        LinearLayout buttonRow2 = new LinearLayout(context);
        buttonRow2.setOrientation(LinearLayout.HORIZONTAL);
        Button exportFullButton = toolbarButton(t("导出完整备份", "Export full backup"));
        exportFullButton.setOnClickListener(v -> showExportFullDialog());
        buttonRow2.addView(exportFullButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Button importFullButton = toolbarButton(t("导入完整备份", "Import full backup"));
        importFullButton.setOnClickListener(v -> showImportFullDialog());
        buttonRow2.addView(importFullButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Button exportLogButton = toolbarButton(t("导出日志", "Export log"));
        exportLogButton.setOnClickListener(v -> showExportLogDialog());
        buttonRow2.addView(exportLogButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(card, buttonRow2, 4);
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
            dialogView.setPadding(dp(20), dp(20), dp(20), dp(20)); // Build #41: wider dialog side margins
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
            jsonView.setBackground(rounded(adjustAlpha(primaryTextColor, 0.07f), dp(10), strokeColor));
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
                        clipboard.setPrimaryClip(ClipData.newPlainText("GramSieve rules", json));
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
            DialogTheme.ensureGlassWindow(dialog, DialogTheme.resolve(context));
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
            dialogView.setPadding(dp(20), dp(20), dp(20), dp(20)); // Build #41: wider dialog side margins
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
            input.setBackground(rounded(adjustAlpha(primaryTextColor, 0.07f), dp(10), strokeColor));
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
            DialogTheme.ensureGlassWindow(dialog, DialogTheme.resolve(context));
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
            dialogView.setPadding(dp(20), dp(20), dp(20), dp(20)); // Build #41: wider dialog side margins
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
            contentView.setBackground(rounded(adjustAlpha(primaryTextColor, 0.07f), dp(10), strokeColor));
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
            DialogTheme.ensureGlassWindow(dialog, DialogTheme.resolve(context));
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
                json, "GramSieve full backup", t("分享完整备份", "Share full backup"),
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
            dialogView.setPadding(dp(20), dp(20), dp(20), dp(20)); // Build #41: wider dialog side margins
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
            input.setBackground(rounded(adjustAlpha(primaryTextColor, 0.07f), dp(10), strokeColor));
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
            DialogTheme.ensureGlassWindow(dialog, DialogTheme.resolve(context));
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
                log, "GramSieve log", t("分享日志", "Share log"), fileName, log, "log");
    }

    private void applyProxyUiToConfig(FilterConfig config) {
        if (config == null) return;
        config.proxyAutoAdd = proxyAutoAddSwitch != null && proxyAutoAddSwitch.isChecked();
        config.proxyAutoSwitch = proxyAutoSwitchSwitch != null && proxyAutoSwitchSwitch.isChecked();
        config.proxyRefreshHours = proxyInt(proxyRefreshInput, 6);
        config.proxyExpiryHours = proxyInt(proxyExpiryInput, 24);
        if (proxySourceInput != null) config.proxySourceUrl = valueOf(proxySourceInput).trim();
        if (proxyPastedInput != null) config.proxyPastedLinks = valueOf(proxyPastedInput);
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
                ? t("未保存代理：先填来源或粘贴链接，再点“立即刷新”。", "No proxies saved: fill a source or paste links, then tap Refresh now.")
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
            dialogView.setPadding(dp(20), dp(20), dp(20), dp(20)); // Build #41: wider dialog side margins
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
            DialogTheme.ensureGlassWindow(dialog, DialogTheme.resolve(context));
        } catch (Throwable throwable) {
            Toast.makeText(context, t("打开文件夹选择失败", "Failed to open folder picker"), Toast.LENGTH_SHORT).show();
        }
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
            dialogView.setPadding(dp(20), dp(20), dp(20), dp(20)); // Build #41: wider dialog side margins
            // Build #34: dialogView stays transparent; the glass card is
            // the dialog window background set by styleAlertDialog after show(),
            // so there is exactly one card edge, not two stacked ones.
            dialogView.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            TextView title = new TextView(context);
            title.setText(t("管理代理", "Manage proxies"));
            title.setTextColor(primaryTextColor);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
            title.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
            dialogView.addView(title, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView hint = new TextView(context);
            hint.setText(t("勾选后点“删除所选”；每条后面的状态分四种：延迟毫秒是测通的；“已过期”是以前测通过但超过上面自定义的失效时间了；“测不通”是测过但连不上；“未测”是还没轮到测（一次刷新最多测 20 个，没轮到的下次刷新再测）。“删除失效”只删测不通和已过期的，不删未测的。删除只影响模块列表，宿主当前正在用的代理不会因此断开，如需换掉请在 Conso 代理设置里手动切换或等下次自动刷新切到最快。", "Tick and tap Delete selected; Delete expired removes all expired or unreachable proxies (expired per the custom expiry time above). Deleting only affects the module list; the host's currently used proxy is not disconnected by this, switch manually in Conso proxy settings or wait for the next auto-refresh to switch to fastest."));
            hint.setTextColor(secondaryTextColor);
            hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            try { hint.setLineSpacing(dp(2), 1.15f); } catch (Throwable ignored) {}
            dialogView.addView(hint, new LinearLayout.LayoutParams(
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
            for (FilterConfig.ProxyEntry entry : entries) {
                android.widget.CheckBox box = new android.widget.CheckBox(context);
                // Build #40: stop labelling every non-good entry "失效".
                // Untested entries are not dead, they just never got a
                // test slot (max 20 per refresh), and must read as 未测.
                boolean expired = TgProxyManager.isExpired(entry, refConfig);
                String proxyStatus;
                if (entry.available && !expired) {
                    proxyStatus = entry.pingMs + " ms";
                } else if (entry.available) {
                    proxyStatus = t("已过期", "expired");
                } else if (entry.lastTestedMs > 0L) {
                    proxyStatus = t("测不通", "unreachable");
                } else {
                    proxyStatus = t("未测", "untested");
                }
                box.setText(entry.display() + "（" + proxyStatus + "）");
                box.setTextColor(primaryTextColor);
                box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
                box.setTag(entry);
                if (!boxes.isEmpty()) {
                    // Build #43: a 1px inset divider between proxy
                    // rows, so 300+ rows read as a list, not a wall.
                    View divider = new View(context);
                    divider.setBackgroundColor(strokeColor);
                    LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
                    dividerParams.leftMargin = dp(16);
                    proxyListContainer.addView(divider, dividerParams);
                }
                boxes.add(box);
                LinearLayout.LayoutParams boxParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                boxParams.topMargin = dp(4); // Build #41: row breathing room
                boxParams.bottomMargin = dp(4);
                proxyListContainer.addView(box, boxParams);
            }
            dialogView.addView(proxyScroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(activity)
                    .setView(dialogView)
                    .create();
            LinearLayout buttonRow = new LinearLayout(context);
            buttonRow.setOrientation(LinearLayout.HORIZONTAL);
            Button selectAll = toolbarButton(t("全选", "Select all"));
            selectAll.setOnClickListener(v -> { for (android.widget.CheckBox b : boxes) b.setChecked(true); });
            buttonRow.addView(selectAll);
            Button deleteSelected = toolbarButton(t("删除所选", "Delete selected"));
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
            buttonRow.addView(deleteSelected);
            Button deleteExpired = toolbarButton(t("删除失效", "Delete expired"));
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
            buttonRow.addView(deleteExpired);
            Button close = toolbarButton(t("关闭", "Close"));
            close.setOnClickListener(v -> dialog.dismiss());
            buttonRow.addView(close);
            dialogView.addView(buttonRow, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            dialog.show();
            DialogTheme.styleAlertDialog(dialog, context);
            DialogTheme.ensureGlassWindow(dialog, DialogTheme.resolve(context));
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
        addInfo(card, t("使用 Conso 自带的翻译服务，不经过第三方翻译网站。开启后，打开聊天时会自动把该聊天的翻译目标设为中文，并尝试开启整聊自动翻译；中文消息不受影响。", "Uses Conso's built-in translation service, no third-party translator. When on, opening a chat sets its translate target to Chinese and tries to enable whole-chat auto-translate; Chinese messages are unaffected."));
        autoTranslateSwitch = addSwitch(card, t("自动翻译外文为中文", "Auto-translate foreign messages to Chinese"));
        autoTranslateSwitch.setChecked(baseConfig.autoTranslateToChinese);
        addInfo(card, t("目标语言固定为简体中文。翻译由 Telegram 服务端完成，首次打开聊天、消息多时需要几秒到十几秒，属正常；若长时间不翻，退出重进该聊天再试一次。整聊自动翻译在宿主里是 Premium 功能，若未自动翻译，请同时开启“增强功能”里的本地 Premium，或点聊天顶部的翻译按钮手动翻译整聊。", "Target language is Simplified Chinese. Translation runs on Telegram's servers, so the first open of a chat with many messages can take seconds to tens of seconds; if it still does not translate, leave and reopen the chat. Whole-chat auto-translate is a Premium-gated host feature; also enable Local Premium in Enhancements, or use the chat's Translate button."));
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
        // Simplified 2026-10-25: the old card showed 4 inputs for EVERY
        // target (text / caption / buttons / sender / chat / any = up to
        // 24 boxes), which is why users called it cluttered. Now only
        // the two boxes most people actually use are visible by default:
        // message-text filter keywords and message-text keep keywords.
        // Everything else (regex, and all other targets) lives in one
        // collapsible "高级规则" section, still created and still saved
        // when hidden, so existing rules are never lost by collapsing.
        LinearLayout card = addGroup(container, t("规则内容", "Rules"));
        addInfo(card, chatMode
                ? t("这里只影响当前聊天。关键词用“、”分隔，不用一行一条；点“编辑”进关键词编辑界面，外部只显示个数，不直接显示关键词内容。常用只需填下面两个框；按目标、按正则的细规则在“高级规则”里，收起时也会照常保存。", "This editor only affects the current chat. Keywords are separated by 、, not one per line; tap Edit to open the keyword editor, which shows only the count outside, not the keywords themselves. Usually the two boxes below are enough; fine-grained rules by target/regex live under Advanced rules and are still saved when collapsed.")
                : t("关键词用“、”分隔，不用一行一条；点“编辑”进关键词编辑界面，外部只显示个数，不直接显示关键词内容。常用只需填下面两个框；按目标、按正则的细规则在“高级规则”里，收起时也会照常保存。", "Keywords are separated by 、, not one per line; tap Edit to open the keyword editor, which shows only the count outside, not the keywords themselves. Usually the two boxes below are enough; fine-grained rules by target/regex live under Advanced rules and are still saved when collapsed."));

        FilterConfig.ChatRuleSet chatRuleSet = chatMode
                ? baseConfig.getOrCreateChatRuleSet(dialogId).deepCopy().sanitize()
                : null;
        RuleDraftMatrix matchMatrix = RuleDraftMatrix.fromRules(chatMode ? chatRuleSet.rules : baseConfig.globalRules);
        RuleDraftMatrix exclusionMatrix = RuleDraftMatrix.fromRules(chatMode ? chatRuleSet.exclusions : baseConfig.globalExclusions);

        LinearLayout advancedContainer = new LinearLayout(context);
        advancedContainer.setOrientation(LinearLayout.VERTICAL);
        advancedContainer.setVisibility(View.GONE);

        for (FilterConfig.RuleTarget target : editorTargets(chatMode)) {
            RuleInputs inputs = new RuleInputs();
            if (target == FilterConfig.RuleTarget.TEXT) {
                // Visible by default: only the two keyword boxes.
                addSectionLabel(card, t("消息文字", "Message text"));
                addInfo(card, t("只检查消息正文，绝大多数规则填这里就够了。", "Only checks message text; most rules belong here."));
                inputs.matchKeywords = addKeywordEditorRow(card, t("过滤关键词", "Filter keywords"), matchMatrix.get(target, FilterConfig.RuleMode.KEYWORD));
                inputs.keepKeywords = addKeywordEditorRow(card, t("保留关键词（白名单）", "Keep keywords (allowlist)"), exclusionMatrix.get(target, FilterConfig.RuleMode.KEYWORD));
                // Regex for text goes to Advanced, same RuleInputs object.
                addSectionLabel(advancedContainer, t("消息文字 · 正则", "Message text · Regex"));
                inputs.matchRegex = addInput(advancedContainer, t("过滤正则", "Filter regex"), matchMatrix.get(target, FilterConfig.RuleMode.REGEX));
                inputs.keepRegex = addInput(advancedContainer, t("保留正则", "Keep regex"), exclusionMatrix.get(target, FilterConfig.RuleMode.REGEX));
            } else {
                addDivider(advancedContainer);
                addSectionLabel(advancedContainer, targetLabel(target));
                addInfo(advancedContainer, targetScope(target));
                inputs.matchKeywords = addKeywordEditorRow(advancedContainer, t("过滤关键词", "Filter keywords"), matchMatrix.get(target, FilterConfig.RuleMode.KEYWORD));
                inputs.matchRegex = addInput(advancedContainer, t("过滤正则", "Filter regex"), matchMatrix.get(target, FilterConfig.RuleMode.REGEX));
                inputs.keepKeywords = addKeywordEditorRow(advancedContainer, t("保留关键词", "Keep keywords"), exclusionMatrix.get(target, FilterConfig.RuleMode.KEYWORD));
                inputs.keepRegex = addInput(advancedContainer, t("保留正则", "Keep regex"), exclusionMatrix.get(target, FilterConfig.RuleMode.REGEX));
            }
            ruleInputs.put(target, inputs);
        }

        addDivider(card);
        Switch advancedSwitch = addSwitch(card, t("显示高级规则", "Show advanced rules"));
        // UI sync (Build #15): advanced rules must not look lost on reopen.
        // If any advanced rule exists (any non-TEXT target, or TEXT regex),
        // open the panel with Advanced already expanded.
        boolean hasAdvanced = false;
        for (FilterConfig.RuleTarget target : editorTargets(chatMode)) {
            boolean isText = target == FilterConfig.RuleTarget.TEXT;
            if (!isText) {
                if (!matchMatrix.get(target, FilterConfig.RuleMode.KEYWORD).isBlank()
                        || !matchMatrix.get(target, FilterConfig.RuleMode.REGEX).isBlank()
                        || !exclusionMatrix.get(target, FilterConfig.RuleMode.KEYWORD).isBlank()
                        || !exclusionMatrix.get(target, FilterConfig.RuleMode.REGEX).isBlank()) {
                    hasAdvanced = true;
                }
            } else {
                if (!matchMatrix.get(target, FilterConfig.RuleMode.REGEX).isBlank()
                        || !exclusionMatrix.get(target, FilterConfig.RuleMode.REGEX).isBlank()) {
                    hasAdvanced = true;
                }
            }
        }
        advancedSwitch.setChecked(hasAdvanced);
        advancedContainer.setVisibility(hasAdvanced ? View.VISIBLE : View.GONE);
        advancedSwitch.setOnCheckedChangeListener((button, isChecked) ->
                advancedContainer.setVisibility(isChecked ? View.VISIBLE : View.GONE));
        addInfo(card, t("高级规则包括：消息文字的正则，以及媒体说明、按钮、发送者、聊天、全字段的关键词与正则。", "Advanced rules include: message-text regex, plus keywords and regex for caption, buttons, sender, chat, and any field."));
        card.addView(advancedContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
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
                updated.showClassifyBar = false; // Build #40: classify bar cancelled per user request.
                updated.autoArchiveFolderChats = autoArchiveFolderSwitch != null && autoArchiveFolderSwitch.isChecked();
                updated.aiAnalysisMode = aiLlmSwitch != null && aiLlmSwitch.isChecked() ? 1 : 0;
                updated.aiFallbackToLocal = aiFallbackSwitch == null || aiFallbackSwitch.isChecked();
                if (aiBaseUrlInput != null) updated.aiApiBaseUrl = valueOf(aiBaseUrlInput).trim();
                if (aiModelInput != null) updated.aiApiModel = valueOf(aiModelInput).trim();
                if (aiKeyInput != null) updated.aiApiKey = valueOf(aiKeyInput).trim();
                updated.proxyAutoAdd = proxyAutoAddSwitch != null && proxyAutoAddSwitch.isChecked();
                updated.proxyAutoSwitch = proxyAutoSwitchSwitch != null && proxyAutoSwitchSwitch.isChecked();
                updated.proxyRefreshHours = proxyInt(proxyRefreshInput, 6);
                updated.proxyExpiryHours = proxyInt(proxyExpiryInput, 24);
                if (proxySourceInput != null) updated.proxySourceUrl = valueOf(proxySourceInput).trim();
                if (proxyPastedInput != null) updated.proxyPastedLinks = valueOf(proxyPastedInput);
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
    private LinearLayout addGroup(LinearLayout parent, String title) {
        TextView header = new TextView(context);
        header.setText(title);
        header.setTextColor(secondaryTextColor);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        // Build #41: wider side margins (group inset 24 + row
        // padding 16 = 40), so groups breathe more on both sides.
        headerParams.leftMargin = dp(40);
        headerParams.rightMargin = dp(40);
        headerParams.topMargin = dp(20);
        headerParams.bottomMargin = dp(6);
        parent.addView(header, headerParams);
        LinearLayout group = new LinearLayout(context);
        group.setOrientation(LinearLayout.VERTICAL);
        // Build #41: inset glass group, not a full-width flat slab.
        // 24dp side inset on each side (was 16dp) keeps the content
        // width in proportion on a wide phone screen and gives the
        // glass card more air on both sides.
        group.setBackground(DialogTheme.glassBackground(
                DialogTheme.resolve(context), context));
        LinearLayout.LayoutParams groupParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        groupParams.leftMargin = dp(24);
        groupParams.rightMargin = dp(24);
        // Build #33: a gap after each group, so groups read as
        // separate blocks instead of one continuous sheet.
        groupParams.bottomMargin = dp(12);
        parent.addView(group, groupParams);
        return group;
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
        TextView label = new TextView(context);
        label.setTag("gramsieve_section");
        label.setText(text);
        label.setTextColor(accentColor);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = dp(12);
        params.bottomMargin = dp(4);
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
        try { info.setLineSpacing(dp(2), 1.15f); } catch (Throwable ignored) {}
        info.setPadding(dp(16), dp(6), dp(16), dp(6));
        info.setVisibility(View.GONE);
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
        help.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        help.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        help.setGravity(Gravity.CENTER);
        help.setMinWidth(dp(32));
        help.setPadding(dp(8), dp(4), dp(8), dp(4));
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

    private android.widget.CheckBox addSwitch(LinearLayout parent, String text) {
        // Build #43: every panel choice is a checkbox now (user
        // 2026-10-26: checked = on/running). The row is a horizontal
        // container so addInfo can attach a "?" button beside the
        // item instead of a permanent paragraph below it.
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setTag("gramsieve_row");
        android.widget.CheckBox box = new android.widget.CheckBox(context);
        box.setMinHeight(dp(48));
        box.setText(text);
        box.setTextColor(primaryTextColor);
        box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        try {
            int[][] states = new int[][]{{android.R.attr.state_checked}, {}};
            int[] boxColors = new int[]{accentColor, adjustAlpha(secondaryTextColor, 0.6f)};
            box.setButtonTintList(new ColorStateList(states, boxColors));
        } catch (Throwable ignored) {}
        row.addView(box, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(createHelpButton(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(parent, row, 6);
        return box;
    }

    // Keyword editor row (2026-10-25 UI change): keywords are NOT shown
    // directly in the rules card. The row shows only a count ("已设置 N 个
    // 关键词") plus an Edit button; tapping it opens a dialog with a
    // symbol-separated EditText. The actual value lives in a hidden
    // EditText, so save()/collectMatrix() work unchanged.
    private EditText addKeywordEditorRow(LinearLayout parent, String label, String initialValue) {
        TextView title = new TextView(context);
        title.setTag("gramsieve_row_label");
        title.setText(label);
        title.setTextColor(secondaryTextColor);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        addView(parent, title, 2);

        EditText hidden = new EditText(context);
        hidden.setText(initialValue == null ? "" : initialValue);
        hidden.setVisibility(View.GONE);
        addView(parent, hidden, 0);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        try {
            row.setBackground(rounded(adjustAlpha(primaryTextColor, 0.07f), dp(10), strokeColor));
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
        } catch (Throwable ignored) {}
        TextView summary = new TextView(context);
        summary.setTextColor(primaryTextColor);
        summary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        updateKeywordSummary(summary, valueOf(hidden));
        row.addView(summary, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

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
        row.addView(editButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // Tapping anywhere on the row opens the editor, not just the button.
        row.setOnClickListener(v -> showKeywordEditorDialog(hidden, summary));
        addView(parent, row, 8);
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
        dialogView.setPadding(dp(20), dp(20), dp(20), dp(12)); // Build #41: wider dialog side margins
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
            input.setBackground(rounded(adjustAlpha(primaryTextColor, 0.07f), dp(10), strokeColor));
            input.setPadding(dp(16), dp(12), dp(16), dp(12)); // Build #41: roomier input fields
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
        DialogTheme.ensureGlassWindow(dialog, DialogTheme.resolve(context));
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
            summary.setText("未设置，点击编辑添加");
        } else {
            summary.setText("已设置 " + count + " 个关键词，点击编辑修改");
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
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        labelRow.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        labelRow.addView(createHelpButton(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(parent, labelRow, 2);

        EditText input = new EditText(context);
        input.setText(initialValue == null ? "" : initialValue);
        input.setTextColor(primaryTextColor);
        input.setHintTextColor(secondaryTextColor);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        try {
            input.setBackground(rounded(adjustAlpha(primaryTextColor, 0.07f), dp(10), strokeColor));
            input.setPadding(dp(16), dp(12), dp(16), dp(12)); // Build #41: roomier input fields
        } catch (Throwable ignored) {}
        input.setMinLines(1);
        input.setMaxLines(4);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setHorizontallyScrolling(false);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        addView(parent, input, 8);
        return input;
    }

    // Build #43: small on/off items in the same category share a
    // row, two per row, instead of one long single column (user
    // 2026-10-26). Only adjacent checkbox rows with short labels
    // (at most 10 characters) are paired, so long labels never get
    // squeezed, sections and inputs break a run, and full-width
    // rows stay full-width. Widths are weights, never fixed dp, so
    // nothing can overflow a narrow screen; longer labels wrap.
    private void pairCheckboxRows(LinearLayout group) {
        try {
            if (group == null) {
                return;
            }
            java.util.ArrayList<View> children = new java.util.ArrayList<>();
            for (int i = 0; i < group.getChildCount(); i++) {
                children.add(group.getChildAt(i));
            }
            for (int i = 0; i + 1 < children.size(); i++) {
                View first = children.get(i);
                View second = children.get(i + 1);
                android.widget.CheckBox firstBox = checkboxRowBox(first);
                android.widget.CheckBox secondBox = checkboxRowBox(second);
                if (firstBox == null || secondBox == null) {
                    continue;
                }
                CharSequence firstText = firstBox.getText();
                CharSequence secondText = secondBox.getText();
                if (firstText == null || secondText == null
                        || firstText.length() > 10 || secondText.length() > 10) {
                    continue;
                }
                int index = group.indexOfChild(first);
                if (index < 0) {
                    continue;
                }
                group.removeView(first);
                group.removeView(second);
                LinearLayout pair = new LinearLayout(context);
                pair.setOrientation(LinearLayout.HORIZONTAL);
                pair.addView(first, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                pair.addView(second, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                group.addView(pair, index, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                children.set(i, pair);
                children.set(i + 1, pair);
                i++;
            }
        } catch (Throwable ignored) {
        }
    }

    private android.widget.CheckBox checkboxRowBox(View row) {
        try {
            if (!(row instanceof LinearLayout) || !"gramsieve_row".equals(row.getTag())) {
                return null;
            }
            LinearLayout container = (LinearLayout) row;
            for (int i = 0; i < container.getChildCount(); i++) {
                if (container.getChildAt(i) instanceof android.widget.CheckBox) {
                    return (android.widget.CheckBox) container.getChildAt(i);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
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
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(8), dp(6), dp(8), dp(6));
        button.setBackgroundColor(Color.TRANSPARENT);
        return button;
    }

    private void addRadio(RadioGroup group, String text, Object value) {
        RadioButton button = new RadioButton(context);
        button.setId(View.generateViewId());
        button.setText(text);
        button.setTextColor(primaryTextColor);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        button.setTag(value);
        group.addView(button, new RadioGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
    }

    private void checkTaggedRadio(RadioGroup group, Object value) {
        if (group == null) {
            return;
        }
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            Object tag = child.getTag();
            if (value == null ? tag == null : value.equals(tag)) {
                group.check(child.getId());
                return;
            }
        }
        if (group.getChildCount() > 0) {
            group.check(group.getChildAt(0).getId());
        }
    }

    private String selectedLanguageTag() {
        Object tag = selectedRadioTag(languageGroup);
        return tag instanceof String ? (String) tag : FilterConfig.APP_LANGUAGE_SYSTEM;
    }

    private FilterConfig.Action selectedAction() {
        Object tag = selectedRadioTag(actionGroup);
        return tag instanceof FilterConfig.Action ? (FilterConfig.Action) tag : FilterConfig.Action.HIDE;
    }

    private Object selectedRadioTag(RadioGroup group) {
        if (group == null) {
            return null;
        }
        int checkedId = group.getCheckedRadioButtonId();
        View checked = group.findViewById(checkedId);
        return checked == null ? null : checked.getTag();
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

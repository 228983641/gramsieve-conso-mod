package com.tianqianguai.gramsieve.module;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
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
    private final FilterConfig baseConfig;
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
    private Switch enabledSwitch;
    private Switch debugLoggingSwitch;
    private Switch excludeChatSwitch;
    private Switch chatAntiRecallSwitch;
    private Switch sponsoredSwitch;
    private Switch videoAdsSwitch;
    private Switch forwardSaveSwitch;
    private Switch saveStoriesSwitch;
    private Switch preventDeleteSwitch;
    private Switch downloadSpeedSwitch;
    private Switch localPremiumSwitch;
    private Switch autoTranslateSwitch;
    private Switch pinnedHighlightSwitch;
    private Switch classifyBarSwitch;
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
        this.secondaryTextColor = telegramThemeColor("key_windowBackgroundWhiteGrayText2", androidSecondaryText);
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
        overlay.setBackgroundColor(backgroundColor);
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
        screen.setBackgroundColor(backgroundColor);
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
        scrollView.setBackgroundColor(backgroundColor);
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setBackgroundColor(backgroundColor);
        int padding = dp(16);
        container.setPadding(padding, dp(12), padding, dp(24));
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
        if (chatMode) {
            buildChatAntiRecallCard(container);
        } else {
            buildEnhancementCard(container);
            buildDialogListCard(container);
            buildTranslateCard(container);
        }
        buildRulesCard(container);

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
        LinearLayout card = addCard(container);
        addTitle(card, chatMode ? t("当前聊天", "Current Chat") : t("全局配置", "Global Settings"));

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
        LinearLayout card = addCard(container);
        addTitle(card, t("增强功能", "Enhancements"));
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
        LinearLayout card = addCard(container);
        addTitle(card, t("聊天列表", "Chat List"));
        addInfo(card, t("只影响聊天列表的外观与筛选，不改动聊天内容与置顶本身。分类栏点“全部”即恢复全部聊天；关闭分类栏后，筛选即失效，重新打开聊天列表后生效。", "Only affects the chat list appearance and filtering, not chat content or pinning itself. Tapping All in the classify bar restores all chats; turning the bar off disables filtering after reopening the chat list."));
        pinnedHighlightSwitch = addSwitch(card, t("置顶聊天区分显示（左侧色条 + 浅色背景）", "Highlight pinned chats (left colour bar + light background)"));
        pinnedHighlightSwitch.setChecked(baseConfig.highlightPinnedDialogs);
        classifyBarSwitch = addSwitch(card, t("显示自动分类栏（全部 / 个人 / 群聊 / 频道）", "Show auto-classify bar (All / Personal / Groups / Channels)"));
        classifyBarSwitch.setChecked(baseConfig.showClassifyBar);
        addInfo(card, t("分类栏出现在文件夹栏下方，与文件夹分开；个人含机器人，群聊含普通群与超级群，频道为广播频道。", "The classify bar appears below the folder tabs, separate from folders; Personal includes bots, Groups include regular and supergroups, Channels are broadcast channels."));
    }

    private void buildTranslateCard(LinearLayout container) {
        LinearLayout card = addCard(container);
        addTitle(card, t("翻译", "Translation"));
        addInfo(card, t("使用 Conso 自带的翻译服务，不经过第三方翻译网站。开启后，打开聊天时会自动把该聊天的翻译目标设为中文，并尝试开启整聊自动翻译；中文消息不受影响。", "Uses Conso's built-in translation service, no third-party translator. When on, opening a chat sets its translate target to Chinese and tries to enable whole-chat auto-translate; Chinese messages are unaffected."));
        autoTranslateSwitch = addSwitch(card, t("自动翻译外文为中文", "Auto-translate foreign messages to Chinese"));
        autoTranslateSwitch.setChecked(baseConfig.autoTranslateToChinese);
        addInfo(card, t("目标语言固定为简体中文。翻译由 Telegram 服务端完成，首次打开聊天、消息多时需要几秒到十几秒，属正常；若长时间不翻，退出重进该聊天再试一次。整聊自动翻译在宿主里是 Premium 功能，若未自动翻译，请同时开启“增强功能”里的本地 Premium，或点聊天顶部的翻译按钮手动翻译整聊。", "Target language is Simplified Chinese. Translation runs on Telegram's servers, so the first open of a chat with many messages can take seconds to tens of seconds; if it still does not translate, leave and reopen the chat. Whole-chat auto-translate is a Premium-gated host feature; also enable Local Premium in Enhancements, or use the chat's Translate button."));
    }

    private void buildChatAntiRecallCard(LinearLayout container) {
        if (antiRecallConfigStore == null || dialogId == 0L) {
            return;
        }
        LinearLayout card = addCard(container);
        addTitle(card, t("当前聊天主动加载", "Current Chat Proactive Loading"));
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
        LinearLayout card = addCard(container);
        addTitle(card, t("规则内容", "Rules"));
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
                updated.showClassifyBar = classifyBarSwitch != null && classifyBarSwitch.isChecked();
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
        TextView info = new TextView(context);
        info.setText(text);
        info.setTextColor(secondaryTextColor);
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        try { info.setLineSpacing(dp(2), 1.15f); } catch (Throwable ignored) {}
        addView(parent, info, 6);
        return info;
    }

    private Switch addSwitch(LinearLayout parent, String text) {
        Switch toggle = new Switch(context);
        toggle.setText(text);
        toggle.setTextColor(primaryTextColor);
        toggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        try {
            int[][] states = new int[][]{{android.R.attr.state_checked}, {}};
            int[] thumbColors = new int[]{accentColor, adjustAlpha(secondaryTextColor, 0.6f)};
            int[] trackColors = new int[]{adjustAlpha(accentColor, 0.45f), adjustAlpha(secondaryTextColor, 0.35f)};
            toggle.setThumbTintList(new ColorStateList(states, thumbColors));
            toggle.setTrackTintList(new ColorStateList(states, trackColors));
        } catch (Throwable ignored) {}
        addView(parent, toggle, 6);
        return toggle;
    }

    // Keyword editor row (2026-10-25 UI change): keywords are NOT shown
    // directly in the rules card. The row shows only a count ("已设置 N 个
    // 关键词") plus an Edit button; tapping it opens a dialog with a
    // symbol-separated EditText. The actual value lives in a hidden
    // EditText, so save()/collectMatrix() work unchanged.
    private EditText addKeywordEditorRow(LinearLayout parent, String label, String initialValue) {
        TextView title = new TextView(context);
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
        dialogView.setPadding(dp(16), dp(16), dp(16), dp(12));
        try {
            dialogView.setBackground(rounded(cardColor, dp(16), strokeColor));
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
            input.setPadding(dp(12), dp(10), dp(12), dp(10));
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
        TextView title = new TextView(context);
        title.setText(label);
        title.setTextColor(secondaryTextColor);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        addView(parent, title, 2);

        EditText input = new EditText(context);
        input.setText(initialValue == null ? "" : initialValue);
        input.setTextColor(primaryTextColor);
        input.setHintTextColor(secondaryTextColor);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        try {
            input.setBackground(rounded(adjustAlpha(primaryTextColor, 0.07f), dp(10), strokeColor));
            input.setPadding(dp(12), dp(10), dp(12), dp(10));
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

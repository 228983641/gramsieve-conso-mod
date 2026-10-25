# GramSieve for Conso (adaptation)

Base: upstream GramSieve main (commit 97bf94a), GPL-3.0-or-later.
This adaptation is NOT the upstream release and is for personal use on the
user's own device only.

Changes vs upstream (minimal, to support Conso):
1. `app/src/main/resources/META-INF/xposed/scope.list`: added `global.xfinite.conso`
   alongside `org.telegram.messenger`.
2. `app/src/main/java/com/tianqianguai/gramsieve/module/GramSieveModule.java`:
   package gate now accepts `org.telegram.messenger` OR `global.xfinite.conso`.
   Conso's Java classes remain `org.telegram.messenger.*`, so class hooks are unchanged.
3. `TelegramHookInstaller.java`: resource lookups via `getIdentifier(..., context.getPackageName())`
   instead of hard-coded `org.telegram.messenger`. For official Telegram this is
   identical; for Conso it matches Conso's resources package (`global.xfinite.conso`
   in resources.arsc, verified from Conso 2.0.0 base.apk).

Not changed: filter logic, settings UI, class names, module package
`com.tianqianguai.gramsieve`, or any Telegram account handling.

Untested on a real device. Known risks:
- GramSieve targets Telegram 13.1.1; Conso 2.0.0's underlying Telegram version
  was not confirmed from static strings, so some UI hooks (Settings entry,
  chat menu, ChatMessageCell binding) may fail silently on Conso.
- Conso-specific UI (e.g. Explore, consoCaptionLayout) is not covered by
  upstream GramSieve hooks and may not be filtered.
- Requires LSPosed supporting libxposed API 101 (module.prop) / 102 per README.
  Enable the module only for `global.xfinite.conso` in LSPosed, force-stop
  Conso, then open Conso Settings -> GramSieve to add filter rules.

## Chinese UI forced (2026-10-25, after install-signature report)
- FilterConfig default appLanguageTag changed from SYSTEM to zh-CN.
- HostConfigPanel: chinese flag forced to true, so all host settings panels use Chinese text.
- TelegramHookInstaller: isChineseLocale() forced to true, so all menus, toasts and dialogs use Chinese text.
- AppLocaleManager: module's own app locale forced to zh-CN.
- Language radio in settings remains visible but no longer changes the UI language in this adaptation; UI stays Chinese.

## 2026-10-25 智能屏蔽与长按菜单修复（Build #3 待构建）
- 原因：Conso 2.0.0 的 ChatActivity.createMenu 有 4 个重载（6/7/8/10 参数），上游只勾 8 参数版；长按若走 10 参数版（带两个 MessageObject），屏蔽/标记/编辑历史项就永远插不进去，而聊天菜单走 createView 所以能出来。本次改为勾全部返回 boolean 且首参为 View 的 createMenu 重载，10 参数版优先用传入的 MessageObject。
- 新增：长按消息菜单里增加“智能分析并屏蔽”，点后分析正文/说明/按钮/发送者，提取赌博词（含上游词表遗漏的 赌博/百家乐/彩票/投注/下注/开奖）、域名、t.me 链接、@用户名、品牌词、发送者，弹多选确认框，确认后加入全局规则（不是只加当前聊天），可勾掉不想加的项。
- 其他功能未删除：防撤回、编辑历史、标记/跳转、主动加载、下载页全选等代码均在；长按菜单修复后，标记与编辑历史（有编辑记录时）应随屏蔽项一同出现。

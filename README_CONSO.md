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

## 2026-10-25 智能屏蔽分析优化（Build #4 待构建）
- 依据用户提供的两条真实赌博信息优化，旧逻辑的漏点：第一条的 IP 落地页 153.43.183.234:18029 完全没被提取，第二条一个赌博关键词都没命中（只有域名/@号在撑），同时旧逻辑会把 https://、.com、官方、下载这类通用词也当成规则候选，误伤极大。
- 新逻辑：扩强信号词表（六合彩、特码、连码、三中三、捕鱼、德州、十三水、真人视讯、返水、出款、提款、派彩、爆奖、一拉、一注、占成、打码、返佣、彩金、流水、存款、存取款、免实名、免绑卡、担保、飞投、充提、盘总、USDT、万U 等）；通用推广线索（http://、.com、官方、下载等）只用于判定，不再作为规则候选；新增 IP 地址规则（只存 IP，不存端口/路径，换端口也拦得住）；中文品牌只认带品牌后缀的（122体育、N1国际），游戏名和金额（极速糖果1000、特码49）不再被当成品牌；匹配时先去掉 emoji 再找词，emoji 插在词中间也能发现；确认框里强信号默认勾选，体育、电子、红包这类通用词默认不勾，标题显示建议条数。
- 模拟结果：第一条强信号从 3 个（棋牌、百家乐、下注）涨到 27 个，外加 IP、78.COM、t.me/df78、@DL78 等；第二条从 0 个赌博词涨到 13 个，外加 1221395.com、n10086.com、122体育、@qsty8999 等。

## 2026-10-25 增强功能合并（Build #5 待构建）
- 用户选定的合并功能：去赞助消息、去视频广告、允许转发和保存、允许保存动态、防止消息删除、提升下载速度，另加用户后来要求的本地 Premium 解锁。
- 来源与移植方式：前四项及防删除取自 NoAdsTelegram 的勾子（已按 libxposed 改写，不再用经典 Xposed API）；下载提速取自 TeleVip 的 DownloadSpeed（大文件分块 512 KiB、并行 8 路、CDN 分片覆盖 2000 MB）；本地 Premium 取自 TeleVip 的 TelePremium（UserConfig.isPremium() 返回 true）。均为逐个勾子移植，不是整项目粘贴，且每个开关在设置里默认关闭。
- 设置面板新增“增强功能”卡片，按广告 / 消息 / 动态 / 性能 / 本地 Premium 分类，与过滤卡片分开，符合用户要的“分类设置”。
- 诚实说明：本地 Premium 仅本地生效，可突破置顶数等客户端检查（实测为准），服务端 Premium 功能仍需官方订阅，且有封号风险；保存动态本版为简化移植（允许截图、启用已有保存菜单项），若 Conso 动态菜单里本来就没有保存项，单开此开关不起作用；下载提速只改客户端并行度，服务端限速仍在。
- 签名连续性（见下）：本版起改用固定调试密钥，之后构建应可覆盖安装；但本版与之前每次构建签名都不同，所以本版仍需最后卸载一次。

## 签名连续性（Build #5 起）
- 之前每次构建签名都不同，是因为 CI 每次都新生成临时调试密钥。本版起仓库里放了一个固定的调试密钥（debug.keystore.b64，标准 Android 调试密钥：alias androiddebugkey，密码 android，非正式发布密钥，仅供个人安装使用），工作流在构建前把它还原到 ~/.android/debug.keystore，之后构建签名一致，应可覆盖安装。本版与之前所有版本签名都不同，所以本版仍需最后卸载一次，之后不用。

## 2026-10-25 自动翻译与界面美化（Build #5 第二推）
- 自动翻译：查 Conso 2.0.0 dex 确认自带 TranslateController（MessagesController.getTranslateController()、setDialogTranslateTo、toggleTranslatingDialog、isChatAutoTranslated、getLanguages().code），本功能直接用宿主原生翻译，不经过第三方翻译网站。设置里新增“翻译”卡片，开关“自动翻译外文为中文”，默认关闭；开启后打开聊天时把该聊天翻译目标设为中文（从宿主语言列表里找 zh 开头的代码，找不到则用 zh-CN），并尝试开启整聊自动翻译，同时把 isChatTranslateEnabled / isFeatureAvailable / isChatAutoTranslated 视为开启。整聊自动翻译在宿主里是 Premium 功能，需配合本地 Premium 开关，实测为准；若仍未自动翻译，可用聊天顶部翻译按钮。
- 界面美化：仅美化插件自己的设置面板，不动 Conso 界面。卡片圆角 16dp、卡片底色用主题灰色与屏幕背景分开、卡片间距和内边距加大、标题 17sp、分类标签用主题蓝 13sp、开关开启时拇指/轨道用主题蓝、说明文字 13sp 行距加大、输入框改成圆角浅底框。

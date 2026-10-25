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

## 签名修复第二步（Build #7）
- Build #6 虽然成功，但核对发现 APK 签名证书指纹是 80:A6:EF:...（主题 CN=Android Debug, O=Android, C=US，即 CI 现场生成的临时调试密钥），不是固定密钥的 24:F7:92:...（主题含 OU/L/ST）。原因是 AGP 在 CI 上没有用 ~/.android/debug.keystore（很可能它查的是 ANDROID_SDK_HOME 下的 .android）。
- 修复：app/build.gradle.kts 里把 debug 签名显式指向仓库根目录的 debug.keystore（工作流在构建前从 debug.keystore.b64 解码出来）；同时工作流把密钥还原到 ~/.android、$ANDROID_HOME/.android、$ANDROID_SDK_HOME/.android 三处。Build #7 交付前必须核对 APK 签名指纹等于 24:F7:...，否则不发版。Build #6 因此不发版，避免用户多卸载一次、多丢一次规则。

## 2026-10-25 翻译目标语言修复 + 规则界面简化 + 开源准备（Build #10 待构建）
- 翻译：Build #9 后用户反馈翻译功能有了但翻得不对/慢。可能原因之一：旧代码取宿主语言列表里第一个 zh* 代码，可能是繁体（zh-TW/zh-Hant）或泛 zh，服务端映射不可靠。本次改为优先简体中文顺序 zh-CN、zh-cn、zh_CN、zh-Hans、zh-Hans-CN、zh，繁体只有在无简体时才作备选，默认仍为 zh-CN。另在翻译卡片里说明：翻译由 Telegram 服务端完成，首次打开、消息多时几秒到十几秒属正常，长时间不翻请退出重进。翻译慢本身是服务端耗时，客户端无法把服务端变快，只能保证目标语言和触发时机正确。
- 规则界面简化：旧规则卡片对每个目标（消息文字/媒体说明/按钮/发送者/聊天/全字段）各显示 4 个输入框，最多 24 个框。本次默认只显示消息文字的过滤关键词和保留关键词两个框；“显示高级规则”开关展开后才显示消息文字正则及其他目标的全部输入框，收起时高级规则照常创建和保存，不会丢失已有规则。保存逻辑未改，仍按原矩阵收集。
- 开源准备（尚未公开）：新增 README_CONSO_PUBLIC.md（中英双语、按实际代码写的功能与效果说明、诚实限制、安装/构建/签名/风险/来源致谢、截图占位表）和 docs/screenshots/README.md（真机截图待补充说明）。LICENSE 已是 GPL-3.0 全文。公开前必须做的事（见下条）尚未执行，仓库仍为私有。

## 2026-10-25 智能分析 UI 优化 + 界面统一 + 关键词符号分隔与编辑入口（Build #11 待构建）
- 智能分析对话框重做：按关键词 / 域名/链接 / IP 地址 / 发送者分组，组标题用 13sp 加粗主题蓝（与设置面板分类标签一致），每项为主题蓝复选框 + 15sp 文字，顶部保留“分析出的关键词：……”摘要，顶部增加“全选 / 仅强信号”快捷操作，底部说明确认后加入全局规则。替代了原来与设置面板风格完全不同的系统多选列表。
- 界面统一核对：设置面板已是 16dp 圆角卡片、主题灰底色、17sp 加粗标题、13sp 加粗主题蓝分类标签、13sp 说明、15sp 主题蓝开关、10dp 圆角输入框；智能分析与关键词编辑对话框现沿用同一套尺寸与颜色（13sp 分类标签、15sp 正文、主题蓝按钮/复选框）。仅做信息展示的原始内容、编辑历史对话框仍用系统对话框，未动，风险低。
- 关键词分隔：规则解析改为关键词模式按符号分隔（、 ， , ; ；及换行都算分隔符），显示时统一用“、”连接；正则模式仍按行分隔，因为正则本身可能含逗号/分号。旧的一行一条规则仍能解析，新旧兼容。
- 关键词编辑入口：规则卡片里关键词不再直接显示为大段输入框，外部只显示“已设置 N 个关键词，点击编辑修改”或“未设置，点击编辑添加”，不显示关键词内容；点整行或“编辑”按钮进入关键词编辑对话框（符号分隔输入框、提示示例、确定/取消/清空），确定后写回隐藏字段，保存逻辑不变。正则框仍直接显示在高级规则里。
- 开源公开方案（第 1 条）未丢，仍在待办：之前卡在本地 JDK 解压不完整（keytool 缺 libjli.so），待本版构建后再继续（重新下载/解压 JDK、生成新密钥、放 Secrets、改工作流、删仓库密钥文件、公开、再构建一次验证新签名）。

## 2026-10-25 开源公开方案第 1 条执行（Build #12 待构建）
- 新发布密钥已生成（别名 gramsieve-conso，RSA 2048，证书 SHA256 指纹以 F1:45:22:73: 开头，完整指纹待构建后核对），只存放在 GitHub Actions Secrets 里（RELEASE_KEYSTORE_BASE64 / RELEASE_STORE_PASSWORD / RELEASE_KEY_ALIAS / RELEASE_KEY_PASSWORD），本地副本已删，Secrets 取不回，只能覆盖重设。
- 工作流已改（提交 edf4bff）：删除“Restore fixed debug keystore”一步，改为“Restore release keystore from Secrets”（从 Secret 还原 release.keystore，只列文件名不打印内容），构建步骤加 release 签名 env（RELEASE_STORE_FILE=release.keystore 等），run 命令本身未改。
- 待做：删仓库当前版本的 debug.keystore.b64（历史里仍有旧密钥，只能用于旧版）、把 README.md 换成开源版、.gitignore 加 *.keystore / debug.keystore.b64 / release.keystore、仓库设公开、再构建一次验证新签名；公开后的首版需卸载旧版再装，之后同一把 Secret 密钥可覆盖安装。

## 2026-10-25 聊天列表：置顶区分 + 自动分类栏（Build #14 待构建，用户选低风险路线）
- 新增设置 → GramSieve → 聊天列表卡片，两个开关默认关闭：置顶聊天区分显示（左侧色条 + 浅色背景）、显示自动分类栏（全部 / 个人 / 群聊 / 频道）。
- 置顶：勾 DialogCell.onDraw，置顶且非文件夹项时，在已绘制内容上叠加浅色主题蓝 tint（0x1A3390EC）+ 左侧 4dp 实色主题蓝条，头像与文字不受遮挡；判断用 DialogCell.getIsPinned，非置顶不画。
- 分类：勾 DialogsActivity.createView，在文件夹栏下方插入独立分类栏（与文件夹分开），按钮为全部/个人/群聊/频道，选中为主题蓝底白字，未选中为灰字透明底；点按钮保存 classifyFilter（0=全部，1=个人，2=群聊，3=频道）并刷新聊天列表；DialogCell.setDialog 勾子按分类隐藏不匹配项（GONE，回收后匹配项恢复显示），分类用 DialogObject.isUserDialog / isChatDialog + MessagesController.getUserOrChat（User=个人含机器人，TL_chat 或 megagroup 频道=群聊，广播频道=频道，未知/未加载项永不隐藏）；分类栏关闭时筛选完全失效。文件夹栏本身未改（多行自适应排布风险高，未按激进路线做）。

## 2026-10-25 UI 同步修复 + 安装包区分（Build #15 待构建，取代 Build #14）
- UI 同步：打开设置前先使配置缓存失效（绕过 1.5 秒节流），刚用智能屏蔽加的规则立刻可见；高级规则有内容时打开设置默认展开高级区，不再看起来像丢了；关键词编辑对话框确定时把输入规范化为解析后的“、”连接形式再写回，外部个数改用与保存相同的 RuleTextCodec 解析计数，对话框/外部个数/实际保存三处同一文本；全局设置保存后立即刷新聊天列表并同步分类栏的出现/消失。
- 全 UI 检查：所有设置开关均在打开面板时从最新配置初始化（缓存失效后），翻译/增强/聊天列表/规则卡片同此；智能屏蔽对话框、长按菜单、聊天列表分类栏的选中态均在点击时即时刷新，不依赖重启。
- 安装包区分：包名保持 com.tianqianguai.gramsieve 不变（保证与 Build #13 同签名密钥直接覆盖安装、规则与 LSPosed 启用不丢）；versionCode 5→1000（高于上游 2.6.10-beta 的 349，上游不再被当作更新），versionName 0.1.4→1.0.0-conso，应用名 GramSieve→GramSieve Conso。更新提示的根因是版本号，不是签名；签名不同只会让那个错误更新装不上。

## 2026-10-25 关键词编辑对话框 UI 统一（Build #16，含 Build #15 全部内容）
- 旧对话框用系统 AlertDialog 标题/按钮，与设置面板统一风格（16dp 圆角卡片、主题灰、17sp 粗体标题、13sp 粗体主题蓝标签、14sp 主题蓝按钮）不一致，看起来不同步。新对话框全部按面板风格自绘：标题、提示、关键词标签、输入框、预览标签、取消/清空/确定按钮均为面板样式。
- 新增实时预览：输入每改一次即用与保存相同的 RuleTextCodec 解析并显示“共 N 个：…”；预览个数、确定后外部个数、实际保存规则数三处同一解析结果，不再出现对话框里一套、外部另一套的感觉。确定时仍存规范化后的“、”连接形式。

## 2026-10-25 智能分析目标选择（Build #17）
- 智能分析（长按 → 智能分析并屏蔽）不再只能加入全局规则：对话框新增“添加到”选择，全局规则（所有聊天，默认，保持旧行为）或当前聊天规则（仅本聊天）。选当前聊天时，规则写入该聊天的 ChatRuleSet（enabled=true），并按该聊天已有规则去重，确认后立即刷新该聊天过滤；无法识别当前聊天时（dialogId=0）该选项置灰，只能选全局。
- 注意：候选词仍先按全局规则去重后展示；确认时再按所选目标去重一次，所以已在所选目标里的词不会重复加入，提示的“已加入 N 条”是实际新增数。已在全局规则里的词对所有聊天（未排除全局的聊天）已生效，无需再重复加入当前聊天。
- Cocoon 说明：本次未接 Cocoon，见下节。

## 2026-10-25 规则界面颜色/对比度修复（Build #18，含 Build #17）
- 截图证实的根因：旧关键词对话框的窗口是系统浅色（白色），而输入框文字颜色取自 Telegram 深色主题（白色），白字 on 白底，看起来像空框，尽管外面显示“已设置 37 个关键词”。新对话框（Build #16 起）已按面板风格自绘，但窗口外框仍可能露白；Build #18 在 show() 后强制把对话框窗口背景也设为面板卡片色，彻底去掉白边，白字只落在深色底上。
- 面板文字看不清：新增对比度保护，次要文字与卡片背景对比度低于 4.5:1 时自动向主文字色靠拢，直到达标，不改色相；对话框打开时的系统变暗也不会再让说明文字糊成一片。
- 分类功能入口说明：个人/群聊/频道分类不在旧版（截图里的版本）里，它在 Build #16 起的设置 → GramSieve → “聊天列表”卡片（在“增强功能”与“翻译”之间），打开“显示自动分类栏”后，回到聊天列表，文件夹栏下方会出现全部/个人/群聊/频道栏；置顶区分在同一卡片。

## 2026-10-25 本地分析加强 + 可插拔大模型后端（Build #19，含 #17/#18）
- 本地加强：强词扩展（首存/首充/充值送/注册送/提款秒到/包赢/带飞/跟单/倍投/梭哈/龙虎/牛牛/炸金花/轮盘/老虎机/电子游艺/盘口/水位/回水/银行卡等，仅作智能候选，需用户确认后才成规则，不自动屏蔽）；混淆网址还原（[.]、(.)、。、hxxp、122 . com）后再提取域名/IP；新增 QQ 号、USDT 地址（0x…/T…）候选。
- 可插拔后端：默认本地（离线、免费、私密）。设置 → GramSieve → “智能分析（AI）”卡片可开启大模型：OpenAI 兼容地址 + 模型 + 自备 Key（掩码输入，只存本机，不进仓库，不打日志）。仅在点“智能分析并屏蔽”时联网，只发消息正文/说明/按钮文字（最多 2000 字），不发发送者名、聊天 id、用户 id；大模型结果与本地候选去重合并，一起确认；失败/超时按开关回退本地。消息会发给所填服务方，按其计费与隐私政策处理，属实告知。
- 本构建包含 Build #17 的全局/当前聊天目标选择和 Build #18 的关键词对话框窗口颜色 + 面板对比度修复；#17/#18 不再单独交付。

## 2026-10-25 TG 代理自动管理（Build #20，含 #19 修复）
- 用户选择：自动添加并自动切换到最快代理，自定义失效时间，全选删除失效代理。
- 宿主接口已在 Conso 2.0.0 dex（classes9.dex）中核对，不是凭空假设：SharedConfig$ProxyInfo(address, port, username, password, secret)、SharedConfig.addProxy/deleteProxy/saveProxyList/loadProxyList、静态字段 currentProxy/proxyList、ConnectionsManager.setProxySettings 与 checkProxy（RequestTimeDelegate.run(long) 为握手延迟）。
- 来源诚实说明：调研未找到可验证的稳定机器可读公开代理源，因此代码里不硬编码任何第三方代理或列表地址；用户填信任的列表页地址（页面含 t.me/proxy 链接）或直接粘贴链接，模块再自动抓取、解析、测速、添加、切最快。测速优先用 Telegram 自身 checkProxy，失败时退回 TCP 连通延迟。
- 设置 → GramSieve → “代理（TG 直链）”卡片：自动添加、自动切换开关，来源地址、刷新间隔（默认 6 小时）、失效时间（默认 24 小时）、粘贴链接；“立即刷新”后台抓取测速并切最快；“管理代理”对话框可全选、删除所选、删除失效（失效 = 测不通或超过自定义失效时间未测通）。删除只影响模块列表，宿主当前正在用的代理不会因此断开。
- 自动刷新触发：打开聊天列表时，若自动添加已开且超过刷新间隔，则后台刷新一次（进程内每小时最多一次防刷屏），切最快后保存配置。

## 2026-10-25 频道加入文件夹 + 翻译链接修复（Build #21）
- 频道右上角菜单新增“将当前频道添加至文件夹”：仅在当前为广播频道时出现，弹文件夹列表，点选后调用宿主 MessagesController.addDialogToFolder，写完再重读文件夹核对，失败时改文件夹列表并让宿主保存；无文件夹时提示先在聊天列表创建文件夹。
- 翻译链接修复：翻译钩子本身不碰消息文字与实体，链接丢失是宿主/服务器翻译丢实体所致。修复为在 MessageObject.updateTranslation() 之后，对译文与译文标题扫描 https?://、t.me/、www. 链接，已有可点链接的不动，没有的用 Telegram 自身的 URLSpanNoUnderline 重新套上（t.me/www 补 https:// 前缀，失败时退回标准 URLSpan）。若服务器把链接文字整个从译文中删了，客户端无法从译文里变回来，需用“显示原文”查看原链接。

## 2026-10-25 文件夹自动归档（Build #22）
- 聊天列表卡片新增“文件夹聊天自动归档”开关（用户要求，默认开，可关）：进文件夹的聊天自动归档（主列表不再出现），从所有文件夹移出后自动取消归档；已在文件夹里的聊天在打开聊天列表时同步一次。
- 归档走 Telegram 服务器接口 folders.editPeerFolders（Dialog.folder_id 1=归档，0=主列表），不是本机假隐藏，换设备后状态仍在；成功后才更新本地 folder_id 并刷新列表。
- 只动模块自己归档的聊天：手动归档的聊天不会被自动取消归档；手动取消归档的文件夹聊天进入退出名单，重启后也不会被重新归档，移出所有文件夹会清除退出状态，加回来后重新归档；多文件夹时只在所有文件夹都移出后才取消归档。

## 2026-10-25 分类按类目 + 代理只扫节点文件夹（Build #23）
- 分类栏按类目独立：所有、归档、每个文件夹各记一套 全部/个人/群聊/频道筛选（默认全部），切类目会恢复该类目的上次筛选，互不干扰；检测不到当前类目时回退到全局筛选，不会因此不筛选。
- 代理只扫指定文件夹：代理卡片新增“代理文件夹”输入（默认 节点，可改），刷新时会读取该文件夹里各聊天最近的消息（最多 20 个聊天、各 50 条，用与后台加载相同的隔离历史接口，不动 Telegram 分页状态），提取 t.me/proxy 链接；只扫这一个文件夹，不扫其他文件夹；文件夹不存在时只用网址/粘贴来源，不报错。
- 代理识别已添加的：刷新先读入 Conso 自带代理列表（地址/端口/密钥/测速/可用状态），合并到管理列表里，密钥按小写归一化比对，已添加的代理会显示、参与测速，不再重复添加。
- 文件夹弹窗面板样式：频道“添加至文件夹”弹窗改用面板主题色（宿主主题背景/主文字/主题色按钮，列表项逐行设色，滚动后仍可读），不再是白底系统弹窗；列表排除 ALL_CHATS（Telegram 自带“所有聊天”伪文件夹，不是真实文件夹）。

## 2026-10-25 分类修复 + 代理识别修复 + 规则备份 + 弹窗统一样式（Build #24）
- 归档说明：文件夹自动归档用的是 Telegram 自带归档（Dialog.folder_id 1），不是新建文件夹或新文件夹标签；归档后聊天出现在 Telegram 自己的“已归档”里，取消归档后回到原来的文件夹，不会生成新的归档。
- 分类栏修复：改用 ViewPager 当前页码识别当前类目（第 0 页是所有，第 i+1 页是第 i 个文件夹），不再只靠标签颜色识别（部分宿主标签颜色不区分选中态时，所有类目都会被当成“所有”，等于还是全局筛选）；切标签时栏的选中状态会跟随更新（300ms 节流）；识别不到时回退到颜色识别，再回退到全局筛选。
- 代理识别修复：同地址同端口、密钥格式不同时按同一代理合并，宿主版本为准；管理弹窗打开时就读入宿主代理，不只在刷新时读入；宿主 SOCKS5 代理（无密钥但有账号密码）不再被清理逻辑删掉。
- 规则备份：全局设置里新增“规则备份”卡片，导出规则（复制或分享，文本需自己存到笔记/文件里），导入规则（粘贴备份文本，合并去重，不改开关、代理和大模型 key，导入后面板关闭）。备份只含规则，不含其他设置。
- 弹窗统一样式：核对了模块里全部系统弹窗（撤回原文、编辑历史、编辑内容、AI 分析中、智能分析确认、添加至文件夹），全部改为宿主主题背景/主文字/主题色按钮，不再出现白底系统弹窗；设置面板里的弹窗本来就是面板样式，新加的备份弹窗也按同一套样式做。

## 2026-10-25 归档/代理/分类修复（Build #25）
- 归档同步排除 ALL_CHATS 伪文件夹：Conso 的文件夹列表里 ALL_CHATS 不是用户文件夹，把它当成文件夹会把全部聊天都当成文件夹聊天归档；#25 同步时跳过它，只处理真正用户文件夹。
- 代理扫描补漏：除了正文和标题，现在还读消息里的链接实体（“点击获取”这类文字背后的链接）和内联按钮里的链接；文件夹里的聊天按最近消息时间排序，最新的先扫，上限从 20 个提到 30 个，总等待从 30 秒提到 45 秒；刷新提示里会写“文件夹 X 个”，能一眼看出文件夹这一步抓到没有。
- 分类栏缺失重试：创建聊天列表时如果标签/列表还没准备好，第一次装栏会悄悄失败且不再重试；#25 在列表绑定单元格时发现栏缺失且开关开着，会每 2 秒重试装一次，直到装上为止。

## 2026-10-25 保存/回到列表同步修复（Build #26）
- 原因：全局面板可以从 Conso 设置里打开，那时宿主是设置页不是聊天列表，保存后的同步动作落不到列表上；用返回键回到列表时列表视图也不会重建，createView 不再跑一次，所以开关明明开了，栏和归档却一直不生效。
- 修复：保存时除了同步当前宿主，还同步到最近一次的聊天列表实例，并立即跑一次文件夹归档同步；回到聊天列表时（onResume）也重新同步一次分类栏和归档，不用重启 Conso。

## 2026-10-25 菜单补注 + 代理扫描诊断（Build #27）
- 添加到文件夹：创建聊天界面时如果频道信息还没加载完，广播判断会失败，菜单项会被永久跳过；#27 在回到聊天界面时重新注入一次菜单（已有的项不会重复），广播频道里应该能重新看到“将当前频道添加至文件夹”。
- 代理扫描诊断：文件夹抓到 0 个时，刷新提示里会多写一步诊断，例如“文件夹 节点：聊天 X 个，历史成功 Y 个，消息 Z 条，文本 N 字”或“文件夹未找到/历史接口不支持”，能直接定位是文件夹没找到、历史没拉到，还是拉到了但里面没有链接。
- 重要：安装模块新版后必须强制停止 Conso 再重新打开，LSPosed 只在应用启动时加载模块代码，不重启就还是旧代码在跑；之前说“不用重启”是错的，特此纠正。

## 2026-10-25 代理文件夹点选 + 扫描用当前账号（Build #28）
- 代理文件夹不再纯靠手打：点那一栏或旁边的“选择文件夹”，会列出已有的用户文件夹（不含 ALL_CHATS 伪项），点哪个就填哪个；选完点“立即刷新”即可。
- 扫描改用当前选中的 Telegram 账号，不再写死 0 号账号；如果选中账号不是 0 号，旧版读到的文件夹列表里根本没有“节点”，这就是文件夹 0 的一个可能原因（#27 的诊断会佐证）。
- 安装后必须强制停止 Conso 再重新打开，否则 LSPosed 仍加载旧代码。

## 2026-10-25 日志导出 + 完整备份（Build #29）
- 数据备份卡片改名并扩容：保留“导出规则 / 导入规则”（只管规则，合并）；新增“导出完整备份 / 导入完整备份”（全部设置一起：开关、规则、分类、翻译、代理列表、大模型地址/模型/Key、文件夹设置；导入完整备份会替换当前全部设置，不是合并；完整备份用 {"fullBackup":true,"config":...} 包裹，与规则备份区分，误贴规则备份进完整导入会被拒绝）。
- 新增“导出日志”：读取宿主应用外部文件目录 GramSieve/gramsieve.log（取不到则读内部文件），取最近约 2000 行，复制/分享；日志里不含大模型 Key。
- 代理文件夹扫描诊断不再只靠会被截断的 Toast：刷新后诊断同时写进日志文件，并显示在代理卡片摘要里（“上次文件夹扫描：…”），下次打开设置还能看到。
- 安全提示：完整备份里有大模型 Key 和代理信息，不要发给别人，只存到自己的地方。

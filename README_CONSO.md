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

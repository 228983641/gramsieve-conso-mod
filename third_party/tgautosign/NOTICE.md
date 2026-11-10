# TGAutoSign integration provenance

- Author: wlmosv / wlmosv-png
- Source: https://github.com/wlmosv-png/TGAutoSign
- Imported commit: dd49b8780858ff8a5b5a3774a0383c92f1da288c (2026-11-10)
- License: GPL-3.0, original LICENSE included here. Original package and attribution retained.
- Runtime sources: app/src/main/java/io/github/wlmosv_png/tgautosign/
- Original regression tests and tooling retained in tools/.

Integration changes: API 101 adapter instead of a second Xposed module entry; restricted to GramSieve host scope; opt-in master switch; delayed startup; disable stops callbacks and gates network sends; GramSieve settings entry; fixes for Java 11 and lint; independent module update checks disabled; request interception returns the correct primitive type and supplies the originating account controller.

The existing upstream panel is retained, including its themes, account isolation, learning, scheduling, judgement, exclusions, summaries and export/import. This is a source integration, not a claim that every path has been device-tested on Conso. Do not enable the standalone TGAutoSign module simultaneously. Sign-in data resides in the host's tg_autosign_gen preferences; GramSieve's full backup currently does not include it. Use the sign-in panel's own export/import.

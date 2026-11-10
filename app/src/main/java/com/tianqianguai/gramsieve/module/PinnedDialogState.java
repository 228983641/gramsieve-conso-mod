package com.tianqianguai.gramsieve.module;

/** Resolves pin state on every draw; never caches recycled row state. */
final class PinnedDialogState {
    private PinnedDialogState() { }

    static boolean isPinned(Object cell) {
        Object pinned = Reflect.invokeIfExists(cell, "getIsPinned", new Class<?>[0]);
        if (pinned instanceof Boolean) {
            return (Boolean) pinned;
        }
        // Forks may expose only the row field, or only the bound TLRPC.Dialog.
        pinned = Reflect.field(cell, "drawPin");
        if (pinned instanceof Boolean) {
            return (Boolean) pinned;
        }
        Object dialog = Reflect.field(cell, "currentDialog");
        return Boolean.TRUE.equals(Reflect.field(dialog, "pinned"));
    }
}

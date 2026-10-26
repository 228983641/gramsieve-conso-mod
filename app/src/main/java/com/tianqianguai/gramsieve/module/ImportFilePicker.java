package com.tianqianguai.gramsieve.module;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

/**
 * Build #43: file import for rules and full backups. The panel runs
 * inside Conso's activity, so the system file picker must be started
 * from that activity and its result comes back through the host
 * activity's onActivityResult, which TelegramHookInstaller forwards
 * here (only for this request code; every other result is untouched).
 * The picked file's text is handed to the pending callback, which
 * fills the import dialog's paste box so the existing validation and
 * import logic runs unchanged. Nothing is parsed here.
 */
final class ImportFilePicker {

    interface Callback {
        void onText(String text);
    }

    static final int REQUEST_CODE = 0x47530077;
    private static final int MAX_BYTES = 5_000_000;

    private static volatile Callback pendingCallback;

    private ImportFilePicker() {
    }

    static boolean launch(Activity activity, Callback callback) {
        try {
            if (activity == null || activity.isFinishing() || callback == null) {
                return false;
            }
            pendingCallback = callback;
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            pick.setType("*/*");
            activity.startActivityForResult(pick, REQUEST_CODE);
            return true;
        } catch (Throwable ignored) {
            pendingCallback = null;
            return false;
        }
    }

    static boolean handleResult(int requestCode, int resultCode, Intent data, Context context) {
        if (requestCode != REQUEST_CODE) {
            return false;
        }
        Callback callback = pendingCallback;
        pendingCallback = null;
        if (callback == null) {
            return true;
        }
        try {
            if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null || context == null) {
                return true;
            }
            Uri uri = data.getData();
            java.io.InputStream in = context.getContentResolver().openInputStream(uri);
            if (in == null) {
                return true;
            }
            try {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int total = 0;
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    total += read;
                    if (total > MAX_BYTES) {
                        return true;
                    }
                    out.write(buffer, 0, read);
                }
                callback.onText(out.toString(java.nio.charset.StandardCharsets.UTF_8.name()));
            } finally {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return true;
    }
}

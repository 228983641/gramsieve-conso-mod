package com.tianqianguai.gramsieve.module;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Build #32: the ONE design system for every settings surface.
 * Before this class, the panel, the smart dialog, the folder dialog
 * and the panel's own dialogs each resolved Telegram theme colours
 * and row spacing in their own way, which is exactly how the panel
 * ended up dark-on-dark in one place and white-on-white in another.
 * Every surface now goes through here: the same theme keys, the
 * same type scale (17sp bold title, 13sp bold accent section label,
 * 15sp rows, 13sp secondary info), the same row padding (16dp
 * horizontal, 12dp vertical) and the same 1px inset divider. New
 * features must use these helpers too, not invent their own colours.
 */
public final class DialogTheme {

    private DialogTheme() {
    }

    public static final class Colors {
        public final int background;
        public final int screen;
        public final int primary;
        public final int secondary;
        public final int accent;
        public final int divider;

        public Colors(int background, int screen, int primary, int secondary, int accent, int divider) {
            this.background = background;
            this.screen = screen;
            this.primary = primary;
            this.secondary = secondary;
            this.accent = accent;
            this.divider = divider;
        }
    }

    public static int dp(Context context, float value) {
        try {
            return Math.round(TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, value, context.getResources().getDisplayMetrics()));
        } catch (Throwable ignored) {
            return Math.round(value);
        }
    }

    private static int adjustAlpha(int color, float factor) {
        int alpha = Math.round(Color.alpha(color) * factor);
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private static int androidThemeColor(Context context, int attr, int fallback) {
        try {
            TypedValue typedValue = new TypedValue();
            if (context != null && context.getTheme() != null
                    && context.getTheme().resolveAttribute(attr, typedValue, true)) {
                if (typedValue.type >= 28 && typedValue.type <= 31) {
                    return typedValue.data;
                }
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    private static int telegramThemeColor(Context context, String keyFieldName, int fallback) {
        try {
            Class<?> themeClass = Class.forName(
                    "org.telegram.ui.ActionBar.Theme", false, context.getClassLoader());
            java.lang.reflect.Field keyField = themeClass.getDeclaredField(keyFieldName);
            keyField.setAccessible(true);
            java.lang.reflect.Method getColor = themeClass.getDeclaredMethod("getColor", int.class);
            getColor.setAccessible(true);
            Object color = getColor.invoke(null, keyField.getInt(null));
            return color instanceof Integer ? (Integer) color : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
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

    private static int ensureTextColor(int foreground, int background) {
        float light = Math.max(luminance(foreground), luminance(background));
        float dark = Math.min(luminance(foreground), luminance(background));
        if ((light + 0.05f) / (dark + 0.05f) >= 4.5f) {
            return foreground;
        }
        return luminance(background) > 0.5f ? 0xFF000000 : 0xFFFFFFFF;
    }

    public static Colors resolve(Context context) {
        int background = telegramThemeColor(context, "key_windowBackgroundWhite",
                androidThemeColor(context, android.R.attr.colorBackground, 0xFF121212));
        int screen = telegramThemeColor(context, "key_windowBackgroundGray", background);
        int primary = ensureTextColor(telegramThemeColor(context, "key_windowBackgroundWhiteBlackText",
                androidThemeColor(context, android.R.attr.textColorPrimary, 0xFFFFFFFF)), background);
        int secondary = ensureTextColor(telegramThemeColor(context, "key_windowBackgroundWhiteGrayText2",
                androidThemeColor(context, android.R.attr.textColorSecondary, 0xFFAAAAAA)), background);
        int accent = telegramThemeColor(context, "key_dialogButton",
                androidThemeColor(context, android.R.attr.colorAccent, 0xFF2AABEE));
        int divider = telegramThemeColor(context, "key_divider", adjustAlpha(primary, 0.14f));
        return new Colors(background, screen, primary, secondary, accent, divider);
    }

    public static void styleTitle(TextView view, Colors colors) {
        view.setTextColor(colors.primary);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    }

    public static void styleSection(TextView view, Colors colors) {
        view.setTextColor(colors.accent);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    }

    public static void styleBody(TextView view, Colors colors, boolean secondary) {
        view.setTextColor(secondary ? colors.secondary : colors.primary);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, secondary ? 13f : 14f);
    }

    public static View divider(Context context, Colors colors) {
        View divider = new View(context);
        divider.setBackgroundColor(colors.divider);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(context, 1f)));
        params.leftMargin = dp(context, 16f);
        divider.setLayoutParams(params);
        return divider;
    }

    private static boolean isLabel(View view) {
        return "gramsieve_row_label".equals(view.getTag())
                || "gramsieve_section".equals(view.getTag());
    }

    private static boolean isInfo(View view) {
        return "gramsieve_info".equals(view.getTag());
    }

    /**
     * Gives every visible row in a vertical group uniform padding and
     * inserts a 1px inset divider between consecutive rows. A divider
     * is never inserted after a label, or before a section header, so
     * a label stays attached to the row it describes. Hidden (GONE)
     * views are ignored. Row spacing comes from padding, not margins,
     * so dividers sit directly between rows.
     */
    public static void styleGroupRows(LinearLayout group, Colors colors, Context context) {
        java.util.ArrayList<View> rows = new java.util.ArrayList<>();
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child != null && child.getVisibility() == View.VISIBLE) {
                rows.add(child);
            }
        }
        for (View row : rows) {
            try {
                if (row.getLayoutParams() instanceof LinearLayout.LayoutParams) {
                    LinearLayout.LayoutParams rowParams =
                            (LinearLayout.LayoutParams) row.getLayoutParams();
                    rowParams.topMargin = 0;
                    rowParams.bottomMargin = 0;
                    row.setLayoutParams(rowParams);
                }
            } catch (Throwable ignored) {
            }
            boolean label = isLabel(row);
            boolean info = isInfo(row);
            try {
                row.setPadding(dp(context, 16f),
                        dp(context, label ? 10f : (info ? 6f : 12f)),
                        dp(context, 16f),
                        dp(context, label ? 2f : (info ? 6f : 12f)));
            } catch (Throwable ignored) {
            }
        }
        for (int i = rows.size() - 2; i >= 0; i--) {
            View current = rows.get(i);
            View next = rows.get(i + 1);
            // Build #33: info paragraphs are text blocks, not rows.
            // Putting a divider on either side of one chops a paragraph
            // into stripes, which is a large part of why #32 looked
            // worse than the cards, not better.
            if (isLabel(current) || isInfo(current) || isInfo(next)
                    || "gramsieve_section".equals(next.getTag())) {
                continue;
            }
            group.addView(divider(context, colors), group.indexOfChild(next));
        }
    }

    public static void styleAlertDialog(AlertDialog dialog, Context context) {
        styleAlertDialog(dialog, resolve(context));
    }

    public static void styleAlertDialog(AlertDialog dialog, Colors colors) {
        try {
            if (dialog == null) {
                return;
            }
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(
                        new android.graphics.drawable.ColorDrawable(colors.background));
            }
            View titleView = dialog.findViewById(android.R.id.title);
            if (titleView instanceof TextView) {
                ((TextView) titleView).setTextColor(colors.primary);
            }
            View messageView = dialog.findViewById(android.R.id.message);
            if (messageView instanceof TextView) {
                ((TextView) messageView).setTextColor(colors.primary);
            }
            for (int which : new int[]{AlertDialog.BUTTON_POSITIVE,
                    AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
                try {
                    android.widget.Button button = dialog.getButton(which);
                    if (button != null) {
                        button.setTextColor(colors.accent);
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }
}

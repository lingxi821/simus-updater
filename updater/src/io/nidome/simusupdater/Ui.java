package io.nidome.simusupdater;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 界面用的配色与小组件工厂。
 *
 * 全部用代码画（不依赖 AndroidX/Material 库）：构建链是 aapt2 + javac + d8，
 * 没有 Gradle 依赖，所以圆角、描边、渐变都靠 {@link GradientDrawable}。
 */
final class Ui {

    // 配色（深色，和 res/values/colors.xml 里的主题一致）
    static final int BG = 0xFF0B0F14;
    static final int SURFACE = 0xFF151A21;
    static final int SURFACE_2 = 0xFF1B222B;
    static final int STROKE = 0xFF232A33;
    static final int TEXT = 0xFFE6EDF3;
    static final int TEXT_DIM = 0xFF9AA4B2;
    static final int TEXT_FAINT = 0xFF6E7681;
    static final int CYAN = 0xFF25F4EE;
    static final int PINK = 0xFFFE2C55;
    static final int GREEN = 0xFF3FB950;
    static final int AMBER = 0xFFD29922;
    static final int RED = 0xFFF85149;
    static final int ON_ACCENT = 0xFF06171A;

    private Ui() {
    }

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()));
    }

    /** 圆角实心背景。 */
    static GradientDrawable bg(int color, float radiusDp, Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    /** 圆角 + 描边背景。 */
    static GradientDrawable stroked(int fill, int stroke, float radiusDp, Context c) {
        GradientDrawable d = bg(fill, radiusDp, c);
        d.setStroke(dp(c, 1), stroke);
        return d;
    }

    /** 渐变背景（主按钮）。 */
    static GradientDrawable gradient(int from, int to, float radiusDp, Context c) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{from, to});
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    static TextView tv(Context c, CharSequence text, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    static TextView bold(TextView t) {
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    static TextView mono(Context c, float sp, int color) {
        TextView t = tv(c, "", sp, color);
        t.setTypeface(Typeface.MONOSPACE);
        t.setLineSpacing(dp(c, 2), 1f);
        t.setTextIsSelectable(true);
        return t;
    }

    /** 撑开的占位视图。 */
    static View spacer(Context c) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        return v;
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    /** 小圆点（状态指示）。 */
    static View dot(Context c, int color) {
        View v = new View(c);
        int s = dp(c, 8);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s, s);
        lp.rightMargin = dp(c, 8);
        v.setLayoutParams(lp);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        v.setBackground(d);
        return v;
    }

    static int alpha(int color, float f) {
        return Color.argb((int) (255 * f), Color.red(color), Color.green(color), Color.blue(color));
    }
}

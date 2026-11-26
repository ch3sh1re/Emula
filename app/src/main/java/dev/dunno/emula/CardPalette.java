package dev.dunno.emula;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;

/** The original phone palette, shared by both card lists. Hues are stored in degrees. */
final class CardPalette {
    static final int ACTIVE_OUTLINE = 0xFFDAF0FF;

    static float defaultHue(int index) {
        return (268f + index * 137.50776f) % 360f;
    }

    static boolean valid(float hue) {
        return hue >= 0f && hue < 360f;
    }

    static GradientDrawable background(float hue, boolean active, int radius, int stroke) {
        GradientDrawable shape = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, gradientColors(hue, active));
        shape.setCornerRadius(radius);
        if (active) shape.setStroke(stroke, ACTIVE_OUTLINE);
        return shape;
    }

    static int[] gradientColors(float hue, boolean active) {
        int start = forWhiteText(hue, .72f, .76f);
        int end = forWhiteText((hue + 18f) % 360f, .82f, .54f);
        if (!active) {
            start = blendWithSurface(start, .24f);
            end = blendWithSurface(end, .28f);
        }
        return new int[]{start, end};
    }

    private static int forWhiteText(float hue, float saturation, float value) {
        float[] hsv = {hue, saturation, value};
        int color;
        do {
            color = Color.HSVToColor(hsv);
            hsv[2] -= .025f;
        } while (Color.luminance(color) > .18f && hsv[2] >= .34f);
        return color;
    }

    private static int blendWithSurface(int color, float amount) {
        float remaining = 1f - amount;
        return Color.rgb(Math.round(Color.red(color) * remaining + 48 * amount),
                Math.round(Color.green(color) * remaining + 52 * amount),
                Math.round(Color.blue(color) * remaining + 60 * amount));
    }

    private CardPalette() {}
}

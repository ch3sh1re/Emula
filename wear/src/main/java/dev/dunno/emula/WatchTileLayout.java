package dev.dunno.emula;

import android.content.Context;
import android.os.SystemClock;

import androidx.wear.protolayout.ActionBuilders;
import androidx.wear.protolayout.ColorBuilders.LinearGradient;
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters;
import androidx.wear.protolayout.DimensionBuilders.BoundingBoxRatio;
import androidx.wear.protolayout.LayoutElementBuilders.*;
import androidx.wear.protolayout.ModifiersBuilders.*;
import androidx.wear.protolayout.ResourceBuilders.*;
import androidx.wear.protolayout.TimelineBuilders.TimeInterval;
import androidx.wear.protolayout.TimelineBuilders.Timeline;
import androidx.wear.protolayout.TimelineBuilders.TimelineEntry;
import androidx.wear.protolayout.TypeBuilders.BoolProp;
import androidx.wear.protolayout.TypeBuilders.FloatProp;
import androidx.wear.protolayout.expression.DynamicBuilders.DynamicBool;
import androidx.wear.protolayout.expression.DynamicBuilders.DynamicInstant;
import androidx.wear.protolayout.expression.ProtoLayoutExperimental;
import androidx.wear.protolayout.expression.VersionBuilders.VersionInfo;
import androidx.wear.tiles.TileBuilders.Tile;

import java.time.Instant;

import static androidx.wear.protolayout.ColorBuilders.argb;
import static androidx.wear.protolayout.DimensionBuilders.dp;
import static androidx.wear.protolayout.DimensionBuilders.expand;
import static androidx.wear.protolayout.DimensionBuilders.sp;
import static androidx.wear.protolayout.LayoutElementBuilders.*;
import static androidx.wear.protolayout.ModifiersBuilders.SEMANTICS_ROLE_BUTTON;
import static dev.dunno.emula.WatchTileAction.Kind;

final class WatchTileLayout {
    static final String VERSION = "3";
    private static final int SURFACE = 0xff30343c, WHITE = 0xfff4f6fa, MUTED = 0xffadb4bf;
    private final Context context;
    private final float width;
    private final VersionInfo schema;

    WatchTileLayout(Context context, DeviceParameters device) {
        this.context = context;
        int screenWidth = device.getScreenWidthDp();
        width = Math.min(188, (screenWidth > 0 ? screenWidth : 192) * .81f);
        schema = device.getRendererSchemaVersion();
    }

    static Resources resources() {
        return new Resources.Builder().setVersion(VERSION)
                .addIdToImageMapping("timer", resource(R.drawable.ic_tile_timer))
                .addIdToImageMapping("next", resource(R.drawable.ic_tile_next)).build();
    }

    private static ImageResource resource(int drawable) {
        return new ImageResource.Builder().setAndroidResourceByResId(
                new AndroidImageResourceByResId.Builder().setResourceId(drawable).build()).build();
    }

    Tile working(boolean start) {
        return tile(text(context.getString(start ? R.string.starting : R.string.stopping), 18, WHITE, 1));
    }

    Tile cards(SavedCard selected, SavedCard activeCard, String next, WatchNfcSession.Pending pending,
            long revision, int seconds, String error) {
        if (selected == null && pending == null) {
            return tile(column().addContent(text(context.getString(R.string.tile_no_cards), 17, WHITE, 2))
                    .addContent(gap(6)).addContent(openApp()).build());
        }
        if (pending == null) return tile(cardView(selected, next, null, revision, seconds, error));
        LayoutElement active = cardView(activeCard, next, pending, revision, seconds, error);
        long remaining = pending.stopAtElapsed - SystemClock.elapsedRealtime();
        if (!pending.active || pending.timerId.isEmpty() || remaining <= 0
                || selected == null || !error.isEmpty()) return tile(active);

        // A successful alarm clears the durable session and advances its revision once.
        // Buttons from this forecast are usable only after that exact transition has happened.
        LayoutElement ready = cardView(selected, next, null, revision + 1, seconds, "");
        long end = System.currentTimeMillis() + remaining;
        if (supports(300)) return timedTile(active, ready, end);
        TimelineEntry timed = new TimelineEntry.Builder().setLayout(Layout.fromLayoutElement(center(active)))
                .setValidity(new TimeInterval.Builder().setEndMillis(end).build()).build();
        return new Tile.Builder().setResourcesVersion(VERSION).setTileTimeline(new Timeline.Builder()
                .addTimelineEntry(timed).addTimelineEntry(TimelineEntry.fromLayoutElement(center(ready))).build()).build();
    }

    // The renderer can switch the visible buttons on its own clock, even if an update is queued.
    @androidx.annotation.OptIn(markerClass = ProtoLayoutExperimental.class)
    private static Tile timedTile(LayoutElement active, LayoutElement ready, long endMillis) {
        Instant end = Instant.ofEpochSecond((endMillis + 999) / 1000);
        DynamicBool running = DynamicInstant.platformTimeWithSecondsPrecision()
                .durationUntil(DynamicInstant.withSecondsPrecision(end)).toIntSeconds().gt(0);
        Box.Builder stack = new Box.Builder().setWidth(expand()).setHeight(expand());
        stack.addContent(layer(active, new BoolProp.Builder(true).setDynamicValue(running).build()));
        stack.addContent(layer(ready, new BoolProp.Builder(false).setDynamicValue(running.negate()).build()));
        return tile(stack.build());
    }

    @androidx.annotation.OptIn(markerClass = ProtoLayoutExperimental.class)
    private static Box layer(LayoutElement body, BoolProp visible) {
        return centeredBox().setModifiers(new Modifiers.Builder().setVisible(visible).build()).addContent(body).build();
    }

    private LayoutElement cardView(SavedCard card, String next, WatchNfcSession.Pending pending,
            long revision, int seconds, String error) {
        boolean active = pending != null;
        String name = card == null ? context.getString(R.string.recovery) : card.name;
        String target = active ? pending.cardId : card.id;
        String verb = context.getString(active ? R.string.tile_stop : R.string.tile_start);
        float hue = card == null ? CardPalette.defaultHue(0) : card.hueOrDefault(0);
        int[] colors = CardPalette.gradientColors(hue, false);
        Background.Builder fill = background(colors[0], 28);
        if (supports(500)) fill.setBrush(new LinearGradient.Builder(argb(colors[0]), argb(colors[1]))
                .setEndY(new BoundingBoxRatio.Builder(new FloatProp.Builder(1f).build()).build()).build());
        Modifiers face = action(verb + " " + name,
                new WatchTileAction(active ? Kind.STOP : Kind.START, revision, target))
                .setBackground(fill.build()).build();
        Box.Builder cardFace = new Box.Builder().setWidth(dp(width)).setHeight(dp(64)).setModifiers(face)
                .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER).setVerticalAlignment(VERTICAL_ALIGN_CENTER)
                .addContent(new Box.Builder().setWidth(dp(width - 28)).setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
                        .addContent(text(name, 18, WHITE, 2)).build());
        if (active) {
            // The renderer draws a gradient brush as a fill. Give the outline its own
            // foreground layer, as WatchCardSwipe does, so it survives that drawing path.
            Modifiers outline = new Modifiers.Builder().setBackground(background(0x00000000, 28).build())
                    .setBorder(new Border.Builder().setWidth(dp(2)).setColor(argb(CardPalette.ACTIVE_OUTLINE)).build())
                    .build();
            cardFace.addContent(new Box.Builder().setWidth(expand()).setHeight(expand()).setModifiers(outline).build());
        }
        Column.Builder column = column();
        if (!error.isEmpty()) column.addContent(text(error, 13, WHITE, 2)).addContent(gap(4));
        column.addContent(cardFace.build()).addContent(gap(5));
        if (!error.isEmpty()) return column.addContent(openApp()).build();
        String timer = seconds == 0 ? context.getString(R.string.tile_off) : context.getString(R.string.tile_seconds, seconds);
        float buttonWidth = next == null ? width * .68f : (width - 6) / 2;
        Row.Builder options = new Row.Builder().setVerticalAlignment(VERTICAL_ALIGN_CENTER);
        if (next != null) options.addContent(chip("next", context.getString(R.string.tile_next),
                context.getString(R.string.tile_next_card), buttonWidth,
                active ? null : new WatchTileAction(Kind.SELECT, revision, next))).addContent(space(6));
        options.addContent(chip("timer", timer, context.getString(R.string.tile_change_timer, timer), buttonWidth,
                active ? null : new WatchTileAction(Kind.TIMER, revision, target)));
        return column.addContent(options.build()).build();
    }

    private Box chip(String icon, String label, String description, float size, WatchTileAction tap) {
        Modifiers.Builder modifiers = tap == null ? new Modifiers.Builder() : action(description, tap);
        modifiers.setBackground(background(tap == null ? 0xff202329 : SURFACE, 24).build());
        Row row = new Row.Builder().setVerticalAlignment(VERTICAL_ALIGN_CENTER).addContent(icon(icon, 16))
                .addContent(space(4)).addContent(text(label, 13, tap == null ? MUTED : WHITE, 1)).build();
        return new Box.Builder().setWidth(dp(size)).setHeight(dp(48)).setModifiers(modifiers.build())
                .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER).setVerticalAlignment(VERTICAL_ALIGN_CENTER)
                .addContent(row).build();
    }

    private Box openApp() {
        String label = context.getString(R.string.tile_open);
        ActionBuilders.AndroidActivity app = new ActionBuilders.AndroidActivity.Builder()
                .setPackageName(context.getPackageName()).setClassName(WatchActivity.class.getName()).build();
        Modifiers modifiers = action(label, "open", new ActionBuilders.LaunchAction.Builder().setAndroidActivity(app).build())
                .setBackground(background(SURFACE, 24).build()).build();
        return new Box.Builder().setWidth(dp(width * .8f)).setHeight(dp(48)).setModifiers(modifiers)
                .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER).setVerticalAlignment(VERTICAL_ALIGN_CENTER)
                .addContent(text(label, 15, WHITE, 1)).build();
    }

    private static Modifiers.Builder action(String description, WatchTileAction tap) {
        return action(description, tap.id(), new ActionBuilders.LoadAction.Builder().build());
    }

    private static Modifiers.Builder action(String description, String id, ActionBuilders.Action action) {
        return new Modifiers.Builder().setClickable(new Clickable.Builder().setId(id).setOnClick(action).build())
                .setSemantics(new Semantics.Builder().setContentDescription(description).setRole(SEMANTICS_ROLE_BUTTON).build());
    }

    private boolean supports(int minor) { return schema.getMajor() > 1 || schema.getMinor() >= minor; }
    private Column.Builder column() { return new Column.Builder().setWidth(dp(width)).setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER); }
    private static Background.Builder background(int color, int radius) {
        return new Background.Builder().setColor(argb(color)).setCorner(new Corner.Builder().setRadius(dp(radius)).build());
    }
    private static Image icon(String id, int size) { return new Image.Builder().setResourceId(id).setWidth(dp(size)).setHeight(dp(size)).build(); }
    private static Spacer space(int width) { return new Spacer.Builder().setWidth(dp(width)).build(); }
    private static Spacer gap(int height) { return new Spacer.Builder().setHeight(dp(height)).build(); }
    private static Text text(String value, int size, int color, int lines) {
        return new Text.Builder().setText(value).setMaxLines(lines).setOverflow(TEXT_OVERFLOW_ELLIPSIZE)
                .setMultilineAlignment(TEXT_ALIGN_CENTER).setFontStyle(new FontStyle.Builder()
                        .setSize(sp(size)).setColor(argb(color)).build()).build();
    }
    private static Box.Builder centeredBox() {
        return new Box.Builder().setWidth(expand()).setHeight(expand())
                .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER).setVerticalAlignment(VERTICAL_ALIGN_CENTER);
    }
    private static Box center(LayoutElement body) { return centeredBox().addContent(body).build(); }
    private static Tile tile(LayoutElement body) {
        return new Tile.Builder().setResourcesVersion(VERSION).setTileTimeline(Timeline.fromLayoutElement(center(body))).build();
    }
}

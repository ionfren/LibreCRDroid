package re.abbot.librecr.app.overlay

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color

internal const val OVERLAY_PREFS = "librecr_overlay_preferences"

internal data class FloatingSettings(
    val enabled: Boolean,
    val transparent: Boolean,
    val showSecondary: Boolean,
    val fontSizeSp: Float,
    val fontStyle: String,
    val showArrow: Boolean,
    val x: Int,
    val y: Int,
    val cornerRadiusDp: Float,
    val opacity: Float,
    val dynamicIsland: Boolean,
    val islandVerticalOffsetDp: Float,
    val islandGapDp: Float,
    /** Pill size in island mode; 0 = follow the text. Lets the pill match the cutout, not the font. */
    val islandWidthDp: Float,
    val islandHeightDp: Float,
    /** Nudges the delta group (delta + unit) sideways; negative pulls it back towards the value. */
    val deltaOffsetDp: Float,
    val subtleOutline: Boolean,
    val glucoseRangeColors: Boolean,
) {
    companion object {
        const val KEY_ENABLED = "floating_glucose_enabled"
        const val KEY_TRANSPARENT = "floating_transparent"
        const val KEY_SHOW_SECONDARY = "floating_show_secondary"
        const val KEY_FONT_SIZE = "floating_font_size"
        const val KEY_FONT_STYLE = "floating_font_weight"
        const val KEY_SHOW_ARROW = "floating_show_arrow"
        const val KEY_X = "floating_x"
        const val KEY_Y = "floating_y"
        const val KEY_CORNER_RADIUS = "floating_corner_radius"
        const val KEY_OPACITY = "floating_opacity"
        /** Camera-cutout width the island straddles by default; 0 is a valid user choice. */
        const val DEFAULT_ISLAND_GAP_DP = 70f
        const val KEY_DYNAMIC_ISLAND = "floating_dynamic_island"
        const val KEY_ISLAND_VERTICAL_OFFSET = "floating_island_vertical_offset"
        const val KEY_ISLAND_GAP = "floating_island_gap"
        const val KEY_ISLAND_WIDTH = "floating_island_width"
        const val KEY_ISLAND_HEIGHT = "floating_island_height"
        const val KEY_DELTA_OFFSET = "floating_delta_offset"
        const val KEY_SUBTLE_OUTLINE = "floating_subtle_outline"
        const val KEY_GLUCOSE_RANGE_COLORS = "floating_glucose_range_colors"

        fun load(context: Context): FloatingSettings = context.overlayPrefs().let { prefs ->
            FloatingSettings(
                enabled = prefs.getBoolean(KEY_ENABLED, false),
                transparent = prefs.getBoolean(KEY_TRANSPARENT, false),
                showSecondary = prefs.getBoolean(KEY_SHOW_SECONDARY, false),
                fontSizeSp = prefs.getFloat(KEY_FONT_SIZE, 16f).coerceIn(12f, 48f),
                fontStyle = normalizeFontStyle(prefs.getString(KEY_FONT_STYLE, FONT_BOLD)),
                showArrow = prefs.getBoolean(KEY_SHOW_ARROW, true),
                x = prefs.getInt(KEY_X, 100),
                y = prefs.getInt(KEY_Y, 100),
                cornerRadiusDp = prefs.getFloat(KEY_CORNER_RADIUS, 28f).coerceIn(0f, 64f),
                opacity = prefs.getFloat(KEY_OPACITY, 0.6f).coerceIn(0.1f, 1f),
                dynamicIsland = prefs.getBoolean(KEY_DYNAMIC_ISLAND, false),
                islandVerticalOffsetDp = prefs.getFloat(KEY_ISLAND_VERTICAL_OFFSET, 0f).coerceIn(0f, 96f),
                islandGapDp = prefs.getFloat(KEY_ISLAND_GAP, DEFAULT_ISLAND_GAP_DP).coerceIn(0f, 220f),
                islandWidthDp = prefs.getFloat(KEY_ISLAND_WIDTH, 0f).coerceIn(0f, 420f),
                islandHeightDp = prefs.getFloat(KEY_ISLAND_HEIGHT, 0f).coerceIn(0f, 96f),
                deltaOffsetDp = prefs.getFloat(KEY_DELTA_OFFSET, 0f).coerceIn(-80f, 80f),
                subtleOutline = prefs.getBoolean(KEY_SUBTLE_OUTLINE, false),
                glucoseRangeColors = prefs.getBoolean(KEY_GLUCOSE_RANGE_COLORS, true),
            )
        }
    }
}

internal data class AodSettings(
    val enabled: Boolean,
    val opacity: Float,
    val backgroundColor: Int,
    /** The widget's tinted surface instead of [backgroundColor]; lights more OLED pixels. */
    val widgetSurface: Boolean,
    val textScale: Float,
    val chartScale: Float,
    /** A fixed card box instead of the content's own size; the chart then fills what is left. */
    val fixedSize: Boolean,
    /** Fixed box, as fractions of the screen width and height. */
    val widthFraction: Float,
    val heightFraction: Float,
    val verticalPosition: Float,
    val showChart: Boolean,
    val showArrow: Boolean,
    val showSecondary: Boolean,
    /** Sensor wear-life bar with the final 24 h in red, so a due sensor change is visible on AOD. */
    val showSensorLife: Boolean,
    val arrowScale: Float,
    val arrowVerticalOffsetDp: Float,
    val metaScale: Float,
    val positions: Set<String>,
    val alignment: String,
    val fontStyle: String,
) {
    companion object {
        const val KEY_ENABLED = "aod_overlay_enabled"
        const val KEY_OPACITY = "aod_opacity"
        const val KEY_BACKGROUND_COLOR = "aod_background_color"
        const val KEY_WIDGET_SURFACE = "aod_widget_surface"
        const val KEY_TEXT_SCALE = "aod_text_scale"
        const val KEY_CHART_SCALE = "aod_chart_scale"
        const val KEY_FIXED_SIZE = "aod_fixed_size"
        const val KEY_WIDTH_FRACTION = "aod_width_fraction"
        const val KEY_HEIGHT_FRACTION = "aod_height_fraction"
        const val KEY_VERTICAL_POSITION = "aod_vertical_position"
        const val KEY_SHOW_CHART = "aod_show_chart"
        const val KEY_SHOW_ARROW = "aod_show_arrow"
        const val KEY_SHOW_SECONDARY = "aod_show_secondary"
        const val KEY_SHOW_SENSOR_LIFE = "aod_show_sensor_life"
        const val KEY_ARROW_SCALE = "aod_arrow_scale"
        const val KEY_ARROW_VERTICAL_OFFSET = "aod_arrow_vertical_offset"
        const val KEY_META_SCALE = "aod_meta_scale"
        const val KEY_POSITIONS = "aod_positions"
        const val KEY_ALIGNMENT = "aod_alignment"
        const val KEY_FONT_STYLE = "aod_font_style"
        const val POSITION_TOP = "TOP"
        const val POSITION_CENTER = "CENTER"
        const val POSITION_BOTTOM = "BOTTOM"
        const val ALIGN_START = "START"
        const val ALIGN_CENTER = "CENTER"
        const val ALIGN_END = "END"

        fun load(context: Context): AodSettings = context.overlayPrefs().let { prefs ->
            AodSettings(
                enabled = prefs.getBoolean(KEY_ENABLED, false),
                opacity = prefs.getFloat(KEY_OPACITY, 1f).coerceIn(0.35f, 1f),
                backgroundColor = prefs.getInt(KEY_BACKGROUND_COLOR, Color.BLACK),
                widgetSurface = prefs.getBoolean(KEY_WIDGET_SURFACE, false),
                textScale = prefs.getFloat(KEY_TEXT_SCALE, 1.2f).coerceIn(0.5f, 6f),
                chartScale = prefs.getFloat(KEY_CHART_SCALE, 0.9f).coerceIn(0.5f, 2f),
                fixedSize = prefs.getBoolean(KEY_FIXED_SIZE, false),
                widthFraction = prefs.getFloat(KEY_WIDTH_FRACTION, 0.9f).coerceIn(0.3f, 1f),
                heightFraction = prefs.getFloat(KEY_HEIGHT_FRACTION, 0.22f).coerceIn(0.1f, 0.6f),
                verticalPosition = prefs.getFloat(KEY_VERTICAL_POSITION, 0.5f).coerceIn(0f, 1f),
                showChart = prefs.getBoolean(KEY_SHOW_CHART, true),
                showArrow = prefs.getBoolean(KEY_SHOW_ARROW, true),
                showSecondary = prefs.getBoolean(KEY_SHOW_SECONDARY, false),
                showSensorLife = prefs.getBoolean(KEY_SHOW_SENSOR_LIFE, true),
                arrowScale = prefs.getFloat(KEY_ARROW_SCALE, 1f).coerceIn(0.5f, 2f),
                arrowVerticalOffsetDp = prefs.getFloat(KEY_ARROW_VERTICAL_OFFSET, 0f).coerceIn(-20f, 20f),
                metaScale = prefs.getFloat(KEY_META_SCALE, 1f).coerceIn(0.5f, 3f),
                positions = prefs.getStringSet(KEY_POSITIONS, setOf(POSITION_TOP)) ?: setOf(POSITION_TOP),
                alignment = prefs.getString(KEY_ALIGNMENT, ALIGN_CENTER) ?: ALIGN_CENTER,
                fontStyle = normalizeFontStyle(prefs.getString(KEY_FONT_STYLE, FONT_BOLD)),
            )
        }
    }
}

internal const val FONT_THIN = "thin"
internal const val FONT_REGULAR = "regular"
internal const val FONT_BOLD = "bold"

internal fun normalizeFontStyle(value: String?): String =
    when (value) {
        FONT_THIN, FONT_REGULAR, FONT_BOLD -> value
        "100", "200", "300", "light" -> FONT_THIN
        "400", "500", "medium" -> FONT_REGULAR
        else -> FONT_BOLD
    }

internal fun Context.overlayPrefs(): SharedPreferences =
    getSharedPreferences(OVERLAY_PREFS, Context.MODE_PRIVATE)

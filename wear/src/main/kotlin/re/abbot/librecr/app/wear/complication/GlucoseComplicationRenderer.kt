package re.abbot.librecr.app.wear.complication

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import re.abbot.librecr.app.R
import re.abbot.librecr.app.data.SensorStateStore
import re.abbot.librecr.app.data.WearAppearanceSettings
import re.abbot.librecr.app.data.WearDisplayFontWeight
import re.abbot.librecr.protocol.TrendArrowShape
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

object GlucoseComplicationRenderer {
    private const val STALE_AFTER_MS = 6 * 60_000L
    private const val TIMESTAMP_TEXT_SCALE = 0.66f
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

    fun isFresh(reading: SensorStateStore.LastGlucose?, nowMs: Long = System.currentTimeMillis()): Boolean =
        reading != null && reading.receivedAtMs > 0L && nowMs - reading.receivedAtMs < STALE_AFTER_MS

    fun timeText(reading: SensorStateStore.LastGlucose?): String =
        reading?.receivedAtMs?.takeIf { it > 0L }?.let { timeFormatter.format(Instant.ofEpochMilli(it)) } ?: "--:--"

    fun deltaText(reading: SensorStateStore.LastGlucose?): String =
        formatDelta(reading?.deltaMgDlPerMin)

    fun valueText(reading: SensorStateStore.LastGlucose?): String =
        if (isFresh(reading)) reading?.mgDL?.toString() ?: "--" else "--"

    fun buildAgeDeltaBitmap(
        context: Context,
        reading: SensorStateStore.LastGlucose?,
        settings: WearAppearanceSettings = WearAppearanceSettings(),
        size: Int = 150,
    ): Bitmap {
        val fresh = isFresh(reading)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
        }
        canvas.drawColor(Color.TRANSPARENT)

        paint.color = settings.timestampColor(stale = !fresh)
        paint.typeface = typefaceFor(context, settings.fontWeight)
        paint.textSize = size * 0.34f * TIMESTAMP_TEXT_SCALE
        drawCenteredText(canvas, timeText(reading), size / 2f, size * 0.27f, paint)
        paint.color = settings.deltaColorFor(reading?.mgDL)
        paint.typeface = typefaceFor(context, settings.fontWeight)
        paint.textSize = size * 0.36f
        drawCenteredText(canvas, deltaText(reading), size / 2f, size * 0.84f, paint)
        return bitmap
    }

    fun buildTrendValueBitmap(
        context: Context,
        reading: SensorStateStore.LastGlucose?,
        settings: WearAppearanceSettings = WearAppearanceSettings(),
        size: Int = 150,
    ): Bitmap {
        val fresh = isFresh(reading)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = typefaceFor(context, settings.fontWeight)
        }
        canvas.drawColor(Color.TRANSPARENT)

        val arrow = buildArrowBitmap(reading, settings, (size * 0.48f).roundToInt())
        canvas.drawBitmap(arrow, (size - arrow.width) / 2f, size * 0.10f, null)
        paint.style = Paint.Style.FILL
        paint.strokeWidth = 0f
        paint.color = if (fresh) settings.glucoseColorFor(reading?.mgDL) else settings.timestampStaleColor
        paint.typeface = typefaceFor(context, settings.fontWeight)
        paint.textSize = size * 0.42f
        drawCenteredText(canvas, valueText(reading), size / 2f, size * 0.74f, paint)
        return bitmap
    }

    fun buildArrowBitmap(
        reading: SensorStateStore.LastGlucose?,
        settings: WearAppearanceSettings = WearAppearanceSettings(),
        size: Int = 150,
    ): Bitmap =
        buildArrowBitmap(
            trend = reading?.trend,
            size = size,
            color = if (isFresh(reading)) settings.trendColorFor(reading?.mgDL) else settings.timestampStaleColor,
            fontWeight = settings.fontWeight,
        )

    fun buildArrowBitmap(
        trend: String?,
        size: Int = 150,
        color: Int = Color.WHITE,
        fontWeight: WearDisplayFontWeight = WearDisplayFontWeight.NORMAL,
    ): Bitmap =
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                style = Paint.Style.STROKE
                strokeWidth = size * 0.094f * fontWeight.strokeScale()
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            canvas.drawColor(Color.TRANSPARENT)
            drawAodArrow(canvas, paint, trend, 0f, 0f, size.toFloat())
        }

    fun contentDescription(label: String, reading: SensorStateStore.LastGlucose?): String =
        if (reading != null) {
            "$label ${reading.mgDL} mg/dL ${trendLabel(reading.trend)} ${timeText(reading)} ${deltaText(reading)}"
        } else {
            "$label no glucose"
        }

    private fun trendLabel(trend: String?): String = when (trend) {
        "FALLING_QUICKLY" -> "falling quickly"
        "FALLING" -> "falling"
        "STABLE" -> "stable"
        "RISING" -> "rising"
        "RISING_QUICKLY" -> "rising quickly"
        else -> "unknown trend"
    }

    private fun drawAodArrow(canvas: Canvas, paint: Paint, trend: String?, left: Float, top: Float, size: Float) {
        val rotation = TrendArrowShape.rotationDegrees(trend) ?: return
        val cx = left + size / 2f
        val cy = top + size / 2f
        canvas.save()
        canvas.scale(TrendArrowShape.SCALE, TrendArrowShape.SCALE, cx, cy)
        canvas.rotate(rotation, cx, cy)
        TrendArrowShape.SEGMENTS.forEach { s ->
            canvas.drawLine(left + s.x0 * size, top + s.y0 * size, left + s.x1 * size, top + s.y1 * size, paint)
        }
        canvas.restore()
    }

    private fun drawCenteredText(canvas: Canvas, text: String, x: Float, y: Float, paint: Paint) {
        val bounds = Rect()
        paint.getTextBounds(text, 0, text.length, bounds)
        canvas.drawText(text, x, y - bounds.exactCenterY(), paint)
    }

    private fun typefaceFor(context: Context, fontWeight: WearDisplayFontWeight): Typeface {
        val baseFont = if (fontWeight.weight >= WearDisplayFontWeight.SEMIBOLD.weight) {
            R.font.google_sans_rounded_bold
        } else {
            R.font.google_sans_rounded_regular
        }
        val roundedTypeface = runCatching { ResourcesCompat.getFont(context, baseFont) }.getOrNull()
            ?: Typeface.SANS_SERIF
        return Typeface.create(roundedTypeface, fontWeight.weight, false)
    }

    private fun WearDisplayFontWeight.strokeScale(): Float =
        (weight / 400f).coerceIn(0.68f, 1.45f)

    private fun formatDelta(delta: Double?): String {
        if (delta == null) return "--"
        val rounded = delta.coerceIn(-99.0, 99.0).roundToInt()
        return if (rounded > 0) "+$rounded" else rounded.toString()
    }
}

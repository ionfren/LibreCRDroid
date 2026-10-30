package re.abbot.librecr.app.idrive

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import re.abbot.librecr.app.data.GlucoseUnit
import re.abbot.librecr.app.data.SensorStateStore
import re.abbot.librecr.app.stats.GlucoseSample
import re.abbot.librecr.app.widget.GlucoseWidgetInput
import java.io.File
import java.time.Instant
import java.util.Locale
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IDriveGlucoseRendererTest {
    private val at = Instant.parse("2026-10-20T18:36:00Z").toEpochMilli()
    private val input = GlucoseWidgetInput(SensorStateStore.LastGlucose(123, 120, "STABLE", at, 0.0))
    private val history = (180 downTo 0).map { minute ->
        GlucoseSample((120 + 65 * sin(minute * Math.PI / 60)).toInt(), at - minute * 60_000L)
    }

    private fun decode(bytes: ByteArray): Bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    private fun green(pixel: Int) = Color.green(pixel) > 150 && Color.green(pixel) > Color.red(pixel) * 1.3

    @Test fun `split keeps the glucose size and the latest chart sample inside its card`() {
        val renderer = IDriveGlucoseRenderer(RuntimeEnvironment.getApplication())
        val content = idriveGlucosePresentation(input, GlucoseUnit.MG_DL, at, history = history)
        val full = decode(renderer.render(content, 1211, 480, 1211))
        val split = decode(renderer.render(content, 743, 480, 1211))
        val stale = decode(renderer.render(idriveGlucosePresentation(input, GlucoseUnit.MG_DL,
            at + 360_000, history = history), 743, 480, 1211))
        fun numberPixels(bitmap: Bitmap) = (40..250).sumOf { x ->
            (150..240).count { y -> green(bitmap.getPixel(x, y)) }
        }
        try {
            assertTrue(numberPixels(full) > 1000)
            assertEquals(numberPixels(full).toDouble(), numberPixels(split).toDouble(), numberPixels(full) * 0.02)
            assertEquals(0, numberPixels(stale))
            // The rightmost sample stays inside both viewports, with axis labels beyond it.
            for ((bitmap, right) in listOf(split to 667, full to 1135)) {
                assertTrue((right - 5..right + 5).sumOf { x ->
                    (325..338).count { y -> green(bitmap.getPixel(x, y)) }
                } > 10)
            }
        } finally { full.recycle(); split.recycle(); stale.recycle() }
    }

    @Test fun `missing intervals are not connected while valid five minute samples are`() {
        val renderer = IDriveGlucoseRenderer(RuntimeEnvironment.getApplication())
        val samples = listOf(GlucoseSample(120, at - 95 * 60_000), GlucoseSample(120, at - 85 * 60_000))
        fun render(data: List<GlucoseSample>) = decode(renderer.render(idriveGlucosePresentation(
            GlucoseWidgetInput(), GlucoseUnit.MG_DL, at, history = data), 743, 480, 1211))
        val gap = render(samples)
        val joined = render(samples + GlucoseSample(120, at - 90 * 60_000))
        try {
            assertFalse(green(gap.getPixel(553, 331)))
            assertTrue(green(joined.getPixel(553, 331)))
        } finally { gap.recycle(); joined.recycle() }
    }

    @Test fun `chart previews cover full split units locales and empty history`() {
        val app = RuntimeEnvironment.getApplication()
        val directory = File("build/reports/idrive-chart-preview").apply { mkdirs() }
        for (language in listOf("ro", "en", "fr")) {
            val context = app.createConfigurationContext(Configuration(app.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(language))
            })
            val renderer = IDriveGlucoseRenderer(context)
            for ((width, height, fullWidth) in listOf(Triple(743, 480, 1211), Triple(838, 540, 1345),
                Triple(1211, 480, 1211), Triple(1345, 540, 1345))) {
                for (unit in GlucoseUnit.entries) {
                    val data = renderer.render(idriveGlucosePresentation(input, unit, at,
                        history = history), width, height, fullWidth)
                    val bitmap = decode(data)
                    assertEquals(width, bitmap.width)
                    assertEquals(height, bitmap.height)
                    bitmap.recycle()
                    File(directory, "$language-$width-${unit.name}.jpg").writeBytes(data)
                }
            }
            File(directory, "$language-empty.jpg").writeBytes(renderer.render(
                idriveGlucosePresentation(GlucoseWidgetInput(), GlucoseUnit.MG_DL, at), 743, 480, 1211))
            File(directory, "$language-stale.jpg").writeBytes(renderer.render(
                idriveGlucosePresentation(input, GlucoseUnit.MG_DL, at + 360_000, history = history), 743, 480, 1211))
        }
    }
}

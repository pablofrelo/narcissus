package pl.yggdrasil.narcissus2.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import pl.yggdrasil.narcissus2.R
import pl.yggdrasil.narcissus2.domain.ActivityMode
import pl.yggdrasil.narcissus2.domain.Telemetry
import pl.yggdrasil.narcissus2.ui.theme.NostromoPalette

/**
 * Odczyty do powiadomienia (pasek i ekran blokady) jako obrazek: czerń,
 * fosfor, Spleen, scanlines — jak ekran licznika. Systemowe powiadomienie
 * umie tylko zwykły tekst.
 *
 * Te same metryki co na ekranie trybu, w tej samej kolejności.
 */
object NotificationCard {

    private const val WIDTH = 1080

    /** Zwinięte: jeden rząd, wszystkie metryki obok siebie. */
    fun compact(context: Context, mode: ActivityMode, t: Telemetry): Bitmap =
        render(context, mode, t, height = 150, valueSize = 72f, labelSize = 28f)

    /** Rozwinięte: to samo, większe cyfry. */
    fun expanded(context: Context, mode: ActivityMode, t: Telemetry): Bitmap =
        render(context, mode, t, height = 300, valueSize = 128f, labelSize = 36f)

    private fun render(
        context: Context, mode: ActivityMode, t: Telemetry,
        height: Int, valueSize: Float, labelSize: Float,
    ): Bitmap {
        val out = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = NostromoPalette
        c.drawColor(p.void.toArgb())

        val font = ResourcesCompat.getFont(context, R.font.spleen_16x32) ?: Typeface.MONOSPACE
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = font; textSize = labelSize; color = p.dim.toArgb(); textAlign = Paint.Align.CENTER
        }
        val value = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = font; textSize = valueSize; color = p.readout.toArgb(); textAlign = Paint.Align.CENTER
            setShadowLayer(10f, 0f, 0f, p.phosphor.copy(alpha = 0.45f).toArgb())
        }

        val metrics = mode.metrics
        val col = WIDTH.toFloat() / metrics.size
        val labelY = height * 0.08f - label.ascent()
        val valueY = labelY + label.descent() + (height - labelY) / 2f - (value.ascent() + value.descent()) / 2f

        metrics.forEachIndexed { i, m ->
            val x = col * i + col / 2f
            val unit = m.unit.text
            val head = if (unit.isEmpty()) m.label.text else "${m.label.text} $unit"
            c.drawText(head, x, labelY, label)
            // Pomniejsz wartość, gdyby nie zmieściła się w kolumnie.
            val text = m.read(t).trimStart()
            val w = value.measureText(text)
            if (w > col * 0.94f) {
                val keep = value.textSize
                value.textSize = keep * col * 0.94f / w
                c.drawText(text, x, valueY, value)
                value.textSize = keep
            } else {
                c.drawText(text, x, valueY, value)
            }
        }

        // Scanlines: linia na 3 piksele, czerń 28%.
        val line = Paint().apply { color = 0x47000000 }
        var y = 0f
        while (y < height) { c.drawRect(0f, y, WIDTH.toFloat(), y + 1f, line); y += 3f }

        return out
    }
}

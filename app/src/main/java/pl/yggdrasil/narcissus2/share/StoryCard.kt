package pl.yggdrasil.narcissus2.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import pl.yggdrasil.narcissus2.R
import pl.yggdrasil.narcissus2.domain.ActivityMode
import pl.yggdrasil.narcissus2.domain.Session
import pl.yggdrasil.narcissus2.domain.TrackPoint
import pl.yggdrasil.narcissus2.i18n.tr
import pl.yggdrasil.narcissus2.ui.theme.NostromoPalette
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Karta do relacji na FB/Instagramie: 1080×1920, pion 9:16.
 *
 * Zdjęcie z treningu idzie na tło i jest "zatapiane": przyciemnione
 * w całości, a od połowy w dół przechodzi w czerń. Na czerni stoją ślad
 * trasy i liczby — tym samym Spleenem i w tych samych kolorach co w
 * aplikacji, więc karta wygląda jak zrzut z terminala, a nie jak szablon
 * z innej aplikacji.
 *
 * Czysta grafika Androida (android.graphics), bez Compose — rysujemy do
 * bitmapy w tle, nie na ekran.
 */
object StoryCard {

    const val WIDTH = 1080
    const val HEIGHT = 1920

    /** Margines boczny. */
    private const val M = 64f

    /** Mapka trasy w prawym górnym rogu. */
    private const val MAP_W = 320f
    private const val MAP_H = 400f

    private val DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy  HH:mm")
        .withZone(ZoneId.systemDefault())

    fun render(
        context: Context,
        session: Session,
        track: List<TrackPoint>,
        photo: Bitmap?,
    ): Bitmap {
        val out = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)

        val small = ResourcesCompat.getFont(context, R.font.spleen_16x32) ?: Typeface.MONOSPACE
        val large = ResourcesCompat.getFont(context, R.font.spleen_32x64) ?: Typeface.MONOSPACE

        val phosphor = NostromoPalette.phosphor.toArgb()
        val readout = NostromoPalette.readout.toArgb()
        val dim = NostromoPalette.dim.toArgb()
        val alarm = NostromoPalette.alarm.toArgb()

        // --- tło: zdjęcie zatopione w czerni ---
        c.drawColor(0xFF000000.toInt())
        if (photo != null) {
            c.drawBitmap(photo, coverRect(photo), Rect(0, 0, WIDTH, HEIGHT), Paint(Paint.FILTER_BITMAP_FLAG))

            // Lekkie przyciemnienie całości — napisy u góry muszą być czytelne
            // na każdym zdjęciu, także na jasnym niebie.
            c.drawColor(0x59000000)

            // Od ~45% wysokości w dół zdjęcie gaśnie do czerni.
            val fade = Paint().apply {
                shader = LinearGradient(
                    0f, HEIGHT * 0.42f, 0f, HEIGHT * 0.72f,
                    0x00000000, 0xF2000000.toInt(),
                    Shader.TileMode.CLAMP,
                )
            }
            c.drawRect(0f, HEIGHT * 0.42f, WIDTH.toFloat(), HEIGHT.toFloat(), fade)
        }

        // --- mapka w prawym górnym rogu ---
        // Nagłówek po lewej ma ~610 px, więc 320 px z prawej jest wolne.
        if (track.size >= 2) {
            val map = RectF(WIDTH - M - MAP_W, M, WIDTH - M, M + MAP_H)
            val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f
                color = dim
            }
            c.drawRect(map, frame)
            map.inset(24f, 24f)
            drawTrack(c, track, map, phosphor, readout, alarm)
        }

        // --- nagłówek ---
        var y = 96f
        y = text(c, "NARCISSUS // ${session.mode.label.text}", M, y, small, 64f, phosphor)
        y = text(c, DATE.format(Instant.ofEpochMilli(session.startedAt)), M, y + 8f, small, 32f, dim)

        // --- dół: liczby, rysowane od dołu w górę ---
        val stats = stats(session)
        val cellW = (WIDTH - 2 * M - 48f) / 2
        var bottom = HEIGHT - 112f

        stats.chunked(2).reversed().forEach { row ->
            var rowTop = bottom
            row.forEachIndexed { i, (label, value, unit) ->
                val right = M + cellW * (i + 1) + 48f * i
                rowTop = min(rowTop, cell(c, label, value, unit, right, bottom, small, phosphor, readout, dim))
            }
            bottom = rowTop - 40f
        }

        // Wielki dystans nad komórkami.
        val km = fmt("%.2f", session.distanceM / 1000.0)
        bottom = bigReadout(c, tr("DISTANCE", "DYSTANS"), km, "KM", WIDTH - M, bottom, small, large, phosphor, readout, dim)

        return out
    }

    /** Zapis do cache i systemowe okno "Udostępnij" — z niego FB → Relacja. */
    fun share(context: Context, card: Bitmap) {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val file = File(dir, "narcissus-relacja.jpg")
        file.outputStream().use { card.compress(Bitmap.CompressFormat.JPEG, 92, it) }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.share", file)

        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            // Podgląd miniatury w oknie udostępniania.
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(
            Intent.createChooser(send, tr("STORY", "RELACJA")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /**
     * Wczytanie zdjęcia z galerii. ImageDecoder sam obraca według EXIF-a.
     * Zmniejszamy przy dekodowaniu — 50-megapikselowe zdjęcie w pełnej
     * rozdzielczości to kilkaset MB pamięci, a karta i tak ma 1080 px.
     */
    fun loadPhoto(context: Context, uri: Uri): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            var sample = 1
            while (w / (sample * 2) >= WIDTH && h / (sample * 2) >= HEIGHT) sample *= 2
            decoder.setTargetSampleSize(sample)
            // Bitmapy sprzętowej nie da się narysować na zwykłym Canvasie.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }

    // ------------------------------------------------------------------

    private data class Stat(val label: String, val value: String, val unit: String)

    private fun stats(s: Session): List<Stat> {
        val list = mutableListOf(Stat(tr("TIME", "CZAS"), clock(s.movingMs), ""))

        if (s.mode == ActivityMode.Bike) {
            list += Stat(tr("AVERAGE", "ŚREDNIA"), fmt("%.1f", s.avgMovingSpeedMps * 3.6f), "KM/H")
            list += Stat(tr("MAX", "MAKS"), fmt("%.1f", s.maxSpeedMps * 3.6f), "KM/H")
        } else {
            list += Stat(tr("AVG PACE", "TEMPO ŚR."), pace(s.avgMovingSpeedMps), "MIN/KM")
            if (s.totalSteps > 0) list += Stat(tr("STEPS", "KROKI"), s.totalSteps.toString(), "")
        }

        s.ascentM?.let { list += Stat(tr("ASCENT", "PRZEWYŻSZENIE"), fmt("%.0f", it), "M") }
        return list
    }

    /**
     * Komórka wyrównana do prawej krawędzi [right], stojąca na [bottom].
     * Zwraca górną krawędź, żeby następny rząd wiedział, gdzie się kończy.
     */
    private fun cell(
        c: Canvas, label: String, value: String, unit: String,
        right: Float, bottom: Float, small: Typeface,
        labelColor: Int, valueColor: Int, unitColor: Int,
    ): Float {
        var y = bottom
        if (unit.isNotEmpty()) y = textUp(c, unit, right, y, small, 32f, unitColor)
        y = textUp(c, value, right, y, small, 128f, valueColor)
        return textUp(c, label, right, y - 4f, small, 32f, labelColor)
    }

    private fun bigReadout(
        c: Canvas, label: String, value: String, unit: String,
        right: Float, bottom: Float, small: Typeface, large: Typeface,
        labelColor: Int, valueColor: Int, unitColor: Int,
    ): Float {
        var y = textUp(c, unit, right, bottom, small, 64f, unitColor)
        y = textUp(c, value, right, y, large, 256f, valueColor)
        return textUp(c, label, right, y - 4f, small, 64f, labelColor)
    }

    private fun drawTrack(
        c: Canvas, pts: List<TrackPoint>, box: RectF,
        line: Int, core: Int, end: Int,
    ) {
        var minLat = Double.MAX_VALUE; var maxLat = -Double.MAX_VALUE
        var minLon = Double.MAX_VALUE; var maxLon = -Double.MAX_VALUE
        pts.forEach {
            minLat = min(minLat, it.latitude); maxLat = max(maxLat, it.latitude)
            minLon = min(minLon, it.longitude); maxLon = max(maxLon, it.longitude)
        }

        // Ta sama korekta zbieżności południków co w TrackView.
        val lonScale = cos((minLat + maxLat) / 2 * PI / 180)
        val spanLat = (maxLat - minLat).coerceAtLeast(1e-7)
        val spanLon = (maxLon - minLon).coerceAtLeast(1e-7) * lonScale

        val scale = min(box.width() / spanLon, box.height() / spanLat)

        val w = (spanLon * scale).toFloat()
        val h = (spanLat * scale).toFloat()
        val ox = box.left + (box.width() - w) / 2
        val oy = box.top + (box.height() - h) / 2

        fun x(p: TrackPoint) = ox + ((p.longitude - minLon) * lonScale * scale).toFloat()
        fun y(p: TrackPoint) = oy + h - ((p.latitude - minLat) * scale).toFloat()

        val path = Path()
        pts.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p), y(p)) else path.lineTo(x(p), y(p)) }

        // Poświata pod linią, potem ostry rdzeń — jak fosfor na starym monitorze.
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 12f
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            color = line
            alpha = 110
            maskFilter = BlurMaskFilter(9f, BlurMaskFilter.Blur.NORMAL)
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            color = core
        }
        c.drawPath(path, glow)
        c.drawPath(path, stroke)

        val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        dot.color = line
        c.drawCircle(x(pts.first()), y(pts.first()), 9f, dot)
        dot.color = end
        c.drawCircle(x(pts.last()), y(pts.last()), 9f, dot)
    }

    /** Tekst od lewej, górna krawędź na [top]. Zwraca dolną krawędź. */
    private fun text(c: Canvas, s: String, x: Float, top: Float, tf: Typeface, size: Float, color: Int): Float {
        val p = paint(tf, size, color)
        val fm = p.fontMetrics
        c.drawText(s, x, top - fm.ascent, p)
        return top - fm.ascent + fm.descent
    }

    /** Tekst do prawej krawędzi [right], dolna krawędź na [bottom]. Zwraca górną krawędź. */
    private fun textUp(c: Canvas, s: String, right: Float, bottom: Float, tf: Typeface, size: Float, color: Int): Float {
        val p = paint(tf, size, color).apply { textAlign = Paint.Align.RIGHT }
        val fm = p.fontMetrics
        c.drawText(s, right, bottom - fm.descent, p)
        return bottom - fm.descent + fm.ascent
    }

    /**
     * Bez antyaliasingu: Spleen jest bitmapowy i przy rozmiarach będących
     * wielokrotnością komórki piksele mają trafiać w piksele, nie się rozmywać.
     */
    private fun paint(tf: Typeface, size: Float, color: Int) = Paint().apply {
        typeface = tf
        textSize = size
        this.color = color
        isAntiAlias = false
    }

    /** Środkowy wycinek zdjęcia o proporcjach 9:16 — jak "cover" w CSS. */
    private fun coverRect(b: Bitmap): Rect {
        val target = WIDTH.toFloat() / HEIGHT
        val src = b.width.toFloat() / b.height
        return if (src > target) {
            val w = (b.height * target).toInt()
            val x = (b.width - w) / 2
            Rect(x, 0, x + w, b.height)
        } else {
            val h = (b.width / target).toInt()
            val y = (b.height - h) / 2
            Rect(0, y, b.width, y + h)
        }
    }

    private fun pace(speedMps: Float): String {
        if (speedMps <= 1000f / (30 * 60)) return "--:--"
        val t = (1000f / speedMps).toInt()
        return fmt("%d:%02d", t / 60, t % 60)
    }

    private fun clock(ms: Long): String {
        val s = ms / 1000
        return fmt("%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
    }

    private fun fmt(f: String, vararg a: Any?) = String.format(Locale.US, f, *a)
}

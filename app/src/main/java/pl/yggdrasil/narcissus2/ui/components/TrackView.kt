package pl.yggdrasil.narcissus2.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import pl.yggdrasil.narcissus2.domain.TrackPoint
import pl.yggdrasil.narcissus2.i18n.tr
import pl.yggdrasil.narcissus2.ui.theme.Theme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Ślad przejazdu rysowany wprost na płótnie.
 *
 * Bez kafelków mapowych i bez sieci — sam kształt trasy na tle fosforu.
 * To nie jest ograniczenie, tylko wybór: mapa w tej estetyce wyglądałaby
 * jak wklejka z innej aplikacji, a przy przeglądaniu własnych przejazdów
 * kształt i tak rozpoznajesz od razu.
 *
 * Stopnie długości geograficznej są krótsze od stopni szerokości i to
 * skrócenie rośnie z szerokością. Bez korekty przez cosinus trasa w Polsce
 * wyszłaby rozciągnięta w poziomie o jakieś 40 procent.
 */
@Composable
fun TrackView(
    points: List<TrackPoint>,
    modifier: Modifier = Modifier,
    heightDp: Int = 220,
) {
    val p = Theme.palette

    Box(
        modifier
            .fillMaxWidth()
            .height(heightDp.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (points.size < 2) {
            Label(tr("NO TRACK", "BRAK ŚLADU"), color = p.dim, softWrap = false)
            return@Box
        }

        Canvas(Modifier.fillMaxWidth().height(heightDp.dp)) {
            var minLat = Double.MAX_VALUE
            var maxLat = -Double.MAX_VALUE
            var minLon = Double.MAX_VALUE
            var maxLon = -Double.MAX_VALUE

            points.forEach {
                minLat = min(minLat, it.latitude)
                maxLat = max(maxLat, it.latitude)
                minLon = min(minLon, it.longitude)
                maxLon = max(maxLon, it.longitude)
            }

            // Korekta zbieżności południków dla środkowej szerokości trasy.
            val midLat = (minLat + maxLat) / 2
            val lonScale = cos(midLat * PI / 180)

            val spanLat = (maxLat - minLat).coerceAtLeast(1e-7)
            val spanLon = (maxLon - minLon).coerceAtLeast(1e-7) * lonScale

            val pad = 16f
            val w = size.width - pad * 2
            val h = size.height - pad * 2

            // Jedna skala na obie osie — trasa ma zachować proporcje,
            // inaczej pętla wokół jeziora wygląda jak elipsa.
            val scale = min(w / spanLon, h / spanLat)

            val drawnW = (spanLon * scale).toFloat()
            val drawnH = (spanLat * scale).toFloat()
            val offX = pad + (w - drawnW) / 2
            val offY = pad + (h - drawnH) / 2

            fun project(pt: TrackPoint): Offset {
                val x = ((pt.longitude - minLon) * lonScale * scale).toFloat()
                // Ekran rośnie w dół, szerokość geograficzna w górę.
                val y = drawnH - ((pt.latitude - minLat) * scale).toFloat()
                return Offset(offX + x, offY + y)
            }

            val path = Path()
            points.forEachIndexed { i, pt ->
                val o = project(pt)
                if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
            }

            drawPath(
                path = path,
                color = p.readout,
                style = Stroke(width = 3f, join = StrokeJoin.Round),
            )

            // Start i meta. Bez nich pętla jest nieczytelna — nie wiadomo,
            // w którą stronę szedł przejazd.
            drawCircle(color = p.phosphor, radius = 7f, center = project(points.first()))
            drawCircle(color = p.alarm, radius = 7f, center = project(points.last()))
        }
    }
}

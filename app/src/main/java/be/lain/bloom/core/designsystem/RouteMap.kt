package be.lain.bloom.core.designsystem

import android.annotation.SuppressLint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** A contiguous piece of route recorded during one kind of segment ("RUN", "WALK", … or null). */
data class RouteLine(val kind: String?, val points: List<LatLng>)

private const val LIGHT_STYLE = "https://tiles.openfreemap.org/styles/liberty"
private const val DARK_STYLE = "https://tiles.openfreemap.org/styles/dark"

/**
 * Route map using MapLibre and OpenFreeMap vector tiles (no API key).
 * Run segments are solid primary lines, walks dashed periwinkle ones. With [follow], the camera tracks the runner.
 */
@SuppressLint("MissingPermission")
@Composable
fun RouteMap(lines: List<RouteLine>, modifier: Modifier = Modifier, follow: LatLng? = null) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.4f
    val runColor = MaterialTheme.colorScheme.primary
    val walkColor = routeWalkColor(runColor, dark)
    val meColor = MaterialTheme.colorScheme.primary
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var fitted by remember { mutableStateOf(false) }

    val mapView = remember {
        MapView(context).apply {
            onCreate(null)
            getMapAsync { m ->
                m.uiSettings.isRotateGesturesEnabled = false
                m.uiSettings.isTiltGesturesEnabled = false
                map = m
            }
        }
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onPause(); mapView.onStop(); mapView.onDestroy()
        }
    }

    LaunchedEffect(map, dark) {
        val m = map ?: return@LaunchedEffect
        m.setStyle(Style.Builder().fromUri(if (dark) DARK_STYLE else LIGHT_STYLE)) { s ->
            s.addSource(GeoJsonSource("route"))
            s.addSource(GeoJsonSource("me"))
            // Walks: thinner, dashed and a cool colour; runs: solid primary on top. Two cues, not just hue.
            s.addLayer(
                LineLayer("route-walk", "route").withProperties(
                    PropertyFactory.lineWidth(4f),
                    PropertyFactory.lineCap(Property.LINE_CAP_BUTT),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    PropertyFactory.lineDasharray(arrayOf(1.6f, 1.2f)),
                    PropertyFactory.lineColor(walkColor.toArgb()),
                ).withFilter(Expression.neq(Expression.get("kind"), Expression.literal("RUN"))),
            )
            s.addLayer(
                LineLayer("route-run", "route").withProperties(
                    PropertyFactory.lineWidth(6f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    PropertyFactory.lineColor(runColor.toArgb()),
                ).withFilter(Expression.eq(Expression.get("kind"), Expression.literal("RUN"))),
            )
            s.addLayer(
                CircleLayer("me-dot", "me").withProperties(
                    PropertyFactory.circleRadius(8f),
                    PropertyFactory.circleColor(meColor.toArgb()),
                    PropertyFactory.circleStrokeColor(Color.White.toArgb()),
                    PropertyFactory.circleStrokeWidth(3f),
                ),
            )
            fitted = false
            style = s
        }
    }

    LaunchedEffect(style, lines, follow) {
        val s = style ?: return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        (s.getSource("route") as? GeoJsonSource)?.setGeoJson(
            FeatureCollection.fromFeatures(
                lines.filter { it.points.size >= 2 }.map { l ->
                    Feature.fromGeometry(LineString.fromLngLats(l.points.map { Point.fromLngLat(it.longitude, it.latitude) }))
                        .also { f -> f.addStringProperty("kind", if (l.kind == null || l.kind == "RUN") "RUN" else "WALK") }
                },
            ),
        )
        (s.getSource("me") as? GeoJsonSource)?.setGeoJson(
            FeatureCollection.fromFeatures(follow?.let { listOf(Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude))) } ?: emptyList()),
        )
        val all = lines.flatMap { it.points }
        when {
            follow != null -> m.animateCamera(CameraUpdateFactory.newLatLngZoom(follow, 16.0), 600)
            !fitted && all.size >= 2 -> {
                val bounds = LatLngBounds.Builder().includes(all).build()
                m.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 80))
                fitted = true
            }
            !fitted && all.size == 1 -> { m.moveCamera(CameraUpdateFactory.newLatLngZoom(all.first(), 15.0)); fitted = true }
        }
    }

    AndroidView({ mapView }, modifier.then(Modifier.fillMaxSize()))
}

/**
 * Walk colour on the map, picked far from the run colour (the theme primary) so the two are easy to tell
 * apart: periwinkle next to Bloom's pink, amber when wallpaper colours make the primary bluish.
 */
fun routeWalkColor(primary: Color, dark: Boolean): Color {
    val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(primary.toArgb(), it) }
    val bluish = hsv[1] > 0.12f && hsv[0] in 170f..290f
    return when {
        bluish && dark -> Color(0xFFFFB74D)
        bluish -> Color(0xFFB45F00)
        dark -> Color(0xFF9DB4FF)
        else -> Color(0xFF3F5BD8)
    }
}

/** "— Run  - - Walk" key under a route map; shown only when the route has both kinds. */
@Composable
fun RouteLegend(lines: List<RouteLine>, modifier: Modifier = Modifier) {
    val kinds = lines.filter { it.points.size >= 2 }.map { it.kind == null || it.kind == "RUN" }.toSet()
    if (kinds.size < 2) return
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.4f
    val run = MaterialTheme.colorScheme.primary
    val walk = routeWalkColor(run, dark)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Canvas(Modifier.size(width = 22.dp, height = 8.dp)) {
                drawLine(run, Offset(3.dp.toPx(), size.height / 2), Offset(size.width - 3.dp.toPx(), size.height / 2), 6.dp.toPx(), StrokeCap.Round)
            }
            Text("Run", style = MaterialTheme.typography.labelLarge)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Canvas(Modifier.size(width = 22.dp, height = 8.dp)) {
                drawLine(walk, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 4.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())))
            }
            Text("Walk", style = MaterialTheme.typography.labelLarge)
        }
    }
}

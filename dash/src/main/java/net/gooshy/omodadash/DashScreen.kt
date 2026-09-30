package net.gooshy.omodadash

import android.content.SharedPreferences
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * The "Omoda Dash" design (Claude Design project e783a14e…, `Omoda Dash.dc.html`)
 * rebuilt in Compose. Everything is laid out in the design's own 1920 × 720
 * pixel grid: the root overrides the density so that 1.dp = 1 design pixel,
 * scaled to whatever the screen really is (on the car, 1920 × 720 at 160 dpi,
 * so the scale is exactly 1).
 */

private const val W = 1920
private const val H = 720

private val NoPad = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))

@Composable
fun DashRoot(feed: LiveFeed) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("dash", 0) }
    var night by remember { mutableStateOf(prefs.getBoolean("night", true)) }
    val p = if (night) Palette.NIGHT else Palette.DAY

    val engine = remember { DashEngine() }
    val live = remember { LiveInput() }
    val demo = remember { DemoInput() }
    // DEMO plays the design's scripted loop. It switches on by itself when the
    // car bus isn't there at all (an emulator, say).
    val demoMode = feed.state == LiveFeed.State.UNAVAILABLE
    val demoNow by rememberUpdatedState(demoMode)
    var bestsAtStart by remember { mutableStateOf(BestsStore.load(prefs)) }
    var sessionStartMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }

    LaunchedEffect(demoMode) {
        engine.resetSession()
        bestsAtStart = BestsStore.load(prefs)
        sessionStartMs = SystemClock.elapsedRealtime()
    }

    // One clock drives everything: canvases redraw every frame; text is
    // recomposed at 20 Hz, which is as fast as any number can be read.
    val frame = remember { mutableLongStateOf(0L) }
    val textFrame = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        var last = 0L
        var lastText = 0L
        var lastSave = 0L
        while (true) {
            withFrameNanos { n ->
                val dt = if (last == 0L) 0.016 else ((n - last) / 1e9).coerceIn(0.0, 0.1)
                last = n
                val now = SystemClock.elapsedRealtime()
                engine.tick(if (demoNow) demo else live, now, dt)
                frame.longValue = n
                if (n - lastText > 50_000_000) { textFrame.longValue = n; lastText = n }
                if (!demoNow && now - lastSave > 2_000) { BestsStore.merge(prefs, engine); lastSave = now }
            }
        }
    }
    val tf: LongState = textFrame
    val bests = if (demoMode) DemoInput.BESTS else bestsAtStart

    BoxWithConstraints(Modifier.fillMaxSize().background(p.bg), contentAlignment = Alignment.Center) {
        val scale = min(constraints.maxWidth / W.toFloat(), constraints.maxHeight / H.toFloat())
        CompositionLocalProvider(LocalDensity provides Density(scale, 1f)) {
            Box(Modifier.size(W.dp, H.dp).background(p.bg)) {
                Screen(p, engine, frame, tf, feed, demoMode, sessionStartMs, bests,
                    onToggleTheme = { night = !night; prefs.edit().putBoolean("night", night).apply() })
            }
        }
    }
}

@Composable
private fun Screen(
    p: Palette, e: DashEngine, frame: LongState, tf: LongState, feed: LiveFeed, demo: Boolean, sessionStartMs: Long,
    bests: Bests, onToggleTheme: () -> Unit,
) {
    tf.longValue // recompose with the 20 Hz text clock
    val pager = rememberPagerState { 3 }
    val scope = rememberCoroutineScope()
    val stale = e.r.stale && (!demo || e.demoStarted)

    Canvas(Modifier.offset(24.dp, 44.dp).size(1872.dp, 32.dp)) { frame.longValue; drawShift(p, e) }

    Header(p, tf, feed, demo, stale, sessionStartMs, pager.currentPage, onTab = { scope.launch { pager.animateScrollToPage(it) } },
        onToggleTheme)

    HorizontalPager(
        pager,
        Modifier.offset(0.dp, 148.dp).size(W.dp, 480.dp).alpha(if (stale) 0.45f else 1f),
    ) { page ->
        Box(Modifier.size(W.dp, 480.dp)) {
            when (page) {
                0 -> RacePage(p, e, frame, tf, bests, stale)
                1 -> EnergyPage(p, e, tf)
                else -> CarPage(p, e, tf, bests)
            }
        }
    }

    Lamps(p, e, tf, demo, stale, feed.regen)

    if (p.night) Box(Modifier.size(W.dp, H.dp).scanlines())
    Canvas(Modifier.size(W.dp, H.dp)) {
        frame.longValue
        val a = 1f - (SystemClock.elapsedRealtime() - e.hardBrakeAt) / 350f
        if (e.hardBrakeAt > 0 && a > 0f) {
            drawRect(p.red.copy(alpha = 0.18f * a))
            drawRect(p.red.copy(alpha = a), topLeft = Offset(6f, 6f), size = Size(size.width - 12f, size.height - 12f), style = Stroke(12f))
        }
    }
}

// ---------------------------------------------------------------- header

@Composable
private fun Header(
    p: Palette, tf: LongState, feed: LiveFeed, demo: Boolean, stale: Boolean, sessionStartMs: Long, page: Int,
    onTab: (Int) -> Unit, onToggleTheme: () -> Unit,
) {
    tf.longValue // recompose with the 20 Hz text clock
    Row(
        Modifier.offset(24.dp, 88.dp).size(1872.dp, 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(Modifier.fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("RACE", "ENERGY", "CAR").forEachIndexed { i, label ->
                val on = i == page
                Box(
                    Modifier.fillMaxHeight().tap { onTab(i) }
                        .drawBehind { if (on) drawRect(p.red, Offset(0f, size.height - 3f), Size(size.width, 3f)) }
                        .padding(horizontal = 22.dp),
                    contentAlignment = Alignment.Center,
                ) { T(label, 28, if (on) p.ink else p.mut, ls = .12f) }
            }
        }
        T("›››", 28, p.dim, ls = -.12f)
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val live = !demo && !stale && feed.state == LiveFeed.State.CONNECTED
            Box(Modifier.size(14.dp).drawBehind {
                when {
                    live -> glowCircle(p.grn, center, size.minDimension / 2, p.grn, 12f)
                    demo -> drawCircle(p.blu)
                    else -> drawCircle(p.amb)
                }
            })
            // The session (since the app opened, or RESET) is what "THIS DRIVE" and the peaks cover.
            val s = ((SystemClock.elapsedRealtime() - sessionStartMs) / 1000).coerceAtLeast(0)
            val label = when {
                demo -> "DEMO"
                feed.state == LiveFeed.State.CONNECTING -> "CONNECTING"
                stale -> "NO DATA"
                else -> "LIVE %02d:%02d".format(s / 60, s % 60)
            }
            T(label, 28, p.ink, Modifier.widthIn(min = 190.dp), ls = .06f)
        }
        val c = Calendar.getInstance()
        // Tap the clock for the day / night theme.
        T("%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)), 28, p.ink, Modifier.tap(onToggleTheme), ls = .06f)
    }
}

// ---------------------------------------------------------------- RACE

@Composable
private fun RacePage(p: Palette, e: DashEngine, frame: LongState, tf: LongState, bests: Bests, stale: Boolean) {
    tf.longValue // recompose with the 20 Hz text clock
    val r = e.r
    // G-force panel.
    Box(Modifier.offset(24.dp, 0.dp).size(480.dp, 480.dp).clip(cutCorner(24f)).carbon(p)) {
        AccentBar(p.red)
        Row(Modifier.offset(24.dp, 20.dp).width(432.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                T("LAT", 28, p.mut, Modifier.alignByBaseline(), ls = .08f)
                T("%.2f".format(abs(e.latG)), 40, p.ink, Modifier.alignByBaseline())
                T(if (e.latG < -.03) "L" else if (e.latG > .03) "R" else "", 28, p.mut, Modifier.alignByBaseline().width(18.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                T("LON", 28, p.mut, Modifier.alignByBaseline(), ls = .08f)
                T(signed(e.longG, 2), 40, p.ink, Modifier.alignByBaseline())
            }
        }
        Box(Modifier.offset(40.dp, 72.dp).size(400.dp, 400.dp)) {
            Canvas(Modifier.fillMaxSize()) { frame.longValue; drawG(p, e, bests) }
            T("ACC", 28, p.mut, Modifier.offset(214.dp, 38.dp))
            T("BRK", 28, p.mut, Modifier.offset(214.dp, 310.dp))
            T("L", 28, p.mut, Modifier.offset(38.dp, 164.dp))
            T("R", 28, p.mut, Modifier.offset(346.dp, 164.dp))
        }
    }
    Chevrons(p, Modifier.offset(516.dp, 20.dp), mirror = false)

    // Centre: 0–60, speed, power.
    Box(Modifier.offset(552.dp, 0.dp).size(816.dp, 480.dp)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Row(
                Modifier.clip(cutCorner(14f)).carbon(p, both = false).padding(start = 18.dp, end = 26.dp, top = 10.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                T("0–60", 28, p.mut, Modifier.alignByBaseline(), ls = .06f)
                val done = e.launchDone
                val v = done ?: e.launchEl
                val best = bests.t060
                val col = when {
                    v == null || done == null -> p.ink
                    best == null || v < best -> p.pur
                    v <= (e.sT060 ?: 99.0) + 1e-6 -> p.grn
                    else -> p.yel
                }
                T(v?.let { "%.2f".format(it) } ?: "—.——", 32, col, Modifier.alignByBaseline().width(84.dp))
                T(if (done != null && best != null) signed(done - best, 2) else "", 32, col, Modifier.alignByBaseline().width(96.dp))
            }
        }
        val blink = SystemClock.elapsedRealtime() % 700 < 380
        Indicator(p, Modifier.offset(8.dp, 150.dp), left = true, on = r.indL && blink && !stale)
        Indicator(p, Modifier.offset(760.dp, 150.dp), left = false, on = r.indR && blink && !stale)
        Row(
            Modifier.offset(60.dp, 70.dp).size(696.dp, 270.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.Bottom,
        ) {
            Num((abs(e.speed) * DashEngine.KMH_TO_MPH).roundToInt().toString(), 340, FontWeight.Bold, p.ink,
                Modifier.width(470.dp), TextAlign.End)
            T("MPH", 28, p.mut, Modifier.padding(bottom = 12.dp), ls = .1f)
        }
        Row(Modifier.offset(0.dp, 360.dp).width(816.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            T("◂ REGEN −${DashEngine.REGEN_MAX_KW.toInt()}", 28, p.grn, Modifier.alignByBaseline(), ls = .06f)
            Row(Modifier.alignByBaseline(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                T(r.powerKw?.let { signed(e.power, 0) + " kW" } ?: "— kW", 36, p.ink, Modifier.alignByBaseline())
                T(r.flow.name, 28, when (r.flow) { Flow.REGEN -> p.grn; Flow.DRIVE -> p.amb; Flow.PARKED -> p.mut },
                    Modifier.alignByBaseline(), ls = .08f)
            }
            T("+${DashEngine.DRIVE_MAX_KW.toInt()} DRIVE ▸", 28, p.amb, Modifier.alignByBaseline(), ls = .06f)
        }
        Canvas(Modifier.offset(0.dp, 404.dp).size(816.dp, 72.dp)) { frame.longValue; drawPower(p, e) }
    }
    Chevrons(p, Modifier.offset(1380.dp, 20.dp), mirror = true)

    // Gear and mode.
    Box(Modifier.offset(1416.dp, 0.dp).size(480.dp, 200.dp).clip(cutCorner(24f)).carbon(p)) {
        AccentBar(p.red)
        T("GEAR", 28, p.mut, Modifier.offset(24.dp, 20.dp), ls = .08f)
        Num(r.gear, 180, FontWeight.ExtraBold, p.ink, Modifier.offset(36.dp, 62.dp))
        Column(Modifier.offset(190.dp, 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            T("MODE", 28, p.mut, ls = .08f)
            Num(r.mode, 56, FontWeight.Bold, p.ink, lineFactor = .9f)
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Chip(p, r.ev, if (r.ev == "HEV") p.amb else if (r.ev == "EV") p.grn else p.dim)
                Chip(p, "AUTO HOLD", if (r.autoHold) p.grn else p.dim)
            }
        }
    }
    // Power as a share of full scale: the nearest thing to a pedal reading
    // (the accelerator isn't on the bus). Regen as a share of its maximum.
    // The brake has its lamp in the bottom row.
    Box(Modifier.offset(1416.dp, 216.dp).size(480.dp, 264.dp).carbon(p)) {
        val pw = if (r.powerKw != null) e.power else null
        val pct = pw?.let { if (it >= 0) it / DashEngine.DRIVE_MAX_KW else it / DashEngine.REGEN_MAX_KW }?.coerceIn(-1.0, 1.0)
        Row(Modifier.offset(24.dp, 20.dp).width(432.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            T(if (pw != null && pw < 0) "REGEN" else "POWER", 28, p.mut, Modifier.alignByBaseline(), ls = .08f)
            T(pct?.let { "%.0f%%".format(abs(it) * 100) } ?: "—", 36, if (pw != null && pw < 0) p.grn else p.ink, Modifier.alignByBaseline())
        }
        Box(Modifier.offset(24.dp, 76.dp).size(432.dp, 164.dp).background(p.off)) {
            Canvas(Modifier.fillMaxSize()) {
                frame.longValue
                val f = e.r.powerKw?.let { if (e.power >= 0) e.power / DashEngine.DRIVE_MAX_KW else -e.power / DashEngine.REGEN_MAX_KW }
                    ?.coerceIn(0.0, 1.0)?.toFloat() ?: 0f
                val col = if (e.power < 0) p.grn else if (f > 0.6f) p.red else p.amb
                if (f > 0.005f) glowRect(col, 0f, 0f, size.width * f, size.height, 18f)
            }
        }
    }
}

@Composable
private fun Indicator(p: Palette, modifier: Modifier, left: Boolean, on: Boolean) {
    val shape = GenericShape { s, _ ->
        val (w, h) = s.width to s.height
        if (left) {
            moveTo(.55f * w, 0f); lineTo(w, 0f); lineTo(.45f * w, .5f * h); lineTo(w, h); lineTo(.55f * w, h); lineTo(0f, .5f * h)
        } else {
            moveTo(0f, 0f); lineTo(.45f * w, 0f); lineTo(w, .5f * h); lineTo(.45f * w, h); lineTo(0f, h); lineTo(.55f * w, .5f * h)
        }
        close()
    }
    Box(modifier.size(48.dp, 84.dp).clip(shape).background(if (on) p.amb else p.dim))
}

@Composable
private fun Chip(p: Palette, label: String, c: Color) {
    Box(Modifier.height(44.dp).border(2.dp, c).padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
        T(label, 28, c, ls = .04f)
    }
}

@Composable
private fun Chevrons(p: Palette, modifier: Modifier, mirror: Boolean) {
    Canvas(modifier.size(24.dp, 440.dp).graphicsLayer { if (mirror) scaleX = -1f }) {
        var y = 0f
        while (y < size.height) {
            val path = Path().apply { moveTo(7f, y + 7f); lineTo(16f, y + 15f); lineTo(7f, y + 23f) }
            drawPath(path, p.dim, style = Stroke(3f))
            y += 30f
        }
    }
}

// ---------------------------------------------------------------- ENERGY

@Composable
private fun EnergyPage(p: Palette, e: DashEngine, tf: LongState) {
    tf.longValue // recompose with the 20 Hz text clock
    val r = e.r
    Box(Modifier.offset(24.dp, 0.dp).size(1080.dp, 480.dp).clip(cutCorner(24f)).carbon(p)) {
        AccentBar(p.grn)
        T("BATTERY SOC", 28, p.mut, Modifier.offset(24.dp, 20.dp), ls = .08f)
        val sc = floor(e.soc * 100).toInt()
        Row(Modifier.offset(36.dp, 66.dp).height(220.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Num(if (r.soc == null) "—" else (sc / 100).toString(), 280, FontWeight.Bold, p.ink)
            Num(if (r.soc == null) "" else ".%02d".format(sc % 100), 88, FontWeight.SemiBold, p.mut, Modifier.padding(bottom = 6.dp))
            T("%", 44, p.mut, Modifier.padding(start = 6.dp, bottom = 6.dp))
        }
        Column(Modifier.offset(0.dp, 20.dp).width(1032.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            T("EV RANGE", 28, p.mut, ls = .08f)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
                Num(r.rangeKm?.roundToInt()?.toString() ?: "—", 170, FontWeight.Bold, p.ink)
                T("km", 36, p.mut)
            }
        }
        Canvas(Modifier.offset(24.dp, 306.dp).size(1032.dp, 150.dp)) { drawBatt(p, e.soc) }
    }

    Box(Modifier.offset(1128.dp, 0.dp).size(768.dp, 220.dp).clip(cutCorner(24f)).carbon(p)) {
        AccentBar(p.amb)
        Row(Modifier.offset(24.dp, 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            T("CONSUMPTION", 28, p.mut, Modifier.alignByBaseline(), ls = .08f)
            T("kWh/100 km", 28, p.dim, Modifier.alignByBaseline())
        }
        Row(Modifier.offset(24.dp, 70.dp).width(720.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            // The car reports a recent average, not an instantaneous figure; live
            // consumption needs motor power, which hasn't been found yet.
            LabelledNum(p, "RECENT", r.recentKwh?.let { "%.1f".format(it) } ?: "—", Modifier.width(200.dp))
            LabelledNum(p, "TRIP AVG", r.tripAvgKwh?.let { "%.1f".format(it) } ?: "—", Modifier.width(200.dp))
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                T("Δ VS AVG", 28, p.mut)
                val rc = r.recentKwh; val av = r.tripAvgKwh
                if (rc != null && av != null) {
                    val dl = (rc - av).coerceIn(-99.9, 99.9)
                    Num(signed(dl, 1), 120, FontWeight.ExtraBold, if (dl <= 0) p.grn else p.red)
                } else Num("—", 120, FontWeight.ExtraBold, p.mut)
            }
        }
    }

    Box(Modifier.offset(1128.dp, 236.dp).size(768.dp, 112.dp).carbon(p)) {
        Row(Modifier.offset(24.dp, 18.dp).width(720.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                T("REGEN", 28, p.grn, Modifier.alignByBaseline()); T(mmss(e.regenS), 32, p.ink, Modifier.alignByBaseline())
            }
            T("%.1f%% REGEN".format(e.regenPct()), 28, p.mut, Modifier.alignByBaseline())
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                T(mmss(e.driveS), 32, p.ink, Modifier.alignByBaseline()); T("DRIVE", 28, p.amb, Modifier.alignByBaseline())
            }
        }
        Canvas(Modifier.offset(24.dp, 66.dp).size(720.dp, 24.dp)) {
            drawRect(p.amb.copy(alpha = .9f))
            val w = size.width * (e.regenPct() / 100).toFloat()
            drawRect(p.bg, size = Size(min(size.width, w + 4f), size.height))
            drawRect(p.grn, size = Size(w, size.height))
        }
    }

    Row(
        Modifier.offset(1128.dp, 364.dp).size(768.dp, 116.dp).carbon(p).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        val evc = if (r.ev == "HEV") p.amb else if (r.ev == "EV") p.grn else p.mut
        Box(Modifier.border(2.dp, evc).padding(horizontal = 18.dp, vertical = 6.dp)) {
            Num(if (r.ev == "—") "—" else r.ev + " MODE", 56, FontWeight.Bold, evc, lineFactor = 1f)
        }
        Stat(p, "ENGINE", if (r.engineOn) "ON" else "OFF", if (r.engineOn) p.grn else p.amb)
        Stat(p, "RPM", r.engineRpm?.takeIf { r.engineOn && it > 0 }?.let { "%.0f".format(it) } ?: "—", if (r.engineOn) p.ink else p.mut)
        Stat(p, "TORQUE", if (r.engineOn && r.engineTorqueNm != null) "%.0f Nm".format(r.engineTorqueNm) else "— Nm",
            if (r.engineOn) p.ink else p.mut)
        Stat(p, "POWER", r.powerKw?.let { "%.0f kW".format(e.power) } ?: "— kW", if (r.powerKw != null) p.ink else p.mut)
    }
}

@Composable
private fun LabelledNum(p: Palette, label: String, value: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        T(label, 28, p.mut)
        Num(value, 96, FontWeight.Bold, p.ink)
    }
}

@Composable
private fun Stat(p: Palette, label: String, value: String, c: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        T(label, 28, p.mut)
        T(value, 36, c)
    }
}

// ---------------------------------------------------------------- CAR

@Composable
private fun CarPage(p: Palette, e: DashEngine, tf: LongState, bests: Bests) {
    tf.longValue // recompose with the 20 Hz text clock
    val r = e.r
    // Tyres. There's no placard figure on the bus, so a tyre is flagged when it
    // sits 2.5 psi or more below the highest of the four.
    Box(Modifier.offset(24.dp, 0.dp).size(600.dp, 480.dp).clip(cutCorner(24f)).carbon(p)) {
        AccentBar(p.red)
        T("TYRES psi", 28, p.mut, Modifier.offset(24.dp, 20.dp), ls = .08f)
        val known = r.tyres.filterNotNull()
        val top = known.maxOrNull()
        val spread = if (known.size == 4) known.max() - known.min() else null
        T(spread?.let { "SPREAD %.1f".format(it) } ?: "SPREAD —", 28, p.dim, Modifier.offset(0.dp, 20.dp).width(560.dp), align = TextAlign.End)
        val colours = r.tyres.map { v -> if (v != null && top != null && top - v >= 2.5) p.amb else if (v == null) p.dim else p.grn }
        Canvas(Modifier.offset(210.dp, 78.dp).size(180.dp, 380.dp)) { drawCar(p, colours) }
        val names = listOf("FL", "FR", "RL", "RR")
        listOf(Triple(0, 32, 136), Triple(1, 40, 136), Triple(2, 32, 332), Triple(3, 40, 332)).forEach { (i, x, y) ->
            val right = i % 2 == 1
            val v = r.tyres[i]
            val low = v != null && top != null && top - v >= 2.5
            Column(
                Modifier.offset(if (right) 0.dp else x.dp, y.dp).then(if (right) Modifier.width((600 - x).dp) else Modifier),
                horizontalAlignment = if (right) Alignment.End else Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                T(if (low) "${names[i]} LOW ${signed(v!! - top!!, 1)}" else names[i], 28, if (low) p.amb else p.mut)
                Num(v?.let { "%.1f".format(it) } ?: "—", 72, FontWeight.Bold, colours[i])
            }
        }
    }

    // Steering.
    Box(Modifier.offset(648.dp, 0.dp).size(456.dp, 480.dp).carbon(p)) {
        T("STEER", 28, p.mut, Modifier.offset(24.dp, 20.dp), ls = .08f)
        Box(Modifier.offset(218.dp, 30.dp).size(20.dp, 8.dp).background(p.dim))
        Canvas(Modifier.offset(118.dp, 44.dp).size(220.dp, 220.dp)) { drawWheel(p, e.steer.toFloat()) }
        Row(Modifier.offset(24.dp, 282.dp).width(408.dp), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)) {
            val st = e.steer.roundToInt()
            Num((if (st < 0) "−" else if (st > 0) "+" else "") + abs(st) + "°", 80, FontWeight.Bold, p.ink, Modifier.alignByBaseline())
            T(if (st < -2) "LEFT" else if (st > 2) "RIGHT" else "CENTRE", 28, p.mut, Modifier.alignByBaseline().width(100.dp))
        }
        Row(Modifier.offset(24.dp, 376.dp).width(408.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { T("YAW °/s", 28, p.mut); T(signed(e.yaw, 1), 44, p.ink) }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                T("OUTSIDE", 28, p.mut); T(r.tempC?.let { "%.0f°C".format(it) } ?: "—", 44, p.ink)
            }
        }
    }

    // Session bests.
    Box(Modifier.offset(1128.dp, 0.dp).size(768.dp, 344.dp).clip(cutCorner(24f)).carbon(p)) {
        AccentBar(p.pur)
        Column(Modifier.offset(24.dp, 18.dp).width(720.dp)) {
            Row(Modifier.height(44.dp)) {
                T("SESSION BESTS", 28, p.mut, Modifier.width(250.dp), ls = .06f)
                T("BEST", 28, p.mut, Modifier.width(150.dp), align = TextAlign.End)
                T("THIS DRIVE", 28, p.mut, Modifier.width(170.dp), align = TextAlign.End)
                T("Δ", 28, p.mut, Modifier.weight(1f), align = TextAlign.End)
            }
            BestRow(p, "0–60 mph", e.sT060, bests.t060, low = true, n = 2)
            BestRow(p, "PEAK ACCEL g", e.sAcc, bests.acc, low = false, n = 2)
            BestRow(p, "PEAK BRAKE g", e.sBrk, bests.brk, low = false, n = 2)
            BestRow(p, "PEAK LATERAL g", max(e.sLatL, e.sLatR), bests.lat, low = false, n = 2)
            BestRow(p, "TOP SPEED", e.sTopMph, bests.topMph, low = false, n = 0)
        }
    }

    // Trip.
    Row(
        Modifier.offset(1128.dp, 360.dp).size(768.dp, 120.dp).carbon(p).padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TripCell(p, "TRIP km", r.tripKm?.let { "%.1f".format(it) } ?: "—", 1f)
        TripCell(p, "TIME", r.tripS?.let { hmmss(it) } ?: "—", 1.3f)
        TripCell(p, "mph", r.tripAvgKmh?.let { (it * DashEngine.KMH_TO_MPH).roundToInt().toString() } ?: "—", 1f)
        TripCell(p, "kWh", r.tripAvgKwh?.let { "%.1f".format(it) } ?: "—", 1f)
        TripCell(p, "ODO", r.odoKm?.let { it.roundToInt().toString() } ?: "—", 1.1f)
    }
}

@Composable
private fun BestRow(p: Palette, label: String, v: Double?, best: Double?, low: Boolean, n: Int) {
    Row(Modifier.fillMaxWidth().height(44.dp).drawBehind { drawRect(p.line, size = Size(size.width, 1f)) }, verticalAlignment = Alignment.CenterVertically) {
        T(label, 28, p.ink, Modifier.width(250.dp))
        T(best?.let { "%.${n}f".format(it) } ?: "—", 28, p.pur, Modifier.width(150.dp), align = TextAlign.End)
        if (v == null || (!low && v < 0.05)) {
            T("—", 28, p.ink, Modifier.width(170.dp), align = TextAlign.End)
            Spacer(Modifier.weight(1f))
        } else {
            val better = best == null || (if (low) v < best else v > best)
            T("%.${n}f".format(v), 28, if (better) p.pur else p.grn, Modifier.width(170.dp), align = TextAlign.End)
            T(best?.let { signed(v - it, n) } ?: "", 28, if (better) p.pur else p.yel, Modifier.weight(1f), align = TextAlign.End)
        }
    }
}

@Composable
private fun RowScope.TripCell(p: Palette, label: String, value: String, weight: Float) {
    Column(Modifier.weight(weight), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        T(label, 28, p.mut)
        T(value, 40, p.ink)
    }
}

// ---------------------------------------------------------------- lamps

@Composable
private fun Lamps(p: Palette, e: DashEngine, tf: LongState, demo: Boolean, stale: Boolean, regen: RegenControl) {
    tf.longValue // recompose with the 20 Hz text clock
    val r = e.r
    val blink = SystemClock.elapsedRealtime() % 700 < 380
    val (dataLabel, dataCol) = when {
        stale -> "NO DATA" to p.amb
        demo -> "DEMO" to p.blu
        else -> "DATA LINK" to p.grn
    }
    Row(Modifier.offset(24.dp, 644.dp).size(1872.dp, 52.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Lamp(p, "◂◂◂", r.indL && blink && !stale, p.amb, Modifier.width(100.dp), size = 32, ls = -.1f)
        Lamp(p, "PARK", r.flow == Flow.PARKED, p.red, Modifier.weight(1f))
        Lamp(p, "DRIVE", r.flow == Flow.DRIVE, p.amb, Modifier.weight(1f))
        Lamp(p, "REGEN", r.flow == Flow.REGEN, p.grn, Modifier.weight(1f))
        // Tap to step the car's regen level. Live only: in demo or with no data it's a readout.
        if (demo || stale) {
            Lamp(p, r.regenLevel ?: "REGEN —", r.regenLevel != null, p.grn, Modifier.weight(1f))
        } else {
            val (label, on, col) = when (val st = regen.status()) {
                is RegenControl.Status.Level -> Triple("REGEN ${RegenControl.name(st.level)}", true, p.grn)
                is RegenControl.Status.Setting -> Triple("→ ${RegenControl.name(st.target)}", blink, p.grn)
                RegenControl.Status.Failed -> Triple("REGEN ✗", true, p.amb)
                RegenControl.Status.Unknown -> Triple("REGEN —", false, p.grn)
            }
            Lamp(p, label, on, col, Modifier.weight(1f).tap { regen.cycle() })
        }
        Lamp(p, if (r.mode == "—") "MODE —" else r.mode, r.mode != "—", p.blu, Modifier.weight(1f))
        Lamp(p, "AUTO HOLD", r.autoHold, p.grn, Modifier.weight(1f))
        Lamp(p, "BRAKE", r.brake, p.red, Modifier.weight(1f))
        Lamp(p, if (r.ev == "—") "EV" else r.ev, r.ev != "—", if (r.ev == "HEV") p.amb else p.grn, Modifier.weight(1f))
        Lamp(p, dataLabel, true, dataCol, Modifier.weight(1f))
        Lamp(p, "▸▸▸", r.indR && blink && !stale, p.amb, Modifier.width(100.dp), size = 32, ls = -.1f)
    }
}

@Composable
private fun Lamp(p: Palette, label: String, on: Boolean, c: Color, modifier: Modifier, size: Int = 28, ls: Float = .04f) {
    Box(
        modifier.fillMaxHeight()
            .drawBehind {
                if (on) glowRect(c.copy(alpha = .45f), 0f, 0f, this.size.width, this.size.height, 18f)
                drawRect(if (on) mix(c, .18f, p.bg) else p.off)
            }
            .border(1.dp, if (on) c else p.line),
        contentAlignment = Alignment.Center,
    ) { T(label, size, if (on) c else p.dim, ls = ls) }
}

// ---------------------------------------------------------------- drawing

private fun DrawScope.drawShift(p: Palette, e: DashEngine) {
    val n = 36; val g = 8f; val w = (size.width - (n - 1) * g) / n; val h = size.height
    val lit = (e.shift * n).roundToInt()
    val full = e.shift >= 0.97
    for (i in 0 until n) {
        val x = i * (w + g)
        val path = android.graphics.Path().apply { moveTo(x + 5, 0f); lineTo(x + w, 0f); lineTo(x + w - 5, h); lineTo(x, h); close() }
        glowPath(p.off, path, 0f)
        if (i < lit || full) {
            val col = if (full) p.blu else if (i < n * 0.6) p.grn else p.red
            glowPath(col, path, if (full) 36f else 12f)
        }
    }
}

private fun DrawScope.drawPower(p: Palette, e: DashEngine) {
    val nR = 12; val nD = 30; val g = 6f; val zg = 20f
    val w = (size.width - zg - (nR + nD - 2) * g) / (nR + nD)
    val flowOnly = e.r.powerKw == null
    val pw = e.power
    fun seg(x: Float, k: Int, col: Color, on: Boolean, alpha: Float) {
        val h = 26f + 46f * ((k + 1f) / nD)
        drawRect(p.off, Offset(x, size.height - h), Size(w, h))
        if (on) glowRect(col, x, size.height - h, w, h, if (alpha > .5f) 12f else 0f, alpha)
    }
    for (i in 0 until nR) {
        val k = nR - 1 - i
        seg(i * (w + g), k, p.grn, if (flowOnly) e.r.flow == Flow.REGEN else pw < 0 && (k + .5) * DashEngine.REGEN_MAX_KW / nR < -pw, if (flowOnly) .35f else 1f)
    }
    val x0 = nR * (w + g) - g + zg
    for (j in 0 until nD) {
        seg(x0 + j * (w + g), j, if (j < 18) p.amb else p.red, if (flowOnly) e.r.flow == Flow.DRIVE else pw > 0 && (j + .5) * DashEngine.DRIVE_MAX_KW / nD < pw, if (flowOnly) .35f else 1f)
    }
    drawRect(p.ink, Offset(nR * (w + g) - g + zg / 2 - 1.5f, 0f), Size(3f, size.height))
}

private fun DrawScope.drawG(p: Palette, e: DashEngine, b: Bests) {
    val o = 200f; val rr = 176f; val f = 0.6
    listOf(0.2, 0.4).forEach { drawCircle(p.line, (it / f * rr).toFloat(), Offset(o, o), style = Stroke(2f)) }
    drawCircle(p.mut, rr, Offset(o, o), style = Stroke(2f))
    drawLine(p.line, Offset(o - rr, o), Offset(o + rr, o), 2f)
    drawLine(p.line, Offset(o, o - rr), Offset(o, o + rr), 2f)
    fun pt(gx: Double, gy: Double) = Offset(
        (o + (gx / f).coerceIn(-1.0, 1.0) * rr).toFloat(),
        (o - (gy / f).coerceIn(-1.0, 1.0) * rr).toFloat(),
    )
    fun tick(c: Offset, horizontal: Boolean) = if (horizontal) drawLine(p.pur, c - Offset(16f, 0f), c + Offset(16f, 0f), 5f)
        else drawLine(p.pur, c - Offset(0f, 16f), c + Offset(0f, 16f), 5f)
    b.acc?.let { tick(pt(0.0, it), true) }
    b.brk?.let { tick(pt(0.0, -it), true) }
    b.lat?.let { tick(pt(-it, 0.0), false); tick(pt(it, 0.0), false) }
    fun diamond(c: Offset) = drawPath(Path().apply {
        moveTo(c.x, c.y - 10); lineTo(c.x + 10, c.y); lineTo(c.x, c.y + 10); lineTo(c.x - 10, c.y); close()
    }, p.grn)
    if (e.sAcc > .05) diamond(pt(0.0, e.sAcc))
    if (e.sBrk > .05) diamond(pt(0.0, -e.sBrk))
    if (e.sLatL > .05) diamond(pt(-e.sLatL, 0.0))
    if (e.sLatR > .05) diamond(pt(e.sLatR, 0.0))
    val now = SystemClock.elapsedRealtime()
    val tr = e.trail
    for (i in 1 until tr.size) {
        val a = 1f - (now - tr[i].third) / 2000f
        if (a <= 0f) continue
        drawLine(p.red.copy(alpha = a * .9f), pt(tr[i - 1].first, tr[i - 1].second), pt(tr[i].first, tr[i].second), 5f, StrokeCap.Round)
    }
    glowCircle(p.ink, pt(e.latG, e.longG), 11f, p.red, 22f)
}

private fun DrawScope.drawBatt(p: Palette, soc: Double) {
    val n = 20; val nub = 16f; val g = 10f
    val w = (size.width - nub - g - (n - 1) * g) / n
    val f = soc / 100 * n
    val col = if (soc > 20) p.grn else if (soc > 10) p.amb else p.red
    for (i in 0 until n) {
        val x = i * (w + g)
        drawRect(p.off, Offset(x, 0f), Size(w, size.height))
        val fl = (f - i).coerceIn(0.0, 1.0).toFloat()
        if (fl > 0f) glowRect(col, x, size.height * (1 - fl), w, size.height * fl, 14f)
    }
    drawRect(p.dim, Offset(size.width - nub, size.height * .3f), Size(nub, size.height * .4f))
}

/** The design's top-down car, in its 180 × 380 viewBox. Wheel colours FL, FR, RL, RR. */
private fun DrawScope.drawCar(p: Palette, wheels: List<Color>) {
    val cr = CornerRadius(6f)
    drawRoundRect(wheels[0], Offset(4f, 62f), Size(22f, 64f), cr)
    drawRoundRect(wheels[1], Offset(154f, 62f), Size(22f, 64f), cr)
    drawRoundRect(wheels[2], Offset(4f, 258f), Size(22f, 64f), cr)
    drawRoundRect(wheels[3], Offset(154f, 258f), Size(22f, 64f), cr)
    drawRoundRect(p.panel, Offset(18f, 10f), Size(144f, 360f), CornerRadius(54f))
    drawRoundRect(p.mut, Offset(18f, 10f), Size(144f, 360f), CornerRadius(54f), style = Stroke(3f))
    drawRoundRect(p.mut, Offset(6f, 128f), Size(14f, 10f), CornerRadius(3f))
    drawRoundRect(p.mut, Offset(160f, 128f), Size(14f, 10f), CornerRadius(3f))
    drawRoundRect(p.ink, Offset(44f, 20f), Size(92f, 4f), CornerRadius(2f))
    drawPath(Path().apply { moveTo(36f, 118f); quadraticBezierTo(90f, 96f, 144f, 118f); lineTo(134f, 160f); quadraticBezierTo(90f, 148f, 46f, 160f); close() }, p.dim)
    drawRoundRect(p.line, Offset(46f, 168f), Size(88f, 110f), CornerRadius(10f), style = Stroke(3f))
    drawPath(Path().apply { moveTo(46f, 286f); quadraticBezierTo(90f, 296f, 134f, 286f); lineTo(140f, 318f); quadraticBezierTo(90f, 332f, 40f, 318f); close() }, p.dim)
    drawRoundRect(p.red, Offset(34f, 356f), Size(112f, 4f), CornerRadius(2f))
}

/** Steering wheel glyph (240 viewBox drawn at 220), rotated by the steering angle. */
private fun DrawScope.drawWheel(p: Palette, deg: Float) {
    scale(size.width / 240f, pivot = Offset.Zero) {
        rotate(deg, pivot = Offset(120f, 120f)) {
            drawCircle(p.ink, 102f, Offset(120f, 120f), style = Stroke(16f))
            drawRect(p.mut, Offset(18f, 112f), Size(72f, 16f))
            drawRect(p.mut, Offset(150f, 112f), Size(72f, 16f))
            drawRect(p.mut, Offset(112f, 150f), Size(16f, 72f))
            drawCircle(p.dim, 32f, Offset(120f, 120f))
            drawRect(p.red, Offset(113f, 8f), Size(14f, 26f))
        }
    }
}

// ---------------------------------------------------------------- bits

@Composable
private fun AccentBar(c: Color) = Box(Modifier.size(48.dp, 3.dp).background(c))

@Composable
private fun T(
    s: String, size: Int, color: Color, modifier: Modifier = Modifier, ls: Float = 0f,
    align: TextAlign? = null, family: FontFamily = Mono,
) = Text(
    s, modifier, color = color, fontSize = size.sp, fontFamily = family, letterSpacing = ls.em,
    textAlign = align, maxLines = 1, softWrap = false, overflow = TextOverflow.Visible, style = NoPad,
)

/**
 * The big italic Saira numerals. CSS gives them `line-height: .8` or so: a box
 * shorter than the glyphs, with the glyphs centred in it and spilling out.
 * Measured here the same way, then skewed like the design's `skewX(-9deg)`.
 */
@Composable
private fun Num(
    s: String, size: Int, weight: FontWeight, color: Color, modifier: Modifier = Modifier,
    align: TextAlign? = null, lineFactor: Float = .8f,
) = Text(
    s,
    modifier
        .layout { m, c ->
            val pl = m.measure(c.copy(minHeight = 0, maxHeight = Constraints.Infinity))
            val box = (size * lineFactor).dp.roundToPx()
            layout(pl.width, box) { pl.place(0, (box - pl.height) / 2) }
        }
        .italic(),
    color = color, fontSize = size.sp, fontFamily = Saira, fontWeight = weight,
    textAlign = align, maxLines = 1, softWrap = false, overflow = TextOverflow.Visible, style = NoPad,
)

private fun Modifier.tap(onClick: () -> Unit): Modifier =
    clickable(interactionSource = MutableInteractionSource(), indication = null, onClick = onClick)

private fun Modifier.hatch(p: Palette): Modifier = drawWithCache {
    val path = Path()
    var d = -size.height
    while (d < size.width) { path.moveTo(d, size.height); path.lineTo(d + size.height, 0f); d += 14f }
    onDrawBehind { drawRect(p.off); drawPath(path, p.dim, style = Stroke(2f)) }
}

private fun Modifier.scanlines(): Modifier = drawWithCache {
    val c = Color(1f, 1f, 1f, .028f)
    onDrawBehind {
        var y = 0f
        while (y < size.height) { drawRect(c, Offset(0f, y), Size(size.width, 1f)); y += 4f }
    }
}

private fun mmss(s: Double): String { val x = s.toInt(); return "%02d:%02d".format(x / 60, x % 60) }
private fun hmmss(s: Double): String { val x = s.toInt(); return "%d:%02d:%02d".format(x / 3600, x / 60 % 60, x % 60) }

/** Personal bests, kept on the car across drives. */
object BestsStore {
    fun load(prefs: SharedPreferences) = Bests(
        t060 = prefs.getFloat("t060", -1f).takeIf { it > 0 }?.toDouble(),
        acc = prefs.getFloat("acc", -1f).takeIf { it > 0 }?.toDouble(),
        brk = prefs.getFloat("brk", -1f).takeIf { it > 0 }?.toDouble(),
        lat = prefs.getFloat("lat", -1f).takeIf { it > 0 }?.toDouble(),
        topMph = prefs.getFloat("top", -1f).takeIf { it > 0 }?.toDouble(),
    )

    fun merge(prefs: SharedPreferences, e: DashEngine) {
        val b = load(prefs)
        val ed = prefs.edit()
        e.sT060?.let { if (b.t060 == null || it < b.t060) ed.putFloat("t060", it.toFloat()) }
        if (e.sAcc > .05 && (b.acc == null || e.sAcc > b.acc)) ed.putFloat("acc", e.sAcc.toFloat())
        if (e.sBrk > .05 && (b.brk == null || e.sBrk > b.brk)) ed.putFloat("brk", e.sBrk.toFloat())
        val lat = max(e.sLatL, e.sLatR)
        if (lat > .05 && (b.lat == null || lat > b.lat)) ed.putFloat("lat", lat.toFloat())
        if (e.sTopMph > 1 && (b.topMph == null || e.sTopMph > b.topMph)) ed.putFloat("top", e.sTopMph.toFloat())
        ed.apply()
    }
}

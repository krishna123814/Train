package com.example.train

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin

/**
 * Poora game ek hi custom View me: menu, race, HUD, result.
 * Physics / AI / track Sim.kt me hai (Android se independent).
 */
class GameView(ctx: Context, private val host: Host) : View(ctx) {

    interface Host {
        fun checkUpdate(manual: Boolean)
        fun getBest(key: String): Double
        fun putBest(key: String, v: Double)
    }

    enum class Screen { MENU, RACE, PAUSE, RESULT }

    private class Btn(val id: String, val r: RectF)

    var updateStatus: String = ""

    // ── game state ──────────────────────────────────────────────────────
    private var screen = Screen.MENU
    private var route = Route.MEDIUM
    private var level = Level.MEDIUM
    private var race: Race? = null
    private var newBest = false

    private var demoSeed = 100L
    private var demoTrack = Track(demoSeed, Route.SHORT)
    private var demo = TrainSim(demoTrack, "Demo", Level.MEDIUM)
    private var demoEnd = 0.0

    // ── camera ──────────────────────────────────────────────────────────
    private var ppm = 7f                // pixels per meter
    private var camX = 0.0
    private var camH = 0.0
    private val exag = 2.0f             // pahad vertical exaggeration
    private val vz = 1.45f              // train vertical size exaggeration
    private var curTk: Track = demoTrack
    private var anim = 0f

    // ── screen metrics ──────────────────────────────────────────────────
    private var W = 0f
    private var H = 0f
    private var u = 1f
    private var baseY = 0f
    private var laneGap = 0f
    private var baseScale = 7f
    private var brakeCx = 0f
    private var brakeCy = 0f
    private var brakeR = 0f
    private var thrX = 0f
    private var thrTop = 0f
    private var thrBottom = 0f
    private val thrZone = RectF()
    private val pauseRect = RectF()

    // ── paints / scratch ────────────────────────────────────────────────
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val skyPaint = Paint()
    private val path = Path()
    private val path2 = Path()
    private val rf = RectF()
    private var sleepers = FloatArray(4 * 600)
    private val btns = ArrayList<Btn>()

    // ── loop ────────────────────────────────────────────────────────────
    private var running = false
    private var lastNs = 0L
    private val frameCb = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val dt = if (lastNs == 0L) 0.016 else ((frameTimeNanos - lastNs) / 1e9).coerceIn(0.0, 0.05)
            lastNs = frameTimeNanos
            update(dt)
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    init {
        tp.setShadowLayer(4f, 0f, 2f, C(0x99000000))
        resetDemo()
    }

    fun resume() {
        if (running) return
        running = true
        lastNs = 0L
        Choreographer.getInstance().postFrameCallback(frameCb)
    }

    fun pause() {
        running = false
        if (screen == Screen.RACE) screen = Screen.PAUSE
    }

    /** Back button: true = hamne handle kar liya */
    fun onBack(): Boolean {
        return when (screen) {
            Screen.RACE -> { screen = Screen.PAUSE; true }
            Screen.PAUSE -> { screen = Screen.RACE; true }
            Screen.RESULT -> { screen = Screen.MENU; true }
            Screen.MENU -> false
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────
    private fun C(v: Long): Int = v.toInt()

    private fun fillP(color: Int): Paint {
        p.style = Paint.Style.FILL
        p.shader = null
        p.color = color
        return p
    }

    private fun strokeP(color: Int, w: Float): Paint {
        p.style = Paint.Style.STROKE
        p.shader = null
        p.color = color
        p.strokeWidth = w
        p.strokeCap = Paint.Cap.BUTT
        return p
    }

    private fun txt(
        c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int,
        align: Paint.Align = Paint.Align.LEFT, bold: Boolean = true
    ) {
        tp.textSize = size
        tp.color = color
        tp.textAlign = align
        tp.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        c.drawText(s, x, y, tp)
    }

    private fun hash(i: Int): Int {
        var h = i * 374761393 + 668265263
        h = (h xor (h ushr 13)) * 1274126177
        return h xor (h ushr 16)
    }

    private fun hf(i: Int): Float = (hash(i) and 0xFFFF) / 65535f

    private fun fmt(t: Double): String {
        val m = (t / 60.0).toInt()
        val s = t - m * 60.0
        return String.format("%02d:%04.1f", m, s)
    }

    private fun fmtDist(m: Double): String =
        if (m >= 1000.0) String.format("%.1f km", m / 1000.0) else String.format("%d m", max(0.0, m).toInt())

    private fun sx(wx: Double): Float = ((wx - camX) * ppm).toFloat()

    private fun ty(wx: Double, lane: Float): Float =
        baseY - ((curTk.heightAt(wx) - camH) * ppm * exag).toFloat() + lane

    private fun carUnder(): Float = max(3.2f * u, 0.5f * ppm * vz) * 2f + 2f * u
    private fun carBody(): Float = 3.4f * ppm * vz
    private fun wireH(): Float = carUnder() + carBody() + 18f * u

    // ── size ────────────────────────────────────────────────────────────
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        W = w.toFloat()
        H = h.toFloat()
        u = H / 1080f
        baseY = 0.70f * H
        laneGap = 0.05f * H
        baseScale = H / 140f
        ppm = baseScale
        skyPaint.shader = LinearGradient(
            0f, 0f, 0f, H * 0.8f,
            intArrayOf(C(0xFF3F86D6), C(0xFF8EC5F0), C(0xFFE6F3FB)),
            floatArrayOf(0f, 0.62f, 1f), Shader.TileMode.CLAMP
        )
        brakeR = 130f * u
        brakeCx = 40f * u + brakeR + 30f * u
        brakeCy = H - 40f * u - brakeR - 20f * u
        thrX = W - 150f * u
        thrBottom = H - 130f * u
        thrTop = H - 620f * u
        thrZone.set(W - 330f * u, thrTop - 90f * u, W, H - 30f * u)
        pauseRect.set(W - 130f * u, 30f * u, W - 30f * u, 130f * u)
    }

    // ── game flow ───────────────────────────────────────────────────────
    private fun resetDemo() {
        demoSeed++
        demoTrack = Track(demoSeed, Route.SHORT)
        demo = TrainSim(demoTrack, "Demo", Level.MEDIUM)
        demo.go()
        demo.v = 28.0
        demo.x += 40.0
        demoEnd = 0.0
        camH = demoTrack.heightAt(demo.x)
    }

    private fun startRace() {
        val r = Race(route, level, System.nanoTime())
        race = r
        screen = Screen.RACE
        newBest = false
        ppm = baseScale
        camH = r.track.heightAt(r.player.x)
        lastNs = 0L
    }

    private fun bestKey(): String = "best_" + route.name + "_" + level.name

    private fun finishRace(r: Race) {
        val key = bestKey()
        val old = host.getBest(key)
        if (r.playerWon && (old < 0.0 || r.playerTime < old)) {
            host.putBest(key, r.playerTime)
            newBest = true
        }
        screen = Screen.RESULT
    }

    private fun update(dt: Double) {
        anim += dt.toFloat()
        when (screen) {
            Screen.MENU -> {
                demo.step(dt)
                if (demo.finished) {
                    demoEnd += dt
                    if (demoEnd > 1.5) resetDemo()
                }
            }
            Screen.RACE -> {
                val r = race
                if (r != null) {
                    var rem = dt
                    while (rem > 1e-9) {
                        val s = min(rem, 1.0 / 90.0)
                        r.update(s)
                        rem -= s
                    }
                    if (r.over) finishRace(r)
                }
            }
            else -> {}
        }
        updateCamera(dt)
    }

    private fun updateCamera(dt: Double) {
        if (W <= 0f) return
        val focus: TrainSim = if (screen == Screen.MENU) demo else (race?.player ?: return)
        val target = baseScale * (1f - 0.28f * (focus.v / Phys.VMAX).toFloat())
        ppm += (target - ppm) * min(1f, dt.toFloat() * 1.4f)
        camX = focus.x - 0.66 * W / ppm
        val hh = focus.track.heightAt(focus.x - 40.0)
        camH += (hh - camH) * min(1.0, dt * 3.0)
    }

    // ── touch ───────────────────────────────────────────────────────────
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (screen == Screen.RACE) {
            raceTouch(e)
        } else if (e.actionMasked == MotionEvent.ACTION_UP) {
            tap(e.x, e.y)
        }
        return true
    }

    private fun raceTouch(e: MotionEvent) {
        val r = race ?: return
        val act = e.actionMasked
        val skip = if (act == MotionEvent.ACTION_POINTER_UP || act == MotionEvent.ACTION_UP) e.actionIndex else -1
        var brake = false
        for (i in 0 until e.pointerCount) {
            if (i == skip) continue
            val px = e.getX(i)
            val py = e.getY(i)
            val isNew = (act == MotionEvent.ACTION_DOWN || act == MotionEvent.ACTION_POINTER_DOWN) && i == e.actionIndex
            if (pauseRect.contains(px, py)) {
                if (isNew) {
                    screen = Screen.PAUSE
                    return
                }
                continue
            }
            if (hypot(px - brakeCx, py - brakeCy) < brakeR * 1.35f) {
                brake = true
            } else if (thrZone.contains(px, py)) {
                val t = ((thrBottom - py) / (thrBottom - thrTop)).coerceIn(0f, 1f)
                r.player.throttleIn = (round(t * 20f) / 20f).toDouble()
            }
        }
        if (brake && r.player.brakeIn == 0.0) r.player.throttleIn = 0.0   // brake dabate hi power cut
        r.player.brakeIn = if (brake) 1.0 else 0.0
    }

    private fun tap(x: Float, y: Float) {
        for (b in btns) {
            if (b.r.contains(x, y)) {
                onButton(b.id)
                return
            }
        }
    }

    private fun onButton(id: String) {
        when {
            id.startsWith("r") && id.length == 2 -> route = Route.values()[id[1] - '0']
            id.startsWith("l") && id.length == 2 -> level = Level.values()[id[1] - '0']
            id == "start" || id == "again" -> startRace()
            id == "upd" -> host.checkUpdate(true)
            id == "menu" -> { screen = Screen.MENU; race = null }
            id == "resume" -> { screen = Screen.RACE; lastNs = 0L }
            id == "quit" -> { screen = Screen.MENU; race = null }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  DRAW
    // ═══════════════════════════════════════════════════════════════════
    override fun onDraw(c: Canvas) {
        if (W <= 0f) return
        btns.clear()
        if (screen == Screen.MENU) {
            drawScene(c, demoTrack, demo, null)
            drawMenu(c)
            return
        }
        val r = race ?: return
        drawScene(c, r.track, r.player, r.ai)
        when (screen) {
            Screen.RACE -> drawHud(c, r)
            Screen.PAUSE -> { drawHud(c, r); drawPause(c) }
            Screen.RESULT -> drawResult(c, r)
            else -> {}
        }
    }

    // ── scene ───────────────────────────────────────────────────────────
    private fun drawScene(c: Canvas, tk: Track, me: TrainSim, other: TrainSim?) {
        curTk = tk
        c.drawRect(0f, 0f, W, H, skyPaint)

        // suraj
        c.drawCircle(W * 0.82f, H * 0.17f, 140f * u, fillP(C(0x22FFF1B8)))
        c.drawCircle(W * 0.82f, H * 0.17f, 80f * u, fillP(C(0x44FFF1B8)))
        c.drawCircle(W * 0.82f, H * 0.17f, 46f * u, fillP(C(0xFFFFF6D0)))

        // baadal
        val off = (camX * ppm * 0.04).toFloat()
        val span = W + 700f * u
        for (i in 0 until 6) {
            var cx = (i * 520f * u + 180f * u - off) % span
            if (cx < 0f) cx += span
            cx -= 350f * u
            val cy = H * (0.10f + 0.06f * (i % 3))
            fillP(C(0xCCFFFFFF))
            rf.set(cx - 120f * u, cy - 26f * u, cx + 120f * u, cy + 26f * u); c.drawOval(rf, p)
            rf.set(cx - 70f * u, cy - 48f * u, cx + 60f * u, cy + 10f * u); c.drawOval(rf, p)
            rf.set(cx + 10f * u, cy - 38f * u, cx + 130f * u, cy + 16f * u); c.drawOval(rf, p)
        }

        // door ke pahad (parallax)
        ridge(c, C(0xFFA9C0D6), 0.05f, 0.10f, 0.52f, 0.26f, 0.0016f, 0.5f)
        ridge(c, C(0xFF86A6BB), 0.10f, 0.20f, 0.60f, 0.24f, 0.0026f, 2.1f)
        ridge(c, C(0xFF5F8A6A), 0.22f, 0.34f, 0.68f, 0.20f, 0.0040f, 4.3f)

        // peeche wali zameen (AI lane) + ped + station building
        groundPoly(c, -laneGap, C(0xFF4E8F44), 0f)
        drawTrees(c, -laneGap)
        for (st in tk.stations) drawStationBack(c, st)

        if (other != null) {
            drawTrack(c, -laneGap)
            drawTrain(c, other, -laneGap, C(0xFFD32F2F))
        }

        // aage wali zameen (player lane)
        groundPoly(c, 0f, C(0xFF3C7D38), 0f)
        groundPoly(c, 0f, C(0xFF6B5638), 58f * u)
        groundPoly(c, 0f, C(0xFF4F3F29), 120f * u)

        drawTrack(c, 0f)
        drawMasts(c)
        drawBoards(c, tk)
        drawTrain(c, me, 0f, C(0xFF1565C0))
        for (st in tk.stations) drawPlatformFront(c, st)
        drawForeground(c)
    }

    private fun ridge(c: Canvas, color: Int, par: Float, vpar: Float, baseFrac: Float, ampFrac: Float, freq: Float, ph: Float) {
        path.reset()
        path.moveTo(0f, H)
        val offX = (camX * ppm * par).toFloat()
        val vo = (camH * ppm * exag * vpar).toFloat()
        var x = 0f
        while (x <= W + 24f) {
            val t = (x + offX) * freq + ph
            val n = 0.55f * sin(t) + 0.30f * sin(2.3f * t + 1.7f) + 0.15f * sin(5.1f * t + 0.4f)
            path.lineTo(x, H * baseFrac + vo - (n * 0.5f + 0.5f) * ampFrac * H)
            x += 24f
        }
        path.lineTo(W + 24f, H)
        path.close()
        c.drawPath(path, fillP(color))
    }

    private fun groundPoly(c: Canvas, lane: Float, color: Int, drop: Float) {
        path.reset()
        var px = -10f
        var first = true
        while (px <= W + 14f) {
            val y = ty(camX + px / ppm, lane) + drop
            if (first) { path.moveTo(px, y); first = false } else path.lineTo(px, y)
            px += 12f
        }
        path.lineTo(W + 14f, H + 10f)
        path.lineTo(-10f, H + 10f)
        path.close()
        c.drawPath(path, fillP(color))
    }

    private fun drawTrees(c: Canvas, lane: Float) {
        val i0 = floor(camX / 26.0).toInt() - 2
        val i1 = ((camX + W / ppm) / 26.0).toInt() + 3
        for (i in i0..i1) {
            if (hf(i * 7 + 3) < 0.5f) continue
            val wx = i * 26.0 + hf(i * 13 + 1) * 20.0
            val px = sx(wx)
            val py = ty(wx, lane) - (4f + hf(i + 9) * 16f) * u
            val th = (8f + hf(i * 5 + 2) * 9f) * ppm * 0.95f
            val g = when ((hash(i) and 3)) {
                0 -> C(0xFF2E6B34)
                1 -> C(0xFF3B7D3A)
                2 -> C(0xFF285E2E)
                else -> C(0xFF4A8B3F)
            }
            c.drawRect(px - th * 0.05f, py - th * 0.2f, px + th * 0.05f, py, fillP(C(0xFF5D4037)))
            fillP(g)
            path.reset()
            path.moveTo(px, py - th)
            path.lineTo(px - th * 0.26f, py - th * 0.38f)
            path.lineTo(px + th * 0.26f, py - th * 0.38f)
            path.close()
            c.drawPath(path, p)
            path.reset()
            path.moveTo(px, py - th * 0.72f)
            path.lineTo(px - th * 0.32f, py - th * 0.12f)
            path.lineTo(px + th * 0.32f, py - th * 0.12f)
            path.close()
            c.drawPath(path, p)
        }
    }

    private fun drawStationBack(c: Canvas, st: Station) {
        val x0 = sx(st.p0 + 10.0)
        val x1 = sx(st.p0 + 112.0)
        if (x1 < -80f || x0 > W + 80f) return
        val gy = ty(st.mark, -laneGap) - 6f * u
        val bh = 10f * ppm * vz * 0.9f
        val cx = (x0 + x1) / 2f
        // deewar
        c.drawRect(x0, gy - bh, x1, gy, fillP(C(0xFFEADFBF)))
        c.drawRect(x0, gy - bh * 0.2f, x1, gy, fillP(C(0xFFB5654B)))
        // khidkiyan
        val n = 6
        val ww = (x1 - x0) / (n * 2f)
        for (i in 0 until n) {
            val wx = x0 + ww * (0.5f + i * 2f)
            if (i == n / 2 || i == n / 2 - 1) {
                c.drawRect(wx, gy - bh * 0.62f, wx + ww, gy - bh * 0.2f, fillP(C(0xFF4E342E)))
            } else {
                c.drawRect(wx, gy - bh * 0.72f, wx + ww, gy - bh * 0.38f, fillP(C(0xFF37556B)))
            }
        }
        // chhat
        path.reset()
        path.moveTo(x0 - 14f * u, gy - bh)
        path.lineTo(x0 + (x1 - x0) * 0.06f, gy - bh - bh * 0.34f)
        path.lineTo(x1 - (x1 - x0) * 0.06f, gy - bh - bh * 0.34f)
        path.lineTo(x1 + 14f * u, gy - bh)
        path.close()
        c.drawPath(path, fillP(C(0xFF8E3B2E)))
        // platform canopy
        c.drawRect(x0 - 40f * u, gy - bh * 0.12f, x1 + 40f * u, gy - bh * 0.12f + 7f * u, fillP(C(0xFF5D4037)))
        // naam ka board
        val bw = 280f * u
        val by = gy - bh - bh * 0.34f - 78f * u
        rf.set(cx - bw / 2f, by, cx + bw / 2f, by + 62f * u)
        c.drawRoundRect(rf, 10f * u, 10f * u, fillP(C(0xFF0D47A1)))
        c.drawRect(cx - 5f * u, by + 62f * u, cx + 5f * u, gy - bh - bh * 0.30f, fillP(C(0xFF37474F)))
        txt(c, st.name.uppercase(), cx, by + 44f * u, 38f * u, C(0xFFFFFFFF), Paint.Align.CENTER)
    }

    private fun drawTrack(c: Canvas, lane: Float) {
        val wx0 = camX - 6.0
        val wx1 = camX + W / ppm + 6.0
        // ballast
        path.reset()
        var wx = floor(wx0 / 5.0) * 5.0
        var first = true
        while (wx <= wx1 + 5.0) {
            val px = sx(wx)
            val py = ty(wx, lane)
            if (first) { path.moveTo(px, py); first = false } else path.lineTo(px, py)
            wx += 5.0
        }
        c.save()
        c.translate(0f, 5f * u)
        c.drawPath(path, strokeP(C(0xFF8C8377), 12f * u))
        c.restore()
        // sleepers
        val stepM = 1.7
        var s = floor(wx0 / stepM) * stepM
        var k = 0
        val need = (((wx1 - wx0) / stepM).toInt() + 6) * 4
        if (sleepers.size < need) sleepers = FloatArray(need + 64)
        while (s <= wx1) {
            val px = sx(s)
            val py = ty(s, lane)
            sleepers[k++] = px; sleepers[k++] = py - 1f * u
            sleepers[k++] = px; sleepers[k++] = py + 7f * u
            s += stepM
        }
        c.drawLines(sleepers, 0, k, strokeP(C(0xFF4E3B2A), 3.2f * u))
        // rails
        c.drawPath(path, strokeP(C(0xFFDCE3E8), 2.4f * u))
    }

    private fun drawMasts(c: Canvas) {
        val wh = wireH()
        val i0 = floor(camX / 50.0).toInt() - 1
        val i1 = ((camX + W / ppm) / 50.0).toInt() + 2
        for (i in i0..i1) {
            val wx = i * 50.0
            val px = sx(wx)
            val y = ty(wx, 0f)
            c.drawLine(px, y + 6f * u, px, y - wh - 8f * u, strokeP(C(0xFF4A5560), 3.4f * u))
            c.drawLine(px - 10f * u, y - wh - 8f * u, px + 10f * u, y - wh - 8f * u, strokeP(C(0xFF4A5560), 3f * u))
        }
        path.reset()
        var px = -10f
        var first = true
        while (px <= W + 14f) {
            val y = ty(camX + px / ppm, 0f) - wh
            if (first) { path.moveTo(px, y); first = false } else path.lineTo(px, y)
            px += 14f
        }
        c.drawPath(path, strokeP(C(0xFF2B333A), 1.8f * u))
    }

    private fun drawBoards(c: Canvas, tk: Track) {
        for (st in tk.stations) {
            for (k in 1..3) {
                val wx = st.mark - k * 300.0 - 100.0
                if (wx < camX - 20.0 || wx > camX + W / ppm + 20.0) continue
                val px = sx(wx)
                val y = ty(wx, 0f) + 10f * u
                c.drawRect(px - 2f * u, y - 54f * u, px + 2f * u, y, fillP(C(0xFF37474F)))
                rf.set(px - 22f * u, y - 90f * u, px + 22f * u, y - 48f * u)
                c.drawRoundRect(rf, 6f * u, 6f * u, fillP(C(0xFFFFC107)))
                txt(c, k.toString(), px, y - 58f * u, 34f * u, C(0xFF1B1B1B), Paint.Align.CENTER)
            }
        }
    }

    // ── train ───────────────────────────────────────────────────────────
    private fun drawTrain(c: Canvas, tr: TrainSim, lane: Float, accent: Int) {
        var end = tr.x
        for (i in 0..3) {
            val len = if (i == 0) 22.0 else 20.0
            val center = end - len / 2.0
            val px = sx(center)
            val half = (len * ppm).toFloat() / 2f
            if (px + half > -60f && px - half < W + 60f) {
                val xf = center + len * 0.32
                val xr = center - len * 0.32
                val yF = ty(xf, lane)
                val yR = ty(xr, lane)
                val ang = atan2(yF - yR, ((xf - xr) * ppm).toFloat())
                c.save()
                c.translate(px, (yF + yR) / 2f)
                c.rotate(Math.toDegrees(ang.toDouble()).toFloat())
                drawCar(c, (len * ppm).toFloat(), i == 0, accent, tr, i)
                c.restore()
            }
            end -= len + 1.0
        }
    }

    private fun drawCar(c: Canvas, L: Float, loco: Boolean, accent: Int, tr: TrainSim, idx: Int) {
        val wr = max(3.2f * u, 0.5f * ppm * vz)
        val under = carUnder()
        val bh = carBody()
        val top = -(under + bh)
        val bot = -under
        val l = -L / 2f
        val r = L / 2f
        val nose = if (loco) 5.5f * ppm else 0f

        // bogie + wheels
        for (bx in floatArrayOf(-L * 0.30f, L * 0.30f)) {
            rf.set(bx - wr * 2.7f, -wr * 1.9f, bx + wr * 2.7f, -wr * 0.5f)
            c.drawRect(rf, fillP(C(0xFF37474F)))
            for (dx in floatArrayOf(-wr * 1.3f, wr * 1.3f)) {
                c.drawCircle(bx + dx, -wr, wr, fillP(C(0xFF1F272C)))
                c.drawCircle(bx + dx, -wr, wr * 0.45f, fillP(C(0xFF90A4AE)))
            }
        }
        // underframe
        c.drawRect(l + 3f * u, bot - 1f * u, r - 3f * u, bot + 7f * u, fillP(C(0xFF2B3338)))

        // body
        val body = C(0xFFF4F6F8)
        if (loco) {
            path.reset()
            path.moveTo(l, bot)
            path.lineTo(l, top + 5f * u)
            path.quadTo(l, top, l + 6f * u, top)
            path.lineTo(r - nose, top)
            path.cubicTo(r - nose * 0.35f, top, r, top + bh * 0.2f, r, top + bh * 0.62f)
            path.lineTo(r, bot)
            path.close()
            c.drawPath(path, fillP(body))
        } else {
            rf.set(l, top, r, bot)
            c.drawRoundRect(rf, 6f * u, 6f * u, fillP(body))
        }
        // niche ki chhaya + roof
        c.drawRect(l + 2f * u, bot - bh * 0.10f, r - 1f * u, bot, fillP(C(0x22000000)))
        c.drawRect(l + 6f * u, top, r - (if (loco) nose * 0.8f else 6f * u), top + bh * 0.09f, fillP(C(0xFFB0BEC5)))
        // accent patti
        c.drawRect(l, bot - bh * 0.26f, r, bot - bh * 0.13f, fillP(accent))
        // khidkiyan
        val x0 = l + L * 0.06f
        val x1 = r - (if (loco) nose * 1.05f else L * 0.06f)
        val n = max(1, ((x1 - x0) / (3.4f * ppm)).toInt())
        val ww = (x1 - x0) / n
        for (i in 0 until n) {
            rf.set(x0 + i * ww + ww * 0.14f, top + bh * 0.22f, x0 + (i + 1) * ww - ww * 0.14f, top + bh * 0.56f)
            c.drawRoundRect(rf, 3f * u, 3f * u, fillP(C(0xFF1E2A33)))
            c.drawRect(rf.left + 2f * u, rf.top + 2f * u, rf.right - 2f * u, rf.top + 5f * u, fillP(C(0x33FFFFFF)))
        }
        // coach ke beech ka connector
        if (!loco) {
            c.drawRect(l - 1.0f * ppm, top + bh * 0.3f, l + 2f * u, bot, fillP(C(0xFF222B30)))
        }

        if (loco) {
            // windshield
            path.reset()
            path.moveTo(r - nose * 0.72f, top + bh * 0.11f)
            path.lineTo(r - nose * 0.30f, top + bh * 0.24f)
            path.lineTo(r - nose * 0.10f, top + bh * 0.52f)
            path.lineTo(r - nose * 0.64f, top + bh * 0.52f)
            path.close()
            c.drawPath(path, fillP(C(0xFF16202A)))
            // headlight
            val hx = r - 4f * u
            val hy = bot - bh * 0.17f
            c.drawCircle(hx, hy, 16f * u, fillP(C(0x33FFE082)))
            c.drawCircle(hx, hy, 4f * u, fillP(C(0xFFFFF59D)))
            // pantograph
            val pxp = -L * 0.20f
            val st = strokeP(C(0xFF37474F), 2.4f * u)
            c.drawLine(pxp - 9f * u, top, pxp + 3f * u, top - 17f * u, st)
            c.drawLine(pxp + 13f * u, top, pxp + 3f * u, top - 17f * u, st)
            c.drawLine(pxp - 4f * u, top - 18f * u, pxp + 10f * u, top - 18f * u, st)
            // naam
            txt(c, if (tr.ai != null) "AI" else "AAP", L * 0.18f, top + bh * 0.88f, 15f * u, accent, Paint.Align.CENTER)
        }
    }

    private fun drawPlatformFront(c: Canvas, st: Station) {
        val xa = sx(st.p0)
        val xb = sx(st.p1)
        if (xb < -60f || xa > W + 60f) return
        val yr = ty(st.mark, 0f)
        val top = yr - 5f * u
        c.drawRect(xa, top, xb, top + 76f * u, fillP(C(0xFFC4C9CF)))
        c.drawRect(xa, top + 24f * u, xb, top + 76f * u, fillP(C(0xFF9AA1A9)))
        c.drawRect(xa, top, xb, top + 6f * u, fillP(C(0xFFFFC107)))
        // rukne ka green zone
        val za = sx(st.mark - Phys.ZONE)
        val zb = sx(st.mark)
        c.drawRect(za, top + 6f * u, zb, top + 22f * u, fillP(C(0xFF2E7D32)))
        txt(c, "STOP ZONE", (za + zb) / 2f, top + 19f * u, 13f * u, C(0xFFFFFFFF), Paint.Align.CENTER)
        // lamp posts
        var lx = st.p0 + 12.0
        while (lx < st.p1) {
            val px = sx(lx)
            c.drawRect(px - 1.5f * u, top - 70f * u, px + 1.5f * u, top, fillP(C(0xFF37474F)))
            c.drawCircle(px, top - 72f * u, 4.5f * u, fillP(C(0xFFFFF3B0)))
            lx += 36.0
        }
        // log
        val cols = intArrayOf(C(0xFFE53935), C(0xFF1E88E5), C(0xFFFDD835), C(0xFF43A047), C(0xFF8E24AA))
        var k = 0
        var pxm = st.p0 + 9.0
        while (pxm < st.p1 - 6.0) {
            val h = hf(k * 31 + hash(st.name.length * 17 + k))
            if (h < 0.55f) {
                val px = sx(pxm + h * 6.0)
                val ph = 1.7f * ppm * 1.1f
                c.drawRect(px - 2.6f * u, top - ph * 0.45f, px + 2.6f * u, top, fillP(C(0xFF263238)))
                c.drawRoundRect(RectF(px - 3.6f * u, top - ph * 0.85f, px + 3.6f * u, top - ph * 0.42f), 3f * u, 3f * u, fillP(cols[k % cols.size]))
                c.drawCircle(px, top - ph * 0.98f, 3.4f * u, fillP(C(0xFFFFCCA0)))
            }
            k++
            pxm += 13.0
        }
        // STOP board
        val bx = sx(st.mark) + 10f * u
        c.drawRect(bx - 1.6f * u, top - 62f * u, bx + 1.6f * u, top, fillP(C(0xFF37474F)))
        rf.set(bx - 26f * u, top - 90f * u, bx + 26f * u, top - 58f * u)
        c.drawRoundRect(rf, 5f * u, 5f * u, fillP(C(0xFFD32F2F)))
        txt(c, "STOP", bx, top - 66f * u, 20f * u, C(0xFFFFFFFF), Paint.Align.CENTER)
    }

    private fun drawForeground(c: Canvas) {
        val par = 1.7f
        val w0 = camX - 30.0
        val w1 = camX + W / (ppm * par) + 30.0
        var i = floor(w0 / 20.0).toInt()
        val iEnd = (w1 / 20.0).toInt() + 1
        while (i <= iEnd) {
            val wx = i * 20.0
            val h = hf(i * 3 + 5)
            if (h < 0.7f) {
                val px = ((wx - camX) * ppm * par).toFloat()
                val sz = (24f + h * 40f) * u
                val y = H - 6f * u
                fillP(C(0xFF244F2A))
                rf.set(px - sz * 1.4f, y - sz, px + sz * 1.4f, y + sz * 0.8f)
                c.drawOval(rf, p)
                fillP(C(0xFF2F6A35))
                rf.set(px - sz * 0.9f, y - sz * 1.05f, px + sz * 0.9f, y + sz * 0.3f)
                c.drawOval(rf, p)
            }
            i++
        }
    }

    // ── HUD ─────────────────────────────────────────────────────────────
    private fun panel(c: Canvas, l: Float, t: Float, r: Float, b: Float) {
        rf.set(l, t, r, b)
        c.drawRoundRect(rf, 18f * u, 18f * u, fillP(C(0x99101820)))
    }

    private fun drawHud(c: Canvas, r: Race) {
        val pl = r.player
        val tk = r.track

        // speed panel
        panel(c, 30f * u, 30f * u, 420f * u, 215f * u)
        txt(c, (pl.v * 3.6).toInt().toString(), 55f * u, 140f * u, 104f * u, C(0xFFFFFFFF))
        txt(c, "km/h", 290f * u, 140f * u, 32f * u, C(0xFFB0BEC5))
        val g = pl.grade() * 100.0
        val gs = when {
            g > 0.4 -> String.format("▲ %.1f%%  chadhai", g)
            g < -0.4 -> String.format("▼ %.1f%%  dhalaan", -g)
            else -> "▬  samtal"
        }
        val gc = if (g > 0.4) C(0xFFFFAB91) else if (g < -0.4) C(0xFFA5D6A7) else C(0xFFCFD8DC)
        txt(c, gs, 55f * u, 191f * u, 31f * u, gc)

        // progress bar
        val bl = W * 0.30f
        val br = W * 0.70f
        val by = 62f * u
        panel(c, bl - 40f * u, 22f * u, br + 40f * u, 120f * u)
        c.drawLine(bl, by, br, by, strokeP(C(0xFF78909C), 8f * u).also { it.strokeCap = Paint.Cap.ROUND })
        val m0 = tk.stations.first().mark
        val m1 = tk.stations.last().mark
        fun frac(x: Double): Float = ((x - m0) / (m1 - m0)).coerceIn(0.0, 1.0).toFloat()
        c.drawLine(bl, by, bl + (br - bl) * frac(pl.x), by, strokeP(C(0xFF42A5F5), 8f * u).also { it.strokeCap = Paint.Cap.ROUND })
        for ((i, st) in tk.stations.withIndex()) {
            val sxp = bl + (br - bl) * frac(st.mark)
            val done = i < pl.next
            c.drawCircle(sxp, by, 9f * u, fillP(if (done) C(0xFF66BB6A) else if (i == pl.next) C(0xFFFFCA28) else C(0xFFECEFF1)))
            if (tk.stations.size <= 6 || i == pl.next || i == 0 || i == tk.stations.size - 1) {
                txt(c, st.name, sxp, 106f * u, 20f * u, C(0xFFECEFF1), Paint.Align.CENTER, false)
            }
        }
        c.drawCircle(bl + (br - bl) * frac(r.ai.x), by, 11f * u, fillP(C(0xFFEF5350)))
        c.drawCircle(bl + (br - bl) * frac(pl.x), by, 12f * u, fillP(C(0xFF29B6F6)))
        c.drawCircle(bl + (br - bl) * frac(pl.x), by, 12f * u, strokeP(C(0xFFFFFFFF), 3f * u))

        // pause
        rf.set(pauseRect)
        c.drawRoundRect(rf, 18f * u, 18f * u, fillP(C(0x99101820)))
        c.drawRect(pauseRect.centerX() - 20f * u, pauseRect.centerY() - 22f * u, pauseRect.centerX() - 7f * u, pauseRect.centerY() + 22f * u, fillP(C(0xFFFFFFFF)))
        c.drawRect(pauseRect.centerX() + 7f * u, pauseRect.centerY() - 22f * u, pauseRect.centerX() + 20f * u, pauseRect.centerY() + 22f * u, fillP(C(0xFFFFFFFF)))

        // agla station
        val nx = min(pl.next, tk.stations.size - 1)
        val ns = tk.stations[nx]
        panel(c, W - 600f * u, 30f * u, W - 150f * u, 215f * u)
        txt(c, if (pl.finished) "MANZIL" else "AGLA STATION", W - 580f * u, 66f * u, 22f * u, C(0xFFB0BEC5))
        txt(c, ns.name, W - 580f * u, 116f * u, 48f * u, C(0xFFFFFFFF))
        txt(c, if (pl.finished) "pahunch gaye" else fmtDist(pl.distToMark()), W - 580f * u, 158f * u, 38f * u, C(0xFFFFCA28))
        val gap = pl.x - r.ai.x
        val gtxt = if (gap >= 0) "AI ${fmtDist(gap)} peeche" else "AI ${fmtDist(-gap)} aage"
        txt(c, gtxt, W - 580f * u, 197f * u, 27f * u, if (gap >= 0) C(0xFFA5D6A7) else C(0xFFEF9A9A))

        // time
        txt(c, fmt(if (pl.finished) pl.finishTime else pl.clock + pl.penalty), W / 2f, 160f * u, 40f * u, C(0xFFFFFFFF), Paint.Align.CENTER)
        if (pl.penalty > 0.0) txt(c, "penalty +${pl.penalty.toInt()}s", W / 2f, 195f * u, 24f * u, C(0xFFEF9A9A), Paint.Align.CENTER)
        if (r.ai.finished && !pl.finished) txt(c, "AI pahunch gayi: " + fmt(r.ai.finishTime), W / 2f, 230f * u, 26f * u, C(0xFFEF9A9A), Paint.Align.CENTER)

        // brake button
        val bpress = pl.brakeIn > 0.0
        c.drawCircle(brakeCx, brakeCy, brakeR, fillP(if (bpress) C(0xFFE53935) else C(0xCC8E1B1B)))
        c.drawCircle(brakeCx, brakeCy, brakeR, strokeP(C(0xFFFFCDD2), 6f * u))
        if (pl.bEff > 0.02) {
            rf.set(brakeCx - brakeR - 14f * u, brakeCy - brakeR - 14f * u, brakeCx + brakeR + 14f * u, brakeCy + brakeR + 14f * u)
            c.drawArc(rf, -90f, (360.0 * pl.bEff).toFloat(), false, strokeP(C(0xFFFFEB3B), 9f * u))
        }
        txt(c, "BRAKE", brakeCx, brakeCy + 15f * u, 54f * u, C(0xFFFFFFFF), Paint.Align.CENTER)

        // power lever
        val lvl = pl.throttleIn.toFloat()
        panel(c, thrX - 120f * u, thrTop - 110f * u, thrX + 120f * u, thrBottom + 60f * u)
        txt(c, "POWER", thrX, thrTop - 62f * u, 32f * u, C(0xFFFFFFFF), Paint.Align.CENTER)
        rf.set(thrX - 24f * u, thrTop, thrX + 24f * u, thrBottom)
        c.drawRoundRect(rf, 24f * u, 24f * u, fillP(C(0xFF37474F)))
        val ky = thrBottom - (thrBottom - thrTop) * lvl
        rf.set(thrX - 24f * u, ky, thrX + 24f * u, thrBottom)
        c.drawRoundRect(rf, 24f * u, 24f * u, fillP(if (lvl > 0.85f) C(0xFFFF7043) else C(0xFF66BB6A)))
        for (k in 0..4) {
            val ty0 = thrBottom - (thrBottom - thrTop) * k / 4f
            c.drawLine(thrX + 34f * u, ty0, thrX + 56f * u, ty0, strokeP(C(0xFFB0BEC5), 3f * u))
        }
        rf.set(thrX - 66f * u, ky - 32f * u, thrX + 66f * u, ky + 32f * u)
        c.drawRoundRect(rf, 14f * u, 14f * u, fillP(C(0xFFECEFF1)))
        c.drawRoundRect(rf, 14f * u, 14f * u, strokeP(C(0xFF263238), 4f * u))
        txt(c, "${(lvl * 100).toInt()}%", thrX, ky + 11f * u, 32f * u, C(0xFF263238), Paint.Align.CENTER)
        txt(c, "0", thrX - 80f * u, thrBottom + 10f * u, 22f * u, C(0xFFB0BEC5), Paint.Align.RIGHT)

        // brake assist
        if (pl.phase == Phase.RUN && pl.v > 3.0) {
            val ratio = pl.brakeRatio().toFloat()
            val bw = 560f * u
            val bx0 = W / 2f - bw / 2f
            val byy = H - 92f * u
            panel(c, bx0 - 24f * u, byy - 80f * u, bx0 + bw + 24f * u, byy + 62f * u)
            txt(c, "BRAKE POINT", W / 2f, byy - 44f * u, 24f * u, C(0xFFCFD8DC), Paint.Align.CENTER)
            rf.set(bx0, byy - 22f * u, bx0 + bw, byy + 4f * u)
            c.drawRoundRect(rf, 13f * u, 13f * u, fillP(C(0xFF37474F)))
            val col = if (ratio < 0.55f) C(0xFF66BB6A) else if (ratio < 0.85f) C(0xFFFFCA28) else C(0xFFEF5350)
            rf.set(bx0, byy - 22f * u, bx0 + bw * min(1f, ratio / 1.1f), byy + 4f * u)
            c.drawRoundRect(rf, 13f * u, 13f * u, fillP(col))
            val stopD = pl.v * pl.v / (2.0 * Phys.BRAKE)
            txt(c, "station " + fmtDist(pl.distToMark()) + "   |   rukne me " + fmtDist(stopD), W / 2f, byy + 42f * u, 25f * u, C(0xFFFFFFFF), Paint.Align.CENTER)
            if (ratio > 0.85f && ((anim * 3f).toInt() % 2 == 0)) {
                txt(c, "BRAKE LAGAO!", W / 2f, H * 0.33f, 74f * u, C(0xFFFF5252), Paint.Align.CENTER)
            }
        }

        // countdown / messages
        if (!r.started) {
            val n = ceil(r.countdown).toInt() - 1
            txt(c, if (n > 0) n.toString() else "GO!", W / 2f, H * 0.42f, 230f * u, C(0xFFFFFFFF), Paint.Align.CENTER)
            txt(c, "Daayein: POWER lever upar karo    Baayein: BRAKE    Har station par STOP ZONE me ruko", W / 2f, H * 0.52f, 32f * u, C(0xFFFFFFFF), Paint.Align.CENTER)
        } else if (r.sinceStart < 1.2) {
            txt(c, "GO!", W / 2f, H * 0.42f, 230f * u, C(0xFFA5D6A7), Paint.Align.CENTER)
        } else if (pl.phase == Phase.DWELL) {
            panel(c, W / 2f - 420f * u, H * 0.27f, W / 2f + 420f * u, H * 0.50f)
            txt(c, pl.track.stations[min(pl.next, tk.stations.size - 1)].name + " station", W / 2f, H * 0.27f + 78f * u, 62f * u, C(0xFFFFFFFF), Paint.Align.CENTER)
            txt(c, "Gate khule hain... " + ceil(pl.dwellLeft).toInt() + "s", W / 2f, H * 0.27f + 136f * u, 40f * u, C(0xFFFFCA28), Paint.Align.CENTER)
        } else if (pl.msgT > 0.0) {
            val a = min(1.0, pl.msgT).toFloat()
            val w = 900f * u
            rf.set(W / 2f - w / 2f, H * 0.27f, W / 2f + w / 2f, H * 0.27f + 96f * u)
            c.drawRoundRect(rf, 18f * u, 18f * u, fillP(C(0x99101820)).also { it.alpha = (a * 150).toInt() })
            txt(c, pl.msg, W / 2f, H * 0.27f + 64f * u, 42f * u, (C(0xFFFFFFFF) and 0x00FFFFFF) or ((a * 255).toInt() shl 24), Paint.Align.CENTER)
        }
        if (pl.phase == Phase.RUN && pl.v < 0.5 && pl.distToMark() > 0 && pl.distToMark() < Phys.ZONE + 400.0 && pl.x < tk.stations[min(pl.next, tk.stations.size - 1)].mark - Phys.ZONE && pl.next > 0 && r.started) {
            txt(c, "Thoda aage badho - STOP ZONE tak", W / 2f, H * 0.36f, 40f * u, C(0xFFFFCA28), Paint.Align.CENTER)
        }
    }

    private fun drawPause(c: Canvas) {
        c.drawRect(0f, 0f, W, H, fillP(C(0xAA000000)))
        txt(c, "PAUSE", W / 2f, H * 0.33f, 120f * u, C(0xFFFFFFFF), Paint.Align.CENTER)
        button(c, "resume", W / 2f, H * 0.52f, 620f * u, 130f * u, "Jaari rakho", null, true)
        button(c, "quit", W / 2f, H * 0.70f, 620f * u, 130f * u, "Menu", null, false)
    }

    private fun drawResult(c: Canvas, r: Race) {
        c.drawRect(0f, 0f, W, H, fillP(C(0xBB000000)))
        val win = r.playerWon
        panel(c, W / 2f - 620f * u, H * 0.10f, W / 2f + 620f * u, H * 0.90f)
        txt(c, if (win) "JEET GAYE!" else "AI JEET GAYI", W / 2f, H * 0.10f + 130f * u, 110f * u,
            if (win) C(0xFF81C784) else C(0xFFEF5350), Paint.Align.CENTER)
        val x1 = W / 2f - 480f * u
        val x2 = W / 2f + 480f * u
        var y = H * 0.10f + 230f * u
        val rows = ArrayList<Pair<String, String>>()
        rows.add(Pair("Aapka time", fmt(r.playerTime) + (if (r.player.penalty > 0) "  (penalty +" + r.player.penalty.toInt() + "s)" else "")))
        rows.add(Pair("AI ka time", if (r.aiTime > 1e8) "--" else fmt(r.aiTime)))
        rows.add(Pair("Top speed", (r.player.topSpeed * 3.6).toInt().toString() + " km/h"))
        rows.add(Pair("Route", r.route.label + " - " + r.route.stations + " station, " + String.format("%.1f", r.route.km()) + " km"))
        val best = host.getBest(bestKey())
        rows.add(Pair("Best time", if (best < 0) "--" else fmt(best) + (if (newBest) "   NAYA RECORD!" else "")))
        for (row in rows) {
            txt(c, row.first, x1, y, 40f * u, C(0xFFB0BEC5), Paint.Align.LEFT, false)
            txt(c, row.second, x2, y, 44f * u, C(0xFFFFFFFF), Paint.Align.RIGHT)
            y += 74f * u
        }
        button(c, "again", W / 2f - 280f * u, H * 0.90f - 100f * u, 500f * u, 120f * u, "Phir se", null, true)
        button(c, "menu", W / 2f + 280f * u, H * 0.90f - 100f * u, 500f * u, 120f * u, "Menu", null, false)
    }

    // ── menu ────────────────────────────────────────────────────────────
    private fun button(
        c: Canvas, id: String, cx: Float, cy: Float, w: Float, h: Float,
        label: String, sub: String?, selected: Boolean
    ) {
        val r = RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        btns.add(Btn(id, r))
        c.drawRoundRect(r, 22f * u, 22f * u, fillP(if (selected) C(0xFFFFB300) else C(0xFF2A3F5A)))
        c.drawRoundRect(r, 22f * u, 22f * u, strokeP(if (selected) C(0xFFFFF3C4) else C(0xFF5C7A99), 4f * u))
        val fg = if (selected) C(0xFF1B1B1B) else C(0xFFFFFFFF)
        if (sub == null) {
            txt(c, label, cx, cy + h * 0.16f, h * 0.40f, fg, Paint.Align.CENTER)
        } else {
            txt(c, label, cx, cy - h * 0.04f, h * 0.36f, fg, Paint.Align.CENTER)
            txt(c, sub, cx, cy + h * 0.30f, h * 0.22f, if (selected) C(0xFF3E2F00) else C(0xFFB0BEC5), Paint.Align.CENTER, false)
        }
    }

    private fun drawMenu(c: Canvas) {
        c.drawRect(0f, 0f, W, H, fillP(C(0x55000000)))
        panel(c, W / 2f - 760f * u, 60f * u, W / 2f + 760f * u, H - 60f * u)
        txt(c, "TRAIN RACING", W / 2f, 190f * u, 120f * u, C(0xFFFFFFFF), Paint.Align.CENTER)
        txt(c, "Pahad, chadhai, dhalaan - AI ke saath 2 train ki daud", W / 2f, 252f * u, 34f * u, C(0xFFB0BEC5), Paint.Align.CENTER, false)

        txt(c, "ROUTE", W / 2f - 690f * u, 335f * u, 30f * u, C(0xFFFFCA28))
        for ((i, rt) in Route.values().withIndex()) {
            button(c, "r$i", W / 2f + (i - 1) * 470f * u, 405f * u, 440f * u, 110f * u, rt.label,
                "${rt.stations} station  -  ${String.format("%.1f", rt.km())} km", rt == route)
        }
        txt(c, "AI LEVEL", W / 2f - 690f * u, 520f * u, 30f * u, C(0xFFFFCA28))
        for ((i, lv) in Level.values().withIndex()) {
            button(c, "l$i", W / 2f + (i - 1) * 470f * u, 590f * u, 440f * u, 100f * u, lv.label, null, lv == level)
        }
        button(c, "start", W / 2f, 780f * u, 760f * u, 150f * u, "START RACE", null, true)

        val best = host.getBest(bestKey())
        txt(c, if (best < 0) "Best time: --" else "Best time: " + fmt(best), W / 2f, 905f * u, 34f * u, C(0xFFFFFFFF), Paint.Align.CENTER)
        txt(c, "Har station par STOP ZONE me ruko  -  overrun par +${Phys.PENALTY.toInt()}s penalty", W / 2f, 955f * u, 26f * u, C(0xFFB0BEC5), Paint.Align.CENTER, false)

        // update
        button(c, "upd", W - 250f * u, H - 130f * u, 380f * u, 80f * u, "Update check", null, false)
        val ver = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
        txt(c, "v$ver", 40f * u, H - 40f * u, 26f * u, C(0xFFECEFF1), Paint.Align.LEFT, false)
        if (updateStatus.isNotEmpty()) {
            txt(c, updateStatus, W - 250f * u, H - 60f * u, 24f * u, C(0xFFFFFFFF), Paint.Align.CENTER, false)
        }
    }
}

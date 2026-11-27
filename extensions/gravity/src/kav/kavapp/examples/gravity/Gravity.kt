package kav.kavapp.examples.gravity

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.view.Choreographer
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewConfiguration
import kav.kavapp.api.Kav
import kav.kavapp.api.KavExtension
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

// Gravity: shake the phone and Kav's screen comes loose. Its pieces (cards' buttons, rows, texts, the tab bar's tabs, the
// search bar) fall, bounce and slide with the phone's tilt, knocking into each other and the screen's edges; after a few
// seconds, a tap, Back or another shake they fly back to their places, and Kav's own screen is there again as it was.
//
// It is one view over the whole screen (the part "screen.overlay"), which draws nothing and takes no touch until the
// phone is shaken. Then it asks Kav for a picture of the screen and its pieces' boxes (Kav.capture), draws the picture
// over Kav's screen with each piece's place painted over, and draws each piece where the physics (Physics.kt) has it.
// Kav's screen under it isn't touched. The accelerometer is listened to only while the view is on the screen, so only
// while Gravity is on and Kav is in front, and slowly until a shake.
class Gravity : KavExtension {
    private lateinit var kav: Kav

    override fun start(kav: Kav) {
        this.kav = kav
        show()
        // A setting changed: register the part again, so Kav makes the view again with the new settings.
        kav.onSettings { show() }
    }

    private fun show() = kav.part("screen.overlay", "after") { context, _ -> loose(context) }

    private fun loose(c: Context): View? {
        val s = JSONObject(kav.settings())
        if (!s.optBoolean("on", true)) return null
        val strength = when (s.optString("strength", "normal")) { "gentle" -> 0.6f; "strong" -> 1.7f; else -> 1f }
        val back = s.optInt("back", 6).coerceIn(2, 30) * 1000L
        return Loose(c, kav, strength, back)
    }
}

private enum class State { WAIT, ASKING, LOOSE, BACK }

// One piece of the screen: where it was (in the picture's pixels), its body, and where it was when it started back.
private class Piece(val home: Rect, val hole: Int, val body: Body) {
    var fromX = 0f
    var fromY = 0f
    var fromAngle = 0f
    var delay = 0L
}

private class Loose(c: Context, val kav: Kav, val strength: Float, val backAfter: Long) : View(c), SensorEventListener,
    Choreographer.FrameCallback {
    private val sensors = c.getSystemService(Context.SENSOR_SERVICE) as SensorManager?
    private val accelerometer = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val slop = ViewConfiguration.get(c).scaledTouchSlop
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val holePaint = Paint()
    private val dst = RectF()

    private var state = State.WAIT
    private var listening = 0
    private var picture: Bitmap? = null
    private var pieces = emptyList<Piece>()
    private var world = World()
    private var meter = 1f
    private var startedAt = 0L
    private var backAt = 0L
    private var endedAt = 0L
    private var frameAt = 0L
    private var spare = 0f
    private var focusBefore: View? = null

    // The phone's gravity, smoothed, and the times of the last strong jolts.
    private var ax = 0f
    private var ay = 9.8f
    private var sensedAt = 0L
    private val jolts = LongArray(3)
    private var jolt = 0

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        listen()
    }

    override fun onDetachedFromWindow() {
        end()
        listen(off = true)
        super.onDetachedFromWindow()
    }

    // Kav going to the background: everything goes back at once, and the sensor is let go.
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE) end()
        listen(off = visibility != VISIBLE)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        end()
    }

    // Slowly while waiting for a shake (enough to feel one), at a game's pace while pieces move.
    private fun listen(off: Boolean = !isAttachedToWindow || windowVisibility != VISIBLE) {
        val want = when {
            off || accelerometer == null -> 0
            state == State.LOOSE || state == State.BACK -> SensorManager.SENSOR_DELAY_GAME + 1
            else -> SensorManager.SENSOR_DELAY_UI + 1
        }
        if (want == listening) return
        if (listening != 0) sensors?.unregisterListener(this)
        listening = want
        if (want != 0) sensors?.registerListener(this, accelerometer, want - 1)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(e: SensorEvent) {
        val now = SystemClock.uptimeMillis()
        val x = e.values[0]; val y = e.values[1]; val z = e.values[2]
        val dt = if (sensedAt == 0L) 1f else (now - sensedAt) / 1000f
        sensedAt = now
        val k = 1f - exp(-dt / 0.08f)
        // The sensor's x and y as the screen has them: right and down, with the screen turned as it is.
        val (sx, sy) = when (display?.rotation ?: Surface.ROTATION_0) {
            Surface.ROTATION_90 -> y to x
            Surface.ROTATION_180 -> x to -y
            Surface.ROTATION_270 -> -y to -x
            else -> -x to y
        }
        ax += (sx - ax) * k
        ay += (sy - ay) * k
        // A shake: three jolts well past gravity within 800 ms.
        if (abs(hypot(hypot(x, y), z) - SensorManager.GRAVITY_EARTH) > 11f && now - jolts[(jolt + 2) % 3] > 120) {
            jolts[jolt] = now
            jolt = (jolt + 1) % 3
            if (now - jolts[jolt] < 800) {
                jolts.fill(0)
                shaken(now)
            }
        }
    }

    private fun shaken(now: Long) {
        when (state) {
            State.WAIT -> if (now - endedAt > 800) ask()
            State.LOOSE -> if (now - startedAt > 1000) goBack(now)
            else -> {}
        }
    }

    private fun ask() {
        if (width == 0 || height == 0) return
        state = State.ASKING
        kav.capture { shot, boxes ->
            if (state != State.ASKING || !isAttachedToWindow || shot == null) {
                shot?.recycle()
                if (state == State.ASKING) state = State.WAIT
                return@capture
            }
            fall(shot, JSONArray(boxes))
        }
    }

    // The pieces come loose: a body for each, the screen's edges as walls, and a little push to start them off.
    private fun fall(shot: Bitmap, boxes: JSONArray) {
        picture = shot
        meter = shot.height / 6f
        world = World().apply { iterations = 10 }
        val kept = ArrayList<Piece>()
        for (k in 0 until boxes.length()) {
            val b = boxes.getJSONObject(k)
            val r = Rect(b.getInt("x"), b.getInt("y"), b.getInt("x") + b.getInt("w"), b.getInt("y") + b.getInt("h"))
            if (!r.intersect(0, 0, shot.width, shot.height) || r.width() < 4 || r.height() < 4) continue
            // A piece mostly on one already taken is part of it.
            if (kept.any { overlap(it.home, r) > 0.5f * r.width() * r.height() }) continue
            val w = r.width() / meter; val h = r.height() / meter
            val body = Body(w, h, w * h).apply {
                x = r.exactCenterX() / meter
                y = r.exactCenterY() / meter
                friction = 0.5f
                bounce = 0.35f
                vx = (Random.nextFloat() - 0.5f) * 1.6f * strength
                vy = -Random.nextFloat() * 1.4f * strength
                spin = (Random.nextFloat() - 0.5f) * 3f * strength
            }
            kept += Piece(r, around(shot, r), body)
            world.add(body)
        }
        if (kept.isEmpty()) { shot.recycle(); picture = null; state = State.WAIT; return }
        val wm = shot.width / meter; val hm = shot.height / meter; val t = 4f
        fun wall(x: Float, y: Float, w: Float, h: Float) = world.add(Body(w, h, Float.POSITIVE_INFINITY).apply {
            this.x = x; this.y = y; friction = 0.5f; bounce = 0.3f
        })
        wall(wm / 2, -t / 2, wm + 2 * t, t)
        wall(wm / 2, hm + t / 2, wm + 2 * t, t)
        wall(-t / 2, hm / 2, t, hm + 2 * t)
        wall(wm + t / 2, hm / 2, t, hm + 2 * t)
        pieces = kept
        state = State.LOOSE
        startedAt = SystemClock.uptimeMillis()
        spare = 0f
        // Every touch and Back are this view's while the pieces are loose, so none reaches Kav's screen under it.
        focusBefore = rootView.findFocus()
        isFocusable = true
        isFocusableInTouchMode = true
        requestFocus()
        listen()
        frameAt = System.nanoTime()
        Choreographer.getInstance().postFrameCallback(this)
        invalidate()
    }

    private fun overlap(a: Rect, b: Rect): Int {
        val w = min(a.right, b.right) - max(a.left, b.left)
        val h = min(a.bottom, b.bottom) - max(a.top, b.top)
        return if (w > 0 && h > 0) w * h else 0
    }

    // The color the screen has around a piece, for the place it leaves: the most common of a few pixels just outside it.
    private fun around(shot: Bitmap, r: Rect): Int {
        val seen = HashMap<Int, Int>()
        val xs = intArrayOf(r.left - 3, r.centerX(), r.right + 2)
        val ys = intArrayOf(r.top - 3, r.centerY(), r.bottom + 2)
        for (x in xs) for (y in ys) {
            if (x == r.centerX() && y == r.centerY()) continue
            if (x < 0 || y < 0 || x >= shot.width || y >= shot.height) continue
            val p = shot.getPixel(x, y)
            seen[p] = (seen[p] ?: 0) + 1
        }
        return seen.maxByOrNull { it.value }?.key ?: shot.getPixel(r.centerX().coerceIn(0, shot.width - 1), r.centerY().coerceIn(0, shot.height - 1))
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (state != State.LOOSE && state != State.BACK) return
        val now = SystemClock.uptimeMillis()
        val dt = min(0.05f, max(0f, (frameTimeNanos - frameAt) / 1e9f))
        frameAt = frameTimeNanos
        if (state == State.LOOSE) {
            world.gx = ax * strength
            world.gy = ay * strength
            spare += dt
            var steps = 0
            while (spare >= STEP && steps < 4) { world.step(STEP); spare -= STEP; steps++ }
            if (steps == 4) spare = 0f
            if (now - startedAt > backAfter) goBack(now)
        } else if (now - backAt > BACK_MS + STAGGER_MS) {
            end()
            return
        }
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    // Each piece flies home from where it is, the ones higher on the screen a little first.
    private fun goBack(now: Long) {
        if (state != State.LOOSE) return
        state = State.BACK
        backAt = now
        val top = pieces.minOf { it.home.top }.toFloat()
        val span = max(1f, pieces.maxOf { it.home.top } - top)
        for (p in pieces) {
            p.fromX = p.body.x * meter
            p.fromY = p.body.y * meter
            p.fromAngle = atan2(sin(p.body.angle), cos(p.body.angle))
            p.delay = ((p.home.top - top) / span * STAGGER_MS).toLong()
        }
    }

    // Done: nothing drawn, no touch or Back taken, the picture let go.
    private fun end() {
        if (state == State.WAIT) return
        val was = state
        state = State.WAIT
        if (was == State.ASKING) return
        Choreographer.getInstance().removeFrameCallback(this)
        endedAt = SystemClock.uptimeMillis()
        pieces = emptyList()
        world = World()
        picture?.recycle()
        picture = null
        if (hasFocus()) {
            isFocusable = false
            val before = focusBefore
            if (before != null && before.isAttachedToWindow) before.requestFocus() else clearFocus()
        }
        isFocusable = false
        isFocusableInTouchMode = false
        focusBefore = null
        listen()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val shot = picture ?: return
        if (state != State.LOOSE && state != State.BACK) return
        canvas.drawBitmap(shot, 0f, 0f, null)
        for (p in pieces) { holePaint.color = p.hole; canvas.drawRect(p.home, holePaint) }
        val now = SystemClock.uptimeMillis()
        for (p in pieces) {
            val cx: Float; val cy: Float; val deg: Float
            if (state == State.BACK) {
                val t = ((now - backAt - p.delay).toFloat() / BACK_MS).coerceIn(0f, 1f)
                val e = outBack(t)
                cx = p.fromX + (p.home.exactCenterX() - p.fromX) * e
                cy = p.fromY + (p.home.exactCenterY() - p.fromY) * e
                deg = (p.fromAngle * (1f - min(1f, e)) * 180f / PI).toFloat()
            } else {
                cx = p.body.x * meter
                cy = p.body.y * meter
                deg = (p.body.angle * 180f / PI).toFloat()
            }
            val hw = p.home.width() / 2f; val hh = p.home.height() / 2f
            canvas.save()
            canvas.translate(cx, cy)
            canvas.rotate(deg)
            dst.set(-hw, -hh, hw, hh)
            canvas.drawBitmap(shot, p.home, dst, paint)
            canvas.restore()
        }
    }

    private fun outBack(t: Float): Float {
        val s = 1.4f
        val u = t - 1f
        return 1f + (s + 1f) * u * u * u + s * u * u
    }

    private var downX = 0f
    private var downY = 0f

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (state != State.LOOSE && state != State.BACK) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y }
            MotionEvent.ACTION_UP -> if (hypot(e.x - downX, e.y - downY) < slop) performClick()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        goBack(SystemClock.uptimeMillis())
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && (state == State.LOOSE || state == State.BACK)) {
            goBack(SystemClock.uptimeMillis())
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && (state == State.LOOSE || state == State.BACK)) return true
        return super.onKeyUp(keyCode, event)
    }

    companion object {
        const val STEP = 1f / 120f
        const val BACK_MS = 450L
        const val STAGGER_MS = 160L
    }
}

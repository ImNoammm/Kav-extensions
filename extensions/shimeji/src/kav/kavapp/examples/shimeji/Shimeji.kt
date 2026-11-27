package kav.kavapp.examples.shimeji

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import org.json.JSONObject
import kav.kavapp.api.Kav
import kav.kavapp.api.KavExtension
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

// Shimeji: a little bus that lives on top of Kav's tab bar. It walks along it, stops to look around, naps when nobody
// has touched it for a while, hops when tapped, and can be carried to another spot. It is one view, drawn with Android's
// own Canvas, added before Kav's "tabbar" screen part: Kav shows that part on every tab, so the bus is there wherever
// the rider is. Kav makes the view again when the tab changes, so where the bus is and what it is doing are kept here,
// not in the view, and the next view carries on from there.
class Shimeji : KavExtension {
    private lateinit var kav: Kav
    private val world = World()

    override fun start(kav: Kav) {
        this.kav = kav
        show()
        // A setting changed: register the part again, so Kav makes the view again with the new size or place.
        kav.onSettings { show() }
    }

    private fun show() = kav.part("tabbar", "before") { context, about -> buddy(context, JSONObject(about).optString("tab")) }

    private fun buddy(c: Context, tab: String): View? {
        val s = JSONObject(kav.settings())
        // Home is Kav's first tab, "directions".
        if (s.optString("where", "all") == "home" && tab != "directions") return null
        val height = when (s.optString("size", "medium")) { "small" -> 24f; "large" -> 40f; else -> 32f }
        val ui = kav.ui()
        val look = Look(
            body = ui.color("accent").takeIf { it != 0 } ?: 0xFF3D7BFF.toInt(),
            ink = ui.color("text").takeIf { it != 0 } ?: Color.BLACK,
            soft = ui.color("textSecondary").takeIf { it != 0 } ?: Color.GRAY,
        )
        world.naps = s.optBoolean("naps", true)
        return Buddy(c, world, look, height, if (kav.locale() == "he") "האוטובוס הקטן" else "The little bus")
    }
}

private class Look(val body: Int, val ink: Int, val soft: Int)

private enum class Mode { WALK, LOOK, SLEEP, HOP, HELD }

// What the bus is doing, kept between views. Times are SystemClock.uptimeMillis(); x is where it stands, from 0 (the
// left end) to 1 (the right end), so it stays put when the view is made again.
private class World {
    var x = 0.3f
    var facing = 1
    var mode = Mode.LOOK
    var until = 0L
    var since = 0L
    var lastPoke = SystemClock.uptimeMillis()
    var stride = 0f
    var heartAt = 0L
    var startledAt = 0L
    var lookAt = 0f
    var blinkAt = 0L
    var naps = true
    // The view that moves the bus; another one still on screen for a moment only draws it.
    var owner: View? = null

    fun set(mode: Mode, now: Long, forMs: Long) {
        this.mode = mode
        since = now
        until = now + forMs
    }

    // One step of time: walking moves the bus and turns it at the ends; each mode ends in the next one.
    fun step(now: Long, dt: Float, speed: Float) {
        if (now > blinkAt) blinkAt = now + 2_000 + Random.nextLong(3_000)
        when (mode) {
            Mode.WALK -> {
                x += facing * speed * dt
                stride += dt * 9f
                if (x < 0f || x > 1f) { x = x.coerceIn(0f, 1f); facing = -facing }
                if (now > until) rest(now)
            }
            Mode.LOOK -> {
                if (Random.nextFloat() < dt * 0.8f) lookAt = Random.nextFloat() * 2f - 1f
                if (now > until) walk(now)
            }
            Mode.SLEEP -> if (now > until) { lastPoke = now; set(Mode.LOOK, now, 2_000) }
            Mode.HOP -> if (now > until) set(Mode.LOOK, now, 1_500)
            Mode.HELD -> stride += dt * 20f
        }
    }

    private fun rest(now: Long) {
        if (naps && now - lastPoke > 40_000 && Random.nextFloat() < 0.5f) set(Mode.SLEEP, now, 20_000 + Random.nextLong(20_000))
        else set(Mode.LOOK, now, 2_000 + Random.nextLong(4_000))
    }

    private fun walk(now: Long) {
        if (Random.nextFloat() < 0.4f) facing = -facing
        if (x <= 0.02f) facing = 1
        if (x >= 0.98f) facing = -1
        lookAt = 0f
        set(Mode.WALK, now, 3_000 + Random.nextLong(5_000))
    }

    fun poke(now: Long) {
        if (mode == Mode.SLEEP) startledAt = now else heartAt = now
        lastPoke = now
        set(Mode.HOP, now, HOP_MS)
    }

    fun drop(now: Long) {
        lastPoke = now
        set(Mode.HOP, now, HOP_MS / 2)
    }

    companion object {
        const val HOP_MS = 600L
    }
}

private class Buddy(c: Context, val world: World, val look: Look, heightDp: Float, words: String) : View(c) {
    private val density = resources.displayMetrics.density
    // The bus's height; everything it draws is measured in u, a 32nd of it.
    private val h = heightDp * density
    private val u = h / 32f
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFakeBoldText = true; textAlign = Paint.Align.CENTER }
    private val box = RectF()
    private val heart = Path()
    private val slop = ViewConfiguration.get(c).scaledTouchSlop
    private var frameAt = 0L
    private var running = false
    private var downX = 0f
    private var dragging = false

    init {
        contentDescription = words
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Room for the bus, its hop and what floats over its head.
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (h * 1.5f).toInt())
    }

    // The ends it walks between: half a bus in from each side.
    private fun left() = 22 * u
    private fun span() = max(1f, width - 44 * u)
    private fun at() = left() + world.x * span()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        world.owner = this
        go()
    }

    override fun onDetachedFromWindow() {
        if (world.owner === this) world.owner = null
        running = false
        super.onDetachedFromWindow()
    }

    // Kav in the background: nothing moves, and nothing is drawn.
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) go() else running = false
    }

    private fun go() {
        if (running || !isAttachedToWindow) return
        running = true
        frameAt = SystemClock.uptimeMillis()
        postOnAnimation(tick)
    }

    // Each frame while it moves; about 20 times a second while it stands or sleeps, which is enough for a blink.
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.uptimeMillis()
            val dt = min(0.05f, (now - frameAt) / 1000f)
            frameAt = now
            if (world.owner == null) world.owner = this@Buddy
            // Walks about two thirds of its own length a second, whatever the screen's width.
            if (world.owner === this@Buddy) world.step(now, dt, 0.7f * 40 * u / span())
            invalidate()
            val busy = world.mode == Mode.WALK || world.mode == Mode.HOP || world.mode == Mode.HELD ||
                now - world.heartAt < 900 || now - world.startledAt < 900
            if (busy) postOnAnimation(this) else postDelayed(this, 50)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val now = SystemClock.uptimeMillis()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Only a touch on the bus is its own.
                if (abs(e.x - at()) > 24 * u || e.y < height - 34 * u - lift(now)) return false
                downX = e.x
                dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(e.x - downX) > slop) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    world.set(Mode.HELD, now, Long.MAX_VALUE)
                }
                if (dragging) {
                    val to = ((e.x - left()) / span()).coerceIn(0f, 1f)
                    if (abs(to - world.x) * span() > 1) world.facing = if (to > world.x) 1 else -1
                    world.x = to
                    go()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) world.drop(now) else performClick()
                dragging = false
                go()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) world.drop(now)
                dragging = false
                return true
            }
        }
        return false
    }

    override fun performClick(): Boolean {
        super.performClick()
        world.poke(SystemClock.uptimeMillis())
        go()
        return true
    }

    // How high it is off the ground: a hop's arc, or held up by the finger.
    private fun lift(now: Long): Float = when (world.mode) {
        Mode.HOP -> {
            val t = ((now - world.since).toFloat() / max(1L, world.until - world.since)).coerceIn(0f, 1f)
            if (world.until - world.since < World.HOP_MS) 10 * u * (1 - t) * (1 - t) else 16 * u * 4 * t * (1 - t)
        }
        Mode.HELD -> 10 * u
        else -> 0f
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val ground = height - 3 * u
        val x = at()
        val up = lift(now)
        val asleep = world.mode == Mode.SLEEP
        val sits = world.mode == Mode.LOOK || asleep

        // Its shadow, smaller the higher it is.
        val shade = 1f - min(0.6f, up / (24 * u))
        fill.color = alpha(look.ink, 0.16f)
        box.set(x - 17 * u * shade, ground - 1.6f * u, x + 17 * u * shade, ground + 1.6f * u)
        canvas.drawOval(box, fill)

        canvas.save()
        canvas.translate(x, ground - up)
        if (world.facing < 0) canvas.scale(-1f, 1f)
        drawBus(canvas, now, sits, asleep)
        canvas.restore()

        val head = ground - up - 32 * u
        // Asleep: z's float up behind it.
        if (asleep) {
            text.color = look.soft
            for (i in 0 until 3) {
                val t = ((now - world.since) / 1400f + i / 3f) % 1f
                text.textSize = (5 + 4 * t) * u
                text.alpha = (255 * (1 - t)).toInt()
                canvas.drawText("z", x - world.facing * (8 + 10 * t) * u, head + 6 * u - t * 12 * u, text)
            }
            text.alpha = 255
        }
        // Tapped: a heart rises and fades. Woken: an exclamation mark.
        val love = now - world.heartAt
        if (love in 0 until 900) {
            val t = love / 900f
            fill.color = alpha(0xFFFF4F6D.toInt(), 1 - t)
            drawHeart(canvas, x, head - t * 6 * u, 4 * u)
        }
        val woke = now - world.startledAt
        if (woke in 0 until 900) {
            text.color = look.ink
            text.textSize = 10 * u
            canvas.drawText("!", x, head - 2 * u, text)
        }
    }

    // The bus facing right, its wheels on y 0. Its legs end in wheels; sitting, it rests on them.
    private fun drawBus(canvas: Canvas, now: Long, sits: Boolean, asleep: Boolean) {
        val legs = if (sits) 1 * u else 6 * u
        val bottom = -3 * u - legs
        val top = bottom - 22 * u
        val dark = 0xFF26262B.toInt()
        // Legs and wheels in the text color, so they show on a dark screen too.
        val wheel = mix(look.ink, dark, 0.3f)

        line.color = wheel
        line.strokeWidth = 2.4f * u
        fill.color = wheel
        for (i in 0 until 2) {
            val hipX = if (i == 0) -10 * u else 10 * u
            val walking = world.mode == Mode.WALK || world.mode == Mode.HELD
            val phase = world.stride + i * PI.toFloat()
            val swing = if (walking) sin(phase) * 3 * u else 0f
            val raise = if (walking) max(0f, cos(phase)) * 2 * u else 0f
            canvas.drawLine(hipX, bottom, hipX + swing, -3 * u - raise, line)
            canvas.drawCircle(hipX + swing, -3 * u - raise, 3 * u, fill)
        }

        // A little bob while it walks, a slow breath while it sleeps.
        val bob = when (world.mode) {
            Mode.WALK -> abs(sin(world.stride)) * -1.2f * u
            Mode.SLEEP -> sin((now - world.since) / 600f) * 0.8f * u
            else -> 0f
        }
        canvas.save()
        canvas.translate(0f, bob)

        fill.color = look.body
        box.set(-20 * u, top, 20 * u, bottom)
        canvas.drawRoundRect(box, 6 * u, 6 * u, fill)
        line.color = alpha(dark, 0.35f)
        line.strokeWidth = 1.2f * u
        canvas.drawRoundRect(box, 6 * u, 6 * u, line)
        // The sign on its forehead.
        fill.color = mix(look.body, Color.WHITE, 0.45f)
        box.set(-15 * u, top + 2.5f * u, 15 * u, top + 6.5f * u)
        canvas.drawRoundRect(box, 2 * u, 2 * u, fill)
        // A headlight at the front, and the bumper under it.
        fill.color = 0xFFFFD54F.toInt()
        canvas.drawCircle(17 * u, bottom - 4.5f * u, 2 * u, fill)
        line.color = alpha(dark, 0.5f)
        line.strokeWidth = 1.6f * u
        canvas.drawLine(-16 * u, bottom - 1.2f * u, 16 * u, bottom - 1.2f * u, line)

        // Its front windows are its eyes.
        val blink = world.blinkAt - now in 0 until 140
        for (ex in floatArrayOf(-3 * u, 9 * u)) {
            if (asleep || blink) {
                line.color = dark
                line.strokeWidth = 1.4f * u
                canvas.drawLine(ex - 3.5f * u, top + 13 * u, ex + 3.5f * u, top + 13 * u, line)
            } else {
                fill.color = Color.WHITE
                box.set(ex - 4.5f * u, top + 8.5f * u, ex + 4.5f * u, top + 17.5f * u)
                canvas.drawRoundRect(box, 3 * u, 3 * u, fill)
                fill.color = dark
                val lookX = (1.5f + world.lookAt * 1.2f) * u
                canvas.drawCircle(ex + lookX, top + 13.5f * u, 2.2f * u, fill)
            }
        }
        // Its mouth: a smile, open when it hops.
        if (world.mode == Mode.HOP || world.mode == Mode.HELD) {
            fill.color = dark
            canvas.drawCircle(3 * u, top + 19.8f * u, 1.4f * u, fill)
        } else if (!asleep) {
            line.color = dark
            line.strokeWidth = 1.2f * u
            box.set(1 * u, top + 17.5f * u, 5 * u, top + 20.5f * u)
            canvas.drawArc(box, 20f, 140f, false, line)
        }
        canvas.restore()
    }

    private fun drawHeart(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        heart.reset()
        heart.moveTo(cx, cy + r)
        heart.cubicTo(cx - 2.2f * r, cy - 0.2f * r, cx - 0.9f * r, cy - 1.6f * r, cx, cy - 0.5f * r)
        heart.cubicTo(cx + 0.9f * r, cy - 1.6f * r, cx + 2.2f * r, cy - 0.2f * r, cx, cy + r)
        canvas.drawPath(heart, fill)
    }

    private fun alpha(color: Int, a: Float) = (color and 0x00FFFFFF) or ((255 * a.coerceIn(0f, 1f)).toInt() shl 24)

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int) = ((((a shr shift) and 0xFF) * (1 - t) + ((b shr shift) and 0xFF) * t).toInt() and 0xFF) shl shift
        return (0xFF shl 24) or ch(16) or ch(8) or ch(0)
    }
}

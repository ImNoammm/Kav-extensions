package kav.kavapp.examples.shimeji

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import org.json.JSONArray
import org.json.JSONObject
import kav.kavapp.api.Kav
import kav.kavapp.api.KavExtension
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// Shimeji: a little bus that lives on Kav's screen. It walks along the bottom, climbs the screen's sides, hangs upside
// down from the top and crawls along it, sits and walks on Kav's cards, looks around, sits, naps when nobody has
// touched it for a while, and hops when tapped. Pick it up and it dangles from your finger; let go with a flick and it
// flies, tumbles, bounces off the sides and lands (a hard landing leaves it dizzy). The brain and the physics are in
// World.kt; this file is the view that draws it, drawn with Android's own Canvas in Kav's colors.
//
// It is one view over the whole screen (the part "screen.overlay"). It takes only a touch that starts on the bus, so
// everywhere else Kav works as if it weren't there. Where Kav's cards are it learns from Kav.capture: only the pieces'
// boxes are used, and of the picture only a few points along each box's top, to tell an edge that shows from a
// see-through button; then the picture is let go. It asks again after the rider touches the screen (the screen may
// scroll or change) and every second and a half. Kav makes the view again when the tab changes; what the bus is
// doing is kept in the World, and the next view carries on from there.
class Shimeji : KavExtension {
    private lateinit var kav: Kav
    private val world = World()

    override fun start(kav: Kav) {
        this.kav = kav
        show()
        // A setting changed: register the part again, so Kav makes the view again with the new size or tabs.
        kav.onSettings { show() }
    }

    private fun show() = kav.part("screen.overlay", "after") { context, about -> buddy(context, JSONObject(about).optString("tab")) }

    private fun buddy(c: Context, tab: String): View? {
        val s = JSONObject(kav.settings())
        // Home is Kav's first tab, "directions". A Kav that doesn't say which tab shows has it everywhere.
        if (s.optString("where", "all") == "home" && tab.isNotEmpty() && tab != "directions") return null
        val height = when (s.optString("size", "medium")) { "small" -> 24f; "large" -> 40f; else -> 32f }
        world.naps = s.optBoolean("naps", true)
        return Buddy(c, kav, world, height)
    }
}

private class Look(val body: Int, val ink: Int, val soft: Int)

private class Buddy(c: Context, val kav: Kav, val world: World, heightDp: Float) : View(c) {
    private val density = resources.displayMetrics.density
    // The bus's height; everything it draws is measured in u, a 32nd of it.
    private val u = heightDp * density / 32f
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFakeBoldText = true; textAlign = Paint.Align.CENTER }
    private val box = RectF()
    private val path = Path()
    private val slop = ViewConfiguration.get(c).scaledTouchSlop
    private var look = colors()
    private var frameAt = 0L
    private var running = false

    // Kav's pieces: asked for now and then, and often for a moment after a touch elsewhere.
    private var asking = false
    private var askedAt = 0L
    private var touchedAt = Long.MIN_VALUE / 2

    // A touch on the bus: where it went down, whether the bus is in the hand yet, and the finger's speed.
    private var downAt = 0L
    private var downX = 0f
    private var downY = 0f
    private var holding = false
    private var touching = false
    private var speed: VelocityTracker? = null

    init {
        // One view over the whole screen: nothing for a screen reader to find in it.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        world.u = u
        world.dp = density
    }

    private fun colors(): Look {
        val ui = kav.ui()
        return Look(
            body = ui.color("accent").takeIf { it != 0 } ?: 0xFF3D7BFF.toInt(),
            ink = ui.color("text").takeIf { it != 0 } ?: Color.BLACK,
            soft = ui.color("textSecondary").takeIf { it != 0 } ?: Color.GRAY,
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        world.owner = this
        go()
    }

    override fun onDetachedFromWindow() {
        if (world.owner === this) world.owner = null
        running = false
        speed?.recycle(); speed = null
        super.onDetachedFromWindow()
    }

    // Kav in the background: nothing moves, nothing is drawn, nothing is asked.
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) go() else running = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        edges()
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        post { edges() }
        return super.onApplyWindowInsets(insets)
    }

    // The top and bottom edges it uses: inside the status bar and the navigation bar.
    private fun edges() {
        if (width == 0 || height == 0) return
        val i = rootWindowInsets
        val (top, bottom) = when {
            i == null -> 0 to 0
            Build.VERSION.SDK_INT >= 30 -> i.getInsets(WindowInsets.Type.systemBars()).let { it.top to it.bottom }
            else -> @Suppress("DEPRECATION") (i.systemWindowInsetTop to i.systemWindowInsetBottom)
        }
        world.bounds(width.toFloat(), height.toFloat(), top.toFloat(), (height - bottom).toFloat(), SystemClock.uptimeMillis())
    }

    // Moving again, at once (a touch changed what it does).
    private fun go() {
        if (!isAttachedToWindow || windowVisibility != VISIBLE) return
        if (!running) {
            running = true
            frameAt = SystemClock.uptimeMillis()
            edges()
        }
        removeCallbacks(tick)
        postOnAnimation(tick)
    }

    // Each frame while it moves; about 20 times a second while it stands, sits or sleeps, enough for a blink or a sway.
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.uptimeMillis()
            val dt = min(0.05f, (now - frameAt) / 1000f)
            frameAt = now
            if (world.owner == null) world.owner = this@Buddy
            if (world.owner === this@Buddy) {
                world.step(now, dt)
                ask(now)
            }
            invalidate()
            val busy = world.act == Act.WALK || world.act == Act.HOP || world.act == Act.FALL || world.act == Act.HELD ||
                world.act == Act.LAND || world.act == Act.CROUCH || world.act == Act.DIZZY || world.on == On.TOP ||
                now - world.turnAt < World.TURN_MS || now - world.heartAt < 900 || now - world.startledAt < 900
            if (busy) postOnAnimation(this) else postDelayed(this, 50)
        }
    }

    // Kav's pieces again: every 1.5 s (8 s while it naps on the floor), and up to 8 times a second for 2.5 s after the
    // rider touched the screen elsewhere, while what is under the bus may scroll away.
    private fun ask(now: Long) {
        if (asking) return
        val every = when {
            now - touchedAt < 2_500 -> 120L
            world.act == Act.NAP && world.on == On.FLOOR -> 8_000L
            else -> 1_500L
        }
        if (now - askedAt < every) return
        asking = true
        askedAt = now
        kav.capture { picture, pieces ->
            asking = false
            if (world.owner !== this) { picture?.recycle(); return@capture }
            look = colors()
            val list = JSONArray(pieces)
            val boxes = ArrayList<Box>(list.length())
            for (k in 0 until list.length()) {
                val p = list.getJSONObject(k)
                val x = p.getInt("x").toFloat(); val y = p.getInt("y").toFloat(); val r = x + p.getInt("w")
                // Only where it shows: a see-through button over the map is no ledge. The picture is looked at here
                // and nowhere else.
                val kind = p.optString("kind")
                val seen = kind == "card" || picture != null && edge(x, y, r, world.dp, picture.width, picture.height) { px, py -> picture.getPixel(px, py) }
                boxes += Box(x, y, r, y + p.getInt("h"), kind, seen)
            }
            picture?.recycle()
            // Nothing given (Kav not in front for a moment): keep what it knew.
            if (picture != null) world.pieces(boxes, SystemClock.uptimeMillis())
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val now = SystemClock.uptimeMillis()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Only a touch on the bus is its own; any other goes on to Kav, and the screen may change under it.
                if (!world.hit(e.x, e.y)) { touchedAt = now; return false }
                touching = true
                holding = false
                downAt = now; downX = e.x; downY = e.y
                speed?.recycle()
                speed = VelocityTracker.obtain().also { it.addMovement(e) }
                parent?.requestDisallowInterceptTouchEvent(true)
                postDelayed(pickUp, PICK_MS)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!touching) return false
                speed?.addMovement(e)
                if (!holding && hypot(e.x - downX, e.y - downY) > slop) hold(e.x, e.y)
                if (holding) {
                    speed?.computeCurrentVelocity(1000)
                    world.drag(e.x, e.y, speed?.xVelocity ?: 0f)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!touching) return false
                removeCallbacks(pickUp)
                speed?.addMovement(e)
                if (holding) {
                    val v = speed
                    v?.computeCurrentVelocity(1000, ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat())
                    world.throwAt(v?.xVelocity ?: 0f, v?.yVelocity ?: 0f, now)
                } else performClick()
                end()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (!touching) return false
                removeCallbacks(pickUp)
                if (holding) world.throwAt(0f, 0f, now)
                end()
                return true
            }
        }
        return false
    }

    // Held still on the bus for a moment: it is picked up all the same.
    private val pickUp = Runnable { if (touching && !holding) hold(downX, downY) }

    private fun hold(fx: Float, fy: Float) {
        holding = true
        world.grab(fx, fy, SystemClock.uptimeMillis())
        go()
    }

    private fun end() {
        touching = false
        holding = false
        speed?.recycle(); speed = null
        go()
    }

    override fun performClick(): Boolean {
        super.performClick()
        world.poke(SystemClock.uptimeMillis())
        go()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        if (world.width <= 0f || world.x.isNaN()) return
        val now = SystemClock.uptimeMillis()
        // Its pose: where its feet are and how it is turned, eased for a moment from the last one after a change of place.
        var fx = world.feetX(); var fy = world.feetY(); var a = world.turn()
        val t = (now - world.turnAt).toFloat() / World.TURN_MS
        if (t in 0f..1f) {
            val e = 1 - (1 - t) * (1 - t)
            var d = a - world.fromAngle
            while (d > PI) d -= (2 * PI).toFloat()
            while (d < -PI) d += (2 * PI).toFloat()
            fx = world.fromX + (fx - world.fromX) * e
            fy = world.fromY + (fy - world.fromY) * e
            a = world.fromAngle + d * e
        }
        val up = world.lift(now)
        shadow(canvas, now, fx, fy, a, up)

        canvas.save()
        canvas.translate(fx, fy)
        canvas.rotate((a * 180 / PI).toFloat())
        canvas.translate(0f, -up)
        // A flip on the third tap: a whole turn about its middle, through the hop.
        if (world.flip && world.act == Act.HOP) {
            val p = ((now - world.since).toFloat() / max(1L, world.until - world.since)).coerceIn(0f, 1f)
            canvas.rotate(world.facing * 360f * smooth(p), 0f, -16 * u)
        }
        squash(canvas, now)
        // On an edge its front points along the edge: mirrored for one way.
        val mirror = when (world.on) {
            On.LEFT -> world.facing < 0
            On.RIGHT -> world.facing > 0
            On.TOP -> world.facing > 0
            else -> world.facing < 0
        }
        if (mirror) canvas.scale(-1f, 1f)
        drawBus(canvas, now)
        canvas.restore()

        // What floats over it, always above it on the screen.
        val mx = fx + sin(a) * 16 * u
        val my = fy - cos(a) * 16 * u - up
        over(canvas, now, mx, my - 26 * u)
    }

    // Its shadow on what is under it, smaller and fainter the higher it is; none on an edge or the top.
    private fun shadow(canvas: Canvas, now: Long, fx: Float, fy: Float, a: Float, up: Float) {
        val ground: Float
        val x: Float
        when (world.on) {
            On.FLOOR, On.LEDGE -> { ground = fy; x = fx }
            On.AIR, On.HAND -> {
                x = fx + sin(a) * 16 * u
                val bottom = fy - cos(a) * 16 * u + 16 * u
                ground = world.ledges.filter { x >= it.l && x <= it.r && it.y >= bottom }.minOfOrNull { it.y } ?: world.floor
            }
            else -> return
        }
        val height = max(0f, ground - fy) + up
        val k = 1f - min(0.7f, height / (120 * u))
        fill.color = alpha(look.ink, 0.16f * k)
        box.set(x - 17 * u * k, ground - 1.6f * u, x + 17 * u * k, ground + 1.6f * u)
        canvas.drawOval(box, fill)
        // A hard landing raises two puffs of dust.
        val dust = now - world.squashAt
        if ((world.on == On.FLOOR || world.on == On.LEDGE) && dust in 0 until 400) {
            val p = dust / 400f
            fill.color = alpha(look.soft, 0.45f * (1 - p))
            for (s in intArrayOf(-1, 1)) canvas.drawCircle(x + s * (18 + 10 * p) * u, ground - (2 + 4 * p) * u, (2.5f + 2 * p) * u, fill)
        }
    }

    // Squashed as it lands or bounces, and when it crouches for a jump; about its feet.
    private fun squash(canvas: Canvas, now: Long) {
        val hit = now - world.squashAt
        var s = if (hit in 0 until 260) 0.28f * (1 - hit / 260f) * (1 - hit / 260f) else 0f
        if (world.act == Act.CROUCH) s = max(s, 0.18f * min(1f, (now - world.since) / 120f))
        if (s > 0f) canvas.scale(1 + s * 0.6f, 1 - s)
    }

    // The bus facing right, its wheels on y 0. Its legs end in wheels; sitting, it rests on them.
    private fun drawBus(canvas: Canvas, now: Long) {
        val act = world.act
        val sits = act == Act.SIT || act == Act.NAP || act == Act.DIZZY || act == Act.LAND
        val legs = when { sits -> 1 * u; act == Act.CROUCH -> 2.5f * u; else -> 6 * u }
        val bottom = -3 * u - legs
        val top = bottom - 22 * u
        val dark = 0xFF26262B.toInt()
        // Legs and wheels in the text color, so they show on a dark screen too.
        val wheel = mix(look.ink, dark, 0.3f)
        val moving = act == Act.WALK || act == Act.HELD || act == Act.FALL
        val reach = when (act) { Act.HELD -> 4.5f * u; Act.FALL -> 4 * u; else -> 3 * u }

        line.color = wheel
        line.strokeWidth = 2.4f * u
        fill.color = wheel
        for (i in 0 until 2) {
            val hipX = if (i == 0) -10 * u else 10 * u
            val phase = world.stride + i * PI.toFloat()
            val swing = if (moving) sin(phase) * reach else 0f
            val raise = if (moving) max(0f, cos(phase)) * 2 * u else 0f
            canvas.drawLine(hipX, bottom, hipX + swing, -3 * u - raise, line)
            canvas.drawCircle(hipX + swing, -3 * u - raise, 3 * u, fill)
        }

        // A little bob while it walks, a slow breath while it sleeps, a wobble while dizzy.
        val bob = when (act) {
            Act.WALK -> abs(sin(world.stride)) * -1.2f * u
            Act.NAP -> sin((now - world.since) / 600f) * 0.8f * u
            else -> 0f
        }
        canvas.save()
        canvas.translate(0f, bob)
        if (act == Act.DIZZY) canvas.rotate(sin((now - world.since) / 160f) * 6f, 0f, bottom)

        fill.color = look.body
        box.set(-20 * u, top, 20 * u, bottom)
        canvas.drawRoundRect(box, 6 * u, 6 * u, fill)
        line.color = alpha(dark, 0.35f)
        line.strokeWidth = 1.2f * u
        canvas.drawRoundRect(box, 6 * u, 6 * u, line)
        // The sign on its forehead, and a side window behind its eyes.
        fill.color = mix(look.body, Color.WHITE, 0.45f)
        box.set(-15 * u, top + 2.5f * u, 15 * u, top + 6.5f * u)
        canvas.drawRoundRect(box, 2 * u, 2 * u, fill)
        fill.color = mix(look.body, Color.WHITE, 0.3f)
        box.set(-16 * u, top + 9 * u, -9 * u, top + 15 * u)
        canvas.drawRoundRect(box, 2 * u, 2 * u, fill)
        // A headlight at the front, and the bumper under it.
        fill.color = 0xFFFFD54F.toInt()
        canvas.drawCircle(17 * u, bottom - 4.5f * u, 2 * u, fill)
        line.color = alpha(dark, 0.5f)
        line.strokeWidth = 1.6f * u
        canvas.drawLine(-16 * u, bottom - 1.2f * u, 16 * u, bottom - 1.2f * u, line)

        eyes(canvas, now, top, dark)
        canvas.restore()
    }

    // Its front windows are its eyes; under them its cheeks and its mouth.
    private fun eyes(canvas: Canvas, now: Long, top: Float, dark: Int) {
        val act = world.act
        val happy = act == Act.HOP && now - world.startledAt > 900
        val wide = act == Act.HELD || act == Act.FALL || now - world.startledAt < 900
        val blink = world.blinkAt - now in 0 until 140
        val ey = top + 13 * u
        for (ex in floatArrayOf(-1 * u, 10 * u)) {
            when {
                act == Act.DIZZY -> {
                    // Spinning eyes.
                    fill.color = Color.WHITE
                    box.set(ex - 4.5f * u, top + 8.5f * u, ex + 4.5f * u, top + 17.5f * u)
                    canvas.drawRoundRect(box, 3 * u, 3 * u, fill)
                    line.color = dark
                    line.strokeWidth = 1.1f * u
                    path.reset()
                    val turn = (now - world.since) / 120f
                    for (k in 0..16) {
                        val r = 0.2f * u * k
                        val px = ex + cos(turn + k * 0.7f) * r
                        val py = ey + 0.5f * u + sin(turn + k * 0.7f) * r
                        if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
                    }
                    canvas.drawPath(path, line)
                }
                happy -> {
                    // Eyes smiling shut: two arcs.
                    line.color = dark
                    line.strokeWidth = 1.5f * u
                    box.set(ex - 3.5f * u, ey - 2 * u, ex + 3.5f * u, ey + 3 * u)
                    canvas.drawArc(box, 200f, 140f, false, line)
                }
                act == Act.NAP || (blink && !wide) -> {
                    line.color = dark
                    line.strokeWidth = 1.4f * u
                    box.set(ex - 3.5f * u, ey - 2.5f * u, ex + 3.5f * u, ey + 1.5f * u)
                    if (act == Act.NAP) canvas.drawArc(box, 20f, 140f, false, line)
                    else canvas.drawLine(ex - 3.5f * u, ey, ex + 3.5f * u, ey, line)
                }
                else -> {
                    fill.color = Color.WHITE
                    box.set(ex - 4.5f * u, top + 8.5f * u, ex + 4.5f * u, top + 17.5f * u)
                    canvas.drawRoundRect(box, 3 * u, 3 * u, fill)
                    fill.color = dark
                    if (wide) canvas.drawCircle(ex + 0.5f * u, ey, 1.5f * u, fill)
                    else {
                        canvas.drawCircle(ex + (1.5f + world.lookAt * 1.2f) * u, ey + 0.5f * u, 2.2f * u, fill)
                        // A glint, which makes it look alive.
                        fill.color = Color.WHITE
                        canvas.drawCircle(ex + (2.3f + world.lookAt * 1.2f) * u, ey - 0.6f * u, 0.7f * u, fill)
                    }
                }
            }
        }
        // Rosy cheeks.
        fill.color = alpha(0xFFFF7A93.toInt(), if (happy) 0.75f else 0.4f)
        for (cx in floatArrayOf(-4 * u, 15 * u)) {
            box.set(cx - 2 * u, top + 17.6f * u, cx + 2 * u, top + 19.4f * u)
            canvas.drawOval(box, fill)
        }
        // Its mouth: a smile; open wide when happy, a little "o" in the air or in the hand, wavy when dizzy.
        val mx = 4.5f * u
        val my = top + 19.5f * u
        when {
            act == Act.NAP -> {}
            happy -> {
                fill.color = dark
                box.set(mx - 2.4f * u, my - 1.6f * u, mx + 2.4f * u, my + 2.4f * u)
                canvas.drawArc(box, 0f, 180f, true, fill)
            }
            wide -> {
                fill.color = dark
                canvas.drawCircle(mx, my + 0.4f * u, 1.3f * u, fill)
            }
            act == Act.DIZZY -> {
                line.color = dark
                line.strokeWidth = 1.1f * u
                path.reset()
                path.moveTo(mx - 3 * u, my)
                path.quadTo(mx - 1.5f * u, my - 1.2f * u, mx, my)
                path.quadTo(mx + 1.5f * u, my + 1.2f * u, mx + 3 * u, my)
                canvas.drawPath(path, line)
            }
            else -> {
                line.color = dark
                line.strokeWidth = 1.2f * u
                box.set(mx - 2 * u, my - 2 * u, mx + 2 * u, my + 1 * u)
                canvas.drawArc(box, 20f, 140f, false, line)
            }
        }
    }

    // Above its head: z's asleep, a heart when tapped, an exclamation mark when startled, stars when dizzy.
    private fun over(canvas: Canvas, now: Long, x: Float, head: Float) {
        if (world.act == Act.NAP) {
            text.color = look.soft
            for (i in 0 until 3) {
                val t = ((now - world.since) / 1400f + i / 3f) % 1f
                text.textSize = (5 + 4 * t) * u
                text.alpha = (255 * (1 - t)).toInt()
                canvas.drawText("z", x - world.facing * (8 + 10 * t) * u, head + 6 * u - t * 12 * u, text)
            }
            text.alpha = 255
        }
        val love = now - world.heartAt
        if (love in 0 until 900) {
            val t = love / 900f
            fill.color = alpha(0xFFFF4F6D.toInt(), 1 - t)
            heart(canvas, x + 6 * u * sin(t * 6f), head - t * 8 * u, 4 * u)
        }
        val woke = now - world.startledAt
        if (woke in 0 until 900) {
            text.color = look.ink
            text.textSize = 10 * u
            canvas.drawText("!", x, head - 2 * u - min(1f, woke / 150f) * 2 * u, text)
        }
        if (world.act == Act.DIZZY) {
            fill.color = 0xFFFFC94D.toInt()
            val turn = (now - world.since) / 300f
            for (k in 0 until 3) {
                val p = turn + k * 2.1f
                star(canvas, x + cos(p) * 13 * u, head + 8 * u + sin(p) * 3 * u, 2.4f * u)
            }
        }
    }

    private fun heart(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        path.reset()
        path.moveTo(cx, cy + r)
        path.cubicTo(cx - 2.2f * r, cy - 0.2f * r, cx - 0.9f * r, cy - 1.6f * r, cx, cy - 0.5f * r)
        path.cubicTo(cx + 0.9f * r, cy - 1.6f * r, cx + 2.2f * r, cy - 0.2f * r, cx, cy + r)
        canvas.drawPath(path, fill)
    }

    private fun star(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        path.reset()
        for (k in 0 until 10) {
            val rr = if (k % 2 == 0) r else r * 0.45f
            val p = -PI / 2 + k * PI / 5
            val px = cx + (cos(p) * rr).toFloat(); val py = cy + (sin(p) * rr).toFloat()
            if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        canvas.drawPath(path, fill)
    }

    private fun smooth(t: Float) = t * t * (3 - 2 * t)

    private fun alpha(color: Int, a: Float) = (color and 0x00FFFFFF) or ((255 * a.coerceIn(0f, 1f)).toInt() shl 24)

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int) = ((((a shr shift) and 0xFF) * (1 - t) + ((b shr shift) and 0xFF) * t).toInt() and 0xFF) shl shift
        return (0xFF shl 24) or ch(16) or ch(8) or ch(0)
    }

    companion object {
        const val PICK_MS = 260L
    }
}

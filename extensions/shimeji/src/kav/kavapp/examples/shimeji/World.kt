package kav.kavapp.examples.shimeji

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

// A box of one of Kav's screen pieces (Kav.capture), in the overlay's pixels, its kind ("card", "button", "field",
// "text" or "image"), and whether its top edge can be seen (edge()): a card draws its own background, but a button
// may be see-through, over a map.
class Box(val l: Float, val t: Float, val r: Float, val b: Float, val kind: String, val seen: Boolean = true)

// Whether the top edge of the box from l to r at t shows on the screen's picture (px: its color at a point, width ×
// height): at most points along it, the color a little above differs from the color a little below.
fun edge(l: Float, t: Float, r: Float, dp: Float, width: Int, height: Int, px: (Int, Int) -> Int): Boolean {
    val above = (t - 3 * dp).toInt(); val below = (t + 3 * dp).toInt()
    val from = l + 16 * dp; val to = r - 16 * dp
    if (above < 0 || below >= height || to <= from) return false
    var differ = 0
    for (k in 0 until 12) {
        val x = (from + (to - from) * k / 11).toInt().coerceIn(0, width - 1)
        val a = px(x, above); val b = px(x, below)
        if ((0..2).any { abs((a shr it * 8 and 255) - (b shr it * 8 and 255)) >= 8 }) differ++
    }
    return differ >= 8
}

// Where the bus can stand on a piece: its top edge, from l to r, at y.
class Ledge(val l: Float, val r: Float, val y: Float)

// Where the bus is: on the screen's bottom edge, on a ledge, on the left or right edge, upside down on the top edge,
// in the air, or in the rider's hand.
enum class On { FLOOR, LEDGE, LEFT, RIGHT, TOP, AIR, HAND }

// What it is doing there. WALK is also climbing on an edge and crawling along the top; STAND is also clinging and
// hanging still. CROUCH is the moment before a jump.
enum class Act { STAND, WALK, LOOK, SIT, NAP, HOP, CROUCH, FALL, LAND, DIZZY, HELD }

// Everything the bus is and does, apart from how it looks: kept by the extension, not the view, so a view made again
// (a tab changed, a setting) carries on from here. Pure Kotlin, so it runs off the phone too. Distances are the
// overlay's pixels; u is a 32nd of the bus's height and dp one density-independent pixel, both in pixels. Times are in
// milliseconds of one clock (the view's SystemClock.uptimeMillis()).
class World(private val random: Random = Random.Default) {
    var u = 1f
    var dp = 1f
    var naps = true
    // The view that moves the bus; another one still on the screen for a moment only draws it.
    var owner: Any? = null

    // The screen: its size, and the top and bottom edges the bus uses (inside the status and navigation bars).
    var width = 0f; private set
    var height = 0f; private set
    var ceiling = 0f; private set
    var floor = 0f; private set
    var ledges: List<Ledge> = emptyList(); private set

    var on = On.AIR; private set
    var act = Act.FALL; private set
    var since = 0L; private set
    var until = 0L; private set
    // On the floor, a ledge or the top: x is its middle and y where its feet touch. On an edge: y is its middle along
    // it. In the air and in the hand: x and y are its middle.
    var x = Float.NaN; private set
    var y = 0f; private set
    // +1 faces right, -1 left; on an edge, -1 is up and +1 down.
    var facing = 1; private set
    var ledge: Ledge? = null; private set
    var vx = 0f; private set
    var vy = 0f; private set
    var angle = 0f; private set
    private var spin = 0f
    // A hold's sway, in radians, and the finger's point on the bus from its middle.
    var sway = 0f; private set
    private var holdX = 0f
    private var holdY = 0f
    private var fingerVx = 0f

    // For the drawing.
    var stride = 0f; private set
    var lookAt = 0f; private set
    var blinkAt = 0L; private set
    var heartAt = Long.MIN_VALUE / 2; private set
    var startledAt = Long.MIN_VALUE / 2; private set
    var squashAt = Long.MIN_VALUE / 2; private set
    var flip = false; private set
    private var lastPoke = 0L
    private var taps = 0
    private var tapAt = 0L
    private var fastest = 0f
    private var bounces = 0
    private var target: Ledge? = null
    private var decided: Ledge? = null

    // From one place to another (a step from an edge onto a ledge), the drawing eases from the last pose for a moment.
    var turnAt = Long.MIN_VALUE / 2; private set
    var fromX = 0f; private set
    var fromY = 0f; private set
    var fromAngle = 0f; private set

    // Half the bus's length and half its height.
    private val half get() = 20 * u
    private val tall get() = 16 * u
    private val g get() = 2600 * dp

    fun bounds(width: Float, height: Float, ceiling: Float, floor: Float, now: Long) {
        val first = x.isNaN()
        this.width = width; this.height = height; this.ceiling = ceiling; this.floor = max(ceiling + 4 * tall, floor)
        if (first) {
            // The first time: it drops in from the top, somewhere in the left half.
            lastPoke = now
            to(On.AIR, Act.FALL, now, Long.MAX_VALUE)
            x = width * (0.25f + random.nextFloat() * 0.25f); y = this.ceiling + tall
            return
        }
        when (on) {
            On.FLOOR -> { y = this.floor; x = x.coerceIn(half, max(half, width - half)) }
            On.LEFT, On.RIGHT -> y = y.coerceIn(this.ceiling + half, max(this.ceiling + half, this.floor - half))
            On.TOP -> { y = this.ceiling; x = x.coerceIn(half, max(half, width - half)) }
            On.AIR, On.HAND -> { x = x.coerceIn(0f, width); y = y.coerceIn(0f, height) }
            On.LEDGE -> {}
        }
    }

    // Where its feet are and how it is turned (radians), as it is now, for the drawing and for a touch.
    fun feetX(): Float = when (on) {
        On.LEFT -> 0f
        On.RIGHT -> width
        On.AIR, On.HAND -> x - sin(angle) * tall
        else -> x
    }

    fun feetY(): Float = when (on) {
        On.AIR, On.HAND -> y + cos(angle) * tall
        else -> y
    }

    fun turn(): Float = when (on) {
        On.LEFT -> (PI / 2).toFloat()
        On.RIGHT -> (-PI / 2).toFloat()
        On.TOP -> PI.toFloat() + sway
        On.AIR -> angle
        On.HAND -> sway
        else -> 0f
    }

    fun middleX(): Float = feetX() + sin(turn()) * tall
    fun middleY(): Float = feetY() - cos(turn()) * tall

    // Whether a touch at (px, py) is on the bus: within its body and a little around, at least 24 dp from its middle.
    fun hit(px: Float, py: Float): Boolean = hypot(px - middleX(), py - middleY()) <= max(26 * u, 24 * dp)

    private fun to(on: On, act: Act, now: Long, forMs: Long) {
        if (on != this.on) {
            fromX = feetX(); fromY = feetY(); fromAngle = turn()
            turnAt = now
            decided = null
        }
        this.on = on
        set(act, now, forMs)
    }

    private fun set(act: Act, now: Long, forMs: Long) {
        this.act = act
        since = now
        until = if (forMs == Long.MAX_VALUE) Long.MAX_VALUE else now + forMs
    }

    private fun ms(from: Long, to: Long) = from + (random.nextFloat() * (to - from)).toLong()

    // Kav's screen changed: its pieces' boxes as they are now, made into ledges (ledges()). On a ledge, the bus stays on
    // the same piece where it went (the screen scrolled), else it loses its footing and falls.
    fun pieces(boxes: List<Box>, now: Long) {
        ledges = ledges(boxes)
        val was = ledge ?: return
        if (on != On.LEDGE) { ledge = null; return }
        // Followed when the screen scrolls, but not to a far one alike (a row of the same width): then the piece it
        // stood on is gone, and it falls.
        val same = ledges.filter { abs(it.l - was.l) <= 3 * dp && abs(it.r - was.r) <= 3 * dp && abs(it.y - was.y) <= height / 4 }
            .minByOrNull { abs(it.y - was.y) }
            ?: ledges.filter { x >= it.l && x <= it.r && abs(it.y - was.y) <= 2 * dp }.minByOrNull { abs(it.y - was.y) }
        if (same == null || same.y < ceiling + 2 * tall) { fall(now, startled = true); return }
        ledge = same
        y = same.y
        x = x.coerceIn(same.l + 6 * u, max(same.l + 6 * u, same.r - 6 * u))
    }

    // The ledges on Kav's screen: the top of each card, and of a wide button or field, wide enough for the bus, with
    // room above it (no other piece within 6 dp over it: cut out where one is), not too near the top, nor on the floor.
    // Boxes come in the order Kav draws them: one drawn before a card that reaches down into it is behind it (a list
    // under the tab bar's glass), and takes no room from it.
    fun ledges(boxes: List<Box>): List<Ledge> {
        val out = ArrayList<Ledge>()
        for ((i, b) in boxes.withIndex()) {
            val w = b.r - b.l
            val card = b.kind == "card"
            if (!card && b.kind != "button" && b.kind != "field" || !b.seen) continue
            if (b.b - b.t < 16 * u || w < if (card) 2.2f * half else 6 * half) continue
            if (b.t < ceiling + 2 * tall || b.t > floor - 4 * dp) continue
            var parts = listOf(max(0f, b.l) to min(width, b.r))
            for ((k, o) in boxes.withIndex()) {
                if (k == i || o.b <= b.t - 6 * dp || o.t >= b.t - 1 || o.r <= b.l || o.l >= b.r) continue
                if (k < i && card && o.b > b.t + 6 * dp) continue
                parts = parts.flatMap { (l, r) ->
                    if (o.r <= l || o.l >= r) listOf(l to r) else listOfNotNull(if (o.l > l) l to o.l else null, if (o.r < r) o.r to r else null)
                }
            }
            for ((l, r) in parts) if (r - l >= 2.2f * half) out += Ledge(l, r, b.t)
        }
        return out
    }

    // One step of time, dt seconds: what it does moves it, and each thing it does ends in the next.
    fun step(now: Long, dt: Float) {
        if (x.isNaN() || width <= 0f) return
        if (now > blinkAt) blinkAt = now + 2_000 + (random.nextFloat() * 3_000).toLong()
        val walk = 0.7f * 2 * half
        when (on) {
            On.FLOOR, On.LEDGE -> ground(now, dt, walk)
            On.LEFT, On.RIGHT -> edge(now, dt, 0.6f * walk)
            On.TOP -> top(now, dt, 0.45f * walk)
            On.AIR -> air(now, dt)
            On.HAND -> stride += dt * 16f
        }
        if (on == On.TOP || on == On.HAND) {
            // A hanging bus sways a little; one in the hand swings with the finger.
            val want = if (on == On.HAND) (-fingerVx / dp * 0.00022f).coerceIn(-0.7f, 0.7f)
            else if (act == Act.WALK) sin(stride * 0.5f) * 0.12f else sin(now / 700f) * 0.06f
            sway += (want - sway) * (1f - exp(-dt / 0.12f))
            fingerVx *= exp(-dt / 0.15f)
        } else sway = 0f
    }

    private fun ground(now: Long, dt: Float, walk: Float) {
        val l = if (on == On.LEDGE) ledge!!.l + 0.5f * half else half
        val r = if (on == On.LEDGE) ledge!!.r - 0.5f * half else width - half
        when (act) {
            Act.WALK -> {
                x += facing * walk * dt
                stride += dt * 9f
                if (x <= l || x >= r) {
                    x = x.coerceIn(l, max(l, r))
                    end(now, if (x <= l) -1 else 1)
                    return
                }
                if (now > until) rest(now)
            }
            Act.LOOK -> {
                if (random.nextFloat() < dt * 0.8f) lookAt = random.nextFloat() * 2f - 1f
                if (random.nextFloat() < dt * 0.15f) facing = -facing
                if (now > until) rest(now)
            }
            Act.CROUCH -> if (now > until) jump(now)
            Act.LAND -> if (now > until) {
                if (fastest > 2600 * dp || bounces >= 2) set(Act.DIZZY, now, 1_800) else set(Act.LOOK, now, ms(1_200, 2_500))
                fastest = 0f; bounces = 0
            }
            Act.NAP -> if (now > until) { lastPoke = now; set(Act.LOOK, now, 2_000) }
            Act.HOP -> if (now > until) { flip = false; set(Act.LOOK, now, 1_500) }
            else -> if (now > until) rest(now)
        }
    }

    // At an end of where it walks (side -1 left, +1 right): on the floor it climbs the edge or turns; on a ledge it
    // climbs the screen's edge when the ledge reaches it, jumps off, or turns.
    private fun end(now: Long, side: Int) {
        val p = random.nextFloat()
        val wall = if (on == On.FLOOR) true else {
            val e = ledge!!
            if (side < 0) e.l <= 2 * half else e.r >= width - 2 * half
        }
        if (wall && p < 0.55f) {
            val y0 = (if (on == On.FLOOR) floor else ledge!!.y) - half
            to(if (side < 0) On.LEFT else On.RIGHT, Act.WALK, now, ms(4_000, 9_000))
            ledge = null
            facing = -1
            y = y0
        } else if (on == On.LEDGE && p < 0.8f) {
            // Off the end, with a little hop.
            val (mx, my) = middleX() to middleY()
            to(On.AIR, Act.FALL, now, Long.MAX_VALUE)
            ledge = null
            x = mx; y = my
            vx = side * 180 * dp; vy = -420 * dp; spin = side * 1.5f; angle = 0f
        } else {
            facing = -side
            set(Act.LOOK, now, ms(800, 2_000))
        }
    }

    // What it does next, standing somewhere.
    private fun rest(now: Long) {
        val p = random.nextFloat()
        // On the floor inside a card that reaches down to it (the tab bar), it hops up onto it rather than stand over
        // what is on it.
        val inside = if (on == On.FLOOR) ledges.firstOrNull { x in it.l..it.r && floor - it.y in 0f..72 * dp } else null
        val reach = inside ?: if (p < 0.2f) reachable() else null
        when {
            naps && act == Act.SIT && now - lastPoke > 60_000 && p < 0.7f -> set(Act.NAP, now, ms(12_000, 25_000))
            reach != null -> { target = reach; facing = if (reach.l + reach.r > 2 * x) 1 else -1; set(Act.CROUCH, now, 280) }
            p < 0.55f -> {
                if (random.nextFloat() < 0.4f) facing = -facing
                lookAt = 0f
                set(Act.WALK, now, ms(3_000, 8_000))
            }
            p < 0.75f -> set(Act.LOOK, now, ms(2_000, 5_000))
            else -> set(Act.SIT, now, ms(3_000, 7_000))
        }
    }

    // A ledge above it near enough to jump to: from three quarters of its height to six heights up, and at most four
    // lengths away to the side.
    private fun reachable(): Ledge? {
        val feet = y
        return ledges.filter { it !== ledge && feet - it.y in 24 * u..200 * u && min(abs(it.l - x), abs(it.r - x)).let { d -> x in it.l..it.r || d < 160 * u } }
            .randomOrNull(random)
    }

    // A jump that comes down on the ledge it chose, a little above it at its top.
    private fun jump(now: Long) {
        val to = target?.takeIf { t -> ledges.any { it === t } }
        target = null
        if (to == null) { set(Act.LOOK, now, 1_000); return }
        val tx = (x + facing * 30 * u).coerceIn(to.l + half, max(to.l + half, to.r - half))
        val up = y - to.y
        val over = 20 * u
        val v0 = sqrt(2 * g * (up + over))
        val t = v0 / g + sqrt(2 * over / g)
        val (mx, my) = middleX() to middleY()
        to(On.AIR, Act.FALL, now, Long.MAX_VALUE)
        ledge = null
        x = mx; y = my
        vx = (tx - mx) / t; vy = -v0; spin = 0f; angle = 0f
    }

    private fun edge(now: Long, dt: Float, climb: Float) {
        val top = ceiling + half
        val bottom = floor - half
        when (act) {
            Act.WALK -> {
                val was = y
                y += facing * climb * dt
                stride += dt * 9f
                // A ledge that reaches this edge: stepped onto on the way down, and sometimes once it is passed going up.
                val side = if (on == On.LEFT) -1 else 1
                for (e in ledges) {
                    val near = if (side < 0) e.l <= 2 * half else e.r >= width - 2 * half
                    if (!near) continue
                    val down = facing > 0 && was + half < e.y && y + half >= e.y
                    val up = facing < 0 && was + half > e.y && y + half <= e.y
                    if (down || (up && decided !== e && random.nextFloat().also { decided = e } < 0.5f)) {
                        to(On.LEDGE, Act.LOOK, now, ms(1_000, 2_500))
                        ledge = e
                        y = e.y
                        x = if (side < 0) e.l + half else e.r - half
                        facing = -side
                        return
                    }
                }
                if (y <= top) {
                    // Up to the top: it hangs there, upside down.
                    x = if (on == On.LEFT) half else width - half
                    y = ceiling
                    facing = -side
                    to(On.TOP, Act.STAND, now, ms(1_500, 3_000))
                } else if (y >= bottom) {
                    x = if (on == On.LEFT) half else width - half
                    y = floor
                    facing = -side
                    to(On.FLOOR, Act.LOOK, now, ms(1_000, 2_500))
                } else if (now > until) set(Act.STAND, now, ms(1_500, 3_000))
            }
            Act.STAND -> {
                if (random.nextFloat() < dt * 0.8f) lookAt = random.nextFloat() * 2f - 1f
                if (now > until) {
                    val p = random.nextFloat()
                    if (p < 0.12f) fall(now)
                    else {
                        if (p > 0.85f) facing = -facing
                        if (y <= top + u) facing = 1
                        if (y >= bottom - u) facing = -1
                        set(Act.WALK, now, ms(4_000, 9_000))
                    }
                }
            }
            else -> if (now > until) set(Act.STAND, now, 1_000)
        }
    }

    private fun top(now: Long, dt: Float, crawl: Float) {
        when (act) {
            Act.WALK -> {
                x += facing * crawl * dt
                stride += dt * 7f
                if (x <= half || x >= width - half) {
                    // To a corner: down that edge.
                    val left = x <= half
                    to(if (left) On.LEFT else On.RIGHT, Act.WALK, now, ms(3_000, 7_000))
                    facing = 1
                    y = ceiling + half
                } else if (now > until) set(Act.STAND, now, ms(2_000, 4_000))
            }
            Act.STAND -> if (now > until) {
                if (random.nextFloat() < 0.3f) fall(now)
                else {
                    if (random.nextFloat() < 0.4f) facing = -facing
                    set(Act.WALK, now, ms(2_000, 5_000))
                }
            }
            else -> if (now > until) set(Act.STAND, now, 1_000)
        }
    }

    // It lets go (or is let go, or startled): into the air from where it is.
    private fun fall(now: Long, startled: Boolean = false) {
        val (mx, my) = middleX() to middleY()
        val a = turn()
        // Off an edge, a little push away from it.
        val push = when (on) { On.LEFT -> 120 * dp; On.RIGHT -> -120 * dp; else -> 0f }
        if (startled) startledAt = now
        to(On.AIR, Act.FALL, now, Long.MAX_VALUE)
        ledge = null
        x = mx; y = my
        angle = a
        vx = push
        vy = 0f
        spin = 0f
    }

    private fun air(now: Long, dt: Float) {
        stride += dt * 14f
        val prevBottom = y + tall
        vy += g * dt
        vx *= exp(-0.3f * dt)
        x += vx * dt
        y += vy * dt
        fastest = max(fastest, hypot(vx, vy))
        // Spinning slows; once it is slow, the bus rights itself.
        angle += spin * dt
        spin *= exp(-1.2f * dt)
        if (abs(spin) < 2f) {
            val upright = (Math.round(angle / (2 * PI)) * 2 * PI).toFloat()
            angle += (upright - angle) * (1f - exp(-dt / 0.18f))
        }
        val r = tall
        // The screen's sides: grabbed when it comes slowly and hasn't bounced yet, else a bounce.
        if (x < r || x > width - r) {
            val left = x < r
            x = x.coerceIn(r, max(r, width - r))
            if (bounces == 0 && abs(vx) < 1500 * dp && vy > -600 * dp && vy < 900 * dp && y > ceiling + 2 * half && y < floor - 2 * half) {
                to(if (left) On.LEFT else On.RIGHT, Act.STAND, now, ms(800, 1_800))
                facing = -1
                return
            }
            vx = -vx * 0.5f
            spin = -spin * 0.6f + (if (left) 1 else -1) * 0.002f * abs(vy) / dp
            bounces++
            squashAt = now
        }
        // The top: grabbed upside down when it comes slowly and hasn't bounced yet; else a bounce.
        if (y - r < ceiling && vy < 0) {
            y = ceiling + r
            if (bounces == 0 && vy > -1500 * dp) {
                facing = if (vx >= 0) 1 else -1
                x = x.coerceIn(half, max(half, width - half))
                y = ceiling
                to(On.TOP, Act.STAND, now, ms(1_500, 3_000))
                return
            }
            vy = -vy * 0.4f
            bounces++
            squashAt = now
        }
        if (vy > 0) {
            val bottom = y + tall
            val hits = ledges.filter { prevBottom <= it.y + 1 && bottom >= it.y && x >= it.l && x <= it.r }.minByOrNull { it.y }
            val ground = hits?.y ?: if (bottom >= floor) floor else null
            if (ground != null) {
                if (vy > 1100 * dp) {
                    // Too fast to land: a bounce, smaller each time.
                    y = ground - tall
                    vy = -vy * 0.38f
                    vx *= 0.75f
                    spin += vx / dp * 0.003f
                    bounces++
                    squashAt = now
                } else {
                    // Landed, upright again.
                    to(if (hits != null) On.LEDGE else On.FLOOR, Act.LAND, now, 320)
                    ledge = hits
                    y = ground
                    val e = hits
                    x = if (e != null) x.coerceIn(e.l + 6 * u, max(e.l + 6 * u, e.r - 6 * u)) else x.coerceIn(half, max(half, width - half))
                    if (abs(vx) > 60 * dp) facing = if (vx > 0) 1 else -1
                    vx = 0f; vy = 0f; spin = 0f; angle = 0f
                    squashAt = now
                    lastPoke = min(lastPoke, now)
                }
            }
        }
        // Lost off the screen somehow: back from the top.
        if (y > height + 400 * dp || y < -400 * dp) { y = ceiling + tall; x = width / 2; vx = 0f; vy = 0f }
    }

    // A tap. Standing, it hops with a heart (on the third quick tap, a flip); asleep, it wakes with a start; on an edge
    // or the top, it is startled and lets go.
    fun poke(now: Long) {
        lastPoke = now
        when (on) {
            On.AIR, On.HAND -> return
            On.LEFT, On.RIGHT, On.TOP -> { fall(now, startled = true); return }
            else -> {}
        }
        taps = if (now - tapAt < 1_500) taps + 1 else 1
        tapAt = now
        if (act == Act.NAP) startledAt = now else heartAt = now
        flip = taps >= 3 && act != Act.NAP
        if (flip) taps = 0
        set(Act.HOP, now, if (flip) 900 else HOP_MS)
    }

    // Picked up by the finger at (fx, fy).
    fun grab(fx: Float, fy: Float, now: Long) {
        val mx = middleX(); val my = middleY()
        holdX = (fx - mx).coerceIn(-14 * u, 14 * u)
        holdY = (fy - my).coerceIn(-14 * u, 14 * u)
        lastPoke = now
        fingerVx = 0f
        to(On.HAND, Act.HELD, now, Long.MAX_VALUE)
        ledge = null
        x = fx - holdX; y = fy - holdY
        vx = 0f; vy = 0f; spin = 0f; angle = 0f
    }

    fun drag(fx: Float, fy: Float, fvx: Float) {
        if (on != On.HAND) return
        fingerVx = fvx
        x = fx - holdX; y = fy - holdY
    }

    // Let go, at the finger's speed (pixels a second): thrown, or dropped where it is.
    fun throwAt(fvx: Float, fvy: Float, now: Long) {
        if (on != On.HAND) return
        val most = 6000 * dp
        val s = hypot(fvx, fvy)
        val k = if (s > most) most / s else 1f
        lastPoke = now
        angle = sway
        to(On.AIR, Act.FALL, now, Long.MAX_VALUE)
        vx = fvx * k; vy = fvy * k
        spin = (vx / dp * 0.004f).coerceIn(-12f, 12f)
        fastest = 0f; bounces = 0
    }

    // How high it is off its ground, in pixels: a hop's arc (with a flip, higher).
    fun lift(now: Long): Float {
        if (act != Act.HOP) return 0f
        val t = ((now - since).toFloat() / max(1L, until - since)).coerceIn(0f, 1f)
        return (if (flip) 40 else 16) * u * 4 * t * (1 - t)
    }

    companion object {
        const val HOP_MS = 600L
        const val TURN_MS = 180L
    }
}

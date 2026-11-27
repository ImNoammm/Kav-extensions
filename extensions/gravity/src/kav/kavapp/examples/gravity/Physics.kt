package kav.kavapp.examples.gravity

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// Boxes that fall, bounce, slide and tumble on each other: Box2D-Lite by Erin Catto, ported to Kotlin. Only boxes, no
// joints; a bounce (restitution), a little air drag and a top speed are added. Lengths are in meters, with y going down
// as on the screen. Nothing here makes an object while it steps, so no garbage is left each frame.
//
// Box2D-Lite is Copyright (c) 2006-2009 Erin Catto, http://www.gphysics.com, and Copyright (c) 2019 Erin Catto, under
// the MIT License:
//
// Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
// documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
// rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit
// persons to whom the Software is furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all copies or substantial portions of the
// Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
// WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
// COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
// OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

// A box: w and h its whole width and height; a mass of Float.POSITIVE_INFINITY never moves (a wall).
class Body(val w: Float, val h: Float, mass: Float) {
    var x = 0f
    var y = 0f
    var angle = 0f
    var vx = 0f
    var vy = 0f
    var spin = 0f
    var friction = 0.4f
    var bounce = 0.3f
    val invMass = if (mass.isFinite()) 1f / mass else 0f
    val invI = if (mass.isFinite()) 12f / (mass * (w * w + h * h)) else 0f

    // This step's cosine and sine of the angle, and the half size of the upright box around it.
    internal var c = 1f
    internal var s = 0f
    internal var ex = 0f
    internal var ey = 0f
}

internal class Contact {
    var px = 0f; var py = 0f
    var nx = 0f; var ny = 0f
    var r1x = 0f; var r1y = 0f; var r2x = 0f; var r2y = 0f
    var separation = 0f
    var pn = 0f; var pt = 0f
    var massNormal = 0f; var massTangent = 0f
    var bias = 0f
    var bounce = 0f
    var feature = 0

    fun copy(o: Contact) {
        px = o.px; py = o.py; nx = o.nx; ny = o.ny; separation = o.separation
        pn = o.pn; pt = o.pt; feature = o.feature
    }
}

// Two boxes that touch, and up to two points where they do.
internal class Arbiter(val b1: Body, val b2: Body) {
    val contacts = Array(2) { Contact() }
    var count = 0
    val friction = sqrt(b1.friction * b2.friction)
    val bounce = maxOf(b1.bounce, b2.bounce)

    // New points from collide: one that was there the step before keeps its impulses (warm starting).
    fun update(fresh: Array<Contact>, n: Int) {
        for (i in 0 until n) {
            val f = fresh[i]
            var pn = 0f; var pt = 0f
            for (j in 0 until count) if (contacts[j].feature == f.feature) { pn = contacts[j].pn; pt = contacts[j].pt; break }
            f.pn = pn; f.pt = pt
        }
        for (i in 0 until n) contacts[i].copy(fresh[i])
        count = n
    }

    fun preStep(invDt: Float) {
        val allowed = 0.01f
        val biasFactor = 0.2f
        for (i in 0 until count) {
            val c = contacts[i]
            c.r1x = c.px - b1.x; c.r1y = c.py - b1.y
            c.r2x = c.px - b2.x; c.r2y = c.py - b2.y
            val rn1 = c.r1x * c.nx + c.r1y * c.ny
            val rn2 = c.r2x * c.nx + c.r2y * c.ny
            val r11 = c.r1x * c.r1x + c.r1y * c.r1y
            val r22 = c.r2x * c.r2x + c.r2y * c.r2y
            c.massNormal = 1f / (b1.invMass + b2.invMass + b1.invI * (r11 - rn1 * rn1) + b2.invI * (r22 - rn2 * rn2))
            val tx = c.ny; val ty = -c.nx
            val rt1 = c.r1x * tx + c.r1y * ty
            val rt2 = c.r2x * tx + c.r2y * ty
            c.massTangent = 1f / (b1.invMass + b2.invMass + b1.invI * (r11 - rt1 * rt1) + b2.invI * (r22 - rt2 * rt2))
            c.bias = -biasFactor * invDt * min(0f, c.separation + allowed)
            // How fast they met along the normal, for the bounce after the solve: before any impulse of this step.
            c.bounce = normalSpeed(c)
        }
    }

    // Last step's impulses again, at once (warm starting), once every pair has been prepared.
    fun warmStart() {
        for (i in 0 until count) {
            val c = contacts[i]
            push(c, c.pn * c.nx + c.pt * c.ny, c.pn * c.ny - c.pt * c.nx)
        }
    }

    fun applyImpulse() {
        for (i in 0 until count) {
            val c = contacts[i]
            val vn = normalSpeed(c)
            var dPn = c.massNormal * (-vn + c.bias)
            val pn0 = c.pn
            c.pn = maxOf(pn0 + dPn, 0f)
            dPn = c.pn - pn0
            push(c, dPn * c.nx, dPn * c.ny)

            val tx = c.ny; val ty = -c.nx
            val dvx = b2.vx - b2.spin * c.r2y - b1.vx + b1.spin * c.r1y
            val dvy = b2.vy + b2.spin * c.r2x - b1.vy - b1.spin * c.r1x
            val vt = dvx * tx + dvy * ty
            var dPt = c.massTangent * -vt
            val maxPt = friction * c.pn
            val pt0 = c.pt
            c.pt = (pt0 + dPt).coerceIn(-maxPt, maxPt)
            dPt = c.pt - pt0
            push(c, dPt * tx, dPt * ty)
        }
    }

    // The bounce, once the boxes no longer sink into each other: part of the speed they met at given back along the
    // normal (as Box2D 3 does it, apart from the solve). Slow touches don't bounce, so a pile comes to rest.
    fun restitute() {
        if (bounce == 0f) return
        for (i in 0 until count) {
            val c = contacts[i]
            if (c.bounce > -1f || c.pn == 0f) continue
            val vn = normalSpeed(c)
            val pn0 = c.pn
            c.pn = maxOf(pn0 - c.massNormal * (vn + bounce * c.bounce), 0f)
            val d = c.pn - pn0
            push(c, d * c.nx, d * c.ny)
        }
    }

    private fun normalSpeed(c: Contact): Float {
        val dvx = b2.vx - b2.spin * c.r2y - b1.vx + b1.spin * c.r1y
        val dvy = b2.vy + b2.spin * c.r2x - b1.vy - b1.spin * c.r1x
        return dvx * c.nx + dvy * c.ny
    }

    private fun push(c: Contact, px: Float, py: Float) {
        b1.vx -= b1.invMass * px; b1.vy -= b1.invMass * py
        b1.spin -= b1.invI * (c.r1x * py - c.r1y * px)
        b2.vx += b2.invMass * px; b2.vy += b2.invMass * py
        b2.spin += b2.invI * (c.r2x * py - c.r2y * px)
    }
}

class World {
    var gx = 0f
    var gy = 9.8f
    var iterations = 10
    var drag = 0.15f
    var topSpeed = 9f
    val bodies = ArrayList<Body>()

    // Each pair's arbiter, at i * size + j for the bodies i < j.
    private var size = 0
    private var arbiters = arrayOfNulls<Arbiter>(0)
    private val touching = ArrayList<Arbiter>()
    private val fresh = Array(2) { Contact() }

    fun add(b: Body) { bodies += b }

    fun step(dt: Float) {
        val invDt = if (dt > 0f) 1f / dt else 0f
        for (b in bodies) {
            b.c = cos(b.angle); b.s = sin(b.angle)
            val ac = abs(b.c); val asn = abs(b.s)
            b.ex = ac * b.w / 2 + asn * b.h / 2
            b.ey = asn * b.w / 2 + ac * b.h / 2
        }
        broadPhase()

        val keep = 1f / (1f + dt * drag)
        for (b in bodies) {
            if (b.invMass == 0f) continue
            b.vx = (b.vx + dt * gx) * keep
            b.vy = (b.vy + dt * gy) * keep
            b.spin *= keep
        }
        for (a in touching) a.preStep(invDt)
        for (a in touching) a.warmStart()
        repeat(iterations) { for (a in touching) a.applyImpulse() }
        for (a in touching) a.restitute()
        for (b in bodies) {
            if (b.invMass == 0f) continue
            val v = sqrt(b.vx * b.vx + b.vy * b.vy)
            if (v > topSpeed) { b.vx *= topSpeed / v; b.vy *= topSpeed / v }
            b.x += dt * b.vx
            b.y += dt * b.vy
            b.angle += dt * b.spin
        }
    }

    // Every pair whose upright boxes overlap is collided; a pair that stopped touching is let go.
    private fun broadPhase() {
        touching.clear()
        val n = bodies.size
        if (size != n) { size = n; arbiters = arrayOfNulls(n * n) }
        for (i in 0 until n) {
            val bi = bodies[i]
            for (j in i + 1 until n) {
                val bj = bodies[j]
                if (bi.invMass == 0f && bj.invMass == 0f) continue
                if (abs(bi.x - bj.x) > bi.ex + bj.ex || abs(bi.y - bj.y) > bi.ey + bj.ey) {
                    arbiters[i * size + j]?.count = 0
                    continue
                }
                val found = collide(fresh, bi, bj)
                var a = arbiters[i * size + j]
                if (found == 0) { a?.count = 0; continue }
                if (a == null) { a = Arbiter(bi, bj); arbiters[i * size + j] = a }
                a.update(fresh, found)
                touching += a
            }
        }
    }
}

// The box-box test of Box2D-Lite: the axis the boxes overlap least on, then the edge of the other box clipped to the
// reference face. Edges and corners are numbered as there:
//
//        e1
//   v2 ------ v1
//    |        |
// e2 |        | e4
//    |        |
//   v3 ------ v4
//        e3
private class ClipVertex {
    var x = 0f
    var y = 0f
    var inEdge1 = 0
    var outEdge1 = 0
    var inEdge2 = 0
    var outEdge2 = 0

    fun set(o: ClipVertex) { x = o.x; y = o.y; inEdge1 = o.inEdge1; outEdge1 = o.outEdge1; inEdge2 = o.inEdge2; outEdge2 = o.outEdge2 }
    fun clear() { inEdge1 = 0; outEdge1 = 0; inEdge2 = 0; outEdge2 = 0 }
    fun feature(flip: Boolean) = if (flip) inEdge2 or (outEdge2 shl 8) or (inEdge1 shl 16) or (outEdge1 shl 24)
        else inEdge1 or (outEdge1 shl 8) or (inEdge2 shl 16) or (outEdge2 shl 24)
}

private val incident = Array(2) { ClipVertex() }
private val clip1 = Array(2) { ClipVertex() }
private val clip2 = Array(2) { ClipVertex() }

private fun clipSegmentToLine(out: Array<ClipVertex>, inp: Array<ClipVertex>, nx: Float, ny: Float, offset: Float, clipEdge: Int): Int {
    var n = 0
    val d0 = nx * inp[0].x + ny * inp[0].y - offset
    val d1 = nx * inp[1].x + ny * inp[1].y - offset
    if (d0 <= 0f) out[n++].set(inp[0])
    if (d1 <= 0f) out[n++].set(inp[1])
    if (d0 * d1 < 0f) {
        val t = d0 / (d0 - d1)
        val o = out[n]
        val ox = inp[0].x + t * (inp[1].x - inp[0].x)
        val oy = inp[0].y + t * (inp[1].y - inp[0].y)
        if (d0 > 0f) { o.set(inp[0]); o.inEdge1 = clipEdge; o.inEdge2 = 0 }
        else { o.set(inp[1]); o.outEdge1 = clipEdge; o.outEdge2 = 0 }
        o.x = ox; o.y = oy
        n++
    }
    return n
}

private fun incidentEdge(hx: Float, hy: Float, px: Float, py: Float, c: Float, s: Float, nx: Float, ny: Float) {
    // The normal in the incident box's own frame, turned around.
    val ix = -(c * nx + s * ny)
    val iy = -(-s * nx + c * ny)
    val a = incident[0]; val b = incident[1]
    a.clear(); b.clear()
    if (abs(ix) > abs(iy)) {
        if (ix >= 0f) {
            a.x = hx; a.y = -hy; a.inEdge2 = 3; a.outEdge2 = 4
            b.x = hx; b.y = hy; b.inEdge2 = 4; b.outEdge2 = 1
        } else {
            a.x = -hx; a.y = hy; a.inEdge2 = 1; a.outEdge2 = 2
            b.x = -hx; b.y = -hy; b.inEdge2 = 2; b.outEdge2 = 3
        }
    } else {
        if (iy >= 0f) {
            a.x = hx; a.y = hy; a.inEdge2 = 4; a.outEdge2 = 1
            b.x = -hx; b.y = hy; b.inEdge2 = 1; b.outEdge2 = 2
        } else {
            a.x = -hx; a.y = -hy; a.inEdge2 = 2; a.outEdge2 = 3
            b.x = hx; b.y = -hy; b.inEdge2 = 3; b.outEdge2 = 4
        }
    }
    for (v in incident) {
        val vx = v.x; val vy = v.y
        v.x = px + c * vx - s * vy
        v.y = py + s * vx + c * vy
    }
}

// The points where A and B touch, into contacts, with the normal from A to B. 0 when they don't.
internal fun collide(contacts: Array<Contact>, a: Body, b: Body): Int {
    val hAx = a.w / 2; val hAy = a.h / 2
    val hBx = b.w / 2; val hBy = b.h / 2
    val ca = a.c; val sa = a.s; val cb = b.c; val sb = b.s
    val dpx = b.x - a.x; val dpy = b.y - a.y
    val dAx = ca * dpx + sa * dpy; val dAy = -sa * dpx + ca * dpy
    val dBx = cb * dpx + sb * dpy; val dBy = -sb * dpx + cb * dpy
    // C = RotA transposed times RotB, as its absolute values.
    val c11 = abs(ca * cb + sa * sb); val c21 = abs(-sa * cb + ca * sb)
    val c12 = abs(-ca * sb + sa * cb); val c22 = abs(sa * sb + ca * cb)

    val faceAx = abs(dAx) - hAx - (c11 * hBx + c12 * hBy)
    val faceAy = abs(dAy) - hAy - (c21 * hBx + c22 * hBy)
    if (faceAx > 0f || faceAy > 0f) return 0
    val faceBx = abs(dBx) - (c11 * hAx + c21 * hAy) - hBx
    val faceBy = abs(dBy) - (c12 * hAx + c22 * hAy) - hBy
    if (faceBx > 0f || faceBy > 0f) return 0

    val relativeTol = 0.95f
    val absoluteTol = 0.01f
    var axis = 0
    var separation = faceAx
    var nx = if (dAx > 0f) ca else -ca
    var ny = if (dAx > 0f) sa else -sa
    if (faceAy > relativeTol * separation + absoluteTol * hAy) {
        axis = 1; separation = faceAy
        nx = if (dAy > 0f) -sa else sa; ny = if (dAy > 0f) ca else -ca
    }
    if (faceBx > relativeTol * separation + absoluteTol * hBx) {
        axis = 2; separation = faceBx
        nx = if (dBx > 0f) cb else -cb; ny = if (dBx > 0f) sb else -sb
    }
    if (faceBy > relativeTol * separation + absoluteTol * hBy) {
        axis = 3; separation = faceBy
        nx = if (dBy > 0f) -sb else sb; ny = if (dBy > 0f) cb else -cb
    }

    val fx: Float; val fy: Float; val front: Float
    val sx: Float; val sy: Float; val negSide: Float; val posSide: Float
    val negEdge: Int; val posEdge: Int
    when (axis) {
        0 -> {
            fx = nx; fy = ny; front = a.x * fx + a.y * fy + hAx
            sx = -sa; sy = ca
            val side = a.x * sx + a.y * sy
            negSide = -side + hAy; posSide = side + hAy; negEdge = 3; posEdge = 1
            incidentEdge(hBx, hBy, b.x, b.y, cb, sb, fx, fy)
        }
        1 -> {
            fx = nx; fy = ny; front = a.x * fx + a.y * fy + hAy
            sx = ca; sy = sa
            val side = a.x * sx + a.y * sy
            negSide = -side + hAx; posSide = side + hAx; negEdge = 2; posEdge = 4
            incidentEdge(hBx, hBy, b.x, b.y, cb, sb, fx, fy)
        }
        2 -> {
            fx = -nx; fy = -ny; front = b.x * fx + b.y * fy + hBx
            sx = -sb; sy = cb
            val side = b.x * sx + b.y * sy
            negSide = -side + hBy; posSide = side + hBy; negEdge = 3; posEdge = 1
            incidentEdge(hAx, hAy, a.x, a.y, ca, sa, fx, fy)
        }
        else -> {
            fx = -nx; fy = -ny; front = b.x * fx + b.y * fy + hBy
            sx = cb; sy = sb
            val side = b.x * sx + b.y * sy
            negSide = -side + hBx; posSide = side + hBx; negEdge = 2; posEdge = 4
            incidentEdge(hAx, hAy, a.x, a.y, ca, sa, fx, fy)
        }
    }

    if (clipSegmentToLine(clip1, incident, -sx, -sy, negSide, negEdge) < 2) return 0
    if (clipSegmentToLine(clip2, clip1, sx, sy, posSide, posEdge) < 2) return 0

    var n = 0
    for (i in 0 until 2) {
        val v = clip2[i]
        val sep = fx * v.x + fy * v.y - front
        if (sep <= 0f) {
            val c = contacts[n]
            c.separation = sep
            c.nx = nx; c.ny = ny
            // Onto the reference face.
            c.px = v.x - sep * fx; c.py = v.y - sep * fy
            c.feature = v.feature(axis >= 2)
            n++
        }
    }
    return n
}

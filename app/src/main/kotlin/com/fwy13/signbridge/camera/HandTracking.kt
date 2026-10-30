package com.fwy13.signbridge.camera

import kotlin.math.hypot
import kotlin.math.roundToLong

data class HandPoint(val x: Float, val y: Float, val z: Float)
data class HandDetection(val label: String, val score: Float, val points: List<HandPoint>)
private data class Anchor(val point: HandPoint, val time: Long)
class HandTracker {
    private var left: Anchor? = null
    private var right: Anchor? = null
    fun reset() { left = null; right = null }
    fun encode(detections: List<HandDetection>, now: Long): FloatArray {
        require(detections.size <= 2 && detections.all { it.points.size == 21 && it.points.all { p -> p.x.isFinite() && p.y.isFinite() && p.z.isFinite() } })
        val freshLeft = left?.takeIf { now - it.time <= 400 }
        val freshRight = right?.takeIf { now - it.time <= 400 }
        fun distance(hand: HandDetection, anchor: Anchor?) = anchor?.let { hypot(hand.points[0].x - it.point.x, hand.points[0].y - it.point.y).toDouble() } ?: Double.POSITIVE_INFINITY
        val hands = detections.sortedByDescending { it.score }
        var l: HandDetection? = null
        var r: HandDetection? = null
        // MediaPipe labels are reversed to person perspective, then serialized back for the native preprocessing swap.
        fun personLeft(hand: HandDetection) = hand.label == "Right"
        if (hands.size == 1) {
            val h = hands[0]; val dl = distance(h, freshLeft); val dr = distance(h, freshRight)
            when {
                freshLeft != null && freshRight == null && dl <= .55 -> l = h
                freshRight != null && freshLeft == null && dr <= .55 -> r = h
                (dl <= .28 || dr <= .28) && dl + .06 < dr -> l = h
                (dl <= .28 || dr <= .28) && dr + .06 < dl -> r = h
                personLeft(h) -> l = h
                h.label == "Left" -> r = h
            }
        } else if (hands.size >= 2) {
            val a = hands[0]; val b = hands[1]
            val keep = distance(a, freshLeft) + distance(b, freshRight)
            val swap = distance(a, freshRight) + distance(b, freshLeft)
            val keepOrder = if (freshLeft != null && freshRight != null && kotlin.math.abs(keep-swap) > .06) keep < swap
                else if (personLeft(a)) true else if (a.label == "Left") false else a.points[0].x <= b.points[0].x
            l = if (keepOrder) a else b; r = if (keepOrder) b else a
        }
        val output = FloatArray(126)
        fun write(hand: HandDetection?, offset: Int) { hand?.points?.forEachIndexed { i, p -> output[offset+i*3]=p.x; output[offset+i*3+1]=p.y; output[offset+i*3+2]=p.z } }
        l?.let { left = Anchor(it.points[0], now) }; r?.let { right = Anchor(it.points[0], now) }
        write(l, 63); write(r, 0)
        return output
    }
}
class FrameSampler(private val capacity: Int = 60, private val sampleFps: Double = 30.0) {
    init { require(capacity > 0); require(sampleFps > 0 && sampleFps.isFinite()) }
    private var origin: Long? = null
    private var lastTime = Long.MIN_VALUE
    private var lastBin = -1L
    private val frames = ArrayList<FloatArray>(capacity)
    private var next = 0
    val count: Int get() = frames.size
    fun reset() { origin=null; lastTime=Long.MIN_VALUE; lastBin=-1; frames.clear(); next=0 }
    fun append(frame: FloatArray, time: Long): Pair<Boolean, Boolean> {
        require(frame.size == 126 && frame.all(Float::isFinite))
        if (time <= lastTime) return false to false
        val restarted = origin != null && time-lastTime > 2000
        if (restarted) reset()
        if (origin == null) origin=time
        val bin = ((time-origin!!)*sampleFps/1000).roundToLong()
        lastTime=time
        if (bin == lastBin) {
            frames[(next+frames.size-1)%capacity]=frame.copyOf()
            return false to restarted
        }
        fun push(value: FloatArray) {
            if (frames.size < capacity) frames.add(value)
            else { frames[next]=value; next=(next+1)%capacity }
        }
        // Keep only real camera observations. Filling missed FPS bins with an all-zero
        // frame changes the input distribution and can dominate 60-frame inference
        // on devices running below the requested sample rate.
        push(frame.copyOf()); lastBin=bin
        return true to restarted
    }
    fun snapshot(): Array<FloatArray>? = if (frames.size == capacity) {
        Array(capacity) { frames[(next+it)%capacity].copyOf() }
    } else null
}

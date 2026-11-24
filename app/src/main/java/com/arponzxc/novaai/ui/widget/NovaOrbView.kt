package com.arponzxc.novaai.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * The glowing glass orb behind NOVA's holographic face on the home page.
 *
 * - Idle: slow breathing, drifting aurora light inside the glass.
 * - Listening: green-teal, rings pull inward toward the orb.
 * - Thinking: amber/violet, rotating arcs.
 * - Speaking: the whole orb trembles with the voice, its edge wobbles, a voice-wave ring
 *   dances around it and glowing ripples spread outward on every beat.
 *
 * Pure Canvas drawing (no allocations per frame apart from small color shader rebuilds while
 * colors cross-fade), and it stops animating whenever it is not visible.
 */
class NovaOrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private enum class Mode { IDLE, LISTENING, THINKING, SPEAKING }

    private val density = resources.displayMetrics.density

    // ---- state ---------------------------------------------------------------------------
    private var mode = Mode.IDLE
    private var running = false
    private var lastFrameNanos = 0L
    private var time = 0f
    private var flow = 0f

    // ---- audio ---------------------------------------------------------------------------
    private var rawAmp = 0f
    private var lastAmpPushMs = 0L
    private var ampSmooth = 0f
    private var prevTarget = 0f
    private var energy = 0f

    // ---- spring (tremble / bounce) -------------------------------------------------------
    private var scale = 1f
    private var scaleVel = 0f

    // ---- colors --------------------------------------------------------------------------
    private var themeTint: Int? = null
    private var targetA = IDLE_A
    private var targetB = IDLE_B
    private var curA = IDLE_A
    private var curB = IDLE_B
    private var builtA = 0
    private var builtB = 0

    // ---- ripples -------------------------------------------------------------------------
    private val ringAges = FloatArray(MAX_RINGS) { -1f }
    private var sinceRing = 0f

    // ---- reusable drawing objects --------------------------------------------------------
    private val blobPath = Path()
    private val voicePath = Path()
    private val arcRect = RectF()
    private val rimMatrix = Matrix()

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val screenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
    }
    private val specPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.4f * density
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val voicePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * density
        strokeJoin = Paint.Join.ROUND
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND
    }

    private var base = 0f
    private var glowShader: Shader? = null
    private var bodyShader: Shader? = null
    private var auroraA: Shader? = null
    private var auroraB: Shader? = null
    private var auroraC: Shader? = null
    private var specShader: Shader? = null
    private var rimSweep: SweepGradient? = null

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    // =========================================================================================
    // Public API
    // =========================================================================================

    /** state: "idle", "listening", "thinking" or "speaking" (same keys the home page already uses). */
    fun setOrbState(state: String) {
        val newMode = when (state.lowercase()) {
            "listening" -> Mode.LISTENING
            "thinking" -> Mode.THINKING
            "speaking" -> Mode.SPEAKING
            else -> Mode.IDLE
        }
        if (newMode == mode) return
        mode = newMode
        updateTargetColors()
        if (newMode == Mode.SPEAKING) sinceRing = RING_INTERVAL_SPEAK // first ripple right away
        postInvalidateOnAnimation()
    }

    /** Voice loudness 0..1 (from the audio frequency analyzer). */
    fun setAmplitude(level: Float) {
        rawAmp = level.coerceIn(0f, 1f)
        lastAmpPushMs = System.currentTimeMillis()
    }

    /** Same theme keys as the app: dark, red, sunset, light, custom (+ #RRGGBB). */
    fun setThemeKey(themeKey: String, customHex: String?) {
        themeTint = when (themeKey) {
            "red" -> 0xFFEF4444.toInt()
            "sunset" -> 0xFFF97316.toInt()
            "custom" -> runCatching { Color.parseColor(customHex) }.getOrNull()
            else -> null
        }
        updateTargetColors()
    }

    // =========================================================================================
    // Lifecycle
    // =========================================================================================

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateRunning()
    }

    override fun onDetachedFromWindow() {
        running = false
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateRunning()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateRunning()
    }

    private fun updateRunning() {
        val shouldRun = isAttachedToWindow && isShown && windowVisibility == VISIBLE
        if (shouldRun && !running) {
            running = true
            lastFrameNanos = 0L
            postInvalidateOnAnimation()
        } else if (!shouldRun) {
            running = false
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        base = min(w, h) / 2f * 0.66f
        builtA = 0
        builtB = 0
        rebuildShaders()
    }

    // =========================================================================================
    // Colors
    // =========================================================================================

    private fun updateTargetColors() {
        val tint = themeTint
        when (mode) {
            Mode.IDLE -> {
                targetA = tint ?: IDLE_A
                targetB = if (tint != null) mix(tint, Color.WHITE, 0.35f) else IDLE_B
            }
            Mode.LISTENING -> {
                targetA = LISTEN_A
                targetB = LISTEN_B
            }
            Mode.THINKING -> {
                targetA = THINK_A
                targetB = THINK_B
            }
            Mode.SPEAKING -> {
                targetA = tint ?: SPEAK_A
                targetB = if (tint != null) mix(tint, Color.WHITE, 0.35f) else SPEAK_B
            }
        }
    }

    private fun rebuildShaders() {
        if (base <= 0f) return
        val a = curA
        val b = curB
        if (a == builtA && b == builtB && glowShader != null) return
        builtA = a
        builtB = b

        val gr = base * 1.7f
        glowShader = RadialGradient(
            0f, 0f, gr,
            intArrayOf(withAlpha(a, 150), withAlpha(a, 95), withAlpha(a, 0)),
            floatArrayOf(0f, base / gr, 1f),
            Shader.TileMode.CLAMP
        )

        // Deep glass body: lit from the top-left, almost black at the far rim, never flat black.
        val bodyLight = mix(a, Color.parseColor("#06122B"), 0.62f)
        val bodyMid = mix(a, Color.parseColor("#050C1F"), 0.84f)
        val bodyDark = Color.parseColor("#02050F")
        bodyShader = RadialGradient(
            -base * 0.28f, -base * 0.34f, base * 1.55f,
            intArrayOf(bodyLight, bodyMid, bodyDark),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )

        auroraA = softBlob(a, 0.95f, 150)
        auroraB = softBlob(b, 0.85f, 125)
        auroraC = softBlob(mix(a, Color.WHITE, 0.55f), 0.62f, 70)

        specShader = RadialGradient(
            -base * 0.33f, -base * 0.40f, base * 0.55f,
            intArrayOf(withAlpha(Color.WHITE, 105), withAlpha(Color.WHITE, 0)),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )

        rimSweep = SweepGradient(
            0f, 0f,
            intArrayOf(
                withAlpha(a, 30), withAlpha(b, 255), withAlpha(a, 235),
                withAlpha(a, 30), withAlpha(b, 200), withAlpha(a, 30)
            ),
            floatArrayOf(0f, 0.22f, 0.45f, 0.62f, 0.82f, 1f)
        )
    }

    private fun softBlob(color: Int, radiusFactor: Float, alpha: Int): Shader {
        val r = base * radiusFactor
        return RadialGradient(
            0f, 0f, r,
            intArrayOf(withAlpha(color, alpha), withAlpha(color, 0)),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    // =========================================================================================
    // Per-frame update
    // =========================================================================================

    private fun step(dt: Float) {
        time += dt

        // Cross-fade colors.
        val ct = min(1f, dt * 6f)
        curA = mix(curA, targetA, ct)
        curB = mix(curB, targetB, ct)
        if (abs(Color.red(curA) - Color.red(targetA)) < 2 &&
            abs(Color.green(curA) - Color.green(targetA)) < 2 &&
            abs(Color.blue(curA) - Color.blue(targetA)) < 2
        ) {
            curA = targetA
            curB = targetB
        }

        // Voice level (real analyzer data, or a lively synthetic voice while speaking without data).
        val fresh = System.currentTimeMillis() - lastAmpPushMs < 300L
        val target = when {
            fresh -> min(1f, rawAmp * 1.25f)
            mode == Mode.SPEAKING ->
                0.12f + abs(sin(time * 5.1f)) * 0.34f + abs(sin(time * 2.7f + 1f)) * 0.24f
            else -> 0f
        }
        val rise = target - prevTarget
        prevTarget = target
        val rate = if (target > ampSmooth) min(1f, dt * 30f) else min(1f, dt * 7f)
        ampSmooth += (target - ampSmooth) * rate

        // Overall activity drives glow, wobble and ripples.
        val activity = when (mode) {
            Mode.SPEAKING -> max(0.28f, ampSmooth * 1.8f)
            Mode.LISTENING -> max(0.14f, ampSmooth * 1.6f)
            Mode.THINKING -> 0.38f
            Mode.IDLE -> 0f
        }.coerceIn(0f, 1f)
        energy += (activity - energy) * min(1f, dt * 8f)

        flow += dt * (0.55f + energy * 1.7f + if (mode == Mode.THINKING) 1.2f else 0f)

        // Spring: bounces on every voice beat, breathes when quiet.
        if (mode == Mode.SPEAKING && rise > 0.07f) scaleVel += rise * 16f
        val breathing = 0.012f * sin(time * 1.6f)
        val springTarget = 1f + breathing + ampSmooth * (if (mode == Mode.SPEAKING) 0.065f else 0.03f)
        val acc = (springTarget - scale) * 150f - scaleVel * 13f
        scaleVel += acc * dt
        scale += scaleVel * dt
        scale = scale.coerceIn(0.9f, 1.18f)

        stepRipples(dt, rise)
    }

    private fun stepRipples(dt: Float, rise: Float) {
        for (i in ringAges.indices) {
            if (ringAges[i] >= 0f) {
                ringAges[i] += dt / RING_LIFE
                if (ringAges[i] > 1f) ringAges[i] = -1f
            }
        }
        sinceRing += dt
        val spawn = when (mode) {
            Mode.SPEAKING -> sinceRing > RING_MIN_GAP && (rise > 0.10f || sinceRing > RING_INTERVAL_SPEAK)
            Mode.LISTENING -> sinceRing > (if (ampSmooth > 0.06f) 0.9f else 1.9f)
            else -> false
        }
        if (spawn) {
            val free = ringAges.indexOfFirst { it < 0f }
            if (free >= 0) {
                ringAges[free] = 0f
                sinceRing = 0f
            }
        }
    }

    // =========================================================================================
    // Drawing
    // =========================================================================================

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0 || base <= 0f) return

        val now = System.nanoTime()
        val dt = if (lastFrameNanos == 0L) 0.016f else ((now - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
        lastFrameNanos = now
        step(dt)
        rebuildShaders()

        // The whole orb trembles slightly with the voice.
        val tremble = if (mode == Mode.SPEAKING) ampSmooth * 1.1f * density else 0f
        val jx = sin(time * 53f) * tremble
        val jy = cos(time * 61f) * tremble

        canvas.save()
        canvas.translate(width / 2f + jx, height / 2f + jy)
        canvas.scale(scale, scale)

        drawGlow(canvas)
        drawRipples(canvas)
        drawBody(canvas)
        drawVoiceRing(canvas)
        drawThinkingArcs(canvas)

        canvas.restore()

        if (running) postInvalidateOnAnimation()
    }

    private fun drawGlow(canvas: Canvas) {
        glowPaint.shader = glowShader
        glowPaint.alpha = ((0.50f + 0.50f * energy) * 255f).toInt().coerceIn(0, 255)
        canvas.drawCircle(0f, 0f, base * 1.7f, glowPaint)
    }

    private fun drawRipples(canvas: Canvas) {
        for (age in ringAges) {
            if (age < 0f) continue
            val inward = mode == Mode.LISTENING
            val r = if (inward) base * (1.55f - 0.55f * age) else base * (1.03f + 0.55f * age)
            val fade = (1f - age).pow(1.6f)
            ringPaint.color = withAlpha(curA, (fade * 150f).toInt().coerceIn(0, 255))
            ringPaint.strokeWidth = (2.4f - 1.3f * age) * density
            canvas.drawCircle(0f, 0f, r, ringPaint)
        }
    }

    private fun drawBody(canvas: Canvas) {
        val wobble = when (mode) {
            Mode.SPEAKING -> 0.014f + ampSmooth * 0.085f
            Mode.LISTENING -> 0.012f + ampSmooth * 0.05f
            Mode.THINKING -> 0.024f
            Mode.IDLE -> 0.010f
        }
        buildBlob(blobPath, base, wobble)

        bodyPaint.shader = bodyShader
        canvas.drawPath(blobPath, bodyPaint)

        // Light drifting inside the glass.
        canvas.save()
        canvas.clipPath(blobPath)
        val f = flow
        drawAurora(canvas, auroraA, cos(f * 0.50f) * 0.45f, sin(f * 0.70f) * 0.40f)
        drawAurora(canvas, auroraB, cos(f * 0.40f + 2f) * 0.50f, sin(f * 0.55f + 1f) * 0.45f)
        drawAurora(canvas, auroraC, sin(f * 0.90f) * 0.30f, cos(f * 0.80f) * 0.50f)
        specPaint.shader = specShader
        canvas.drawCircle(-base * 0.33f, -base * 0.40f, base * 0.55f, specPaint)
        canvas.restore()

        // Rim light that slowly circles the glass.
        rimMatrix.setRotate(flow * 38f, 0f, 0f)
        rimSweep?.setLocalMatrix(rimMatrix)
        rimPaint.shader = rimSweep
        rimPaint.alpha = ((0.70f + 0.30f * energy) * 255f).toInt().coerceIn(0, 255)
        canvas.drawPath(blobPath, rimPaint)
    }

    private fun drawAurora(canvas: Canvas, shader: Shader?, ox: Float, oy: Float) {
        if (shader == null) return
        screenPaint.shader = shader
        canvas.save()
        canvas.translate(ox * base, oy * base)
        canvas.drawCircle(0f, 0f, base * 0.95f, screenPaint)
        canvas.restore()
    }

    private fun drawVoiceRing(canvas: Canvas) {
        if (mode != Mode.SPEAKING && mode != Mode.LISTENING) return
        val strength = (ampSmooth * 1.4f).coerceIn(0f, 1f)
        if (strength < 0.03f) return
        voicePath.reset()
        val n = 120
        for (i in 0..n) {
            val ang = (2.0 * PI * i / n).toFloat()
            val d = sin(ang * 7f + time * 9f) * 0.50f +
                sin(ang * 11f - time * 13f) * 0.35f +
                sin(ang * 17f + time * 7f) * 0.25f
            val r = base * (1.10f + 0.085f * strength * d)
            val x = cos(ang) * r
            val y = sin(ang) * r
            if (i == 0) voicePath.moveTo(x, y) else voicePath.lineTo(x, y)
        }
        voicePath.close()
        voicePaint.color = withAlpha(mix(curB, Color.WHITE, 0.25f), (strength * 235f).toInt().coerceIn(0, 255))
        canvas.drawPath(voicePath, voicePaint)
    }

    private fun drawThinkingArcs(canvas: Canvas) {
        if (mode != Mode.THINKING) return
        val r = base * 1.2f
        arcRect.set(-r, -r, r, r)
        arcPaint.color = withAlpha(curB, 230)
        val start = flow * 170f
        for (i in 0 until 3) {
            canvas.drawArc(arcRect, start + i * 120f, 62f, false, arcPaint)
        }
        arcPaint.color = withAlpha(curA, 120)
        arcRect.set(-r * 1.08f, -r * 1.08f, r * 1.08f, r * 1.08f)
        for (i in 0 until 3) {
            canvas.drawArc(arcRect, -start * 0.7f + i * 120f + 40f, 30f, false, arcPaint)
        }
    }

    /** Circle whose edge gently wobbles; [wobble] 0.01 is almost round, 0.1 is a lively blob. */
    private fun buildBlob(path: Path, radius: Float, wobble: Float) {
        path.reset()
        val n = 96
        for (i in 0..n) {
            val ang = (2.0 * PI * i / n).toFloat()
            val w = sin(ang * 2f + time * 1.7f) * 0.50f +
                sin(ang * 3f - time * 2.3f) * 0.35f +
                sin(ang * 5f + time * 3.1f) * 0.25f
            val r = radius * (1f + wobble * w)
            val x = cos(ang) * r
            val y = sin(ang) * r
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
    }

    // =========================================================================================
    // Helpers
    // =========================================================================================

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    private fun mix(from: Int, to: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        val r = (Color.red(from) + (Color.red(to) - Color.red(from)) * k).toInt()
        val g = (Color.green(from) + (Color.green(to) - Color.green(from)) * k).toInt()
        val b = (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * k).toInt()
        return Color.rgb(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
    }

    private companion object {
        const val MAX_RINGS = 4
        const val RING_LIFE = 1.35f
        const val RING_MIN_GAP = 0.26f
        const val RING_INTERVAL_SPEAK = 0.62f

        val IDLE_A = Color.parseColor("#22D3EE")
        val IDLE_B = Color.parseColor("#3B82F6")
        val LISTEN_A = Color.parseColor("#10B981")
        val LISTEN_B = Color.parseColor("#22D3EE")
        val THINK_A = Color.parseColor("#F59E0B")
        val THINK_B = Color.parseColor("#8B5CF6")
        val SPEAK_A = Color.parseColor("#06B6D4")
        val SPEAK_B = Color.parseColor("#8B5CF6")
    }
}

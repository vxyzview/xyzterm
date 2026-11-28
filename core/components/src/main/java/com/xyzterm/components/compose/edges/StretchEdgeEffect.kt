/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.xyzterm.components.compose.edges

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.view.animation.AnimationUtils
import androidx.annotation.IntDef

/**
 * This class performs the graphical effect used at the edges of scrollable widgets
 * when the user scrolls beyond the content bounds in 2D space.
 *
 * EdgeEffect is stateful. Custom widgets using EdgeEffect should create an instance
 * for each edge that should show the effect, feed it input data using [onAbsorb],
 * [onPull], and [onRelease], and draw the effect using [draw] in the widget's
 * overridden draw method. If [isFinished] returns false after drawing, the edge
 * effect's animation is not yet complete and the widget should schedule another
 * drawing pass to continue the animation.
 */
class StretchEdgeEffect(context: Context) : EdgeEffectCompat(context) {

    constructor(
        context: Context,
        invalidate: Runnable,
        postInvalidateOnAnimation: Runnable,
    ) : this(context) {
        mInvalidate = invalidate
        mPostInvalidateOnAnimation = postInvalidateOnAnimation
    }

    private var mDistance = 0f
    private var mVelocity = 0f // only for stretch animations
    private var mStartTime = 0L
    private var mState = STATE_IDLE
    private var mPullDistance = 0f
    private var mWidth = 0f
    private var mHeight = 0f

    private var mInvalidate: Runnable = EMPTY_RUNNABLE
    private var mPostInvalidateOnAnimation: Runnable = EMPTY_RUNNABLE
    private val mTmpOut = FloatArray(5)

    fun setOnInvalidate(invalidate: Runnable) {
        mInvalidate = invalidate
    }

    fun setPostInvalidateOnAnimation(postInvalidateOnAnimation: Runnable) {
        mPostInvalidateOnAnimation = postInvalidateOnAnimation
    }

    @EdgeEffectType
    private fun getCurrentEdgeEffectBehavior(): Int =
        if (!ValueAnimator.areAnimatorsEnabled()) TYPE_NONE else TYPE_STRETCH

    /** Set the size of this edge effect in pixels. */
    override fun setSize(width: Int, height: Int) {
        mWidth = width.toFloat()
        mHeight = height.toFloat()
    }

    /** Reports if this EdgeEffect's animation is finished. */
    override fun isFinished(): Boolean = mState == STATE_IDLE

    /** Immediately finish the current animation. */
    override fun finish() {
        mState = STATE_IDLE
        mDistance = 0f
        mVelocity = 0f
    }

    private fun invalidateIfNotFinished() {
        if (!isFinished()) mInvalidate.run()
    }

    override fun onPull(deltaDistance: Float) {
        onPull(deltaDistance, 0.5f)
    }

    override fun onPull(deltaDistance: Float, displacement: Float) {
        if (getCurrentEdgeEffectBehavior() == TYPE_NONE) {
            finish()
            return
        }
        val now = AnimationUtils.currentAnimationTimeMillis()
        if (mState != STATE_PULL) {
            // Catch the currently shown stretch value.
            mPullDistance = mDistance
        }
        mState = STATE_PULL
        mStartTime = now
        mPullDistance += deltaDistance
        // Don't allow stretch beyond 1.
        mPullDistance = Math.min(1f, mPullDistance)
        mDistance = Math.max(0f, mPullDistance)
        mVelocity = 0f
        if (mDistance == 0f) mState = STATE_IDLE
        invalidateIfNotFinished()
    }

    override fun onPullDistance(deltaDistance: Float, displacement: Float): Float {
        if (getCurrentEdgeEffectBehavior() == TYPE_NONE) return 0f
        val finalDistance = Math.max(0f, deltaDistance + mDistance)
        val delta = finalDistance - mDistance
        if (delta == 0f && mDistance == 0f) return 0f // No pull, don't do anything.
        onPull(delta, displacement)
        return delta
    }

    override fun getDistance(): Float = mDistance

    override fun onRelease() {
        mPullDistance = 0f
        if (mState != STATE_PULL && mState != STATE_PULL_DECAY) return
        mState = STATE_RECEDE
        mVelocity = 0f
        mStartTime = AnimationUtils.currentAnimationTimeMillis()
        invalidateIfNotFinished()
    }

    override fun onAbsorb(velocity: Int) {
        if (getCurrentEdgeEffectBehavior() == TYPE_STRETCH) {
            mState = STATE_RECEDE
            mVelocity = velocity * ON_ABSORB_VELOCITY_ADJUSTMENT
            mStartTime = AnimationUtils.currentAnimationTimeMillis()
            invalidateIfNotFinished()
        } else {
            finish()
        }
    }

    fun applyStretch(canvas: Canvas, @EdgeEffectPosition position: Int) {
        applyStretch(canvas, position, 0, 0)
    }

    fun applyStretch(
        canvas: Canvas,
        @EdgeEffectPosition position: Int,
        translationX: Int,
        translationY: Int,
    ) {
        mTmpOut[0] = 0f
        getScale(mTmpOut, position)
        if (mTmpOut[0] == 1f) {
            canvas.scale(mTmpOut[1], mTmpOut[2], mTmpOut[3] - translationX, mTmpOut[4] - translationY)
        }
    }

    fun getScale(out: FloatArray, @EdgeEffectPosition position: Int) {
        if (getCurrentEdgeEffectBehavior() == TYPE_STRETCH) {
            if (mState == STATE_RECEDE) updateSpring()
            if (mDistance != 0f) {
                val vec = dampStretchVector(Math.max(-1f, Math.min(1f, mDistance)))
                val scale = 1f + vec
                /* apply, scaleX, scaleY, pivotX, pivotY */
                out[0] = 1f
                when (position) {
                    POSITION_TOP -> {
                        out[1] = 1f
                        out[2] = scale
                        out[3] = 0f
                        out[4] = 0f
                    }
                    POSITION_BOTTOM -> {
                        out[1] = 1f
                        out[2] = scale
                        out[3] = 0f
                        out[4] = mHeight
                    }
                    POSITION_LEFT -> {
                        out[1] = scale
                        out[2] = 1f
                        out[3] = 0f
                        out[4] = 0f
                    }
                    POSITION_RIGHT -> {
                        out[1] = scale
                        out[2] = 1f
                        out[3] = mWidth
                        out[4] = 0f
                    }
                }
            }
        } else {
            // Animations disabled or drawing into a non-recording canvas: end the effect.
            mState = STATE_IDLE
            mDistance = 0f
            mVelocity = 0f
        }

        var oneLastFrame = false
        if (mState == STATE_RECEDE && mDistance == 0f && mVelocity == 0f) {
            mState = STATE_IDLE
            oneLastFrame = true
        }
        if (mState != STATE_IDLE || oneLastFrame) mPostInvalidateOnAnimation.run()
    }

    override fun draw(canvas: Canvas): Boolean = false

    override fun getMaxHeight(): Int = mHeight.toInt()

    private fun updateSpring() {
        val time = AnimationUtils.currentAnimationTimeMillis()
        val deltaT = (time - mStartTime) / 1000f // millis to seconds
        if (deltaT < 0.001f) return // Must have at least 1 ms difference
        mStartTime = time

        if (Math.abs(mVelocity) <= LINEAR_VELOCITY_TAKE_OVER &&
            Math.abs((mDistance * mHeight).toDouble()) < LINEAR_DISTANCE_TAKE_OVER &&
            Math.signum(mVelocity) == -Math.signum(mDistance)
        ) {
            // Close enough that the spring would settle slowly: interpolate linearly instead.
            mVelocity = Math.signum(mVelocity) * LINEAR_VELOCITY_TAKE_OVER
            val targetDistance = mDistance + (mVelocity * deltaT / mHeight)
            if (Math.signum(targetDistance) != Math.signum(mDistance)) {
                mDistance = 0f
                mVelocity = 0f
            } else {
                mDistance = targetDistance
            }
            return
        }

        val dampedFreq = NATURAL_FREQUENCY * Math.sqrt(1 - DAMPING_RATIO * DAMPING_RATIO)
        // Always underdamped, so only the underdamped equations are needed.
        val cosCoeff = (mDistance * mHeight).toDouble()
        val sinCoeff =
            (1 / dampedFreq) *
                (DAMPING_RATIO * NATURAL_FREQUENCY * (mDistance * mHeight).toDouble() + mVelocity.toDouble())
        val decay = Math.pow(Math.E, -DAMPING_RATIO * NATURAL_FREQUENCY * deltaT.toDouble())
        val distance =
            decay * (cosCoeff * Math.cos(dampedFreq * deltaT.toDouble()) +
                sinCoeff * Math.sin(dampedFreq * deltaT.toDouble()))
        val velocity =
            distance * (-NATURAL_FREQUENCY) * DAMPING_RATIO +
                decay * (-dampedFreq * cosCoeff * Math.sin(dampedFreq * deltaT.toDouble()) +
                    dampedFreq * sinCoeff * Math.cos(dampedFreq * deltaT.toDouble()))
        mDistance = distance.toFloat() / mHeight
        mVelocity = velocity.toFloat()
        if (mDistance > 1f) {
            mDistance = 1f
            mVelocity = 0f
        }
        if (isAtEquilibrium()) {
            mDistance = 0f
            mVelocity = 0f
        }
    }

    private fun isAtEquilibrium(): Boolean {
        val displacement = (mDistance * mHeight).toDouble() // in pixels
        val velocity = mVelocity.toDouble()
        // Don't let displacement drop below 0 (no stretching the opposite way) and stop as
        // soon as the destination is close enough.
        return displacement < 0 || (Math.abs(velocity) < VELOCITY_THRESHOLD && displacement < VALUE_THRESHOLD)
    }

    private fun dampStretchVector(normalizedVec: Float): Float {
        val sign = if (normalizedVec > 0) 1f else -1f
        val overscroll = Math.abs(normalizedVec)
        val linearIntensity = LINEAR_STRETCH_INTENSITY * overscroll
        val scalar = Math.E / SCROLL_DIST_AFFECTED_BY_EXP_STRETCH.toDouble()
        val expIntensity = EXP_STRETCH_INTENSITY.toDouble() * (1 - Math.exp(-overscroll.toDouble() * scalar))
        return sign * (linearIntensity + expIntensity).toFloat()
    }

    companion object {
        private const val TYPE_NONE = -1
        private const val TYPE_STRETCH = 1
        private const val VELOCITY_THRESHOLD = 0.01
        private const val LINEAR_VELOCITY_TAKE_OVER = 200f
        private const val VALUE_THRESHOLD = 0.001
        private const val LINEAR_DISTANCE_TAKE_OVER = 8.0
        private const val NATURAL_FREQUENCY = 24.657
        private const val DAMPING_RATIO = 0.98
        private const val ON_ABSORB_VELOCITY_ADJUSTMENT = 13f
        private const val LINEAR_STRETCH_INTENSITY = 0.016f
        private const val EXP_STRETCH_INTENSITY = 0.016f
        private const val SCROLL_DIST_AFFECTED_BY_EXP_STRETCH = 0.33f
        private const val TAG = "EdgeEffect"
        private const val STATE_IDLE = 0
        private const val STATE_PULL = 1
        private const val STATE_RECEDE = 3
        private const val STATE_PULL_DECAY = 4

        const val POSITION_TOP = 0
        const val POSITION_BOTTOM = 1
        const val POSITION_LEFT = 2
        const val POSITION_RIGHT = 3

        private val EMPTY_RUNNABLE = Runnable {}
    }

    @IntDef(TYPE_NONE, TYPE_STRETCH)
    @Retention(AnnotationRetention.SOURCE)
    annotation class EdgeEffectType

    @IntDef(POSITION_TOP, POSITION_BOTTOM, POSITION_LEFT, POSITION_RIGHT)
    @Retention(AnnotationRetention.SOURCE)
    annotation class EdgeEffectPosition
}

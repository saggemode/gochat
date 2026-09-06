package com.example.gochat.core.wallpaper

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import androidx.core.graphics.ColorUtils

object ChatBubbleHelper {

    private fun dpToPx(context: Context, dp: Float): Float {
        return dp * context.resources.displayMetrics.density
    }

    private fun makeRadii(tl: Float, tr: Float, br: Float, bl: Float): FloatArray {
        return floatArrayOf(tl, tl, tr, tr, br, br, bl, bl)
    }

    /**
     * Generates a dynamic GradientDrawable for outgoing (isMe) or incoming (isOther) chat bubbles
     * according to the selected BubbleShape and theme accent color.
     */
    fun getBubbleDrawable(
        context: Context,
        isMe: Boolean,
        shape: BubbleShape,
        accentColor: Int
    ): Drawable {
        val drawable = GradientDrawable()
        drawable.shape = GradientDrawable.RECTANGLE

        val defaultEmerald = 0xFF00A884.toInt()
        val classicMeColor = if (accentColor == defaultEmerald) 0xFF005C4B.toInt() else ColorUtils.blendARGB(accentColor, Color.BLACK, 0.15f)
        val otherColor = 0xFF202C33.toInt()

        when (shape) {
            BubbleShape.CLASSIC -> {
                val r16 = dpToPx(context, 16f)
                val r4 = dpToPx(context, 4f)
                if (isMe) {
                    drawable.cornerRadii = makeRadii(tl = r16, tr = r16, br = r4, bl = r16)
                    drawable.setColor(classicMeColor)
                } else {
                    drawable.cornerRadii = makeRadii(tl = r16, tr = r16, br = r16, bl = r4)
                    drawable.setColor(otherColor)
                }
            }

            BubbleShape.ROUNDED_PILL -> {
                val r22 = dpToPx(context, 22f)
                val r6 = dpToPx(context, 6f)
                if (isMe) {
                    drawable.cornerRadii = makeRadii(tl = r22, tr = r22, br = r6, bl = r22)
                    drawable.setColor(classicMeColor)
                } else {
                    drawable.cornerRadii = makeRadii(tl = r22, tr = r22, br = r22, bl = r6)
                    drawable.setColor(otherColor)
                }
            }

            BubbleShape.GLASSMORPHISM -> {
                val r16 = dpToPx(context, 16f)
                val r6 = dpToPx(context, 6f)
                val strokeWidth = dpToPx(context, 1.5f).toInt().coerceAtLeast(1)

                if (isMe) {
                    drawable.cornerRadii = makeRadii(tl = r16, tr = r16, br = r6, bl = r16)
                    drawable.setColor(ColorUtils.setAlphaComponent(accentColor, 130))
                    drawable.setStroke(strokeWidth, ColorUtils.setAlphaComponent(Color.WHITE, 120))
                } else {
                    drawable.cornerRadii = makeRadii(tl = r16, tr = r16, br = r16, bl = r6)
                    drawable.setColor(ColorUtils.setAlphaComponent(otherColor, 160))
                    drawable.setStroke(strokeWidth, ColorUtils.setAlphaComponent(Color.WHITE, 60))
                }
            }

            BubbleShape.NEON_GLOW -> {
                val r14 = dpToPx(context, 14f)
                val r3 = dpToPx(context, 3f)
                val strokeWidth = dpToPx(context, 2f).toInt().coerceAtLeast(2)
                val darkVoid = 0xEE0B0E14.toInt()

                if (isMe) {
                    drawable.cornerRadii = makeRadii(tl = r14, tr = r14, br = r3, bl = r14)
                    drawable.setColor(darkVoid)
                    drawable.setStroke(strokeWidth, accentColor)
                } else {
                    drawable.cornerRadii = makeRadii(tl = r14, tr = r14, br = r14, bl = r3)
                    drawable.setColor(darkVoid)
                    drawable.setStroke(
                        dpToPx(context, 1.5f).toInt().coerceAtLeast(1),
                        ColorUtils.setAlphaComponent(accentColor, 150)
                    )
                }
            }

            BubbleShape.MINIMAL_FLAT -> {
                val r8 = dpToPx(context, 8f)
                val r2 = dpToPx(context, 2f)
                val strokeWidth = dpToPx(context, 1f).toInt().coerceAtLeast(1)

                if (isMe) {
                    drawable.cornerRadii = makeRadii(tl = r8, tr = r8, br = r2, bl = r8)
                    drawable.setColor(0xFF1F2C34.toInt())
                    drawable.setStroke(strokeWidth, ColorUtils.setAlphaComponent(accentColor, 100))
                } else {
                    drawable.cornerRadii = makeRadii(tl = r8, tr = r8, br = r8, bl = r2)
                    drawable.setColor(0xFF182229.toInt())
                    drawable.setStroke(strokeWidth, 0x22FFFFFF.toInt())
                }
            }

            BubbleShape.VINTAGE_CURVE -> {
                val r20 = dpToPx(context, 20f)
                val r4 = dpToPx(context, 4f)
                val r18 = dpToPx(context, 18f)

                if (isMe) {
                    drawable.cornerRadii = makeRadii(tl = r20, tr = r4, br = r18, bl = r20)
                    drawable.setColor(classicMeColor)
                } else {
                    drawable.cornerRadii = makeRadii(tl = r4, tr = r20, br = r20, bl = r18)
                    drawable.setColor(otherColor)
                }
            }
        }

        return drawable
    }

    /**
     * Generates a miniature preview drawable for BubbleShape item selection card.
     */
    fun getMiniPreviewDrawable(
        context: Context,
        shape: BubbleShape,
        accentColor: Int
    ): Drawable {
        val drawable = GradientDrawable()
        drawable.shape = GradientDrawable.RECTANGLE

        val defaultEmerald = 0xFF00A884.toInt()
        val fillMe = if (accentColor == defaultEmerald) 0xFF005C4B.toInt() else accentColor

        when (shape) {
            BubbleShape.CLASSIC -> {
                val r10 = dpToPx(context, 10f)
                val r2 = dpToPx(context, 2f)
                drawable.cornerRadii = makeRadii(r10, r10, r2, r10)
                drawable.setColor(fillMe)
            }
            BubbleShape.ROUNDED_PILL -> {
                val r14 = dpToPx(context, 14f)
                val r3 = dpToPx(context, 3f)
                drawable.cornerRadii = makeRadii(r14, r14, r3, r14)
                drawable.setColor(fillMe)
            }
            BubbleShape.GLASSMORPHISM -> {
                val r10 = dpToPx(context, 10f)
                val r3 = dpToPx(context, 3f)
                drawable.cornerRadii = makeRadii(r10, r10, r3, r10)
                drawable.setColor(ColorUtils.setAlphaComponent(accentColor, 120))
                drawable.setStroke(dpToPx(context, 1.5f).toInt(), ColorUtils.setAlphaComponent(Color.WHITE, 140))
            }
            BubbleShape.NEON_GLOW -> {
                val r10 = dpToPx(context, 10f)
                val r2 = dpToPx(context, 2f)
                drawable.cornerRadii = makeRadii(r10, r10, r2, r10)
                drawable.setColor(0xFF0B0E14.toInt())
                drawable.setStroke(dpToPx(context, 2f).toInt(), accentColor)
            }
            BubbleShape.MINIMAL_FLAT -> {
                val r4 = dpToPx(context, 4f)
                val r1 = dpToPx(context, 1f)
                drawable.cornerRadii = makeRadii(r4, r4, r1, r4)
                drawable.setColor(0xFF1F2C34.toInt())
                drawable.setStroke(dpToPx(context, 1f).toInt(), accentColor)
            }
            BubbleShape.VINTAGE_CURVE -> {
                val r12 = dpToPx(context, 12f)
                val r2 = dpToPx(context, 2f)
                val r10 = dpToPx(context, 10f)
                drawable.cornerRadii = makeRadii(r12, r2, r10, r12)
                drawable.setColor(fillMe)
            }
        }
        return drawable
    }

    fun getMessageTextColor(isMe: Boolean, shape: BubbleShape): Int {
        return when (shape) {
            BubbleShape.NEON_GLOW -> if (isMe) Color.WHITE else 0xFFE9EDEF.toInt()
            BubbleShape.GLASSMORPHISM -> Color.WHITE
            BubbleShape.MINIMAL_FLAT -> if (isMe) Color.WHITE else 0xFFE9EDEF.toInt()
            else -> if (isMe) Color.WHITE else 0xFFE9EDEF.toInt()
        }
    }

    fun getTimestampTextColor(isMe: Boolean, shape: BubbleShape): Int {
        return when (shape) {
            BubbleShape.NEON_GLOW -> 0xCCFFFFFF.toInt()
            BubbleShape.GLASSMORPHISM -> 0xDDFFFFFF.toInt()
            else -> if (isMe) 0x9EFFFFFF.toInt() else 0xFF8696A0.toInt()
        }
    }
}

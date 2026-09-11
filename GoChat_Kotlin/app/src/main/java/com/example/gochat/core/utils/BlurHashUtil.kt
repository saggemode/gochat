package com.example.gochat.core.utils

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.withSign

/**
 * Native Kotlin implementation of BlurHash (Encoder & Decoder).
 */
object BlurHashUtil {

    fun decode(blurHash: String?, width: Int, height: Int, punch: Float = 1f): Bitmap? {
        if (blurHash == null || blurHash.length < 6) return null

        val numComponents = decode83(blurHash[0])
        val nx = (numComponents % 9) + 1
        val ny = (numComponents / 9) + 1

        if (blurHash.length != 4 + 2 * nx * ny) return null

        val maxAc = (decode83(blurHash[1]) + 1) / 166f
        val colors = Array(nx * ny) { i ->
            if (i == 0) {
                val value = decode83(blurHash.substring(2, 6))
                decodeDc(value)
            } else {
                val value = decode83(blurHash.substring(4 + i * 2, 6 + i * 2))
                decodeAc(value, maxAc * punch)
            }
        }

        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var r = 0f
                var g = 0f
                var b = 0f

                for (j in 0 until ny) {
                    for (i in 0 until nx) {
                        val basis = (cos(Math.PI * x * i / width) * cos(Math.PI * y * j / height)).toFloat()
                        val color = colors[i + j * nx]
                        r += color[0] * basis
                        g += color[1] * basis
                        b += color[2] * basis
                    }
                }

                pixels[x + y * width] = Color.rgb(encodeCanvas(r), encodeCanvas(g), encodeCanvas(b))
            }
        }

        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    fun encode(bitmap: Bitmap, componentsX: Int, componentsY: Int): String? {
        if (componentsX < 1 || componentsX > 9 || componentsY < 1 || componentsY > 9) return null

        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val factors = Array(componentsX * componentsY) { FloatArray(3) }

        for (j in 0 until componentsY) {
            for (i in 0 until componentsX) {
                val factor = FloatArray(3)
                var normalisation = if (i == 0 && j == 0) 1f else if (i == 0 || j == 0) 2f else 4f

                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val basis = (cos(Math.PI * i * x / width) * cos(Math.PI * j * y / height)).toFloat()
                        val pixel = pixels[x + y * width]
                        factor[0] += basis * decodeCanvas(Color.red(pixel))
                        factor[1] += basis * decodeCanvas(Color.green(pixel))
                        factor[2] += basis * decodeCanvas(Color.blue(pixel))
                    }
                }

                val scale = normalisation / (width * height)
                factors[i + j * componentsX][0] = factor[0] * scale
                factors[i + j * componentsX][1] = factor[1] * scale
                factors[i + j * componentsX][2] = factor[2] * scale
            }
        }

        val dc = factors[0]
        val ac = factors.sliceArray(1 until factors.size)

        val hash = StringBuilder()
        hash.append(encode83((componentsX - 1) + (componentsY - 1) * 9, 1))

        var maxAc = 0f
        if (ac.isNotEmpty()) {
            val actualMax = ac.map { f -> f.map { Math.abs(it) }.maxOrNull()!! }.maxOrNull()!!
            val quantisedMax = Math.max(0, Math.min(82, Math.floor((actualMax * 166f - 0.5f).toDouble()).toInt()))

            maxAc = (quantisedMax + 1) / 166f
            hash.append(encode83(quantisedMax, 1))
        } else {
            hash.append(encode83(0, 1))
        }

        hash.append(encode83(encodeDc(dc), 4))

        for (factor in ac) {
            hash.append(encode83(encodeAc(factor, maxAc), 2))
        }

        return hash.toString()
    }

    private fun decode83(str: String): Int {
        var res = 0
        for (c in str) res = res * 83 + charMap[c]!!
        return res
    }

    private fun decode83(c: Char): Int = charMap[c]!!

    private fun encode83(value: Int, length: Int): String {
        val res = CharArray(length)
        var temp = value
        for (i in length - 1 downTo 0) {
            res[i] = charList[temp % 83]
            temp /= 83
        }
        return String(res)
    }

    private fun decodeDc(value: Int): FloatArray {
        return floatArrayOf(
            decodeCanvas(value shr 16),
            decodeCanvas((value shr 8) and 255),
            decodeCanvas(value and 255)
        )
    }

    private fun encodeDc(color: FloatArray): Int {
        return (encodeCanvas(color[0]) shl 16) + (encodeCanvas(color[1]) shl 8) + encodeCanvas(color[2])
    }

    private fun decodeAc(value: Int, maxAc: Float): FloatArray {
        val r = value / (19 * 19)
        val g = (value / 19) % 19
        val b = value % 19
        return floatArrayOf(
            signPow((r - 9) / 9f, 2f) * maxAc,
            signPow((g - 9) / 9f, 2f) * maxAc,
            signPow((b - 9) / 9f, 2f) * maxAc
        )
    }

    private fun encodeAc(color: FloatArray, maxAc: Float): Int {
        val r = Math.max(0, Math.min(18, Math.floor(signPow(color[0] / maxAc, 0.5f) * 9 + 9.5).toInt()))
        val g = Math.max(0, Math.min(18, Math.floor(signPow(color[1] / maxAc, 0.5f) * 9 + 9.5).toInt()))
        val b = Math.max(0, Math.min(18, Math.floor(signPow(color[2] / maxAc, 0.5f) * 9 + 9.5).toInt()))
        return r * 19 * 19 + g * 19 + b
    }

    private fun decodeCanvas(value: Int): Float {
        val v = value / 255f
        return if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
    }

    private fun encodeCanvas(value: Float): Int {
        val v = Math.max(0f, Math.min(1f, value))
        val res = if (v <= 0.0031308f) v * 12.92f else 1.055f * v.pow(1 / 2.4f) - 0.055f
        return (res * 255 + 0.5f).toInt()
    }

    private fun signPow(value: Float, exp: Float): Float = value.withSign(Math.abs(value).pow(exp))

    private val charList = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#$%*+,-.:;=?@[]^_{|}~".toCharArray()
    private val charMap = charList.withIndex().associate { it.value to it.index }
}

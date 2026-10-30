package com.fwy13.signbridge.models

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder

object AlphabetFixture {

    private const val FRAMES = 60
    private const val FEATURES = 126

    fun load(context: Context): Array<FloatArray> {
        val bytes = context.assets.open(
            "fixtures/bang-chu-cai.input.f32"
        ).use { it.readBytes() }

        val expectedSize = FRAMES * FEATURES * Float.SIZE_BYTES

        require(bytes.size == expectedSize) {
            "Invalid fixture size: ${bytes.size}, expected $expectedSize"
        }

        val buffer = ByteBuffer.wrap(bytes)
            .order(ByteOrder.LITTLE_ENDIAN)

        return Array(FRAMES) {
            FloatArray(FEATURES) {
                buffer.float
            }
        }
    }
}
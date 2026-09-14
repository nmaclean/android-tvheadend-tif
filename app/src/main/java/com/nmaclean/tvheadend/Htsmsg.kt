package com.nmaclean.tvheadend

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

object Htsmsg {
    private const val TYPE_S64 = 2
    private const val TYPE_STR = 3
    private const val TYPE_BIN = 4

    fun encode(map: Map<String, Any>): ByteArray {
        val body = ByteArrayOutputStream()
        for ((k, v) in map) {
            val nameBytes = k.toByteArray(StandardCharsets.UTF_8)
            val nameLen = nameBytes.size
            require(nameLen <= 255) { "Field name too long: $k" }

            when (v) {
                is String -> {
                    val dataBytes = v.toByteArray(StandardCharsets.UTF_8)
                    body.write(TYPE_STR)
                    body.write(nameLen)
                    body.write(writeInt32(dataBytes.size))
                    body.write(nameBytes)
                    body.write(dataBytes)
                }
                is Int -> {
                    val dataBytes = encodeS64(v.toLong())
                    body.write(TYPE_S64)
                    body.write(nameLen)
                    body.write(writeInt32(dataBytes.size))
                    body.write(nameBytes)
                    body.write(dataBytes)
                }
                is Long -> {
                    val dataBytes = encodeS64(v)
                    body.write(TYPE_S64)
                    body.write(nameLen)
                    body.write(writeInt32(dataBytes.size))
                    body.write(nameBytes)
                    body.write(dataBytes)
                }
                is ByteArray -> {
                    body.write(TYPE_BIN)
                    body.write(nameLen)
                    body.write(writeInt32(v.size))
                    body.write(nameBytes)
                    body.write(v)
                }
            }
        }

        val bodyBytes = body.toByteArray()
        val totalLen = bodyBytes.size
        val result = ByteArray(4 + totalLen)
        ByteBuffer.wrap(result).putInt(totalLen)
        System.arraycopy(bodyBytes, 0, result, 4, totalLen)
        return result
    }

    private fun encodeS64(value: Long): ByteArray {
        if (value == 0L) return byteArrayOf(0)
        val full = ByteBuffer.allocate(8).putLong(value).array()
        var start = 0
        while (start < 7 && full[start] == 0.toByte()) {
            start++
        }
        return full.copyOfRange(start, 8)
    }

    private fun writeInt32(value: Int): ByteArray {
        return ByteBuffer.allocate(4).putInt(value).array()
    }

    fun decode(bytes: ByteArray): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        var index = 0
        val limit = bytes.size
        while (index < limit) {
            if (index + 6 > limit) break
            val type = bytes[index++].toInt() and 0xFF
            val nameLen = bytes[index++].toInt() and 0xFF
            val dataLen = ByteBuffer.wrap(bytes, index, 4).int
            index += 4

            if (index + nameLen + dataLen > limit) break
            val name = String(bytes, index, nameLen, StandardCharsets.UTF_8)
            index += nameLen

            val data = ByteArray(dataLen)
            System.arraycopy(bytes, index, data, 0, dataLen)
            index += dataLen

            when (type) {
                TYPE_STR -> map[name] = String(data, StandardCharsets.UTF_8)
                TYPE_S64 -> {
                    var l = 0L
                    for (b in data) {
                        l = (l shl 8) or (b.toInt() and 0xFF).toLong()
                    }
                    map[name] = l
                }
                TYPE_BIN -> map[name] = data
            }
        }
        return map
    }
}

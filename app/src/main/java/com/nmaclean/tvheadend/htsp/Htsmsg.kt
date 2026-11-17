package com.nmaclean.tvheadend.htsp

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * Serializer and deserializer for the HTSMSG binary messaging format used by Tvheadend's HTSP protocol.
 *
 * An HTSMSG message consists of a 4-byte big-endian body length header followed by fields. Each field
 * is formatted as: `[1 byte type] [1 byte name length] [4 bytes big-endian data length] [name bytes] [data bytes]`.
 */
object Htsmsg {
    private const val TYPE_MAP = 1
    private const val TYPE_S64 = 2
    private const val TYPE_STR = 3
    private const val TYPE_BIN = 4
    private const val TYPE_LIST = 5

    /**
     * Encodes a key-value map into a length-prefixed HTSMSG binary byte array.
     *
     * @param map Map containing strings, integers, longs, or byte arrays to encode.
     * @return Complete HTSMSG binary frame including 4-byte big-endian header.
     */
    fun encode(map: Map<String, Any?>): ByteArray {
        val body = ByteArrayOutputStream()
        for ((k, v) in map) {
            if (v == null) continue
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
                else -> {
                    val dataBytes = v.toString().toByteArray(StandardCharsets.UTF_8)
                    body.write(TYPE_STR)
                    body.write(nameLen)
                    body.write(writeInt32(dataBytes.size))
                    body.write(nameBytes)
                    body.write(dataBytes)
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

    /**
     * Encodes a signed 64-bit integer into variable-length little-endian HTSMSG integer bytes.
     */
    private fun encodeS64(value: Long): ByteArray {
        if (value == 0L) return byteArrayOf(0)
        var v = value
        val temp = ByteArray(8)
        var len = 0
        while (v != 0L && len < 8) {
            temp[len++] = (v and 0xFF).toByte()
            v = v ushr 8
        }
        return temp.copyOfRange(0, len)
    }

    /**
     * Writes a 32-bit integer as a 4-byte big-endian byte array.
     */
    private fun writeInt32(value: Int): ByteArray {
        return byteArrayOf(
            ((value shr 24) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte()
        )
    }

    private fun readInt32BE(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }

    private fun readS64LE(bytes: ByteArray, offset: Int, length: Int): Long {
        var value = 0L
        for (i in 0 until length) {
            value = value or ((bytes[offset + i].toLong() and 0xFF) shl (8 * i))
        }
        return value
    }

    /**
     * Decodes an HTSMSG binary payload into a key-value map.
     *
     * @param bytes HTSMSG binary frame payload (without the 4-byte length prefix).
     * @param offset Start offset within [bytes].
     * @param length Number of valid bytes starting at [offset].
     * @return Map containing decoded strings, longs, byte arrays, nested maps, or lists.
     */
    @Suppress("UNCHECKED_CAST")
    @JvmOverloads
    fun decode(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        var index = offset
        val limit = minOf(bytes.size, offset + length)
        while (index < limit) {
            if (index + 6 > limit) break
            val type = bytes[index++].toInt() and 0xFF
            val nameLen = bytes[index++].toInt() and 0xFF
            val dataLen = readInt32BE(bytes, index)
            index += 4

            if (dataLen < 0 || index + nameLen > limit || index + nameLen + dataLen > limit) break
            val name = String(bytes, index, nameLen, StandardCharsets.UTF_8)
            index += nameLen

            when (type) {
                TYPE_STR -> map[name] = String(bytes, index, dataLen, StandardCharsets.UTF_8)
                TYPE_S64 -> map[name] = readS64LE(bytes, index, dataLen)
                TYPE_BIN -> map[name] = bytes.copyOfRange(index, index + dataLen)
                TYPE_MAP -> map[name] = decode(bytes, index, dataLen)
                TYPE_LIST -> {
                    val list = mutableListOf<Any?>()
                    var listIndex = index
                    val listLimit = index + dataLen
                    while (listIndex < listLimit) {
                        if (listIndex + 6 > listLimit) break
                        val itemFieldType = bytes[listIndex++].toInt() and 0xFF
                        val itemNameLen = bytes[listIndex++].toInt() and 0xFF
                        val itemDataLen = readInt32BE(bytes, listIndex)
                        listIndex += 4

                        listIndex += itemNameLen // Skip name bytes if any (should be 0)

                        if (itemDataLen < 0 || listIndex + itemDataLen > listLimit) break

                        when (itemFieldType) {
                            TYPE_STR -> list.add(String(bytes, listIndex, itemDataLen, StandardCharsets.UTF_8))
                            TYPE_S64 -> list.add(readS64LE(bytes, listIndex, itemDataLen))
                            TYPE_BIN -> list.add(bytes.copyOfRange(listIndex, listIndex + itemDataLen))
                            TYPE_MAP -> list.add(decode(bytes, listIndex, itemDataLen))
                        }
                        listIndex += itemDataLen
                    }
                    map[name] = list
                }
            }
            index += dataLen
        }
        return map
    }
}

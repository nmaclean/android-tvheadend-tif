package com.nmaclean.tvheadend

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

    /**
     * Decodes an HTSMSG binary payload into a key-value map.
     *
     * @param bytes HTSMSG binary frame payload (without the 4-byte length prefix).
     * @return Map containing decoded strings, longs, byte arrays, nested maps, or lists.
     */
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
                    for (i in data.indices) {
                        l = l or ((data[i].toLong() and 0xFF) shl (8 * i))
                    }
                    map[name] = l
                }
                TYPE_BIN -> map[name] = data
                TYPE_MAP -> map[name] = decode(data)
                TYPE_LIST -> {
                    val list = mutableListOf<Any>()
                    var listIndex = 0
                    while (listIndex < data.size) {
                        if (listIndex + 5 > data.size) break
                        val itemType = data[listIndex++].toInt() and 0xFF
                        val itemFieldType = itemType
                        val itemNameLen = data[listIndex++].toInt() and 0xFF
                        if (listIndex + 4 > data.size) break
                        val itemDataLen = ByteBuffer.wrap(data, listIndex, 4).int
                        listIndex += 4
                        
                        listIndex += itemNameLen // Skip name bytes if any (should be 0)
                        
                        if (listIndex + itemDataLen > data.size) break
                        val itemData = ByteArray(itemDataLen)
                        System.arraycopy(data, listIndex, itemData, 0, itemDataLen)
                        listIndex += itemDataLen

                        when (itemFieldType) {
                            TYPE_STR -> list.add(String(itemData, StandardCharsets.UTF_8))
                            TYPE_S64 -> {
                                var l = 0L
                                for (i in itemData.indices) {
                                    l = l or ((itemData[i].toLong() and 0xFF) shl (8 * i))
                                }
                                list.add(l)
                            }
                            TYPE_BIN -> list.add(itemData)
                            TYPE_MAP -> list.add(decode(itemData))
                        }
                    }
                    map[name] = list
                }
            }
        }
        return map
    }
}

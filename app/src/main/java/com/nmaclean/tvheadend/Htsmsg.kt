package com.nmaclean.tvheadend

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

object Htsmsg {
    private const val TYPE_MAP = 1
    private const val TYPE_S64 = 2
    private const val TYPE_STR = 3
    private const val TYPE_BIN = 4
    private const val TYPE_LIST = 5

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
        val full = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()
        var end = 7
        while (end > 0 && full[end] == 0.toByte()) {
            end--
        }
        return full.copyOfRange(0, end + 1)
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
                        // In an HTSMSG List, fields are ordered but unnamed.
                        // However, standard HTSMSG wire structure includes a name length byte (which is 0).
                        // Let's check if the protocol fields inside a list have a name length byte.
                        // Standard field format: [1 byte type] [1 byte name length] [4 bytes data length]
                        // If it's a list item, name length is 0, so the name string itself takes up 0 bytes.
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
package com.iptv.tv.core.p2p

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

internal sealed interface AceBencodeValue {
    data class Integer(val value: Long) : AceBencodeValue
    class Bytes(val value: ByteArray) : AceBencodeValue
    data class ListValue(val values: List<AceBencodeValue>) : AceBencodeValue
    data class Dictionary(val values: Map<String, AceBencodeValue>) : AceBencodeValue
}

/** Bounded parser shared by descriptor and peer-metadata decoding. */
internal class AceBoundedBencodeParser(
    private val data: ByteArray,
    startOffset: Int = 0,
    private val maxDepth: Int = DEFAULT_MAX_DEPTH,
    private val maxContainerEntries: Int = DEFAULT_MAX_CONTAINER_ENTRIES,
    private val maxStringBytes: Int = DEFAULT_MAX_STRING_BYTES,
    private val maxTotalNodes: Int = DEFAULT_MAX_TOTAL_NODES
) {
    private var index: Int = startOffset
    private var totalNodes: Int = 0

    init {
        require(startOffset in 0..data.size) { "startOffset is outside the bencode buffer" }
        require(maxDepth > 0) { "maxDepth must be positive" }
        require(maxContainerEntries > 0) { "maxContainerEntries must be positive" }
        require(maxStringBytes > 0) { "maxStringBytes must be positive" }
        require(maxTotalNodes > 0) { "maxTotalNodes must be positive" }
    }

    fun parseRootDictionary(): AceBencodeValue.Dictionary {
        val (value, consumedBytes) = parsePrefixRootDictionary()
        require(consumedBytes == data.size) { "trailing bencode bytes" }
        return value
    }

    fun parsePrefixRootDictionary(): Pair<AceBencodeValue.Dictionary, Int> {
        val value = parseValue(depth = 0) as? AceBencodeValue.Dictionary
            ?: error("root is not a dictionary")
        return value to index
    }

    fun parseRoot(): AceBencodeValue {
        val value = parseValue(depth = 0)
        require(index == data.size) { "trailing bencode bytes" }
        return value
    }

    private fun parseValue(depth: Int): AceBencodeValue {
        require(depth <= maxDepth) { "bencode nesting too deep" }
        require(index < data.size) { "unexpected end of bencode" }
        totalNodes += 1
        require(totalNodes <= maxTotalNodes) { "too many bencode nodes" }

        return when (val marker = data[index].toInt() and 0xff) {
            'i'.code -> AceBencodeValue.Integer(parseInteger())
            'l'.code -> parseList(depth)
            'd'.code -> parseDictionary(depth)
            in '0'.code..'9'.code -> AceBencodeValue.Bytes(parseByteString())
            else -> error("invalid bencode marker: $marker")
        }
    }

    private fun parseInteger(): Long {
        expect('i')
        val start = index
        if (peek('-')) index += 1
        val digitsStart = index
        while (index < data.size && isDigit(data[index])) index += 1
        require(index > digitsStart) { "empty bencode integer" }
        require(index < data.size && data[index] == 'e'.code.toByte()) { "unterminated integer" }

        val token = String(data, start, index - start, StandardCharsets.US_ASCII)
        index += 1
        require(token != "-0") { "negative zero is not canonical" }
        val unsigned = token.removePrefix("-")
        require(unsigned == "0" || !unsigned.startsWith('0')) { "integer has leading zero" }
        return token.toLongOrNull() ?: error("integer overflow")
    }

    private fun parseList(depth: Int): AceBencodeValue.ListValue {
        expect('l')
        val values = ArrayList<AceBencodeValue>()
        while (!peek('e')) {
            require(values.size < maxContainerEntries) { "too many list entries" }
            values += parseValue(depth + 1)
        }
        expect('e')
        return AceBencodeValue.ListValue(values)
    }

    private fun parseDictionary(depth: Int): AceBencodeValue.Dictionary {
        expect('d')
        val values = LinkedHashMap<String, AceBencodeValue>()
        while (!peek('e')) {
            require(values.size < maxContainerEntries) { "too many dictionary entries" }
            require(index < data.size && isDigit(data[index])) { "dictionary key is not a byte string" }
            val keyBytes = parseByteString()
            val key = String(keyBytes, StandardCharsets.US_ASCII)
            require(key.toByteArray(StandardCharsets.US_ASCII).contentEquals(keyBytes)) {
                "dictionary key is not ASCII"
            }
            require(!values.containsKey(key)) { "duplicate dictionary key" }
            values[key] = parseValue(depth + 1)
        }
        expect('e')
        return AceBencodeValue.Dictionary(values)
    }

    private fun parseByteString(): ByteArray {
        val lengthStart = index
        while (index < data.size && isDigit(data[index])) index += 1
        require(index > lengthStart) { "missing string length" }
        require(index < data.size && data[index] == ':'.code.toByte()) { "missing string separator" }

        val lengthText = String(data, lengthStart, index - lengthStart, StandardCharsets.US_ASCII)
        require(lengthText == "0" || !lengthText.startsWith('0')) { "string length has leading zero" }
        val length = lengthText.toIntOrNull() ?: error("string length overflow")
        require(length <= maxStringBytes) { "bencode string too large" }
        index += 1
        require(length <= data.size - index) { "truncated bencode string" }

        val value = data.copyOfRange(index, index + length)
        index += length
        return value
    }

    private fun expect(char: Char) {
        require(index < data.size && data[index] == char.code.toByte()) { "expected $char" }
        index += 1
    }

    private fun peek(char: Char): Boolean =
        index < data.size && data[index] == char.code.toByte()

    private fun isDigit(value: Byte): Boolean {
        val unsigned = value.toInt() and 0xff
        return unsigned in '0'.code..'9'.code
    }

    companion object {
        const val DEFAULT_MAX_DEPTH: Int = 8
        const val DEFAULT_MAX_CONTAINER_ENTRIES: Int = 128
        const val DEFAULT_MAX_STRING_BYTES: Int = 1024 * 1024
        const val DEFAULT_MAX_TOTAL_NODES: Int = 1024
    }
}

internal object AceBencodeEncoder {
    fun encode(value: AceBencodeValue): ByteArray {
        val output = ByteArrayOutputStream()
        write(value, output)
        return output.toByteArray()
    }

    private fun write(value: AceBencodeValue, output: ByteArrayOutputStream) {
        when (value) {
            is AceBencodeValue.Integer -> {
                output.write('i'.code)
                output.write(value.value.toString().toByteArray(StandardCharsets.US_ASCII))
                output.write('e'.code)
            }

            is AceBencodeValue.Bytes -> writeBytes(value.value, output)

            is AceBencodeValue.ListValue -> {
                output.write('l'.code)
                value.values.forEach { item -> write(item, output) }
                output.write('e'.code)
            }

            is AceBencodeValue.Dictionary -> {
                output.write('d'.code)
                value.values.entries
                    .sortedWith { left, right ->
                        compareUnsigned(
                            left.key.toByteArray(StandardCharsets.US_ASCII),
                            right.key.toByteArray(StandardCharsets.US_ASCII)
                        )
                    }
                    .forEach { (key, item) ->
                        writeBytes(key.toByteArray(StandardCharsets.US_ASCII), output)
                        write(item, output)
                    }
                output.write('e'.code)
            }
        }
    }

    private fun writeBytes(bytes: ByteArray, output: ByteArrayOutputStream) {
        output.write(bytes.size.toString().toByteArray(StandardCharsets.US_ASCII))
        output.write(':'.code)
        output.write(bytes)
    }

    private fun compareUnsigned(left: ByteArray, right: ByteArray): Int {
        val common = minOf(left.size, right.size)
        for (index in 0 until common) {
            val comparison = (left[index].toInt() and 0xff).compareTo(right[index].toInt() and 0xff)
            if (comparison != 0) return comparison
        }
        return left.size.compareTo(right.size)
    }
}

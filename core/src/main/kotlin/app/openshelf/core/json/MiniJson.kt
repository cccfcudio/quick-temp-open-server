package app.openshelf.core.json

sealed interface JsonValue {
    data object Null : JsonValue
    data class Bool(val value: Boolean) : JsonValue
    data class Num(val raw: String) : JsonValue
    data class Str(val value: String) : JsonValue
    data class Arr(val values: List<JsonValue>) : JsonValue
    data class Obj(val values: Map<String, JsonValue>) : JsonValue
}

object MiniJson {
    fun str(value: String): JsonValue = JsonValue.Str(value)

    fun num(value: Long): JsonValue = JsonValue.Num(value.toString())

    fun bool(value: Boolean): JsonValue = JsonValue.Bool(value)

    val nil: JsonValue = JsonValue.Null

    fun arr(values: List<JsonValue>): JsonValue = JsonValue.Arr(values)

    fun obj(vararg pairs: Pair<String, JsonValue>): JsonValue = JsonValue.Obj(linkedMapOf(*pairs))

    fun parse(text: String): JsonValue {
        val parser = Parser(text)
        val value = parser.parseValue()
        parser.skipWs()
        if (!parser.eof()) {
            throw IllegalArgumentException("JSON 後面仲有多餘內容")
        }
        return value
    }

    fun stringify(value: JsonValue): String = buildString { appendValue(this, value) }

    private fun appendValue(out: StringBuilder, value: JsonValue) {
        when (value) {
            JsonValue.Null -> out.append("null")
            is JsonValue.Bool -> out.append(if (value.value) "true" else "false")
            is JsonValue.Num -> out.append(value.raw)
            is JsonValue.Str -> appendString(out, value.value)
            is JsonValue.Arr -> {
                out.append('[')
                value.values.forEachIndexed { index, item ->
                    if (index > 0) {
                        out.append(',')
                    }
                    appendValue(out, item)
                }
                out.append(']')
            }
            is JsonValue.Obj -> {
                out.append('{')
                var index = 0
                for ((key, item) in value.values) {
                    if (index++ > 0) {
                        out.append(',')
                    }
                    appendString(out, key)
                    out.append(':')
                    appendValue(out, item)
                }
                out.append('}')
            }
        }
    }

    private fun appendString(out: StringBuilder, value: String) {
        out.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (ch.code < 0x20) {
                    out.append("\\u")
                    out.append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    out.append(ch)
                }
            }
        }
        out.append('"')
    }
}

fun JsonValue.asObject(): Map<String, JsonValue>? = (this as? JsonValue.Obj)?.values

fun JsonValue.asString(): String? = (this as? JsonValue.Str)?.value

private class Parser(private val text: String) {
    private var index: Int = 0

    fun eof(): Boolean = index >= text.length

    fun skipWs() {
        while (!eof() && text[index].isWhitespace()) {
            index += 1
        }
    }

    fun parseValue(): JsonValue {
        skipWs()
        if (eof()) {
            throw IllegalArgumentException("JSON 空白")
        }
        return when (text[index]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> JsonValue.Str(parseString())
            't' -> literal("true", JsonValue.Bool(true))
            'f' -> literal("false", JsonValue.Bool(false))
            'n' -> literal("null", JsonValue.Null)
            '-', in '0'..'9' -> JsonValue.Num(parseNumber())
            else -> throw IllegalArgumentException("JSON 值唔合法")
        }
    }

    private fun parseObject(): JsonValue {
        expect('{')
        skipWs()
        val values = LinkedHashMap<String, JsonValue>()
        if (peek('}')) {
            index += 1
            return JsonValue.Obj(values)
        }
        while (true) {
            skipWs()
            val key = parseString()
            skipWs()
            expect(':')
            values[key] = parseValue()
            skipWs()
            when {
                peek('}') -> {
                    index += 1
                    return JsonValue.Obj(values)
                }
                peek(',') -> index += 1
                else -> throw IllegalArgumentException("JSON 物件未完")
            }
        }
    }

    private fun parseArray(): JsonValue {
        expect('[')
        skipWs()
        val values = ArrayList<JsonValue>()
        if (peek(']')) {
            index += 1
            return JsonValue.Arr(values)
        }
        while (true) {
            values.add(parseValue())
            skipWs()
            when {
                peek(']') -> {
                    index += 1
                    return JsonValue.Arr(values)
                }
                peek(',') -> index += 1
                else -> throw IllegalArgumentException("JSON 陣列未完")
            }
        }
    }

    private fun parseString(): String {
        expect('"')
        val out = StringBuilder()
        while (!eof()) {
            val ch = text[index]
            index += 1
            when (ch) {
                '"' -> return out.toString()
                '\\' -> out.append(parseEscape())
                else -> out.append(ch)
            }
        }
        throw IllegalArgumentException("JSON 字串未完")
    }

    private fun parseEscape(): Char {
        if (eof()) {
            throw IllegalArgumentException("JSON 字串未完")
        }
        val ch = text[index]
        index += 1
        return when (ch) {
            '"' -> '"'
            '\\' -> '\\'
            '/' -> '/'
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> {
                if (index + 4 > text.length) {
                    throw IllegalArgumentException("JSON unicode 唔完整")
                }
                val hex = text.substring(index, index + 4)
                val code = hex.toIntOrNull(16) ?: throw IllegalArgumentException("JSON unicode 唔合法")
                index += 4
                code.toChar()
            }
            else -> throw IllegalArgumentException("JSON 跳脫唔支援")
        }
    }

    private fun parseNumber(): String {
        val start = index
        if (peek('-')) {
            index += 1
        }
        if (eof() || text[index] !in '0'..'9') {
            throw IllegalArgumentException("JSON 數字唔合法")
        }
        while (!eof() && text[index] in '0'..'9') {
            index += 1
        }
        if (!eof() && text[index] == '.') {
            index += 1
            if (eof() || text[index] !in '0'..'9') {
                throw IllegalArgumentException("JSON 數字唔合法")
            }
            while (!eof() && text[index] in '0'..'9') {
                index += 1
            }
        }
        if (!eof() && (text[index] == 'e' || text[index] == 'E')) {
            index += 1
            if (!eof() && (text[index] == '+' || text[index] == '-')) {
                index += 1
            }
            if (eof() || text[index] !in '0'..'9') {
                throw IllegalArgumentException("JSON 數字唔合法")
            }
            while (!eof() && text[index] in '0'..'9') {
                index += 1
            }
        }
        return text.substring(start, index)
    }

    private fun literal(word: String, value: JsonValue): JsonValue {
        if (!text.startsWith(word, index)) {
            throw IllegalArgumentException("JSON 字面值唔啱")
        }
        index += word.length
        return value
    }

    private fun expect(ch: Char) {
        if (eof() || text[index] != ch) {
            throw IllegalArgumentException("JSON 預期 $ch")
        }
        index += 1
    }

    private fun peek(ch: Char): Boolean = !eof() && text[index] == ch
}

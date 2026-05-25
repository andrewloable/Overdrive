package com.overdrive.app.config

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.LinkedHashMap

/**
 * File-backed store for secret material that must not live in the public
 * unified config.
 *
 * The store is JSON-based so we can keep multiple secret sections in one file
 * without inventing a new format. Callers should prefer the bridge layer for
 * app-side access; this class directly touches the filesystem and assumes the
 * caller has permission to do so.
 */
class SecretConfigStore @JvmOverloads constructor(
    private val file: File = File(DEFAULT_PATH)
) {
    companion object {
        const val DEFAULT_PATH = "/data/local/tmp/overdrive_secrets.json"
    }

    private val lock = Any()

    fun exists(): Boolean = file.exists()

    fun canReadDirectly(): Boolean = !file.exists() || file.canRead()

    fun canWriteDirectly(): Boolean = !file.exists() || file.canWrite() || file.parentFile?.canWrite() == true

    fun getString(section: String, key: String): String? = synchronized(lock) {
        val sectionMap = loadSectionMap(section)
        val value = sectionMap[key]?.toString() ?: return null
        if (value.isEmpty()) null else value
    }

    fun getLong(section: String, key: String, defaultValue: Long = 0L): Long = synchronized(lock) {
        val sectionMap = loadSectionMap(section)
        val value = sectionMap[key] ?: return defaultValue
        when (value) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull() ?: defaultValue
            is Boolean -> if (value) 1L else 0L
            else -> value.toString().toLongOrNull() ?: defaultValue
        }
    }

    fun getBoolean(section: String, key: String, defaultValue: Boolean = false): Boolean = synchronized(lock) {
        val sectionMap = loadSectionMap(section)
        val value = sectionMap[key] ?: return defaultValue
        when (value) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> when (value.lowercase()) {
                "true", "1", "yes", "on" -> true
                "false", "0", "no", "off" -> false
                else -> defaultValue
            }
            else -> defaultValue
        }
    }

    fun loadSection(section: String): JSONObject = synchronized(lock) {
        JSONObject(loadSectionMap(section))
    }

    fun putString(section: String, key: String, value: String?): Boolean = synchronized(lock) {
        mutateSection(section) { target ->
            if (value.isNullOrEmpty()) {
                target.remove(key)
            } else {
                target[key] = value
            }
        }
    }

    fun putLong(section: String, key: String, value: Long): Boolean = synchronized(lock) {
        mutateSection(section) { target -> target[key] = value }
    }

    fun putBoolean(section: String, key: String, value: Boolean): Boolean = synchronized(lock) {
        mutateSection(section) { target -> target[key] = value }
    }

    fun replaceSection(section: String, replacement: JSONObject): Boolean = synchronized(lock) {
        mutateRoot { root ->
            root[section] = jsonToMap(replacement)
        }
    }

    fun delete(section: String, key: String): Boolean = synchronized(lock) {
        mutateSection(section) { target -> target.remove(key) }
    }

    private fun mutateSection(section: String, mutation: (MutableMap<String, Any?>) -> Unit): Boolean {
        return mutateRoot { root ->
            val target = sectionMapFromRoot(root, section)
            mutation(target)
            if (target.isEmpty()) {
                root.remove(section)
            } else {
                root[section] = target
            }
        }
    }

    private fun mutateRoot(mutator: (MutableMap<String, Any?>) -> Unit): Boolean {
        ensureWritableOrThrow()
        val root = readRootMap()
        mutator(root)
        return writeRootMap(root)
    }

    private fun loadSectionMap(section: String): MutableMap<String, Any?> {
        ensureReadableOrThrow()
        return sectionMapFromRoot(readRootMap(), section)
    }

    private fun ensureReadableOrThrow() {
        if (file.exists() && !file.canRead()) {
            throw IllegalStateException("Secret store is not readable: ${file.absolutePath}")
        }
    }

    private fun ensureWritableOrThrow() {
        if (file.exists() && !file.canWrite()) {
            throw IllegalStateException("Secret store is not writable: ${file.absolutePath}")
        }
        file.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                throw IllegalStateException("Failed to create secret store directory: ${parent.absolutePath}")
            }
        }
    }

    private fun readRootMap(): MutableMap<String, Any?> {
        if (!file.exists()) {
            return LinkedHashMap()
        }
        return try {
            val content = file.readText()
            if (content.isBlank()) {
                LinkedHashMap()
            } else {
                JsonParser(content).parseObject()
            }
        } catch (_: Exception) {
            LinkedHashMap()
        }
    }

    private fun writeRootMap(root: MutableMap<String, Any?>): Boolean {
        val parent = file.parentFile ?: return false
        val tmp = File(parent, file.name + ".tmp")
        return try {
            FileOutputStream(tmp).use { out ->
                out.write(serializeObject(root).toByteArray(StandardCharsets.UTF_8))
            }
            applyOwnerOnlyPermissions(tmp)
            try {
                Files.move(
                    tmp.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: Exception) {
                if (file.exists() && !file.delete()) {
                    throw IllegalStateException("Failed to replace secret store: ${file.absolutePath}")
                }
                if (!tmp.renameTo(file)) {
                    throw IllegalStateException("Failed to rename secret store temp file: ${tmp.absolutePath}")
                }
            }
            applyOwnerOnlyPermissions(file)
            true
        } catch (_: Exception) {
            try { tmp.delete() } catch (_: Exception) {}
            false
        }
    }

    private fun applyOwnerOnlyPermissions(target: File) {
        try {
            Files.setPosixFilePermissions(
                target.toPath(),
                PosixFilePermissions.fromString("rw-------")
            )
        } catch (_: Exception) {
            target.setReadable(true, true)
            target.setWritable(true, true)
            target.setExecutable(false, false)
        }
    }

    private fun sectionMapFromRoot(root: MutableMap<String, Any?>, section: String?): MutableMap<String, Any?> {
        if (section == null) {
            return copyMap(root)
        }
        val value = root[section] ?: return LinkedHashMap()
        return when (value) {
            is MutableMap<*, *> -> copyRawMap(value)
            is Map<*, *> -> copyRawMap(value)
            is JSONObject -> jsonToMap(value)
            else -> try {
                jsonToMap(JSONObject(value.toString()))
            } catch (_: Exception) {
                LinkedHashMap()
            }
        }
    }

    private fun copyMap(source: MutableMap<String, Any?>): MutableMap<String, Any?> {
        val copy = LinkedHashMap<String, Any?>()
        for ((key, value) in source) {
            copy[key] = value
        }
        return copy
    }

    private fun copyRawMap(source: Map<*, *>): MutableMap<String, Any?> {
        val copy = LinkedHashMap<String, Any?>()
        for ((key, value) in source) {
            if (key is String) {
                copy[key] = value
            }
        }
        return copy
    }

    private fun jsonToMap(json: JSONObject): MutableMap<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            try {
                map[key] = jsonValueToNative(json.get(key))
            } catch (_: Exception) {
                // Skip invalid entries.
            }
        }
        return map
    }

    private fun jsonValueToNative(value: Any?): Any? {
        return when (value) {
            null, JSONObject.NULL -> null
            is JSONObject -> jsonToMap(value)
            is JSONArray -> value.toString()
            else -> value
        }
    }

    private fun serializeObject(value: Any?): String {
        return when (value) {
            null, JSONObject.NULL -> "null"
            is String -> quoteJson(value)
            is Number, is Boolean -> value.toString()
            is Map<*, *> -> serializeMap(value)
            is JSONObject -> serializeObject(jsonToMap(value))
            is JSONArray -> quoteJson(value.toString())
            else -> quoteJson(value.toString())
        }
    }

    private fun serializeMap(source: Map<*, *>): String {
        val builder = StringBuilder()
        builder.append('{')
        var first = true
        for ((key, value) in source) {
            if (key !is String) continue
            if (!first) builder.append(',')
            first = false
            builder.append(quoteJson(key))
            builder.append(':')
            builder.append(serializeObject(value))
        }
        builder.append('}')
        return builder.toString()
    }

    private fun quoteJson(value: String): String {
        val builder = StringBuilder(value.length + 16)
        builder.append('"')
        for (ch in value) {
            when (ch) {
                '\\' -> builder.append("\\\\")
                '"' -> builder.append("\\\"")
                '\b' -> builder.append("\\b")
                '\u000C' -> builder.append("\\f")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                else -> {
                    if (ch.code < 0x20) {
                        builder.append(String.format("\\u%04x", ch.code))
                    } else {
                        builder.append(ch)
                    }
                }
            }
        }
        builder.append('"')
        return builder.toString()
    }

    private class JsonParser(private val input: String) {
        private var index = 0

        fun parseObject(): MutableMap<String, Any?> {
            skipWhitespace()
            expect('{')
            val result = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                index++
                return result
            }
            while (index < input.length) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                result[key] = parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> {
                        index++
                        continue
                    }
                    '}' -> {
                        index++
                        return result
                    }
                    else -> throw IllegalStateException("Invalid JSON object")
                }
            }
            throw IllegalStateException("Unterminated JSON object")
        }

        private fun parseValue(): Any? {
            skipWhitespace()
            return when (peek()) {
                '"' -> parseString()
                '{' -> parseObject()
                't' -> { consumeLiteral("true"); true }
                'f' -> { consumeLiteral("false"); false }
                'n' -> { consumeLiteral("null"); null }
                else -> parseNumber()
            }
        }

        private fun parseString(): String {
            expect('"')
            val builder = StringBuilder()
            while (index < input.length) {
                val ch = input[index++]
                when (ch) {
                    '"' -> return builder.toString()
                    '\\' -> {
                        if (index >= input.length) {
                            throw IllegalStateException("Invalid escape")
                        }
                        when (val escaped = input[index++]) {
                            '"', '\\', '/' -> builder.append(escaped)
                            'b' -> builder.append('\b')
                            'f' -> builder.append('\u000C')
                            'n' -> builder.append('\n')
                            'r' -> builder.append('\r')
                            't' -> builder.append('\t')
                            'u' -> {
                                if (index + 4 > input.length) {
                                    throw IllegalStateException("Invalid unicode escape")
                                }
                                val hex = input.substring(index, index + 4)
                                builder.append(hex.toInt(16).toChar())
                                index += 4
                            }
                            else -> throw IllegalStateException("Invalid escape")
                        }
                    }
                    else -> builder.append(ch)
                }
            }
            throw IllegalStateException("Unterminated string")
        }

        private fun parseNumber(): Any {
            val start = index
            while (index < input.length) {
                when (input[index]) {
                    ' ', '\t', '\r', '\n', ',', '}', ']' -> break
                    else -> index++
                }
            }
            val token = input.substring(start, index)
            return when {
                token.contains('.') || token.contains('e', true) || token.contains('E', true) -> token.toDoubleOrNull()
                    ?: throw IllegalStateException("Invalid number")
                token.toLongOrNull() != null -> token.toLong()
                else -> throw IllegalStateException("Invalid number")
            }
        }

        private fun consumeLiteral(literal: String) {
            if (!input.regionMatches(index, literal, 0, literal.length)) {
                throw IllegalStateException("Invalid literal")
            }
            index += literal.length
        }

        private fun skipWhitespace() {
            while (index < input.length && input[index].isWhitespace()) {
                index++
            }
        }

        private fun expect(ch: Char) {
            if (peek() != ch) {
                throw IllegalStateException("Expected '$ch'")
            }
            index++
        }

        private fun peek(): Char {
            return if (index < input.length) input[index] else '\u0000'
        }
    }
}

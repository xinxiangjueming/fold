package com.example.fold.util

import org.mozilla.universalchardet.UniversalDetector
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset

object CharsetDetector {

    private val COMMON_CHARSETS = listOf(
        Charset.forName("UTF-8"),
        Charset.forName("GBK"),
        Charset.forName("GB2312"),
        Charset.forName("GB18030"),
        Charset.forName("BIG5"),
        Charset.forName("Shift_JIS"),
        Charset.forName("EUC-KR"),
        Charset.forName("ISO-8859-1"),
        Charset.forName("UTF-16LE"),
        Charset.forName("UTF-16BE")
    )

    fun detect(file: File): String {
        return try {
            val bytes = ByteArray(8192)
            val bytesRead = file.inputStream().use { it.read(bytes) }
            detectFromBytes(bytes.copyOf(bytesRead))
        } catch (e: Exception) {
            "UTF-8"
        }
    }

    fun detect(inputStream: InputStream): String {
        return try {
            val bytes = ByteArray(8192)
            val bytesRead = inputStream.read(bytes)
            detectFromBytes(bytes.copyOf(bytesRead))
        } catch (e: Exception) {
            "UTF-8"
        }
    }

    private fun detectFromBytes(bytes: ByteArray): String {
        val detector = UniversalDetector(null)
        detector.handleData(bytes, 0, bytes.size)
        detector.dataEnd()
        val detected = detector.detectedCharset
        detector.reset()

        if (detected != null) return detected

        for (charset in COMMON_CHARSETS) {
            try {
                val text = String(bytes, charset)
                if (text.count { it == '\uFFFD' } < text.length * 0.01) {
                    return charset.name()
                }
            } catch (_: Exception) {}
        }

        return "UTF-8"
    }

    fun readTextFile(file: File, forcedEncoding: String? = null): String {
        val charsetName = forcedEncoding ?: detect(file)
        val charset = Charset.forName(charsetName)
        val bytes = file.readBytes()
        return String(bytes, charset)
    }
}

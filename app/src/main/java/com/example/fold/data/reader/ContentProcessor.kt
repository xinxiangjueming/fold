package com.example.fold.data.reader

import com.example.fold.util.FoldLogger

/**
 * Chinese intelligent re-paragraphing, inspired by legado's ContentHelp.reSegment.
 */
object ContentProcessor {

    private const val TAG = "ContentProcessor"
    private const val MAX_LINE_LEN = 200

    // Curly quotes as Int code points to avoid compiler confusion
    private const val DQ_OPEN = 0x201C   // left double quotation mark
    private const val DQ_CLOSE = 0x201D  // right double quotation mark
    private const val SQ_OPEN = 0x2018   // left single quotation mark
    private const val SQ_CLOSE = 0x2019  // right single quotation mark

    private val MARK_END = charArrayOf('？', '。', '！', '?', '!', '~', '…')
    private val MARK_MID = charArrayOf('，', '、', '—', '–', ';', '；')
    private val MARK_SAY = charArrayOf(
        '问', '说', '喊', '唱', '叫', '骂',
        '道', '着', '答', '吼', '嚷', '叹',
        '嘟', '笑'
    )
    private val QUOTE_OPEN = charArrayOf('"', DQ_OPEN.toChar(), SQ_OPEN.toChar())
    private val QUOTE_CLOSE = charArrayOf('"', DQ_CLOSE.toChar(), SQ_CLOSE.toChar())
    private val QUOTE_ALL = QUOTE_OPEN + QUOTE_CLOSE

    // Pre-compiled regex patterns
    private val RE_COLON_QUOTE = Regex(":" + "\\s*" + DQ_OPEN.toChar())
    private val RE_CLOSE_OPEN = Regex(DQ_CLOSE.toChar().toString() + "\\s*" + DQ_OPEN.toChar())
    private val RE_NEWLINE = Regex("\\r?\\n")
    private val RE_MULTI_NEWLINE = Regex("\\n+")
    private val RE_CONSECUTIVE_QUOTES = Regex("([\"\\u201C\\u201D\\u2018\\u2019])([\"\\u201C\\u201D\\u2018\\u2019])")
    private val RE_QUOTE_PERIOD_QUOTE = Regex("([\"\\u201D\\u2019])([。？！?!.~…])([\"\\u201C\\u2018])")
    private val RE_QUOTE_PERIOD_NONQUOTE = Regex("([\"\\u201D\\u2019])([。？！?!.~…])([^\"\\u201C\\u2018\\n])")
    private val RE_SENTENCE_SPLIT = Regex("(?<=[${MARK_END.joinToString("")}])")

    fun reSegment(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        if (raw.length < MAX_LINE_LEN) return raw.split(RE_MULTI_NEWLINE).filter { it.isNotBlank() }

        // Phase 1 - Normalize quotes
        var t = raw
            .replace("&quot;", DQ_OPEN.toChar().toString())
            .replace(RE_COLON_QUOTE, "：" + DQ_OPEN.toChar())
            .replace(RE_CLOSE_OPEN, "${DQ_CLOSE.toChar()}\n${DQ_OPEN.toChar()}")

        // Phase 2 - Merge broken paragraphs
        t = merge(t)

        // Phase 3 - Pre-split on dialogue patterns
        t = preSplit(t)

        // Phase 4 - Quote-aware splitting per segment
        val result = mutableListOf<String>()
        t.split(RE_MULTI_NEWLINE).forEach { seg ->
            val s = seg.trim()
            if (s.isNotEmpty()) {
                result += findNewLines(s)
            }
        }

        // Phase 5 - Force-split very long paragraphs
        val finalResult = mutableListOf<String>()
        for (p in result) {
            if (p.length > MAX_LINE_LEN * 2) {
                finalResult += forceSplit(p)
            } else {
                finalResult += p
            }
        }

        // Phase 6 - Clean up broken quotes across lines
        val cleaned = fixBrokenQuotes(finalResult).filter { it.isNotBlank() }

        FoldLogger.d(TAG, "reSegment: ${raw.length} chars -> ${cleaned.size} paragraphs")
        return cleaned
    }

    // ========== Phase 2: Merge ==========

    private fun merge(text: String): String {
        val parts = text.split(RE_NEWLINE).filter { it.isNotBlank() }
        if (parts.size <= 1) return parts.firstOrNull()?.trim() ?: ""

        val sb = StringBuilder(parts[0].trim())
        for (i in 1 until parts.size) {
            val line = parts[i].trim()
            if (line.isEmpty()) continue

            val last = sb.last()
            val lastIsQuote = last in QUOTE_ALL
            val keepNl = if (lastIsQuote && sb.length >= 2) {
                sb[sb.length - 2] in MARK_END
            } else {
                last in MARK_END
            }
            if (keepNl) {
                sb.append('\n')
            }
            sb.append(line)
        }
        return sb.toString()
    }

    // ========== Phase 3: Pre-split ==========

    private fun preSplit(text: String): String {
        var t = text
        t = RE_CONSECUTIVE_QUOTES.replace(t) { "${it.groupValues[1]}\n${it.groupValues[2]}" }
        t = RE_QUOTE_PERIOD_QUOTE.replace(t) { "${it.groupValues[1]}${it.groupValues[2]}\n${it.groupValues[3]}" }
        t = RE_QUOTE_PERIOD_NONQUOTE.replace(t) { "${it.groupValues[1]}${it.groupValues[2]}\n${it.groupValues[3]}" }
        for (v in MARK_SAY) {
            t = t.replace("$v。", "$v。\n")
        }
        return t
    }

    // ========== Phase 4: Quote-aware splitting ==========

    private fun findNewLines(text: String): List<String> {
        if (text.length < 4) return listOf(text)

        val qPos = mutableListOf<Int>()
        for (i in text.indices) {
            if (text[i] in QUOTE_ALL) qPos.add(i)
        }
        if (qPos.isEmpty()) return listOf(text)

        // Pair quotes
        val mod = IntArray(qPos.size)
        var depth = 0
        for (i in qPos.indices) {
            val ch = text[qPos[i]]
            if (ch in QUOTE_OPEN && (i == 0 || mod[i - 1] <= 0)) {
                mod[i] = ++depth
            } else if (depth > 0) {
                mod[i] = -depth
                depth--
            } else {
                mod[i] = ++depth
            }
        }

        // Find break positions
        val insN = mutableListOf<Int>()
        for (i in 1 until qPos.size) {
            val prevClose = mod[i - 1] < 0
            val curOpen = mod[i] > 0
            if (prevClose && curOpen) {
                val between = qPos[i] - qPos[i - 1]
                if (between in 1..4) continue
                if (qPos[i] > 0 && text[qPos[i] - 1] in MARK_END) {
                    insN.add(qPos[i])
                }
                if (qPos[i - 1] + 1 < qPos[i]) {
                    val gap = text.substring(qPos[i - 1] + 1, qPos[i])
                    if (gap.any { it in MARK_SAY }) {
                        insN.add(qPos[i - 1] + 1)
                    }
                }
            }
        }

        // Non-quote long segments
        for (i in 0 until qPos.size - 1) {
            val start = qPos[i]
            val end = qPos[i + 1]
            if (end - start > MAX_LINE_LEN) {
                for (j in start + 1 until end) {
                    if (text[j] in MARK_END && j + 1 < end && text[j + 1] !in MARK_END) {
                        insN.add(j + 1)
                    }
                }
            }
        }

        if (insN.isEmpty()) return listOf(text)

        val sorted = insN.distinct().sorted()
        val parts = mutableListOf<String>()
        var prev = 0
        for (pos in sorted) {
            if (pos > prev && pos < text.length) {
                parts.add(text.substring(prev, pos).trim())
                prev = pos
            }
        }
        if (prev < text.length) {
            parts.add(text.substring(prev).trim())
        }
        return parts.filter { it.isNotEmpty() }
    }

    // ========== Phase 5: Force-split long paragraphs ==========

    private fun forceSplit(text: String): List<String> {
        if (text.length <= MAX_LINE_LEN * 2) return listOf(text)

        val sentences = text.split(RE_SENTENCE_SPLIT)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (sentences.size >= 3) {
            val result = mutableListOf<String>()
            val buf = StringBuilder()
            for (s in sentences) {
                if (buf.isNotEmpty() && buf.length + s.length > MAX_LINE_LEN) {
                    result.add(buf.toString().trim())
                    buf.clear()
                }
                buf.append(s)
            }
            if (buf.isNotEmpty()) result.add(buf.toString().trim())
            return result
        }

        val midPos = mutableListOf<Int>()
        for (i in text.indices) {
            if (text[i] in MARK_MID) midPos.add(i)
        }
        if (midPos.isNotEmpty()) {
            val splitAt = midPos[midPos.size / 2]
            val a = text.substring(0, splitAt + 1).trim()
            val b = text.substring(splitAt + 1).trim()
            return listOfNotNull(a.ifBlank { null }, b.ifBlank { null })
        }

        return listOf(text)
    }

    // ========== Phase 6: Fix broken quotes ==========

    private fun fixBrokenQuotes(paragraphs: List<String>): List<String> {
        val result = paragraphs.map { it.trimStart() }.toMutableList()
        for (i in 0 until result.size - 1) {
            val cur = result[i]
            val nxt = result[i + 1]
            val curLast = cur.lastOrNull()
            val nxtFirst = nxt.firstOrNull()
            if (curLast == DQ_OPEN.toChar() || curLast == SQ_OPEN.toChar()) {
                result[i] = cur.dropLast(1)
                result[i + 1] = DQ_OPEN.toChar().toString() + nxt
            }
            if (nxtFirst == DQ_CLOSE.toChar() || nxtFirst == SQ_CLOSE.toChar()) {
                result[i] = cur + DQ_CLOSE.toChar()
                result[i + 1] = nxt.drop(1)
            }
        }
        return result
    }
}

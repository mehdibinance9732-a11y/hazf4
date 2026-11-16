package com.example.itemexporter

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A piece of text read from the accessibility tree together with its on-screen bounds. */
data class RawNode(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val cy: Int get() = (top + bottom) / 2
    val cx: Int get() = (left + right) / 2
    val h: Int get() = max(bottom - top, 1)
}

data class Item(
    val name: String,
    val barcode: String,
    val stock: String,
    val waste: String,
    val price: Long?,
    val note: String
) {
    /** Note without the decorative ** markers: this is the column to sort/filter by. */
    val noteClean: String
        get() = note.replace('*', ' ').replace(Regex("\\s+"), " ").trim()

    /** A card whose name and price were both read (a card clipped by the screen edge is not complete). */
    val complete: Boolean get() = name.isNotEmpty() && price != null && stock.isNotEmpty() && waste.isNotEmpty()

    /** Higher = more complete. Used to replace half-visible cards seen earlier while scrolling. */
    val score: Int
        get() = name.length + note.length +
            (if (price != null) 1000 else 0) +
            (if (stock.isNotEmpty()) 50 else 0) +
            (if (waste.isNotEmpty()) 50 else 0)
}

object Parser {
    private val barcodeRe = Regex("^\\d{8,14}$")
    private val priceRe = Regex("^\\d{1,3}(,\\d{3})+$")
    private val digitsRe = Regex("^\\d+$")
    private val numericOnlyRe = Regex("^[\\d.,%\\s:/-]+$")
    private val numberRe = Regex("^\\d+(\\.\\d+)?%?$")
    private val firstNumberRe = Regex("\\d+(?:\\.\\d+)?")
    private val totalLabelRe = Regex("^تعداد\\s*:?$")
    private val totalMergedRe = Regex("^تعداد\\s*:\\s*(\\d+)$")
    private val spacesRe = Regex("\\s+")
    private val headerWords = listOf("تعداد", "مجموع", "نام یا بارکد", "حذف ", "فعال سازی", "نظرسنجی", "مخفی")

    /** Unifies Persian/Arabic digits, separators and Arabic letter variants. */
    fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            when (c) {
                in '\u06F0'..'\u06F9' -> sb.append('0' + (c - '\u06F0'))
                in '\u0660'..'\u0669' -> sb.append('0' + (c - '\u0660'))
                '\u066C', '\u060C' -> sb.append(',')
                '\u066B' -> sb.append('.')
                '\u064A' -> sb.append('\u06CC') // ي -> ی
                '\u0643' -> sb.append('\u06A9') // ك -> ک
                '\u00A0' -> sb.append(' ')
                '\u200F', '\u200E', '\u202A', '\u202B', '\u202C', '\u2066', '\u2067', '\u2068', '\u2069' -> {}
                else -> sb.append(c)
            }
        }
        return sb.toString().trim()
    }

    private fun isBarcode(t: String) = barcodeRe.matches(t.replace(" ", ""))

    private fun priceOf(text: String): Long? {
        val hasRial = text.contains("ریال")
        val t = text.replace("ریال", "").trim()
        if (priceRe.matches(t)) return t.replace(",", "").toLongOrNull()
        if (hasRial && digitsRe.matches(t)) return t.toLongOrNull()
        return null
    }

    private fun isNoiseBasic(t: String): Boolean =
        t.startsWith("موجودی") || t.startsWith("درصد ضایعات") || t == "انتخاب" || t == "ریال" ||
            numericOnlyRe.matches(t) || isBarcode(t) || priceOf(t) != null

    private fun isNoise(t: String): Boolean = headerWords.any { t.startsWith(it) } || isNoiseBasic(t)

    private fun numberAfterColon(label: String): String =
        if (label.contains(':')) firstNumberRe.find(label.substringAfter(':'))?.value ?: "" else ""

    // ------------------------------------------------------------------ page total

    /** Total number of items shown in the page header ("تعداد: 1192"), if visible. */
    fun parseTotal(raw: List<RawNode>): Int? {
        val nodes = raw.map { it.copy(text = normalize(it.text)) }.filter { it.text.isNotEmpty() }.sortedBy { it.cy }
        for (n in nodes) for (line in n.text.split('\n')) {
            totalMergedRe.find(line.trim())?.let { return it.groupValues[1].toIntOrNull() }
        }
        val label = nodes.firstOrNull { totalLabelRe.matches(it.text) } ?: return null
        return numberLeftOf(label, nodes, label.h.coerceIn(16, 80))?.toDoubleOrNull()?.toInt()
    }

    /** Nearest purely numeric node on the same row, placed to the left of [label] (RTL layout). */
    private fun numberLeftOf(label: RawNode, nodes: List<RawNode>, tol: Int): String? =
        nearRow(label, nodes, tol)
            .filter { it !== label && it.cx < label.cx }
            .filter { numberRe.matches(it.text) && it.text.length < 8 }
            .maxByOrNull { it.cx }
            ?.text?.removeSuffix("%")

    /** Nodes (sorted by cy) whose centre lies within [tol] of the label's row. */
    private fun nearRow(label: RawNode, nodes: List<RawNode>, tol: Int): List<RawNode> {
        var lo = 0; var hi = nodes.size
        while (lo < hi) { val m = (lo + hi) ushr 1; if (nodes[m].cy < label.cy - tol) lo = m + 1 else hi = m }
        val out = ArrayList<RawNode>()
        var i = lo
        while (i < nodes.size && nodes[i].cy <= label.cy + tol) { out.add(nodes[i]); i++ }
        return out
    }

    private fun valueFor(label: RawNode, nodes: List<RawNode>, tol: Int): String {
        val inline = numberAfterColon(label.text)
        return if (inline.isNotEmpty()) inline else numberLeftOf(label, nodes, tol) ?: ""
    }

    // ------------------------------------------------------------------ main entry

    /**
     * Turns the visible text nodes of one screen into items.
     * Works for both layouts an app can expose: one node per text (geometry based) and one node per
     * whole card holding several lines (text based).
     */
    fun parse(raw: List<RawNode>, minTop: Int = 0): List<Item> {
        val all = raw.map { it.copy(text = normalize(it.text)) }.filter { it.text.isNotEmpty() && it.cy >= minTop }

        val positioned = ArrayList<Pair<Int, Item>>()
        val simple = ArrayList<RawNode>()
        for (n in all) {
            val lines = n.text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.size >= 3 && lines.any { isBarcode(it) }) {
                parseCardLines(lines)?.let { positioned.add(n.top to it) }
            } else if (lines.size > 1) {
                // several lines in one node: give each line its own slice of the node's bounds
                val lh = max((n.bottom - n.top) / lines.size, 1)
                lines.forEachIndexed { i, l ->
                    simple.add(RawNode(l, n.left, n.top + i * lh, n.right, n.top + (i + 1) * lh))
                }
            } else simple.add(n.copy(text = lines.firstOrNull() ?: n.text))
        }
        for (item in parseGeometry(simple)) positioned.add(item)
        return positioned.sortedBy { it.first }.map { it.second }
    }

    // ------------------------------------------------------------------ text based (whole card in one node)

    private fun parseCardLines(lines: List<String>): Item? {
        val bi = lines.indexOfFirst { isBarcode(it) }
        if (bi < 0) return null
        var stock = ""
        var waste = ""
        var price: Long? = null
        val name = ArrayList<String>()
        val note = ArrayList<String>()
        var seenPrice = false

        fun valueOfLabel(i: Int): String {
            val inline = numberAfterColon(lines[i])
            if (inline.isNotEmpty()) return inline
            val next = lines.getOrNull(i + 1)
            return if (next != null && numberRe.matches(next)) next.removeSuffix("%") else ""
        }

        for ((i, l) in lines.withIndex()) {
            when {
                i == bi || l == "انتخاب" || l == "ریال" -> {}
                l.startsWith("موجودی") -> if (stock.isEmpty()) stock = valueOfLabel(i)
                l.startsWith("درصد ضایعات") -> if (waste.isEmpty()) waste = valueOfLabel(i)
                priceOf(l) != null -> if (price == null) { price = priceOf(l); seenPrice = true }
                numericOnlyRe.matches(l) -> {
                    // a bare number right before/after "ریال" is the price (e.g. 500 ریال)
                    val near = listOfNotNull(lines.getOrNull(i - 1), lines.getOrNull(i + 1))
                    if (price == null && near.any { it == "ریال" } && digitsRe.matches(l)) {
                        price = l.toLongOrNull(); seenPrice = true
                    }
                }
                headerWords.any { l.startsWith(it) } -> {}
                i < bi && !seenPrice -> name.add(l)
                else -> note.add(l)
            }
        }
        return Item(
            name = name.joinToString(" ").replace(spacesRe, " ").trim(),
            barcode = lines[bi].replace(" ", ""),
            stock = stock, waste = waste, price = price,
            note = note.joinToString(" ").replace(spacesRe, " ").trim()
        )
    }

    // ------------------------------------------------------------------ geometry based (one node per text)

    private fun parseGeometry(input: List<RawNode>): List<Pair<Int, Item>> {
        val nodes = input.sortedWith(compareBy({ it.cy }, { -it.left }))
        val barcodes = nodes.filter { isBarcode(it.text) }.sortedBy { it.cy }
        if (barcodes.isEmpty()) return emptyList()

        // unit = typical text height; every tolerance below is relative to it (works at any density)
        val hs = nodes.map { it.h }.sorted()
        val u = hs[hs.size / 2].coerceIn(14, 120)
        val half = u / 2 + 4

        val cys = IntArray(nodes.size) { nodes[it].cy }
        /** first index whose cy >= v (nodes are sorted by cy) */
        fun lowerIdx(v: Int): Int {
            var lo = 0; var hi = cys.size
            while (lo < hi) { val m = (lo + hi) ushr 1; if (cys[m] < v) lo = m + 1 else hi = m }
            return lo
        }

        // rial markers and price flags are computed once; the card loop below only looks at a small window
        val isRial = BooleanArray(nodes.size) { nodes[it].text == "ریال" }
        val endsRial = BooleanArray(nodes.size) { nodes[it].text.endsWith("ریال") }
        val isPriceArr = BooleanArray(nodes.size) { idx ->
            val n = nodes[idx]
            if (priceOf(n.text) != null) true
            else if (digitsRe.matches(n.text) && !isBarcode(n.text) && n.text.length <= 7) {
                // a bare number sitting on the same row as a lone "ریال" (e.g. 500  ریال)
                var found = false
                var j = lowerIdx(n.cy - half)
                while (j < nodes.size && cys[j] <= n.cy + half) { if (j != idx && isRial[j]) { found = true; break }; j++ }
                found
            } else false
        }
        fun priceValue(n: RawNode): Long? = priceOf(n.text) ?: n.text.toLongOrNull()

        val gaps = barcodes.zipWithNext { a, b -> b.cy - a.cy }.sorted()
        val pitch = if (gaps.isNotEmpty()) gaps[gaps.size / 2] else 0
        val span = if (pitch > 0) (pitch * 0.75).toInt() else u * 6

        val result = ArrayList<Pair<Int, Item>>()
        for ((bi, b) in barcodes.withIndex()) {
            val prev = barcodes.getOrNull(bi - 1)
            val next = barcodes.getOrNull(bi + 1)

            // window of node indices that can belong to this card
            val from = lowerIdx(b.cy - span - u)
            val to = lowerIdx((next?.cy ?: (b.cy + 2 * span)) + b.h)   // exclusive
            val idxs = (from until to)
            fun priceRowNear(cy: Int, tol: Int): Boolean =
                idxs.any { (isPriceArr[it] || endsRial[it]) && abs(cys[it] - cy) <= tol }

            val priceIdx = idxs.filter { isPriceArr[it] && cys[it] > b.cy && (next == null || cys[it] < next.cy) }
                .minByOrNull { cys[it] }
            val priceNode = priceIdx?.let { nodes[it] }

            // everything above this line belongs to the previous card (its note row included)
            val prevEnd = if (prev != null) {
                idxs.filter { isPriceArr[it] && cys[it] > prev.cy && cys[it] < b.cy }
                    .minByOrNull { cys[it] }?.let { nodes[it].bottom } ?: prev.cy
            } else Int.MIN_VALUE
            val lower = max(prevEnd, b.cy - span)

            // ---- name: lines stacked directly above the barcode ----
            val nameNodes = idxs.map { nodes[it] }.filter { n ->
                n.cy > lower && n.cy < b.cy - b.h / 2 &&
                    !isNoise(n.text) && !priceRowNear(n.cy, half)
            }
            val lines = ArrayList<MutableList<RawNode>>()
            for (n in nameNodes) {
                val last = lines.lastOrNull()
                if (last != null && abs(last.first().cy - n.cy) <= max(n.h, u) / 2) last.add(n)
                else lines.add(mutableListOf(n))
            }
            val chain = ArrayList<MutableList<RawNode>>()
            for (line in lines.asReversed()) {
                val h = max(line.maxOf { it.h }, u)
                val below = chain.lastOrNull()
                if (below == null || (below.first().cy - line.first().cy) <= h * 2.2) chain.add(line) else break
            }
            chain.reverse()
            val name = chain.joinToString(" ") { line ->
                line.sortedByDescending { it.right }.joinToString(" ") { it.text }
            }.replace(spacesRe, " ").trim()

            // ---- stock / waste: labels START with the text ("بررسی موجودی…" is a note) ----
            val near = idxs.map { nodes[it] }.filter { n ->
                n.cy > lower && n.cy <= b.cy + b.h && !priceRowNear(n.cy, half)
            }
            val stock = near.firstOrNull { it.text.startsWith("موجودی") }?.let { valueFor(it, nodes, u) } ?: ""
            val waste = near.firstOrNull { it.text.startsWith("درصد ضایعات") }?.let { valueFor(it, nodes, u) } ?: ""

            // ---- note: same row as the price ----
            val note = priceNode?.let { p ->
                idxs.filter { j ->
                    val n = nodes[j]
                    n !== p && abs(n.cy - p.cy) <= max(p.h, u) &&
                        !endsRial[j] && !isPriceArr[j] && n.text.length > 1 &&
                        !headerWords.any { n.text.startsWith(it) }
                }.map { nodes[it] }.sortedByDescending { it.right }.joinToString(" ") { it.text }
            } ?: ""

            result.add(
                b.cy to Item(
                    name = name,
                    barcode = b.text.replace(" ", ""),
                    stock = stock,
                    waste = waste,
                    price = priceNode?.let { priceValue(it) },
                    note = note.replace(spacesRe, " ").trim()
                )
            )
        }
        return result
    }
}

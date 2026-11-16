package com.example.itemexporter

/**
 * Collects items while the USER scrolls. Pure logic (no Android classes) so it can be unit-tested.
 * Items are keyed by barcode; a card clipped by the screen edge is replaced when a fuller copy is seen.
 */
class CaptureSession {
    val items = LinkedHashMap<String, Item>()
    var total: Int? = null

    val completeCount: Int get() = items.values.count { it.complete }
    val isDone: Boolean get() = total?.let { completeCount >= it } ?: false

    /**
     * Merges one screen. Returns false when the screen shares no card with what was already
     * collected, i.e. the user scrolled so fast that cards in between were probably skipped.
     */
    fun add(parsed: List<Item>): Boolean {
        if (parsed.isEmpty()) return true
        val known = parsed.count { it.barcode in items }
        val connected = items.isEmpty() || known >= 1
        for (item in parsed) {
            val old = items[item.barcode]
            if (old == null || item.score > old.score) items[item.barcode] = item
        }
        return connected
    }
}

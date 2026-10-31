package com.bohemian.odoo.pos.odoo

/** A customer as the index reads them, with the E.164 mobile Odoo searches on (`phone_sanitized`). */
data class IndexedCustomer(val customer: Customer, val phoneSanitized: String)

/**
 * Every customer with a mobile, held on the till (0.9.0), so "Name or mobile" finds a customer by the
 * first or the last digits with no call to Odoo. In a live rush the customer search was a quarter of all
 * calls; 3,281 customers have a mobile (31 Oct), about 640 KB as read.
 *
 * Two arrays: the Indian numbers sorted by their 10 digits, and every number sorted by its digits reversed.
 * A typed prefix of either is a binary search, then a scan over the matches only. The match is Odoo's own
 * ([CustomerService.phoneDomain]): the mobile ends with the digits, or its Indian number starts with them.
 *
 * In memory only, never written to the till's storage. A customer missing here (saved at another store
 * since the last update) is asked of Odoo, and a picked customer is read again from Odoo with their card.
 */
class CustomerIndex {

    private class Entry(val c: IndexedCustomer, val indian: String?, val reversed: String, val sortKey: String)

    private class Snapshot(
        val byId: Map<Int, Entry>,
        val byPhone: Map<String, List<Entry>>,
        /** Indian numbers, sorted by the 10 digits. */
        val byNumber: Array<Entry>,
        /** Every number, sorted by its digits reversed. */
        val byReversed: Array<Entry>,
    )

    @Volatile private var snap = build(emptyMap())

    /** When the index was last loaded in full (0 = never). */
    @Volatile var loadedAt = 0L
        private set

    val ready: Boolean get() = loadedAt > 0
    val size: Int get() = snap.byId.size

    /** Everything again, from a full read. */
    @Synchronized fun replaceAll(rows: List<IndexedCustomer>, at: Long) {
        snap = build(rows.mapNotNull { r -> entry(r)?.let { r.customer.id to it } }.toMap())
        loadedAt = at
    }

    /** A delta: [changed] customers in again with what Odoo has now; [removed] ids out (archived, or no mobile). */
    @Synchronized fun apply(changed: List<IndexedCustomer>, removed: Collection<Int>) {
        if (changed.isEmpty() && removed.isEmpty()) return
        val m = snap.byId.toMutableMap()
        removed.forEach { m.remove(it) }
        changed.forEach { row -> entry(row)?.let { m[row.customer.id] = it } ?: m.remove(row.customer.id) }
        snap = build(m)
    }

    /** One customer just saved or read again on this till. */
    fun put(customer: Customer, phoneSanitized: String?) {
        if (phoneSanitized.isNullOrBlank()) return
        apply(listOf(IndexedCustomer(customer, phoneSanitized)), emptyList())
    }

    /** The customer with exactly this E.164 mobile, as Odoo's `phone_sanitized = …` would find. */
    fun exact(e164: String): Customer? = snap.byPhone[e164]?.firstOrNull()?.c?.customer

    /**
     * Customers whose mobile ends with the typed digits, or whose Indian number starts with them, A→Z;
     * one page of [limit] from [offset]. Empty under [CustomerService.MIN_DIGITS] digits.
     */
    fun byPhone(typed: String, offset: Int = 0, limit: Int = CustomerService.PAGE): List<Customer> {
        val digits = CustomerService.nationalDigits(typed)
        if (digits.length < CustomerService.MIN_DIGITS) return emptyList()
        val s = snap
        val found = LinkedHashMap<Int, Entry>()
        scan(s.byNumber, digits) { it.indian!! }.forEach { found[it.c.customer.id] = it }
        scan(s.byReversed, digits.reversed()) { it.reversed }.forEach { found[it.c.customer.id] = it }
        return found.values.sortedWith(ORDER).drop(offset).take(limit).map { it.c.customer }
    }

    companion object {
        /** Odoo's "name, id". */
        private val ORDER = compareBy<Entry>({ it.sortKey }, { it.c.customer.id })

        private fun entry(row: IndexedCustomer): Entry? {
            val digits = row.phoneSanitized.filter { it.isDigit() }
            if (digits.isEmpty()) return null
            val indian = row.phoneSanitized.takeIf { it.startsWith("+91") }?.removePrefix("+91")
            return Entry(row, indian, digits.reversed(), row.customer.name.lowercase())
        }

        private fun build(byId: Map<Int, Entry>): Snapshot {
            val all = byId.values
            return Snapshot(
                byId = byId,
                byPhone = all.groupBy { it.c.phoneSanitized }.mapValues { (_, v) -> v.sortedWith(ORDER) },
                byNumber = all.filter { it.indian != null }.sortedBy { it.indian }.toTypedArray(),
                byReversed = all.sortedBy { it.reversed }.toTypedArray(),
            )
        }

        /** The entries whose key starts with [prefix]: the first by binary search, then while they match. */
        private inline fun scan(sorted: Array<Entry>, prefix: String, key: (Entry) -> String): List<Entry> {
            var lo = 0
            var hi = sorted.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (key(sorted[mid]) < prefix) lo = mid + 1 else hi = mid
            }
            val out = ArrayList<Entry>()
            while (lo < sorted.size && key(sorted[lo]).startsWith(prefix)) out += sorted[lo++]
            return out
        }
    }
}

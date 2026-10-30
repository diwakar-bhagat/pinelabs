package com.bohemian.odoo.pos.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The ViewModel's init block must come after every property: Kotlin initializes in source order, and
 * 0.5.0's init near the top wrote to `_partlyPaid` before it existed (a NullPointerException on every
 * start, seen at Kite Gurugram on 30 Oct). Checked on the source, since the ViewModel needs Android.
 */
class PosViewModelOrderTest {
    @Test fun `init runs after every property of the ViewModel is set`() {
        val lines = File("src/main/java/com/bohemian/odoo/pos/ui/PosViewModel.kt").readLines()
        val classStart = lines.indexOfFirst { it.startsWith("class PosViewModel(") }
        val init = lines.indexOfFirst { it == "    init {" }
        val companion = lines.indexOfFirst { it == "    companion object {" }
        assertTrue("init block not found", classStart in 0 until init)
        val property = Regex("""^    (private |internal |protected )?(val|var) """)
        val late = (init until companion).filter { property.containsMatchIn(lines[it]) }
        assertTrue("properties after init: ${late.map { lines[it].trim() }}", late.isEmpty())
    }
}

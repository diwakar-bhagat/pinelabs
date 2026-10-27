package com.bohemian.odoo.pos.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PosLogTest {
    @Test fun `free-text error values are redacted`() {
        assertEquals(
            "[X] err=Partner ******3210 invalid detail=bearer *** msg=null",
            PosLog.line("X", arrayOf("err" to "Partner 9876543210 invalid", "detail" to "bearer abc", "msg" to null)),
        )
    }

    @Test fun `structured ids stay readable for reconciliation`() {
        assertEquals("[R] ref=CFG4261027101010123 rrn=123456789012",
            PosLog.line("R", arrayOf("ref" to "CFG4261027101010123", "rrn" to "123456789012")))
    }
}

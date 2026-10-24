package com.bohemian.odoo.pos.receipt

import com.bohemian.odoo.pos.core.Paise
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class TicketQrTest {

    private val url = TicketQr.ticketUrl("https://shop.example.test", "tok_abc123")

    @Test fun `ticket url matches Odoo's receipt link`() {
        assertEquals("https://shop.example.test/pos/ticket/validate?access_token=tok_abc123", url)
    }

    @Test fun `trailing slash on base is not doubled`() {
        assertEquals(url, TicketQr.ticketUrl("https://shop.example.test/", "tok_abc123"))
    }

    @Test fun `refuses non-https base and blank token`() {
        assertThrows(IllegalArgumentException::class.java) { TicketQr.ticketUrl("http://insecure.test", "t") }
        assertThrows(IllegalArgumentException::class.java) { TicketQr.ticketUrl("https://shop.example.test", " ") }
    }

    @Test fun `png has the PNG signature`() {
        val png = TicketQr.pngBytes(url)
        assertEquals(0x89.toByte(), png[0])
        assertEquals('P'.code.toByte(), png[1])
        assertEquals('N'.code.toByte(), png[2])
        assertEquals('G'.code.toByte(), png[3])
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test fun `base64 png decodes to the same bytes`() {
        val b64 = TicketQr.pngBase64(url)
        assertEquals(TicketQr.pngBytes(url).toList(), Base64.Default.decode(b64).toList())
    }

    @Test fun `the printed QR decodes back to the ticket url`() {
        // Full round trip: our PNG → independent PNG reader below → zxing QR reader → the original link.
        val (w, h, pixels) = readGreyPng(TicketQr.pngBytes(url))
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(w, h, pixels)))
        val result = MultiFormatReader().decode(bitmap, mapOf(DecodeHintType.TRY_HARDER to true))
        assertEquals(url, result.text)
    }

    /** Minimal reader for the 1-bit greyscale, unfiltered PNG that TicketQr writes. */
    private fun readGreyPng(png: ByteArray): Triple<Int, Int, IntArray> {
        var pos = 8 // skip signature
        var w = 0; var h = 0
        val idat = ByteArrayOutputStream()
        while (pos < png.size) {
            val len = readInt(png, pos); val type = String(png, pos + 4, 4, Charsets.US_ASCII)
            val data = png.copyOfRange(pos + 8, pos + 8 + len)
            when (type) {
                "IHDR" -> {
                    w = readInt(data, 0); h = readInt(data, 4)
                    assertEquals(1, data[8].toInt())   // bit depth
                    assertEquals(0, data[9].toInt())   // greyscale
                }
                "IDAT" -> idat.write(data)
            }
            pos += 12 + len // length + type + data + crc
        }
        val inflater = Inflater()
        inflater.setInput(idat.toByteArray())
        val raw = ByteArray(h * (1 + (w + 7) / 8))
        var got = 0
        while (got < raw.size && !inflater.finished()) got += inflater.inflate(raw, got, raw.size - got)
        inflater.end()
        val rowBytes = (w + 7) / 8
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            assertEquals(0, raw[y * (1 + rowBytes)].toInt()) // filter type 0 only
            for (x in 0 until w) {
                val byte = raw[y * (1 + rowBytes) + 1 + x / 8].toInt()
                val white = (byte shr (7 - x % 8)) and 1 == 1
                pixels[y * w + x] = if (white) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
            }
        }
        return Triple(w, h, pixels)
    }

    private fun readInt(b: ByteArray, at: Int) =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)

    @Test fun `receipt with an e-bill link prints one native QR line, without one prints none`() {
        val data = receipt(qr = null)
        assertFalse(ReceiptFormatter(32).format(data).any { it.qrText != null || it.imageBase64 != null })

        val withQr = receipt(qr = url)
        val lines = ReceiptFormatter(32).format(withQr)
        assertEquals(listOf(url), lines.mapNotNull { it.qrText })
        assertTrue(lines.none { it.imageBase64 != null })
        assertTrue(lines.any { it.text.contains("Scan for your e-bill") })
    }

    private fun receipt(qr: String?) = ReceiptData(
        storeName = "ZenZebra",
        storeLines = listOf("ZenZebra - Okhla"),
        orderRef = "ZenZebra - 000012",
        dateTime = "23-Oct-2026 12:48",
        items = listOf(ReceiptItem("Monster Energy drink ultra can 350ml", 1, Paise.ofRupees(125), Paise.ofRupees(125))),
        subtotalExcl = Paise(5356),
        tax = Paise(2144),
        total = Paise.ofRupees(75),
        paymentLabel = "CASH",
        paymentRef = null,
        authCode = null,
        cardLast4 = null,
        customerName = "Diwakar",
        footer = listOf("Thank you for shopping!"),
        eBillUrl = qr,
    )
}

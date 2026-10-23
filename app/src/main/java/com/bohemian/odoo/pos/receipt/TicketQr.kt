package com.bohemian.odoo.pos.receipt

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The e-bill QR, the same link Odoo's own POS receipt prints:
 * `<base>/pos/ticket/validate?access_token=<pos.order.access_token>`
 * (addons/point_of_sale/static/src/app/screens/receipt_screen/receipt/order_receipt.js).
 * The customer scans it to open the bill and request an invoice.
 *
 * Pure JVM + zxing core, so it runs on the E700 and in unit tests. The PNG is written
 * by hand (1-bit greyscale) because javax.imageio and android.graphics are not both available.
 */
object TicketQr {

    fun ticketUrl(baseUrl: String, accessToken: String): String {
        require(baseUrl.startsWith("https://")) { "e-bill base URL must be https" }
        require(accessToken.isNotBlank()) { "access token is empty" }
        return "${baseUrl.trimEnd('/')}/pos/ticket/validate?access_token=$accessToken"
    }

    /** QR as a base64 PNG, scaled to roughly [targetPx] pixels with a 4-module quiet zone. */
    @OptIn(ExperimentalEncodingApi::class)
    fun pngBase64(text: String, targetPx: Int = 192): String =
        Base64.Default.encode(pngBytes(text, targetPx))

    internal fun pngBytes(text: String, targetPx: Int = 192): ByteArray {
        val hints = mapOf(EncodeHintType.MARGIN to 0)
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        val modules = matrix.width
        val quiet = 4
        val total = modules + 2 * quiet
        val scale = (targetPx / total).coerceAtLeast(2)
        val size = total * scale

        // PNG greyscale 1-bit: 0 = black, 1 = white. Each row: filter byte 0, then packed bits.
        val rowBytes = (size + 7) / 8
        val raw = ByteArrayOutputStream()
        for (py in 0 until size) {
            raw.write(0)
            val my = py / scale - quiet
            for (byteIdx in 0 until rowBytes) {
                var b = 0
                for (bit in 0 until 8) {
                    val px = byteIdx * 8 + bit
                    val white = if (px >= size) true else {
                        val mx = px / scale - quiet
                        !(mx in 0 until modules && my in 0 until modules && matrix.get(mx, my))
                    }
                    if (white) b = b or (1 shl (7 - bit))
                }
                raw.write(b)
            }
        }
        val deflated = ByteArrayOutputStream().also { out ->
            DeflaterOutputStream(out, Deflater(Deflater.BEST_COMPRESSION)).use { it.write(raw.toByteArray()) }
        }.toByteArray()

        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        val ihdr = ByteArrayOutputStream().apply {
            writeInt(size); writeInt(size)
            write(1)   // bit depth
            write(0)   // colour type: greyscale
            write(0); write(0); write(0) // compression, filter, interlace
        }.toByteArray()
        chunk(out, "IHDR", ihdr)
        chunk(out, "IDAT", deflated)
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        out.write(intBytes(data.size))
        out.write(typeBytes)
        out.write(data)
        val crc = CRC32().apply { update(typeBytes); update(data) }.value.toInt()
        out.write(intBytes(crc))
    }

    private fun intBytes(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun ByteArrayOutputStream.writeInt(v: Int) = write(intBytes(v))
}

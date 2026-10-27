package com.bohemian.odoo.pos.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/** The Odoo company logo, made printable on 58 mm thermal paper. */
internal object BillLogo {
    /** About 40 % of a 384-dot head, like the logo's share of the PDF's width. */
    private const val WIDTH_DOTS = 160

    /** res.company.logo (base64) → a small PNG on white (transparent pixels would print black), base64. */
    fun forPrinter(base64: String): String? = runCatching {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val h = (src.height * WIDTH_DOTS / src.width.toFloat()).roundToInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(WIDTH_DOTS, h, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(Bitmap.createScaledBitmap(src, WIDTH_DOTS, h, true), 0f, 0f, null)
        }
        ByteArrayOutputStream().use { s ->
            out.compress(Bitmap.CompressFormat.PNG, 100, s)
            Base64.encodeToString(s.toByteArray(), Base64.NO_WRAP)
        }
    }.getOrNull()
}

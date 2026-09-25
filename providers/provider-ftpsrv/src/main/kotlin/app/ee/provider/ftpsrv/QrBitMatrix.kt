package app.ee.provider.ftpsrv

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Pure-JVM QR encoding (ZXing) → ARGB pixel array. The Android layer turns
 * the array into a Bitmap; keeping this artifact-free makes it unit-testable.
 */
object QrBitMatrix {

    const val DEFAULT_SIZE = 288

    /**
     * @return [size]x[size] ARGB_8888 pixels (opaque black modules on white)
     * @throws IllegalArgumentException for content too long for QR
     */
    fun encode(content: String, size: Int = DEFAULT_SIZE): IntArray {
        require(size >= 33) { "QR needs at least 33px" }
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        val out = IntArray(size * size) { 0xFFFFFFFF.toInt() }
        for (y in 0 until size) {
            for (x in 0 until size) {
                if (matrix.get(x, y)) {
                    out[y * size + x] = 0xFF000000.toInt()
                }
            }
        }
        return out
    }
}

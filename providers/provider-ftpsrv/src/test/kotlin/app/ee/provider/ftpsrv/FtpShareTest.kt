package app.ee.provider.ftpsrv

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanAddressTest {

    @Test
    fun localIpv4ReturnsIpv4OrNull() {
        val ip = LanAddress.localIpv4()
        if (ip != null) {
            assertTrue("expected IPv4, got $ip", ip.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}")))
        }
    }

    @Test
    fun shareUrlEmbedsGuestCredentials() {
        val server = FtpShareServer(File("/tmp/ee-share-test"))
        val url = server.shareUrl("192.168.1.5", 2121)
        assertEquals("ftp://ee:ee@192.168.1.5:2121/", url)
    }

    @Test
    fun qrEncodesToArPixels() {
        val pixels = QrBitMatrix.encode("ftp://ee:ee@192.168.1.5:2121/", QrBitMatrix.DEFAULT_SIZE)
        assertEquals(QrBitMatrix.DEFAULT_SIZE * QrBitMatrix.DEFAULT_SIZE, pixels.size)
        assertTrue("QR should contain black modules", pixels.any { it == 0xFF000000.toInt() })
        assertTrue("QR should contain white background", pixels.any { it == 0xFFFFFFFF.toInt() })
    }
}

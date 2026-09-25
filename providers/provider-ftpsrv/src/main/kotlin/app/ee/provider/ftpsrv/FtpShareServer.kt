package app.ee.provider.ftpsrv

import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.PasvDataConnectionConfiguration
import org.apache.ftpserver.ftplet.FtpUser
import org.apache.ftpserver.impl.DefaultFtpServerFactory
import org.apache.ftpserver.impl.DefaultUserFactory
import org.apache.ftpserver.listener.ListenerFactory
import java.io.File
import java.net.ServerSocket

/**
 * Embedded LAN FTP server (P1-5 — مشاركة الملفات عبر الشبكة المحلية).
 *
 * Apache FtpServer rooted at [rootDir] with a single guest account
 * ([DEFAULT_USER]/[DEFAULT_PASS] — the URL with embedded credentials is what
 * the QR code encodes, so any client can connect with one scan).
 *
 * Runs on its own JVM threads — no Android Service needed while the app
 * process lives (background-resilience via foreground notification is an M4
 * item). Data connections use passive mode on a fixed high port range.
 */
class FtpShareServer(private val rootDir: File) {

    @Volatile
    private var server: FtpServer? = null

    @Volatile
    private var boundPort: Int = 0

    val active: Boolean get() = server != null
    val port: Int get() = boundPort

    /** Binds a free control port (candidates [2121, 2141)) and starts serving. */
    fun start(): Int {
        stop()
        require(rootDir.exists() || rootDir.mkdirs()) { "share root missing: $rootDir" }

        val socket = (FIRST_PORT..LAST_PORT)
            .mapNotNull { runCatching { ServerSocket(it) }.getOrNull() }
            .firstOrNull()
            ?: throw IllegalStateException("no free FTP port in $FIRST_PORT..$LAST_PORT")

        try {
            val factory: FtpServerFactory = DefaultFtpServerFactory()
            val ftp = factory.createServer()

            val listener = ListenerFactory().apply {
                server = ftp
                port = socket.localPort
                this.socket = socket
            }.createListener()
            ftp.listener = listener

            ftp.dataConnectionConfiguration = PasvDataConnectionConfiguration().apply {
                portRangeBegin = DATA_PORT_START
                portRangeEnd = DATA_PORT_END
            }

            ftp.userManager = DefaultUserFactory(
                mapOf(
                    DEFAULT_USER to FtpUser().apply {
                        name = DEFAULT_USER
                        password = DEFAULT_PASS
                        homeDirectory = rootDir.absolutePath
                        enabled = true
                        writePermission = true
                    },
                ),
            )

            ftp.start()
            server = ftp
            boundPort = socket.localPort
            return boundPort
        } catch (e: Exception) {
            stop()
            throw e
        }
    }

    fun stop() {
        val s = server
        server = null
        boundPort = 0
        runCatching { s?.stop() }
    }

    /** Connection URL with embedded guest credentials (what the QR encodes). */
    fun shareUrl(host: String, port: Int): String =
        "ftp://$DEFAULT_USER:$DEFAULT_PASS@$host:$port/"

    companion object {
        const val DEFAULT_USER = "ee"
        const val DEFAULT_PASS = "ee"
        const val FIRST_PORT = 2121
        const val LAST_PORT = 2141
        const val DATA_PORT_START = 50000
        const val DATA_PORT_END = 50099
    }
}

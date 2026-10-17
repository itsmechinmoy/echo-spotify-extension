package dev.brahmkshatriya.echo.extension.spotify

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Minimal loopback HTTP server for cached MPD bytes.
 *
 * Playback serves the MPD as a file: URI (proven on stock Exo), but
 * manifest-fetching downloaders (OkHttp / Exo HttpDataSource, the only
 * stacks a downloader can use for the license + manifest exchange) speak
 * http(s) only — a file: manifest is unreadable to them while the audio
 * path works because it is plain https. Serving the IDENTICAL bytes over
 * 127.0.0.1 keeps one code path correct for both: http works everywhere
 * file: does, plus everywhere it doesn't.
 *
 * Loopback cleartext is OS-exempt (no manifest flag needed); the socket
 * binds 127.0.0.1 on an ephemeral port, serves only /mv/<name> from
 * registered absolute paths, and lives in a static daemon so ext-instance
 * churn can't kill it mid-download. Never throws (null = use file: URI).
 */
object MpdServer {

    @Volatile
    private var port: Int = 0
    private val lock = Any()
    private val paths = LinkedHashMap<String, String>()

    /** Register [mpdFile] and return its http loopback URL, or null. */
    fun serve(mpdFile: File): String? = runCatching {
        val p = synchronized(lock) {
            paths[mpdFile.name] = mpdFile.absolutePath
            while (paths.size > 64) paths.remove(paths.keys.first())
            if (port == 0) {
                val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
                port = server.localPort
                Thread({
                    while (!server.isClosed) {
                        runCatching { handle(server.accept()) }
                    }
                }, "mpd-server").apply {
                    isDaemon = true
                    start()
                }
            }
            port
        }
        "http://127.0.0.1:$p/mv/${mpdFile.name}"
    }.getOrNull()

    private fun handle(sock: Socket) {
        sock.use { s ->
            val input = s.getInputStream().bufferedReader()
            val out = s.getOutputStream()
            val line = runCatching { input.readLine() }.getOrNull().orEmpty()
            // Drain headers (bounded) so the client never blocks on us.
            var drained = 0
            while (drained++ < 32) {
                if (runCatching { input.readLine() }.getOrNull().isNullOrEmpty()) break
            }
            val name = line.substringAfter("GET /mv/").substringBefore(' ').trim()
            val bytes = synchronized(lock) { paths[name] }
                ?.let { runCatching { File(it).readBytes() }.getOrNull() }
            if (bytes == null) {
                out.write("HTTP/1.1 404 Not Found\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".toByteArray())
            } else {
                val mime = if (name.endsWith(".mpd", ignoreCase = true)) {
                    "application/dash+xml"
                } else {
                    "video/mp4"
                }
                val head = "HTTP/1.1 200 OK\r\nContent-Type: $mime\r\n" +
                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                out.write(head.toByteArray())
                out.write(bytes)
            }
            out.flush()
        }
    }
}

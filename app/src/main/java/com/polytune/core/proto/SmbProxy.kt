package com.polytune.core.proto

import android.content.Context
import android.util.Log
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File as SmbFile
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.EnumSet
import kotlin.concurrent.thread

/**
 * SMB 本地代理。Media3 只能吃 URI，不能直接读 smb，这里起一个极简 HTTP 服务，
 * 把 smb://host/share/dir/file.mp3 映射成 http://127.0.0.1:port/stream，支持单段 Range。
 *
 * 只实现播放与下载必需的 GET/HEAD，勿当通用 WebDAV 网关。
 */
class SmbProxy private constructor(
    private val ctx: Context,
    private val smbUrl: String,
    private val user: String?,
    private val pass: String?,
) {

    private lateinit var server: ServerSocket
    @Volatile private var running = true
    private var client: SMBClient? = null
    private var disk: DiskShare? = null

    /**
     * 等 accept 线程把端口绑好再返回。旧实现是先 connect 再 serve，companion 又在 connect 期间
     * 把半成品放进池子，别的线程一不小心就拿到 server 还没初始化的实例，访问 url 直接
     * UninitializedPropertyAccessException。
     */
    val url: String get() = "http://127.0.0.1:${server.localPort}/stream"

    private fun samba(): DiskShare {
        disk?.let { return it }
        synchronized(this) {
            disk?.let { return it }
            val (host, share, path) = parse(smbUrl)
            val c = SMBClient()
            val auth = if (user.isNullOrBlank()) AuthenticationContext.anonymous()
            else AuthenticationContext(user, pass?.toCharArray() ?: CharArray(0), null)
            // smb://host:port/share/... 的端口必须带走：默认 445 在真机/测试转发场景常常不可达，
            // 旧实现只取主机名，:4451 这类地址会连到 445 上，或把 host:port 当主机名抛 UnknownHostException。
            val (hostName, port) = splitHostPort(host)
            val conn = if (port > 0) c.connect(hostName, port) else c.connect(hostName)
            val session = conn.authenticate(auth)
            val d = session.connectShare(share) as DiskShare
            client = c
            disk = d
            Log.i(TAG, "SMB 已连接 $hostName:$port/$share/$path")
            return d
        }
    }

    private fun connect() {
        samba()
    }

    private data class SmbParts(val host: String, val share: String, val path: String)

    /** host 可能是 host、host:port 或 [ipv6]:port，拆成主机名与非 0 端口。 */
    internal fun splitHostPort(raw: String): Pair<String, Int> {
        val h = raw.trim()
        if (h.startsWith("[")) {
            val end = h.indexOf(']')
            if (end > 0) {
                val name = h.substring(1, end)
                val port = h.substring(end + 1).removePrefix(":").toIntOrNull() ?: 0
                return name to port
            }
        }
        val colon = h.lastIndexOf(':')
        if (colon > 0 && h.indexOf(':') == colon) {
            val port = h.substring(colon + 1).toIntOrNull()
            if (port != null) return h.substring(0, colon) to port
        }
        return h to 0
    }

    private fun parse(raw: String): SmbParts {
        val s = raw.removePrefix("smb://").removePrefix("smb:\\\\")
        val slash = s.indexOf('/')
        val host = if (slash > 0) s.substring(0, slash) else s
        val rest = if (slash > 0) s.substring(slash + 1) else ""
        val share = rest.substringBefore('/')
        val path = rest.substringAfter('/', "")
        return SmbParts(host.substringBefore('@'), share, path)
    }

    /**
     * 返回从 [start] 起的流与总大小。smbj 的 InputStream 不保证 skip 能吃满整段偏移
     * （服务端按读缓冲返回，skip 常常只走一小截就返回 0），旧实现 skip 不动就 break，
     * 结果「续传」实际从文件头开始灌，downloadedBytes 会写超过真实大小。
     * 这里改成读一段丢一段，保证跳过整个偏移。
     */
    private fun openStream(start: Long): Pair<InputStream, Long> {
        val d = samba()
        val (_, _, path) = parse(smbUrl)
        val f: SmbFile = d.openFile(
            path,
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        )
        val size = f.fileInformation.standardInformation.endOfFile
        val ins = f.inputStream
        var toSkip = start.coerceIn(0L, size)
        val junk = ByteArray(64 * 1024)
        while (toSkip > 0) {
            val n = ins.read(junk, 0, minOf(junk.size.toLong(), toSkip).toInt())
            if (n <= 0) break
            toSkip -= n
        }
        Log.i(TAG, "open path=$path start=$start size=$size skipped=${start - toSkip}")
        return ins to size
    }

    private fun serve() {
        server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val ready = java.util.concurrent.CountDownLatch(1)
        thread(name = "smb-proxy-accept", isDaemon = true) {
            ready.countDown()
            while (running) {
                val sock = runCatching { server.accept() }.getOrNull() ?: break
                thread(name = "smb-proxy-conn", isDaemon = true) { handle(sock) }
            }
        }
        ready.await(3, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun handle(sock: Socket) {
        sock.use { s ->
            val input = s.getInputStream().bufferedReader()
            val requestLine = input.readLine() ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            var rangeStart = 0L
            var rangeEnd = -1L
            var line: String?
            while (true) {
                line = input.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Range:", true)) {
                    // 只支持单段：bytes=a-b / bytes=a- / bytes=-n（后缀长度）
                    val m = Regex("bytes=(\\d*)-(\\d*)").find(line)
                    if (m != null) {
                        val a = m.groupValues[1]
                        val b = m.groupValues[2]
                        when {
                            a.isNotEmpty() -> {
                                rangeStart = a.toLong()
                                rangeEnd = if (b.isNotEmpty()) b.toLong() else -1L
                            }

                            b.isNotEmpty() -> {
                                // 后缀长度要等知道总大小再换算，这里先记负数标记
                                rangeStart = -b.toLong()
                            }
                        }
                    }
                }
            }
            val out: OutputStream = s.getOutputStream()
            runCatching {
                val (stream, size) = openStream(if (rangeStart > 0) rangeStart else 0L)
                // bytes=-n 在这里换算成真实起点
                if (rangeStart < 0) {
                    stream.close()
                    val from = (size + rangeStart).coerceAtLeast(0L)
                    val (s2, _) = openStream(from)
                    writeResponse(out, method, size, from, rangeEnd, s2)
                    return@runCatching
                }
                writeResponse(out, method, size, rangeStart, rangeEnd, stream)
            }.onFailure { Log.w(TAG, "代理响应失败: ${it.javaClass.simpleName}:${it.message}") }
        }
    }

    private fun writeResponse(
        out: OutputStream,
        method: String,
        size: Long,
        start: Long,
        reqEnd: Long,
        stream: InputStream,
    ) {
        stream.use { i ->
            if (start >= size) {
                out.write(
                    ("HTTP/1.1 416 Range Not Satisfiable\r\n" +
                        "Content-Range: bytes */$size\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                        .toByteArray(),
                )
                out.flush()
                return
            }
            val end = if (reqEnd in 0 until size) reqEnd else size - 1
            val length = end - start + 1
            val partial = start > 0 || end < size - 1
            val head = buildString {
                append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                append("Content-Type: audio/mpeg\r\n")
                append("Accept-Ranges: bytes\r\n")
                append("Content-Length: $length\r\n")
                if (partial) append("Content-Range: bytes $start-$end/$size\r\n")
                append("Connection: close\r\n\r\n")
            }
            out.write(head.toByteArray())
            if (method != "HEAD") {
                val buf = ByteArray(128 * 1024)
                var left = length
                while (running && left > 0) {
                    val n = i.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    left -= n
                }
            }
            out.flush()
        }
    }

    fun stop() {
        running = false
        runCatching { server.close() }
        runCatching { disk?.close() }
        runCatching { client?.close() }
    }

    companion object {
        private const val TAG = "SmbProxy"
        private val pools = mutableMapOf<String, SmbProxy>()

        @Synchronized
        fun start(ctx: Context, smbUrl: String, user: String?, pass: String?): SmbProxy {
            pools[smbUrl]?.let { return it }
            val p = SmbProxy(ctx.applicationContext, smbUrl, user, pass)
            p.connect()
            p.serve()
            pools[smbUrl] = p
            return p
        }

        @Synchronized
        fun stopAll() {
            pools.values.forEach { it.stop() }
            pools.clear()
        }
    }
}

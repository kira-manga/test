package com.ukkera.pos.app.gateway.setup.ipc

import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/** One authenticated-by-OS local byte stream between the UI process and the Master service. */
public interface DesktopSetupIpcConnection : Closeable {
    public val input: InputStream
    public val output: OutputStream
}

/** Service-side listener. Each implementation restricts peers with OS access control. */
public interface DesktopSetupIpcEndpoint : Closeable {
    /** Blocks for the next peer; throws once closed. */
    public fun accept(): DesktopSetupIpcConnection
}

internal fun DesktopSetupIpcConnection.dataInput() = DataInputStream(input.buffered())
internal fun DesktopSetupIpcConnection.dataOutput() = DataOutputStream(output.buffered())

/**
 * macOS/Linux Unix-domain socket inside an owner-only (0700) directory owned by the service user.
 * Only that same user can reach it; the directory is refused if it is a link, foreign or wider.
 */
public object DesktopSetupUnixIpc {
    private const val SOCKET = "setup.sock"
    private val ownerOnly = PosixFilePermissions.fromString("rwx------")

    private fun requireOwnerOnlyDirectory(directory: Path): Path {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(ownerOnly))
        }
        check(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) { "IPC directory is not a plain directory" }
        check(Files.getOwner(directory, LinkOption.NOFOLLOW_LINKS).name == System.getProperty("user.name")) { "IPC directory owner" }
        check(Files.getPosixFilePermissions(directory, LinkOption.NOFOLLOW_LINKS) == ownerOnly) { "IPC directory is not owner-only" }
        return directory.resolve(SOCKET)
    }

    public fun listen(directory: Path): DesktopSetupIpcEndpoint {
        val socket = requireOwnerOnlyDirectory(directory)
        Files.deleteIfExists(socket) // A stale socket inside our own owner-only directory.
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX).bind(UnixDomainSocketAddress.of(socket))
        Files.setPosixFilePermissions(socket, PosixFilePermissions.fromString("rw-------"))
        return object : DesktopSetupIpcEndpoint {
            override fun accept(): DesktopSetupIpcConnection = ChannelConnection(server.accept())
            override fun close() { server.close(); runCatching { Files.deleteIfExists(socket) } }
        }
    }

    public fun connect(directory: Path): DesktopSetupIpcConnection =
        ChannelConnection(SocketChannel.open(UnixDomainSocketAddress.of(requireOwnerOnlyDirectory(directory))))

    /** Direct channel streams: [java.nio.channels.Channels] streams would serialize read and write. */
    private class ChannelConnection(private val channel: SocketChannel) : DesktopSetupIpcConnection {
        override val input: InputStream = object : InputStream() {
            override fun read(): Int { val one = ByteArray(1); return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff }
            override fun read(b: ByteArray, off: Int, len: Int): Int = if (len == 0) 0 else channel.read(ByteBuffer.wrap(b, off, len))
            override fun close() = this@ChannelConnection.close()
        }
        override val output: OutputStream = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                val buffer = ByteBuffer.wrap(b, off, len)
                while (buffer.hasRemaining()) if (channel.write(buffer) < 0) throw IOException("closed")
            }
            override fun close() = this@ChannelConnection.close()
        }
        override fun close() { channel.close() }
    }
}

package com.metrolist.shared.soulseek

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** The server refused the credentials. [reason] is the server's code, such as INVALIDPASS. */
class SoulseekLoginException(
    val reason: String,
) : Exception("Soulseek rejected the login: $reason")

/** A transfer that did not complete; the message says why. */
class SoulseekTransferException(
    message: String,
) : Exception(message)

/**
 * A download-only Soulseek client. It never listens for connections: peers that cannot reach it ask the server to
 * relay a "connect to me" request, and the client connects out with the pierce-firewall ticket. That works behind NAT,
 * which is the normal case on phones.
 */
class SoulseekClient(
    private val username: String,
    private val password: String,
    private val serverHost: String = "server.slsknet.org",
    private val serverPort: Int = 2416,
) : Closeable {
    // Every open peer connection blocks a thread on reads, and a popular search opens dozens; a growable pool of our
    // own keeps them from starving Dispatchers.IO. Idle threads exit after a minute.
    private val executor =
        ThreadPoolExecutor(0, Int.MAX_VALUE, 60, TimeUnit.SECONDS, SynchronousQueue()) { task ->
            Thread(task, "soulseek-io").apply { isDaemon = true }
        }
    private val io = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + io)
    private var server: Connection? = null
    private val login = CompletableDeferred<String?>()
    private val peerAddresses = ConcurrentHashMap<String, CompletableDeferred<Pair<String, Int>>>()
    private val searches = ConcurrentHashMap<Long, ConcurrentLinkedQueue<SoulseekSearchReply>>()
    private val peers = ConcurrentHashMap<String, Connection>()
    private val pendingByFile = ConcurrentHashMap<Pair<String, String>, PendingDownload>()
    private val pendingByTicket = ConcurrentHashMap<Long, PendingDownload>()

    private class Connection(
        val socket: Socket,
        val peer: String?,
    ) : Closeable {
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        private val output: OutputStream = BufferedOutputStream(socket.getOutputStream())

        fun send(bytes: ByteArray) = synchronized(this) { SoulseekCodec.write(output, bytes) }

        override fun close() {
            runCatching { socket.close() }
        }
    }

    private class PendingDownload(
        val username: String,
        val filename: String,
    ) {
        val transferRequest = CompletableDeferred<Long>()
        val fileConnection = CompletableDeferred<Connection>()
        val failure = CompletableDeferred<String>()
    }

    /** Connects and signs in. Throws [SoulseekLoginException] for bad credentials and [IOException] for network errors. */
    suspend fun login() {
        val connection = withContext(io) { Connection(connect(serverHost, serverPort, CONNECT_TIMEOUT_MS), null) }
        server = connection
        scope.launch { readServer(connection) }
        val md5 = MessageDigest.getInstance("MD5").digest((username + password).toByteArray()).joinToString("") { "%02x".format(it) }
        connection.send(
            MessageWriter()
                .string(username)
                .string(password)
                .u32(CLIENT_VERSION)
                .string(md5)
                .u32(MINOR_VERSION)
                .frame(Codes.LOGIN),
        )
        val refusal = withTimeout(LOGIN_TIMEOUT_MS) { login.await() }
        if (refusal != null) throw SoulseekLoginException(refusal)
        // Download only: no shares, no distributed search network.
        connection.send(MessageWriter().u32(2).frame(Codes.SET_STATUS))
        connection.send(MessageWriter().u32(0).u32(0).frame(Codes.SHARED_FOLDERS_FILES))
        connection.send(MessageWriter().bool(false).frame(Codes.TOGGLE_PARENT_SEARCH))
        connection.send(MessageWriter().bool(false).frame(Codes.ACCEPT_CHILDREN))
        scope.launch {
            while (isActive) {
                delay(PING_INTERVAL_MS)
                runCatching { connection.send(MessageWriter().frame(Codes.PING)) }
            }
        }
    }

    /** Sends a search and collects the replies that arrive within [waitMs]. */
    suspend fun search(
        query: String,
        waitMs: Long = 10_000,
    ): List<SoulseekSearchReply> {
        val ticket = Random.nextLong(1, 0x7FFFFFFF)
        val replies = ConcurrentLinkedQueue<SoulseekSearchReply>()
        searches[ticket] = replies
        try {
            requireServer().send(MessageWriter().u32(ticket).string(query).frame(Codes.FILE_SEARCH))
            delay(waitMs)
        } finally {
            searches.remove(ticket)
        }
        return replies.toList()
    }

    /**
     * Downloads [filename] from [peer] into [directory] and returns the saved file. Gives a peer [startTimeoutMs] to
     * start sending and drops it when it stops sending for [stallTimeoutMs].
     */
    suspend fun download(
        peer: String,
        filename: String,
        directory: File,
        startTimeoutMs: Long = 60_000,
        stallTimeoutMs: Int = 60_000,
        onProgress: (received: Long, total: Long) -> Unit = { _, _ -> },
    ): File {
        val pending = PendingDownload(peer, filename)
        pendingByFile[peer to filename] = pending
        var ticket: Long? = null
        try {
            val size =
                withTimeout(startTimeoutMs) {
                    peerConnection(peer).send(MessageWriter().string(filename).frame(Codes.PEER_TRANSFER_QUEUE))
                    firstOf(pending.transferRequest, pending.failure)
                }
            ticket = pendingByTicket.entries.firstOrNull { it.value === pending }?.key
            val connection = withTimeout(startTimeoutMs) { firstOf(pending.fileConnection, pending.failure) }
            return withContext(io) { receiveFile(connection, size, directory, filename, stallTimeoutMs, onProgress) }
        } catch (_: TimeoutCancellationException) {
            throw SoulseekTransferException("$peer did not start sending")
        } finally {
            pendingByFile.remove(peer to filename)
            ticket?.let(pendingByTicket::remove)
        }
    }

    private suspend fun <T> firstOf(
        value: CompletableDeferred<T>,
        failure: CompletableDeferred<String>,
    ): T =
        select {
            value.onAwait { it }
            failure.onAwait { throw SoulseekTransferException(it) }
        }

    private fun receiveFile(
        connection: Connection,
        size: Long,
        directory: File,
        filename: String,
        stallTimeoutMs: Int,
        onProgress: (Long, Long) -> Unit,
    ): File =
        connection.use {
            // Offset 0: always start from the beginning.
            connection.send(MessageWriter().u64(0).bytes())
            connection.socket.soTimeout = stallTimeoutMs
            directory.mkdirs()
            val part = File(directory, SoulseekMatching.localFileName(filename) + ".part")
            var received = 0L
            try {
                part.outputStream().buffered().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (received < size) {
                        val read = connection.input.read(buffer, 0, minOf(buffer.size.toLong(), size - received).toInt())
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        received += read
                        onProgress(received, size)
                    }
                }
            } catch (_: SocketTimeoutException) {
                part.delete()
                throw SoulseekTransferException("${connection.peer} stopped sending")
            } catch (e: IOException) {
                part.delete()
                throw e
            }
            if (received < size) {
                part.delete()
                throw SoulseekTransferException("${connection.peer} closed the transfer early")
            }
            uniqueFile(directory, SoulseekMatching.localFileName(filename)).also { part.renameTo(it) }
        }

    private fun uniqueFile(
        directory: File,
        name: String,
    ): File {
        val base = name.substringBeforeLast('.')
        val extension = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        return generateSequence(1) { it + 1 }
            .map { if (it == 1) File(directory, name) else File(directory, "$base ($it)$extension") }
            .first { !it.exists() }
    }

    private suspend fun peerConnection(peer: String): Connection {
        peers[peer]?.takeIf { !it.socket.isClosed }?.let { return it }
        val address = CompletableDeferred<Pair<String, Int>>()
        peerAddresses[peer] = address
        requireServer().send(MessageWriter().string(peer).frame(Codes.GET_PEER_ADDRESS))
        val (ip, port) = withTimeout(CONNECT_TIMEOUT_MS.toLong()) { address.await() }
        if (port == 0 || ip == "0.0.0.0") throw SoulseekTransferException("$peer is offline")
        val connection =
            withContext(io) {
                try {
                    Connection(connect(ip, port, CONNECT_TIMEOUT_MS), peer)
                } catch (e: IOException) {
                    // shortcut: no listening socket, so a peer that is unreachable directly cannot be queued; add a listener for desktop.
                    throw SoulseekTransferException("$peer cannot be reached: ${e.message}")
                }
            }
        connection.send(
            MessageWriter()
                .string(username)
                .string("P")
                .u32(Random.nextLong(1, 0x7FFFFFFF))
                .frameInit(Codes.PEER_INIT),
        )
        peers[peer] = connection
        scope.launch { readPeer(connection) }
        return connection
    }

    private fun requireServer(): Connection = server ?: throw IllegalStateException("Call login() first")

    private fun readServer(connection: Connection) {
        try {
            while (true) {
                val reader = MessageReader(SoulseekCodec.readFrame(connection.input) ?: break)
                when (reader.u32().toInt()) {
                    Codes.LOGIN -> {
                        if (reader.bool()) login.complete(null) else login.complete(runCatching { reader.string() }.getOrDefault("UNKNOWN"))
                    }

                    Codes.GET_PEER_ADDRESS -> {
                        val user = reader.string()
                        val ip = reader.ip()
                        val port = reader.u32().toInt()
                        peerAddresses.remove(user)?.complete(ip to port)
                    }

                    Codes.CONNECT_TO_PEER -> {
                        val user = reader.string()
                        val type = reader.string()
                        val ip = reader.ip()
                        val port = reader.u32().toInt()
                        val ticket = reader.u32()
                        scope.launch { pierce(user, type, ip, port, ticket) }
                    }
                }
            }
        } catch (_: IOException) {
        } catch (_: SoulseekProtocolException) {
        } finally {
            login.complete("DISCONNECTED")
        }
    }

    /** Answers a relayed "connect to me" request: connect out and identify with the server's ticket. */
    private fun pierce(
        peer: String,
        type: String,
        ip: String,
        port: Int,
        ticket: Long,
    ) {
        if (type != "P" && type != "F") return
        val connection =
            try {
                Connection(connect(ip, port, CONNECT_TIMEOUT_MS), peer)
            } catch (_: IOException) {
                runCatching { server?.send(MessageWriter().u32(ticket).string(peer).frame(Codes.CANNOT_CONNECT)) }
                return
            }
        try {
            connection.send(MessageWriter().u32(ticket).frameInit(Codes.PIERCE_FIREWALL))
            if (type == "P") {
                // Keep it: queueing a download over a connection the peer opened needs no direct connection to the peer.
                peers[peer] = connection
                readPeer(connection)
            } else {
                acceptFileConnection(connection)
            }
        } catch (_: IOException) {
            connection.close()
        }
    }

    /** A file connection starts with the uploader sending the transfer ticket as four raw bytes. */
    private fun acceptFileConnection(connection: Connection) {
        connection.socket.soTimeout = TICKET_TIMEOUT_MS
        val ticketBytes = SoulseekCodec.readExactly(connection.input, 4)
        val pending = ticketBytes?.let { pendingByTicket[MessageReader(it).u32()] }
        if (pending == null || !pending.fileConnection.complete(connection)) connection.close()
    }

    private fun readPeer(connection: Connection) {
        val peer = connection.peer ?: return connection.close()
        // Close quiet connections so open sockets and reader threads stay bounded.
        connection.socket.soTimeout = PEER_IDLE_TIMEOUT_MS
        try {
            while (true) {
                val reader = MessageReader(SoulseekCodec.readFrame(connection.input) ?: break)
                when (reader.u32().toInt()) {
                    Codes.PEER_SEARCH_REPLY -> {
                        // A malformed reply from one peer only drops that reply.
                        runCatching { SoulseekCodec.parseSearchReply(reader.rest()) }
                            .getOrNull()
                            ?.let { searches[it.ticket]?.add(it.copy(username = peer)) }
                    }

                    Codes.PEER_TRANSFER_REQUEST -> {
                        val direction = reader.u32()
                        val ticket = reader.u32()
                        val filename = reader.string()
                        val size = if (direction == 1L && reader.remaining >= 8) reader.u64() else -1L
                        val pending = pendingByFile[peer to filename]?.takeIf { direction == 1L && size >= 0 }
                        if (pending != null) {
                            pendingByTicket[ticket] = pending
                            connection.send(MessageWriter().u32(ticket).bool(true).frame(Codes.PEER_TRANSFER_REPLY))
                            pending.transferRequest.complete(size)
                        } else {
                            connection.send(
                                MessageWriter()
                                    .u32(ticket)
                                    .bool(false)
                                    .string("Cancelled")
                                    .frame(Codes.PEER_TRANSFER_REPLY),
                            )
                        }
                    }

                    Codes.PEER_UPLOAD_FAILED -> {
                        pendingByFile[peer to reader.string()]?.failure?.complete("$peer could not send the file")
                    }

                    Codes.PEER_TRANSFER_QUEUE_FAILED -> {
                        val filename = reader.string()
                        val reason = runCatching { reader.string() }.getOrDefault("")
                        pendingByFile[peer to filename]?.failure?.complete("$peer refused the file: $reason")
                    }
                }
            }
        } catch (_: IOException) {
        } catch (_: SoulseekProtocolException) {
        } finally {
            connection.close()
            peers.remove(peer, connection)
        }
    }

    override fun close() {
        scope.cancel()
        server?.close()
        peers.values.forEach { it.close() }
        pendingByFile.values.forEach { it.failure.complete("Cancelled") }
        executor.shutdown()
    }

    private fun connect(
        host: String,
        port: Int,
        timeoutMs: Int,
    ): Socket = Socket().apply { connect(InetSocketAddress(host, port), timeoutMs) }

    private companion object {
        const val CLIENT_VERSION = 160
        const val MINOR_VERSION = 1
        const val CONNECT_TIMEOUT_MS = 10_000
        const val LOGIN_TIMEOUT_MS = 20_000L
        const val TICKET_TIMEOUT_MS = 10_000
        const val PING_INTERVAL_MS = 5 * 60_000L
        const val PEER_IDLE_TIMEOUT_MS = 3 * 60_000
    }
}

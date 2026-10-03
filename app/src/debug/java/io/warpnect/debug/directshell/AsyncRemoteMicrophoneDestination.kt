package io.warpnect.debug.directshell

import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Policy/control operations must never stall the protected audio ingress thread. */
internal class AsyncRemoteMicrophoneDestination(
    acquire: () -> Pair<RemoteMicrophonePcmDestination, AutoCloseable>?,
    private val control: ExecutorService = Executors.newSingleThreadExecutor { work ->
        Thread(work, "DirectShellRemoteMicControl")
    },
) : RemoteMicrophonePcmDestination, AutoCloseable {
    private val lock = Any()
    private var closed = false
    private var startRequested = false
    private var active = false
    private var source: RemoteMicrophonePcmDestination? = null
    private var lease: AutoCloseable? = null

    init {
        control.execute {
            val acquired = runCatching(acquire).getOrNull()
            val retain = synchronized(lock) {
                if (closed) {
                    false
                } else {
                    source = acquired?.first
                    lease = acquired?.second
                    true
                }
            }
            if (!retain) {
                runCatching { acquired?.second?.close() }
            } else {
                activateIfRequested()
            }
        }
    }

    override fun start(): Boolean = synchronized(lock) {
        if (closed) return false
        if (!startRequested) {
            startRequested = true
            control.execute(::activateIfRequested)
        }
        true
    }

    private fun activateIfRequested() {
        val target = synchronized(lock) {
            if (closed || active || !startRequested) null else source
        } ?: return
        val started = runCatching { target.start() }.getOrDefault(false)
        synchronized(lock) { if (!closed && source === target) active = started }
    }

    override fun submit(buffer: ByteBuffer, offset: Int, frames: Int) = synchronized(lock) {
        if (!closed && active) source?.submit(buffer, offset, frames)
        Unit
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            active = false
            source = null
            control.execute {
                val owned = synchronized(lock) { lease.also { lease = null } }
                runCatching { owned?.close() }
            }
            control.shutdown()
        }
    }
}

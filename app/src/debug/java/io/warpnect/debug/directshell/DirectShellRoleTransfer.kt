package io.warpnect.debug.directshell

import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Unregistered role boundaries. No renderer, injector, provider discovery or descriptor broker. */
internal object DirectShellRoleCommands {
    enum class Input(val wire: Int) {
        CAPABILITIES(67),
        PREPARE(68),
        START(69),
        KEY(70),
        TOUCH(71),
        POINTER(72),
        JOYSTICK(73),
        RESET(74),
        STOP(75),
        SNAPSHOT(76),
    }

    enum class Pcm(val wire: Int) {
        BIND(40),
        PREPARE(42),
        START(43),
        STOP(44),
        CLOSE(46),
    }

    enum class Video(val wire: Int) {
        QUALIFY(80),
        START(81),
        UPDATE(82),
        STOP(83),
    }

    fun input(wire: Int) = Input.entries.singleOrNull { it.wire == wire }

    fun pcm(wire: Int) = Pcm.entries.singleOrNull { it.wire == wire }

    fun video(wire: Int) = Video.entries.singleOrNull { it.wire == wire }

    /** Framing only; the typed legacy payload codec and authenticated caller still own dispatch. */
    fun inputEnvelope(wire: Int, payload: ByteArray, generation: Long): Input? {
        val command = input(wire) ?: return null
        if (generation <= 0 || payload.size !in 16..4096) return null
        val header = ByteBuffer.wrap(payload)
        return command.takeIf { header.int == 0x574E4953 && header.int == 1 && header.long == generation }
    }
}

/** Generation-bound descriptor/Surface transfer contract, not a physical kernel release witness. */
internal class DirectShellRoleTransfer(
    private val owner: Owner,
    private val generation: Long,
    private val resources: Resources,
    secret: ByteArray,
) {
    class Owner
    class Resource
    class Grant internal constructor()

    sealed interface Layout {
        data class Pcm(val bytes: Int, val slots: Int, val payloadBytes: Int) : Layout {
            init {
                require(bytes > 0 && slots > 0 && payloadBytes > 0)
                require(slots.toLong() * payloadBytes <= bytes)
            }
        }

        data class Video(val width: Int, val height: Int) : Layout {
            init {
                require(width > 0 && height > 0)
            }
        }
    }

    sealed interface Resources {
        val layout: Layout
        val handles: Set<Resource>

        class Pcm(
            override val layout: Layout.Pcm,
            val backing: Resource,
            val notifyWrite: Resource,
            val ackRead: Resource,
        ) : Resources {
            override val handles = setOf(backing, notifyWrite, ackRead).also { require(it.size == 3) }
        }

        class Video(override val layout: Layout.Video, val surface: Resource) : Resources {
            override val handles = setOf(surface)
        }
    }

    enum class Phase { OFFERED, RESERVED, HANDED_OFF, RELEASE_PENDING, RELEASED }

    init {
        require(generation > 0 && secret.size == 32)
    }

    private var expectedProof: ByteArray? = proof(resources.layout, generation, secret)
    private val ownedHandles = resources.handles.toSet()
    private var grant: Grant? = null
    private var phase = Phase.OFFERED

    @Synchronized
    fun reserve(measuredUid: Int, caller: Owner, generation: Long, layout: Layout, proof: ByteArray): Grant? {
        if (phase != Phase.OFFERED || measuredUid != 2000 || caller !== owner ||
            this.generation != generation || resources.layout != layout || proof.size != 32 ||
            expectedProof?.let { MessageDigest.isEqual(it, proof) } != true
        ) {
            return null
        }
        wipeProof()
        phase = Phase.RESERVED
        return Grant().also { grant = it }
    }

    @Synchronized
    fun handoff(claim: Grant): Boolean {
        if (claim !== grant || phase != Phase.RESERVED) return false
        phase = Phase.HANDED_OFF
        return true
    }

    @Synchronized
    fun beginRelease(caller: Owner): Boolean {
        if (caller !== owner || phase == Phase.RELEASED || phase == Phase.RELEASE_PENDING) return false
        wipeProof()
        phase = Phase.RELEASE_PENDING
        return true
    }

    /** Missing, foreign or extra evidence keeps the entire transfer release-pending. */
    @Synchronized
    fun observeRelease(caller: Owner, generation: Long, closed: Set<Resource>, workerExited: Boolean): Boolean {
        if (caller !== owner || this.generation != generation || phase != Phase.RELEASE_PENDING ||
            !workerExited || ownedHandles != closed
        ) {
            return false
        }
        grant = null
        phase = Phase.RELEASED
        return true
    }

    @Synchronized
    fun phase() = phase

    /** This adapter only attaches to the exact ledger and never to an equivalent replacement. */
    fun owns(caller: Owner, ledger: Resources) = caller === owner && ledger === resources

    @Synchronized
    fun isReleasePending(caller: Owner, generation: Long) =
        caller === owner && this.generation == generation && phase == Phase.RELEASE_PENDING

    private fun wipeProof() {
        expectedProof?.fill(0)
        expectedProof = null
    }

    companion object {
        /** Fixed, role-separated proof bytes compatible with their existing typed Debug protocols. */
        fun proof(layout: Layout, generation: Long, secret: ByteArray): ByteArray {
            require(generation > 0 && secret.size == 32)
            val payload = when (layout) {
                is Layout.Pcm -> ByteBuffer.allocate(40).put("WNPR_OPEN_V1".encodeToByteArray())
                    .putInt(0x574E5052).putInt(1).putLong(generation)
                    .putInt(layout.bytes).putInt(layout.slots).putInt(layout.payloadBytes).array()
                is Layout.Video -> ByteBuffer.allocate(36).put("WNVS_OPEN_V1".encodeToByteArray())
                    .putInt(0x574E5653).putInt(1).putLong(generation)
                    .putInt(layout.width).putInt(layout.height).array()
            }
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret, "HmacSHA256"))
            return mac.doFinal(payload)
        }
    }
}

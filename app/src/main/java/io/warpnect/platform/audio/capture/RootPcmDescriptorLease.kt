package io.warpnect.platform.audio.capture

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** A finite Root PCM descriptor set, not an FD registry. */
internal class RootPcmDescriptorLease(
    private val request: RootAudioRequest,
    private val pid: Int,
    key: ByteArray,
    private val expires: Long,
) : AutoCloseable {
    private val secret = key.clone().also { require(it.size == 32) }
    private var issued = false
    private var closed = false

    @Synchronized
    fun accept(uid: Int, callerPid: Int, provider: String?, bytes: ByteArray, proof: ByteArray?, now: Long): Boolean =
        !closed && !issued && now < expires && uid == 0 && callerPid == pid && provider == "DIRECT_ROOT" &&
            runCatching { RootAudioRequestCodec.decode(bytes) == request }.getOrDefault(false) &&
            proof?.let { MessageDigest.isEqual(it, proof(secret, request)) } == true

    @Synchronized
    fun issued() {
        check(!closed && !issued)
        issued = true
    }

    @Synchronized
    override fun close() {
        closed = true
        secret.fill(0)
    }

    companion object {
        const val SLOT_COUNT = 8
        const val SLOT_BYTES = 960
        const val REGION_BYTES = 8256

        fun proof(key: ByteArray, request: RootAudioRequest): ByteArray {
            require(key.size == 32)
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            mac.update("DIRECT_ROOT_PCM_V1".toByteArray(Charsets.US_ASCII))
            return mac.doFinal(RootAudioRequestCodec.encode(request))
        }
    }
}

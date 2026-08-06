package io.warpnect

internal object NativeBridge {
    init {
        System.loadLibrary("scl_core")
    }

    @JvmStatic
    private external fun nativeProtocolName(): String

    @JvmStatic
    private external fun nativeProtocolVersion(): Int

    @JvmStatic
    private external fun nativeProtocolAbiVersion(): Int

    fun sclInfo(): NativeSclInfo = NativeSclInfo(
        protocolName = nativeProtocolName(),
        protocolVersion = nativeProtocolVersion(),
        nativeBridgeAbiVersion = nativeProtocolAbiVersion(),
    )
}

internal data class NativeSclInfo(
    val protocolName: String,
    val protocolVersion: Int,
    val nativeBridgeAbiVersion: Int,
)

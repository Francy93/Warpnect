package io.warpnect.input.session

import io.warpnect.input.capture.InputCaptureConfig
import io.warpnect.input.capture.InputCaptureError
import io.warpnect.input.capture.InputCaptureSnapshot
import io.warpnect.input.injection.InputInjectionConfig
import io.warpnect.input.injection.InputInjectionError
import io.warpnect.input.injection.InputInjectionSnapshot
import io.warpnect.input.mapping.ViewportInputMappingConfig
import io.warpnect.input.mapping.ViewportInputMappingSnapshot
import io.warpnect.input.transport.InputReceiverConfig
import io.warpnect.input.transport.InputReceiverError
import io.warpnect.input.transport.InputReceiverSnapshot
import io.warpnect.input.transport.InputTransportConfig
import io.warpnect.input.transport.InputTransportError
import io.warpnect.input.transport.InputTransportSnapshot
import io.warpnect.platform.input.mapping.AndroidTargetInputMappingSnapshot

enum class ReverseInputSessionState {
    Stopped,
    Starting,
    Running,
    Stopping,
    Error,
    Closed,
}

enum class ReverseInputSessionError {
    None,
    InvalidConfiguration,
    TransportPrepareFailed,
    TransportStartFailed,
    MapperPrepareFailed,
    CapturePrepareFailed,
    CaptureStartFailed,
    ResetSendFailed,
    InjectionPrepareFailed,
    InjectionStartFailed,
    ReceiverPrepareFailed,
    ReceiverStartFailed,
    ReceiverFailure,
    BridgeDecodeFailed,
    MappingFailure,
    InjectionFailure,
    ResetInjectionFailure,
    Closed,
}

data class ReverseInputSenderSessionConfig(
    val captureConfig: InputCaptureConfig,
    val viewportMappingConfig: ViewportInputMappingConfig = ViewportInputMappingConfig(),
    val transportConfig: InputTransportConfig,
) {
    fun isValid(): Boolean = captureConfig.validate() == InputCaptureError.None &&
        viewportMappingConfig.isValid() &&
        transportConfig.validate() == InputTransportError.None &&
        transportConfig.localPort != 0
}

data class ReverseInputReceiverSessionConfig(
    val receiverConfig: InputReceiverConfig,
    val injectionConfig: InputInjectionConfig,
    val receiverWaitTimeoutUs: Long = DEFAULT_RECEIVER_WAIT_TIMEOUT_US,
) {
    fun isValid(): Boolean = receiverConfig.validate() == InputReceiverError.None &&
        injectionConfig.validate() == InputInjectionError.None &&
        receiverWaitTimeoutUs in 1L..MAX_RECEIVER_WAIT_TIMEOUT_US

    companion object {
        const val DEFAULT_RECEIVER_WAIT_TIMEOUT_US: Long = 1_000_000L
        const val MAX_RECEIVER_WAIT_TIMEOUT_US: Long = 5_000_000L
    }
}

data class ReverseInputSenderSessionSnapshot(
    val state: ReverseInputSessionState = ReverseInputSessionState.Stopped,
    val startAttempts: Long = 0L,
    val stopAttempts: Long = 0L,
    val stopResetAttempted: Boolean = false,
    val stopResetSent: Boolean = false,
    val stopResetFailed: Boolean = false,
    val lastError: ReverseInputSessionError = ReverseInputSessionError.None,
    val capture: InputCaptureSnapshot = InputCaptureSnapshot(),
    val mapper: ViewportInputMappingSnapshot? = null,
    val transport: InputTransportSnapshot = InputTransportSnapshot(),
)

data class ReverseInputReceiverSessionSnapshot(
    val state: ReverseInputSessionState = ReverseInputSessionState.Stopped,
    val receivedEvents: Long = 0L,
    val droppedDatagrams: Long = 0L,
    val mappingFailures: Long = 0L,
    val injectionFailures: Long = 0L,
    val emergencyResetRequests: Long = 0L,
    val emergencyResetsCompleted: Long = 0L,
    val finalResetAttempted: Boolean = false,
    val finalResetSucceeded: Boolean = false,
    val lastError: ReverseInputSessionError = ReverseInputSessionError.None,
    val receiver: InputReceiverSnapshot = InputReceiverSnapshot(),
    val mapper: AndroidTargetInputMappingSnapshot = AndroidTargetInputMappingSnapshot(),
    val injection: InputInjectionSnapshot = InputInjectionSnapshot(),
)

data class ReverseInputSenderSessionResult(
    val error: ReverseInputSessionError,
    val snapshot: ReverseInputSenderSessionSnapshot,
) {
    val isSuccess: Boolean
        get() = error == ReverseInputSessionError.None
}

data class ReverseInputReceiverSessionResult(
    val error: ReverseInputSessionError,
    val snapshot: ReverseInputReceiverSessionSnapshot,
) {
    val isSuccess: Boolean
        get() = error == ReverseInputSessionError.None
}

package io.warpnect.platform.privileged.directshell.pairing

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Enrollment deadlines use suspend-inclusive elapsed time, never media/Session clocks. */
internal fun interface DirectShellPairingClock {
    fun elapsedRealtimeMs(): Long
}

internal fun interface DirectShellPairingTimer {
    fun cancel()
}

/** Nonthrowing enqueue, never inline. Nonthrowing cancellation releases the scheduled closure. */
internal fun interface DirectShellPairingScheduler {
    fun at(deadlineMs: Long, action: () -> Unit): DirectShellPairingTimer
}

internal data class DirectShellPairingEnvironment(
    val backendAvailable: Boolean,
    val permissionsGranted: Boolean,
    val networkEpoch: Long,
    val credentialRevision: Long,
    val runtimeInUse: Boolean = false,
    val reconciliationRequired: Boolean = false,
) {
    init {
        require(networkEpoch >= 0 && credentialRevision >= 0) { "pairing_environment_revision_invalid" }
    }
}

/** Object identity is authoritative: no restoration/deserialization or Session-token conversion. */
internal class DirectShellPairingTicket(
    internal val ownerEpoch: String,
    internal val nonce: String,
    val originReadinessGeneration: Long,
    internal val networkEpoch: Long,
    internal val credentialRevision: Long,
) {
    override fun toString(): String = "DirectShellPairingTicket(redacted)"
}

internal enum class DirectShellPairingPhase {
    IDLE,
    UNAVAILABLE,
    AWAITING_CONSENT_AND_CODE,
    DISCOVERING,
    PAIRING,
    COMMITTING,
    COMMIT_RESERVED,
    COMMIT_OUTCOME_UNKNOWN,
    RECONCILIATION_REQUIRED,
    CLOSING,
    CANCELLING,
    PAIRED_NEEDS_EXPLICIT_HOST_RETRY,
    CANCELLED,
    EXPIRED,
    FAILED,
    CLEANUP_INCOMPLETE,
    CLOSED,
}

internal enum class DirectShellPairingReason {
    READINESS_STALE,
    BACKEND_UNAVAILABLE,
    PERMISSION_DENIED,
    RUNTIME_BUSY,
    INVALID_CODE,
    CONSENT_EXPIRED,
    OPERATION_EXPIRED,
    USER_CANCELLED,
    OWNER_CLOSED,
    NETWORK_CHANGED,
    CREDENTIALS_CHANGED,
    PAIRING_REJECTED,
    BACKEND_FAILURE,
    COMMIT_FAILED,
    RECOVERY_UNCONFIRMED,
    RECEIPT_INVALID,
    RECONCILIATION_REQUIRED,
}

internal enum class DirectShellPairingPublication { NOT_PUBLISHED, PUBLISHED, UNKNOWN }
internal enum class DirectShellPairingDurability { NOT_APPLICABLE, SYNC_CONFIRMED, UNCONFIRMED }
internal enum class DirectShellPairingExternalEffect { NONE_POSSIBLE, POSSIBLE, AUTHENTICATED }
internal enum class DirectShellPairingProtocolVerification { UNCONFIRMED, PEER_INFO_VERIFIED }

/** Trusted-port observation only, never consent/publication or confirmed Android trust. */
internal data class DirectShellPairingProtocolObservation(
    internal val ticket: DirectShellPairingTicket,
    val verification: DirectShellPairingProtocolVerification,
    val externalEffect: DirectShellPairingExternalEffect,
) {
    override fun toString(): String = "PairingProtocolObservation(redacted)"
}

internal enum class DirectShellPairingRecovery { NONE, RETAINED_UNCONFIRMED, COMMITTED_RECORD_PRESENT, UNKNOWN }
internal enum class DirectShellPairingResolution { RETAIN_UNCONFIRMED, RECORD_PUBLISHED }
internal enum class DirectShellPairingStop {
    NO_LOCAL_PUBLICATION_WILL_START,
    COMMIT_PENDING_NO_ROLLBACK_GUARANTEE,
    ALREADY_STORED_NO_ROLLBACK,
    RECONCILIATION_REQUIRED,
    STALE_OR_FINISHED,
}

internal data class DirectShellPairingState(
    val phase: DirectShellPairingPhase = DirectShellPairingPhase.IDLE,
    val originReadinessGeneration: Long? = null,
    val reason: DirectShellPairingReason? = null,
    val cleanupFailed: Boolean = false,
    val publication: DirectShellPairingPublication = DirectShellPairingPublication.NOT_PUBLISHED,
    val durability: DirectShellPairingDurability = DirectShellPairingDurability.NOT_APPLICABLE,
    val externalEffect: DirectShellPairingExternalEffect = DirectShellPairingExternalEffect.NONE_POSSIBLE,
    val recovery: DirectShellPairingRecovery = DirectShellPairingRecovery.NONE,
    val drained: Boolean = false,
    val protocolVerification: DirectShellPairingProtocolVerification =
        DirectShellPairingProtocolVerification.UNCONFIRMED,
)

/** Borrowed only during start(); any backend copy must be owned until drain and then wiped. */
internal class DirectShellPairingCode private constructor(private val characters: CharArray) : AutoCloseable {
    private var closed = false

    @Synchronized
    fun <T> useCharacters(action: (CharArray) -> T): T {
        check(!closed) { "pairing_code_closed" }
        val copy = characters.copyOf()
        return try {
            action(copy)
        } finally {
            copy.fill('\u0000')
        }
    }

    @Synchronized
    override fun close() {
        closed = true
        characters.fill('\u0000')
    }

    override fun toString(): String = "DirectShellPairingCode(redacted)"

    companion object {
        /** Consumes/wipes caller input even when invalid. Retained allocation is at most six chars. */
        fun take(input: CharArray): DirectShellPairingCode? = try {
            if (input.size == 6 && input.all { it in '0'..'9' }) DirectShellPairingCode(input.copyOf()) else null
        } finally {
            input.fill('\u0000')
        }
    }
}

/** Opaque resource, not credentials. Nonblocking resolution runs once, including duplicate callbacks. */
internal class DirectShellStagedAuthorization(private val onResolved: (DirectShellPairingResolution) -> Unit) {
    private val resolved = AtomicBoolean(false)
    internal val isResolved: Boolean get() = resolved.get()

    internal fun resolve(resolution: DirectShellPairingResolution): Boolean {
        if (!resolved.compareAndSet(false, true)) return true
        return try {
            onResolved(resolution)
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun toString(): String = "DirectShellStagedAuthorization(redacted)"
}

internal interface DirectShellPairingObserver {
    /** Backend attests recovery material before relevant I/O; false forbids starting it. */
    fun pairingStarted(recovery: DirectShellPairingRecovery): Boolean
    fun prepared(authorization: DirectShellStagedAuthorization)
    fun failed(reason: DirectShellPairingReason)
}

/** Optional explicit path; a caller requiring it must not fall back to legacy prepared(). */
internal interface ObservedDirectShellPairingObserver : DirectShellPairingObserver {
    fun prepared(authorization: DirectShellStagedAuthorization, observation: DirectShellPairingProtocolObservation)
}

internal fun interface DirectShellPairingBackend {
    /** Inert and non-reentrant factory: no work/callbacks until start(); a throw owns no resources. */
    fun create(ticket: DirectShellPairingTicket, observer: DirectShellPairingObserver): DirectShellPairingOperation
}

/** Methods dispatch bounded work without blocking. Callbacks may run synchronously/reentrantly. */
internal interface DirectShellPairingOperation {
    fun start(code: DirectShellPairingCode)
    fun cancel()

    /** Called once. Drained means all operation-owned work/resources/copies are actually released. */
    fun close(drained: (DirectShellPairingDrainReceipt) -> Unit)
}

/** Immutable owner metadata, not consent/publication authority. Never renew the absolute cutoff. */
internal class DirectShellPairingWorkContext(
    val ticket: DirectShellPairingTicket,
    val deadlineElapsedRealtimeMs: Long,
    val clock: DirectShellPairingClock,
    val scheduler: DirectShellPairingScheduler,
    private val firstEnrollmentAdmission: () -> Boolean = { false },
) {
    fun mayProvision(ticket: DirectShellPairingTicket): Boolean =
        this.ticket === ticket && clock.elapsedRealtimeMs() < deadlineElapsedRealtimeMs && firstEnrollmentAdmission()

    override fun toString(): String = "DirectShellPairingWorkContext(redacted)"
}

/** Optional additive dispatch; legacy operations keep their original start contract. */
internal interface DeadlineAwarePairingOperation : DirectShellPairingOperation {
    fun start(code: DirectShellPairingCode, work: DirectShellPairingWorkContext)

    override fun start(code: DirectShellPairingCode) {
        error("pairing_owner_work_context_required")
    }
}

/** An observation is not physical drain unless all three resource domains are released. */
internal data class DirectShellPairingDrainReceipt(
    val externalEffect: DirectShellPairingExternalEffect,
    val recovery: DirectShellPairingRecovery,
    val transportDrained: Boolean = true,
    val storeDrained: Boolean = true,
    val codeCopiesReleased: Boolean = true,
    val protocolObservation: DirectShellPairingProtocolObservation? = null,
)

internal data class DirectShellPairingStoreReceipt(
    val publication: DirectShellPairingPublication,
    val durability: DirectShellPairingDurability,
    val cleanupComplete: Boolean = true,
    val publishedCredentialRevision: Long? = null,
) {
    internal fun isConsistent(): Boolean = when (publication) {
        DirectShellPairingPublication.NOT_PUBLISHED -> {
            durability == DirectShellPairingDurability.NOT_APPLICABLE && publishedCredentialRevision == null
        }
        DirectShellPairingPublication.UNKNOWN -> {
            durability == DirectShellPairingDurability.UNCONFIRMED && publishedCredentialRevision == null
        }
        DirectShellPairingPublication.PUBLISHED -> {
            durability != DirectShellPairingDurability.NOT_APPLICABLE &&
                publishedCredentialRevision != null && publishedCredentialRevision >= 0
        }
    }
}

/** Prepared metadata, not authority, a path or credential bytes. */
internal class DirectShellPairingPreparedCommit(
    internal val ticket: DirectShellPairingTicket,
    internal val authorization: DirectShellStagedAuthorization,
    internal val expectedCredentialRevision: Long = ticket.credentialRevision,
) {
    override fun toString(): String = "DirectShellPairingPreparedCommit(redacted)"
}

/** Issued only at the owner's reservation boundary; no caller-supplied publication closure. */
internal class DirectShellPairingCommitReservation private constructor(
    private val prepared: DirectShellPairingPreparedCommit,
) {
    private val execution = AtomicInteger(AVAILABLE)
    internal val wasClaimed: Boolean get() = execution.get() == CLAIMED
    internal val wasAbstained: Boolean get() = execution.get() == ABSTAINED

    fun claim(candidate: DirectShellPairingPreparedCommit): Boolean =
        candidate === prepared && execution.compareAndSet(AVAILABLE, CLAIMED)

    /** Stops provably unstarted work, never an already-claimed filesystem operation. */
    internal fun abstainUnclaimed() {
        execution.compareAndSet(AVAILABLE, ABSTAINED)
    }

    override fun toString(): String = "DirectShellPairingCommitReservation(redacted)"

    companion object {
        private const val AVAILABLE = 0
        private const val CLAIMED = 1
        private const val ABSTAINED = 2

        internal fun issue(prepared: DirectShellPairingPreparedCommit): DirectShellPairingCommitReservation =
            DirectShellPairingCommitReservation(prepared)
    }
}

internal interface DirectShellPairingCommitControl {
    /** CPU-only check/grant. All preparation is already complete on the operation-owned worker. */
    fun reserve(prepared: DirectShellPairingPreparedCommit): DirectShellPairingCommitReservation?
    fun completed(reservation: DirectShellPairingCommitReservation, receipt: DirectShellPairingStoreReceipt)
}

internal fun interface DirectShellPairingCommitter {
    /**
     * Deferred preparation belongs to the operation's drain, never an independent worker.
     * Reservation is not publication. The writer must claim it once and atomically compare
     * the expected revision in its store. P1 supplies no store or network implementation.
     */
    fun commit(
        ticket: DirectShellPairingTicket,
        authorization: DirectShellStagedAuthorization,
        control: DirectShellPairingCommitControl,
    )
}

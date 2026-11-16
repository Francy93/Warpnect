package io.warpnect.platform.privileged.directshell.pairing

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Blocking operation-owned storage, never callable under the P1 monitor. Construction is inert.
 * Only an explicitly submitted operation may call begin(); this is not a pairing backend or UI adapter.
 * Default NIO directory sync is unavailable on Windows and unqualified on Android: fail closed.
 */
internal class DirectShellCredentialStorage(
    private val noBackupBase: Path,
    private val wrappingKey: DirectShellPairingWrappingKey,
    private val io: DirectShellCredentialFileSystem = DirectShellCredentialFileSystem(),
) {
    enum class Status {
        EMPTY,
        RECOVERY_READY,
        PREPARED,
        RECOVERY_RECONCILIATION,
        PREPARED_RECONCILIATION,
        COMMITTED_RECONCILIATION,
        COMMITTED_CLEANUP_PENDING,
        KEY_UNAVAILABLE,
        CORRUPT,
        INVALID_MATERIAL,
        BUSY,
        STALE,
        REVISION_CONFLICT,
        UNSUPPORTED,
        IO_UNCERTAIN,
        OWNERSHIP_UNRESOLVED,
        FOREIGN_RECORD,
    }

    data class Inspection(val status: Status) {
        val reconciliationRequired: Boolean get() = status != Status.EMPTY
    }

    class Candidate private constructor(
        internal val owner: DirectShellCredentialStorage,
        internal val prepared: DirectShellPairingPreparedCommit,
        private val digest: ByteArray,
    ) {
        internal fun matches(bytes: ByteArray): Boolean =
            MessageDigest.isEqual(digest, DirectShellCredentialCodec.digest(bytes))

        override fun toString(): String = "DirectShellCredentialCandidate(redacted)"

        companion object {
            internal fun create(
                owner: DirectShellCredentialStorage,
                prepared: DirectShellPairingPreparedCommit,
                digest: ByteArray,
            ): Candidate = Candidate(owner, prepared, digest.copyOf())
        }
    }

    data class Preparation(val status: Status, val candidate: Candidate? = null)

    private val executing = AtomicBoolean(false)
    private var directory: Path? = null
    private var lease: DirectShellCredentialLease? = null
    private var ticket: DirectShellPairingTicket? = null
    private var anchorDigest: ByteArray? = null
    private var candidate: Candidate? = null
    private var begun = false
    private var closed = false
    private var publicationAttempted = false
    private var executionConsumed = false
    private var settledReservation: DirectShellPairingCommitReservation? = null
    private var settledReceipt: DirectShellPairingStoreReceipt? = null
    private var ownershipUnknown = false

    /** One-shot, explicit worker boundary. All failures require close(); surviving artifacts are retained. */
    fun begin(ticket: DirectShellPairingTicket, identity: DirectShellCredentialIdentity): Status =
        exclusive(Status.BUSY) {
            if (closed || begun) return@exclusive Status.STALE
            begun = true
            if (ticket.credentialRevision != 0L) return@exclusive Status.REVISION_CONFLICT
            val key = existingKey() ?: return@exclusive Status.KEY_UNAVAILABLE
            val encrypted = try {
                DirectShellCredentialCodec.encode(
                    DirectShellCredentialCodec.Record(
                        DirectShellCredentialCodec.Kind.RECOVERY,
                        DirectShellCredentialCodec.binding(ticket),
                        identity,
                        null,
                    ),
                    key,
                )
            } catch (_: Exception) {
                return@exclusive Status.INVALID_MATERIAL
            }
            try {
                val base = io.canonicalBase(noBackupBase)
                val dir = io.prepareNamespace(base)
                directory = dir
                lease = io.acquireLease(dir, true) ?: return@exclusive Status.BUSY
                val initial = inspectOwned(key).status
                if (initial != Status.EMPTY) return@exclusive initial
                try {
                    io.syncDirectory(base)
                    io.syncDirectory(dir)
                } catch (failure: Exception) {
                    return@exclusive platformStatus(failure, Status.UNSUPPORTED)
                }
                io.writeNew(dir.resolve(ANCHOR), encrypted)
                try {
                    io.syncDirectory(dir)
                } catch (failure: Exception) {
                    return@exclusive platformStatus(failure, Status.IO_UNCERTAIN)
                }
                // Re-read the exact authenticated anchor before declaring the pre-PAKE gate open.
                require(MessageDigest.isEqual(encrypted, io.read(dir.resolve(ANCHOR)))) { "credential_anchor_changed" }
                DirectShellCredentialCodec.decode(encrypted, key)
                this.ticket = ticket
                anchorDigest = DirectShellCredentialCodec.digest(encrypted)
                Status.RECOVERY_READY
            } catch (_: UnsupportedOperationException) {
                Status.UNSUPPORTED
            } catch (_: DirectShellCredentialOwnershipUnresolved) {
                ownershipUnknown = true
                Status.OWNERSHIP_UNRESOLVED
            } catch (failure: Exception) {
                platformStatus(failure, Status.IO_UNCERTAIN)
            }
        }

    /** Read-only worker checkpoint before protocol entry; never creates, repairs or adopts a record. */
    fun recoveryAdmission(owner: DirectShellPairingTicket): Status = exclusive(Status.BUSY) {
        if (closed || ticket !== owner || candidate != null) return@exclusive Status.STALE
        val key = existingKey() ?: return@exclusive Status.KEY_UNAVAILABLE
        try {
            if (ownershipUnknown || lease?.valid() != true) return@exclusive Status.OWNERSHIP_UNRESOLVED
            readAnchor(key)
            Status.RECOVERY_READY
        } catch (failure: Exception) {
            platformStatus(failure, Status.IO_UNCERTAIN)
        }
    }

    /** Evidence is a trusted-adapter input, not proof manufactured by file presence or an mDNS claim. */
    fun prepare(
        evidence: DirectShellAuthenticatedPairingEvidence,
        authorization: DirectShellStagedAuthorization,
    ): Preparation = exclusive(Preparation(Status.BUSY)) {
        val current = ticket
        if (closed || current == null || candidate != null || evidence.ticket !== current || authorization.isResolved) {
            return@exclusive Preparation(Status.STALE)
        }
        val key = existingKey() ?: return@exclusive Preparation(Status.KEY_UNAVAILABLE)
        try {
            require(lease?.valid() == true) { "credential_ownership_lost" }
            val anchor = readAnchor(key)
            if (!evidence.matches(anchor.identity.certificate)) {
                return@exclusive Preparation(Status.INVALID_MATERIAL)
            }
            val bytes = DirectShellCredentialCodec.encode(
                DirectShellCredentialCodec.Record(
                    DirectShellCredentialCodec.Kind.AUTHENTICATED,
                    anchor.binding,
                    anchor.identity,
                    evidence.peerDigestCopy(),
                ),
                key,
            )
            io.writeNew(path(PENDING), bytes)
            io.syncDirectory(directory!!)
            require(MessageDigest.isEqual(bytes, io.read(path(PENDING)))) { "credential_candidate_changed" }
            val prepared = DirectShellPairingPreparedCommit(current, authorization, 0)
            val value = Candidate.create(this, prepared, DirectShellCredentialCodec.digest(bytes))
            candidate = value
            Preparation(Status.PREPARED, value)
        } catch (failure: Exception) {
            Preparation(platformStatus(failure, Status.IO_UNCERTAIN))
        }
    }

    /** P is outside R. No fallback copy/replace and no inference of nonpublication from an unknown exception. */
    fun publish(value: Candidate, reservation: DirectShellPairingCommitReservation): DirectShellPairingStoreReceipt =
        exclusive(unknown()) {
            if (value === candidate && settledReservation === reservation) {
                settledReceipt?.let { return@exclusive it }
                if (executionConsumed) return@exclusive unknown()
            }
            if (closed || value !== candidate || value.owner !== this || executionConsumed ||
                value.prepared.ticket !== ticket || value.prepared.expectedCredentialRevision != 0L
            ) {
                return@exclusive notPublished()
            }
            // Claim one validated in-memory operation, not P. Known failures before publication entry
            // can now truthfully return NotPublished to P1 without an unproven execution receipt.
            if (!reservation.claim(value.prepared)) return@exclusive notPublished()
            executionConsumed = true
            settledReservation = reservation
            publishClaimed(value).also { settledReceipt = it }
        }

    private fun publishClaimed(value: Candidate): DirectShellPairingStoreReceipt {
        val key = existingKey() ?: return notPublished()
        return try {
            require(lease?.valid() == true) { "credential_ownership_lost" }
            readAnchor(key)
            val bytes = io.read(path(PENDING))
            require(value.matches(bytes)) { "credential_candidate_changed" }
            val decoded = DirectShellCredentialCodec.decode(bytes, key)
            require(
                decoded.kind == DirectShellCredentialCodec.Kind.AUTHENTICATED &&
                    MessageDigest.isEqual(decoded.binding, DirectShellCredentialCodec.binding(ticket!!)),
            ) { "credential_candidate_binding" }
            publicationAttempted = true
            // The storage primitive decides no-replacement at P, not an exists() check at R.
            val outcome = io.publishNoReplace(path(RECORD), path(PENDING))
            publicationAttempted = outcome.attempted != false
            when (outcome.publication) {
                DirectShellPairingPublication.NOT_PUBLISHED -> return notPublished()
                DirectShellPairingPublication.UNKNOWN -> return unknown()
                DirectShellPairingPublication.PUBLISHED -> Unit
            }
            try {
                io.syncDirectory(directory!!)
            } catch (_: Exception) {
                return published(false, false)
            }
            try {
                // Only redundant copies of a known published/synchronized complete identity.
                if (outcome.source == DirectShellCredentialSource.RETAINED) io.removeRedundant(path(PENDING))
                io.removeRedundant(path(ANCHOR))
                io.syncDirectory(directory!!)
                published(true, true)
            } catch (_: Exception) {
                published(true, false)
            }
        } catch (_: Exception) {
            if (publicationAttempted) unknown() else notPublished()
        }
    }

    /** No deletion on stop/close/restart. False is not D; the operation must remain occupied. */
    fun close(): Boolean = exclusive(false) {
        if (closed) return@exclusive true
        if (ownershipUnknown || !io.resourcesDrained()) return@exclusive false
        val released = lease?.close() ?: true
        if (released) {
            closed = true
            lease = null
        }
        released
    }

    /** Bounded, read-only restart classification; never creates a directory, lock, key or consent. */
    fun inspect(): Inspection = exclusive(Inspection(Status.BUSY)) {
        if (lease != null || begun || closed) return@exclusive Inspection(Status.BUSY)
        try {
            val dir = io.canonicalBase(noBackupBase).resolve(DIRECTORY)
            val attributes = DirectShellCredentialFileSystem.attributes(dir)
                ?: return@exclusive Inspection(Status.EMPTY)
            if (!attributes.isDirectory) return@exclusive Inspection(Status.CORRUPT)
            io.validateNamespace(dir)
            directory = dir
            if (DirectShellCredentialFileSystem.attributes(dir.resolve(LOCK)) == null) {
                return@exclusive Inspection(Status.IO_UNCERTAIN)
            }
            lease = io.acquireLease(dir, false)
                ?: return@exclusive Inspection(Status.BUSY)
            val key = existingKey()
            val result = if (key == null) Inspection(Status.KEY_UNAVAILABLE) else inspectOwned(key)
            if (!io.resourcesDrained()) return@exclusive Inspection(Status.OWNERSHIP_UNRESOLVED)
            if (lease!!.close()) {
                lease = null
                result
            } else {
                Inspection(Status.OWNERSHIP_UNRESOLVED)
            }
        } catch (_: DirectShellCredentialOwnershipUnresolved) {
            ownershipUnknown = true
            Inspection(Status.OWNERSHIP_UNRESOLVED)
        } catch (failure: Exception) {
            if (!io.resourcesDrained() || lease?.close() == false) {
                Inspection(Status.OWNERSHIP_UNRESOLVED)
            } else {
                lease = null
                Inspection(platformStatus(failure, Status.IO_UNCERTAIN))
            }
        }
    }

    private fun inspectOwned(key: DirectShellCredentialAead): Inspection {
        require(lease?.valid() == true) { "credential_ownership_lost" }
        val names = mutableSetOf<String>()
        var seen = 0
        Files.newDirectoryStream(directory!!).use { entries ->
            for (entry in entries) {
                if (++seen > 4 || entry.fileName.toString() !in NAMES) return Inspection(Status.CORRUPT)
                names.add(entry.fileName.toString())
                if (DirectShellCredentialFileSystem.attributes(entry)?.isRegularFile != true) {
                    return Inspection(
                        Status.CORRUPT,
                    )
                }
            }
        }
        val records = try {
            listOf(ANCHOR, PENDING, RECORD).filter(names::contains).associateWith {
                val record = DirectShellCredentialCodec.decode(io.read(path(it)), key)
                require(
                    record.kind == if (it == ANCHOR) {
                        DirectShellCredentialCodec.Kind.RECOVERY
                    } else {
                        DirectShellCredentialCodec.Kind.AUTHENTICATED
                    },
                ) { "credential_record_role" }
                record
            }
        } catch (failure: Exception) {
            return Inspection(platformStatus(failure, Status.CORRUPT))
        }
        val first = records.values.firstOrNull()
        if (first != null && records.values.any {
                !MessageDigest.isEqual(it.binding, first.binding) ||
                    !MessageDigest.isEqual(it.identity.certificate.encoded, first.identity.certificate.encoded)
            }
        ) {
            return Inspection(Status.CORRUPT)
        }
        return Inspection(
            when {
                RECORD in records && records.size > 1 -> Status.COMMITTED_CLEANUP_PENDING
                RECORD in records -> Status.COMMITTED_RECONCILIATION
                PENDING in records -> Status.PREPARED_RECONCILIATION
                ANCHOR in records -> Status.RECOVERY_RECONCILIATION
                else -> Status.EMPTY
            },
        )
    }

    private fun readAnchor(key: DirectShellCredentialAead): DirectShellCredentialCodec.Record {
        val bytes = io.read(path(ANCHOR))
        require(
            MessageDigest.isEqual(anchorDigest, DirectShellCredentialCodec.digest(bytes)),
        ) { "credential_anchor_changed" }
        return DirectShellCredentialCodec.decode(bytes, key).also {
            require(
                it.kind == DirectShellCredentialCodec.Kind.RECOVERY &&
                    MessageDigest.isEqual(it.binding, DirectShellCredentialCodec.binding(ticket!!)),
            ) { "credential_anchor_binding" }
        }
    }

    private fun existingKey(): DirectShellCredentialAead? = try {
        wrappingKey.existingAead()
    } catch (_: Exception) {
        null
    }

    private fun platformStatus(failure: Exception, fallback: Status): Status =
        when ((failure as? DirectShellCredentialPlatformFailure)?.problem) {
            DirectShellCredentialPlatformProblem.UNSUPPORTED -> Status.UNSUPPORTED
            DirectShellCredentialPlatformProblem.UNKNOWN -> Status.IO_UNCERTAIN
            DirectShellCredentialPlatformProblem.CLEANUP_INCOMPLETE -> Status.OWNERSHIP_UNRESOLVED
            DirectShellCredentialPlatformProblem.FOREIGN_RECORD -> Status.FOREIGN_RECORD
            null -> fallback
        }

    private fun path(name: String): Path = directory!!.resolve(name)

    private inline fun <T> exclusive(busy: T, block: () -> T): T {
        if (!executing.compareAndSet(false, true)) return busy
        return try {
            block()
        } finally {
            executing.set(false)
        }
    }

    companion object {
        const val DIRECTORY = "directshell-pairing-v1"
        const val LOCK = "writer.lock"
        const val ANCHOR = "identity.recovery"
        const val PENDING = "identity.pending"
        const val RECORD = "identity.record"
        private val NAMES = setOf(LOCK, ANCHOR, PENDING, RECORD)

        private fun notPublished() = DirectShellPairingStoreReceipt(
            DirectShellPairingPublication.NOT_PUBLISHED,
            DirectShellPairingDurability.NOT_APPLICABLE,
        )

        private fun unknown() = DirectShellPairingStoreReceipt(
            DirectShellPairingPublication.UNKNOWN,
            DirectShellPairingDurability.UNCONFIRMED,
            false,
        )

        private fun published(synchronized: Boolean, cleaned: Boolean) = DirectShellPairingStoreReceipt(
            DirectShellPairingPublication.PUBLISHED,
            if (synchronized) DirectShellPairingDurability.SYNC_CONFIRMED else DirectShellPairingDurability.UNCONFIRMED,
            cleaned,
            1,
        )
    }
}

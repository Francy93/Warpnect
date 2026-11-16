package io.warpnect.platform.privileged.directshell.pairing

import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/** Isolated experiment. No platform writer, no fallback and no consumer of recovered identities. */
internal class DirectShellEnrollmentIntentPrototype(
    private val fixtureRoot: Path,
    private val io: DirectShellCredentialFileSystem,
    private val provider: NoReplaceKeyProvider,
    private val nonce: () -> ByteArray = { ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes) },
    private val checkpoint: (Stage) -> Unit = {},
) {
    enum class Lookup { ABSENT_CONFIRMED, PRESENT, UNKNOWN }
    enum class Generation { CREATED, COLLISION, UNKNOWN }
    enum class Stage { BEFORE_INTENT, INTENT_SYNCED, KEY_ACKNOWLEDGED, BEFORE_PUBLICATION, PUBLISHED }
    enum class State {
        EMPTY,
        BUSY,
        STALE,
        UNRECOGNIZED,
        UNAUTHENTICATED_INTENT,
        ALIAS_CONFLICT,
        PROVIDER_UNCERTAIN,
        PUBLICATION_UNCERTAIN,
        AUTHENTICATED_ANCHOR,
        RECONCILIATION,
        OWNERSHIP_UNRESOLVED,
    }

    /** A negative containsAlias() is not ABSENT_CONFIRMED. COLLISION must not clobber the alias. */
    interface NoReplaceKeyProvider {
        fun lookup(alias: String): Lookup
        fun generateNoReplace(alias: String): Generation
        fun verifiedExisting(alias: String): DirectShellCredentialAead?
    }

    private val busy = AtomicBoolean()
    private var used = false
    private var lease: DirectShellCredentialLease? = null
    private var ownershipUnknown = false

    fun attempt(
        ticket: DirectShellPairingTicket,
        work: DirectShellPairingWorkContext,
        identity: () -> DirectShellCredentialIdentity,
    ): State = exclusive(State.BUSY) {
        fun authorized() = ticket.credentialRevision == 0L && work.mayProvision(ticket)
        fun effectAllowed() = lease?.valid() == true && authorized()
        if (used || !authorized()) return@exclusive State.STALE
        used = true
        var failure = State.RECONCILIATION
        try {
            val base = io.canonicalBase(fixtureRoot)
            if (!authorized()) return@exclusive State.STALE
            val dir = base.resolve(DIRECTORY)
            try {
                Files.createDirectory(dir)
            } catch (_: java.nio.file.FileAlreadyExistsException) {
                // A stable namespace is inspected, never replaced.
            }
            io.validateNamespace(dir)
            lease = DirectShellCredentialLease.acquire(dir.resolve(LOCK), true) ?: return@exclusive State.BUSY
            if (entries(dir) != setOf(LOCK)) return@exclusive State.RECONCILIATION
            io.syncDirectory(base)
            val material = identity()
            val intent = newIntent(ticket)
            val alias = requireNotNull(readAlias(intent))
            checkpoint(Stage.BEFORE_INTENT)
            if (!effectAllowed()) return@exclusive State.STALE
            io.writeNew(dir.resolve(INTENT), intent)
            io.syncDirectory(dir)
            checkIntent(dir, intent)
            checkpoint(Stage.INTENT_SYNCED)

            if (!effectAllowed()) return@exclusive State.RECONCILIATION
            failure = State.PROVIDER_UNCERTAIN
            when (provider.lookup(alias)) {
                Lookup.PRESENT -> return@exclusive State.ALIAS_CONFLICT
                Lookup.UNKNOWN -> return@exclusive State.PROVIDER_UNCERTAIN
                Lookup.ABSENT_CONFIRMED -> Unit
            }
            checkIntent(dir, intent)
            if (!effectAllowed()) return@exclusive State.RECONCILIATION
            when (provider.generateNoReplace(alias)) {
                Generation.COLLISION -> return@exclusive State.ALIAS_CONFLICT
                Generation.UNKNOWN -> return@exclusive State.PROVIDER_UNCERTAIN
                Generation.CREATED -> Unit
            }
            checkpoint(Stage.KEY_ACKNOWLEDGED)
            val key = provider.verifiedExisting(alias) ?: return@exclusive State.PROVIDER_UNCERTAIN
            failure = State.PUBLICATION_UNCERTAIN
            val binding = DirectShellCredentialCodec.digest(intent)
            val encrypted = DirectShellCredentialCodec.encode(
                DirectShellCredentialCodec.Record(DirectShellCredentialCodec.Kind.RECOVERY, binding, material, null),
                key,
            )
            if (!effectAllowed()) return@exclusive State.RECONCILIATION
            io.writeNew(dir.resolve(PENDING), encrypted)
            io.syncDirectory(dir)
            checkpoint(Stage.BEFORE_PUBLICATION)
            checkIntent(dir, intent)
            if (!effectAllowed()) return@exclusive State.RECONCILIATION
            val outcome = io.publishNoReplace(dir.resolve(ANCHOR), dir.resolve(PENDING))
            if (outcome.publication != DirectShellPairingPublication.PUBLISHED) {
                return@exclusive State.PUBLICATION_UNCERTAIN
            }
            checkpoint(Stage.PUBLISHED)
            io.syncDirectory(dir)
            checkIntent(dir, intent)
            val stored = io.read(dir.resolve(ANCHOR))
            check(MessageDigest.isEqual(encrypted, stored) && authentic(stored, intent, key))
            if (!effectAllowed()) return@exclusive State.RECONCILIATION
            State.AUTHENTICATED_ANCHOR
        } catch (_: DirectShellCredentialOwnershipUnresolved) {
            ownershipUnknown = true
            State.OWNERSHIP_UNRESOLVED
        } catch (_: Exception) {
            failure
        }
    }

    /** Read-back of authenticated bytes never restores authority and never generates or deletes a key. */
    fun inspectReadOnly(): State = exclusive(State.BUSY) {
        if (used) return@exclusive State.BUSY
        used = true
        val state = try {
            val dir = io.canonicalBase(fixtureRoot).resolve(DIRECTORY)
            if (DirectShellCredentialFileSystem.attributes(dir) == null) return@exclusive State.EMPTY
            io.validateNamespace(dir)
            if (DirectShellCredentialFileSystem.attributes(dir.resolve(LOCK)) == null) {
                return@exclusive State.RECONCILIATION
            }
            lease = DirectShellCredentialLease.acquire(dir.resolve(LOCK), false) ?: return@exclusive State.BUSY
            readOwned(dir)
        } catch (_: DirectShellCredentialOwnershipUnresolved) {
            ownershipUnknown = true
            State.OWNERSHIP_UNRESOLVED
        } catch (_: Exception) {
            State.RECONCILIATION
        }
        if (releaseOwned()) state else State.OWNERSHIP_UNRESOLVED
    }

    fun drain(): Boolean = exclusive(false) {
        used = true
        releaseOwned()
    }

    private fun releaseOwned(): Boolean {
        if (ownershipUnknown || !io.resourcesDrained() || lease?.close() == false) return false
        lease = null
        return true
    }

    private fun readOwned(dir: Path): State {
        val files = entries(dir)
        if (files == setOf(LOCK)) return State.EMPTY
        if (files.isEmpty() || INTENT !in files) return State.UNRECOGNIZED
        val bytes = io.read(dir.resolve(INTENT))
        val alias = readAlias(bytes) ?: return State.UNRECOGNIZED
        if (ANCHOR !in files) return State.UNAUTHENTICATED_INTENT
        val key = provider.verifiedExisting(alias) ?: return State.PROVIDER_UNCERTAIN
        val stored = io.read(dir.resolve(ANCHOR))
        if (PENDING in files && !MessageDigest.isEqual(stored, io.read(dir.resolve(PENDING)))) {
            return State.RECONCILIATION
        }
        return if (authentic(stored, bytes, key)) State.AUTHENTICATED_ANCHOR else State.RECONCILIATION
    }

    private fun checkIntent(dir: Path, expected: ByteArray) {
        check(MessageDigest.isEqual(expected, io.read(dir.resolve(INTENT))))
    }

    private fun entries(dir: Path): Set<String> {
        check(lease?.valid() == true)
        return Files.newDirectoryStream(dir).use { stream ->
            val result = mutableSetOf<String>()
            for (entry in stream) {
                val name = entry.fileName.toString()
                if (name !in NAMES || DirectShellCredentialFileSystem.attributes(entry)?.isRegularFile != true) {
                    return emptySet()
                }
                result.add(name)
            }
            result
        }
    }

    private fun newIntent(ticket: DirectShellPairingTicket): ByteArray {
        val random = nonce()
        require(random.size == NONCE_BYTES)
        return ByteBuffer.allocate(INTENT_BYTES).putInt(MAGIC).putInt(VERSION)
            .put(DirectShellCredentialCodec.binding(ticket)).put(random).array()
    }

    private fun readAlias(bytes: ByteArray): String? {
        if (bytes.size != INTENT_BYTES) return null
        val input = ByteBuffer.wrap(bytes)
        if (input.int != MAGIC || input.int != VERSION) return null
        input.position(INTENT_BYTES - NONCE_BYTES)
        val name = ByteArray(NONCE_BYTES).also(input::get).joinToString("") { "%02x".format(it) }
        return ALIAS_PREFIX + name
    }

    private fun authentic(stored: ByteArray, intent: ByteArray, key: DirectShellCredentialAead): Boolean {
        val decoded = DirectShellCredentialCodec.decode(stored, key)
        return decoded.kind == DirectShellCredentialCodec.Kind.RECOVERY &&
            MessageDigest.isEqual(decoded.binding, DirectShellCredentialCodec.digest(intent))
    }

    private inline fun <T> exclusive(rejected: T, block: () -> T): T {
        if (!busy.compareAndSet(false, true)) return rejected
        return try {
            block()
        } finally {
            busy.set(false)
        }
    }

    companion object {
        const val DIRECTORY = "directshell-first-enrollment-experiment-v1"
        const val LOCK = "writer.lock"
        const val INTENT = "intent.bin"
        const val PENDING = "anchor.pending"
        const val ANCHOR = "anchor.bin"
        const val ALIAS_PREFIX = "warpnect.directshell.experimental.enrollment.v1."
        private const val MAGIC = 0x574E494E
        private const val VERSION = 1
        private const val NONCE_BYTES = 32
        private const val INTENT_BYTES = 8 + 32 + NONCE_BYTES
        private val NAMES = setOf(LOCK, INTENT, PENDING, ANCHOR)
    }
}

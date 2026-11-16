package io.warpnect.platform.privileged.directshell.pairing

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.platform.app.InstrumentationRegistry
import io.warpnect.debug.storage.PairingStorageFixtureProtocol
import java.io.File
import java.math.BigInteger
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.Date
import java.util.UUID
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/** DEBUG instrumentation only. Never targets production records/alias or existing DEBUG pairing identity. */
internal class AndroidPairingStorageFixture(
    multiprocess: Boolean = false,
    expectedApi: Int = 30,
) : AutoCloseable {
    init {
        check(expectedApi == 30 || (expectedApi == 31 && !multiprocess))
        check(Build.VERSION.SDK_INT == expectedApi)
    }

    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val ownerRoot = target.noBackupFilesDir.toPath().toRealPath()
    val token: String = UUID.randomUUID().toString()
    private val prefix = if (multiprocess) PairingStorageFixtureProtocol.PREFIX else "wn-pairing-storage-"
    private val aliasPrefix = if (multiprocess) {
        PairingStorageFixtureProtocol.ALIAS
    } else {
        "warpnect.test.directshell.pairing.aes256.v1."
    }
    private val fixture = ownerRoot.resolve(prefix + token)
    val base: Path = fixture.resolve("no_backup")
    val alias = aliasPrefix + token
    private val aliases = mutableSetOf<String>()
    private val stores = mutableListOf<DirectShellCredentialStorage>()
    val directory: Path get() = base.resolve(DirectShellCredentialStorage.DIRECTORY)
    val record: Path get() = directory.resolve(DirectShellCredentialStorage.RECORD)
    val keys = AndroidDirectShellCredentialKeyStore(alias)
    val ticket = DirectShellPairingTicket("instrumentation-owner", "instrumentation-attempt", 1, 1, 0)
    val context: Context

    init {
        check(Process.myUid() == target.applicationInfo.uid)
        val domain = File("/proc/self/attr/current").readText().trim().trimEnd('\u0000')
        check(domain.startsWith("u:r:untrusted_app:"))
        check(!ownerRoot.resolve(DirectShellCredentialStorage.DIRECTORY).toFile().exists())
        val mode = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
        Files.createDirectory(fixture, mode)
        Files.createDirectory(base, mode)
        emit("api=$expectedApi uid=${Process.myUid()} domain=$domain fixture=${fixture.fileName}")
        context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getDataDir(): File = fixture.toFile()
            override fun getNoBackupFilesDir(): File = base.toFile()
            override fun getApplicationInfo(): ApplicationInfo = ApplicationInfo(target.applicationInfo).apply {
                dataDir = fixture.toString()
            }
        }
    }

    fun provision(
        ownedAlias: String = alias,
        purposes: Int = KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
    ) {
        check(ownedAlias == alias || ownedAlias.startsWith("$alias-"))
        check(ownedAlias != AndroidDirectShellCredentialKeyStore.PRODUCTION_ALIAS)
        val existing = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        check(!existing.containsAlias(ownedAlias)) { "fixture_alias_already_exists" }
        KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ownedAlias, purposes).setKeySize(256)
                    .setBlockModes(
                        KeyProperties.BLOCK_MODE_GCM,
                    ).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true).setUserAuthenticationRequired(false).build(),
            )
        }.generateKey()
        aliases.add(ownedAlias)
    }

    fun syntheticKey(ownedAlias: String = alias): SecretKey =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(ownedAlias, null) as SecretKey

    fun removeOwnKey() {
        check(alias in aliases)
        KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            deleteEntry(alias)
        }
    }

    fun adapter(): AndroidDirectShellCredentialStorageAdapter {
        val opened = AndroidDirectShellCredentialStorageAdapter.forApplication(context, keys)
        check(opened.location == AndroidDirectShellCredentialStorageAdapter.Location.RESOLVED_NOT_QUALIFIED)
        return requireNotNull(opened.adapter).also { stores.add(it.storage) }
    }

    fun store(
        fs: DirectShellCredentialFileSystem = AndroidDirectShellCredentialFileSystem(base),
    ): DirectShellCredentialStorage = DirectShellCredentialStorage(base, keys, fs).also(stores::add)

    fun begin(store: DirectShellCredentialStorage): DirectShellCredentialStorage.Candidate {
        check(store.begin(ticket, identity) == DirectShellCredentialStorage.Status.RECOVERY_READY)
        return requireNotNull(store.prepare(evidence(ticket), DirectShellStagedAuthorization {}).candidate)
    }

    fun evidence(ticket: DirectShellPairingTicket) = DirectShellAuthenticatedPairingEvidence(
        ticket,
        DirectShellCredentialCodec.digest(identity.certificate.encoded),
        ByteArray(32) { 7 },
    )

    override fun close() {
        check(stores.all { it.close() }) { "fixture_storage_drain_unproven" }
        val fds = requireNotNull(File("/proc/self/fd").listFiles())
        check(fds.size <= 512)
        fds.forEach {
            val destination = try {
                Os.readlink(it.path)
            } catch (failure: ErrnoException) {
                check(failure.errno == OsConstants.ENOENT)
                ""
            }
            check(destination != fixture.toString() && !destination.startsWith("$fixture/"))
        }
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        aliases.forEach {
            check(it == alias || it.startsWith("$alias-"))
            check(it != AndroidDirectShellCredentialKeyStore.PRODUCTION_ALIAS)
            keystore.deleteEntry(it)
            check(!keystore.containsAlias(it))
        }
        val path = fixture.toAbsolutePath().normalize()
        check(
            path.startsWith(ownerRoot) && path != ownerRoot &&
                path.fileName.toString() == prefix + token,
        )
        // Walk without FOLLOW_LINKS, confined to the unique test-owned root; never delete production storage.
        Files.walkFileTree(
            path,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    check(file.toAbsolutePath().normalize().startsWith(path))
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }
                override fun postVisitDirectory(dir: Path, failure: java.io.IOException?): FileVisitResult {
                    if (failure != null) throw failure
                    check(dir.toAbsolutePath().normalize().startsWith(path))
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
        emit(
            "cleanup=verified owned_kernel_fds=0 aliases_removed=${aliases.size} fixture_absent=${!Files.exists(
                fixture,
            )}",
        )
    }

    fun emit(message: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(
            2,
            Bundle().apply { putString("stream", "INTEGRATED_STORAGE $message\n") },
        )
    }

    companion object {
        val identity: DirectShellCredentialIdentity by lazy { newIdentity() }

        fun newIdentity(): DirectShellCredentialIdentity {
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val name = X500Name("CN=Warpnect Isolated Storage Fixture")
            val certificate = JcaX509CertificateConverter().getCertificate(
                JcaX509v3CertificateBuilder(name, BigInteger.ONE, Date(0), Date(4102444800000L), name, pair.public)
                    .build(JcaContentSignerBuilder("SHA256withRSA").build(pair.private)),
            )
            return DirectShellCredentialIdentity(pair.private, certificate)
        }
    }
}

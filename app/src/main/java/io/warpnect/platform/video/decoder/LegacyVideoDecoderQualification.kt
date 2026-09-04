package io.warpnect.platform.video.decoder

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.os.Build
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import io.warpnect.video.decoder.VideoDecoderConfig
import io.warpnect.video.decoder.VideoDecoderQualification
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal data class LegacyDecoderQualificationKey(
    val algorithmVersion: Int,
    val fixtureId: String,
    val fixtureSha256: String,
    val videoProfileVersion: String,
    val codecName: String,
    val buildFingerprint: String,
    val mediaRuntimeVersion: String,
) {
    val storageKey: String
        get() = MessageDigest.getInstance("SHA-256")
            .digest(
                listOf(
                    algorithmVersion.toString(),
                    fixtureId,
                    fixtureSha256,
                    videoProfileVersion,
                    codecName,
                    buildFingerprint,
                    mediaRuntimeVersion,
                ).joinToString("\u0000").toByteArray(StandardCharsets.UTF_8),
            )
            .joinToString("") { "%02x".format(it) }

    companion object {
        fun forCandidate(codecName: String): LegacyDecoderQualificationKey = LegacyDecoderQualificationKey(
            algorithmVersion = LegacyDecoderQualificationProfile.ALGORITHM_VERSION,
            fixtureId = LegacyDecoderQualificationProfile.FIXTURE_ID,
            fixtureSha256 = LegacyDecoderQualificationProfile.FIXTURE_SHA256,
            videoProfileVersion = LegacyDecoderQualificationProfile.VIDEO_PROFILE_VERSION,
            codecName = codecName,
            buildFingerprint = Build.FINGERPRINT,
            mediaRuntimeVersion = LegacyDecoderQualificationProfile.MEDIA_RUNTIME_VERSION,
        )
    }
}

internal object LegacyDecoderQualificationProfile {
    const val ALGORITHM_VERSION = 1
    const val FIXTURE_ID = "rfc002i-avc-720p60-full-v2"
    const val FIXTURE_SHA256 = "554F1E7AD82F5DFDE40BF4D276F8F76C0170D41908C3B8554C652F55B17D86E0"
    const val VIDEO_PROFILE_VERSION = "avc-1280x720-60-v1"
    const val MEDIA_RUNTIME_VERSION = "android-mediacodec-v1"
}

internal enum class LegacyDecoderProbeResult(val code: Int) {
    Pass(0),
    NormalRejection(1),
    ConfigureFailure(2),
    StartFailure(3),
    InsufficientPerformance(4),
    FixtureIntegrityFailure(5),
    FixtureUnavailable(6),
    MainThreadRejected(7),
    ProbeServiceUnavailable(8),
    ProbeProcessDied(9),
    ProbeTimedOut(10),
    ;

    companion object {
        fun fromCode(code: Int): LegacyDecoderProbeResult =
            entries.firstOrNull { it.code == code } ?: ProbeServiceUnavailable
    }
}

internal enum class LegacyDecoderQualificationOutcome {
    Pass,
    Fail,
    Inconclusive,
}

internal enum class LegacyDecoderQualificationSource {
    ActiveProbe,
    PersistentCache,
    CurrentProcessQuarantine,
}

internal data class LegacyDecoderQualificationDecision(
    val outcome: LegacyDecoderQualificationOutcome,
    val source: LegacyDecoderQualificationSource,
    val result: LegacyDecoderProbeResult,
) {
    fun qualificationState(): VideoDecoderQualification = when (source) {
        LegacyDecoderQualificationSource.ActiveProbe -> when (outcome) {
            LegacyDecoderQualificationOutcome.Pass -> VideoDecoderQualification.ActivePass
            LegacyDecoderQualificationOutcome.Fail -> VideoDecoderQualification.ActiveFail
            LegacyDecoderQualificationOutcome.Inconclusive -> VideoDecoderQualification.ActiveInconclusive
        }
        LegacyDecoderQualificationSource.PersistentCache -> when (outcome) {
            LegacyDecoderQualificationOutcome.Pass -> VideoDecoderQualification.CachedPass
            LegacyDecoderQualificationOutcome.Fail -> VideoDecoderQualification.CachedFail
            LegacyDecoderQualificationOutcome.Inconclusive -> VideoDecoderQualification.CachedInconclusive
        }
        LegacyDecoderQualificationSource.CurrentProcessQuarantine -> VideoDecoderQualification.CachedInconclusive
    }
}

internal fun interface LegacyVideoDecoderQualifier {
    fun qualify(config: VideoDecoderConfig, codecName: String): LegacyDecoderQualificationDecision
}

internal interface LegacyDecoderQualificationDebugObserver {
    fun onProbeStarted() = Unit

    fun onDecision(decision: LegacyDecoderQualificationDecision) = Unit

    companion object {
        val None = object : LegacyDecoderQualificationDebugObserver {}
    }
}

internal fun interface LegacyDecoderProbeServiceCaller {
    fun probe(codecName: String, algorithmVersion: Int): LegacyDecoderProbeResult
}

internal interface LegacyDecoderQualificationStore {
    fun read(key: LegacyDecoderQualificationKey): LegacyDecoderProbeResult?
    fun write(key: LegacyDecoderQualificationKey, result: LegacyDecoderProbeResult)
}

/** Exact-key cache with a process-local quarantine for Binder, timeout, and codec-process death. */
internal class CachedLegacyVideoDecoderQualifier(
    private val caller: LegacyDecoderProbeServiceCaller,
    private val store: LegacyDecoderQualificationStore,
    private val keyFactory: (String) -> LegacyDecoderQualificationKey = LegacyDecoderQualificationKey::forCandidate,
) : LegacyVideoDecoderQualifier {
    private val memory = mutableMapOf<LegacyDecoderQualificationKey, LegacyDecoderProbeResult>()
    private val quarantined = mutableSetOf<LegacyDecoderQualificationKey>()

    override fun qualify(config: VideoDecoderConfig, codecName: String): LegacyDecoderQualificationDecision =
        synchronized(
            memory,
        ) {
            val key = keyFactory(codecName)
            if (key in quarantined) {
                return cachedDecision(
                    LegacyDecoderProbeResult.ProbeProcessDied,
                    LegacyDecoderQualificationSource.CurrentProcessQuarantine,
                )
            }
            memory[key]?.let { return cachedDecision(it, LegacyDecoderQualificationSource.PersistentCache) }
            store.read(key)?.let { cached ->
                memory[key] = cached
                if (cached.requiresQuarantine()) quarantined += key
                return cachedDecision(cached, LegacyDecoderQualificationSource.PersistentCache)
            }

            val result = caller.probe(codecName, key.algorithmVersion)
            memory[key] = result
            store.write(key, result)
            if (result.requiresQuarantine()) quarantined += key
            activeDecision(result)
        }

    private fun cachedDecision(
        result: LegacyDecoderProbeResult,
        source: LegacyDecoderQualificationSource,
    ): LegacyDecoderQualificationDecision = LegacyDecoderQualificationDecision(
        outcome = result.outcome(),
        source = source,
        result = result,
    )

    private fun activeDecision(result: LegacyDecoderProbeResult): LegacyDecoderQualificationDecision =
        LegacyDecoderQualificationDecision(
            outcome = result.outcome(),
            source = LegacyDecoderQualificationSource.ActiveProbe,
            result = result,
        )
}

internal class SharedPreferencesLegacyDecoderQualificationStore(context: Context) : LegacyDecoderQualificationStore {
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(
        "legacy_decoder_qualification",
        Context.MODE_PRIVATE,
    )

    override fun read(key: LegacyDecoderQualificationKey): LegacyDecoderProbeResult? =
        preferences.getInt(key.storageKey, Int.MIN_VALUE)
            .takeUnless { it == Int.MIN_VALUE }
            ?.let(LegacyDecoderProbeResult::fromCode)

    override fun write(key: LegacyDecoderQualificationKey, result: LegacyDecoderProbeResult) {
        preferences.edit().putInt(key.storageKey, result.code).apply()
    }
}

/** Application-private caller for the normal-UID `:decoderProbe` process. */
internal class AndroidLegacyDecoderProbeServiceCaller(
    context: Context,
    private val bindTimeoutMs: Long = BIND_TIMEOUT_MS,
    private val callTimeoutMs: Long = CALL_TIMEOUT_MS,
) : LegacyDecoderProbeServiceCaller {
    private val appContext = context.applicationContext

    override fun probe(codecName: String, algorithmVersion: Int): LegacyDecoderProbeResult {
        if (Looper.myLooper() == Looper.getMainLooper()) return LegacyDecoderProbeResult.MainThreadRejected
        val connection = ProbeConnection()
        val intent = Intent(appContext, LegacyVideoDecoderProbeService::class.java)
        val bound = runCatching {
            appContext.bindService(
                intent,
                connection,
                Context.BIND_AUTO_CREATE,
            )
        }.getOrDefault(false)
        if (!bound) return LegacyDecoderProbeResult.ProbeServiceUnavailable
        return try {
            if (!connection.await(bindTimeoutMs)) return LegacyDecoderProbeResult.ProbeTimedOut
            val service = connection.service ?: return connection.failureResult()
            invokeBounded(service, codecName, algorithmVersion)
        } finally {
            runCatching { appContext.unbindService(connection) }
        }
    }

    private fun invokeBounded(
        service: ILegacyVideoDecoderProbeService,
        codecName: String,
        algorithmVersion: Int,
    ): LegacyDecoderProbeResult {
        val executor = Executors.newSingleThreadExecutor()
        return try {
            val result = executor.submit<LegacyDecoderProbeResult> {
                LegacyDecoderProbeResult.fromCode(service.probe(codecName, algorithmVersion))
            }.get(callTimeoutMs, TimeUnit.MILLISECONDS)
            result
        } catch (_: TimeoutException) {
            LegacyDecoderProbeResult.ProbeTimedOut
        } catch (_: DeadObjectException) {
            LegacyDecoderProbeResult.ProbeProcessDied
        } catch (_: RemoteException) {
            LegacyDecoderProbeResult.ProbeProcessDied
        } catch (error: ExecutionException) {
            if (error.cause is DeadObjectException || error.cause is RemoteException) {
                LegacyDecoderProbeResult.ProbeProcessDied
            } else {
                LegacyDecoderProbeResult.ProbeServiceUnavailable
            }
        } catch (_: Exception) {
            LegacyDecoderProbeResult.ProbeServiceUnavailable
        } finally {
            executor.shutdownNow()
        }
    }

    private class ProbeConnection : ServiceConnection {
        private val connected = CountDownLatch(1)

        @Volatile var service: ILegacyVideoDecoderProbeService? = null

        @Volatile private var processDied = false

        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = ILegacyVideoDecoderProbeService.Stub.asInterface(binder)
            connected.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            processDied = true
            service = null
            connected.countDown()
        }

        override fun onBindingDied(name: ComponentName) {
            processDied = true
            service = null
            connected.countDown()
        }

        override fun onNullBinding(name: ComponentName) {
            service = null
            connected.countDown()
        }

        fun await(timeoutMs: Long): Boolean = connected.await(timeoutMs, TimeUnit.MILLISECONDS)

        fun failureResult(): LegacyDecoderProbeResult = if (processDied) {
            LegacyDecoderProbeResult.ProbeProcessDied
        } else {
            LegacyDecoderProbeResult.ProbeServiceUnavailable
        }
    }

    private companion object {
        const val BIND_TIMEOUT_MS = 1_000L
        const val CALL_TIMEOUT_MS = 8_000L
    }
}

internal class AndroidLegacyVideoDecoderQualifier(context: Context) : LegacyVideoDecoderQualifier {
    private val cached = CachedLegacyVideoDecoderQualifier(
        caller = AndroidLegacyDecoderProbeServiceCaller(context),
        store = SharedPreferencesLegacyDecoderQualificationStore(context),
    )

    override fun qualify(config: VideoDecoderConfig, codecName: String): LegacyDecoderQualificationDecision =
        cached.qualify(config, codecName)
}

private fun LegacyDecoderProbeResult.outcome(): LegacyDecoderQualificationOutcome = when (this) {
    LegacyDecoderProbeResult.Pass -> LegacyDecoderQualificationOutcome.Pass
    LegacyDecoderProbeResult.NormalRejection,
    LegacyDecoderProbeResult.ConfigureFailure,
    LegacyDecoderProbeResult.StartFailure,
    LegacyDecoderProbeResult.InsufficientPerformance,
    -> LegacyDecoderQualificationOutcome.Fail
    LegacyDecoderProbeResult.FixtureIntegrityFailure,
    LegacyDecoderProbeResult.FixtureUnavailable,
    LegacyDecoderProbeResult.MainThreadRejected,
    LegacyDecoderProbeResult.ProbeServiceUnavailable,
    LegacyDecoderProbeResult.ProbeProcessDied,
    LegacyDecoderProbeResult.ProbeTimedOut,
    -> LegacyDecoderQualificationOutcome.Inconclusive
}

private fun LegacyDecoderProbeResult.requiresQuarantine(): Boolean = this in setOf(
    LegacyDecoderProbeResult.ProbeProcessDied,
    LegacyDecoderProbeResult.ProbeTimedOut,
    LegacyDecoderProbeResult.ProbeServiceUnavailable,
)

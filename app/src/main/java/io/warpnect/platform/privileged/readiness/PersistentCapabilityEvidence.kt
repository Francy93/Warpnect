package io.warpnect.platform.privileged.readiness

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import io.warpnect.BuildConfig
import io.warpnect.platform.input.injection.DirectRootInputCapability
import io.warpnect.platform.privileged.planning.CapabilityQualification
import io.warpnect.platform.privileged.planning.CapabilityQualificationProvenance
import io.warpnect.platform.privileged.planning.CapabilityQualificationSource
import io.warpnect.platform.privileged.planning.CapabilityQualificationState
import io.warpnect.platform.privileged.planning.PrivilegedCapability
import io.warpnect.platform.privileged.planning.PrivilegedCapabilityMatrix
import io.warpnect.platform.privileged.planning.PrivilegedCapabilityPlanResult
import io.warpnect.platform.privileged.planning.PrivilegedCapabilityPlanner
import io.warpnect.platform.privileged.planning.PrivilegedProvider
import io.warpnect.platform.privileged.runtime.DirectRootRuntime
import io.warpnect.platform.privileged.runtime.PrivilegedRuntimeSnapshot
import io.warpnect.platform.privileged.runtime.RootBootstrapProvenance
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

/**
 * Exact compatibility identity for one durable provider/capability observation. The Android
 * build identity is stored only as a SHA-256 digest, never as a raw fingerprint.
 */
internal data class PersistentCapabilityEvidenceKey(
    val provider: PrivilegedProvider,
    val capability: PrivilegedCapability,
    val androidApi: Int,
    val androidBuildIdentityDigest: String,
    val warpnectVersionCode: Int,
    val providerImplementationRevision: String,
    val providerRuntimeVersion: String?,
    val backendRevision: String,
) {
    init {
        require(androidApi >= 0) { "capability_evidence_api_invalid" }
        require(warpnectVersionCode >= 0) { "capability_evidence_app_version_invalid" }
        require(
            androidBuildIdentityDigest.length == SHA256_HEX_LENGTH && androidBuildIdentityDigest.all {
                it in "0123456789abcdef"
            },
        ) {
            "capability_evidence_build_digest_invalid"
        }
        require(providerImplementationRevision.isEvidenceField()) { "capability_evidence_provider_revision_invalid" }
        require(providerRuntimeVersion == null || providerRuntimeVersion.isEvidenceField()) {
            "capability_evidence_runtime_version_invalid"
        }
        require(backendRevision.isEvidenceField()) { "capability_evidence_backend_revision_invalid" }
    }

    /** Stable diagnostics/storage identity. It intentionally excludes no compatibility dimension. */
    val stableId: String
        get() = sha256Hex(
            listOf(
                provider.name,
                capability.name,
                androidApi.toString(),
                androidBuildIdentityDigest,
                warpnectVersionCode.toString(),
                providerImplementationRevision,
                providerRuntimeVersion.orEmpty(),
                backendRevision,
            ).joinToString("\u0000"),
        )

    private companion object {
        const val SHA256_HEX_LENGTH = 64
    }
}

/** One bounded durable positive observation. Transient runtime states are never representable. */
internal data class PersistentCapabilityEvidenceRecord(
    val schemaVersion: Int = PersistentCapabilityEvidenceCodec.SCHEMA_VERSION,
    val provider: PrivilegedProvider,
    val capability: PrivilegedCapability,
    val state: CapabilityQualificationState,
    val originalSource: CapabilityQualificationSource,
    val compatibilityKey: PersistentCapabilityEvidenceKey,
    val recordedAtEpochMs: Long,
) {
    init {
        require(schemaVersion == PersistentCapabilityEvidenceCodec.SCHEMA_VERSION) {
            "capability_evidence_schema_invalid"
        }
        require(state.isUsable) { "capability_evidence_state_not_persistable" }
        require(
            provider != PrivilegedProvider.DIRECT_ROOT ||
                (
                    capability == PrivilegedCapability.INPUT && compatibilityKey.providerRuntimeVersion in
                        RootBootstrapProvenance.entries.map { it.name }
                    ),
        ) { "capability_evidence_root_domain_invalid" }
        require(originalSource != CapabilityQualificationSource.EXISTING_COMPATIBILITY_CACHE) {
            "capability_evidence_cached_source_not_persistable"
        }
        require(provider == compatibilityKey.provider && capability == compatibilityKey.capability) {
            "capability_evidence_coordinate_mismatch"
        }
        require(recordedAtEpochMs >= 0L) { "capability_evidence_timestamp_invalid" }
    }

    val coordinate: PersistentCapabilityEvidenceCoordinate
        get() = PersistentCapabilityEvidenceCoordinate(provider, capability)
}

internal data class PersistentCapabilityEvidenceCoordinate(
    val provider: PrivilegedProvider,
    val capability: PrivilegedCapability,
)

/** The compatibility factory may deliberately leave a provider version unknown while it is offline. */
internal sealed interface PersistentCapabilityEvidenceKeyExpectation {
    data class Known(val key: PersistentCapabilityEvidenceKey) : PersistentCapabilityEvidenceKeyExpectation

    /** Retain prior evidence but do not reuse it until its provider-version key can be checked. */
    data class TemporarilyUnknown(
        val knownDimensions: PersistentCapabilityEvidenceKey,
    ) : PersistentCapabilityEvidenceKeyExpectation

    /** There is no valid positive evidence domain for this coordinate. */
    data object Unsupported : PersistentCapabilityEvidenceKeyExpectation
}

internal fun interface PersistentCapabilityEvidenceKeyFactory {
    fun expectationFor(
        provider: PrivilegedProvider,
        capability: PrivilegedCapability,
    ): PersistentCapabilityEvidenceKeyExpectation
}

/**
 * Android compatibility-key source. Revisions are explicit invalidation controls rather than
 * inferred device names. DirectRoot keys are Input-only and explicitly bootstrap-provenance scoped.
 */
internal class AndroidPersistentCapabilityEvidenceKeyFactory(
    private val apiLevel: () -> Int = { Build.VERSION.SDK_INT },
    private val buildFingerprint: () -> String = { Build.FINGERPRINT.orEmpty() },
    private val warpnectVersionCode: () -> Int = { BuildConfig.VERSION_CODE },
    private val shizukuRuntimeVersion: () -> String?,
    private val rootBootstrapProvenance: () -> RootBootstrapProvenance? = {
        DirectRootInputCapability.bootstrapProvenance
    },
) : PersistentCapabilityEvidenceKeyFactory {
    override fun expectationFor(
        provider: PrivilegedProvider,
        capability: PrivilegedCapability,
    ): PersistentCapabilityEvidenceKeyExpectation = when (provider) {
        PrivilegedProvider.DIRECT_ROOT -> rootBootstrapProvenance()?.takeIf { capability == PrivilegedCapability.INPUT }
            ?.let { provenance ->
                PersistentCapabilityEvidenceKeyExpectation.Known(
                    keyFor(
                        provider, capability,
                        DirectRootRuntime.IMPLEMENTATION, provenance.name, DirectRootInputCapability.BACKEND_REVISION,
                    ),
                )
            } ?: PersistentCapabilityEvidenceKeyExpectation.Unsupported
        PrivilegedProvider.DIRECT_SHELL -> PersistentCapabilityEvidenceKeyExpectation.Known(
            keyFor(
                provider = provider,
                capability = capability,
                providerImplementationRevision = DIRECT_SHELL_IMPLEMENTATION_REVISION,
                providerRuntimeVersion = null,
                backendRevision = directShellBackendRevision(capability),
            ),
        )

        PrivilegedProvider.SHIZUKU -> {
            val version = shizukuRuntimeVersion()
            val key = keyFor(
                provider = provider,
                capability = capability,
                providerImplementationRevision = SHIZUKU_IMPLEMENTATION_REVISION,
                providerRuntimeVersion = version,
                backendRevision = shizukuBackendRevision(capability),
            )
            if (version == null) {
                PersistentCapabilityEvidenceKeyExpectation.TemporarilyUnknown(key)
            } else {
                PersistentCapabilityEvidenceKeyExpectation.Known(key)
            }
        }
    }

    private fun keyFor(
        provider: PrivilegedProvider,
        capability: PrivilegedCapability,
        providerImplementationRevision: String,
        providerRuntimeVersion: String?,
        backendRevision: String,
    ): PersistentCapabilityEvidenceKey = PersistentCapabilityEvidenceKey(
        provider = provider,
        capability = capability,
        androidApi = apiLevel(),
        androidBuildIdentityDigest = sha256Hex(buildFingerprint()),
        warpnectVersionCode = warpnectVersionCode(),
        providerImplementationRevision = providerImplementationRevision,
        providerRuntimeVersion = providerRuntimeVersion,
        backendRevision = backendRevision,
    )

    private companion object {
        const val DIRECT_SHELL_IMPLEMENTATION_REVISION = "directshell-bootstrap-v1"
        const val SHIZUKU_IMPLEMENTATION_REVISION = "shizuku-adapter-v1"

        fun directShellBackendRevision(capability: PrivilegedCapability): String = when (capability) {
            PrivilegedCapability.VIDEO -> "directshell-video-capture-v1"
            PrivilegedCapability.SYSTEM_AUDIO -> "directshell-system-audio-v1"
            PrivilegedCapability.INPUT -> "directshell-input-injection-v1"
        }

        fun shizukuBackendRevision(capability: PrivilegedCapability): String = when (capability) {
            PrivilegedCapability.VIDEO -> "shizuku-video-capture-v1"
            PrivilegedCapability.SYSTEM_AUDIO -> "shizuku-system-audio-v1"
            PrivilegedCapability.INPUT -> "shizuku-input-injection-v1"
        }
    }
}

internal enum class PersistentCapabilityEvidenceDecodeStatus {
    EMPTY,
    VALID,
    PARTIAL,
    SCHEMA_MISMATCH,
    CORRUPT,
}

internal data class PersistentCapabilityEvidenceDecoded(
    val records: List<PersistentCapabilityEvidenceRecord>,
    val status: PersistentCapabilityEvidenceDecodeStatus,
)

/** Pure compact codec so corruption and schema behavior can be tested without Android storage. */
internal object PersistentCapabilityEvidenceCodec {
    const val SCHEMA_VERSION = 1
    private const val HEADER = "WARPNECT_PRIVILEGED_CAPABILITY_EVIDENCE"
    private const val MAX_RECORDS = 9
    private const val MAX_PAYLOAD_CHARS = 16 * 1024
    private const val FIELD_COUNT = 12

    fun encode(records: List<PersistentCapabilityEvidenceRecord>): String {
        val normalized = normalize(records)
        require(normalized.size <= MAX_RECORDS) { "capability_evidence_store_capacity_exceeded" }
        return buildString {
            append(HEADER)
            append('|')
            append(SCHEMA_VERSION)
            normalized.forEach { record ->
                append('\n')
                append(
                    listOf(
                        record.schemaVersion.toString(),
                        record.provider.name,
                        record.capability.name,
                        record.state.name,
                        record.originalSource.name,
                        record.recordedAtEpochMs.toString(),
                        record.compatibilityKey.androidApi.toString(),
                        record.compatibilityKey.androidBuildIdentityDigest,
                        record.compatibilityKey.warpnectVersionCode.toString(),
                        record.compatibilityKey.providerImplementationRevision,
                        record.compatibilityKey.providerRuntimeVersion.orEmpty(),
                        record.compatibilityKey.backendRevision,
                    ).joinToString("|") { value -> encodeField(value) },
                )
            }
        }.also { encoded ->
            require(encoded.length <= MAX_PAYLOAD_CHARS) { "capability_evidence_store_payload_too_large" }
        }
    }

    fun decode(payload: String?): PersistentCapabilityEvidenceDecoded {
        if (payload.isNullOrEmpty()) {
            return PersistentCapabilityEvidenceDecoded(emptyList(), PersistentCapabilityEvidenceDecodeStatus.EMPTY)
        }
        if (payload.length > MAX_PAYLOAD_CHARS) {
            return PersistentCapabilityEvidenceDecoded(emptyList(), PersistentCapabilityEvidenceDecodeStatus.CORRUPT)
        }
        val lines = payload.split('\n')
        val header = lines.firstOrNull()?.split('|', limit = 2)
            ?: return PersistentCapabilityEvidenceDecoded(emptyList(), PersistentCapabilityEvidenceDecodeStatus.CORRUPT)
        if (header.size != 2 || header[0] != HEADER) {
            return PersistentCapabilityEvidenceDecoded(emptyList(), PersistentCapabilityEvidenceDecodeStatus.CORRUPT)
        }
        if (header[1].toIntOrNull() != SCHEMA_VERSION) {
            return PersistentCapabilityEvidenceDecoded(
                emptyList(),
                PersistentCapabilityEvidenceDecodeStatus.SCHEMA_MISMATCH,
            )
        }
        if (lines.size - 1 > MAX_RECORDS) {
            return PersistentCapabilityEvidenceDecoded(emptyList(), PersistentCapabilityEvidenceDecodeStatus.CORRUPT)
        }

        val records = linkedMapOf<PersistentCapabilityEvidenceCoordinate, PersistentCapabilityEvidenceRecord>()
        var invalidRows = 0
        lines.drop(1).filter { it.isNotEmpty() }.forEach { line ->
            val record = decodeRecord(line)
            if (record == null) {
                invalidRows += 1
            } else {
                val previous = records[record.coordinate]
                if (previous == null || record.recordedAtEpochMs >= previous.recordedAtEpochMs) {
                    records[record.coordinate] = record
                }
                if (previous != null) invalidRows += 1
            }
        }
        val normalized = records.values.toList()
        val status = when {
            invalidRows == 0 -> PersistentCapabilityEvidenceDecodeStatus.VALID
            normalized.isNotEmpty() -> PersistentCapabilityEvidenceDecodeStatus.PARTIAL
            else -> PersistentCapabilityEvidenceDecodeStatus.CORRUPT
        }
        return PersistentCapabilityEvidenceDecoded(normalized, status)
    }

    private fun decodeRecord(line: String): PersistentCapabilityEvidenceRecord? = runCatching {
        val fields = line.split('|')
        require(fields.size == FIELD_COUNT) { "capability_evidence_field_count_invalid" }
        val decoded = fields.map(::decodeField)
        val recordSchema = decoded[0].toInt()
        val provider = PrivilegedProvider.valueOf(decoded[1])
        val capability = PrivilegedCapability.valueOf(decoded[2])
        val state = CapabilityQualificationState.valueOf(decoded[3])
        val source = CapabilityQualificationSource.valueOf(decoded[4])
        val recordedAt = decoded[5].toLong()
        val key = PersistentCapabilityEvidenceKey(
            provider = provider,
            capability = capability,
            androidApi = decoded[6].toInt(),
            androidBuildIdentityDigest = decoded[7],
            warpnectVersionCode = decoded[8].toInt(),
            providerImplementationRevision = decoded[9],
            providerRuntimeVersion = decoded[10].ifEmpty { null },
            backendRevision = decoded[11],
        )
        PersistentCapabilityEvidenceRecord(
            schemaVersion = recordSchema,
            provider = provider,
            capability = capability,
            state = state,
            originalSource = source,
            compatibilityKey = key,
            recordedAtEpochMs = recordedAt,
        )
    }.getOrNull()

    private fun normalize(records: List<PersistentCapabilityEvidenceRecord>): List<PersistentCapabilityEvidenceRecord> =
        records
            .groupBy { it.coordinate }
            .values
            .map { candidates -> candidates.maxBy { it.recordedAtEpochMs } }
            .sortedWith(
                compareBy<PersistentCapabilityEvidenceRecord> { it.provider.ordinal }.thenBy { it.capability.ordinal },
            )

    private fun encodeField(value: String): String = Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decodeField(value: String): String = String(
        Base64.getUrlDecoder().decode(value),
        StandardCharsets.UTF_8,
    )
}

/** Bounded app-private persistence. It stores only compatibility metadata, never credentials. */
internal interface PersistentCapabilityEvidenceStore {
    /** Shared across repository instances accessing the same backing store. */
    val transactionLock: Any get() = this

    fun load(): PersistentCapabilityEvidenceDecoded

    /** A complete replacement is one atomic-enough preference commit for the tiny fixed matrix. */
    fun replace(records: List<PersistentCapabilityEvidenceRecord>): Boolean
}

internal class SharedPreferencesPersistentCapabilityEvidenceStore(context: Context) :
    PersistentCapabilityEvidenceStore {
    private val applicationContext = context.applicationContext
    private val preferences: SharedPreferences by lazy {
        applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }
    override val transactionLock: Any get() = LOCK

    override fun load(): PersistentCapabilityEvidenceDecoded = synchronized(transactionLock) {
        val decoded = try {
            PersistentCapabilityEvidenceCodec.decode(preferences.getString(PAYLOAD_KEY, null))
        } catch (_: ClassCastException) {
            PersistentCapabilityEvidenceDecoded(emptyList(), PersistentCapabilityEvidenceDecodeStatus.CORRUPT)
        }
        if (
            decoded.status == PersistentCapabilityEvidenceDecodeStatus.SCHEMA_MISMATCH ||
            decoded.status == PersistentCapabilityEvidenceDecodeStatus.CORRUPT
        ) {
            preferences.edit().remove(PAYLOAD_KEY).commit()
        }
        decoded
    }

    override fun replace(records: List<PersistentCapabilityEvidenceRecord>): Boolean = synchronized(transactionLock) {
        val editor = preferences.edit()
        if (records.isEmpty()) {
            editor.remove(PAYLOAD_KEY).commit()
        } else {
            editor.putString(PAYLOAD_KEY, PersistentCapabilityEvidenceCodec.encode(records)).commit()
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "privileged_capability_evidence"
        const val PAYLOAD_KEY = "records_v1"
        val LOCK = Any()
    }
}

internal object NoOpPersistentCapabilityEvidenceStore : PersistentCapabilityEvidenceStore {
    override fun load(): PersistentCapabilityEvidenceDecoded =
        PersistentCapabilityEvidenceDecoded(emptyList(), PersistentCapabilityEvidenceDecodeStatus.EMPTY)

    override fun replace(records: List<PersistentCapabilityEvidenceRecord>): Boolean = true
}

internal data class PersistentCapabilityEvidenceLoad(
    val reusableRecords: List<PersistentCapabilityEvidenceRecord>,
    val retainedRecordCount: Int,
    val invalidatedRecordCount: Int,
    val decodeStatus: PersistentCapabilityEvidenceDecodeStatus,
)

/**
 * Selectively validates durable records. A currently unknown Shizuku server version retains its
 * historical record without reusing it, while a known mismatch removes only that coordinate.
 */
internal class PersistentCapabilityEvidenceRepository(
    private val store: PersistentCapabilityEvidenceStore,
    private val keyFactory: PersistentCapabilityEvidenceKeyFactory,
    private val recordedAtEpochMs: () -> Long,
    private val onDiagnostic: (String) -> Unit = {},
) {
    fun loadReusableEvidence(): PersistentCapabilityEvidenceLoad = synchronized(store.transactionLock) {
        val decoded = loadSafely()
        val retained = mutableListOf<PersistentCapabilityEvidenceRecord>()
        val reusable = mutableListOf<PersistentCapabilityEvidenceRecord>()
        var invalidated = 0
        decoded.records.forEach { record ->
            when (val expectation = keyFactory.expectationFor(record.provider, record.capability)) {
                is PersistentCapabilityEvidenceKeyExpectation.Known -> {
                    if (record.compatibilityKey == expectation.key) {
                        retained += record
                        reusable += record
                    } else {
                        invalidated += 1
                        onDiagnostic(
                            "capability_evidence_invalidated coordinate=${record.provider}.${record.capability} " +
                                "reason=key_mismatch",
                        )
                    }
                }

                is PersistentCapabilityEvidenceKeyExpectation.TemporarilyUnknown -> {
                    if (record.compatibilityKey.copy(providerRuntimeVersion = null) == expectation.knownDimensions) {
                        retained += record
                    } else {
                        invalidated += 1
                        onDiagnostic(
                            "capability_evidence_invalidated coordinate=${record.provider}.${record.capability} " +
                                "reason=known_key_mismatch",
                        )
                    }
                }
                PersistentCapabilityEvidenceKeyExpectation.Unsupported -> {
                    invalidated += 1
                    onDiagnostic(
                        "capability_evidence_invalidated coordinate=${record.provider}.${record.capability} " +
                            "reason=unsupported",
                    )
                }
            }
        }
        if (invalidated > 0 || decoded.status == PersistentCapabilityEvidenceDecodeStatus.PARTIAL) {
            replaceSafely(retained)
        }
        onDiagnostic(
            "capability_evidence_cache_load status=${decoded.status} hit=${reusable.size} " +
                "retained=${retained.size} invalidated=$invalidated",
        )
        PersistentCapabilityEvidenceLoad(
            reusableRecords = reusable,
            retainedRecordCount = retained.size,
            invalidatedRecordCount = invalidated,
            decodeStatus = decoded.status,
        )
    }

    /**
     * Only live provider observations may alter durable evidence. Usable observations replace a
     * matching coordinate; a fresh capability-bound contradiction removes old matching evidence.
     */
    fun reconcileAuthoritativeMatrix(matrix: PrivilegedCapabilityMatrix) = synchronized(store.transactionLock) {
        val existing = loadSafely().records.toMutableList()
        var changed = false
        matrix.entries().forEach { qualification ->
            if (qualification.provenance.source == CapabilityQualificationSource.EXISTING_COMPATIBILITY_CACHE) {
                return@forEach
            }
            when (val expectation = keyFactory.expectationFor(qualification.provider, qualification.capability)) {
                is PersistentCapabilityEvidenceKeyExpectation.Known -> {
                    val coordinate = PersistentCapabilityEvidenceCoordinate(
                        qualification.provider,
                        qualification.capability,
                    )
                    if (qualification.state.isUsable) {
                        val previousRecord = existing.singleOrNull {
                            it.coordinate == coordinate && it.compatibilityKey == expectation.key
                        }
                        // A cheap requalification cannot erase stronger historical validation.
                        if (previousRecord?.state == CapabilityQualificationState.VALIDATED &&
                            qualification.state == CapabilityQualificationState.QUALIFIED
                        ) {
                            return@forEach
                        }
                        val replacement = PersistentCapabilityEvidenceRecord(
                            provider = qualification.provider,
                            capability = qualification.capability,
                            state = qualification.state,
                            originalSource = qualification.provenance.source,
                            compatibilityKey = expectation.key,
                            recordedAtEpochMs = recordedAtEpochMs(),
                        )
                        val previous = existing.filter { it.coordinate == coordinate }
                        if (previous.singleOrNull() != replacement) {
                            existing.removeAll { it.coordinate == coordinate }
                            existing += replacement
                            changed = true
                            onDiagnostic(
                                "capability_evidence_updated " +
                                    "coordinate=${qualification.provider}.${qualification.capability} " +
                                    "state=${qualification.state}",
                            )
                        }
                    } else if (qualification.isDurableContradiction()) {
                        val removed = existing.removeAll {
                            it.coordinate == coordinate && it.compatibilityKey == expectation.key
                        }
                        if (removed) {
                            changed = true
                            onDiagnostic(
                                "capability_evidence_invalidated " +
                                    "coordinate=${qualification.provider}.${qualification.capability} " +
                                    "reason=authoritative_contradiction",
                            )
                        }
                    }
                }

                is PersistentCapabilityEvidenceKeyExpectation.TemporarilyUnknown,
                PersistentCapabilityEvidenceKeyExpectation.Unsupported,
                -> Unit
            }
        }
        if (changed) replaceSafely(existing)
    }

    private fun loadSafely(): PersistentCapabilityEvidenceDecoded = try {
        store.load()
    } catch (_: RuntimeException) {
        onDiagnostic("capability_evidence_cache_load status=STORAGE_UNAVAILABLE")
        PersistentCapabilityEvidenceDecoded(emptyList(), PersistentCapabilityEvidenceDecodeStatus.CORRUPT)
    }

    private fun replaceSafely(records: List<PersistentCapabilityEvidenceRecord>) {
        val written = runCatching { store.replace(records) }.getOrDefault(false)
        if (!written) onDiagnostic("capability_evidence_cache_write status=STORAGE_UNAVAILABLE")
    }

    private fun CapabilityQualification.isDurableContradiction(): Boolean =
        state == CapabilityQualificationState.QUALIFICATION_FAILED &&
            provenance.source in setOf(
                CapabilityQualificationSource.CURRENT_ATTEMPT_PROBE,
                CapabilityQualificationSource.PROVIDER_ADAPTER,
            ) && reason in DURABLE_CONTRADICTIONS

    private companion object {
        // Only explicit framework-contract contradictions. Authorization, crashes, resource
        // exhaustion, remote timeouts and DEBUG suppression remain volatile failures.
        val DURABLE_CONTRADICTIONS = setOf(
            "video_capture_UnsupportedAndroidVersion",
            "video_capture_HiddenApiUnavailable",
            "system_audio_UnsupportedPlatform",
            "system_audio_UnsupportedFormat",
            "system_audio_AudioPolicyUnavailable",
            "input_InputApiUnavailable",
            "input_DisplayTargetingUnsupported",
        )
    }
}

/** Rebuilds the immutable RFC-006I matrix solely from already validated compatibility evidence. */
internal object CachedCapabilityEvidenceMatrixBuilder {
    fun build(records: List<PersistentCapabilityEvidenceRecord>): PrivilegedCapabilityMatrix {
        val byCoordinate = records.associateBy { it.coordinate }
        return PrivilegedCapabilityMatrix.complete { provider, capability ->
            val record = byCoordinate[PersistentCapabilityEvidenceCoordinate(provider, capability)]
            when {
                provider == PrivilegedProvider.DIRECT_ROOT && capability != PrivilegedCapability.INPUT ->
                    CapabilityQualification.unavailable(
                        provider = provider,
                        capability = capability,
                        state = CapabilityQualificationState.NOT_IMPLEMENTED,
                        reason = "direct_root_not_implemented",
                        provenance = CapabilityQualificationProvenance(
                            CapabilityQualificationSource.EXISTING_COMPATIBILITY_CACHE,
                        ),
                    )

                record != null -> CapabilityQualification.usable(
                    provider = provider,
                    capability = capability,
                    state = record.state,
                    reason = "cached_${record.state.name.lowercase()}_evidence",
                    provenance = CapabilityQualificationProvenance(
                        source = CapabilityQualificationSource.EXISTING_COMPATIBILITY_CACHE,
                        compatibilityKey = record.compatibilityKey.stableId,
                        providerVersion = record.compatibilityKey.providerRuntimeVersion,
                        implementationVersion = record.compatibilityKey.providerImplementationRevision,
                    ),
                )

                else -> CapabilityQualification.unavailable(
                    provider = provider,
                    capability = capability,
                    state = CapabilityQualificationState.DEFERRED,
                    reason = "persistent_evidence_missing",
                    provenance = CapabilityQualificationProvenance(
                        CapabilityQualificationSource.EXISTING_COMPATIBILITY_CACHE,
                    ),
                )
            }
        }
    }
}

/** Durable evidence is reused only after the current feature and codec probes still agree. */
internal object LateRevalidatedCapabilityEvidence {
    fun combine(live: PrivilegedCapabilityMatrix, cached: PrivilegedCapabilityMatrix): PrivilegedCapabilityMatrix =
        live.withQualifications(
            live.entries().mapNotNull { fresh ->
                val previous = cached.qualification(fresh.provider, fresh.capability)
                if (!fresh.state.isUsable || !previous.state.isUsable) return@mapNotNull null
                previous.copy(
                    reason = "cached_evidence_late_revalidated",
                    provenance = previous.provenance.copy(runtimeGeneration = fresh.provenance.runtimeGeneration),
                )
            },
        )
}

internal data class CachedPreparedPlanResult(
    val matrix: PrivilegedCapabilityMatrix,
    val preparedPlan: PreparedPrivilegedCapabilityPlan?,
    val reusableEvidenceCount: Int,
)

/**
 * Produces only an advisory plan. Provider acquisition, final qualification and RFC-006I freeze
 * remain exclusively in the existing Host-admission path.
 */
internal object CachedPreparedPlanBuilder {
    fun build(
        runtimeState: PrivilegedRuntimeStateSnapshot,
        passiveRuntimeSnapshot: PrivilegedRuntimeSnapshot,
        records: List<PersistentCapabilityEvidenceRecord>,
        preparedAtMonotonicMs: Long,
    ): CachedPreparedPlanResult {
        val durableMatrix = CachedCapabilityEvidenceMatrixBuilder.build(records)
        val matrix = durableMatrix.withQualifications(
            durableMatrix.entries().filter { it.state.isUsable && !providerIsUsable(it, runtimeState) }
                .map { cell ->
                    cell.copy(
                        state = CapabilityQualificationState.NOT_AVAILABLE,
                        reason = "cached_provider_volatile_unavailable",
                    )
                },
        )
        val selected = PrivilegedCapabilityPlanner.select(matrix) as? PrivilegedCapabilityPlanResult.Complete
        val plan = selected?.plan
        val prepared = plan?.let { complete ->
            PreparedPrivilegedCapabilityPlan(
                generation = runtimeState.generation,
                runtimeState = runtimeState,
                runtimeSnapshot = passiveRuntimeSnapshot,
                matrix = matrix,
                plan = complete,
                evidenceSource = HostReadinessEvidenceSource.EXISTING_COMPATIBILITY_CACHE,
                evidenceCompatibilityKeys = complete.selectedQualifications.mapNotNull {
                    it.provenance.compatibilityKey
                },
                commitState = HostReadinessPlanCommitState.ADVISORY,
                preparedAtMonotonicMs = preparedAtMonotonicMs,
            )
        }
        return CachedPreparedPlanResult(matrix, prepared, records.size)
    }

    private fun providerIsUsable(cell: CapabilityQualification, runtimeState: PrivilegedRuntimeStateSnapshot): Boolean =
        when (cell.provider) {
            PrivilegedProvider.DIRECT_ROOT ->
                runtimeState.directRoot.state == PrivilegedRuntimeAvailabilityState.READY &&
                    runtimeState.directRoot.rootFoundation?.provenance?.name == cell.provenance.providerVersion
            PrivilegedProvider.DIRECT_SHELL -> runtimeState.directShell.isCachedPlanCandidate
            PrivilegedProvider.SHIZUKU -> runtimeState.shizuku.state == PrivilegedRuntimeAvailabilityState.READY
        }
}

/** Cached DirectShell evidence is useful before startup only when the registered runtime is acquirable. */
internal val PrivilegedRuntimeAvailability.isCachedPlanCandidate: Boolean
    get() = state == PrivilegedRuntimeAvailabilityState.READY || candidateForHostAcquisition

private fun String.isEvidenceField(): Boolean = isNotBlank() &&
    length <= MAX_EVIDENCE_FIELD_LENGTH &&
    none { it == '\n' || it == '\r' }

private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }

private const val MAX_EVIDENCE_FIELD_LENGTH = 160

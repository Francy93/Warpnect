package io.warpnect.platform.privileged.readiness

import io.warpnect.platform.privileged.planning.CapabilityQualification
import io.warpnect.platform.privileged.planning.CapabilityQualificationProvenance
import io.warpnect.platform.privileged.planning.CapabilityQualificationSource
import io.warpnect.platform.privileged.planning.CapabilityQualificationState
import io.warpnect.platform.privileged.planning.PrivilegedCapability
import io.warpnect.platform.privileged.planning.PrivilegedCapabilityMatrix
import io.warpnect.platform.privileged.planning.PrivilegedCapabilityPlanShape
import io.warpnect.platform.privileged.planning.PrivilegedProvider
import io.warpnect.platform.privileged.runtime.PrivilegedRuntimeKind
import io.warpnect.platform.privileged.runtime.PrivilegedRuntimeSnapshot
import io.warpnect.platform.privileged.runtime.PrivilegedRuntimeState
import io.warpnect.platform.privileged.runtime.RootBootstrapProvenance
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentCapabilityEvidenceTest {
    @Test fun `Root Video and Input evidence round trip without Root Audio`() {
        val records = listOf(PrivilegedCapability.VIDEO, PrivilegedCapability.INPUT).map {
            record(PrivilegedProvider.DIRECT_ROOT, it, runtime = "LABORATORY_EXTERNAL_ROOT")
        }
        val decoded = PersistentCapabilityEvidenceCodec.decode(PersistentCapabilityEvidenceCodec.encode(records))
        assertEquals(records, decoded.records)
        val matrix = CachedCapabilityEvidenceMatrixBuilder.build(decoded.records)
        assertTrue(matrix.qualification(PrivilegedProvider.DIRECT_ROOT, PrivilegedCapability.VIDEO).state.isUsable)
        assertEquals(
            CapabilityQualificationState.DEFERRED,
            matrix.qualification(PrivilegedProvider.DIRECT_ROOT, PrivilegedCapability.SYSTEM_AUDIO).state,
        )
        assertNull(
            CachedPreparedPlanBuilder.build(runtimeState(false), idleDirectShellRuntime(), records, 1).preparedPlan,
        )
    }

    @Test fun `Root Video contradiction preserves Input evidence`() {
        val video =
            record(PrivilegedProvider.DIRECT_ROOT, PrivilegedCapability.VIDEO, runtime = "LABORATORY_EXTERNAL_ROOT")
        val input =
            record(PrivilegedProvider.DIRECT_ROOT, PrivilegedCapability.INPUT, runtime = "LABORATORY_EXTERNAL_ROOT")
        val store = MemoryStore(listOf(video, input))
        val repo =
            repository(
                store,
                mapOf(
                    video.coordinate to PersistentCapabilityEvidenceKeyExpectation.Known(video.compatibilityKey),
                    input.coordinate to PersistentCapabilityEvidenceKeyExpectation.Known(input.compatibilityKey),
                ),
            )
        repo.reconcileAuthoritativeMatrix(
            matrix { provider, capability ->
                unavailable(
                    provider,
                    capability,
                    if (provider == PrivilegedProvider.DIRECT_ROOT && capability == PrivilegedCapability.VIDEO) {
                        CapabilityQualificationState.QUALIFICATION_FAILED
                    } else {
                        CapabilityQualificationState.DEFERRED
                    },
                    "video_capture_HiddenApiUnavailable",
                )
            },
        )
        assertEquals(listOf(input), repo.loadReusableEvidence().reusableRecords)
    }

    @Test
    fun `Root Input evidence round trips with laboratory provenance`() {
        val root = record(
            PrivilegedProvider.DIRECT_ROOT,
            PrivilegedCapability.INPUT,
            runtime = "LABORATORY_EXTERNAL_ROOT",
        )
        val decoded = PersistentCapabilityEvidenceCodec.decode(PersistentCapabilityEvidenceCodec.encode(listOf(root)))
        assertEquals(listOf(root), decoded.records)
        val matrix = CachedCapabilityEvidenceMatrixBuilder.build(decoded.records)
        assertEquals(
            CapabilityQualificationState.VALIDATED,
            matrix.qualification(PrivilegedProvider.DIRECT_ROOT, PrivilegedCapability.INPUT).state,
        )
        assertEquals(
            CapabilityQualificationState.DEFERRED,
            matrix.qualification(PrivilegedProvider.DIRECT_ROOT, PrivilegedCapability.VIDEO).state,
        )
        assertEquals(
            CapabilityQualificationState.DEFERRED,
            matrix.qualification(PrivilegedProvider.DIRECT_ROOT, PrivilegedCapability.SYSTEM_AUDIO).state,
        )
    }

    @Test
    fun `laboratory evidence cannot satisfy production root manager compatibility`() {
        val root = record(
            PrivilegedProvider.DIRECT_ROOT,
            PrivilegedCapability.INPUT,
            runtime = "LABORATORY_EXTERNAL_ROOT",
        )
        val shell = record(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.VIDEO)
        val store = MemoryStore(listOf(root, shell))
        val production = root.compatibilityKey.copy(providerRuntimeVersion = "PRODUCTION_ROOT_MANAGER")
        val repository =
            repository(store, mapOf(root.coordinate to PersistentCapabilityEvidenceKeyExpectation.Known(production)))
        assertEquals(listOf(shell), repository.loadReusableEvidence().reusableRecords)
    }

    @Test
    fun `Root helper outage retains durable Input evidence`() {
        val root = record(
            PrivilegedProvider.DIRECT_ROOT,
            PrivilegedCapability.INPUT,
            runtime = "LABORATORY_EXTERNAL_ROOT",
        )
        val store = MemoryStore(listOf(root))
        val repository =
            repository(
                store,
                mapOf(root.coordinate to PersistentCapabilityEvidenceKeyExpectation.Known(root.compatibilityKey)),
            )
        repository.reconcileAuthoritativeMatrix(
            matrix { provider, capability ->
                unavailable(provider, capability, CapabilityQualificationState.NOT_AVAILABLE, "root_helper_lost")
            },
        )
        assertEquals(listOf(root), repository.loadReusableEvidence().reusableRecords)
    }

    @Test
    fun `authoritative Root Input contradiction affects only Root Input`() {
        val root = record(
            PrivilegedProvider.DIRECT_ROOT,
            PrivilegedCapability.INPUT,
            runtime = "LABORATORY_EXTERNAL_ROOT",
        )
        val shell = record(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.INPUT)
        val store = MemoryStore(listOf(root, shell))
        val repository =
            repository(
                store,
                mapOf(root.coordinate to PersistentCapabilityEvidenceKeyExpectation.Known(root.compatibilityKey)),
            )
        repository.reconcileAuthoritativeMatrix(
            matrix { provider, capability ->
                if (provider == PrivilegedProvider.DIRECT_ROOT && capability == PrivilegedCapability.INPUT) {
                    unavailable(
                        provider,
                        capability,
                        CapabilityQualificationState.QUALIFICATION_FAILED,
                        "input_InputApiUnavailable",
                    )
                } else {
                    unavailable(provider, capability, CapabilityQualificationState.DEFERRED)
                }
            },
        )
        assertEquals(listOf(shell), repository.loadReusableEvidence().reusableRecords)
    }

    @Test
    fun `Root Audio evidence round trips but missing bootstrap provenance is rejected`() {
        val audio = record(
            PrivilegedProvider.DIRECT_ROOT,
            PrivilegedCapability.SYSTEM_AUDIO,
            runtime = "LABORATORY_EXTERNAL_ROOT",
        )
        assertEquals(
            listOf(audio),
            PersistentCapabilityEvidenceCodec.decode(
                PersistentCapabilityEvidenceCodec.encode(listOf(audio)),
            ).records,
        )
        assertTrue(
            CachedCapabilityEvidenceMatrixBuilder.build(listOf(audio))
                .qualification(PrivilegedProvider.DIRECT_ROOT, PrivilegedCapability.SYSTEM_AUDIO).state.isUsable,
        )
        assertTrue(runCatching { record(PrivilegedProvider.DIRECT_ROOT, PrivilegedCapability.SYSTEM_AUDIO) }.isFailure)
        assertTrue(runCatching { record(PrivilegedProvider.DIRECT_ROOT, PrivilegedCapability.INPUT) }.isFailure)
    }

    @Test fun `Root Audio contradiction invalidates only Audio and outage retains all evidence`() {
        val records = PrivilegedCapability.canonicalOrder.map {
            record(PrivilegedProvider.DIRECT_ROOT, it, runtime = "LABORATORY_EXTERNAL_ROOT")
        }
        val store = MemoryStore(records)
        val repo = repository(
            store,
            records.associate {
                it.coordinate to
                    PersistentCapabilityEvidenceKeyExpectation.Known(it.compatibilityKey)
            },
        )
        repo.reconcileAuthoritativeMatrix(
            matrix { p, c ->
                unavailable(p, c, CapabilityQualificationState.NOT_AVAILABLE, "root_helper_lost")
            },
        )
        assertEquals(records.toSet(), repo.loadReusableEvidence().reusableRecords.toSet())
        repo.reconcileAuthoritativeMatrix(
            matrix { p, c ->
                unavailable(
                    p,
                    c,
                    if (p == PrivilegedProvider.DIRECT_ROOT && c == PrivilegedCapability.SYSTEM_AUDIO) {
                        CapabilityQualificationState.QUALIFICATION_FAILED
                    } else {
                        CapabilityQualificationState.DEFERRED
                    },
                    "system_audio_UnsupportedFormat",
                )
            },
        )
        assertEquals(
            records.filter { it.capability != PrivilegedCapability.SYSTEM_AUDIO }.toSet(),
            repo.loadReusableEvidence().reusableRecords.toSet(),
        )
    }

    @Test
    fun `cached Root Input does not make absent runtime ready or a complete Host`() {
        val root = record(
            PrivilegedProvider.DIRECT_ROOT,
            PrivilegedCapability.INPUT,
            runtime = "LABORATORY_EXTERNAL_ROOT",
        )
        val result = CachedPreparedPlanBuilder.build(runtimeState(false), idleDirectShellRuntime(), listOf(root), 1)
        assertNull(result.preparedPlan)
        assertEquals(
            CapabilityQualificationState.NOT_AVAILABLE,
            result.matrix.qualification(PrivilegedProvider.DIRECT_ROOT, PrivilegedCapability.INPUT).state,
        )
    }

    @Test
    fun `Root compatibility key distinguishes provenance for all implemented capabilities`() {
        fun factory(provenance: RootBootstrapProvenance?) = AndroidPersistentCapabilityEvidenceKeyFactory(
            { 26 },
            { "aosp-x86_64-lab" },
            { 1 },
            { null },
            { provenance },
        )
        val lab = factory(RootBootstrapProvenance.LABORATORY_EXTERNAL_ROOT)
        val production =
            factory(RootBootstrapProvenance.PRODUCTION_ROOT_MANAGER)
        val labKey = (
            lab.expectationFor(PrivilegedProvider.DIRECT_ROOT, PrivilegedCapability.INPUT) as
                PersistentCapabilityEvidenceKeyExpectation.Known
            ).key
        val prodKey = (
            production.expectationFor(PrivilegedProvider.DIRECT_ROOT, PrivilegedCapability.INPUT) as
                PersistentCapabilityEvidenceKeyExpectation.Known
            ).key
        assertTrue(labKey.stableId != prodKey.stableId)
        assertTrue(
            lab.expectationFor(PrivilegedProvider.DIRECT_ROOT, PrivilegedCapability.SYSTEM_AUDIO)
                is PersistentCapabilityEvidenceKeyExpectation.Known,
        )
        assertEquals(
            PersistentCapabilityEvidenceKeyExpectation.Unsupported,
            factory(null).expectationFor(PrivilegedProvider.DIRECT_ROOT, PrivilegedCapability.INPUT),
        )
    }

    @Test fun `complete Root cache prepares only with matching live laboratory runtime`() {
        val records = PrivilegedCapability.canonicalOrder.map {
            record(PrivilegedProvider.DIRECT_ROOT, it, runtime = "LABORATORY_EXTERNAL_ROOT")
        }
        assertNull(
            CachedPreparedPlanBuilder.build(runtimeState(false), idleDirectShellRuntime(), records, 1).preparedPlan,
        )
        val foundation = io.warpnect.platform.privileged.runtime.DirectRootSnapshot(
            state = io.warpnect.platform.privileged.runtime.DirectRootState.HELPER_READY,
            generation = 10,
            provenance = RootBootstrapProvenance.LABORATORY_EXTERNAL_ROOT,
            identity = io.warpnect.platform.privileged.runtime.RootHelperIdentity(
                1, 0, 0, "u:r:su:s0", 26, 10,
                io.warpnect.platform.privileged.runtime.DirectRootRuntime.PROTOCOL_VERSION,
                io.warpnect.platform.privileged.runtime.DirectRootRuntime.IMPLEMENTATION, "a".repeat(64),
            ),
        )
        val live = runtimeState(false).copy(
            directRoot = PrivilegedRuntimeAvailability(
                PrivilegedRuntimeKind.DIRECT_ROOT,
                PrivilegedRuntimeAvailabilityState.READY,
                rootFoundation = foundation,
            ),
        )
        val prepared = CachedPreparedPlanBuilder.build(live, idleDirectShellRuntime(), records, 1).preparedPlan!!
        assertEquals(PrivilegedCapabilityPlanShape.SINGLE, prepared.plan.shape)
        assertEquals(listOf(PrivilegedProvider.DIRECT_ROOT), prepared.plan.participatingProviders)
        assertEquals(HostReadinessPlanCommitState.ADVISORY, prepared.commitState)
        val wrong = live.copy(
            directRoot = live.directRoot.copy(
                rootFoundation = foundation.copy(provenance = RootBootstrapProvenance.PRODUCTION_ROOT_MANAGER),
            ),
        )
        assertNull(CachedPreparedPlanBuilder.build(wrong, idleDirectShellRuntime(), records, 1).preparedPlan)
    }

    @Test
    fun `empty cache has no reusable capability evidence`() {
        val repository = repository(MemoryStore())

        val loaded = repository.loadReusableEvidence()

        assertTrue(loaded.reusableRecords.isEmpty())
        assertEquals(PersistentCapabilityEvidenceDecodeStatus.EMPTY, loaded.decodeStatus)
    }

    @Test
    fun `valid record round trip preserves qualification semantics`() {
        val record = record(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.VIDEO)
        val decoded = PersistentCapabilityEvidenceCodec.decode(
            PersistentCapabilityEvidenceCodec.encode(listOf(record)),
        )

        assertEquals(PersistentCapabilityEvidenceDecodeStatus.VALID, decoded.status)
        assertEquals(listOf(record), decoded.records)
    }

    @Test
    fun `schema mismatch and corruption fail closed`() {
        val record = record(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.VIDEO)
        val encoded = PersistentCapabilityEvidenceCodec.encode(listOf(record))

        assertEquals(
            PersistentCapabilityEvidenceDecodeStatus.SCHEMA_MISMATCH,
            PersistentCapabilityEvidenceCodec.decode(encoded.replace("|1", "|2")).status,
        )
        assertEquals(
            PersistentCapabilityEvidenceDecodeStatus.CORRUPT,
            PersistentCapabilityEvidenceCodec.decode("not-a-capability-cache").status,
        )
    }

    @Test
    fun `unknown enum row is ignored without a startup crash`() {
        val record = record(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.VIDEO)
        val encoded = PersistentCapabilityEvidenceCodec.encode(listOf(record))
            .replace("RElSRUNUX1NIRUxM", "VU5LTk9XTl9QUk9WSURFUg")

        val decoded = PersistentCapabilityEvidenceCodec.decode(encoded)

        assertTrue(decoded.records.isEmpty())
        assertEquals(PersistentCapabilityEvidenceDecodeStatus.CORRUPT, decoded.status)
    }

    @Test
    fun `exact key evidence is reusable`() {
        val store = MemoryStore(listOf(record(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.INPUT)))
        val repository = repository(store)

        val loaded = repository.loadReusableEvidence()

        assertEquals(1, loaded.reusableRecords.size)
        assertEquals(0, loaded.invalidatedRecordCount)
        assertEquals(0, store.replaceCalls)
    }

    @Test
    fun `backend revision change invalidates only the affected coordinate`() {
        val video = record(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.VIDEO)
        val input = record(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.INPUT)
        val changedInputKey = key(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.INPUT, backend = "input-v2")
        val store = MemoryStore(listOf(video, input))
        val repository = repository(
            store,
            mapOf(input.coordinate to PersistentCapabilityEvidenceKeyExpectation.Known(changedInputKey)),
        )

        val loaded = repository.loadReusableEvidence()

        assertEquals(listOf(video), loaded.reusableRecords)
        assertEquals(1, loaded.invalidatedRecordCount)
        assertEquals(listOf(video), store.records())
    }

    @Test
    fun `Shizuku provider version change leaves DirectShell evidence intact`() {
        val directShell = record(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.VIDEO)
        val shizuku = record(PrivilegedProvider.SHIZUKU, PrivilegedCapability.INPUT, runtime = "version=13.5;patch=1")
        val changedShizuku = key(
            PrivilegedProvider.SHIZUKU,
            PrivilegedCapability.INPUT,
            runtime = "version=13.6;patch=1",
        )
        val store = MemoryStore(listOf(directShell, shizuku))
        val repository = repository(
            store,
            mapOf(shizuku.coordinate to PersistentCapabilityEvidenceKeyExpectation.Known(changedShizuku)),
        )

        val loaded = repository.loadReusableEvidence()

        assertEquals(listOf(directShell), loaded.reusableRecords)
        assertEquals(listOf(directShell), store.records())
    }

    @Test
    fun `Android compatibility identity change invalidates its evidence`() {
        val video = record(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.VIDEO)
        val changed = video.compatibilityKey.copy(androidBuildIdentityDigest = "b".repeat(64))
        val store = MemoryStore(listOf(video))
        val repository = repository(
            store,
            mapOf(video.coordinate to PersistentCapabilityEvidenceKeyExpectation.Known(changed)),
        )

        assertTrue(repository.loadReusableEvidence().reusableRecords.isEmpty())
        assertTrue(store.records().isEmpty())
    }

    @Test
    fun `temporarily unknown Shizuku version retains but does not reuse evidence`() {
        val record = record(PrivilegedProvider.SHIZUKU, PrivilegedCapability.INPUT)
        val store = MemoryStore(listOf(record))
        val repository = repository(
            store,
            mapOf(
                record.coordinate to PersistentCapabilityEvidenceKeyExpectation.TemporarilyUnknown(
                    record.compatibilityKey,
                ),
            ),
        )

        val loaded = repository.loadReusableEvidence()

        assertTrue(loaded.reusableRecords.isEmpty())
        assertEquals(1, loaded.retainedRecordCount)
        assertEquals(listOf(record), store.records())
    }

    @Test
    fun `transient failure is not persisted as incompatibility`() {
        val existing = record(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.INPUT)
        val store = MemoryStore(listOf(existing))
        val repository = repository(store)
        val matrix = matrix { provider, capability ->
            if (provider == PrivilegedProvider.DIRECT_SHELL && capability == PrivilegedCapability.INPUT) {
                unavailable(provider, capability, CapabilityQualificationState.NOT_AVAILABLE)
            } else {
                unavailable(provider, capability, CapabilityQualificationState.DEFERRED)
            }
        }

        repository.reconcileAuthoritativeMatrix(matrix)

        assertEquals(listOf(existing), store.records())
    }

    @Test
    fun `authoritative capability contradiction removes matching cache evidence`() {
        val existing = record(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.INPUT)
        val store = MemoryStore(listOf(existing))
        val repository = repository(store)
        val matrix = matrix { provider, capability ->
            if (provider == PrivilegedProvider.DIRECT_SHELL && capability == PrivilegedCapability.INPUT) {
                unavailable(
                    provider,
                    capability,
                    CapabilityQualificationState.QUALIFICATION_FAILED,
                    "input_InputApiUnavailable",
                )
            } else {
                unavailable(provider, capability, CapabilityQualificationState.DEFERRED)
            }
        }

        repository.reconcileAuthoritativeMatrix(matrix)

        assertTrue(store.records().isEmpty())
    }

    @Test
    fun `usable live evidence replaces one coordinate without affecting others`() {
        val video = record(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.VIDEO, timestamp = 1L)
        val input = record(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.INPUT, timestamp = 1L)
        val store = MemoryStore(listOf(video, input))
        val repository = repository(store, now = 42L)
        val matrix = matrix { provider, capability ->
            if (provider == PrivilegedProvider.DIRECT_SHELL && capability == PrivilegedCapability.INPUT) {
                usable(provider, capability, CapabilityQualificationState.VALIDATED)
            } else {
                unavailable(provider, capability, CapabilityQualificationState.DEFERRED)
            }
        }

        repository.reconcileAuthoritativeMatrix(matrix)

        val updatedInput = requireNotNull(store.records().singleOrNull { it.coordinate == input.coordinate })
        assertEquals(42L, updatedInput.recordedAtEpochMs)
        assertEquals(video, store.records().single { it.coordinate == video.coordinate })
    }

    @Test
    fun `full cached DirectShell evidence creates a single advisory plan`() {
        val records = PrivilegedCapability.canonicalOrder.map { capability ->
            record(PrivilegedProvider.DIRECT_SHELL, capability)
        }

        val result = CachedPreparedPlanBuilder.build(
            runtimeState = runtimeState(directShellCandidate = true),
            passiveRuntimeSnapshot = idleDirectShellRuntime(),
            records = records,
            preparedAtMonotonicMs = 7L,
        )

        val prepared = requireNotNull(result.preparedPlan)
        assertEquals(PrivilegedCapabilityPlanShape.SINGLE, prepared.plan.shape)
        assertEquals(listOf(PrivilegedProvider.DIRECT_SHELL), prepared.plan.participatingProviders)
        assertEquals(HostReadinessPlanCommitState.ADVISORY, prepared.commitState)
        assertEquals(HostReadinessEvidenceSource.EXISTING_COMPATIBILITY_CACHE, prepared.evidenceSource)
        assertEquals(3, prepared.evidenceCompatibilityKeys.size)
    }

    @Test
    fun `partial cached evidence does not fabricate a prepared plan`() {
        val result = CachedPreparedPlanBuilder.build(
            runtimeState = runtimeState(directShellCandidate = true),
            passiveRuntimeSnapshot = idleDirectShellRuntime(),
            records = listOf(
                record(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.VIDEO),
                record(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.SYSTEM_AUDIO),
            ),
            preparedAtMonotonicMs = 7L,
        )

        assertNull(result.preparedPlan)
    }

    @Test
    fun `cached composite plan is deterministic without waking a provider`() {
        val result = CachedPreparedPlanBuilder.build(
            runtimeState = runtimeState(directShellCandidate = true, shizukuReady = true),
            passiveRuntimeSnapshot = idleDirectShellRuntime(),
            records = listOf(
                record(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.VIDEO),
                record(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.SYSTEM_AUDIO),
                record(PrivilegedProvider.SHIZUKU, PrivilegedCapability.INPUT),
            ),
            preparedAtMonotonicMs = 7L,
        )

        val plan = requireNotNull(result.preparedPlan).plan
        assertEquals(PrivilegedCapabilityPlanShape.COMPOSITE, plan.shape)
        assertEquals(PrivilegedProvider.SHIZUKU, plan.inputProvider)
    }

    @Test
    fun `offline provider still invalidates changed known Android dimensions`() {
        val old = record(PrivilegedProvider.SHIZUKU, PrivilegedCapability.INPUT)
        val store = MemoryStore(listOf(old))
        val changed = old.compatibilityKey.copy(androidApi = 36)
        val repository = repository(
            store,
            mapOf(old.coordinate to PersistentCapabilityEvidenceKeyExpectation.TemporarilyUnknown(changed)),
        )

        assertEquals(1, repository.loadReusableEvidence().invalidatedRecordCount)
        assertTrue(store.records().isEmpty())
    }

    @Test
    fun `app version change invalidates only a different key`() {
        val old = record(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.INPUT)
        val store = MemoryStore(listOf(old))
        val repository = repository(
            store,
            mapOf(
                old.coordinate to PersistentCapabilityEvidenceKeyExpectation.Known(
                    old.compatibilityKey.copy(warpnectVersionCode = 2),
                ),
            ),
        )

        assertEquals(1, repository.loadReusableEvidence().invalidatedRecordCount)
    }

    @Test
    fun `transient failed qualification and debug suppression preserve evidence`() {
        listOf(
            "input_ServiceDied",
            "input_UserServiceBindFailed",
            "debug_directshell_input_suppressed",
        ).forEach { reason ->
            val existing = record(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.INPUT)
            val store = MemoryStore(listOf(existing))
            val live = matrix { provider, capability ->
                unavailable(provider, capability, CapabilityQualificationState.QUALIFICATION_FAILED, reason)
            }

            repository(store).reconcileAuthoritativeMatrix(live)

            assertEquals(listOf(existing), store.records())
        }
    }

    @Test
    fun `cheap qualification does not downgrade validated evidence`() {
        val existing = record(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.INPUT)
        val store = MemoryStore(listOf(existing))
        val live = matrix { provider, capability ->
            if (provider == existing.provider && capability == existing.capability) {
                usable(provider, capability, CapabilityQualificationState.QUALIFIED)
            } else {
                unavailable(provider, capability, CapabilityQualificationState.DEFERRED)
            }
        }

        repository(store).reconcileAuthoritativeMatrix(live)

        assertEquals(listOf(existing), store.records())
        assertEquals(0, store.replaceCalls)
    }

    @Test
    fun `storage read and write errors are nonfatal`() {
        val failingStore = object : PersistentCapabilityEvidenceStore {
            override fun load(): PersistentCapabilityEvidenceDecoded = error("storage_unavailable")

            override fun replace(records: List<PersistentCapabilityEvidenceRecord>): Boolean = error(
                "storage_unavailable",
            )
        }
        val repository = repository(failingStore)

        assertTrue(repository.loadReusableEvidence().reusableRecords.isEmpty())
        repository.reconcileAuthoritativeMatrix(
            matrix { provider, capability ->
                if (provider == PrivilegedProvider.DIRECT_SHELL) {
                    usable(provider, capability, CapabilityQualificationState.QUALIFIED)
                } else {
                    unavailable(provider, capability, CapabilityQualificationState.DEFERRED)
                }
            },
        )
    }

    @Test
    fun `multiple repositories serialize complete read modify write transactions`() {
        val store = MemoryStore()
        val first = repository(store)
        val second = repository(store)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val operations = listOf(
                PrivilegedCapability.VIDEO,
                PrivilegedCapability.INPUT,
            ).mapIndexed { index, selected ->
                Callable {
                    val repository = if (index == 0) first else second
                    repeat(20) {
                        repository.reconcileAuthoritativeMatrix(
                            matrix { provider, capability ->
                                if (provider == PrivilegedProvider.DIRECT_SHELL && capability == selected) {
                                    usable(provider, capability, CapabilityQualificationState.QUALIFIED)
                                } else {
                                    unavailable(provider, capability, CapabilityQualificationState.DEFERRED)
                                }
                            },
                        )
                    }
                }
            }
            executor.invokeAll(operations).forEach { it.get(5, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
        assertEquals(
            setOf(PrivilegedCapability.VIDEO, PrivilegedCapability.INPUT),
            store.records().map {
                it.capability
            }.toSet(),
        )
    }

    @Test
    fun `volatile unavailable preferred provider does not mask another complete plan`() {
        val records = PrivilegedCapability.canonicalOrder.flatMap { capability ->
            listOf(record(PrivilegedProvider.DIRECT_SHELL, capability), record(PrivilegedProvider.SHIZUKU, capability))
        }
        val result = CachedPreparedPlanBuilder.build(
            runtimeState(false, shizukuReady = true),
            idleDirectShellRuntime(),
            records,
            7L,
        )

        assertEquals(
            listOf(PrivilegedProvider.SHIZUKU),
            requireNotNull(result.preparedPlan).plan.participatingProviders,
        )
    }

    @Test
    fun `late reuse preserves cache provenance and binds to current runtime generation`() {
        val cached = CachedCapabilityEvidenceMatrixBuilder.build(
            listOf(record(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.VIDEO)),
        )
        val live = matrix { provider, capability ->
            usable(provider, capability, CapabilityQualificationState.QUALIFIED).copy(
                provenance = CapabilityQualificationProvenance(
                    CapabilityQualificationSource.CURRENT_ATTEMPT_PROBE,
                    runtimeGeneration = 55L,
                ),
            )
        }
        val merged = LateRevalidatedCapabilityEvidence.combine(live, cached)
        val video = merged.qualification(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.VIDEO)

        assertEquals(CapabilityQualificationSource.EXISTING_COMPATIBILITY_CACHE, video.provenance.source)
        assertEquals(55L, video.provenance.runtimeGeneration)
        assertEquals(CapabilityQualificationState.VALIDATED, video.state)
        assertEquals(
            CapabilityQualificationSource.CURRENT_ATTEMPT_PROBE,
            merged.qualification(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.INPUT).provenance.source,
        )
    }

    @Test
    fun `cached positive never overrides current failed feature qualification`() {
        val cached = CachedCapabilityEvidenceMatrixBuilder.build(
            listOf(record(PrivilegedProvider.DIRECT_SHELL, PrivilegedCapability.INPUT)),
        )
        val live =
            matrix { provider, capability ->
                unavailable(
                    provider,
                    capability,
                    CapabilityQualificationState.QUALIFICATION_FAILED,
                )
            }

        assertEquals(live, LateRevalidatedCapabilityEvidence.combine(live, cached))
    }

    private fun repository(
        store: PersistentCapabilityEvidenceStore,
        overrides: Map<PersistentCapabilityEvidenceCoordinate, PersistentCapabilityEvidenceKeyExpectation> = emptyMap(),
        now: Long = 10L,
    ): PersistentCapabilityEvidenceRepository = PersistentCapabilityEvidenceRepository(
        store = store,
        keyFactory = PersistentCapabilityEvidenceKeyFactory { provider, capability ->
            overrides[PersistentCapabilityEvidenceCoordinate(provider, capability)] ?: when (provider) {
                PrivilegedProvider.DIRECT_ROOT -> PersistentCapabilityEvidenceKeyExpectation.Unsupported
                else -> PersistentCapabilityEvidenceKeyExpectation.Known(key(provider, capability))
            }
        },
        recordedAtEpochMs = { now },
    )

    private fun record(
        provider: PrivilegedProvider,
        capability: PrivilegedCapability,
        runtime: String? = null,
        timestamp: Long = 1L,
    ): PersistentCapabilityEvidenceRecord = PersistentCapabilityEvidenceRecord(
        provider = provider,
        capability = capability,
        state = CapabilityQualificationState.VALIDATED,
        originalSource = CapabilityQualificationSource.CURRENT_ATTEMPT_PROBE,
        compatibilityKey = key(provider, capability, runtime = runtime),
        recordedAtEpochMs = timestamp,
    )

    private fun key(
        provider: PrivilegedProvider,
        capability: PrivilegedCapability,
        runtime: String? = null,
        backend: String = "backend-v1",
    ): PersistentCapabilityEvidenceKey = PersistentCapabilityEvidenceKey(
        provider = provider,
        capability = capability,
        androidApi = 33,
        androidBuildIdentityDigest = "a".repeat(64),
        warpnectVersionCode = 1,
        providerImplementationRevision = "provider-v1",
        providerRuntimeVersion = runtime,
        backendRevision = backend,
    )

    private fun matrix(
        cell: (PrivilegedProvider, PrivilegedCapability) -> CapabilityQualification,
    ): PrivilegedCapabilityMatrix = PrivilegedCapabilityMatrix.complete(cell)

    private fun usable(
        provider: PrivilegedProvider,
        capability: PrivilegedCapability,
        state: CapabilityQualificationState,
    ): CapabilityQualification = CapabilityQualification.usable(
        provider,
        capability,
        state,
        "live_usable",
        CapabilityQualificationProvenance(CapabilityQualificationSource.CURRENT_ATTEMPT_PROBE),
    )

    private fun unavailable(
        provider: PrivilegedProvider,
        capability: PrivilegedCapability,
        state: CapabilityQualificationState,
        reason: String = "live_unavailable",
    ): CapabilityQualification = CapabilityQualification.unavailable(
        provider,
        capability,
        state,
        reason,
        CapabilityQualificationProvenance(CapabilityQualificationSource.CURRENT_ATTEMPT_PROBE),
    )

    private fun runtimeState(
        directShellCandidate: Boolean,
        shizukuReady: Boolean = false,
    ): PrivilegedRuntimeStateSnapshot = PrivilegedRuntimeStateSnapshot(
        generation = 1L,
        observedAtMonotonicMs = 1L,
        directRoot = PrivilegedRuntimeAvailability(
            PrivilegedRuntimeKind.DIRECT_ROOT,
            PrivilegedRuntimeAvailabilityState.NOT_IMPLEMENTED,
            detail = "root_not_implemented",
        ),
        directShell = PrivilegedRuntimeAvailability(
            PrivilegedRuntimeKind.DIRECT_SHELL,
            PrivilegedRuntimeAvailabilityState.UNKNOWN,
            detail = "directshell_idle",
            candidateForHostAcquisition = directShellCandidate,
        ),
        shizuku = PrivilegedRuntimeAvailability(
            PrivilegedRuntimeKind.SHIZUKU,
            if (shizukuReady) PrivilegedRuntimeAvailabilityState.READY else PrivilegedRuntimeAvailabilityState.UNKNOWN,
            detail = "shizuku_test",
        ),
    )

    private fun idleDirectShellRuntime() = PrivilegedRuntimeSnapshot(
        kind = PrivilegedRuntimeKind.DIRECT_SHELL,
        state = PrivilegedRuntimeState.IDLE,
    )

    private class MemoryStore(initial: List<PersistentCapabilityEvidenceRecord> = emptyList()) :
        PersistentCapabilityEvidenceStore {
        private var payload: String? = initial
            .takeIf { it.isNotEmpty() }
            ?.let(PersistentCapabilityEvidenceCodec::encode)
        var replaceCalls = 0
            private set

        override fun load(): PersistentCapabilityEvidenceDecoded = PersistentCapabilityEvidenceCodec.decode(payload)

        override fun replace(records: List<PersistentCapabilityEvidenceRecord>): Boolean {
            replaceCalls += 1
            payload = records.takeIf { it.isNotEmpty() }?.let(PersistentCapabilityEvidenceCodec::encode)
            return true
        }

        fun records(): List<PersistentCapabilityEvidenceRecord> = load().records
    }
}

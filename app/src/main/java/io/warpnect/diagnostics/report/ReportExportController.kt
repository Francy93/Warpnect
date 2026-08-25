package io.warpnect.diagnostics.report

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

fun interface ReportDestination {
    fun openForWrite(): OutputStream?
}

enum class ReportExportPhase { Idle, Capturing, Prepared, ChoosingDestination, Writing, Succeeded, Failed }
enum class BenchmarkCaptureStatus { Idle, Capturing, Preparing, ReadyToExport, Failed }

data class ReportExportUiState(
    val phase: ReportExportPhase = ReportExportPhase.Idle,
    val benchmark: BenchmarkCaptureStatus = BenchmarkCaptureStatus.Idle,
    val suggestedFilename: String? = null,
    val message: String? = null,
)

/** Application-scoped bounded owner: benchmarks keep only their start snapshot/cursors, never a sampler. */
class ReportExportController(
    private val builder: DiagnosticReportBuilder,
    private val cacheDirectory: File,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: Clock = Clock.systemUTC(),
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val exportBusy = AtomicBoolean(false)
    private val benchmarkActive = AtomicBoolean(false)
    private val benchmarkLock = Any()
    private val _state = MutableStateFlow(ReportExportUiState())
    val state: StateFlow<ReportExportUiState> = _state.asStateFlow()

    @Volatile
    private var baseline: BenchmarkBaseline? = null

    private var benchmarkEpoch = 0L

    @Volatile
    private var prepared: File? = null

    fun prepareDiagnosticsSnapshot(selection: ReportSessionSelection?): Boolean = prepare("warpnect-diagnostics") {
        builder.captureSnapshot(selection)
    }

    fun startBenchmark(selection: ReportSessionSelection): Boolean {
        if (exportBusy.get()) return false
        val epoch = synchronized(benchmarkLock) {
            if (!benchmarkActive.compareAndSet(false, true)) return false
            ++benchmarkEpoch
        }
        _state.value = _state.value.copy(benchmark = BenchmarkCaptureStatus.Capturing, message = null)
        scope.launch {
            runCatching { builder.startBenchmark(selection) }.onSuccess { captured ->
                if (acceptStartedBenchmark(epoch, captured)) {
                    _state.value = _state.value.copy(benchmark = BenchmarkCaptureStatus.Capturing, message = null)
                }
            }.onFailure {
                failBenchmarkStart(epoch)
            }
        }
        return true
    }

    fun stopBenchmark(): Boolean {
        val captured = synchronized(benchmarkLock) {
            val activeBaseline = baseline ?: return false
            if (!exportBusy.compareAndSet(false, true)) return false
            baseline = null
            activeBaseline
        }
        _state.value = _state.value.copy(
            phase = ReportExportPhase.Capturing,
            benchmark = BenchmarkCaptureStatus.Preparing,
            message = null,
        )
        scope.launch {
            runCatching {
                builder.stopBenchmark(captured).also { report ->
                    prepareFile(report, "warpnect-benchmark")
                }
            }.onSuccess {
                finishBenchmarkStop(BenchmarkCaptureStatus.ReadyToExport)
            }.onFailure { failure ->
                finishBenchmarkStop(BenchmarkCaptureStatus.Failed)
                fail(failure, BenchmarkCaptureStatus.Failed)
            }
        }
        return true
    }

    fun cancelBenchmark() {
        if (exportBusy.get()) return
        synchronized(benchmarkLock) {
            if (!benchmarkActive.get()) return
            baseline = null
            benchmarkActive.set(false)
            ++benchmarkEpoch
        }
        val current = _state.value
        _state.value = current.copy(benchmark = BenchmarkCaptureStatus.Idle, message = null)
    }

    fun beginDestinationChoice(): String? {
        val filename = _state.value.suggestedFilename ?: return null
        _state.value = _state.value.copy(phase = ReportExportPhase.ChoosingDestination)
        return filename
    }

    fun destinationCancelled() {
        cleanupPrepared()
        exportBusy.set(false)
        _state.value = _state.value.copy(
            phase = ReportExportPhase.Idle,
            suggestedFilename = null,
            message = null,
        ).let { state ->
            if (benchmarkActive.get()) state else state.copy(benchmark = BenchmarkCaptureStatus.Idle)
        }
    }

    fun writePrepared(destination: ReportDestination): Boolean {
        val input = prepared ?: return false
        if (_state.value.phase != ReportExportPhase.ChoosingDestination) return false
        _state.value = _state.value.copy(phase = ReportExportPhase.Writing, message = null)
        scope.launch {
            runCatching {
                destination.openForWrite()?.use { output -> copyBounded(input, output) }
                    ?: throw DiagnosticReportFailure.DestinationWriteFailure
            }.onSuccess {
                cleanupPrepared()
                exportBusy.set(false)
                _state.value = _state.value.copy(
                    phase = ReportExportPhase.Succeeded,
                    benchmark = if (benchmarkActive.get()) _state.value.benchmark else BenchmarkCaptureStatus.Idle,
                    suggestedFilename = null,
                    message = "Exported",
                )
            }.onFailure { fail(it, _state.value.benchmark) }
        }
        return true
    }

    private fun prepare(prefix: String, build: () -> DiagnosticReport): Boolean {
        if (!exportBusy.compareAndSet(false, true)) return false
        _state.value = _state.value.copy(phase = ReportExportPhase.Capturing, message = null)
        scope.launch {
            runCatching {
                build().also { report -> prepareFile(report, prefix) }
            }
                .onFailure { fail(it, _state.value.benchmark) }
        }
        return true
    }

    private fun acceptStartedBenchmark(epoch: Long, captured: BenchmarkBaseline): Boolean =
        synchronized(benchmarkLock) {
            if (!benchmarkActive.get() || benchmarkEpoch != epoch) {
                false
            } else {
                baseline = captured
                true
            }
        }

    private fun failBenchmarkStart(epoch: Long) {
        val current = synchronized(benchmarkLock) {
            if (benchmarkEpoch != epoch) return
            baseline = null
            benchmarkActive.set(false)
            ++benchmarkEpoch
            true
        }
        if (current) {
            _state.value = _state.value.copy(
                benchmark = BenchmarkCaptureStatus.Failed,
                message = "Benchmark start failed",
            )
        }
    }

    private fun finishBenchmarkStop(status: BenchmarkCaptureStatus) {
        synchronized(benchmarkLock) {
            benchmarkActive.set(false)
            ++benchmarkEpoch
        }
        _state.value = _state.value.copy(benchmark = status)
    }

    private fun prepareFile(report: DiagnosticReport, prefix: String) {
        cleanupPrepared()
        val file = File.createTempFile("$prefix-", ".json", cacheDirectory)
        try {
            FileOutputStream(file).use { stream ->
                BoundedOutputStream(stream, MAX_REPORT_BYTES).use { bounded ->
                    OutputStreamWriter(bounded, StandardCharsets.UTF_8).use { writer ->
                        DiagnosticReportJsonWriter().write(report, writer)
                    }
                }
            }
            prepared = file
            _state.value = _state.value.copy(
                phase = ReportExportPhase.Prepared,
                suggestedFilename = "$prefix-${timestampLabel()}.json",
                message = "Report ready",
            )
        } catch (failure: Throwable) {
            file.delete()
            throw if (failure is DiagnosticReportFailure) failure else DiagnosticReportFailure.TemporaryStorageFailure
        }
    }

    private fun fail(failure: Throwable, benchmarkState: BenchmarkCaptureStatus) {
        cleanupPrepared()
        exportBusy.set(false)
        _state.value = _state.value.copy(
            phase = ReportExportPhase.Failed,
            benchmark = benchmarkState,
            suggestedFilename = null,
            message = when (failure) {
                DiagnosticReportFailure.ReportTooLarge -> "Report is too large"
                DiagnosticReportFailure.DestinationWriteFailure -> "Destination write failed"
                else -> "Report preparation failed"
            },
        )
    }

    private fun copyBounded(input: File, output: OutputStream) {
        BufferedInputStream(FileInputStream(input)).use { source ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
            }
            output.flush()
        }
    }

    private fun timestampLabel(): String = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        .withZone(java.time.ZoneOffset.UTC)
        .format(clock.instant())

    private fun cleanupPrepared() {
        prepared?.let { if (it.exists()) it.delete() }
        prepared = null
    }
    override fun close() {
        cleanupPrepared()
        synchronized(benchmarkLock) {
            baseline = null
            benchmarkActive.set(false)
            ++benchmarkEpoch
        }
        scope.cancel()
    }
}

private class BoundedOutputStream(private val delegate: OutputStream, private val maximum: Int) : OutputStream() {
    private var size = 0
    override fun write(value: Int) = write(byteArrayOf(value.toByte()))
    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        if (length > maximum - size) throw DiagnosticReportFailure.ReportTooLarge
        delegate.write(buffer, offset, length)
        size += length
    }
    override fun flush() = delegate.flush()
    override fun close() = delegate.close()
}

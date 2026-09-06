package io.warpnect.debug.video

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface as ComposeSurface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.warpnect.platform.video.render.WarpnectVideoSurfaceView
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Debug-only visual check for the exact RFC-002I fixture on the normal Compose/SurfaceView stack.
 * It never reads decoded pixels and never touches Session media, networking, or capture.
 */
class ClientFixturePresentationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FixturePresentationScreen() }
    }
}

@Composable
private fun FixturePresentationScreen() {
    val context = LocalContext.current
    val surfaceView = remember { WarpnectVideoSurfaceView(context) }
    var status by remember { mutableStateOf("Preparing fixture") }
    val playback = remember { FixturePlayback(context as ComponentActivity) { status = it } }

    DisposableEffect(surfaceView) {
        val callback = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                playback.start(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                playback.stop()
            }
        }
        surfaceView.holder.addCallback(callback)
        if (surfaceView.holder.surface.isValid) playback.start(surfaceView.holder.surface)
        onDispose {
            surfaceView.holder.removeCallback(callback)
            playback.stop()
        }
    }

    MaterialTheme {
        ComposeSurface {
            Column(Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { surfaceView },
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f),
                )
                Text(
                    text = "Warpnect fixture presentation: $status",
                    modifier = Modifier.padding(24.dp),
                )
            }
        }
    }
}

private class FixturePlayback(
    private val activity: ComponentActivity,
    private val onStatus: (String) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    private var queuedInputs = 0
    private var releasedOutputs = 0

    fun start(surface: Surface) {
        if (!running.compareAndSet(false, true)) return
        worker = Thread({ play(surface) }, "WarpnectFixturePresentation").apply { start() }
    }

    fun stop() {
        running.set(false)
        worker?.interrupt()
        worker = null
    }

    private fun play(surface: Surface) {
        val fixture = runCatching { Fixture.load(activity) }.getOrElse {
            publish("fixture unavailable")
            Log.e(TAG, "FIXTURE_PRESENTATION_LOAD_FAILED", it)
            running.set(false)
            return
        }
        val codec = runCatching { MediaCodec.createDecoderByType(MIME) }.getOrElse {
            publish("decoder creation failed")
            Log.e(TAG, "FIXTURE_PRESENTATION_DECODER_CREATE_FAILED", it)
            running.set(false)
            return
        }
        try {
            codec.configure(fixture.format(), surface, null, 0)
            codec.start()
            queuedInputs = 0
            releasedOutputs = 0
            publish("playing with ${codec.name}")
            Log.i(TAG, "FIXTURE_PRESENTATION_STARTED codec=${codec.name}")
            var cycle = 0L
            while (running.get()) {
                playCycle(codec, fixture, cycle++)
            }
        } catch (throwable: Throwable) {
            publish("decoder failed")
            Log.e(TAG, "FIXTURE_PRESENTATION_FAILED", throwable)
        } finally {
            runCatching { codec.stop() }
            codec.release()
            running.set(false)
        }
    }

    private fun playCycle(codec: MediaCodec, fixture: Fixture, cycle: Long) {
        val startedAtMs = SystemClock.elapsedRealtime()
        fixture.accessUnits.forEachIndexed { index, unit ->
            if (!running.get()) return
            val inputIndex = awaitInput(codec) ?: return
            codec.getInputBuffer(inputIndex)?.apply {
                clear()
                put(unit.bytes)
            } ?: error("Decoder input buffer was unavailable")
            codec.queueInputBuffer(
                inputIndex,
                0,
                unit.bytes.size,
                unit.presentationTimeUs + cycle * CYCLE_DURATION_US,
                unit.flags,
            )
            queuedInputs += 1
            if (queuedInputs <= DEBUG_FRAME_COUNT) {
                Log.i(TAG, "FIXTURE_PRESENTATION_INPUT_QUEUED count=$queuedInputs pts_us=${unit.presentationTimeUs}")
            }
            drain(codec)
            val targetMs = startedAtMs + (index + 1L) * 1_000L / FRAME_RATE
            val delayMs = targetMs - SystemClock.elapsedRealtime()
            if (delayMs > 0L) Thread.sleep(delayMs)
        }
        drain(codec)
    }

    private fun awaitInput(codec: MediaCodec): Int? {
        while (running.get()) {
            val inputIndex = codec.dequeueInputBuffer(INPUT_WAIT_US)
            if (inputIndex >= 0) return inputIndex
            drain(codec)
        }
        return null
    }

    private fun drain(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running.get()) {
            when (val outputIndex = codec.dequeueOutputBuffer(info, 0L)) {
                MediaCodec.INFO_TRY_AGAIN_LATER,
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED,
                -> return

                else -> if (outputIndex >= 0) {
                    codec.releaseOutputBuffer(outputIndex, info.size > 0)
                    releasedOutputs += 1
                    if (releasedOutputs <= DEBUG_FRAME_COUNT) {
                        Log.i(
                            TAG,
                            "FIXTURE_PRESENTATION_OUTPUT_RELEASED count=$releasedOutputs " +
                                "pts_us=${info.presentationTimeUs} render=${info.size > 0}",
                        )
                    }
                }
            }
        }
    }

    private fun publish(value: String) {
        activity.runOnUiThread { onStatus(value) }
    }

    private data class Fixture(
        val codecSpecificData: List<ByteArray>,
        val accessUnits: List<AccessUnit>,
    ) {
        data class AccessUnit(
            val bytes: ByteArray,
            val presentationTimeUs: Long,
            val flags: Int,
        )

        fun format(): MediaFormat = MediaFormat.createVideoFormat(MIME, WIDTH, HEIGHT).apply {
            codecSpecificData.forEachIndexed { index, bytes ->
                setByteBuffer("csd-$index", java.nio.ByteBuffer.wrap(bytes))
            }
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, accessUnits.maxOf { it.bytes.size })
        }

        companion object {
            fun load(activity: ComponentActivity): Fixture {
                val bytes = activity.assets.open(ASSET_PATH).use { it.readBytes() }
                check(sha256(bytes) == EXPECTED_SHA256) { "Fixture digest mismatch" }
                return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                    check(input.readInt() == MAGIC)
                    check(input.readInt() == VERSION)
                    check(input.readInt() == WIDTH)
                    check(input.readInt() == HEIGHT)
                    check(input.readInt() == FRAME_RATE)
                    input.readInt()
                    input.readInt()
                    val codecSpecificData = List(input.readInt().also { check(it in 1..4) }) {
                        val size = input.readInt().also { check(it in 1..MAX_ENTRY_BYTES) }
                        ByteArray(size).also(input::readFully)
                    }
                    val accessUnits = List(input.readInt().also { check(it == ACCESS_UNIT_COUNT) }) {
                        val bytesPerUnit = input.readInt().also { size -> check(size in 1..MAX_ENTRY_BYTES) }
                        val presentationTimeUs = input.readLong()
                        val flags = input.readInt()
                        AccessUnit(ByteArray(bytesPerUnit).also(input::readFully), presentationTimeUs, flags)
                    }
                    check(input.available() == 0)
                    Fixture(codecSpecificData, accessUnits)
                }
            }

            private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }
        }
    }

    private companion object {
        const val TAG = "WarpnectFixturePresentation"
        const val MIME = "video/avc"
        const val WIDTH = 1280
        const val HEIGHT = 720
        const val FRAME_RATE = 60
        const val ACCESS_UNIT_COUNT = 360
        const val CYCLE_DURATION_US = 6_000_000L
        const val INPUT_WAIT_US = 10_000L
        const val DEBUG_FRAME_COUNT = 3
        const val MAGIC = 0x574E4932
        const val VERSION = 2
        const val MAX_ENTRY_BYTES = 1_048_576
        const val ASSET_PATH = "video/rfc002i-avc-720p60-full-v2.fixture"
        const val EXPECTED_SHA256 = "554f1e7ad82f5dfde40bf4d276f8f76c0170d41908c3b8554c652f55b17d86e0"
    }
}

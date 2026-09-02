package io.warpnect.platform.audio.capture.privileged

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import io.warpnect.audio.capture.AudioCaptureCapabilities
import io.warpnect.audio.capture.AudioCaptureError
import io.warpnect.audio.capture.AudioCaptureRequest
import io.warpnect.audio.capture.AudioCaptureSource
import io.warpnect.audio.capture.AudioPcmEncoding
import io.warpnect.audio.capture.AudioTimestampAnchor
import io.warpnect.audio.capture.AudioTimestampQuality
import java.lang.reflect.Method
import java.nio.ByteBuffer

internal class ReflectivePrivilegedAudioPolicyCaptureApi : PrivilegedAudioPolicyCaptureApi {
    private var audioManager: AudioManager? = null
    private var audioPolicy: Any? = null
    private var audioMix: Any? = null
    private var audioRecord: AudioRecord? = null
    private val timestamp = AudioTimestamp()

    override fun queryCapabilities(request: AudioCaptureRequest): AudioCaptureCapabilities {
        val context = currentApplicationContext()
        // This implementation has no MediaProjection token. Resolving hidden classes alone does
        // not authorize an AudioPolicy sink for Warpnect's normal application attribution.
        val qualification = AudioPolicyCapabilityQualification(
            contextAvailable = context != null,
            hiddenApiAvailable = hiddenAudioPolicyClassesAvailable(),
            routingPermissionGranted = context?.hasModifyAudioRoutingPermission() == true,
        )
        val available = qualification.isAvailable
        return AudioCaptureCapabilities(
            source = AudioCaptureSource.SystemAudio,
            available = available,
            supportedSampleRatesHz = listOf(
                request.preferredSampleRateHz ?: AudioCaptureRequest.DEFAULT_SAMPLE_RATE_HZ,
            ),
            selectedSampleRateHz = request.preferredSampleRateHz ?: AudioCaptureRequest.DEFAULT_SAMPLE_RATE_HZ,
            channelCount = request.channelCount ?: 2,
            encoding = AudioPcmEncoding.Pcm16,
            privilegedBackendAvailable = available,
            timestampSupport = if (available) {
                AudioTimestampQuality.AudioRecordTimestamp
            } else {
                AudioTimestampQuality.Unavailable
            },
            lastError = qualification.error,
        )
    }

    override fun prepareSystemAudioCapture(
        prepareRequest: PrivilegedSystemAudioPrepareRequest,
    ): PrivilegedAudioPolicyPrepareResult {
        close()
        if (prepareRequest.request.targetUid != null) {
            return PrivilegedAudioPolicyPrepareResult(AudioCaptureError.AudioPolicyUnavailable)
        }
        val context = currentApplicationContext()
            ?: return PrivilegedAudioPolicyPrepareResult(AudioCaptureError.PrivilegedServiceUnavailable)
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return PrivilegedAudioPolicyPrepareResult(AudioCaptureError.AudioPolicyUnavailable)
        val prepared = tryCreateAudioPolicy(context, prepareRequest.format)
            ?: return PrivilegedAudioPolicyPrepareResult(AudioCaptureError.AudioMixCreationFailed)
        val registerError = registerPolicy(manager, prepared.policy)
        if (registerError != AudioCaptureError.None) {
            return PrivilegedAudioPolicyPrepareResult(registerError)
        }
        val record = createAudioRecordSink(prepared.policy, prepared.mix)
        if (record == null) {
            unregisterPolicy(manager, prepared.policy)
            return PrivilegedAudioPolicyPrepareResult(AudioCaptureError.AudioRecordCreationFailed)
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            unregisterPolicy(manager, prepared.policy)
            return PrivilegedAudioPolicyPrepareResult(AudioCaptureError.AudioRecordUninitialized)
        }
        audioManager = manager
        audioPolicy = prepared.policy
        audioMix = prepared.mix
        audioRecord = record
        return PrivilegedAudioPolicyPrepareResult(
            error = AudioCaptureError.None,
            actualBufferSizeFrames = record.bufferSizeInFrames,
        )
    }

    override fun startRecording(): AudioCaptureError {
        val record = audioRecord ?: return AudioCaptureError.NotPrepared
        return try {
            record.startRecording()
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                AudioCaptureError.None
            } else {
                AudioCaptureError.AudioRecordStartFailed
            }
        } catch (_: RuntimeException) {
            AudioCaptureError.AudioRecordStartFailed
        }
    }

    override fun read(target: ByteBuffer, sizeBytes: Int): Int {
        val record = audioRecord ?: return AudioRecord.ERROR_INVALID_OPERATION
        return try {
            record.read(target, sizeBytes, AudioRecord.READ_BLOCKING)
        } catch (_: RuntimeException) {
            AudioRecord.ERROR
        }
    }

    override fun latestTimestampAnchor(): AudioTimestampAnchor? {
        val record = audioRecord ?: return null
        val result = try {
            record.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC)
        } catch (_: RuntimeException) {
            AudioRecord.ERROR
        }
        return if (result == AudioRecord.SUCCESS && timestamp.framePosition >= 0L) {
            AudioTimestampAnchor(timestamp.framePosition, timestamp.nanoTime)
        } else {
            null
        }
    }

    override fun stopSystemAudioCapture(): AudioCaptureError {
        val record = audioRecord
        runCatching { record?.stop() }
        runCatching { record?.release() }
        val policy = audioPolicy
        val manager = audioManager
        if (policy != null && manager != null) {
            unregisterPolicy(manager, policy)
        }
        audioRecord = null
        audioPolicy = null
        audioMix = null
        audioManager = null
        return AudioCaptureError.None
    }

    override fun close() {
        stopSystemAudioCapture()
    }

    private fun tryCreateAudioPolicy(
        context: Context,
        format: io.warpnect.audio.capture.AudioCaptureFormat,
    ): PreparedPolicy? = try {
        val ruleClass = Class.forName("android.media.audiopolicy.AudioMixingRule")
        val ruleBuilderClass = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")
        val mixClass = Class.forName("android.media.audiopolicy.AudioMix")
        val mixBuilderClass = Class.forName("android.media.audiopolicy.AudioMix\$Builder")
        val policyBuilderClass = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder")

        val ruleBuilder = ruleBuilderClass.getConstructor().newInstance()
        setTargetMixRoleIfAvailable(ruleBuilderClass, ruleBuilder)
        val usageRule = ruleClass.getField("RULE_MATCH_ATTRIBUTE_USAGE").getInt(null)
        for (usage in SYSTEM_AUDIO_USAGES) {
            val attributes = AudioAttributes.Builder().setUsage(usage).build()
            invokeBuilder(ruleBuilderClass, ruleBuilder, "addRule", attributes, usageRule)
        }
        val rule = ruleBuilderClass.getMethod("build").invoke(ruleBuilder)

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(format.sampleRateHz)
            .setChannelMask(outputChannelMask(format.channelCount))
            .build()
        val routeFlags = mixClass.getField("ROUTE_FLAG_LOOP_BACK").getInt(null) or
            mixClass.getField("ROUTE_FLAG_RENDER").getInt(null)
        val mixBuilder = mixBuilderClass.getConstructor(ruleClass).newInstance(rule)
        invokeBuilder(mixBuilderClass, mixBuilder, "setRouteFlags", routeFlags)
        invokeBuilder(mixBuilderClass, mixBuilder, "setFormat", audioFormat)
        val mix = mixBuilderClass.getMethod("build").invoke(mixBuilder) ?: return null

        val policyBuilder = policyBuilderClass.getConstructor(Context::class.java).newInstance(context)
        invokeBuilder(policyBuilderClass, policyBuilder, "addMix", mix)
        val policy = policyBuilderClass.getMethod("build").invoke(policyBuilder) ?: return null
        PreparedPolicy(policy = policy, mix = mix)
    } catch (_: ReflectiveOperationException) {
        null
    } catch (_: RuntimeException) {
        null
    }

    private fun registerPolicy(manager: AudioManager, policy: Any): AudioCaptureError {
        val method = findMethod(manager.javaClass, "registerAudioPolicy", 1)
            ?: return AudioCaptureError.AudioPolicyRegistrationFailed
        val result = try {
            method.invoke(manager, policy)
        } catch (_: ReflectiveOperationException) {
            return AudioCaptureError.AudioPolicyRegistrationFailed
        } catch (_: RuntimeException) {
            return AudioCaptureError.AudioPolicyRegistrationFailed
        }
        return if (result !is Int || result == 0) {
            AudioCaptureError.None
        } else {
            AudioCaptureError.AudioPolicyRegistrationFailed
        }
    }

    private fun unregisterPolicy(manager: AudioManager, policy: Any) {
        val method = findMethod(manager.javaClass, "unregisterAudioPolicy", 1) ?: return
        runCatching { method.invoke(manager, policy) }
    }

    private fun createAudioRecordSink(policy: Any, mix: Any): AudioRecord? {
        val method = findMethod(policy.javaClass, "createAudioRecordSink", 1) ?: return null
        return try {
            method.invoke(policy, mix) as? AudioRecord
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun invokeBuilder(builderClass: Class<*>, target: Any, name: String, vararg args: Any) {
        val method = builderClass.methods.first {
            it.name == name && it.parameterTypes.size == args.size
        }
        method.invoke(target, *args)
    }

    private fun setTargetMixRoleIfAvailable(builderClass: Class<*>, builder: Any) {
        val method = builderClass.methods.firstOrNull {
            it.name == "setTargetMixRole" && it.parameterTypes.size == 1
        } ?: return
        val role = runCatching {
            Class.forName("android.media.audiopolicy.AudioMixingRule")
                .getField("MIX_ROLE_PLAYERS")
                .getInt(null)
        }.getOrNull() ?: return
        runCatching { method.invoke(builder, role) }
    }

    private fun findMethod(type: Class<*>, name: String, parameterCount: Int): Method? =
        type.methods.firstOrNull { it.name == name && it.parameterTypes.size == parameterCount }

    private fun hiddenAudioPolicyClassesAvailable(): Boolean = try {
        Class.forName("android.media.audiopolicy.AudioPolicy")
        Class.forName("android.media.audiopolicy.AudioMix")
        Class.forName("android.media.audiopolicy.AudioMixingRule")
        true
    } catch (_: ReflectiveOperationException) {
        false
    }

    private fun currentApplicationContext(): Context? = try {
        Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication")
            .invoke(null) as? Context
    } catch (_: ReflectiveOperationException) {
        null
    } catch (_: RuntimeException) {
        null
    }

    private fun Context.hasModifyAudioRoutingPermission(): Boolean =
        packageManager.checkPermission(MODIFY_AUDIO_ROUTING_PERMISSION, packageName) ==
            PackageManager.PERMISSION_GRANTED

    private fun outputChannelMask(channelCount: Int): Int = when (channelCount) {
        1 -> AudioFormat.CHANNEL_OUT_MONO
        2 -> AudioFormat.CHANNEL_OUT_STEREO
        else -> AudioFormat.CHANNEL_OUT_STEREO
    }

    private data class PreparedPolicy(
        val policy: Any,
        val mix: Any,
    )

    private companion object {
        const val MODIFY_AUDIO_ROUTING_PERMISSION = "android.permission.MODIFY_AUDIO_ROUTING"

        val SYSTEM_AUDIO_USAGES = intArrayOf(
            AudioAttributes.USAGE_GAME,
            AudioAttributes.USAGE_MEDIA,
            AudioAttributes.USAGE_UNKNOWN,
        )
    }
}

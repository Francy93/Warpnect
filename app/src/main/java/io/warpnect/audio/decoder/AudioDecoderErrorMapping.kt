package io.warpnect.audio.decoder

fun audioDecoderErrorFromCode(code: Int): AudioDecoderError = AudioDecoderError.fromNativeCode(code)

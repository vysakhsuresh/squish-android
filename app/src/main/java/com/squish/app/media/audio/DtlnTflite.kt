package com.squish.app.media.audio

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * DTLN's two TFLite models (assets/dtln, MIT) as [VoiceSplit.Network]: the
 * first maps a block's magnitude spectrum to a mask, the second refines the
 * masked block in the time domain. Each carries its LSTM states from block to
 * block, as inputs and outputs of its own.
 *
 * Which input is which is read off the shapes rather than assumed from an
 * order - the repository's own script indexes them, and a converter that
 * numbered them the other way would put the states where the spectrum goes.
 */
class DtlnTflite private constructor(
    private val first: Interpreter,
    private val second: Interpreter
) : VoiceSplit.Network, Closeable {

    private class Stage(val interpreter: Interpreter, signalSize: Int) {
        val signalIn: Int
        val statesIn: Int
        val signalOut: Int
        val statesOut: Int
        val statesBytes: Int
        val input = direct(signalSize * 4)
        val output = direct(signalSize * 4)
        var states: ByteBuffer
        var statesNext: ByteBuffer

        init {
            fun bytes(shape: IntArray) = shape.fold(4) { a, b -> a * b }
            val ins = (0 until interpreter.inputTensorCount).map { interpreter.getInputTensor(it).shape() }
            val outs = (0 until interpreter.outputTensorCount).map { interpreter.getOutputTensor(it).shape() }
            signalIn = ins.indexOfFirst { it.last() == signalSize && it.size == 3 }
            statesIn = ins.indexOfFirst { it.size == 4 }
            signalOut = outs.indexOfFirst { it.last() == signalSize && it.size == 3 }
            statesOut = outs.indexOfFirst { it.size == 4 }
            check(signalIn >= 0 && statesIn >= 0 && signalOut >= 0 && statesOut >= 0) {
                "DTLN stage shapes not as expected: in ${ins.map { it.toList() }}, out ${outs.map { it.toList() }}"
            }
            statesBytes = bytes(ins[statesIn])
            states = direct(statesBytes)
            statesNext = direct(statesBytes)
        }

        fun run(signal: FloatArray): FloatArray {
            input.clear(); input.asFloatBuffer().put(signal)
            output.clear(); statesNext.clear(); states.rewind()
            val inputs = arrayOfNulls<Any>(2)
            inputs[signalIn] = input
            inputs[statesIn] = states
            interpreter.runForMultipleInputsOutputs(inputs, mapOf(signalOut to output, statesOut to statesNext))
            // The states this block left are the next block's.
            val held = states; states = statesNext; statesNext = held
            output.rewind()
            val out = FloatArray(signal.size)
            output.asFloatBuffer().get(out)
            return out
        }

        fun reset() {
            states = direct(statesBytes)
            statesNext = direct(statesBytes)
        }
    }

    private val mask = Stage(first, VoiceSplit.BINS)
    private val refine = Stage(second, VoiceSplit.BLOCK)

    override fun mask(magnitude: FloatArray): FloatArray = mask.run(magnitude)
    override fun refine(block: FloatArray): FloatArray = refine.run(block)
    override fun reset() { mask.reset(); refine.reset() }

    override fun close() {
        runCatching { first.close() }
        runCatching { second.close() }
    }

    companion object {
        /** The two models from the app's own assets, or null when the runtime will not load them. */
        fun open(context: Context): DtlnTflite? = runCatching {
            // One thread: the two models are small enough that handing each 8 ms block
            // between threads costs more than the block itself.
            val options = Interpreter.Options().setNumThreads(1)
            DtlnTflite(Interpreter(asset(context, "dtln/model_1.tflite"), options), Interpreter(asset(context, "dtln/model_2.tflite"), options))
        }.onFailure { android.util.Log.w("SquishVoiceSplit", "DTLN would not load", it) }.getOrNull()

        private fun asset(context: Context, name: String): MappedByteBuffer =
            context.assets.openFd(name).use { fd ->
                FileInputStream(fd.fileDescriptor).channel.use { ch ->
                    ch.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                }
            }

        private fun direct(bytes: Int): ByteBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
    }
}

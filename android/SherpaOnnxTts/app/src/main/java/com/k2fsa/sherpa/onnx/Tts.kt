// Copyright (c)  2023  Xiaomi Corporation
package com.k2fsa.sherpa.onnx

import android.content.res.AssetManager
import android.util.Log
import java.io.File

data class OfflineTtsVitsModelConfig(
    var model: String,
    var lexicon: String = "",
    var tokens: String,
    var dataDir: String = "",
    var dictDir: String = "",
    var noiseScale: Float = 0.667f,
    var noiseScaleW: Float = 0.8f,
    var lengthScale: Float = 1.0f,
)

data class OfflineTtsModelConfig(
    var vits: OfflineTtsVitsModelConfig,
    var numThreads: Int = 1,
    var debug: Boolean = false,
    var provider: String = "nnapi", //"cpu",
)

data class OfflineTtsConfig(
    var model: OfflineTtsModelConfig,
    var ruleFsts: String = "",
    var ruleFars: String = "",
    var maxNumSentences: Int = 2,
)

class GeneratedAudio(
    val samples: FloatArray,
    val sampleRate: Int,
) {
    fun save(filename: String) =
        saveImpl(filename = filename, samples = samples, sampleRate = sampleRate)

    private external fun saveImpl(
        filename: String,
        samples: FloatArray,
        sampleRate: Int
    ): Boolean
}

class OfflineTts(
    assetManager: AssetManager? = null,
    var config: OfflineTtsConfig,
) {
    private var ptr: Long
    private var token2id: Map<Char, Long>

    init {
        ptr = if (assetManager != null) {
            newFromAsset(assetManager, config)
        } else {
            newFromFile(config)
        }
        token2id = getTokenMap(config.model.vits.tokens)
    }

    fun getTokenMap(tokenFile: String): Map<Char, Long> {
        val tokenMap = mutableMapOf<Char, Long>()

        //read from tokenFile entries with format "<char> <id>"
        File(tokenFile).forEachLine {
            try {
                tokenMap[it[0]] = it.split(" ")[1].toLong()
            }
            catch(e: Exception){
                Log.e("SherpaOnnx", "Error parsing token file: $e")
            }
        }
        return tokenMap
    }
    fun sampleRate() = getSampleRate(ptr)

    fun numSpeakers() = getNumSpeakers(ptr)

    fun generate(
        text: String,
        sid: Int = 0,
        speed: Float = 1.0f
    ): GeneratedAudio {
        // val objArray = generateImpl(ptr, text = text, sid = sid, speed = speed)

        val normText = normalizeText(text)
        Log.d("SherpaOnnx", "normText: $normText")
        val tokenIds = convertTextToTokenIds(normText)
        Log.d("SherpaOnnx", "tokenIds: $tokenIds")


        return GeneratedAudio(
            samples = floatArrayOf(0.0f) as FloatArray,
            sampleRate = 1 as Int
        )
    }

    fun generateWithCallback(
        text: String,
        sid: Int = 0,
        speed: Float = 1.0f,
        callback: (samples: FloatArray) -> Unit
    ): GeneratedAudio {
        val objArray = generateWithCallbackImpl(
            ptr,
            text = text,
            sid = sid,
            speed = speed,
            callback = callback
        )
        return GeneratedAudio(
            samples = objArray[0] as FloatArray,
            sampleRate = objArray[1] as Int
        )
    }

//    fun generate(
//        text: String,
//        sid: Int = 0,
//        speed: Float = 1.0f
//    ): GeneratedAudio {
//        val objArray = generateImpl(ptr, text = text, sid = sid, speed = speed)
//        return GeneratedAudio(
//            samples = objArray[0] as FloatArray,
//            sampleRate = objArray[1] as Int
//        )
//    }
//
//    fun generateWithCallback(
//        text: String,
//        sid: Int = 0,
//        speed: Float = 1.0f,
//        callback: (samples: FloatArray) -> Unit
//    ): GeneratedAudio {
//        val objArray = generateWithCallbackImpl(
//            ptr,
//            text = text,
//            sid = sid,
//            speed = speed,
//            callback = callback
//        )
//        return GeneratedAudio(
//            samples = objArray[0] as FloatArray,
//            sampleRate = objArray[1] as Int
//        )
//    }

    fun allocate(assetManager: AssetManager? = null) {
        if (ptr == 0L) {
            ptr = if (assetManager != null) {
                newFromAsset(assetManager, config)
            } else {
                newFromFile(config)
            }
        }
    }

    fun free() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0
        }
    }

    protected fun finalize() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0
        }
    }

    fun release() = finalize()

    private external fun newFromAsset(
        assetManager: AssetManager,
        config: OfflineTtsConfig,
    ): Long

    private external fun newFromFile(
        config: OfflineTtsConfig,
    ): Long

    private external fun delete(ptr: Long)
    private external fun getSampleRate(ptr: Long): Int
    private external fun getNumSpeakers(ptr: Long): Int

    private external fun normalizeText( text: String): String

    // The returned array has two entries:
    //  - the first entry is an 1-D float array containing audio samples.
    //    Each sample is normalized to the range [-1, 1]
    //  - the second entry is the sample rate
    private external fun generateImpl(
        ptr: Long,
        text: String,
        sid: Int = 0,
        speed: Float = 1.0f
    ): Array<Any>

    private external fun generateWithCallbackImpl(
        ptr: Long,
        text: String,
        sid: Int = 0,
        speed: Float = 1.0f,
        callback: (samples: FloatArray) -> Unit
    ): Array<Any>

    companion object {
        init {
            System.loadLibrary("sherpa-onnx-jni")
        }
    }

    /**
     * Converts a given text to a list of lists of token IDs.
     *
     * @param text The input text to be converted.
     * @return A list of lists of token IDs. Each inner list represents a sentence in the text.
     */
    fun convertTextToTokenIds(
        text: String
    ): List<List<Long>> {
        val useEosBos = false //metaData.useEosBos
        val bosId = 0L //metaData.bosId
        val eosId = 0L //metaData.eosId
        val blankId = 0L //metaData.blankId
        val addBlank = false //metaData.addBlank

        val convertedText = text.lowercase().replace("\\s+".toRegex(), "")

        val thisSentence = mutableListOf<Long>()
        val ans = mutableListOf<MutableList<Long>>()

        if (addBlank) {
            if (useEosBos) {
                thisSentence.add(bosId)
            }
            thisSentence.add(blankId)

            for (c in convertedText) {
                if (token2id.containsKey(c)) {
                    thisSentence.add(token2id[c]!!)
                    thisSentence.add(blankId)
                } else {
                    Log.e("SherpaOnnx", "Skip unknown character. Unicode codepoint: \\U+%04x. $c".format(c.code))

                }

                if (c == '.' || c == ':' || c == '?' || c == '!') {
                    // end of a sentence
                    if (useEosBos) {
                        thisSentence.add(eosId)
                    }

                    ans.add(thisSentence.toMutableList())

                    // re-initialize thisSentence
                    if (useEosBos) {
                        thisSentence.add(bosId)
                    }
                    thisSentence.add(blankId)
                }
            }

            if (useEosBos) {
                thisSentence.add(eosId)
            }

            if (thisSentence.size > 1 + (if(useEosBos) 1 else 0) ) {
                ans.add(thisSentence.toMutableList())
            }
        } else {
            // not adding blank
            if (useEosBos) {
                thisSentence.add(bosId)
            }

            for (c in convertedText) {
                if (token2id.containsKey(c)) {
                    thisSentence.add(token2id[c]!!)
                }

                if (c == '.' || c == ':' || c == '?' || c == '!') {
                    // end of a sentence
                    if (useEosBos) {
                        thisSentence.add(eosId)
                    }

                    ans.add(thisSentence.toMutableList())

                    // re-initialize thisSentence
                    if (useEosBos) {
                        thisSentence.add(bosId)
                    }
                }
            }

            if (thisSentence.size > 1) {
                ans.add(thisSentence.toMutableList())
            }
        }

        return ans
    }

}

// please refer to
// https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/index.html
// to download models
fun getOfflineTtsConfig(
    modelDir: String,
    modelName: String,
    lexicon: String,
    dataDir: String,
    dictDir: String,
    ruleFsts: String,
    ruleFars: String
): OfflineTtsConfig {
    return OfflineTtsConfig(
        model = OfflineTtsModelConfig(
            vits = OfflineTtsVitsModelConfig(
                model = "$modelDir/$modelName",
                lexicon = "$modelDir/$lexicon",
                tokens = "$modelDir/tokens.txt",
                dataDir = dataDir,
                dictDir = dictDir,
            ),
            numThreads = 4,
            debug = false,
            provider = "nnapi", //"cpu",
        ),
        ruleFsts = ruleFsts,
        ruleFars = ruleFars,
    )
}


package d.d.watchrecorder.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.*
import android.os.StrictMode.ThreadPolicy
import android.util.Log
import android.widget.Toast
import com.theeasiestway.opus.Constants
import com.theeasiestway.opus.Opus
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder


class VoiceService : Service() {
    private val TAG = "VoiceService"

    private val WHAT_VOICE_DATA_RECEIVED = 0

    private lateinit var voiceMessenger: Messenger

    private lateinit var audioTrack: AudioTrack
    private lateinit var opus: Opus

    private var fileOutStream: RandomAccessFile? = null
    private var rawFileOutStream: FileOutputStream? = null
    private var fileOut: File? = null
    private var dataLength = 0

    private val socket = DatagramSocket()


    inner class IncomingHandler(
        context: Context,
        private val applicationContext: Context = context.applicationContext
    ) : Handler() {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                WHAT_VOICE_DATA_RECEIVED -> {
                    handleVoiceData(msg)
                }
                else -> super.handleMessage(msg)
            }
        }
    }

    fun handleVoiceData(msg: Message) {
        val data = msg.data
        val voiceData = data.getByteArray("VOICE_DATA")

        fun ByteArray.toHex(): String = joinToString(separator = " ") { eachByte -> "0x%02x".format(eachByte) }

        Log.d(TAG, "handleVoiceData: size: %d".format(voiceData?.size))

        rawFileOutStream?.write(voiceData)

        val decoded = voiceData?.let {
            opus.decode(voiceData, Constants.FrameSize._960())
        }

        decoded?.let {
            fileOutStream?.write(decoded)
            dataLength += decoded.size
            audioTrack.write(decoded, 0, decoded.size)

            try {
                val packet = DatagramPacket(voiceData, voiceData.size, InetAddress.getByName("192.168.0.107"), 9999)
                socket.send(packet)
                Log.d(TAG, "handleVoiceData: sent.")
            }catch (e: Exception){
                e.printStackTrace()
            }
        }
    }

    /**
     * When binding to the service, we return an interface to our messenger
     * for sending messages to the service.
     */
    override fun onBind(intent: Intent): IBinder? {
        Log.d(TAG, "onBind: ")
        voiceMessenger = Messenger(IncomingHandler(this))
        return voiceMessenger.binder
    }

    private fun wavHeader(): ByteArray {
        val buffer = ByteBuffer.allocate(44)
        buffer.order(ByteOrder.LITTLE_ENDIAN)

        val sampleRate = 48000 * 2
        val channels = 1

        buffer.put("RIFF".toByteArray())
        buffer.putInt(0) // file size, changed at the end
        buffer.put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16)
        buffer.putShort(1) // PCM encoding
        buffer.putShort(channels.toShort()) // one channel
        buffer.putInt(sampleRate) // double sample rate for some reason
        buffer.putInt((sampleRate * 8 * channels) / 8)
        buffer.putShort(((8 * channels) / 8).toShort())
        buffer.putShort(8) // bits per sample
        buffer.put("data".toByteArray())
        buffer.putInt(0) // data section size, changed later

        return buffer.array()
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate: ")

        val policy = ThreadPolicy.Builder().permitAll().build()
        StrictMode.setThreadPolicy(policy)


        val sampleRate = 48000
        val encoding = AudioFormat.ENCODING_PCM_8BIT

        val bufferSize = AudioTrack.getMinBufferSize(sampleRate / 2, AudioFormat.CHANNEL_OUT_MONO, encoding)

        Toast.makeText(this, "receiving audio...", Toast.LENGTH_SHORT).show()

        audioTrack = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(sampleRate * 2)
                .setEncoding(encoding)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            bufferSize,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )

        audioTrack.play()

        opus = Opus()
        opus.decoderInit(Constants.SampleRate._48000(), Constants.Channels.mono())

        val outputFileDir = getExternalFilesDir("Recordings")
        if (outputFileDir != null) {
            if(!outputFileDir.exists()){
                outputFileDir.mkdir()
            }
            fileOut = File(outputFileDir, "%d.wav.recording".format(System.currentTimeMillis()))
            fileOutStream = RandomAccessFile(fileOut, "rw")

            fileOutStream?.write(wavHeader())

            val rawFileOut = File(outputFileDir, "%d.raw".format(System.currentTimeMillis()))
            rawFileOutStream = FileOutputStream(rawFileOut)
        }

        dataLength = 0
    }

    private fun finishFile(){
        fileOutStream?.seek(4)
        fileOutStream?.write(
            ByteBuffer.allocate(4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(dataLength + 36)
                .array()
        )
        fileOutStream?.seek(40)
        fileOutStream?.write(
            ByteBuffer.allocate(4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(dataLength - 1)
                .array()
        )

        fileOutStream?.close()

        val newFileName = fileOut?.absolutePath?.replace(Regex("\\.recording$"), "")

        newFileName?.let { fileOut?.renameTo(File(it)) }

        fileOutStream = null
        fileOut = null

        rawFileOutStream?.close()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy: ")

        Toast.makeText(this, "audio stopped", Toast.LENGTH_SHORT).show()

        finishFile()

        audioTrack.release()
        opus.decoderRelease()
    }
}
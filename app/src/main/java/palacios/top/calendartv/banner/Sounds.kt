package palacios.top.calendartv.banner

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.util.Log
import palacios.top.calendartv.R

data class SoundOption(val id: String, val label: String)

object Sounds {

    const val SILENT = "silent"
    const val DEFAULT = "default"
    private const val RAW = "raw:"
    private const val URI = "uri:"

    private val bundled = listOf(
        SoundOption("raw:sound_chime", "Chime"),
        SoundOption("raw:sound_bell", "Bell"),
        SoundOption("raw:sound_beep", "Double beep"),
    )

    fun options(context: Context): List<SoundOption> {
        val system = mutableListOf<SoundOption>()
        runCatching {
            val manager = RingtoneManager(context).apply { setType(RingtoneManager.TYPE_NOTIFICATION or RingtoneManager.TYPE_ALARM) }
            manager.cursor.use { cursor ->
                while (cursor.moveToNext() && system.size < 12) {
                    val uri = manager.getRingtoneUri(cursor.position)
                    system += SoundOption(URI + uri, cursor.getString(RingtoneManager.TITLE_COLUMN_INDEX))
                }
            }
        }
        return bundled + SoundOption(DEFAULT, "System default") + system + SoundOption(SILENT, "Silent")
    }

    fun label(context: Context, id: String): String = options(context).firstOrNull { it.id == id }?.label ?: "Chime"

    fun uri(context: Context, id: String): Uri? = when {
        id == SILENT -> null
        id == DEFAULT -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        id.startsWith(RAW) -> {
            val res = context.resources.getIdentifier(id.removePrefix(RAW), "raw", context.packageName)
            Uri.parse("android.resource://${context.packageName}/$res")
        }
        id.startsWith(URI) -> Uri.parse(id.removePrefix(URI))
        else -> null
    }
}

/** Plays one reminder sound at a time through the TV's main volume, briefly ducking whatever is playing. */
object SoundPlayer {

    private const val TAG = "CalendarTvSound"
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null

    @Synchronized
    fun play(context: Context, soundId: String, volumePercent: Int) {
        stop(context)
        val uri = Sounds.uri(context, soundId) ?: return
        // Media usage on purpose: a TV has one real volume (the remote's), while the notification, alarm and
        // system streams can sit at 0 (the Haier ships that way), which made reminders silent.
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val audio = context.getSystemService(AudioManager::class.java)
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .build()
        audio.requestAudioFocus(request)
        focus = request
        val volume = volumePercent.coerceIn(0, 100) / 100f
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(attributes)
                setDataSource(context, uri)
                setVolume(volume, volume)
                setOnPreparedListener { it.start() }
                setOnCompletionListener { stop(context) }
                setOnErrorListener { _, _, _ -> stop(context); true }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not play $soundId", e)
            stop(context)
        }
    }

    @Synchronized
    fun stop(context: Context) {
        player?.let { runCatching { it.release() } }
        player = null
        focus?.let { context.getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        focus = null
    }
}

package palacios.top.calendartv.banner

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.serialization.json.Json

/**
 * Draws the reminder banner over whatever app is in the foreground. The window is non-focusable and
 * non-touchable, so the remote keeps controlling the app underneath.
 */
class OverlayService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var banner: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val request = intent?.getStringExtra(EXTRA_REQUEST)
            ?.let { runCatching { Json.decodeFromString<BannerRequest>(it) }.getOrNull() }
        if (request == null || request.items.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        handler.removeCallbacksAndMessages(null)
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        removeBanner(windowManager)

        val view = buildBanner(request.items)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 48
            y = 48
        }

        try {
            windowManager.addView(view, params)
            banner = view
        } catch (e: Exception) {
            Log.e(TAG, "overlay failed", e)
            stopSelf()
            return START_NOT_STICKY
        }

        SoundPlayer.play(this, request.soundId, request.volume)
        handler.postDelayed({ stopSelf() }, request.seconds * 1_000L)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        removeBanner(getSystemService(Context.WINDOW_SERVICE) as WindowManager)
        SoundPlayer.stop(this)
        super.onDestroy()
    }

    private fun removeBanner(windowManager: WindowManager) {
        banner?.let { runCatching { windowManager.removeView(it) } }
        banner = null
    }

    private fun buildBanner(items: List<BannerItem>): View {
        val pad = 28
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F21E2A3A"))
                cornerRadius = 24f
            }
            minimumWidth = 560
        }
        items.take(MAX_ITEMS).forEachIndexed { index, item ->
            column.addView(itemView(item, topMargin = if (index == 0) 0 else 20))
        }
        if (items.size > MAX_ITEMS) {
            column.addView(
                TextView(this).apply {
                    text = "+${items.size - MAX_ITEMS} more"
                    textSize = 18f
                    setTextColor(Color.parseColor("#B3FFFFFF"))
                    setPadding(0, 16, 0, 0)
                },
            )
        }
        return column
    }

    private fun itemView(item: BannerItem, topMargin: Int): View {
        val color = runCatching { Color.parseColor(item.color) }.getOrDefault(Color.parseColor("#4285F4"))
        val bar = View(this).apply {
            setBackgroundColor(color)
            layoutParams = LinearLayout.LayoutParams(8, LinearLayout.LayoutParams.MATCH_PARENT).apply { marginEnd = 20 }
        }
        val text = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                TextView(this@OverlayService).apply {
                    this.text = item.title
                    textSize = 26f
                    setTextColor(Color.WHITE)
                    maxLines = 2
                    maxWidth = TEXT_MAX_WIDTH
                },
            )
            addView(
                TextView(this@OverlayService).apply {
                    this.text = item.subtitle
                    textSize = 19f
                    setTextColor(Color.parseColor("#CCFFFFFF"))
                    maxLines = 2
                    maxWidth = TEXT_MAX_WIDTH
                },
            )
            if (item.description.isNotBlank()) {
                addView(
                    TextView(this@OverlayService).apply {
                        this.text = item.description
                        textSize = 17f
                        setTextColor(Color.parseColor("#B3FFFFFF"))
                        maxLines = 3
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        maxWidth = TEXT_MAX_WIDTH
                        setPadding(0, 10, 0, 0)
                    },
                )
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { this.topMargin = topMargin }
            addView(bar)
            addView(text)
            avatarView(item)?.let { addView(it) }
        }
    }

    /** Circular account image at the right of the item: the Google photo, else a colored circle with the initial. */
    private fun avatarView(item: BannerItem): View? {
        val size = AVATAR_SIZE
        val layout = LinearLayout.LayoutParams(size, size).apply { marginStart = 24 }
        val bitmap = item.avatarPath.takeIf { it.isNotBlank() }?.let { BitmapFactory.decodeFile(it) }
        if (bitmap != null) {
            return ImageView(this).apply {
                setImageBitmap(bitmap)
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = layout
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) = outline.setOval(0, 0, view.width, view.height)
                }
                clipToOutline = true
            }
        }
        if (item.accountInitial.isBlank()) return null
        val fill = runCatching { Color.parseColor(item.avatarColor) }.getOrDefault(Color.parseColor("#5F6368"))
        return TextView(this).apply {
            text = item.accountInitial
            textSize = 26f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            layoutParams = layout
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(fill)
            }
        }
    }

    companion object {
        const val EXTRA_REQUEST = "request"
        private const val TAG = "CalendarTvOverlay"
        private const val MAX_ITEMS = 3
        private const val TEXT_MAX_WIDTH = 640
        private const val AVATAR_SIZE = 72
    }
}

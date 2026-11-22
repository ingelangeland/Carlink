package no.carlink

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewOutlineProvider
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.palette.graphics.Palette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.min

/** «Spilles nå» med coverbilde, fremdrift og knapper. Tilpasser seg panelets størrelse. */
@SuppressLint("ViewConstructor")
class NowPlayingPanel(ctx: Context) : ScopedFrame(ctx) {

    private val box = LinearLayout(ctx).apply { gravity = Gravity.CENTER; setPadding(20.dp, 12.dp, 20.dp, 16.dp) }
    private val cover = ImageView(ctx).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = rounded(C.PANEL2, 18)
        outlineProvider = ViewOutlineProvider.BACKGROUND
        clipToOutline = true
        elevation = 8.dp.toFloat()
    }
    private val info = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
    private val title = ctx.label("", 22f, C.TEXT, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER }
    private val artist = ctx.label("", 16f, C.SUB).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER }
    private val source = ctx.label("", 12f, C.ACCENT, true).apply { gravity = Gravity.CENTER }
    private val progress = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000
        progressTintList = ColorStateList.valueOf(C.ACCENT)
        progressBackgroundTintList = ColorStateList.valueOf(C.DIV)
    }
    private val time = ctx.label("", 12f, C.SUB).apply { gravity = Gravity.CENTER }
    private val playBtn: ImageButton
    private var media: Hub.Media? = null
    private var lastArtId = Int.MIN_VALUE

    init {
        info.addView(source)
        info.addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 4.dp })
        info.addView(artist, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 2.dp })
        info.addView(progress, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 6.dp).apply { topMargin = 14.dp })
        info.addView(time, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 4.dp })

        val controls = LinearLayout(ctx).apply { gravity = Gravity.CENTER }
        controls.addView(ctrl(android.R.drawable.ic_media_previous, 52, "prev"))
        playBtn = ctrl(android.R.drawable.ic_media_play, 68, "toggle").apply { background = rounded(C.ACCENT, 34) }
        controls.addView(playBtn, LinearLayout.LayoutParams(68.dp, 68.dp).apply { setMargins(18.dp, 0, 18.dp, 0) })
        controls.addView(ctrl(android.R.drawable.ic_media_next, 52, "next"))
        info.addView(controls, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 12.dp })

        box.addView(cover); box.addView(info)
        addView(box, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        render(null, "")
    }

    private fun ctrl(icon: Int, sizeDp: Int, action: String) = ImageButton(context).apply {
        setImageResource(icon)
        setColorFilter(C.TEXT)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(12.dp, 12.dp, 12.dp, 12.dp)
        background = rounded(C.PANEL2, sizeDp / 2)
        layoutParams = LinearLayout.LayoutParams(sizeDp.dp, sizeDp.dp)
        setOnClickListener { Hub.mediaCommand(action) }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        post { relayout(w, h) }
    }

    private fun relayout(w: Int, h: Int) {
        val horiz = w > h * 1.15f
        box.orientation = if (horiz) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        val size = if (horiz) min(h - 40.dp, (w * 0.42f).toInt()) else min(w - 48.dp, h - 260.dp)
        cover.visibility = if (size < 56.dp) GONE else VISIBLE
        cover.layoutParams = LinearLayout.LayoutParams(size.coerceAtLeast(1), size.coerceAtLeast(1)).apply {
            if (horiz) rightMargin = 24.dp else bottomMargin = 14.dp
        }
        info.layoutParams = if (horiz) LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        else LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        box.requestLayout()
    }

    override fun onStart(scope: CoroutineScope) {
        scope.launch {
            Ambient.media.collect { (m, src) -> render(m, src) }
        }
        scope.launch { while (isActive) { tick(); delay(1000) } }
    }

    private fun render(m: Hub.Media?, src: String) {
        media = m
        if (m == null) {
            source.text = ""
            title.text = "Ingen musikk spilles"
            artist.text = if (Hub.connected.value) "Start musikk på telefonen" else "Koble til telefonen, eller gi varseltilgang for lokal musikk"
            playBtn.setImageResource(android.R.drawable.ic_media_play)
            setArt(null, Int.MIN_VALUE + 1)
            tick(); return
        }
        source.text = src
        title.text = m.title.ifBlank { "Ukjent tittel" }
        artist.text = listOf(m.artist, m.album).filter { it.isNotBlank() }.joinToString(" · ")
        playBtn.setImageResource(if (m.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
        setArt(m.art, m.artId)
        tick()
    }

    private fun setArt(art: android.graphics.Bitmap?, id: Int) {
        if (id == lastArtId) return
        lastArtId = id
        if (art == null) {
            cover.scaleType = ImageView.ScaleType.CENTER
            cover.setImageResource(android.R.drawable.ic_media_play)
            cover.setColorFilter(C.SUB)
            return
        }
        cover.clearColorFilter()
        cover.scaleType = ImageView.ScaleType.CENTER_CROP
        cover.setImageBitmap(art)

    }

    private fun tick() {
        val m = media
        if (m == null || m.durationMs <= 0) { progress.progress = 0; time.text = ""; return }
        val pos = m.currentPosition()
        progress.progress = (pos * 1000 / m.durationMs).toInt()
        time.text = "${fmt(pos)} / ${fmt(m.durationMs)}"
    }

    private fun fmt(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }
}

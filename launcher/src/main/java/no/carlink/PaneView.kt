package no.carlink

import android.content.Context
import android.view.Gravity
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

enum class PaneType(val label: String) { MAP("Kart"), MUSIC("Musikk"), APPS("Apper") }

/** Ett panel i delt skjerm: liten velger øverst + innhold. */
class PaneView(
    ctx: Context,
    initial: PaneType,
    private val onPick: (PaneView, PaneType) -> Unit,
) : LinearLayout(ctx) {

    var type: PaneType = initial
        private set
    private val content = FrameLayout(ctx)
    private val chips = mutableMapOf<PaneType, TextView>()

    init {
        orientation = VERTICAL
        background = rounded(0xB3141821.toInt(), 22, 0x1AFFFFFF)
        outlineProvider = ViewOutlineProvider.BACKGROUND
        clipToOutline = true

        val header = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(10.dp, 8.dp, 10.dp, 4.dp)
        }
        PaneType.entries.forEach { t ->
            val chip = ctx.label(t.label, 12f, C.SUB, true).apply {
                setPadding(12.dp, 5.dp, 12.dp, 5.dp)
                setOnClickListener { if (t != type) onPick(this@PaneView, t) }
            }
            chips[t] = chip
            header.addView(chip, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { rightMargin = 4.dp })
        }
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        show(initial)
    }

    fun show(t: PaneType) {
        type = t
        content.removeAllViews()
        val v = when (t) {
            PaneType.MAP -> MapPanel(context)
            PaneType.MUSIC -> NowPlayingPanel(context)
            PaneType.APPS -> AppsPanel(context)
        }
        content.addView(v, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        chips.forEach { (k, chip) ->
            chip.background = if (k == t) rounded(C.ACCENT, 14) else null
            chip.setTextColor(if (k == t) 0xFFFFFFFF.toInt() else C.SUB)
        }
    }
}

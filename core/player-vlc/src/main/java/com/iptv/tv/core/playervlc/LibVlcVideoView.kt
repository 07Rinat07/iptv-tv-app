package com.iptv.tv.core.playervlc

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * Android View-адаптер, скрывающий типы LibVLC от feature-модулей.
 */
class LibVlcVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {
    internal val videoLayout = VLCVideoLayout(context)

    init {
        addView(
            videoLayout,
            LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT
            )
        )
    }
}

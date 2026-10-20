package com.andrerinas.openheadunit.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.ColorRes
import androidx.annotation.IdRes
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.connection.ConnectionStage
import com.andrerinas.openheadunit.main.BarFitPolicy
import com.andrerinas.openheadunit.main.HomePrimaryPolicy.Connection
import com.andrerinas.openheadunit.main.HomePrimaryPolicy.Decision
import com.andrerinas.openheadunit.main.HomePrimaryPolicy.Status
import com.andrerinas.openheadunit.main.HomePrimaryPolicy.TileState
import com.andrerinas.openheadunit.main.HomeThemePolicy.Mode

/** Paints the home screen. What it shows is decided by [com.andrerinas.openheadunit.main.HomePrimaryPolicy]. */
object HomeUiHelper {

    private const val RIPPLE_COLOR = 0x33000000
    private const val RIPPLE_COLOR_ON_DARK = 0x33FFFFFF
    private const val RIPPLE_COLOR_EXTREME = 0x1FFFFFFF
    private const val ACCENT_WASH_ALPHA = 0x29
    private const val PROGRESS_TRACK_ALPHA = 0x40
    private const val EXTREME_APP_ICON_ALPHA = 0.75f
    private const val EXTREME_CUSTOM_COLOR_DIM = 0.8f
    // Where black text starts to contrast better than white: (L + 0.05) / 0.05 = 1.05 / (L + 0.05).
    private const val DARK_TEXT_LUMINANCE = 0.179
    private const val NEAR_BLACK = 0xFF14100C.toInt()
    /** Shown in place of the speed while the GPS has nothing current to say. */
    private const val UNKNOWN_SPEED = "--"

    /** The rows a right-hand drive car reads from the other end. Vertical ones are left alone. */
    private val mirroredRows = listOf(
        R.id.main_buttons_container,
        R.id.home_driver_bar,
        R.id.home_favorites,
        R.id.home_primary_header,
        R.id.home_primary_actions
    )

    val favoriteSlotIds = listOf(
        R.id.favorite_slot_1, R.id.favorite_slot_2, R.id.favorite_slot_3, R.id.favorite_slot_4
    )

    private val scaledTiles = listOf(
        R.id.usb_button, R.id.wifi_button, R.id.self_mode_button, R.id.settings_button, R.id.exit_button,
        // The scroll view keeps the tiles' width, so it scales with them.
        R.id.home_bar_scroll
    )

    /**
     * What [fitDriverBar] shrinks: the connection tiles and the settings tile. Exit is left out, its
     * width and height stay at the user's scale, because it is the tile that must never be ambiguous
     * and at the smallest scales its icon and its label no longer fit side by side.
     */
    private val fittedTiles = listOf(
        R.id.usb_button, R.id.wifi_button, R.id.self_mode_button, R.id.settings_button, R.id.home_bar_scroll
    )

    fun chosenConnections(settings: Settings): Set<Connection> = settings.connectionModes.map {
        when (it) {
            Settings.ConnectionMode.USB -> Connection.USB
            Settings.ConnectionMode.WIFI -> Connection.WIFI
            Settings.ConnectionMode.SELF -> Connection.SELF
        }
    }.toSet()

    fun lastConnection(settings: Settings): Connection? = when (settings.lastConnectionType) {
        Settings.CONNECTION_TYPE_USB -> Connection.USB
        Settings.CONNECTION_TYPE_WIFI, Settings.CONNECTION_TYPE_NEARBY -> Connection.WIFI
        else -> null
    }

    private fun tileId(connection: Connection): Int = when (connection) {
        Connection.USB -> R.id.usb_button
        Connection.WIFI -> R.id.wifi_button
        Connection.SELF -> R.id.self_mode_button
    }

    /**
     * Puts the bar and the start of every row on the driver's side, and aligns the main button with
     * it. LEFT and RIGHT on purpose: the driver's side is physical, not a reading direction.
     */
    @SuppressLint("RtlHardcoded")
    fun applyDriverSide(rootView: View, rightHandDrive: Boolean) {
        mirroredRows.mapNotNull { rootView.findViewById<LinearLayout>(it) }
            .filter { it.orientation == LinearLayout.HORIZONTAL }
            .forEach { placeChildren(it, reversed = rightHandDrive) }

        val side = if (rightHandDrive) Gravity.RIGHT else Gravity.LEFT
        rootView.findViewById<LinearLayout>(R.id.home_primary_content)?.gravity = side
        listOf(R.id.home_primary_kicker, R.id.home_primary_title, R.id.home_primary_subtitle).forEach {
            rootView.findViewById<TextView>(it)?.gravity = side
        }
        // The date and the speed read inwards, so they line up with the edge they sit against, and
        // they sit at the far end of the card, which is the other side from the driver.
        val farSide = if (rightHandDrive) Gravity.LEFT else Gravity.RIGHT
        val info = rootView.findViewById<View>(R.id.home_info)
        (info?.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
            params.gravity = Gravity.TOP or farSide
            info.layoutParams = params
        }
        rootView.findViewById<LinearLayout>(R.id.home_info)?.gravity = farSide
        // The widget row keeps the order the driver placed, so it starts from their side by
        // gravity rather than by reversing what they chose.
        rootView.findViewById<LinearLayout>(R.id.home_widgets_row)?.gravity = side or Gravity.CENTER_VERTICAL
    }

    // The XML order is the left-hand drive one, kept on the row so applying twice is harmless.
    private fun placeChildren(row: LinearLayout, reversed: Boolean) {
        @Suppress("UNCHECKED_CAST")
        val xmlOrder = row.getTag(R.id.home_xml_order) as? List<View>
            ?: (0 until row.childCount).map { row.getChildAt(it) }.also { row.setTag(R.id.home_xml_order, it) }
        row.removeAllViews()
        // A for loop, not forEach: lint reads that one as Iterable.forEach, which is API 24.
        for (child in if (reversed) xmlOrder.asReversed() else xmlOrder) row.addView(child)
    }

    /**
     * The colors of one look. Day and dark come from the same resources (values and values-night);
     * extreme dark is a theme, not a resource qualifier, so it has its own set.
     */
    class Palette(
        val mode: Mode,
        @ColorInt val panel: Int,
        @ColorInt val panelStroke: Int,
        @ColorInt val tile: Int,
        @ColorInt val tilePressed: Int,
        @ColorInt val tileStroke: Int,
        @ColorInt val slotStroke: Int,
        @ColorInt val text: Int,
        @ColorInt val textMuted: Int,
        @ColorInt val appsTile: Int,
        @ColorInt val appsTilePressed: Int,
        @ColorInt val appsIcon: Int,
        @ColorInt val usb: Int,
        @ColorInt val wifi: Int,
        @ColorInt val self: Int,
        @ColorInt val monochrome: Int,
        @ColorInt val countdownRing: Int
    ) {
        val isDark: Boolean get() = mode != Mode.DAY
        val ripple: Int get() = when (mode) {
            Mode.DAY, Mode.DARK -> RIPPLE_COLOR_ON_DARK
            Mode.EXTREME_DARK -> RIPPLE_COLOR_EXTREME
        }
    }

    fun palette(context: Context, mode: Mode): Palette {
        val extreme = mode == Mode.EXTREME_DARK
        fun color(@ColorRes normal: Int, @ColorRes extremeDark: Int) =
            ContextCompat.getColor(context, if (extreme) extremeDark else normal)
        return Palette(
            mode = mode,
            panel = color(R.color.home_panel, R.color.home_extreme_panel),
            panelStroke = color(R.color.home_panel_stroke, R.color.home_extreme_panel_stroke),
            tile = color(R.color.home_tile, R.color.home_extreme_tile),
            tilePressed = color(R.color.home_tile_pressed, R.color.home_extreme_tile_pressed),
            tileStroke = color(R.color.home_tile_stroke, R.color.home_extreme_tile_stroke),
            slotStroke = color(R.color.home_slot_stroke, R.color.home_extreme_slot_stroke),
            text = color(R.color.home_text, R.color.home_extreme_text),
            textMuted = color(R.color.home_text_muted, R.color.home_extreme_text_muted),
            appsTile = color(R.color.home_apps_tile, R.color.home_extreme_apps_tile),
            appsTilePressed = color(R.color.home_apps_tile_pressed, R.color.home_extreme_apps_tile_pressed),
            appsIcon = color(R.color.home_apps_icon, R.color.home_extreme_apps_icon),
            usb = color(R.color.home_usb, R.color.home_extreme_usb),
            wifi = color(R.color.home_wifi, R.color.home_extreme_wifi),
            self = color(R.color.home_self, R.color.home_extreme_self),
            monochrome = color(R.color.home_monochrome, R.color.home_extreme_monochrome),
            countdownRing = color(R.color.home_countdown_ring, R.color.home_extreme_countdown_ring)
        )
    }

    /** Paints what does not change with the connection state: the bar panel, Exit, All and the countdown ring. */
    fun applyTheme(context: Context, rootView: View, palette: Palette) {
        val resources = context.resources
        val panelRadius = resources.getDimension(R.dimen.home_panel_radius)
        val strokePx = dp(context, 1)
        rootView.findViewById<View>(R.id.home_driver_bar)?.background =
            shape(palette.panel, panelRadius, palette.panelStroke, strokePx)
        rootView.findViewById<ViewGroup>(R.id.exit_button)?.let {
            paintTile(it, tileBackground(context, palette), palette.textMuted, palette.textMuted)
        }
        rootView.findViewById<ViewGroup>(R.id.apps_button)?.let {
            val radius = resources.getDimension(R.dimen.home_tile_radius)
            val background = pressable(
                palette.ripple,
                { shape(palette.appsTile, radius, palette.tileStroke, strokePx) },
                { shape(palette.appsTilePressed, radius, palette.tileStroke, strokePx) }
            )
            paintTile(it, background, palette.appsIcon, palette.text)
        }
        rootView.findViewById<TextView>(R.id.home_countdown)?.apply {
            setTextColor(palette.text)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(context, 6), palette.countdownRing)
            }
        }
        favoriteSlotIds.mapNotNull { rootView.findViewById<ViewGroup>(it) }.forEach {
            paintFavoriteSlot(context, it, it.getTag(R.id.home_favorite_filled) == true, palette)
        }
    }

    /** A filled slot is a tile with the app's own icon; a free one is a dashed outline with a "+". */
    fun paintFavoriteSlot(context: Context, slot: ViewGroup, filled: Boolean, palette: Palette) {
        paintSlot(context, slot, R.id.favorite_icon, R.id.favorite_label, filled, palette)
    }

    /** The "+" that adds a widget: the dashes an empty favorite slot already uses. */
    fun paintWidgetAddTile(context: Context, tile: ViewGroup, palette: Palette) {
        paintSlot(context, tile, R.id.widget_add_icon, R.id.widget_add_label, filled = false, palette)
    }

    /** A widget sits on the same tile surface as everything around it, so the row reads as one. */
    fun paintWidgetSlot(context: Context, slot: View, palette: Palette) {
        slot.background = shape(
            palette.tile,
            context.resources.getDimension(R.dimen.home_tile_radius),
            palette.tileStroke,
            dp(context, 1)
        )
    }

    private fun paintSlot(
        context: Context,
        slot: ViewGroup,
        @IdRes iconId: Int,
        @IdRes labelId: Int,
        filled: Boolean,
        palette: Palette
    ) {
        slot.setTag(R.id.home_favorite_filled, filled)
        val icon = slot.findViewById<ImageView>(iconId)
        val label = slot.findViewById<TextView>(labelId)
        if (filled) {
            slot.background = tileBackground(context, palette)
            icon?.clearColorFilter()
            // App icons keep their colors; extreme dark only turns them down.
            icon?.alpha = if (palette.mode == Mode.EXTREME_DARK) EXTREME_APP_ICON_ALPHA else 1f
            label?.setTextColor(palette.text)
        } else {
            val radius = context.resources.getDimension(R.dimen.home_tile_radius)
            val dash = dp(context, 8).toFloat()
            val gap = dp(context, 6).toFloat()
            slot.background = pressable(
                palette.ripple,
                { shape(palette.panel, radius, palette.slotStroke, dp(context, 2), dash, gap) },
                { shape(palette.tile, radius, palette.slotStroke, dp(context, 2), dash, gap) }
            )
            icon?.setColorFilter(palette.textMuted, PorterDuff.Mode.SRC_IN)
            icon?.alpha = 1f
            label?.setTextColor(palette.textMuted)
        }
    }

    /**
     * [tiles] says which connection is running or on its way. That tile wears the connection's
     * color and says so in place of its name, which the icon still gives: on a page without the
     * main button it is the only sign of either, and a third line would not fit the smallest tiles.
     */
    fun applyBar(
        context: Context,
        rootView: View,
        settings: Settings,
        barConnections: List<Connection>,
        palette: Palette,
        tiles: Map<Connection, TileState> = emptyMap()
    ) {
        Connection.values().forEach { connection ->
            val tile = rootView.findViewById<ViewGroup>(tileId(connection)) ?: return@forEach
            tile.visibility = if (connection in barConnections) View.VISIBLE else View.GONE
            val label = (0 until tile.childCount).map { tile.getChildAt(it) }.filterIsInstance<TextView>().firstOrNull()
            // The name from the XML, kept so a tile that stops connecting gets it back.
            val name = (label?.getTag(R.id.home_tile_name) as? CharSequence)
                ?: label?.text?.also { label.setTag(R.id.home_tile_name, it) }
            val state = tiles[connection] ?: TileState.IDLE
            val status = when (state) {
                TileState.ACTIVE -> context.getString(R.string.connected)
                TileState.CONNECTING -> context.getString(R.string.stage_connecting)
                TileState.IDLE -> null
            }
            label?.text = status ?: name
            tile.contentDescription = if (status != null && name != null) "$name, $status" else null
            val radius = context.resources.getDimension(R.dimen.home_tile_radius)
            val color = accentColor(settings, connection, palette)
            when (state) {
                TileState.ACTIVE -> {
                    val onColor = readableOn(color)
                    paintTile(tile, pressable(color, radius), onColor, onColor)
                }
                TileState.CONNECTING -> {
                    val strokePx = dp(context, 2)
                    paintTile(
                        tile,
                        pressable(
                            palette.ripple,
                            { shape(palette.tile, radius, color, strokePx) },
                            { shape(palette.tilePressed, radius, color, strokePx) }
                        ),
                        palette.text,
                        palette.text
                    )
                }
                TileState.IDLE -> paintTile(tile, tileBackground(context, palette), palette.text, palette.text)
            }
        }

        val settingsTile = rootView.findViewById<ViewGroup>(R.id.settings_button) ?: return
        val customColor = settings.customSettingsButtonColor
        val background = if (customColor != 0 && !palette.isDark) {
            pressable(customColor, context.resources.getDimension(R.dimen.home_tile_radius))
        } else {
            tileBackground(context, palette)
        }
        paintTile(settingsTile, background, palette.text, palette.text)
    }

    private fun tileBackground(context: Context, palette: Palette): Drawable {
        val radius = context.resources.getDimension(R.dimen.home_tile_radius)
        val strokePx = dp(context, 1)
        return pressable(
            palette.ripple,
            { shape(palette.tile, radius, palette.tileStroke, strokePx) },
            { shape(palette.tilePressed, radius, palette.tileStroke, strokePx) }
        )
    }

    private fun paintTile(tile: ViewGroup, background: Drawable, @ColorInt iconColor: Int, @ColorInt textColor: Int) {
        tile.background = background
        for (i in 0 until tile.childCount) {
            when (val child = tile.getChildAt(i)) {
                is ImageView -> child.setColorFilter(iconColor, PorterDuff.Mode.SRC_IN)
                is TextView -> child.setTextColor(textColor)
            }
        }
    }

    /** Scales the bar tiles and the favorites row by the user's home button scale. */
    fun applyButtonScale(rootView: View, scalePercent: Int) {
        val validScale = if (scalePercent in 60..120) scalePercent else 100
        val factor = validScale / 100.0f
        (scaledTiles + R.id.home_favorites).mapNotNull { rootView.findViewById<View>(it) }.forEach { scale(it, factor) }
        // This overwrites the fit, so [fitDriverBar] has to run again on top of it.
        rootView.setTag(R.id.home_bar_fit, null)
    }

    /**
     * Shrinks the bar tiles when the screen is too short for them at their current size, which is
     * what keeps the pinned Exit tile on screen: without it Exit was the child that got clipped.
     * The bar scrolls only when even the floor of [BarFitPolicy] is not enough. Portrait bars share
     * the width between their tiles instead, so they are left alone.
     */
    fun fitDriverBar(rootView: View, scalePercent: Int) {
        val bar = rootView.findViewById<LinearLayout>(R.id.home_driver_bar) ?: return
        if (bar.orientation != LinearLayout.VERTICAL) return
        val scroll = rootView.findViewById<View>(R.id.home_bar_scroll) ?: return
        val tiles = rootView.findViewById<ViewGroup>(R.id.home_bar_tiles) ?: return

        val available = scroll.height - tiles.paddingTop - tiles.paddingBottom
        val visible = (0 until tiles.childCount).map { tiles.getChildAt(it) }
            .filter { it.visibility == View.VISIBLE }
        val gapPx = rootView.resources.getDimensionPixelSize(R.dimen.home_gap)
        val fixedPx = gapPx * (visible.size - 1)
        val tileScale = BarFitPolicy.tileScale(available, visible.sumOf { baseHeight(it) }, fixedPx, scalePercent)

        // Applying the same size again would request another layout on every pass.
        if (rootView.getTag(R.id.home_bar_fit) == tileScale) return
        rootView.setTag(R.id.home_bar_fit, tileScale)
        fittedTiles.mapNotNull { rootView.findViewById<View>(it) }.forEach { view ->
            scale(view, tileScale)
            (view as? ViewGroup)?.let { scaleTileContent(it, tileScale) }
        }
    }

    /** The icon and the label shrink with their tile, so a shrunk tile still spells its name. */
    private fun scaleTileContent(tile: ViewGroup, factor: Float) {
        for (i in 0 until tile.childCount) {
            when (val child = tile.getChildAt(i)) {
                is ImageView -> scale(child, factor)
                is TextView -> {
                    val base = child.getTag(R.id.home_base_text_size) as? Float
                        ?: child.textSize.also { child.setTag(R.id.home_base_text_size, it) }
                    child.setTextSize(TypedValue.COMPLEX_UNIT_PX, base * factor)
                }
            }
        }
    }

    private fun baseHeight(view: View): Int = baseSize(view)[1].coerceAtLeast(0)

    // Sizes from the XML, kept on the view so every call scales from them rather than compounding.
    private fun baseSize(view: View): IntArray =
        (view.getTag(R.id.home_base_size) as? IntArray) ?: run {
            val params = view.layoutParams
            intArrayOf(params?.width ?: 0, params?.height ?: 0).also { view.setTag(R.id.home_base_size, it) }
        }

    private fun scale(view: View, factor: Float) {
        val params = view.layoutParams ?: return
        val base = baseSize(view)
        if (base[0] > 0) params.width = (base[0] * factor).toInt()
        if (base[1] > 0) params.height = (base[1] * factor).toInt()
        view.layoutParams = params
    }

    fun applyPrimaryCard(
        context: Context,
        rootView: View,
        settings: Settings,
        decision: Decision,
        phoneName: String?,
        secondsLeft: Int,
        palette: Palette,
        /** The WiFi Direct group, shown under the step while a connection comes up. */
        networkLine: String? = null
    ) {
        val card = rootView.findViewById<View>(R.id.home_primary_card) ?: return
        val reconnecting = decision.status == Status.RECONNECTING
        val connecting = decision.status == Status.CONNECTING
        val stage = decision.stage

        val kicker = rootView.findViewById<TextView>(R.id.home_primary_kicker)
        val title = rootView.findViewById<TextView>(R.id.home_primary_title)
        val subtitle = rootView.findViewById<TextView>(R.id.home_primary_subtitle)
        val action = rootView.findViewById<TextView>(R.id.home_primary_action)
        val icon = rootView.findViewById<ImageView>(R.id.home_primary_icon)
        kicker?.text = context.getString(kickerRes(decision))
        title?.text = title(context, decision, phoneName)
        subtitle?.text = stage?.let { context.getString(it.label) } ?: context.getString(subtitleRes(decision))
        action?.text = context.getString(actionRes(decision))
        // Nothing to start while a connection is on its way; Cancel is the one thing left to do.
        action?.visibility = if (connecting) View.GONE else View.VISIBLE
        icon?.setImageResource(iconRes(decision.primary))
        val cancel = rootView.findViewById<TextView>(R.id.home_primary_cancel)
        // The status pill's X lived here before: it stops the bring-up, listening included.
        cancel?.visibility = if (reconnecting || stage != null) View.VISIBLE else View.GONE
        rootView.findViewById<TextView>(R.id.home_countdown)?.apply {
            visibility = if (reconnecting) View.VISIBLE else View.GONE
            text = secondsLeft.toString()
        }
        val detail = rootView.findViewById<TextView>(R.id.home_primary_detail)
        detail?.text = networkLine
        detail?.visibility = if (stage != null && !networkLine.isNullOrEmpty()) View.VISIBLE else View.GONE
        val progress = rootView.findViewById<View>(R.id.home_primary_progress)
        progress?.visibility = if (connecting && stage != null) View.VISIBLE else View.GONE
        if (connecting && stage != null) setProgress(rootView, stage.rank, ConnectionStage.STARTING_PROJECTION.rank)

        val color = accentColor(settings, decision.primary, palette)
        paintProgress(context, rootView, color, palette)
        val resources = context.resources
        val panelRadius = resources.getDimension(R.dimen.home_panel_radius)
        val tileRadius = resources.getDimension(R.dimen.home_tile_radius)
        val ctaRadius = resources.getDimension(R.dimen.home_cta_height) / 3.5f
        val cancelRadius = resources.getDimension(R.dimen.home_secondary_cta_height) / 2f
        val iconTile = rootView.findViewById<View>(R.id.home_primary_icon_tile)

        if (!palette.isDark) {
            // Day: the whole card wears the connection's color.
            val shade = ColorUtils.darkenColor(color, 0.78f)
            card.background = rounded(color, panelRadius)
            iconTile?.background = rounded(shade, tileRadius)
            icon?.clearColorFilter()
            listOf(kicker, title, subtitle, detail).forEach { it?.setTextColor(Color.WHITE) }
            action?.apply {
                background = pressable(Color.WHITE, ctaRadius)
                setTextColor(ColorUtils.darkenColor(color, 0.66f))
            }
            cancel?.apply {
                background = pressable(shade, cancelRadius)
                setTextColor(Color.WHITE)
            }
            return
        }

        // Night: a neutral card, so the screen does not glow. The color stays on the kicker, the
        // icon and the button, which is what the driver looks for.
        val strokePx = dp(context, 1)
        card.background = shape(palette.panel, panelRadius, palette.panelStroke, strokePx)
        iconTile?.background = rounded(withAlpha(color, ACCENT_WASH_ALPHA), tileRadius)
        icon?.setColorFilter(color, PorterDuff.Mode.SRC_IN)
        kicker?.setTextColor(color)
        title?.setTextColor(palette.text)
        subtitle?.setTextColor(palette.textMuted)
        detail?.setTextColor(palette.textMuted)
        action?.apply {
            if (palette.mode == Mode.EXTREME_DARK) {
                // An outline lights far fewer pixels than a filled button.
                background = pressable(
                    palette.ripple,
                    { shape(Color.TRANSPARENT, ctaRadius, color, dp(context, 2)) },
                    { shape(palette.tilePressed, ctaRadius, color, dp(context, 2)) },
                    // A see-through button would hide its own ripple without a mask.
                    mask = { rounded(Color.WHITE, ctaRadius) }
                )
                setTextColor(color)
            } else {
                background = pressable(color, ctaRadius)
                setTextColor(readableOn(color))
            }
        }
        cancel?.apply {
            background = pressable(
                palette.ripple,
                { shape(palette.tile, cancelRadius, palette.tileStroke, strokePx) },
                { shape(palette.tilePressed, cancelRadius, palette.tileStroke, strokePx) }
            )
            setTextColor(palette.text)
        }
    }

    /**
     * The apps row exists only while Open Headunit is the launcher, and takes the height the main
     * button's icon would use. With the favorites switched off it keeps "All", the only way left
     * to the other apps.
     */
    fun applyFavoritesVisibility(rootView: View, isLauncherActive: Boolean, showFavorites: Boolean) {
        rootView.findViewById<View>(R.id.home_favorites)?.visibility = if (isLauncherActive) View.VISIBLE else View.GONE
        rootView.findViewById<View>(R.id.home_primary_icon_tile)?.visibility =
            if (isLauncherActive) View.GONE else View.VISIBLE
        favoriteSlotIds.forEach {
            rootView.findViewById<View>(it)?.visibility = if (showFavorites) View.VISIBLE else View.GONE
        }
    }

    /**
     * The date, the time and the speed the driver asked for, at the far end of the main button's
     * header. The countdown owns the header while it runs, so the block steps aside there rather
     * than the two fighting over the same space.
     */
    fun applyInfoBlock(
        rootView: View,
        settings: Settings,
        palette: Palette,
        clock: String,
        date: String,
        speed: String,
        countdownVisible: Boolean
    ) {
        val block = rootView.findViewById<View>(R.id.home_info) ?: return
        val showClock = settings.showHomeClock && !countdownVisible
        val showSpeed = settings.showHomeSpeed && !countdownVisible
        val visible = showClock || showSpeed
        block.visibility = if (visible) View.VISIBLE else View.GONE
        rootView.findViewById<TextView>(R.id.home_info_clock)?.apply {
            visibility = if (showClock) View.VISIBLE else View.GONE
            text = clock
            setTextColor(palette.text)
        }
        rootView.findViewById<TextView>(R.id.home_info_date)?.apply {
            visibility = if (showClock) View.VISIBLE else View.GONE
            text = date
            setTextColor(palette.textMuted)
        }
        rootView.findViewById<View>(R.id.home_info_speed_row)?.visibility =
            if (showSpeed) View.VISIBLE else View.GONE
        rootView.findViewById<TextView>(R.id.home_info_speed)?.apply {
            text = speed
            setTextColor(palette.text)
        }
        rootView.findViewById<TextView>(R.id.home_info_speed_unit)?.setTextColor(palette.textMuted)
        constrainInfoTexts(rootView, block, visible)
    }

    /**
     * The block floats in the card's empty corner, so the lines under it are narrowed to leave it
     * their width. Without this a long title ran under the time on a narrow screen. On a card too
     * narrow for both, the block steps out rather than covering the title.
     */
    private fun constrainInfoTexts(rootView: View, block: View, visible: Boolean) {
        val card = rootView.findViewById<View>(R.id.home_primary_card) ?: return
        val texts = listOf(
            R.id.home_primary_kicker, R.id.home_primary_title, R.id.home_primary_subtitle
        ).mapNotNull { rootView.findViewById<TextView>(it) }
        val content = rootView.findViewById<View>(R.id.home_primary_content)
        if (content != null && content.visibility != View.VISIBLE) {
            // Compact home: there is no title to protect, so the date and the speed keep the strip.
            texts.forEach { it.maxWidth = Int.MAX_VALUE }
            return
        }
        if (!visible) {
            texts.forEach { it.maxWidth = Int.MAX_VALUE }
            return
        }
        if (card.width <= 0) {
            // The first pass runs before the card has a width, and the block would cover the title
            // until the next minute. Ask again once the layout has one.
            card.doOnLayout { constrainInfoTexts(rootView, block, block.visibility == View.VISIBLE) }
            return
        }
        block.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val resources = card.resources
        val padding = resources.getDimensionPixelSize(R.dimen.home_card_padding_h)
        val gap = resources.getDimensionPixelSize(R.dimen.home_gap)
        val room = card.width - padding * 2 - block.measuredWidth - gap
        if (room < resources.getDimensionPixelSize(R.dimen.home_info_min_text)) {
            AppLog.i("HomeUiHelper: no width left for the title beside the date on this screen")
            block.visibility = View.GONE
            texts.forEach { it.maxWidth = Int.MAX_VALUE }
            return
        }
        texts.forEach { it.maxWidth = room }
    }

    /** The speed readout: what the GPS last said, or a placeholder when it has nothing. */
    fun speedText(kmh: Int?): String = kmh?.toString() ?: UNKNOWN_SPEED

    /**
     * The connection's color. At night the user's own colors are kept as accents (extreme dark
     * turns them down a little), and the monochrome option takes the color out altogether.
     */
    private fun accentColor(settings: Settings, connection: Connection, palette: Palette): Int {
        if (palette.isDark && settings.autoMonochromeButtonsAtNight) return palette.monochrome
        val customColor = when (connection) {
            Connection.USB -> settings.customUsbButtonColor
            Connection.WIFI -> settings.customWifiButtonColor
            Connection.SELF -> settings.customSelfModeButtonColor
        }
        if (customColor != 0) {
            return if (palette.mode == Mode.EXTREME_DARK) ColorUtils.darkenColor(customColor, EXTREME_CUSTOM_COLOR_DIM) else customColor
        }
        return when (connection) {
            Connection.USB -> palette.usb
            Connection.WIFI -> palette.wifi
            Connection.SELF -> palette.self
        }
    }

    private fun kickerRes(decision: Decision): Int = when (decision.status) {
        Status.ACTIVE_SESSION -> R.string.home_kicker_active
        Status.RECONNECTING -> R.string.home_kicker_last_connection
        else -> when (decision.primary) {
            Connection.USB -> R.string.home_kicker_cable
            Connection.WIFI -> R.string.home_kicker_wireless
            Connection.SELF -> R.string.self_mode
        }
    }

    private fun title(context: Context, decision: Decision, phoneName: String?): String = when (decision.status) {
        Status.ACTIVE_SESSION -> context.getString(R.string.home_title_active)
        Status.RECONNECTING -> context.getString(R.string.home_title_reconnecting)
        Status.CONNECTING -> context.getString(R.string.home_title_connecting)
        Status.SEARCHING -> context.getString(R.string.home_title_searching)
        Status.PHONE_DETECTED -> phoneName?.let { context.getString(R.string.home_title_phone_detected, it) }
            ?: context.getString(R.string.home_title_phone_detected_generic)
        Status.WAITING, Status.READY -> context.getString(
            when (decision.primary) {
                Connection.USB -> R.string.home_title_usb_ready
                Connection.WIFI -> R.string.home_title_wifi_ready
                Connection.SELF -> R.string.home_title_self_ready
            }
        )
    }

    /** How far along the steps are, as two weighted halves: no measuring, and it works on API 16. */
    private fun setProgress(rootView: View, done: Int, total: Int) {
        val fill = rootView.findViewById<View>(R.id.home_primary_progress_fill) ?: return
        val rest = rootView.findViewById<View>(R.id.home_primary_progress_rest) ?: return
        val doneWeight = done.coerceIn(1, total).toFloat()
        (fill.layoutParams as? LinearLayout.LayoutParams)?.let { it.weight = doneWeight; fill.layoutParams = it }
        (rest.layoutParams as? LinearLayout.LayoutParams)?.let { it.weight = total - doneWeight; rest.layoutParams = it }
    }

    private fun paintProgress(context: Context, rootView: View, @ColorInt color: Int, palette: Palette) {
        val track = rootView.findViewById<View>(R.id.home_primary_progress) ?: return
        val fill = rootView.findViewById<View>(R.id.home_primary_progress_fill) ?: return
        val radius = dp(context, 3).toFloat()
        // Day cards wear the connection's color, so the bar is white on it; night cards are neutral.
        val fillColor = if (palette.isDark) color else Color.WHITE
        track.background = rounded(withAlpha(fillColor, PROGRESS_TRACK_ALPHA), radius)
        fill.background = rounded(fillColor, radius)
    }

    private fun subtitleRes(decision: Decision): Int = when {
        decision.status == Status.SEARCHING -> R.string.home_subtitle_searching
        decision.primary == Connection.USB -> R.string.home_subtitle_usb
        decision.primary == Connection.WIFI -> R.string.home_subtitle_wifi
        else -> R.string.home_subtitle_self
    }

    private fun actionRes(decision: Decision): Int = when (decision.status) {
        Status.ACTIVE_SESSION -> R.string.home_action_back_to_android_auto
        Status.RECONNECTING -> R.string.home_action_connect_now
        Status.SEARCHING -> R.string.home_action_search_again
        else -> when (decision.primary) {
            Connection.USB -> R.string.home_action_connect_usb
            Connection.WIFI -> R.string.home_action_connect_wifi
            Connection.SELF -> R.string.home_action_start_self_mode
        }
    }

    private fun iconRes(connection: Connection): Int = when (connection) {
        Connection.USB -> R.drawable.ic_usb_white
        Connection.WIFI -> R.drawable.ic_network_wifi_white
        Connection.SELF -> R.drawable.ic_launch_white
    }

    private fun rounded(color: Int, radiusPx: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusPx
    }

    private fun shape(
        color: Int,
        radiusPx: Float,
        strokeColor: Int = Color.TRANSPARENT,
        strokePx: Int = 0,
        dashPx: Float = 0f,
        gapPx: Float = 0f
    ) = rounded(color, radiusPx).apply {
        if (strokePx > 0 && Color.alpha(strokeColor) != 0) setStroke(strokePx, strokeColor, dashPx, gapPx)
    }

    // Ripple is API 21; older units get a darker shade while pressed instead.
    private fun pressable(color: Int, radiusPx: Float): Drawable = pressable(
        RIPPLE_COLOR,
        { rounded(color, radiusPx) },
        { rounded(ColorUtils.darkenColor(color, 0.85f), radiusPx) }
    )

    private fun pressable(
        ripple: Int,
        normal: () -> Drawable,
        pressed: () -> Drawable,
        mask: (() -> Drawable)? = null
    ): Drawable {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            return RippleDrawable(ColorStateList.valueOf(ripple), normal(), mask?.invoke())
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressed())
            addState(intArrayOf(), normal())
        }
    }

    private fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)

    /** Near-black or white, whichever reads better on [background]. */
    private fun readableOn(background: Int): Int {
        val luminance = androidx.core.graphics.ColorUtils.calculateLuminance(background)
        return if (luminance > DARK_TEXT_LUMINANCE) NEAR_BLACK else Color.WHITE
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()
}

package com.andrerinas.openheadunit.main

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.DrawableCompat
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.utils.HomeUiHelper

/**
 * The home's own menu for a long press: a title that names what was pressed, one row per action
 * with an icon and, where it helps, a line on what the action takes with it, and Cancel.
 *
 * Drawn with the home's palette, so it reads as part of the home in its day, dark and extreme dark
 * looks rather than as the system's white list. Rows are as tall as a tile, so a driver's finger
 * does not need to aim.
 */
object HomeActionDialog {

    class Action(
        val title: String,
        val detail: String?,
        @DrawableRes val icon: Int,
        /** Takes something away. Painted in the warning color, and kept after the other rows. */
        val destructive: Boolean,
        val run: () -> Unit
    )

    fun show(context: Context, title: String, actions: List<Action>, palette: HomeUiHelper.Palette): Dialog {
        val dialog = Dialog(context, R.style.HomeActionDialog)
        val inflater = LayoutInflater.from(context)
        val content = inflater.inflate(R.layout.dialog_home_actions, null, false)
        val res = context.resources
        val panelRadius = res.getDimension(R.dimen.home_panel_radius)
        val tileRadius = res.getDimension(R.dimen.home_tile_radius)

        content.findViewById<View>(R.id.home_actions_panel).background = GradientDrawable().apply {
            cornerRadius = panelRadius
            setColor(palette.panel)
            setStroke(res.getDimensionPixelSize(R.dimen.home_action_stroke), palette.panelStroke)
        }
        content.findViewById<TextView>(R.id.home_actions_title).apply {
            text = title
            setTextColor(palette.text)
        }

        val list = content.findViewById<LinearLayout>(R.id.home_actions_list)
        for (action in actions.sortedBy { it.destructive }) {
            val row = inflater.inflate(R.layout.item_home_action, list, false)
            val accent = if (action.destructive) palette.usb else palette.text
            row.background = pressable(palette.tile, palette.tilePressed, tileRadius)
            row.findViewById<ImageView>(R.id.home_action_icon).setImageDrawable(tinted(context, action.icon, accent))
            row.findViewById<TextView>(R.id.home_action_title).apply {
                text = action.title
                setTextColor(accent)
            }
            row.findViewById<TextView>(R.id.home_action_detail).apply {
                if (action.detail != null) {
                    text = action.detail
                    setTextColor(palette.textMuted)
                    visibility = View.VISIBLE
                }
            }
            row.contentDescription = listOfNotNull(action.title, action.detail).joinToString(". ")
            row.setOnClickListener {
                dialog.dismiss()
                action.run()
            }
            list.addView(row)
        }

        content.findViewById<TextView>(R.id.home_actions_cancel).apply {
            setTextColor(palette.textMuted)
            background = pressable(Color.TRANSPARENT, palette.tilePressed, tileRadius)
            setOnClickListener { dialog.cancel() }
        }

        dialog.setContentView(content)
        dialog.window?.let { window ->
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            // As wide as reads well, never wider than the screen less a margin on each side.
            val margin = res.getDimensionPixelSize(R.dimen.home_gap) * 2
            val width = minOf(res.getDimensionPixelSize(R.dimen.home_action_max_width), res.displayMetrics.widthPixels - margin)
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
        return dialog
    }

    private fun tinted(context: Context, @DrawableRes icon: Int, @ColorInt color: Int): Drawable? =
        AppCompatResources.getDrawable(context, icon)?.mutate()?.let { drawable ->
            DrawableCompat.wrap(drawable).also { DrawableCompat.setTint(it, color) }
        }

    // A StateListDrawable rather than a ripple: ripples are Android 5.0, and this has to press
    // the same on a 4.1 unit.
    private fun pressable(@ColorInt normal: Int, @ColorInt pressed: Int, radius: Float): Drawable =
        StateListDrawable().apply {
            val down = GradientDrawable().apply { cornerRadius = radius; setColor(pressed) }
            addState(intArrayOf(android.R.attr.state_pressed), down)
            addState(intArrayOf(android.R.attr.state_focused), down)
            addState(intArrayOf(), GradientDrawable().apply { cornerRadius = radius; setColor(normal) })
        }
}

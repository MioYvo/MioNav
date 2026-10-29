package io.mionav.watch

import android.graphics.Color

/** Palette only. Changing a theme must never touch geometry or the protocol. */
data class Palette(
    val background: Int,
    val water: Int,
    val roadMajor: Int,
    val roadMajorCasing: Int,
    val roadMinor: Int,
    val roadMinorCasing: Int,
    val route: Int,
    val routeCasing: Int,
    val text: Int,
    val textDim: Int,
    val card: Int,
    val accent: Int,
    val warn: Int,
) {
    companion object {
        val DAY = Palette(
            background = Color.parseColor("#F6F6F1"),
            water = Color.parseColor("#CFE3F5"),
            roadMajor = Color.WHITE,
            roadMajorCasing = Color.parseColor("#B9BEC6"),
            roadMinor = Color.WHITE,
            roadMinorCasing = Color.parseColor("#D6DAE0"),
            route = Color.parseColor("#E8452C"),
            routeCasing = Color.parseColor("#B32D1B"),
            text = Color.parseColor("#111111"),
            textDim = Color.parseColor("#666B73"),
            card = Color.parseColor("#FFFFFF"),
            accent = Color.parseColor("#D0021B"),
            warn = Color.parseColor("#C77700"),
        )

        val NIGHT = Palette(
            background = Color.parseColor("#101216"),
            water = Color.parseColor("#16202B"),
            roadMajor = Color.parseColor("#2C323C"),
            roadMajorCasing = Color.parseColor("#1B2028"),
            roadMinor = Color.parseColor("#232830"),
            roadMinorCasing = Color.parseColor("#181C23"),
            route = Color.parseColor("#FF7A45"),
            routeCasing = Color.parseColor("#8C3A16"),
            text = Color.parseColor("#E8EAEE"),
            textDim = Color.parseColor("#9AA1AC"),
            card = Color.parseColor("#191D24"),
            accent = Color.parseColor("#FF5A5F"),
            warn = Color.parseColor("#FFC14D"),
        )
    }
}

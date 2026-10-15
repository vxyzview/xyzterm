package com.rk.icons

import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.svg.SvgDecoder
import com.rk.utils.application

@Composable
fun XedIcon(
    icon: Icon,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    tint: Color = LocalContentColor.current,
) {
    when (icon) {
        is Icon.ResourceIcon -> {
            Icon(
                painter = painterResource(icon.drawableRes),
                contentDescription = contentDescription,
                modifier = modifier,
                tint = tint,
            )
        }

        is Icon.ExternalResourceIcon -> {
            val theme = LocalContext.current.theme

            Icon(
                imageVector = ImageVector.vectorResource(theme = theme, resId = icon.drawableRes, res = icon.resources),
                contentDescription = contentDescription,
                modifier = modifier,
                tint = tint,
            )
        }

        is Icon.VectorIcon -> {
            Icon(imageVector = icon.vector, contentDescription = contentDescription, modifier = modifier, tint = tint)
        }

        is Icon.SvgIcon -> {
            AsyncImage(
                model = icon.file,
                imageLoader = rememberSvgImageLoader(),
                contentDescription = contentDescription,
                modifier = modifier,
                colorFilter = ColorFilter.tint(tint),
            )
        }

        is Icon.TextIcon -> {
            val textSize =
                when (icon.text.length) {
                    in 1..2 -> 14.sp
                    in 3..5 -> 12.sp
                    else -> 10.sp
                }

            Text(text = icon.text, fontFamily = FontFamily.Monospace, color = tint, fontSize = textSize, maxLines = 1)
        }
    }
}

/**
 * One loader for the process.
 *
 * This was `remember { ImageLoader.Builder(context)... }` -- `remember` keyed on nothing, so it
 * ran once per *composition*, not once per app. An icon-pack-backed list with 200 visible rows
 * built 200 ImageLoaders, each with its own memory cache, disk cache and dispatcher pool, and
 * Coil will not share decode results between them, so every SVG was re-parsed.
 */
private val svgImageLoader: ImageLoader by lazy {
    ImageLoader.Builder(application!!).components { add(SvgDecoder.Factory()) }.build()
}

@Composable
fun rememberSvgImageLoader(): ImageLoader = svgImageLoader

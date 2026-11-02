package org.navalny.vpn.ui.settings

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ColorLens
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.navalny.vpn.R
import org.navalny.vpn.core.Palette
import org.navalny.vpn.ui.components.MorphingShape
import org.navalny.vpn.ui.components.Shapes
import org.navalny.vpn.ui.theme.schemeFromSeed

@Composable
fun paletteName(p: Palette): String = stringResource(
    when (p) {
        Palette.DYNAMIC -> R.string.palette_dynamic
        Palette.INDIGO -> R.string.palette_indigo
        Palette.SUNSET -> R.string.palette_sunset
        Palette.FOREST -> R.string.palette_forest
        Palette.PURPLE -> R.string.palette_purple
        Palette.OCEAN -> R.string.palette_ocean
        Palette.ROSE -> R.string.palette_rose
    },
)

/** Row of palette swatches; the selected one sits inside a slowly morphing cookie ring. */
@Composable
fun PalettePicker(
    index: Int,
    count: Int,
    selected: Palette,
    animate: Boolean,
    onSelect: (Palette) -> Unit,
) {
    val context = LocalContext.current
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val palettes = remember {
        Palette.entries.filter { it != Palette.DYNAMIC || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S }
    }
    val previews: Map<Palette, ColorScheme> = remember(dark) {
        palettes.associateWith { p ->
            when (p) {
                Palette.DYNAMIC ->
                    if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
                else -> schemeFromSeed(p.seed, dark, vibrant = true)
            }
        }
    }

    // Bring the selected swatch into view (the row scrolls horizontally on narrow screens).
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    LaunchedEffect(Unit) {
        val i = palettes.indexOf(selected).coerceAtLeast(0)
        scroll.scrollTo(with(density) { ((i - 1).coerceAtLeast(0) * 70).dp.roundToPx() })
    }

    SegmentedListItem(
        shapes = ListItemDefaults.segmentedShapes(index, count),
        leadingContent = { Icon(Icons.Rounded.ColorLens, null) },
        supportingContent = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .padding(top = 10.dp)
                    .horizontalScroll(scroll),
            ) {
                palettes.forEach { p ->
                    Swatch(
                        name = paletteName(p),
                        scheme = previews.getValue(p),
                        selected = p == selected,
                        animate = animate,
                        onClick = { onSelect(p) },
                    )
                }
            }
        },
    ) {
        Text(stringResource(R.string.settings_palette))
    }
}

@Composable
private fun Swatch(
    name: String,
    scheme: ColorScheme,
    selected: Boolean,
    animate: Boolean,
    onClick: () -> Unit,
) {
    val scale by animateFloatAsState(
        if (selected) 1f else 0.86f,
        MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "swatch",
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(64.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .semantics {
                this.selected = selected
                contentDescription = name
            },
    ) {
        Box(Modifier.size(60.dp), contentAlignment = Alignment.Center) {
            if (selected) {
                MorphingShape(
                    shapes = Shapes.Swatch,
                    color = scheme.primary.copy(alpha = 0.35f),
                    animate = animate,
                    holdMillis = 2400,
                    rotationMillis = 18000,
                    modifier = Modifier.size(60.dp),
                )
            }
            Canvas(
                Modifier
                    .size(44.dp)
                    .graphicsLayer { scaleX = scale; scaleY = scale },
            ) {
                // Android-wallpaper-picker style: primary on top, secondary / tertiary below.
                drawArc(scheme.primary, 180f, 180f, useCenter = true, size = size, topLeft = Offset.Zero)
                drawArc(scheme.secondaryContainer, 90f, 90f, useCenter = true, size = size)
                drawArc(scheme.tertiary, 0f, 90f, useCenter = true, size = size)
                drawCircle(scheme.surface, radius = size.minDimension * 0.18f)
            }
            if (selected) {
                Icon(
                    Icons.Rounded.Check,
                    null,
                    tint = scheme.onSurface,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            name,
            style = if (selected) MaterialTheme.typography.labelMediumEmphasized else MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

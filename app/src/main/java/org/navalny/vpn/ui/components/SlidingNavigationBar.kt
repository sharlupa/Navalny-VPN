package org.navalny.vpn.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarDefaults
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.ShortNavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

class NavItem(val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

/**
 * MD3 Expressive ShortNavigationBar with ONE active-indicator pill that slides between items
 * instead of fading in/out per item.
 *
 * The pill "stretches" while moving: its leading edge uses a stiffer spring than the trailing
 * edge, so it elongates towards the target and then snaps back to 56×32 dp — a liquid feel.
 * It is drawn on a Canvas and read in the draw phase only, so the bar itself never recomposes
 * during the animation.
 */
@Composable
fun SlidingNavigationBar(
    items: List<NavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val pillWidth = with(density) { 56.dp.toPx() }
    val pillHeight = with(density) { 32.dp.toPx() }
    val pillColor = MaterialTheme.colorScheme.secondaryContainer

    var barCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val iconBounds = remember { mutableStateMapOf<Int, Rect>() }

    val left = remember { Animatable(Float.NaN) }
    val right = remember { Animatable(Float.NaN) }
    var centerY by remember { mutableStateOf(Float.NaN) }

    val target = iconBounds[selectedIndex]
    LaunchedEffect(selectedIndex, target) {
        val t = target ?: return@LaunchedEffect
        val targetLeft = t.center.x - pillWidth / 2
        val targetRight = t.center.x + pillWidth / 2
        centerY = t.center.y
        if (left.value.isNaN()) {
            left.snapTo(targetLeft)
            right.snapTo(targetRight)
            return@LaunchedEffect
        }
        val movingRight = targetLeft > left.value
        val fast = spring<Float>(dampingRatio = 0.75f, stiffness = 900f)
        val slow = spring<Float>(dampingRatio = 0.7f, stiffness = 260f)
        coroutineScope {
            launch { left.animateTo(targetLeft, if (movingRight) slow else fast) }
            launch { right.animateTo(targetRight, if (movingRight) fast else slow) }
        }
    }

    Box(
        modifier
            .fillMaxWidth()
            .background(ShortNavigationBarDefaults.containerColor)
            .onGloballyPositioned { barCoordinates = it },
    ) {
        Canvas(Modifier.matchParentSize()) {
            val l = left.value
            val r = right.value
            if (l.isNaN() || centerY.isNaN()) return@Canvas
            drawRoundRect(
                color = pillColor,
                topLeft = Offset(l, centerY - pillHeight / 2),
                size = Size(r - l, pillHeight),
                cornerRadius = CornerRadius(pillHeight / 2),
            )
        }
        ShortNavigationBar(containerColor = Color.Transparent) {
            items.forEachIndexed { index, item ->
                val selected = index == selectedIndex
                ShortNavigationBarItem(
                    selected = selected,
                    onClick = { onSelect(index) },
                    icon = {
                        Icon(
                            if (selected) item.selectedIcon else item.icon,
                            contentDescription = null,
                            modifier = Modifier.onGloballyPositioned { c ->
                                val bar = barCoordinates ?: return@onGloballyPositioned
                                if (c.isAttached && bar.isAttached) {
                                    val rect = bar.localBoundingBoxOf(c, clipBounds = false)
                                    if (iconBounds[index] != rect) iconBounds[index] = rect
                                }
                            },
                        )
                    },
                    label = { Text(item.label) },
                    colors = ShortNavigationBarItemDefaults.colors(selectedIndicatorColor = Color.Transparent),
                )
            }
        }
    }
}

package org.navalny.vpn.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.sign

/** Shape sets used across the app (MaterialShapes are cached singletons → stable keys). */
object Shapes {
    val ConnectIdle by lazy {
        listOf(MaterialShapes.Cookie9Sided, MaterialShapes.Clover8Leaf, MaterialShapes.Sunny, MaterialShapes.Cookie6Sided)
    }
    val ConnectBusy by lazy { listOf(MaterialShapes.SoftBurst, MaterialShapes.Cookie12Sided, MaterialShapes.Burst) }
    val ConnectOn by lazy { listOf(MaterialShapes.SoftBurst, MaterialShapes.Cookie12Sided) }
    val ConnectError by lazy { listOf(MaterialShapes.SoftBoom) }
    val Pressed by lazy { listOf(MaterialShapes.Square) }
    val HaloIdle by lazy { listOf(MaterialShapes.Cookie12Sided) }
    val HaloOn by lazy { listOf(MaterialShapes.Sunny, MaterialShapes.Cookie12Sided, MaterialShapes.Flower) }
    val ServerIdle by lazy {
        listOf(MaterialShapes.Cookie4Sided, MaterialShapes.Gem, MaterialShapes.Clover4Leaf, MaterialShapes.Pentagon)
    }
    val ServerOn by lazy { listOf(MaterialShapes.SoftBurst, MaterialShapes.Cookie7Sided) }
    val Swatch by lazy { listOf(MaterialShapes.Cookie9Sided, MaterialShapes.Cookie12Sided) }
}

/** Slow, bouncy spring — the "expressive" feel. */
val ExpressiveMorphSpring: AnimationSpec<Float> = spring(dampingRatio = 0.55f, stiffness = 60f)

/**
 * A filled shape that morphs between [shapes] (MD3 Expressive shape morphing).
 *
 *  - When [shapes] changes (e.g. disconnected → connected, or pressed), it morphs from what is on
 *    screen into the first shape of the new set.
 *  - With [animate] = true and more than one shape it keeps looping through the set, pausing
 *    [holdMillis] on each, and optionally rotates (one turn per [rotationMillis], negative = CCW).
 *
 * Everything is read in the draw phase (no recomposition per frame), and Compose pauses its frame
 * clock when the app is not visible — so with the screen off this costs nothing.
 */
@Composable
fun MorphingShape(
    shapes: List<RoundedPolygon>,
    color: Color,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
    holdMillis: Long = 1800,
    rotationMillis: Int = 0,
    morphSpec: AnimationSpec<Float> = ExpressiveMorphSpring,
    content: @Composable () -> Unit = {},
) {
    var from by remember { mutableStateOf(shapes.first()) }
    var to by remember { mutableStateOf(shapes.first()) }
    val progress = remember { Animatable(1f) }
    val rotation = remember { Animatable(0f) }
    val morph = remember(from, to) { Morph(from, to) }

    LaunchedEffect(shapes, animate) {
        if (to != shapes.first()) {
            // start from whatever is closer to what the user currently sees
            if (progress.value >= 0.5f) from = to
            to = shapes.first()
            progress.snapTo(0f)
            progress.animateTo(1f, morphSpec)
        }
        if (!animate || shapes.size < 2) return@LaunchedEffect
        var index = 0
        while (true) {
            delay(holdMillis)
            index = (index + 1) % shapes.size
            from = to
            to = shapes[index]
            progress.snapTo(0f)
            progress.animateTo(1f, morphSpec)
        }
    }

    LaunchedEffect(rotationMillis, animate) {
        if (!animate || rotationMillis == 0) return@LaunchedEffect
        val direction = sign(rotationMillis.toFloat())
        while (true) {
            rotation.animateTo(rotation.value + 360f * direction, tween(abs(rotationMillis), easing = LinearEasing))
            rotation.snapTo(rotation.value % 360f)
        }
    }

    val path = remember { Path() }
    val matrix = remember { Matrix() }
    Box(
        modifier.drawBehind {
            path.rewind()
            morph.toPath(progress = progress.value, path = path)
            matrix.reset()
            matrix.scale(size.width, size.height)
            path.transform(matrix)
            val bounds = path.getBounds()
            path.translate(center - bounds.center)
            rotate(rotation.value) { drawPath(path, color) }
        },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

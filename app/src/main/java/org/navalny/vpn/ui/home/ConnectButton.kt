package org.navalny.vpn.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import org.navalny.vpn.R
import org.navalny.vpn.service.VpnStatus

/** A Shape that renders a [Morph] between two MaterialShapes at [progress]. */
private class MorphShape(private val morph: Morph, private val progress: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = morph.toPath(progress = progress, path = Path())
        path.transform(Matrix().apply { scale(size.width, size.height) })
        path.translate(size.center - path.getBounds().center)
        return Outline.Generic(path)
    }

    private val Size.center get() = androidx.compose.ui.geometry.Offset(width / 2f, height / 2f)
}

/**
 * Big connect button — MD3 Expressive shape morphing:
 * Cookie (disconnected) → Soft burst (connected), with spring motion from the expressive
 * MotionScheme. The rotation runs ONLY while connecting; in idle / connected state there are
 * no running animations, so the UI does not render frames needlessly.
 */
@Composable
fun ConnectButton(
    status: VpnStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val connected = status is VpnStatus.Connected
    val busy = status is VpnStatus.Connecting || status is VpnStatus.Stopping
    val haptics = LocalHapticFeedback.current
    val colors = MaterialTheme.colorScheme

    val morph = remember { Morph(MaterialShapes.Cookie9Sided, MaterialShapes.SoftBurst) }
    val progress by animateFloatAsState(
        targetValue = if (connected) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.slowSpatialSpec(),
        label = "morph",
    )
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "press",
    )
    val container by animateColorAsState(
        targetValue = when {
            connected -> colors.primary
            status is VpnStatus.Error -> colors.errorContainer
            else -> colors.primaryContainer
        },
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "container",
    )
    val content by animateColorAsState(
        targetValue = when {
            connected -> colors.onPrimary
            status is VpnStatus.Error -> colors.onErrorContainer
            else -> colors.onPrimaryContainer
        },
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "content",
    )
    val halo by animateColorAsState(
        targetValue = if (connected) colors.primary.copy(alpha = 0.16f) else colors.surfaceContainerHigh,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "halo",
    )

    val rotation = if (busy) {
        val transition = rememberInfiniteTransition(label = "spin")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(6000, easing = LinearEasing), RepeatMode.Restart),
            label = "rotation",
        ).value
    } else {
        0f
    }

    val label = stringResource(
        when (status) {
            is VpnStatus.Connected -> R.string.action_disconnect
            VpnStatus.Connecting -> R.string.status_connecting
            VpnStatus.Stopping -> R.string.status_stopping
            else -> R.string.action_connect
        },
    )

    Box(
        modifier = modifier
            .size(232.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        // Halo
        Box(
            Modifier
                .size(232.dp)
                .graphicsLayer { rotationZ = -rotation / 2 }
                .clip(MorphShape(morph, progress))
                .background(halo),
        )
        // Button body
        Box(
            Modifier
                .size(196.dp)
                .graphicsLayer { rotationZ = rotation }
                .clip(MorphShape(morph, progress))
                .background(container),
        )
        Box(
            Modifier
                .size(176.dp)
                .clip(CircleShape)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = !busy,
                    role = Role.Button,
                ) {
                    haptics.performHapticFeedback(
                        if (connected) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn,
                    )
                    onClick()
                },
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = busy,
                transitionSpec = {
                    (fadeIn() + scaleIn(initialScale = 0.8f)) togetherWith (fadeOut() + scaleOut(targetScale = 0.8f))
                },
                label = "icon",
            ) { isBusy ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (isBusy) {
                        LoadingIndicator(color = content, modifier = Modifier.size(64.dp))
                    } else {
                        Icon(
                            Icons.Rounded.PowerSettingsNew,
                            contentDescription = null,
                            tint = content,
                            modifier = Modifier.size(56.dp),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleMediumEmphasized,
                        color = content,
                    )
                }
            }
        }
    }
}

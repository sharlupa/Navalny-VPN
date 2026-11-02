package org.navalny.vpn.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.navalny.vpn.R
import org.navalny.vpn.service.VpnStatus
import org.navalny.vpn.ui.components.MorphingShape
import org.navalny.vpn.ui.components.Shapes

/**
 * Big connect button — MD3 Expressive shape morphing:
 *  - idle: slowly cycles Cookie → Clover → Sunny → Cookie6 while rotating;
 *  - pressed: squishes into a rounded square;
 *  - connecting: quick burst morphs + fast spin + loading indicator;
 *  - connected: soft burst ↔ cookie, the halo "breathes".
 *
 * With [animate] = false (setting "Animations") the button only morphs on state changes.
 * All looping work stops automatically when the app is in background / the screen is off.
 */
@Composable
fun ConnectButton(
    status: VpnStatus,
    onClick: () -> Unit,
    animate: Boolean,
    modifier: Modifier = Modifier,
) {
    val connected = status is VpnStatus.Connected
    val busy = status is VpnStatus.Connecting || status is VpnStatus.Stopping
    val error = status is VpnStatus.Error
    val haptics = LocalHapticFeedback.current
    val colors = MaterialTheme.colorScheme

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "press",
    )

    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<androidx.compose.ui.graphics.Color>()
    val container by animateColorAsState(
        when {
            connected -> colors.primary
            error -> colors.errorContainer
            busy -> colors.secondaryContainer
            else -> colors.primaryContainer
        },
        effects, label = "container",
    )
    val content by animateColorAsState(
        when {
            connected -> colors.onPrimary
            error -> colors.onErrorContainer
            busy -> colors.onSecondaryContainer
            else -> colors.onPrimaryContainer
        },
        effects, label = "content",
    )
    val halo by animateColorAsState(
        if (connected) colors.primary.copy(alpha = 0.18f) else colors.surfaceContainerHigh,
        effects, label = "halo",
    )

    // Halo "breathing" while connected (only when animations are on and the app is visible).
    val breath = remember { Animatable(1f) }
    LaunchedEffect(connected, animate) {
        if (connected && animate) {
            while (true) {
                breath.animateTo(1.06f, tween(1600, easing = FastOutSlowInEasing))
                breath.animateTo(1f, tween(1600, easing = FastOutSlowInEasing))
            }
        } else {
            breath.animateTo(1f)
        }
    }

    val bodyShapes = when {
        pressed -> Shapes.Pressed
        busy -> Shapes.ConnectBusy
        connected -> Shapes.ConnectOn
        error -> Shapes.ConnectError
        else -> Shapes.ConnectIdle
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
            .size(240.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        MorphingShape(
            shapes = if (connected) Shapes.HaloOn else Shapes.HaloIdle,
            color = halo,
            animate = animate,
            holdMillis = 2600,
            rotationMillis = if (busy) -6000 else -45000,
            modifier = Modifier
                .size(236.dp)
                .graphicsLayer { scaleX = breath.value; scaleY = breath.value },
        )
        MorphingShape(
            shapes = bodyShapes,
            color = container,
            // the busy morph sequence runs even with animations off — it is the progress indicator
            animate = animate || busy,
            holdMillis = if (busy) 250 else 2200,
            rotationMillis = when {
                busy -> 3500
                connected -> 24000
                else -> 32000
            },
            modifier = Modifier.size(200.dp),
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
                    (fadeIn() + scaleIn(initialScale = 0.7f)) togetherWith (fadeOut() + scaleOut(targetScale = 0.7f))
                },
                label = "icon",
            ) { isBusy ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (isBusy) {
                        LoadingIndicator(color = content, modifier = Modifier.size(64.dp))
                    } else {
                        val iconRotation by animateFloatAsState(
                            // full turn — the symbol ends upright again
                            if (connected) 360f else 0f,
                            MaterialTheme.motionScheme.slowSpatialSpec(),
                            label = "icon-rotation",
                        )
                        Icon(
                            Icons.Rounded.PowerSettingsNew,
                            contentDescription = null,
                            tint = content,
                            modifier = Modifier
                                .size(56.dp)
                                .graphicsLayer { rotationZ = iconRotation },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(label, style = MaterialTheme.typography.titleMediumEmphasized, color = content)
                }
            }
        }
    }
}

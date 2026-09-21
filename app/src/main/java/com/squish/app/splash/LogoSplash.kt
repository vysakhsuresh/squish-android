package com.squish.app.splash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.squish.app.R
import com.squish.app.ui.theme.SquishColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Picks up where the system splash leaves off. The launcher shows the mark on the
 * brand ground; this brings the full lockup in with a squish settle and a glow
 * bloom, then clears to the dashboard. Same artwork, same background, so the
 * hand-off has no visible seam.
 */
@Composable
fun LogoSplash(onFinished: () -> Unit) {
    val scaleX = remember { Animatable(0.74f) }
    val scaleY = remember { Animatable(0.90f) }
    val alpha = remember { Animatable(0f) }
    val glow = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        launch { alpha.animateTo(1f, tween(280)) }
        launch { glow.animateTo(1f, tween(620)) }

        launch {
            scaleX.animateTo(1.10f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMediumLow))
            scaleX.animateTo(1f, spring(Spring.DampingRatioLowBouncy, Spring.StiffnessLow))
        }
        scaleY.animateTo(0.88f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMediumLow))
        scaleY.animateTo(1f, spring(Spring.DampingRatioLowBouncy, Spring.StiffnessLow))

        delay(520)
        launch { glow.animateTo(0f, tween(240)) }
        alpha.animateTo(0f, tween(240))
        onFinished()
    }

    Box(
        modifier = Modifier.fillMaxSize().background(SquishColors.Background),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(330.dp)
                .graphicsLayer { this.alpha = glow.value * 0.45f }
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            SquishColors.Violet.copy(alpha = 0.75f),
                            SquishColors.Cyan.copy(alpha = 0.22f),
                            Color.Transparent
                        )
                    )
                )
        )

        Image(
            painter = painterResource(R.drawable.squish_logo),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(280.dp)
                .graphicsLayer {
                    this.scaleX = scaleX.value
                    this.scaleY = scaleY.value
                    this.alpha = alpha.value
                }
        )
    }
}

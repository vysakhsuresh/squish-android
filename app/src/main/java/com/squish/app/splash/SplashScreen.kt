package com.squish.app.splash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.graphicsLayer
import androidx.compose.ui.unit.dp
import com.squish.app.ui.components.SquishLogoMark
import com.squish.app.ui.theme.SquishColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The "fancy stuff" splash: the mark pops in and does a literal squish
 * (scaleX/scaleY animate independently with a bouncy spring) before settling,
 * then the wordmark fades up underneath it. ~1.5s total, then hands off to Home.
 *
 * This is the placeholder brand animation - swap the mark/timing here once a
 * real intro design is ready, the hook (onFinished) and timing budget stay the same.
 */
@Composable
fun SplashScreen(onFinished: () -> Unit) {
    val scaleX = remember { Animatable(0.4f) }
    val scaleY = remember { Animatable(0.4f) }
    val markAlpha = remember { Animatable(0f) }
    val wordmarkAlpha = remember { Animatable(0f) }
    val wordmarkOffset = remember { Animatable(18f) }

    LaunchedEffect(Unit) {
        markAlpha.animateTo(1f, tween(180))

        launch {
            scaleX.animateTo(1.22f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
            scaleX.animateTo(0.90f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
            scaleX.animateTo(1f, spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow))
        }
        launch {
            scaleY.animateTo(0.78f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
            scaleY.animateTo(1.15f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
            scaleY.animateTo(1f, spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow))
        }

        delay(420)
        wordmarkAlpha.animateTo(1f, tween(320))
        wordmarkOffset.animateTo(0f, tween(320))

        delay(650)
        onFinished()
    }

    Box(
        modifier = Modifier.fillMaxSize().background(SquishColors.Background),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SquishLogoMark(
                modifier = Modifier
                    .size(84.dp)
                    .graphicsLayer {
                        this.scaleX = scaleX.value
                        this.scaleY = scaleY.value
                        this.alpha = markAlpha.value
                    }
            )
            Text(
                text = "Squish",
                color = SquishColors.TextPrimary,
                style = MaterialTheme.typography.displayLarge,
                modifier = Modifier.graphicsLayer {
                    alpha = wordmarkAlpha.value
                    translationY = wordmarkOffset.value
                }
            )
        }
    }
}

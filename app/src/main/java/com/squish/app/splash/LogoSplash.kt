package com.squish.app.splash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.squish.app.R
import com.squish.app.ui.theme.SquishColors
import kotlinx.coroutines.delay

/**
 * Restrained on purpose: the mark eases up to size while the wordmark lifts in
 * under it, then the whole thing clears. No bounce, no glow - the earlier version
 * drew attention to itself instead of getting out of the way.
 */
@Composable
fun LogoSplash(onFinished: () -> Unit) {
    val markScale = remember { Animatable(0.88f) }
    val markAlpha = remember { Animatable(0f) }
    val wordAlpha = remember { Animatable(0f) }
    val wordLift = remember { Animatable(14f) }
    val exit = remember { Animatable(1f) }

    LaunchedEffect(Unit) {
        markAlpha.animateTo(1f, tween(360, easing = FastOutSlowInEasing))
        markScale.animateTo(1f, tween(560, easing = FastOutSlowInEasing))
        wordAlpha.animateTo(1f, tween(300))
        wordLift.animateTo(0f, tween(360, easing = FastOutSlowInEasing))
        delay(420)
        exit.animateTo(0f, tween(260))
        onFinished()
    }

    Box(
        modifier = Modifier.fillMaxSize().background(SquishColors.Background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.graphicsLayer { alpha = exit.value }
        ) {
            Image(
                painter = painterResource(R.drawable.squish_mark_art),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(148.dp)
                    .graphicsLayer {
                        scaleX = markScale.value
                        scaleY = markScale.value
                        alpha = markAlpha.value
                    }
            )
            Text(
                text = "SQUISH",
                color = SquishColors.TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                letterSpacing = 6.sp,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.graphicsLayer {
                    alpha = wordAlpha.value
                    translationY = wordLift.value
                }
            )
        }
    }
}

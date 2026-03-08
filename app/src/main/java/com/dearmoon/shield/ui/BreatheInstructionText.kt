package com.dearmoon.shield.ui

/* 
 * Reusable Design System Component for the "Breathe In" instruction typography.
 * Implements the following visual requirements:
 * 1. FONT FAMILY: System Sans-Serif (resembling SF Pro Display or Inter).
 * 2. WEIGHT & STYLE: Medium (500) or Regular (400), no italics.
 * 3. COLOR & OPACITY: Soft Off-White (#F5F5F5) with 85% opacity.
 * 4. SPACING: 22sp Font Size, 1.4x (28sp) Line Height, Center-aligned.
 * 5. LAYOUT POSITION: Positioned in the upper 25% of the screen.
 *
 * NOTE: This Compose code is commented out because your project does not currently 
 * have Jetpack Compose enabled in its build.gradle, which caused compilation errors. 
 * If you ever migrate to Compose, simply uncomment the code below and add Compose 
 * dependencies to build.gradle.kts. For now, the styling applies fully via your Android Views!
 */

/*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun BreatheInstructionText(
    modifier: Modifier = Modifier,
    text: String = "Breathe in,\nwe have got you"
) {
    // 85% opacity of #F5F5F5 Off-White
    val offWhite85 = Color(0xFFF5F5F5).copy(alpha = 0.85f)

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Text(
            text = text,
            style = TextStyle(
                fontFamily = FontFamily.SansSerif, // 1. System Sans-Serif
                fontWeight = FontWeight.Medium,    // 2. Medium (500)
                color = offWhite85,                // 3. Off-White with 85% Opacity
                fontSize = 22.sp,                  // 4. 22sp Font Size
                lineHeight = 28.sp,                // 4. 28sp Line Height (1.4x)
                textAlign = TextAlign.Center       // 4. Center-aligned
            ),
            // 5. Positioned in upper 25% of screen (fractionally or using fixed padding based on parent)
            modifier = Modifier.padding(top = 80.dp) // Adjust based on your parent container to reach upper 25%
        )
    }
}
*/

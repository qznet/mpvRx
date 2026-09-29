package app.gyrolet.mpvrx.ui.player.controls.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp

/**
 * Large "position / duration" badge drawn in the middle of the screen while the remote's seek keys
 * (D-pad left/right, rewind / fast-forward) are being used.
 *
 * The text is remembered while the fade-out animation runs so the label does not blank out for a
 * frame when the value is cleared. An empty [text] hides the badge.
 */
@Composable
fun SeekTimeOverlay(
  text: String?,
  modifier: Modifier = Modifier,
) {
  val lastText = remember { mutableStateOf("") }
  if (text != null) lastText.value = text

  AnimatedVisibility(
    visible = text != null,
    enter = fadeIn(tween(100)),
    exit = fadeOut(tween(250)),
    modifier = modifier.fillMaxSize(),
  ) {
    Box(
      modifier = Modifier.fillMaxSize(),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        text = lastText.value,
        color = Color.White,
        fontSize = 32.sp,
        fontWeight = FontWeight.SemiBold,
        fontFamily = FontFamily.Monospace,
        textAlign = TextAlign.Center,
        style =
          TextStyle(
            shadow =
              Shadow(
                color = Color.Black.copy(alpha = 0.8f),
                offset = Offset(0f, 2f),
                blurRadius = 8f,
              ),
          ),
      )
    }
  }
}

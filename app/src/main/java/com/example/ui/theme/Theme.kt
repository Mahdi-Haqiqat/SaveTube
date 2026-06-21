package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme =
  darkColorScheme(
    primary = DarkCleanPrimary,
    onPrimary = DarkCleanOnPrimary,
    secondaryContainer = DarkCleanSecondaryContainer,
    onSecondaryContainer = DarkCleanOnSecondaryContainer,
    background = DarkCleanBackground,
    onBackground = DarkCleanTextMain,
    surface = DarkCleanSurface,
    onSurface = DarkCleanTextMain,
    surfaceVariant = DarkCleanSurfaceVariant,
    onSurfaceVariant = DarkCleanOnSurfaceVariant,
    outline = DarkCleanBorder
  )

private val LightColorScheme =
  lightColorScheme(
    primary = CleanPrimary,
    onPrimary = CleanOnPrimary,
    secondaryContainer = CleanSecondaryContainer,
    onSecondaryContainer = CleanOnSecondaryContainer,
    background = CleanBackground,
    onBackground = CleanTextMain,
    surface = CleanSurface,
    onSurface = CleanTextMain,
    surfaceVariant = CleanSurfaceVariant,
    onSurfaceVariant = CleanOnSurfaceVariant,
    outline = CleanBorder
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  // Set to false by default to ensure Clean Minimalism theme always renders cleanly
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }

      darkTheme -> DarkColorScheme
      else -> LightColorScheme
    }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}

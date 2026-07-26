package me.rerere.rikkahub.ui.hooks

import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * Premium haptic feedback patterns for a tactile, immersive experience.
 * Each pattern is designed to feel distinct and purposeful.
 */
sealed class HapticPattern {
    /** Quick, light tap - for button clicks, selections */
    data object Tick : HapticPattern()
    
    /** Soft pop - for toggles, confirmations */
    data object Pop : HapticPattern()
    
    /** Heavy thud - for drops, important actions */
    data object Thud : HapticPattern()
    
    /** Rising buildup - for long press detection */
    data object Buildup : HapticPattern()
    
    /** Success confirmation - double tap */
    data object Success : HapticPattern()
    
    /** Error feedback - sharp, distinct */
    data object Error : HapticPattern()
    
    /** Drag start - subtle lift feel */
    data object DragStart : HapticPattern()
    
    /** Drag end - settling feel */
    data object DragEnd : HapticPattern()
    
    /** Send message - whoosh feel */
    data object Send : HapticPattern()
    
    /** Scroll edge - subtle resistance */
    data object ScrollEdge : HapticPattern()
    
    /** Selection change - light double tick */
    data object Selection : HapticPattern()
    
    /** Cancel/dismiss - quick fade */
    data object Cancel : HapticPattern()
}

class PremiumHaptics(
    private val hapticFeedback: HapticFeedback,
    private val vibrator: Vibrator?,
    private val enabled: Boolean
) {
    private fun safeVibrate(effect: () -> Unit, fallback: HapticFeedbackType) {
        if (!enabled) return
        try {
            if (vibrator?.hasVibrator() == true) {
                effect()
            } else {
                hapticFeedback.performHapticFeedback(fallback)
            }
        } catch (e: Exception) {
            Log.w("PremiumHaptics", "Vibration failed, using fallback", e)
            try {
                hapticFeedback.performHapticFeedback(fallback)
            } catch (e2: Exception) {
                // Silently fail
            }
        }
    }
    
    fun perform(pattern: HapticPattern) {
        if (!enabled) return
        
        when (pattern) {
            HapticPattern.Tick -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    safeVibrate(
                        { vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)) },
                        HapticFeedbackType.TextHandleMove
                    )
                } else {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            }
            
            HapticPattern.Pop -> {
                // Satisfying pop - click effect
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    safeVibrate(
                        { vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)) },
                        HapticFeedbackType.LongPress
                    )
                } else {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            }
            
            HapticPattern.Thud -> {
                // Heavy satisfying drop
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    safeVibrate(
                        { vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)) },
                        HapticFeedbackType.LongPress
                    )
                } else {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            }
            
            HapticPattern.Buildup -> {
                // Rising intensity
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    safeVibrate(
                        {
                            val effect = VibrationEffect.createWaveform(
                                longArrayOf(0, 20, 30, 40),
                                intArrayOf(0, 80, 150, 255),
                                -1
                            )
                            vibrator?.vibrate(effect)
                        },
                        HapticFeedbackType.LongPress
                    )
                } else {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            }
            
            HapticPattern.Success -> {
                // Double tap celebration
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    safeVibrate(
                        { vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)) },
                        HapticFeedbackType.LongPress
                    )
                } else {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            }
            
            HapticPattern.Error -> {
                // Sharp distinct buzz
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    safeVibrate(
                        {
                            val effect = VibrationEffect.createWaveform(
                                longArrayOf(0, 30, 50, 30),
                                intArrayOf(0, 200, 0, 200),
                                -1
                            )
                            vibrator?.vibrate(effect)
                        },
                        HapticFeedbackType.LongPress
                    )
                } else {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            }
            
            HapticPattern.DragStart -> {
                // Lift - light rising feel
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    safeVibrate(
                        { vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)) },
                        HapticFeedbackType.TextHandleMove
                    )
                } else {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            }
            
            HapticPattern.DragEnd -> {
                // Drop - heavy thud
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    safeVibrate(
                        { vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)) },
                        HapticFeedbackType.LongPress
                    )
                } else {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            }
            
            HapticPattern.Send -> {
                // Whoosh - quick ascending burst
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    safeVibrate(
                        {
                            val effect = VibrationEffect.createOneShot(40, 220)
                            vibrator?.vibrate(effect)
                        },
                        HapticFeedbackType.LongPress
                    )
                } else {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            }
            
            HapticPattern.ScrollEdge -> {
                // Subtle bump
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    safeVibrate(
                        {
                            val effect = VibrationEffect.createOneShot(15, 60)
                            vibrator?.vibrate(effect)
                        },
                        HapticFeedbackType.TextHandleMove
                    )
                } else {
                    // Skip on older devices
                }
            }
            
            HapticPattern.Selection -> {
                // Quick double tick for selection changes
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    safeVibrate(
                        { vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)) },
                        HapticFeedbackType.TextHandleMove
                    )
                } else {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            }
            
            HapticPattern.Cancel -> {
                // Quick fade out feel
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    safeVibrate(
                        {
                            val effect = VibrationEffect.createOneShot(25, 100)
                            vibrator?.vibrate(effect)
                        },
                        HapticFeedbackType.TextHandleMove
                    )
                } else {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            }
        }
    }
}

@Composable
fun rememberPremiumHaptics(enabled: Boolean? = null): PremiumHaptics {
    val context = LocalContext.current
    val hapticFeedback = LocalHapticFeedback.current
    
    // Read enableUIHaptics from settings if not explicitly provided.
    // settingsFlow 是 StateFlow，collectAsState 无需 initial；显式传入 enabled 时完全跳过订阅。
    val isEnabled = if (enabled != null) {
        enabled
    } else {
        val settingsStore = org.koin.compose.koinInject<me.rerere.rikkahub.data.datastore.SettingsStore>()
        val settings by settingsStore.settingsFlow.collectAsState()
        settings.displaySetting.enableUIHaptics
    }
    
    val vibrator = remember {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(VibratorManager::class.java)
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Vibrator::class.java)
            }
        } catch (e: Exception) {
            Log.w("PremiumHaptics", "Failed to get vibrator service", e)
            null
        }
    }
    
    return remember(hapticFeedback, vibrator, isEnabled) {
        PremiumHaptics(hapticFeedback, vibrator, isEnabled)
    }
}



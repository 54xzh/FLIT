package me.rerere.rikkahub.ui.pages.menu

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.repository.ConversationRepository
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.uuid.Uuid

class MenuVM(
    private val conversationRepository: ConversationRepository,
    private val settingsStore: SettingsStore
) : ViewModel() {

    val currentAssistant = settingsStore.settingsFlow
        .map { it.getCurrentAssistant() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val stats: StateFlow<MenuStats> = combine(
        conversationRepository.getConversationCountFlow(),
        conversationRepository.getDailyActivitiesFlow(),
        conversationRepository.getMostActiveAssistantIdFlow(),
        conversationRepository.getEpisodeCountFlow(),
        settingsStore.settingsFlow
    ) { totalChats, activities, mostActiveAssistantId, episodeCount, settings ->
        
        val activityDates = activities.map { it.date }

        // Daily Chat Streak - uses persistent daily activity table
        val streak = calculateStreak(activityDates)

        // Most Active Assistant
        val mostActiveAssistantName = mostActiveAssistantId?.let { id ->
            try {
                settings.assistants.find { it.id == Uuid.parse(id) }?.name
            } catch (e: Exception) {
                null
            }
        } ?: "None"

        val avgMessagesPerDay = if (activities.isNotEmpty()) {
            activities.sumOf { it.messageCount }.toFloat() / activities.size.coerceAtLeast(1)
        } else {
            0f
        }

        MenuStats(
            totalChats = totalChats,
            totalMemories = episodeCount,
            mostActiveAssistantName = mostActiveAssistantName,
            totalAssistants = settings.assistants.size,
            dailyChatStreak = streak,
            avgMessagesPerDay = avgMessagesPerDay
        )
    }
        .flowOn(Dispatchers.Default) // Move calculation off main thread
        .distinctUntilChanged() // Prevent unnecessary recompositions
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = MenuStats()
        )

    private fun calculateStreak(distinctDates: List<String>): Int {
        if (distinctDates.isEmpty()) return 0
        
        val formatter = DateTimeFormatter.ISO_LOCAL_DATE
        val dates = distinctDates.mapNotNull { 
            try { LocalDate.parse(it, formatter) } catch (e: Exception) { null }
        }.sortedDescending()
        
        if (dates.isEmpty()) return 0
        
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)
        
        // Check if streak is active (chatted today or yesterday)
        val startDate = when {
            dates.contains(today) -> today
            dates.contains(yesterday) -> yesterday
            else -> return 0 // Streak broken
        }
        
        var streak = 0
        var current = startDate
        
        while (dates.contains(current)) {
            streak++
            current = current.minusDays(1)
        }
        
        return streak
    }
}

data class MenuStats(
    val totalChats: Int = 0,
    val totalMemories: Int = 0,
    val mostActiveAssistantName: String = "None",
    val totalAssistants: Int = 0,
    val dailyChatStreak: Int = 0,
    val avgMessagesPerDay: Float = 0f
)

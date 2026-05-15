package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.db.dao.ScheduledTaskDao
import me.rerere.rikkahub.data.db.entity.ScheduledTaskEntity
import me.rerere.rikkahub.service.scheduledtask.ScheduledTaskAccuracyMode
import me.rerere.rikkahub.service.scheduledtask.ScheduledTaskIntervalUnit
import me.rerere.rikkahub.service.scheduledtask.ScheduledTaskRepeatType
import me.rerere.rikkahub.service.scheduledtask.ScheduledTaskScheduler
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import java.time.Instant
import kotlin.uuid.Uuid

fun createScheduledTaskTools(
    assistantId: Uuid,
    taskDao: ScheduledTaskDao,
    scheduler: ScheduledTaskScheduler,
): List<Tool> = listOf(
    buildListTool(assistantId, taskDao),
    buildCreateTool(assistantId, taskDao, scheduler),
    buildUpdateTool(assistantId, taskDao, scheduler),
    buildDeleteTool(assistantId, taskDao, scheduler),
)

private fun buildListTool(assistantId: Uuid, taskDao: ScheduledTaskDao) = Tool(
    name = "list_scheduled_tasks",
    description = "List scheduled tasks.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject { })
    },
    systemPrompt = { _, _ -> SCHEDULED_TASK_SYSTEM_PROMPT_TEMPLATE },
    execute = {
        val ids = withContext(Dispatchers.IO) {
            taskDao.getTaskIdsOfAssistant(assistantId.toString())
        }
        val tasks = withContext(Dispatchers.IO) {
            ids.mapNotNull { taskDao.getById(it) }
        }
        buildJsonObject {
            put("tasks", buildJsonArray {
                tasks.forEach { task ->
                    add(buildJsonObject {
                        put("id", task.id)
                        put("name", task.name)
                        put("enabled", task.enabled)
                        put("repeat_type", repeatTypeToString(task.repeatType))
                        put("time_of_day", minutesToTimeString(task.timeOfDayMinutes))
                        put("prompt_template", task.promptTemplate)
                        put("next_run_at", task.nextRunAt?.let {
                            Instant.ofEpochMilli(it).toString()
                        } ?: "")
                    })
                }
            })
        }
    }
)

private fun buildCreateTool(
    assistantId: Uuid,
    taskDao: ScheduledTaskDao,
    scheduler: ScheduledTaskScheduler,
) = Tool(
    name = "create_scheduled_task",
    description = "Create a scheduled task.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("name", buildJsonObject {
                    put("type", "string")
                    put("description", "Task name")
                })
                put("prompt_template", buildJsonObject {
                    put("type", "string")
                    put("description", "The user message sent when the task runs. Supports {date}, {time}, {weekday} placeholders.")
                })
                put("repeat_type", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        add(JsonPrimitive("once"))
                        add(JsonPrimitive("daily"))
                        add(JsonPrimitive("weekly"))
                        add(JsonPrimitive("monthly"))
                        add(JsonPrimitive("interval"))
                    })
                    put("description", "Repeat type of the task")
                })
                put("time_of_day", buildJsonObject {
                    put("type", "string")
                    put("description", "Time of day to run the task (HH:mm, 24h). Required for once/daily/weekly/monthly.")
                })
                put("weekly_days", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun").forEach {
                                add(JsonPrimitive(it))
                            }
                        })
                    })
                    put("description", "Days of week to run (required for weekly)")
                })
                put("monthly_day", buildJsonObject {
                    put("type", "integer")
                    put("description", "Day of month (1-28, or -1 for last day). Required for monthly.")
                })
                put("interval_value", buildJsonObject {
                    put("type", "integer")
                    put("description", "Interval value. Required for interval repeat type.")
                })
                put("interval_unit", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        add(JsonPrimitive("hours"))
                        add(JsonPrimitive("days"))
                    })
                    put("description", "Interval unit. Required for interval repeat type.")
                })
                put("enabled", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether the task is enabled immediately (default true)")
                })
                put("notify_on_done", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Send a notification when the task completes (default true)")
                })
            },
            required = listOf("name", "prompt_template", "repeat_type"),
        )
    },
    requiresUserApproval = true,
    execute = { args ->
        val obj = args.jsonObject
        val name = obj["name"]?.jsonPrimitiveOrNull?.contentOrNull?.trim()
            ?: return@Tool buildJsonObject { put("error", "missing name") }
        val promptTemplate = obj["prompt_template"]?.jsonPrimitiveOrNull?.contentOrNull
            ?: return@Tool buildJsonObject { put("error", "missing prompt_template") }
        val repeatTypeStr = obj["repeat_type"]?.jsonPrimitiveOrNull?.contentOrNull
            ?: return@Tool buildJsonObject { put("error", "missing repeat_type") }
        val repeatType = stringToRepeatType(repeatTypeStr)
            ?: return@Tool buildJsonObject { put("error", "invalid repeat_type: $repeatTypeStr") }

        val timeOfDay = obj["time_of_day"]?.jsonPrimitiveOrNull?.contentOrNull
        val timeOfDayMinutes = when {
            repeatType in listOf(
                ScheduledTaskRepeatType.ONCE,
                ScheduledTaskRepeatType.DAILY,
                ScheduledTaskRepeatType.WEEKLY,
                ScheduledTaskRepeatType.MONTHLY,
            ) -> {
                if (timeOfDay == null) return@Tool buildJsonObject {
                    put("error", "time_of_day is required for $repeatTypeStr")
                }
                parseTimeToMinutes(timeOfDay) ?: return@Tool buildJsonObject {
                    put("error", "invalid time_of_day format, expected HH:mm")
                }
            }
            else -> 0
        }

        val weeklyMask = when (repeatType) {
            ScheduledTaskRepeatType.WEEKLY -> {
                val daysElem = obj["weekly_days"]
                if (daysElem == null || daysElem !is JsonArray || daysElem.isEmpty()) {
                    return@Tool buildJsonObject { put("error", "weekly_days is required for weekly") }
                }
                daysElem.fold(0) { mask, elem ->
                    mask or (dayStringToMask(elem.jsonPrimitive.contentOrNull ?: "")
                        ?: return@Tool buildJsonObject { put("error", "invalid day in weekly_days") })
                }
            }
            else -> 0
        }

        val monthlyDay = when (repeatType) {
            ScheduledTaskRepeatType.MONTHLY -> {
                obj["monthly_day"]?.jsonPrimitiveOrNull?.intOrNull
                    ?: return@Tool buildJsonObject { put("error", "monthly_day is required for monthly") }
            }
            else -> 0
        }

        val intervalValue: Int
        val intervalUnit: Int
        if (repeatType == ScheduledTaskRepeatType.INTERVAL) {
            intervalValue = obj["interval_value"]?.jsonPrimitiveOrNull?.intOrNull
                ?: return@Tool buildJsonObject { put("error", "interval_value is required for interval") }
            val intervalUnitStr = obj["interval_unit"]?.jsonPrimitiveOrNull?.contentOrNull
                ?: return@Tool buildJsonObject { put("error", "interval_unit is required for interval") }
            intervalUnit = when (intervalUnitStr) {
                "hours" -> ScheduledTaskIntervalUnit.HOURS
                "days" -> ScheduledTaskIntervalUnit.DAYS
                else -> return@Tool buildJsonObject { put("error", "invalid interval_unit: $intervalUnitStr") }
            }
        } else {
            intervalValue = 0
            intervalUnit = 0
        }

        val enabled = obj["enabled"]?.jsonPrimitiveOrNull?.contentOrNull?.toBooleanStrictOrNull() ?: true
        val notifyOnDone = obj["notify_on_done"]?.jsonPrimitiveOrNull?.contentOrNull?.toBooleanStrictOrNull() ?: true

        val id = Uuid.random().toString()
        val entity = ScheduledTaskEntity(
            id = id,
            assistantId = assistantId.toString(),
            name = name,
            enabled = enabled,
            promptTemplate = promptTemplate,
            timeOfDayMinutes = timeOfDayMinutes,
            repeatType = repeatType,
            weeklyMask = weeklyMask,
            monthlyDay = monthlyDay,
            intervalValue = intervalValue,
            intervalUnit = intervalUnit,
            accuracyMode = ScheduledTaskAccuracyMode.ECO,
            notifyOnDone = notifyOnDone,
        )
        withContext(Dispatchers.IO) { taskDao.upsert(entity) }
        if (enabled) withContext(Dispatchers.IO) { scheduler.schedule(entity) }

        buildJsonObject {
            put("ok", true)
            put("task_id", id)
        }
    }
)

private fun buildUpdateTool(
    assistantId: Uuid,
    taskDao: ScheduledTaskDao,
    scheduler: ScheduledTaskScheduler,
) = Tool(
    name = "update_scheduled_task",
    description = "Update a scheduled task.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("task_id", buildJsonObject {
                    put("type", "string")
                    put("description", "ID of the task to update")
                })
                put("name", buildJsonObject {
                    put("type", "string")
                    put("description", "New task name")
                })
                put("prompt_template", buildJsonObject {
                    put("type", "string")
                    put("description", "New prompt template.")
                })
                put("repeat_type", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        add(JsonPrimitive("once"))
                        add(JsonPrimitive("daily"))
                        add(JsonPrimitive("weekly"))
                        add(JsonPrimitive("monthly"))
                        add(JsonPrimitive("interval"))
                    })
                })
                put("time_of_day", buildJsonObject {
                    put("type", "string")
                    put("description", "New time of day (HH:mm)")
                })
                put("weekly_days", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun").forEach {
                                add(JsonPrimitive(it))
                            }
                        })
                    })
                })
                put("monthly_day", buildJsonObject {
                    put("type", "integer")
                    put("description", "Day of month (1-28, or -1 for last day)")
                })
                put("interval_value", buildJsonObject {
                    put("type", "integer")
                })
                put("interval_unit", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        add(JsonPrimitive("hours"))
                        add(JsonPrimitive("days"))
                    })
                })
                put("enabled", buildJsonObject {
                    put("type", "boolean")
                })
                put("notify_on_done", buildJsonObject {
                    put("type", "boolean")
                })
            },
            required = listOf("task_id"),
        )
    },
    requiresUserApproval = true,
    execute = { args ->
        val obj = args.jsonObject
        val taskId = obj["task_id"]?.jsonPrimitiveOrNull?.contentOrNull
            ?: return@Tool buildJsonObject { put("error", "missing task_id") }

        val existing = withContext(Dispatchers.IO) { taskDao.getById(taskId) }
            ?: return@Tool buildJsonObject { put("error", "task_not_found") }

        if (existing.assistantId != assistantId.toString()) {
            return@Tool buildJsonObject { put("error", "access_denied") }
        }

        val newRepeatTypeStr = obj["repeat_type"]?.jsonPrimitiveOrNull?.contentOrNull
        val newRepeatType = if (newRepeatTypeStr != null) {
            stringToRepeatType(newRepeatTypeStr)
                ?: return@Tool buildJsonObject { put("error", "invalid repeat_type: $newRepeatTypeStr") }
        } else null

        val effectiveRepeatType = newRepeatType ?: existing.repeatType

        val newTimeOfDayMinutes = obj["time_of_day"]?.jsonPrimitiveOrNull?.contentOrNull?.let {
            parseTimeToMinutes(it) ?: return@Tool buildJsonObject {
                put("error", "invalid time_of_day format, expected HH:mm")
            }
        }

        val newWeeklyMask = obj["weekly_days"]?.let { elem ->
            if (elem !is JsonArray) return@let null
            elem.fold(0) { mask, item ->
                mask or (dayStringToMask(item.jsonPrimitive.contentOrNull ?: "")
                    ?: return@Tool buildJsonObject { put("error", "invalid day in weekly_days") })
            }
        }

        val newMonthlyDay = obj["monthly_day"]?.jsonPrimitiveOrNull?.intOrNull

        val newIntervalValue = obj["interval_value"]?.jsonPrimitiveOrNull?.intOrNull
        val newIntervalUnit = obj["interval_unit"]?.jsonPrimitiveOrNull?.contentOrNull?.let {
            when (it) {
                "hours" -> ScheduledTaskIntervalUnit.HOURS
                "days" -> ScheduledTaskIntervalUnit.DAYS
                else -> return@Tool buildJsonObject { put("error", "invalid interval_unit: $it") }
            }
        }

        val newEnabled = obj["enabled"]?.jsonPrimitiveOrNull?.contentOrNull?.toBooleanStrictOrNull()
        val newNotifyOnDone = obj["notify_on_done"]?.jsonPrimitiveOrNull?.contentOrNull?.toBooleanStrictOrNull()

        val patched = existing.copy(
            name = obj["name"]?.jsonPrimitiveOrNull?.contentOrNull?.trim() ?: existing.name,
            promptTemplate = obj["prompt_template"]?.jsonPrimitiveOrNull?.contentOrNull ?: existing.promptTemplate,
            repeatType = effectiveRepeatType,
            timeOfDayMinutes = newTimeOfDayMinutes ?: existing.timeOfDayMinutes,
            weeklyMask = newWeeklyMask ?: existing.weeklyMask,
            monthlyDay = newMonthlyDay ?: existing.monthlyDay,
            intervalValue = newIntervalValue ?: existing.intervalValue,
            intervalUnit = newIntervalUnit ?: existing.intervalUnit,
            enabled = newEnabled ?: existing.enabled,
            notifyOnDone = newNotifyOnDone ?: existing.notifyOnDone,
        )

        withContext(Dispatchers.IO) { taskDao.upsert(patched) }
        scheduler.cancel(taskId)
        if (patched.enabled) withContext(Dispatchers.IO) { scheduler.schedule(patched) }

        buildJsonObject { put("ok", true) }
    }
)

private fun buildDeleteTool(
    assistantId: Uuid,
    taskDao: ScheduledTaskDao,
    scheduler: ScheduledTaskScheduler,
) = Tool(
    name = "delete_scheduled_task",
    description = "Delete a scheduled task.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("task_id", buildJsonObject {
                    put("type", "string")
                    put("description", "ID of the task to delete")
                })
            },
            required = listOf("task_id"),
        )
    },
    requiresUserApproval = true,
    execute = { args ->
        val obj = args.jsonObject
        val taskId = obj["task_id"]?.jsonPrimitiveOrNull?.contentOrNull
            ?: return@Tool buildJsonObject { put("error", "missing task_id") }

        val existing = withContext(Dispatchers.IO) { taskDao.getById(taskId) }
            ?: return@Tool buildJsonObject { put("error", "task_not_found") }

        if (existing.assistantId != assistantId.toString()) {
            return@Tool buildJsonObject { put("error", "access_denied") }
        }

        scheduler.cancel(taskId)
        withContext(Dispatchers.IO) { taskDao.deleteById(taskId) }

        buildJsonObject { put("ok", true) }
    }
)

// ── Helpers ────────────────────────────────────────────────────────────────────

private fun stringToRepeatType(s: String): Int? = when (s) {
    "once" -> ScheduledTaskRepeatType.ONCE
    "daily" -> ScheduledTaskRepeatType.DAILY
    "weekly" -> ScheduledTaskRepeatType.WEEKLY
    "monthly" -> ScheduledTaskRepeatType.MONTHLY
    "interval" -> ScheduledTaskRepeatType.INTERVAL
    else -> null
}

private fun repeatTypeToString(type: Int): String = when (type) {
    ScheduledTaskRepeatType.ONCE -> "once"
    ScheduledTaskRepeatType.DAILY -> "daily"
    ScheduledTaskRepeatType.WEEKLY -> "weekly"
    ScheduledTaskRepeatType.MONTHLY -> "monthly"
    ScheduledTaskRepeatType.INTERVAL -> "interval"
    else -> "unknown"
}

/** Parse "HH:mm" → total minutes since midnight, or null if invalid. */
private fun parseTimeToMinutes(timeStr: String): Int? {
    val parts = timeStr.split(":")
    if (parts.size != 2) return null
    val hour = parts[0].toIntOrNull() ?: return null
    val minute = parts[1].toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59) return null
    return hour * 60 + minute
}

/** Convert total minutes since midnight → "HH:mm". */
private fun minutesToTimeString(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return "%02d:%02d".format(h, m)
}

/**
 * Convert a weekday abbreviation to its bitmask.
 * Bit position = DayOfWeek.value - 1 (Monday=1 → bit 0, Sunday=7 → bit 6).
 */
private fun dayStringToMask(day: String): Int? = when (day) {
    "mon" -> 1 shl 0
    "tue" -> 1 shl 1
    "wed" -> 1 shl 2
    "thu" -> 1 shl 3
    "fri" -> 1 shl 4
    "sat" -> 1 shl 5
    "sun" -> 1 shl 6
    else -> null
}

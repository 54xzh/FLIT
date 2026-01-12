package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import com.whl.quickjs.wrapper.QuickJSContext
import com.whl.quickjs.wrapper.QuickJSObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.model.Skill
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import java.io.File
import java.util.Locale
import kotlin.uuid.Uuid

@Serializable
sealed class LocalToolOption {
    @Serializable
    @SerialName("javascript_engine")
    data object JavascriptEngine : LocalToolOption()

    @Serializable
    @SerialName("python_engine")
    data object PythonEngine : LocalToolOption()

    @Serializable
    @SerialName("device_control")
    data object DeviceControl : LocalToolOption()

    @Serializable
    @SerialName("workspace_files")
    data object WorkspaceFiles : LocalToolOption()
}

class LocalTools(private val context: Context) {
    val javascriptTool by lazy {
        Tool(
            name = "eval_javascript",
            description = "Execute JavaScript code with QuickJS. If use this tool to calculate math, better to add `toFixed` to the code.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("code", buildJsonObject {
                            put("type", "string")
                            put("description", "The JavaScript code to execute")
                        })
                    },
                )
            },
            execute = {
                val context = QuickJSContext.create()
                val code = it.jsonObject["code"]?.jsonPrimitive?.contentOrNull
                val result = context.evaluate(code)
                buildJsonObject {
                    put(
                        "result", when (result) {
                            is QuickJSObject -> JsonPrimitive(result.stringify())
                            else -> JsonPrimitive(result.toString())
                        }
                    )
                }
            }
        )
    }

    fun getDeviceControlTools(assistantId: Uuid, conversationId: Uuid): List<Tool> {
        return listOf(
            Tool(
                name = "send_notification",
                description = "Send a notification to the user",
                parameters = {
                    InputSchema.Obj(
                        properties = buildJsonObject {
                            put("title", buildJsonObject {
                                put("type", "string")
                                put("description", "Notification title")
                            })
                            put("content", buildJsonObject {
                                put("type", "string")
                                put("description", "Notification content")
                            })
                        },
                        required = listOf("title", "content")
                    )
                },
                execute = {
                    val title = it.jsonObject["title"]?.jsonPrimitive?.contentOrNull ?: "Notification"
                    val content = it.jsonObject["content"]?.jsonPrimitive?.contentOrNull ?: ""
                    
                    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                    val channelId = "assistant_notification"
                    val channel = android.app.NotificationChannel(
                        channelId,
                        "Assistant Notification",
                        android.app.NotificationManager.IMPORTANCE_DEFAULT
                    )
                    notificationManager.createNotificationChannel(channel)
                    
                    // Create pending intent to open the conversation when notification is clicked
                    val intent = android.content.Intent(context, me.rerere.rikkahub.RouteActivity::class.java).apply {
                        flags = android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                        putExtra("conversationId", conversationId.toString())
                    }
                    val pendingIntent = android.app.PendingIntent.getActivity(
                        context,
                        conversationId.hashCode(),
                        intent,
                        android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                    )

                    val notification = androidx.core.app.NotificationCompat.Builder(context, channelId)
                        .setSmallIcon(me.rerere.rikkahub.R.drawable.ic_notification)
                        .setContentTitle(title)
                        .setContentText(content)
                        .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
                        .setContentIntent(pendingIntent)
                        .setAutoCancel(true)
                        .build()
                        
                    if (androidx.core.app.ActivityCompat.checkSelfPermission(
                            context,
                            android.Manifest.permission.POST_NOTIFICATIONS
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
                        buildJsonObject { put("status", "success") }
                    } else {
                        buildJsonObject { put("status", "error: permission denied") }
                    }
                }
            ),
            Tool(
                name = "schedule_message",
                description = "Schedule a message to be sent by the assistant after a certain delay.",
                parameters = {
                    InputSchema.Obj(
                        properties = buildJsonObject {
                            put("reason", buildJsonObject {
                                put("type", "string")
                                put("description", "The reason for scheduling this message (e.g., 'Remind user to drink water')")
                            })
                            put("delay_minutes", buildJsonObject {
                                put("type", "integer")
                                put("description", "Delay in minutes before sending the message")
                            })
                        },
                        required = listOf("reason", "delay_minutes")
                    )
                },
                execute = {
                    val reason = it.jsonObject["reason"]?.jsonPrimitive?.contentOrNull ?: ""
                    val delayMinutes = it.jsonObject["delay_minutes"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 1L
                    
                    try {
                        val currentTime = System.currentTimeMillis()
                        val targetTime = currentTime + (delayMinutes * 60 * 1000)
                        
                        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                        
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                            if (!alarmManager.canScheduleExactAlarms()) {
                                    buildJsonObject { put("status", "error: permission SCHEDULE_EXACT_ALARM not granted") }
                            }
                        }

                        val intent = android.content.Intent(context, me.rerere.rikkahub.service.ScheduledMessageReceiver::class.java).apply {
                            putExtra("assistantId", assistantId.toString())
                            putExtra("conversationId", conversationId.toString())
                            putExtra("reason", reason)
                        }
                        
                        val pendingIntent = android.app.PendingIntent.getBroadcast(
                            context,
                            (assistantId.hashCode() + conversationId.hashCode() + reason.hashCode()),
                            intent,
                            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                        )
                        
                        alarmManager.setExactAndAllowWhileIdle(
                            android.app.AlarmManager.RTC_WAKEUP,
                            targetTime,
                            pendingIntent
                        )
                        
                        buildJsonObject { 
                            put("status", "success")
                            put("scheduled_at", java.time.Instant.ofEpochMilli(targetTime).toString())
                        }
                    } catch (e: Exception) {
                        buildJsonObject { put("status", "error: ${e.message}") }
                    }
                }
            ),
            Tool(
                name = "get_notifications",
                description = "Get recent notifications from the device",
                parameters = {
                    InputSchema.Obj(
                        properties = buildJsonObject {
                            put("limit", buildJsonObject {
                                put("type", "integer")
                                put("description", "Max number of notifications to retrieve (default 10)")
                            })
                        }
                    )
                },
                execute = {
                    val limit = it.jsonObject["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 10
                    val notifications = me.rerere.rikkahub.service.AssistantNotificationListener.notifications.value.take(limit)
                    
                    buildJsonObject {
                        put("notifications", kotlinx.serialization.json.JsonArray(notifications.map { notification ->
                            buildJsonObject {
                                put("package", notification.packageName)
                                put("title", notification.title)
                                put("content", notification.content)
                                put("time", notification.postTime)
                            }
                        }))
                    }
                }
            ),
            Tool(
                name = "open_app",
                description = "Open an application by package name",
                parameters = {
                    InputSchema.Obj(
                        properties = buildJsonObject {
                            put("package_name", buildJsonObject {
                                put("type", "string")
                                put("description", "Package name of the app to open")
                            })
                        },
                        required = listOf("package_name")
                    )
                },
                execute = {
                    val packageName = it.jsonObject["package_name"]?.jsonPrimitive?.contentOrNull ?: ""
                    val pm = context.packageManager
                    try {
                        val intent = pm.getLaunchIntentForPackage(packageName)
                        if (intent != null) {
                            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                            buildJsonObject { put("status", "success") }
                        } else {
                            buildJsonObject { put("status", "error: app not found") }
                        }
                    } catch (e: Exception) {
                        buildJsonObject { put("status", "error: ${e.message}") }
                    }
                }
            ),
            Tool(
                name = "set_alarm",
                description = "Set an alarm at a specific time",
                parameters = {
                    InputSchema.Obj(
                        properties = buildJsonObject {
                            put("hour", buildJsonObject {
                                put("type", "integer")
                                put("description", "Hour (0-23)")
                            })
                            put("minute", buildJsonObject {
                                put("type", "integer")
                                put("description", "Minute (0-59)")
                            })
                            put("message", buildJsonObject {
                                put("type", "string")
                                put("description", "Alarm label/message")
                            })
                        },
                        required = listOf("hour", "minute")
                    )
                },
                execute = {
                    val hour = it.jsonObject["hour"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                    val minute = it.jsonObject["minute"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                    val message = it.jsonObject["message"]?.jsonPrimitive?.contentOrNull ?: "Alarm"
                    
                    try {
                        val intent = android.content.Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
                            putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour)
                            putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute)
                            putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, message)
                            putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, false)
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                        buildJsonObject { 
                            put("status", "success")
                            put("time", "$hour:${minute.toString().padStart(2, '0')}")
                        }
                    } catch (e: Exception) {
                        buildJsonObject { put("status", "error: ${e.message}") }
                    }
                }
            ),
            Tool(
                name = "set_reminder",
                description = "Create a reminder/task",
                parameters = {
                    InputSchema.Obj(
                        properties = buildJsonObject {
                            put("title", buildJsonObject {
                                put("type", "string")
                                put("description", "Reminder title")
                            })
                            put("description", buildJsonObject {
                                put("type", "string")
                                put("description", "Reminder description")
                            })
                            put("time_millis", buildJsonObject {
                                put("type", "integer")
                                put("description", "Time in milliseconds since epoch (optional)")
                            })
                        },
                        required = listOf("title")
                    )
                },
                execute = {
                    val title = it.jsonObject["title"]?.jsonPrimitive?.contentOrNull ?: "Reminder"
                    val description = it.jsonObject["description"]?.jsonPrimitive?.contentOrNull ?: ""
                    val timeMillis = it.jsonObject["time_millis"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                    
                    try {
                        // Try to use Calendar/Tasks app
                        val intent = android.content.Intent(android.content.Intent.ACTION_INSERT).apply {
                            data = android.provider.CalendarContract.Events.CONTENT_URI
                            putExtra(android.provider.CalendarContract.Events.TITLE, title)
                            putExtra(android.provider.CalendarContract.Events.DESCRIPTION, description)
                            if (timeMillis != null) {
                                putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, timeMillis)
                                putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, timeMillis + 3600000) // 1 hour duration
                            }
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                        buildJsonObject { put("status", "success") }
                    } catch (e: Exception) {
                        buildJsonObject { put("status", "error: ${e.message}") }
                    }
                }
            )
        )
    }

    fun createSkillFileTool(allowedSkills: List<Skill>): Tool {
        val allowedSkillIds = allowedSkills.map { it.id.toString() }.toSet()
        val allowedSkillsById = allowedSkills.associateBy { it.id.toString() }
        val allowedSkillsByName = allowedSkills.groupBy { it.name.trim().lowercase(Locale.ROOT) }
        return Tool(
            name = "read_skill_file",
            description = "Read a file from an installed Skill package (e.g., SKILL.md or referenced files).",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("skill_name", buildJsonObject {
                            put("type", "string")
                            put("description", "Skill name from the available skills list (preferred). If duplicated, also pass skill_id to disambiguate.")
                        })
                        put("skill_id", buildJsonObject {
                            put("type", "string")
                            put("description", "Skill id (UUID) from the available skills list (for disambiguation / backward compatibility)")
                        })
                        put("path", buildJsonObject {
                            put("type", "string")
                            put("description", "Relative path inside the skill folder (default: SKILL.md)")
                        })
                        put("max_chars", buildJsonObject {
                            put("type", "integer")
                            put("description", "Maximum characters to return (default: 20000)")
                        })
                    },
                    required = listOf("skill_name"),
                )
            },
            systemPrompt = { _, _ ->
                if (allowedSkills.isEmpty()) return@Tool ""
                buildString {
                    appendLine("You can load local Skill packages on demand via the tool `read_skill_file`.")
                    appendLine("Call with `skill_name` (preferred). If multiple skills share the same name, disambiguate with `skill_id`.")
                    appendLine("Rules:")
                    appendLine("- Always load a skill's SKILL.md before using it.")
                    appendLine("- Never invent skill contents; use the tool.")
                    appendLine("Available skills:")
                    allowedSkills.forEach { skill ->
                        append("- name: ")
                        append(skill.name)
                        append(" | id: ")
                        append(skill.id.toString())
                        if (skill.description.isNotBlank()) {
                            append(" | desc: ")
                            append(skill.description.replace('\n', ' ').trim())
                        }
                        appendLine()
                    }
                }
            },
            execute = { args ->
                val obj = args.jsonObject
                val skillNameRaw = obj["skill_name"]?.jsonPrimitiveOrNull?.contentOrNull?.trim()
                val skillIdRaw = obj["skill_id"]?.jsonPrimitiveOrNull?.contentOrNull?.trim()

                val resolvedSkill = when {
                    !skillIdRaw.isNullOrBlank() -> {
                        if (skillIdRaw !in allowedSkillIds) {
                            return@Tool buildJsonObject { put("error", "Skill not allowed: $skillIdRaw") }
                        }
                        allowedSkillsById[skillIdRaw]
                    }

                    !skillNameRaw.isNullOrBlank() -> {
                        val candidates = allowedSkillsByName[skillNameRaw.lowercase(Locale.ROOT)].orEmpty()
                        when {
                            candidates.isEmpty() -> {
                                buildJsonObject {
                                    put("error", "Skill not allowed: $skillNameRaw")
                                }.let { return@Tool it }
                            }

                            candidates.size > 1 -> {
                                buildJsonObject {
                                    put("error", "Ambiguous skill_name: $skillNameRaw")
                                    put("candidates", buildJsonArray {
                                        candidates.forEach { skill ->
                                            add(buildJsonObject {
                                                put("id", skill.id.toString())
                                                put("name", skill.name)
                                            })
                                        }
                                    })
                                }.let { return@Tool it }
                            }

                            else -> candidates.single()
                        }
                    }

                    else -> null
                }

                if (resolvedSkill == null) {
                    return@Tool buildJsonObject { put("error", "Missing skill_name") }
                }

                val resolvedSkillId = resolvedSkill.id.toString()

                val pathRaw = obj["path"]?.jsonPrimitiveOrNull?.contentOrNull?.trim().orEmpty()
                val relativePath = if (pathRaw.isBlank()) "SKILL.md" else pathRaw

                val maxChars = obj["max_chars"]?.jsonPrimitiveOrNull?.intOrNull?.coerceIn(1, 200_000) ?: 20_000

                val skillRoot = File(context.filesDir, "skills/$resolvedSkillId")
                val target = safeResolve(skillRoot, relativePath)
                    ?: return@Tool buildJsonObject { put("error", "Invalid path") }

                if (!target.exists() || !target.isFile) {
                    return@Tool buildJsonObject { put("error", "File not found: $relativePath") }
                }

                val text = try {
                    withContext(Dispatchers.IO) {
                        target.readText(Charsets.UTF_8)
                    }
                } catch (e: Exception) {
                    return@Tool buildJsonObject { put("error", "Failed to read file: ${e.message}") }
                }

                val truncated = text.length > maxChars
                val content = if (truncated) text.take(maxChars) else text

                buildJsonObject {
                    put("ok", true)
                    put("skill_id", resolvedSkillId)
                    put("skill_name", resolvedSkill.name)
                    put("path", relativePath)
                    put("truncated", truncated)
                    put("content", content)
                }
            }
        )
    }

    private fun safeResolve(rootDir: File, relativePath: String): File? {
        val normalized = relativePath.replace('\\', '/')
        if (normalized.startsWith("/")) return null
        val file = File(rootDir, normalized)
        return runCatching {
            val rootPath = rootDir.canonicalFile.toPath()
            val filePath = file.canonicalFile.toPath()
            if (filePath.startsWith(rootPath)) file else null
        }.getOrNull()
    }

    fun getTools(options: List<LocalToolOption>, assistantId: Uuid, conversationId: Uuid): List<Tool> {
        val tools = mutableListOf<Tool>()
        if (options.contains(LocalToolOption.JavascriptEngine)) {
            tools.add(javascriptTool)
        }
        if (options.contains(LocalToolOption.DeviceControl)) {
            tools.addAll(getDeviceControlTools(assistantId, conversationId))
        }
        return tools
    }
}

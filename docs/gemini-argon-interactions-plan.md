# Gemini 4 Argon 摘要与 Gemini 搜索步骤适配

调研与实施日期：2026-10-15。本文按用户后续要求收回额外 UI，记录最终范围。

## 1. 范围

- 支持 Argon 在 generateContent 和 Interactions API 中返回并展示推理摘要。
- 支持 Gemini 3、4 在 Interactions API 下的内置搜索步骤，复用已有 Codex 搜索时间线。
- 搜索事件到达时立即更新步骤，不额外等待结果或 interaction.completed。只有服务端提前返回搜索事件时，才能在正文生成前显示真实搜索进度。
- 保留统一推理选择器。模型参数的合法转换留在接口层，不增加模型专用 UI。
- 不增加 Interactions 开关说明、搜索设置入口或 Google 搜索推荐组件。

## 2. 文档确认的请求参数

| 接口 | 启用摘要 | Argon 自动等级 |
| --- | --- | --- |
| generateContent | generationConfig.thinkingConfig.includeThoughts: true | thinkingLevel: medium |
| Interactions API | generation_config.thinking_summaries: auto | thinking_level: medium |

Argon 支持 low、medium、high。接口层将旧 OFF 设置转换为 low，更高等级转换为 high；界面保留原有统一选择方式。Gemini 2.5 的预算处理保持原有行为，用户已有的能力开关与自定义请求配置也继续生效。

依据：[generateContent 思考文档](https://ai.google.dev/gemini-api/docs/generate-content/thinking)、[Interactions 思考文档](https://ai.google.dev/gemini-api/docs/thinking)、[Interactions 接口字段](https://ai.google.dev/api/interactions-api)。

## 3. 响应和续聊

- 按请求及步骤序号累计首包摘要、摘要增量和签名；停止事件结束对应步骤，完整快照不重复追加正文或摘要。
- 搜索调用 id 和结果 call_id 对应同一条可更新步骤，显示搜索词、进行中、完成或失败状态。
- 正文注释和 text_annotation_delta 中的来源继续使用现有来源展示。
- 保存原始带签名步骤，显示合并后仍按原顺序回传完整步骤；快照版本用于避免媒体或其他部件中较旧的元数据覆盖最新输出。
- 新 interactionSteps 与旧 interactionStep 兼容，取消或连接中断时为未结束搜索收尾。
- 搜索推荐不渲染。服务端原始结果仍用于带签名步骤的续聊回传。

依据：[Google Search 文档](https://ai.google.dev/gemini-api/docs/google-search)、[Interactions 接口字段](https://ai.google.dev/api/interactions-api)。

## 4. 实际响应中的延迟和客户端修复

用户提供的官方 Agent Platform、gemini-3.8-flash 响应记录如下：

| index | 事件 | 到达顺序 |
| --- | --- | --- |
| 0 | thought 停止 | 先结束推理 |
| 1 | model_output 的正文增量和停止 | 完整正文已发送 |
| 2 | google_search_call 的开始、四条 queries、停止 | 正文结束后才返回搜索信息 |
| 3 | google_search_result 和停止 | 更新对应搜索调用 |
| 4 | 仅包含 text_annotation_delta 的 model_output | 补充正文来源 |

因此，这次请求不能在正文生成前展示真实搜索词或搜索执行状态：服务端尚未发送相关事件。客户端将结束后的搜索块移到顶部，并不等于实时搜索。本次查阅 Agent Platform 请求字段和开发指南，未找到可强制提前发送内置搜索步骤的公开参数。当前验证使用用户日志和本地流式回放。

客户端处理如下：

- 撤销统一将所有 Gemini 过程项挪到顶部的处理，按各阶段到达的位置显示。
- 正文按显示阶段累加，避免搜索前后的文字都合并进第一块。搜索之后继续返回同一 thought 序号的摘要时，也另起一段。
- 一个搜索调用包含四条查询时，复用 Codex 的时间线行逐条显示；不再只展示第一条及“还有三条”。这些行共用该调用的完成或失败状态，接口没有提供各查询独立的耗时和状态。
- 搜索调用的开始事件立即显示进行中；结果、签名和停止事件更新该调用，保留多个调用各自的位置。
- 原始调用和结果仍保持完整，用于续聊。独立输出中的来源注释保存并继续使用现有来源展示，不生成空正文块。

已撤回此前添加的 Argon 专用推理 UI、Interactions 说明文案、额外提供商设置入口、Google 搜索推荐组件及其资源。

依据：[Agent Platform 接口字段](https://docs.cloud.google.com/gemini-enterprise-agent-platform/reference/models/interactions-api)、[Agent Platform 开发指南](https://docs.cloud.google.com/gemini-enterprise-agent-platform/models/capabilities/interactions/developer-guide)、[Gemini 流式步骤示例](https://ai.google.dev/gemini-api/docs/streaming)。

## 5. 验证

通过实际 OkHttp SSE 读取器分批送入响应事件，再经过消息合并、界面更新限速和聊天显示计划检查。测试在尚未发送最终完成事件且连接保持打开时，验证搜索开始已显示、四条查询可见、结果更新原行、后续摘要另起步骤，以及不同搜索调用各自保留。另按用户日志中的延后搜索顺序回放，确认客户端不会提前构造尚未收到的搜索事件。

同时验证分阶段正文和来源注释不会丢失，续聊只回传每个原始步骤一次。157 项相关回归通过：接口与消息处理 132 项，聊天显示和实际 SSE 流式回放 25 项。测试由 Gradle 编译后通过 JUnit 直接运行，以避开本机 Gradle 测试执行进程的连接错误。发布构建 `:app:assembleExpRelease` 及发布 Lint 检查通过，已生成新安装包。以上属于本地流式回放，没有进行真实 Google 请求或真机联调。

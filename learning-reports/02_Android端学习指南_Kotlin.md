# 02 — Android 端学习指南 (Kotlin)

> 适合 Android 开发者，了解 Kotlin 端架构和核心代码
> 基于当前代码重新生成：2026-06-21

---

## 一、学习路径

```
入门: AICompanionApp.kt → MainActivity.kt → ChatViewModel.kt
进阶: ChatAdapter.kt → ConversationSessionManager.kt → VoiceController.kt
高级: 自定义UI组件 → 插件系统 → 语音引擎 → 性能优化
```

---

## 二、Application 入口

### [AICompanionApp.kt](file:///f:/Trae%20AI/ai-APP/android/app/src/main/java/com/aicompanion/app/AICompanionApp.kt)

**关键点**：
- 继承 `PyApplication`（必须！否则 Chaquopy 初始化失败）
- 初始化顺序：CrashHandler → PluginRegistry → 后台任务（DeviceAdaptation / NotificationHelper）
- Python 引擎在后台线程预热（`warmUpPython()`），减少首次对话等待
- `onTrimMemory()` 分级处理内存压力，TRIM_MEMORY_RUNNING_CRITICAL 时触发 Python GC
- 预热时调用 `set_build_type()` 根据 `BuildConfig.DEBUG` 显式设置日志级别

---

## 三、聊天主界面

### [MainActivity.kt](file:///f:/Trae%20AI/ai-APP/android/app/src/main/java/com/aicompanion/app/MainActivity.kt)

**职责拆分**（3 个协调器）：

| 协调器 | 文件 | 职责 |
|--------|------|------|
| `ChatViewModel` | [ChatViewModel.kt](file:///f:/Trae%20AI/ai-APP/android/app/src/main/java/com/aicompanion/app/ChatViewModel.kt) | 消息管理、流式对话、搜索、导出 |
| `VoiceController` | [VoiceController.kt](file:///f:/Trae%20AI/ai-APP/android/app/src/main/java/com/aicompanion/app/VoiceController.kt) | 语音识别、录制、播放、TTS |
| `ConversationCoordinator` | [ConversationCoordinator.kt](file:///f:/Trae%20AI/ai-APP/android/app/src/main/java/com/aicompanion/app/ConversationCoordinator.kt) | 多会话切换、保存/加载 |

**初始化流程**：
```
MainActivity.onCreate()
  ├─→ ViewUtils.setupEdgeToEdge()        # 沉浸式状态栏
  ├─→ ChatAdapter (RecyclerView)          # 消息列表
  ├─→ ChatViewModel                       # 聊天逻辑
  ├─→ VoiceController                     # 语音模块
  ├─→ ConversationCoordinator             # 多会话
  ├─→ initChatEngine()                    # 初始化 Python 聊天引擎
  └─→ loadConversation()                  # 加载上次会话
```

---

## 四、ChatViewModel — 聊天核心逻辑

### [ChatViewModel.kt](file:///f:/Trae%20AI/ai-APP/android/app/src/main/java/com/aicompanion/app/ChatViewModel.kt)

**核心方法**：

| 方法 | 功能 |
|------|------|
| `sendMessage(text)` | 发送消息，启动流式对话 |
| `sendMessageStream()` | 轮询 Python 端的流式 token |
| `initChatEngine()` | 初始化 Python 聊天引擎 |
| `searchConversation()` | 搜索对话历史 |
| `exportConversation()` | 导出对话 |
| `destroy()` | 清理资源（取消流+关闭线程池） |

**流式对话的核心机制**：

```
sendMessage(text)
  └─→ withContext(Dispatchers.IO) {
        // 1. 启动 Python 后台线程生成 token
        streamId = python.callAttr("chat_stream_start", msg)
        // 2. 轮询取 token (30ms 间隔)
        while (true) {
          delay(30)
          result = python.callAttr("chat_stream_poll", streamId)
          // 3. 解析 JSON → 更新 UI
          withContext(Dispatchers.Main) {
            adapter.updateLastMessage(token)
          }
        }
      }
```

**注意**：`ChatViewModel` 是普通类，不继承 `androidx.lifecycle.ViewModel`。在配置变更时会重建。

---

## 五、语音系统

### [VoiceController.kt](file:///f:/Trae%20AI/ai-APP/android/app/src/main/java/com/aicompanion/app/VoiceController.kt)

语音总控制器，管理语音识别、录制、播放、TTS。

### 语音模块文件

| 文件 | 功能 |
|------|------|
| [SpeechManager.kt](file:///f:/Trae%20AI/ai-APP/android/app/src/main/java/com/aicompanion/app/speech/SpeechManager.kt) | 语音识别管理 |
| [SherpaTtsEngine.kt](file:///f:/Trae%20AI/ai-APP/android/app/src/main/java/com/aicompanion/app/speech/SherpaTtsEngine.kt) | Sherpa-ONNX TTS 引擎 |
| [VoiceRecorder.kt](file:///f:/Trae%20AI/ai-APP/android/app/src/main/java/com/aicompanion/app/speech/VoiceRecorder.kt) | 录音器 |
| [VoicePlayer.kt](file:///f:/Trae%20AI/ai-APP/android/app/src/main/java/com/aicompanion/app/speech/VoicePlayer.kt) | 播放器 |

**TTS 模型**：`vocos-22khz-univ.onnx` (ONNX 格式，约50MB)，位于 `assets/` 目录。

---

## 六、加密配置管理

### [AppConfig.kt](file:///f:/Trae%20AI/ai-APP/android/app/src/main/java/com/aicompanion/app/AppConfig.kt)

**安全设计**：
- API Key 使用 `EncryptedSharedPreferences` + `AES256_GCM` 加密存储
- 非敏感配置（Token预设、温度等）使用普通 SharedPreferences
- 双重检查锁定缓存加密实例

**配置项**：

| 配置 | 存储方式 | 默认值 |
|------|----------|--------|
| API Key | 加密 | 空 |
| Token 预设 | 明文 | "balanced" |
| 模型 | 明文 | 空 |
| 温度 | 明文 | 0.7 |
| 最大 Tokens | 明文 | 1000 |
| 上下文大小 | 明文 | 2000 |
| TTS 语速 | 明文 | 1.0 |
| 主动消息间隔 | 明文 | 3小时 |

---

## 七、消息数据模型

### [Message.kt](file:///f:/Trae%20AI/ai-APP/android/app/src/main/java/com/aicompanion/app/Message.kt)

```kotlin
data class Message(
    val id: String,           // UUID
    val role: String,         // "user" / "assistant" / "system"
    val content: String,      // 消息内容
    val timestamp: Long,      // 时间戳
    val status: MessageStatus, // SENDING / STREAMING / COMPLETED / ERROR
    val msgType: MsgType,     // TEXT / VOICE / IMAGE
    val voicePath: String?,   // 语音文件路径
    val voiceDuration: Long?, // 语音时长
    val tokenCount: Int,      // Token 计数
    val editHistory: List<EditRecord>? // 编辑历史
)
```

---

## 八、自定义 UI 组件

### 玻璃态组件

| 组件 | 文件 | 说明 |
|------|------|------|
| `GlassCard` | [GlassCard.kt](file:///f:/Trae%20AI/ai-APP/android/app/src/main/java/com/aicompanion/app/ui/GlassCard.kt) | 半透明毛玻璃卡片 |
| `GlassDialog` | [GlassDialog.kt](file:///f:/Trae%20AI/ai-APP/android/app/src/main/java/com/aicompanion/app/ui/GlassDialog.kt) | 玻璃态对话框 |

### 动画组件

| 组件 | 文件 | 说明 |
|------|------|------|
| `ParticleView` | [ParticleView.kt](file:///f:/Trae%20AI/ai-APP/android/app/src/main/java/com/aicompanion/app/ui/ParticleView.kt) | 粒子动画背景 |
| `PetalView` | [PetalView.kt](file:///f:/Trae%20AI/ai-APP/android/app/src/main/java/com/aicompanion/app/ui/PetalView.kt) | 花瓣飘落动画 |
| `MessageItemAnimator` | [MessageItemAnimator.kt](file:///f:/Trae%20AI/ai-APP/android/app/src/main/java/com/aicompanion/app/MessageItemAnimator.kt) | 消息入场动画 |

### 工具类

| 类 | 文件 | 说明 |
|----|------|------|
| `BlurUtils` | [BlurUtils.kt](file:///f:/Trae%20AI/ai-APP/android/app/src/main/java/com/aicompanion/app/ui/BlurUtils.kt) | RenderScript 模糊 |
| `ViewUtils` | [ViewUtils.kt](file:///f:/Trae%20AI/ai-APP/android/app/src/main/java/com/aicompanion/app/ViewUtils.kt) | EdgeToEdge、Insets |
| `StateViewHelper` | [StateViewHelper.kt](file:///f:/Trae%20AI/ai-APP/android/app/src/main/java/com/aicompanion/app/StateViewHelper.kt) | 加载/空/错误状态 |
| `DeviceAdaptationHelper` | [DeviceAdaptationHelper.kt](file:///f:/Trae%20AI/ai-APP/android/app/src/main/java/com/aicompanion/app/DeviceAdaptationHelper.kt) | 品牌设备适配 |
| `ActivityTransitionHelper` | [ActivityTransitionHelper.kt](file:///f:/Trae%20AI/ai-APP/android/app/src/main/java/com/aicompanion/app/ActivityTransitionHelper.kt) | 共享元素转场 |

---

## 九、插件系统

### 插件架构

```
IPlugin (接口)
  └─→ PluginInfo (数据模型)
        └─→ PluginRegistry (注册中心)
              └─→ BuiltinPlugins (内置插件注册)
                    └─→ PluginAdapter (列表适配器)
```

### 关键文件

| 文件 | 功能 |
|------|------|
| [IPlugin.kt](file:///f:/Trae%20AI/ai-APP/android/app/src/main/java/com/aicompanion/app/plugin/IPlugin.kt) | 插件接口定义 |
| [PluginInfo.kt](file:///f:/Trae%20AI/ai-APP/android/app/src/main/java/com/aicompanion/app/plugin/PluginInfo.kt) | 插件元数据 |
| [PluginRegistry.kt](file:///f:/Trae%20AI/ai-APP/android/app/src/main/java/com/aicompanion/app/plugin/PluginRegistry.kt) | 插件注册中心 |
| [BuiltinPlugins.kt](file:///f:/Trae%20AI/ai-APP/android/app/src/main/java/com/aicompanion/app/plugin/BuiltinPlugins.kt) | 内置插件（日志、翻译等） |
| [PluginAdapter.kt](file:///f:/Trae%20AI/ai-APP/android/app/src/main/java/com/aicompanion/app/PluginAdapter.kt) | 插件列表适配器 |
| [PluginItem.kt](file:///f:/Trae%20AI/ai-APP/android/app/src/main/java/com/aicompanion/app/PluginItem.kt) | 插件项数据模型 |
| [PluginViewModel.kt](file:///f:/Trae%20AI/ai-APP/android/app/src/main/java/com/aicompanion/app/PluginViewModel.kt) | 插件状态管理 |

---

## 十、世界书

### 关键文件

| 文件 | 功能 |
|------|------|
| [WorldBookActivity.kt](file:///f:/Trae%20AI/ai-APP/android/app/src/main/java/com/aicompanion/app/WorldBookActivity.kt) | 世界书管理界面 |
| [WorldBookAdapter.kt](file:///f:/Trae%20AI/ai-APP/android/app/src/main/java/com/aicompanion/app/WorldBookAdapter.kt) | 世界书列表适配器 |
| [WorldBookEntry.kt](file:///f:/Trae%20AI/ai-APP/android/app/src/main/java/com/aicompanion/app/WorldBookEntry.kt) | 世界书条目模型 |
| [WorldBookSection.kt](file:///f:/Trae%20AI/ai-APP/android/app/src/main/java/com/aicompanion/app/WorldBookSection.kt) | 世界书分组模型 |

---

## 十一、崩溃处理

### [CrashHandler.kt](file:///f:/Trae%20AI/ai-APP/android/app/src/main/java/com/aicompanion/app/CrashHandler.kt)

- 捕获未处理异常，写入崩溃日志文件
- 下次启动时通过 `AICompanionApp.checkAndShowCrashLogDialog()` 弹窗提示
- 崩溃日志文件存储在应用私有目录，文件名含时间戳

---

## 十二、关键学习要点

1. **PyApplication 继承**：Chaquopy 要求 Application 继承 PyApplication
2. **Python 调用必须在后台线程**：`chat_stream_start()` 内部使用 `ThreadPoolExecutor`
3. **流式对话的轮询模式**：30ms 间隔轮询 Python 队列
4. **EncryptedSharedPreferences**：API Key 必须加密存储
5. **协程生命周期管理**：使用 `lifecycleScope` 和 `SupervisorJob`
6. **内存管理**：`onTrimMemory()` 触发 Python GC，`destroy()` 清理流式资源
7. **configChanges**：Manifest 中声明 `orientation|screenSize|...`，防止 Activity 重建
# 纯移动端 AI 角色扮演伴侣 -- 技术架构方案报告

**项目代号**: AI-Companion-Mobile
**版本**: v2.0-architecture
**创建日期**: 2026-05-27
**分析方式**: 多智能体独立设计 + 交叉验证

---

## 目录

1. [总体架构设计](#1-总体架构设计)
2. [核心模块详细设计](#2-核心模块详细设计)
3. [技术栈选型](#3-技术栈选型)
4. [数据流设计](#4-数据流设计)
5. [模块间接口定义](#5-模块间接口定义)
6. [项目目录结构 (移动端)](#6-项目目录结构-移动端)
7. [开发阶段划分建议](#7-开发阶段划分建议)

---

## 1. 总体架构设计

### 1.1 移动端 App 架构图

```
┌─────────────────────────────────────────────────────────────┐
│                     Android 应用层                           │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌───────────────┐  │
│  │ 角色卡UI  │ │ 聊天界面  │ │ 声线管理  │ │ 记忆浏览器    │  │
│  │ Compose  │ │ Compose  │ │ Compose  │ │ Compose       │  │
│  └────┬─────┘ └────┬─────┘ └────┬─────┘ └───────┬───────┘  │
│       └─────────────┴────────────┴───────────────┘          │
│                         │ ViewModel 层                       │
│  ┌──────────────────────────────────────────────────────┐   │
│  │              Application 容器 (Application.kt)        │   │
│  │  依赖注入: CardManager / MemoryStorage / RolePlayer   │   │
│  └──────────────────────┬───────────────────────────────┘   │
├─────────────────────────┼───────────────────────────────────┤
│              领域层 (Domain Layer)                           │
│  ┌──────────┐┌──────────┐┌──────────┐┌──────────────────┐  │
│  │chat_engine││ memory   ││  voice   ││ wx_adapter       │  │
│  │(复用)    ││(复用+扩展)││(新增)   ││(重写)           │  │
│  └─────┬────┘└────┬─────┘└────┬─────┘└────────┬─────────┘  │
│        └──────────┴───────────┴──────────────┘              │
├─────────────────────────────────────────────────────────────┤
│              基础设施层 (Infrastructure)                     │
│  ┌─────────┐┌──────────┐┌────────┐┌──────────┐┌─────────┐  │
│  │llama.cpp││ ONNX RT  ││CoquiTTS││ChromaLite││SQLite   │  │
│  │(C++/JNI)││ (C/JNI)  ││(C/JNI) ││(Kotlin)  ││(原生)   │  │
│  └─────────┘└──────────┘└────────┘└──────────┘└─────────┘  │
└─────────────────────────────────────────────────────────────┘
```

### 1.2 与现有 PC 端代码的复用策略

| 模块 | 复用比例 | 策略 |
|------|---------|------|
| `chat_engine/` | **85%** 复用 | 核心逻辑（角色卡解析、Prompt 组装、上下文管理）与平台无关，直接翻译为 Kotlin |
| `api_client/` | **60%** 复用 | API 调用接口定义复用，新增 `LocalLLMClient` 实现本地推理 |
| `memory/` | **40%** 复用 | 接口设计复用，底层替换为 ChromaDB-on-Android + 向量检索 |
| `config/` | **70%** 复用 | 配置数据结构复用，平台适配为 Android DataStore |
| `core/` | **80%** 复用 | Application 容器模式、依赖注入、生命周期管理直接沿用 |
| `wx_adapter/` | **10%** 复用 | 接口契约复用，底层从 itchat 完全重写为 AccessibilityService |
| `voice/` | **0%** 新增 | 全新模块（TTS、声线管理、混合微调） |
| `skills/` | **30%** 复用 | 搜索接口复用，新增主动消息引擎 |

### 1.3 核心差异分析

PC 端 vs 移动端的关键差异：

| 维度 | PC 端 (现有) | 移动端 (目标) |
|------|-------------|-------------|
| AI 推理 | 仅云端 DeepSeek API | 本地 llama.cpp + 云端降级 |
| 微信接入 | itchat (Web 协议) | AccessibilityService + NotificationListener |
| 记忆存储 | JSON/SQLite (纯文本匹配) | SQLite + 向量嵌入 + 语义检索 |
| 语音 | 无 | TTS 引擎 + 声线系统 |
| 主动消息 | 无 | 前台服务 + WorkManager 定时器 |
| UI | FastAPI + Jinja2 (Web) | Jetpack Compose (Native) |
| 部署 | pip install | APK 安装包 |

---

## 2. 核心模块详细设计

### 2.1 微信适配层 (`wx_adapter/`) -- **完整重写**

#### 方案选择

| 方案 | 原理 | 优点 | 缺点 | 推荐度 |
|------|------|------|------|--------|
| **AccessibilityService + NotificationListener** | 监听通知栏 + 辅助功能模拟点击 | 无需 root，相对安全 | 部分功能受限 | **推荐 (首选)** |
| Xposed/LSPosed Hook | 注入微信进程拦截方法调用 | 功能最完整 | 需要 root，风险极高 | 备选 |
| ADB 模拟 | 通过 ADB 命令模拟 UI 操作 | 简单直接 | 慢，不可靠，需要开发者模式 | 不推荐 |

**推荐方案**: AccessibilityService + NotificationListener，理由：
1. 不需要 root 权限，用户接受度高
2. NotificationListener 可以可靠地获取新消息通知和内容
3. AccessibilityService 可以模拟点击操作发送消息
4. 微信小号专用，封号风险可控

#### 接口设计

```kotlin
// IWxAdapter.kt -- 微信适配层抽象接口
interface IWxAdapter {
    /**
     * 初始化并请求必要权限
     * @return 权限是否全部授予
     */
    suspend fun initialize(): Result<Boolean>

    /**
     * 发送文本消息到指定联系人
     */
    suspend fun sendTextMessage(targetName: String, content: String): Result<Unit>

    /**
     * 发送语音消息到指定联系人
     */
    suspend fun sendVoiceMessage(
        targetName: String,
        audioData: ByteArray,
        durationMs: Int
    ): Result<Unit>

    /**
     * 设置消息监听回调
     */
    fun setMessageListener(listener: WxMessageListener)

    fun isWeChatForeground(): Boolean
    fun shutdown()
}

// 微信消息数据类
data class WxMessage(
    val id: String,
    val type: WxMessageType,          // TEXT, VOICE, IMAGE
    val content: String,
    val senderName: String,
    val senderId: String,
    val isGroupChat: Boolean,
    val groupName: String?,
    val timestamp: Long,
    val rawAudioData: ByteArray? = null
)

enum class WxMessageType { TEXT, VOICE, IMAGE, UNKNOWN }

// 消息监听器
interface WxMessageListener {
    fun onMessageReceived(message: WxMessage)
    fun onConnectionStateChanged(connected: Boolean)
}
```

#### 实现架构

```
wx_adapter/
├── IWxAdapter.kt              # 抽象接口
├── WxAccessibilityAdapter.kt  # AccessibilityService 实现
├── WxNotificationListener.kt  # NotificationListenerService
├── WxUiAutomator.kt           # UI 自动化操作工具
├── WxContactManager.kt        # 联系人解析与缓存
└── WxConstants.kt             # 微信包名、Activity 名称等常量
```

### 2.2 AI 引擎层 (`ai_engine/`) -- **新增 + 部分复用**

#### LLM 推理方案

**推荐方案**: llama.cpp + GGUF 量化模型

| 模型候选 | 参数量 | 量化格式 | 文件大小 | RAM 占用 | 建议设备 |
|----------|--------|---------|---------|---------|---------|
| Qwen2.5-7B-Instruct | 7B | Q4_K_M | ~4.2GB | ~5GB | 8GB+ RAM |
| Qwen2.5-3B-Instruct | 3B | Q4_K_M | ~1.8GB | ~2.5GB | 4GB+ RAM |
| Gemma-3-4B-it | 4B | Q4_K_M | ~2.4GB | ~3GB | 6GB+ RAM |
| Llama-3.2-3B-Instruct | 3B | Q4_K_M | ~1.8GB | ~2.5GB | 4GB+ RAM |

**推荐首选**: Qwen2.5-3B-Instruct-Q4_K_M（中文能力优秀，内存门槛低）

#### 推理框架接口

```kotlin
// ILlmInference.kt -- LLM 推理抽象接口
interface ILlmInference {
    suspend fun loadModel(modelPath: String, config: InferenceConfig): Result<Unit>
    fun unloadModel()
    
    suspend fun generateStream(
        messages: List<ChatMessage>,
        systemPrompt: String,
        callback: (token: String) -> Unit
    ): Result<GenerateStats>

    suspend fun generate(
        messages: List<ChatMessage>,
        systemPrompt: String,
        config: GenerationConfig = GenerationConfig()
    ): Result<String>

    suspend fun getEmbedding(text: String): Result<FloatArray>

    val isModelLoaded: Boolean
    val modelInfo: ModelInfo?
}

data class InferenceConfig(
    val contextSize: Int = 4096,
    val threads: Int = 4,
    val gpuLayers: Int = 0,        // GPU 加速层数 (0=纯CPU)
    val batchSize: Int = 512
)

data class GenerationConfig(
    val temperature: Float = 0.8f,
    val maxTokens: Int = 2048,
    val topP: Float = 0.9f,
    val topK: Int = 40,
    val repeatPenalty: Float = 1.1f
)
```

**降级策略**: 当本地模型未加载或推理失败时，自动降级到 DeepSeek API（复用现有 `api_client/` 的接口设计）。

```kotlin
// HybridLlmClient.kt -- 混合推理客户端
class HybridLlmClient(
    private val localInference: ILlmInference,
    private val cloudClient: ICloudApiClient
) : ILlmInference by localInference {
    // 自动降级逻辑
}
```

### 2.3 语音引擎层 (`voice/`) -- **全新模块**

#### 模块架构

```
voice/
├── tts/
│   ├── ITtsEngine.kt           # TTS 引擎抽象接口
│   ├── PiperTtsEngine.kt       # Piper TTS (本地、轻量)
│   ├── EdgeTtsClient.kt        # Edge TTS (云端降级、中文优秀)
│   └── AudioUtils.kt           # 音频格式转换 (PCM→AMR/WAV)
├── voice_profile/
│   ├── VoiceProfile.kt         # 声线配置数据类
│   ├── VoiceProfileManager.kt  # 声线 CRUD 管理
│   └── VoicePreset.kt          # 预设声线模板
├── finetune/
│   ├── VoiceFinetuner.kt       # 混合微调协调器
│   ├── SpeakerEncoder.kt       # 说话人特征提取
│   └── VoiceCloner.kt          # 声音克隆服务
└── VoicePipeline.kt            # 语音处理管道 (协调器)
```

#### TTS 方案选型

| 方案 | 原理 | 延迟 | 质量 | 内存占用 | 中文支持 |
|------|------|------|------|---------|---------|
| **Piper TTS** | 本地 ONNX/VITS | <1s | 中等 | ~50MB | 有限 |
| **Edge-TTS** | 微软云 API | ~2s | 优秀 | 0 (网络) | 优秀 |
| **Coqui TTS** | 本地 XTTSv2 | ~3s | 优秀 | ~500MB | 中等 |
| **系统 TTS** | Android TTS | <0.5s | 基础 | 0 | 好 |

**推荐分层策略**:
1. **默认**: Edge-TTS（中文最优，零内存占用，需要网络）
2. **离线降级**: Piper TTS（下载中文模型后可用）
3. **声线克隆**: 基于 Edge-TTS 参数调节 + 少量的 fine-tune

#### 声线管理设计

```kotlin
data class VoiceProfile(
    val id: String,
    val name: String,
    val description: String,
    val baseModel: VoiceBaseModel,
    val pitch: Float,              // 音高调节 (-20~+20 半音)
    val speed: Float,              // 语速 (0.5~2.0)
    val volume: Float,             // 音量 (0.0~1.0)
    val style: VoiceStyle,
    val customSampleData: String?,
    val isDefault: Boolean,
    val createdAt: Long
)

enum class VoiceBaseModel {
    XIAOXIAO,        // 晓晓 (女声, 温柔)
    YUNXI,           // 云希 (男声, 自然)
    XIAOYI,          // 晓伊 (女声, 活泼)
    YUNJIAN,         // 云健 (男声, 磁性)
    XIAOXUAN,        // 晓萱 (女声, 知性)
    CUSTOM_CLONE     // 自定义克隆
}

enum class VoiceStyle {
    NORMAL, SOFT, LIVELY, SAD, ANGRY, WHISPER, CHEERFUL
}
```

#### 混合微调流程

```
用户录音样本 (10-30秒)
    → SpeakerEncoder 提取声纹特征 (512维向量)
    → 与基座声线混合插值
    → 调节 pitch/speed/style 参数
    → 生成初步声线
    → 用户试听 → 调整参数 → 迭代优化
    → 保存为 VoiceProfile
```

> **注意**: 真正的深度学习声线克隆（如 XTTSv2 fine-tuning）在移动端不可行（需要 GPU），因此采用 **参数化声线调节** 方案。

### 2.4 记忆系统层 (`memory/`) -- **复用接口 + 升级实现**

#### 升级方案

```
memory/
├── IMemoryStorage.kt           # 复用现有的抽象接口
├── MemoryEntry.kt              # 数据类
├── SqliteMemoryStore.kt        # SQLite 主存储
├── EmbeddingProvider.kt        # 嵌入向量生成
├── VectorIndex.kt              # 本地向量索引 (HNSW)
└── MemorySearchEngine.kt       # 混合检索 (语义 + 关键词)
```

**核心升级**: 从纯文本 `LIKE` 查询升级为 `语义向量相似度 + 关键词` 混合检索：

```kotlin
interface IMemoryStorage {
    fun saveMessage(
        conversationId: String,
        role: String,
        content: String,
        metadata: Map<String, Any> = emptyMap()
    )

    fun getMessages(
        conversationId: String,
        limit: Int? = null
    ): List<MemoryEntry>

    suspend fun searchMemory(
        conversationId: String,
        query: String,
        maxResults: Int = 5,
        minSimilarity: Float = 0.6f
    ): List<MemoryEntry>

    fun deleteConversation(conversationId: String): Boolean
    fun clearAll(): Boolean
    fun getStats(): MemoryStats

    // 新增接口
    suspend fun getRecentSummary(days: Int = 7): String
    suspend fun compact(targetEntries: Int = 500): Int
}
```

### 2.5 主动消息引擎 (`skills/proactive/`) -- **全新模块**

#### 触发机制

```
触发源                         处理器
┌──────────────┐        ┌──────────────────┐
│ 定时器触发    │───────→│ TimeBasedTrigger │──→ 问候 (早安/晚安)
│ WorkManager   │        └──────────────────┘
├──────────────┤        ┌──────────────────┐
│ 事件触发      │───────→│ EventTrigger     │──→ 特殊日期/天气/新闻
│ BroadcastRecv │        └──────────────────┘
├──────────────┤        ┌──────────────────┐
│ 上下文触发    │───────→│ ContextTrigger   │──→ 用户沉默太久/对话中断
│ Coroutine     │        └──────────────────┘
└──────────────┘                │
                                ▼
                     ┌─────────────────────┐
                     │ ProactiveEngine     │
                     │ 1. 决定是否发送      │
                     │ 2. 生成消息内容      │
                     │ 3. 选择发送方式      │
                     │ 4. 记录发送历史      │
                     └──────────┬──────────┘
                                ▼
                     ┌─────────────────────┐
                     │ 微信适配层 (发送)     │
                     └─────────────────────┘
```

```kotlin
class ProactiveEngine(
    private val wxAdapter: IWxAdapter,
    private val rolePlayer: RolePlayer,
    private val memory: IMemoryStorage,
    private val settings: ProactiveSettings
) {
    suspend fun evaluate(): ProactiveDecision
    suspend fun generateContent(decision: ProactiveDecision): String
    suspend fun execute(decision: ProactiveDecision, content: String): Result<Unit>
}

data class ProactiveSettings(
    val enabled: Boolean = true,
    val morningGreeting: Boolean = true,
    val eveningGreeting: Boolean = true,
    val silenceReminder: Boolean = true,
    val maxDailyProactive: Int = 3,
    val quietHoursStart: Int = 23,
    val quietHoursEnd: Int = 7
)
```

### 2.6 搜索增强 (`skills/search/`) -- **复用接口 + 本地缓存**

```kotlin
interface ISearchEngine {
    suspend fun search(
        query: String,
        maxResults: Int = 5
    ): Result<SearchResponse>

    suspend fun searchLocal(query: String, maxResults: Int = 5): Result<SearchResponse>
}

data class SearchResponse(
    val success: Boolean,
    val query: String,
    val results: List<SearchResult>,
    val source: SearchSource
)

data class SearchResult(
    val title: String,
    val url: String,
    val summary: String,
    val source: String
)
```

### 2.7 角色卡系统 -- **完全复用**

角色卡系统 (`chat_engine/card_parser.py`) 的逻辑可以直接翻译为 Kotlin，无需修改。支持 Tavo 和酒馆格式。

---

## 3. 技术栈选型

### 3.1 最终推荐方案

| 层级 | 技术选型 | 理由 |
|------|---------|------|
| **开发语言** | **Kotlin** (100%) | Android 原生，性能最优，协程天然适合异步 |
| **UI 框架** | **Jetpack Compose** | 声明式 UI，Material3，动画/手势原生支持 |
| **LLM 推理** | **llama.cpp + JNI** | C++ 底层高性能，GGUF 生态成熟 |
| **嵌入模型** | **ONNX Runtime** (all-MiniLM-L6-v2) | 跨平台，轻量级，90MB 模型文件 |
| **TTS 引擎** | **Edge-TTS (主力) + Piper TTS (离线降级)** | Edge 中文质量最优，Piper 保证离线可用 |
| **本地数据库** | **SQLite (Room)** | Android 原生集成，零额外开销 |
| **向量存储** | **自建 HNSW 索引 (基于 Room)** | 消息量级不大，无需引入重型方案 |
| **异步框架** | **Kotlin Coroutines + Flow** | 原生支持，结构化并发 |
| **依赖注入** | **Hilt** | Android 官方推荐，编译期 DI |
| **后台任务** | **WorkManager + Foreground Service** | 定时任务 + 前台常驻 |
| **微信自动化** | **AccessibilityService + NotificationListener** | 无需 root，安全可控 |

### 3.2 为什么不选其他方案

| 方案 | 否决理由 |
|------|---------|
| Python on Android (Chaquopy) | 性能差，APK 体积大，微信自动化无法实现 |
| React Native / Flutter | 无法直接调用 JNI，TTS 需桥接，增加复杂度 |
| mlc-llm | 框架偏重，Android 支持不如 llama.cpp 成熟 |
| Coqui TTS (本地) | 500MB+ 模型，移动端内存压力大，中文效果不如 Edge-TTS |

---

## 4. 数据流设计

### 4.1 用户发微信 -- AI 处理 -- 回复微信 完整数据流

```
微信App                         本App
  │                              │
  │  新消息通知                   │
  ├─────────────────────────────→│
  │  NotificationListener        │
  │  捕获通知内容                 │
  │                              │
  │                      ┌───────┴────────┐
  │                      │ WxAdapter      │
  │                      │ 解析消息/语音   │
  │                      └───────┬────────┘
  │                              │
  │                      ┌───────┴────────┐
  │                      │ MessageRouter  │
  │                      │ 路由到处理器    │
  │                      └───────┬────────┘
  │                              │
  │                  ┌───────────┼───────────┐
  │                  │           │           │
  │          ┌───────┴───┐ ┌─────┴────┐ ┌───┴────────┐
  │          │ 文本消息   │ │ 语音消息  │ │ 命令消息    │
  │          │ 处理流程   │ │ 处理流程  │ │ /开头的命令 │
  │          └───────┬───┘ └─────┬────┘ └───┬────────┘
  │                  │           │           │
  │                  │   ┌───────┘           │
  │                  │   │ 语音→文本(STT)     │
  │                  │   └───────┘           │
  │                  └───────┬───────────────┘
  │                          │
  │                  ┌───────┴────────┐
  │                  │ RolePlayer     │
  │                  │ .chat(message) │
  │                  └───────┬────────┘
  │                          │
  │          ┌───────────────┼───────────────┐
  │          │               │               │
  │  ┌───────┴──────┐ ┌──────┴──────┐ ┌──────┴──────┐
  │  │ 检索记忆      │ │ 组装Prompt  │ │ LLM推理     │
  │  │ Memory.search │ │ Character   │ │ llama.cpp   │
  │  │              │ │ Card + Ctx  │ │ (本地)      │
  │  └──────────────┘ └─────────────┘ │ 或          │
  │                                   │ DeepSeek API│
  │                                   │ (云端降级)  │
  │                                   └──────┬──────┘
  │                                          │
  │                          ┌───────────────┘
  │                          │ (AI 回复文本)
  │                          ▼
  │                  ┌───────────────┐
  │                  │ 后处理         │
  │                  │ - 保存到记忆   │
  │                  │ - 更新上下文   │
  │                  └───────┬───────┘
  │                          │
  │                  ┌───────┴───────┐
  │                  │ 是否语音回复?  │
  │                  └───┬───────┬───┘
  │                否    │       │ 是
  │                      │       │
  │                      │ ┌─────┴──────┐
  │                      │ │ TTS 合成    │
  │                      │ │ VoiceEngine │
  │                      │ └─────┬──────┘
  │                      │       │
  │              ┌───────┘       └───────┐
  │              ▼                       ▼
  │    ┌──────────────┐       ┌──────────────┐
  │    │ 发送文本消息  │       │ 发送语音消息  │
  │    └──────────────┘       └──────────────┘
```

### 4.2 主动消息触发流程

```
┌──────────────────────────────────────────────────┐
│                  WorkManager                      │
│  ┌────────────────┐  ┌────────────────────────┐  │
│  │ PeriodicWork   │  │ OneTimeWork            │  │
│  │ 每30分钟检查   │  │ 特定时间触发           │  │
│  └───────┬────────┘  └───────────┬────────────┘  │
│          │                       │                │
│          └───────────┬───────────┘                │
│                      ▼                            │
│          ┌──────────────────────┐                 │
│          │ ProactiveEngine      │                 │
│          │ .evaluate()          │                 │
│          └──────────┬───────────┘                 │
│                     │                             │
│         ┌───────────┼───────────┐                 │
│         ▼           ▼           ▼                 │
│   ┌─────────┐ ┌─────────┐ ┌─────────┐            │
│   │检查静默 │ │检查频次 │ │检查时段 │            │
│   └────┬────┘ └────┬────┘ └────┬────┘            │
│        │           │           │                  │
│        └───────────┼───────────┘                  │
│                    ▼                              │
│         ┌─────────────────┐                      │
│         │ Skip / Send     │                      │
│         └────────┬────────┘                      │
│                  ▼                                │
│         ┌─────────────────┐                      │
│         │ generateContent │                      │
│         └────────┬────────┘                      │
│                  ▼                                │
│         ┌─────────────────┐                      │
│         │ WxAdapter.send  │                      │
│         └─────────────────┘                      │
└──────────────────────────────────────────────────┘
```

### 4.3 语音消息处理流程

```
接收语音:
  微信语音消息 → NotificationListener 捕获
  → 提取 AMR 音频附件
  → AMR → PCM 转换
  → ASR 语音识别 (可选: 本地 Whisper-ONNX / 云端 API)
  → 文本输入 → RolePlayer.chat()

发送语音:
  AI 回复文本 → VoicePipeline
  → VoiceProfileManager 获取当前声线配置
  → TtsEngine.synthesize(text, profile)
  → PCM → AMR 格式转换
  → WxAdapter.sendVoiceMessage()
```

---

## 5. 模块间接口定义

### 5.1 核心接口契约总览

```
┌─────────────────────────────────────────────────────────┐
│                    接口关系图                            │
│                                                         │
│  MainActivity / ViewModel                               │
│       │                                                 │
│       ├──→ IWxAdapter          微信收发                 │
│       ├──→ IRolePlayer         角色扮演                  │
│       ├──→ IMemoryStorage      记忆存储                 │
│       ├──→ ICardManager        角色卡管理               │
│       ├──→ IVoicePipeline      语音处理                 │
│       ├──→ IProactiveEngine    主动消息                 │
│       └──→ ISearchEngine       搜索增强                 │
│                                                         │
│  IRolePlayer ──→ IContextManager                        │
│              ──→ IAIEngine (ILlmInference | ICloudApi)  │
│              ──→ ICardManager                           │
│              ──→ IMemoryStorage                         │
│                                                         │
│  IVoicePipeline ──→ ITtsEngine                         │
│                ──→ IVoiceProfileManager                 │
│                ──→ IWxAdapter                           │
│                                                         │
│  IProactiveEngine ──→ IWxAdapter                       │
│                  ──→ IRolePlayer                        │
│                  ──→ IMemoryStorage                     │
└─────────────────────────────────────────────────────────┘
```

### 5.2 IRolePlayer (角色扮演引擎核心接口)

```kotlin
interface IRolePlayer {
    suspend fun loadCharacterCard(cardPath: String): Result<Unit>
    suspend fun loadCharacter(cardData: CharacterCard): Result<Unit>

    suspend fun chat(
        userMessage: String,
        conversationId: String = "default",
        config: GenerationConfig = GenerationConfig(),
        recallMemory: Boolean = true
    ): Result<String>

    fun chatStream(
        userMessage: String,
        conversationId: String = "default",
        onToken: (String) -> Unit
    ): Flow<String>

    fun clearContext()
    fun getContextSummary(): ContextSummary
    val currentCharacter: CharacterCard?
}
```

### 5.3 IVoicePipeline (语音处理管道)

```kotlin
interface IVoicePipeline {
    suspend fun synthesizeAndSend(
        text: String,
        targetName: String
    ): Result<VoiceSendResult>

    suspend fun synthesize(text: String): Result<ByteArray>
    suspend fun setVoiceProfile(profile: VoiceProfile): Result<Unit>
    fun getCurrentProfile(): VoiceProfile?

    suspend fun createCustomProfile(
        name: String,
        baseModel: VoiceBaseModel,
        sampleAudio: ByteArray?,
        params: VoiceTuningParams
    ): Result<VoiceProfile>

    suspend fun previewVoice(
        text: String,
        profile: VoiceProfile
    ): Result<ByteArray>
}
```

### 5.4 IAIEngine (统一 AI 推理接口)

```kotlin
interface IAIEngine {
    suspend fun chat(
        messages: List<ChatMessage>,
        systemPrompt: String,
        config: GenerationConfig = GenerationConfig()
    ): Result<String>

    fun chatStream(
        messages: List<ChatMessage>,
        systemPrompt: String,
        config: GenerationConfig = GenerationConfig()
    ): Flow<String>

    suspend fun getEmbedding(text: String): Result<FloatArray>
    suspend fun testConnection(): Result<ConnectionTestResult>
    val mode: InferenceMode
}

enum class InferenceMode {
    LOCAL_CPU, LOCAL_GPU, CLOUD, OFFLINE
}
```

### 5.5 错误码定义

```kotlin
enum class ErrorCode(val code: Int, val message: String) {
    // 微信适配层 (1xxx)
    WX_PERMISSION_DENIED(1001, "缺少必要权限"),
    WX_NOT_INSTALLED(1002, "微信未安装"),
    WX_SERVICE_DISABLED(1003, "辅助功能服务未启用"),
    WX_SEND_FAILED(1004, "消息发送失败"),

    // AI 引擎 (2xxx)
    AI_MODEL_NOT_LOADED(2001, "模型未加载"),
    AI_INFERENCE_TIMEOUT(2002, "推理超时"),
    AI_OUT_OF_MEMORY(2003, "内存不足"),
    AI_CLOUD_API_ERROR(2004, "云端 API 错误"),

    // 语音引擎 (3xxx)
    VOICE_SYNTHESIS_FAILED(3001, "语音合成失败"),
    VOICE_NETWORK_ERROR(3002, "TTS 网络错误"),
    VOICE_PROFILE_INVALID(3003, "声线配置无效"),

    // 记忆系统 (4xxx)
    MEMORY_STORAGE_FULL(4001, "存储空间不足"),
    MEMORY_CORRUPTED(4002, "记忆数据损坏"),

    // 通用 (9xxx)
    UNKNOWN_ERROR(9999, "未知错误"),
    CONFIG_ERROR(9001, "配置错误"),
    NETWORK_ERROR(9002, "网络错误")
}
```

---

## 6. 项目目录结构 (移动端)

```
ai_companion_android/
├── app/
│   ├── src/main/
│   │   ├── java/com/ai_companion/
│   │   │   ├── App.kt                    # Application 类
│   │   │   ├── MainActivity.kt           # 主 Activity
│   │   │   │
│   │   │   ├── di/                       # Hilt 依赖注入
│   │   │   │   └── AppModule.kt
│   │   │   │
│   │   │   ├── core/                     # 应用容器
│   │   │   │   ├── ApplicationContainer.kt
│   │   │   │   └── SessionManager.kt
│   │   │   │
│   │   │   ├── chat_engine/              # 角色扮演引擎 (复用 85%)
│   │   │   │   ├── CardParser.kt
│   │   │   │   ├── CardManager.kt
│   │   │   │   ├── CharacterCard.kt
│   │   │   │   ├── ContextManager.kt
│   │   │   │   └── RolePlayer.kt
│   │   │   │
│   │   │   ├── ai_engine/                # AI 推理引擎 (新增)
│   │   │   │   ├── ILlmInference.kt
│   │   │   │   ├── IAIEngine.kt
│   │   │   │   ├── LlamaCppEngine.kt
│   │   │   │   ├── DeepSeekCloudClient.kt
│   │   │   │   └── HybridInferenceEngine.kt
│   │   │   │
│   │   │   ├── memory/                   # 记忆系统
│   │   │   │   ├── IMemoryStorage.kt
│   │   │   │   ├── MemoryEntry.kt
│   │   │   │   ├── SqliteMemoryStore.kt
│   │   │   │   ├── VectorIndex.kt
│   │   │   │   └── MemorySearchEngine.kt
│   │   │   │
│   │   │   ├── voice/                    # 语音引擎 (新增)
│   │   │   │   ├── tts/
│   │   │   │   │   ├── ITtsEngine.kt
│   │   │   │   │   ├── EdgeTtsEngine.kt
│   │   │   │   │   ├── PiperTtsEngine.kt
│   │   │   │   │   └── AudioUtils.kt
│   │   │   │   ├── profile/
│   │   │   │   │   ├── VoiceProfile.kt
│   │   │   │   │   ├── VoiceProfileManager.kt
│   │   │   │   │   └── VoicePresets.kt
│   │   │   │   └── VoicePipeline.kt
│   │   │   │
│   │   │   ├── wx_adapter/               # 微信适配层 (重写)
│   │   │   │   ├── IWxAdapter.kt
│   │   │   │   ├── WxAccessibilityAdapter.kt
│   │   │   │   ├── WxNotificationListener.kt
│   │   │   │   ├── WxUiAutomator.kt
│   │   │   │   └── WxContactManager.kt
│   │   │   │
│   │   │   ├── skills/                   # 技能工具箱
│   │   │   │   ├── proactive/
│   │   │   │   │   ├── ProactiveEngine.kt
│   │   │   │   │   └── ProactiveSettings.kt
│   │   │   │   └── search/
│   │   │   │       ├── ISearchEngine.kt
│   │   │   │       └── DuckDuckGoSearch.kt
│   │   │   │
│   │   │   └── ui/                       # Jetpack Compose UI
│   │   │       ├── theme/
│   │   │       ├── screens/
│   │   │       │   ├── ChatScreen.kt
│   │   │       │   ├── CardManagerScreen.kt
│   │   │       │   ├── VoiceProfileScreen.kt
│   │   │       │   ├── MemoryBrowserScreen.kt
│   │   │       │   └── SettingsScreen.kt
│   │   │       ├── components/
│   │   │       └── viewmodel/
│   │   │
│   │   ├── res/                          # 资源文件
│   │   └── AndroidManifest.xml
│   │
│   └── build.gradle.kts
│
├── cpp/                                  # JNI 原生代码
│   ├── llama.cpp/                        # llama.cpp 子模块
│   └── bridge/
│       ├── llama_bridge.cpp              # llama.cpp JNI 桥接
│       └── onnx_bridge.cpp               # ONNX Runtime JNI 桥接
│
├── models/                               # 模型文件
├── cards/                                # 内置角色卡
│
├── build.gradle.kts
└── settings.gradle.kts
```

---

## 7. 开发阶段划分建议

| 阶段 | 内容 | 产出 |
|------|------|------|
| **Phase 1: 基础设施** | Kotlin 项目搭建，Hilt DI，llama.cpp JNI 桥接，Room 数据库 | 可编译的 APK |
| **Phase 2: 核心对话** | CardParser/CardManager/ContextManager/RolePlayer 翻译为 Kotlin | 本地聊天功能 |
| **Phase 3: 微信接入** | AccessibilityService + NotificationListener 实现 | 可通过微信小号收发消息 |
| **Phase 4: 记忆升级** | 向量嵌入 + HNSW 索引 + 语义检索 | 长期记忆能力 |
| **Phase 5: 语音系统** | Edge-TTS + Piper TTS + 声线管理 + 语音消息发送 | 语音对话能力 |
| **Phase 6: 主动消息** | WorkManager + ProactiveEngine | AI 主动发消息 |
| **Phase 7: 优化打磨** | 性能优化、电池优化、UI 打磨 | 发布就绪 |
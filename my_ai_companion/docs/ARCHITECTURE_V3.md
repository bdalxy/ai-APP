# 独立 Android APP 技术架构方案 (v3.0)

**项目代号**: AiCompanion-Native
**版本**: v3.0
**日期**: 2026-05-27

---

## 一、架构总览

```
┌─────────────────────────────────────────────────────────┐
│                AiCompanion Android APP                   │
│                                                         │
│  ┌───────────────────────────────────────────────────┐  │
│  │           Jetpack Compose UI 层                    │  │
│  │  Chat │ Cards │ Voice │ Settings                  │  │
│  └────────────────────┬──────────────────────────────┘  │
│                       │ ViewModel / StateFlow           │
│  ┌────────────────────┴──────────────────────────────┐  │
│  │            领域层 (100% Kotlin)                     │  │
│  │                                                    │  │
│  │  chat_engine/        voice/         proactive/      │  │
│  │  ├─ CardParser       ├─ VitsOnnx    ├─ Proactive    │  │
│  │  ├─ CardManager      ├─ EmbedBlend   │  ─ Worker    │  │
│  │  ├─ RolePlayer       ├─ Pipeline     │  ─ Notifier  │  │
│  │  └─ ContextMgr       └─ ProfileMgr                  │  │
│  │                                                    │  │
│  │  memory/             api_client/                    │  │
│  │  ├─ MessageDao       ├─ DeepSeekClient              │  │
│  │  ├─ EmbeddingDao     └─ DuckDuckGoSearch            │  │
│  │  └─ MemoryStorage                                   │  │
│  └────────────────────┬──────────────────────────────┘  │
│                       │                                  │
│  ┌────────────────────┴──────────────────────────────┐  │
│  │            基础设施层                                │  │
│  │  Room  │ ONNX RT │ OkHttp │ WorkManager │ Hilt     │  │
│  └───────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────┘
         │                              │
         ▼                              ▼
   ┌──────────┐                ┌──────────────┐
   │ DeepSeek │                │ DuckDuckGo   │
   │ API      │                │ API          │
   └──────────┘                └──────────────┘
```

---

## 二、技术栈

| 层级 | 选型 | 理由 |
|------|------|------|
| 语言 | Kotlin 100% | 旧方案是 Python+Kotlin，现在统一 |
| UI | Jetpack Compose + Material3 | 声明式，动画原生 |
| 数据库 | Room (SQLite) | Android 官方 ORM |
| 向量检索 | 自建余弦相似度 | 单用户消息 < 10000，无需 HNSW |
| ONNX 推理 | ONNX Runtime Android | TTS + 文本嵌入 |
| HTTP | OkHttp + kotlinx.serialization | 工业标准 |
| DI | Hilt | Android 官方 |
| 后台 | WorkManager | 系统管理生命周期 |
| 通知 | NotificationManager | 本地通知主动消息 |

---

## 三、核心模块

### 3.1 聊天系统

```
用户发文字 → RolePlayer.chat()
  ├─ ContextManager (最近50条上下文)
  ├─ MemoryStorage.search (语义检索历史)
  ├─ CardManager.getActive (角色卡 → System Prompt)
  └─ DeepSeekClient.chat()
      └─ 返回 → 保存到 Room → 更新 UI
```

### 3.2 语音引擎

```
声线方案:
  PC: GPT-SoVITS 训练 → ONNX 导出 (~80MB)
  手机: ONNX Runtime Mobile 加载 → TTS 推理

声线混合:
  EmbeddingBlender.blend(voiceA, voiceB, ratio=0.7)
  → Speaker Embedding 线性插值 → 注入解码器

降级链路:
  VITS-ONNX (主力) → Piper TTS (离线) → 系统 TTS (兜底)
```

### 3.3 主动消息

```
WorkManager (每30分钟)
  → 检查: 是否静默时段? 今日已发几次?
  → 生成: DeepSeek API 以角色身份生成问候
  → 发送: 本地通知 → 用户点击 → 打开 APP
```

### 3.4 记忆系统

```
Room MessageDao: 消息 CRUD
ONNX TextEmbedder: 文本 → 384维向量
EmbeddingDao: 向量存储
MemoryStorage.search: 余弦相似度 Top-K
长期记忆: 定期 DeepSeek 辅助提取关键事实
```

---

## 四、旧代码复用

| 模块 | 复用率 | 方式 |
|------|--------|------|
| chat_engine/ | 85% | Python → Kotlin 翻译 |
| api_client/ | 70% | requests → OkHttp |
| memory/ | 40% | 接口保留，底层换 Room |
| wx_adapter/ | 0% | **完全废弃** |
| api_server.py | 0% | **完全废弃** |
| templates/ | 0% | **完全废弃** |

---

## 五、目录结构

```
AiCompanion/
├── app/src/main/java/com/ai_companion/
│   ├── App.kt                    # Application + Hilt
│   ├── MainActivity.kt           # 单 Activity
│   ├── di/                       # Hilt 模块
│   ├── chat_engine/              # 角色引擎 (Python→Kotlin)
│   │   ├── CharacterCard.kt / CardParser.kt / CardManager.kt
│   │   ├── ContextManager.kt / RolePlayer.kt
│   ├── api_client/               # API 封装
│   │   ├── DeepSeekClient.kt / DuckDuckGoSearch.kt
│   ├── memory/                   # 记忆系统
│   │   ├── MessageDao.kt / EmbeddingDao.kt / MemoryStorage.kt
│   ├── voice/                    # 语音引擎 (全新)
│   │   ├── tts/ (ITtsEngine / VitsOnnxEngine / PiperTtsEngine)
│   │   ├── profile/ (VoiceProfile / EmbeddingBlender / VoicePresets)
│   │   └── VoicePipeline.kt
│   ├── proactive/                # 主动消息
│   │   ├── ProactiveEngine.kt / ProactiveWorker.kt / ProactiveNotifier.kt
│   ├── data/                     # Room 数据库 + DataStore
│   └── ui/                       # Compose 界面
│       ├── screens/ (Chat / Cards / Voice / Settings)
│       ├── components/ (MessageBubble / VoicePlayButton / BlenderSlider)
│       └── viewmodel/
```

---

## 六、开发阶段

| Phase | 内容 | 工时 | 里程碑 |
|-------|------|------|--------|
| **0** | 预研：GPT-SoVITS ONNX 导出 + 声线插值实验 | 3-5天 | 验证通过才继续 |
| **1** | 骨架 + 核心对话 | 7天 | M1: 能聊天 |
| **2** | 记忆系统升级 | 3天 | M2: 有记忆 |
| **3** | 语音引擎 | 5天 | M3: 能说话 |
| **4** | 主动消息 + 搜索 | 2天 | M4: 会主动 |
| **5** | 打磨发布 | 3天 | M5: 可发布 |
| **合计** | | **23-25天** | |

---

## 七、旧方案废弃清单

| 废弃 | 原因 |
|------|------|
| wx_adapter/ 全部 | 不再依赖微信 |
| api_server.py + templates/ | 不再用 FastAPI+WebView |
| ChromaDB + llama.cpp 依赖 | 纯云端 AI + Room 向量检索 |
| Termux 运行环境 | APK 直接安装 |
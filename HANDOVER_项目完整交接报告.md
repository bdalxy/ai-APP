# AI Companion 项目交接报告（完整版）

> 日期：2026-06-17  
> 版本：v0.1.0  
> 状态：APK 已构建、已安装到设备 `1dafb92f`、已推送到 GitHub `main` 分支  
> 最新 commit：`70902f2`

---

## 一、项目概述

**项目名称**：AI Companion（AI 伙伴）  
**项目类型**：独立 Android 原生 APP  
**核心功能**：AI 角色扮演聊天，支持长期记忆、世界书、插件系统、主动消息推送  
**技术栈**：Kotlin + Chaquopy (Python桥接) + DeepSeek API  
**设计主题**：往世乐土 (Elysian Realm) 深色半透明玻璃态  
**主色调**：紫色系 (#9B59B6)  
**仓库地址**：https://github.com/bdalxy/ai-APP.git

---

## 二、技术栈

| 层级 | 技术 | 说明 |
|------|------|------|
| 语言 | Kotlin 2.0.20 + Python 3.10 | 主力语言 |
| 桥接 | Chaquopy 17.0.0 | Kotlin ↔ Python 互调 |
| UI | Material Design 3 + ViewBinding | 原生 Android View |
| AI | DeepSeek API (deepseek-v4-flash) | 对话 + Embedding |
| 向量存储 | SQLite (自实现) | 余弦相似度 + BM25 |
| 数据库 | 本地 JSON 文件 + SharedPreferences | 角色卡/会话/设置 |
| 加密 | EncryptedSharedPreferences (AES-256) | API Key 安全存储 |
| 定时任务 | WorkManager | 主动消息推送 |
| 构建 | Gradle 8.7.0 + AGP 8.7.0 | 构建系统 |
| 最低SDK | API 26 (Android 8.0) | 真机 arm64-v8a |

---

## 三、项目目录结构

```
ai-APP/
├── HANDOVER_记忆系统.md              # 记忆系统专项交接报告
├── docs/                              # 文档目录
│   ├── REQUIREMENTS.md                # 需求文档
│   ├── TASK_LIST.md                   # 任务清单
│   ├── PROJECT_PLAN_v1.0.md           # 项目计划
│   ├── APP_UI_DESIGN_SYSTEM_REPORT.md # UI 设计系统报告
│   ├── V7.0-项目交接报告-AI可读版.md   # 旧版交接报告
│   └── ...（更多文档）
│
└── android/                           # Android 项目根目录
    ├── build.gradle.kts               # 顶层构建配置
    ├── settings.gradle.kts            # 模块配置
    ├── gradle.properties              # Gradle 属性
    ├── gradlew / gradlew.bat          # Gradle Wrapper
    └── app/
        ├── build.gradle.kts           # 应用构建配置（含 Chaquopy）
        └── src/
            ├── androidTest/           # Android 测试
            │   └── java/.../app/
            │       ├── MainActivityTest.kt
            │       └── MemoryManageActivityTest.kt
            │
            └── main/
                ├── AndroidManifest.xml
                ├── java/com/aicompanion/app/
                │   ├── adapters/
                │   │   └── CharacterListAdapter.kt
                │   ├── ui/
                │   │   ├── GlassCard.kt          # 玻璃态卡片组件
                │   │   └── ParticleView.kt        # 粒子动画组件
                │   ├── AICompanionApp.kt          # Application 类
                │   ├── ActivityTransitionHelper.kt # Activity 转场动画
                │   ├── AppConfig.kt               # 加密配置管理
                │   ├── CharacterActivity.kt       # 角色详情页
                │   ├── CharacterData.kt           # 角色数据模型
                │   ├── CharacterEditActivity.kt   # 角色编辑页
                │   ├── CharacterManageActivity.kt # 角色管理页
                │   ├── CharacterSelectActivity.kt # 角色选择页
                │   ├── ChatAdapter.kt             # 聊天消息适配器
                │   ├── ConversationSession.kt     # 会话数据模型
                │   ├── ConversationSessionManager.kt # 多会话管理器
                │   ├── DeviceAdaptationHelper.kt  # 设备适配辅助
                │   ├── MainActivity.kt            # 聊天主界面
                │   ├── MemoryAdapter.kt           # 记忆列表适配器
                │   ├── MemoryItem.kt              # 记忆数据模型
                │   ├── MemoryManageActivity.kt    # 记忆管理页
                │   ├── Message.kt                 # 消息数据模型
                │   ├── MessageItemAnimator.kt     # 消息入场动画
                │   ├── NotificationHelper.kt      # 通知管理
                │   ├── PluginAdapter.kt           # 插件列表适配器
                │   ├── PluginItem.kt              # 插件数据模型
                │   ├── PluginManageActivity.kt    # 插件管理页
                │   ├── PluginViewModel.kt         # 插件 ViewModel
                │   ├── ProactiveService.kt        # 主动推送服务
                │   ├── ProactiveWorker.kt         # 主动推送 Worker
                │   ├── SettingsActivity.kt        # 设置主页面
                │   ├── SettingsDetailActivity.kt  # 设置子页面
                │   ├── StateViewHelper.kt         # 状态视图辅助
                │   ├── ViewUtils.kt               # 视图工具类
                │   └── WorldBookSection.kt        # 世界书区域组件
                │
                ├── res/                           # Android 资源
                │   ├── anim/                      # 动画（滑动进出）
                │   ├── drawable/                  # 48个 drawable（背景/图标/按钮/气泡等）
                │   ├── layout/                    # 14个布局文件
                │   ├── layout-land/               # 横屏布局
                │   ├── mipmap-anydpi-v26/         # 启动图标
                │   ├── values/                    # 颜色/尺寸/字符串/主题
                │   ├── values-en/                 # 英文字符串
                │   ├── values-land/               # 横屏尺寸
                │   ├── values-night/              # 深色主题
                │   ├── values-sw600dp/            # 平板尺寸
                │   ├── values-sw840dp/            # 大屏平板尺寸
                │   └── xml/file_paths.xml         # FileProvider 路径
                │
                └── python/                        # Python 源码（Chaquopy）
                    ├── requirements.txt           # Python 依赖清单
                    ├── chaquopy_test.py           # Chaquopy 连通性测试
                    ├── .env.example               # 环境变量示例
                    │
                    ├── chat_bridge/               # Kotlin ↔ Python 桥接层
                    │   ├── __init__.py            # 统一导出（所有桥接函数）
                    │   ├── _state.py              # 全局状态单例 + 线程池
                    │   ├── _core.py               # 聊天引擎桥接（init/chat/chat_stream/reset）
                    │   ├── _memory.py             # 记忆系统桥接（CRUD/检索/备份/缓存/分析）
                    │   ├── _character.py          # 角色卡管理桥接
                    │   ├── _plugins.py            # 插件管理桥接
                    │   ├── _proactive.py          # 主动消息桥接
                    │   └── _world_book.py         # 世界书桥接
                    │
                    ├── src/                       # Python 核心模块
                    │   ├── __init__.py
                    │   ├── app_context.py         # 全局应用上下文（单例，管理生命周期）
                    │   │
                    │   ├── api_client/            # API 客户端
                    │   │   ├── __init__.py
                    │   │   └── deepseek.py        # DeepSeek API 封装（chat + embed）
                    │   │
                    │   ├── chat_engine/           # 聊天引擎
                    │   │   ├── __init__.py
                    │   │   ├── card_parser.py     # 角色卡 JSON 解析器
                    │   │   ├── context_manager.py # 对话上下文管理器
                    │   │   ├── prompt_builder.py  # System Prompt 构建器
                    │   │   ├── role_player.py     # 核心：角色扮演对话引擎
                    │   │   └── token_presets.py   # Token 预设配置
                    │   │
                    │   ├── memory/                # 记忆系统（最复杂模块）
                    │   │   ├── __init__.py        # 模块导出
                    │   │   ├── orchestrator.py    # 核心编排器（统一入口）
                    │   │   ├── vector_store.py    # SQLite向量存储 + 倒排索引 + 关系图 + 标签
                    │   │   ├── extractor.py       # 记忆提取器（规则+LLM混合）
                    │   │   ├── retriever.py       # 混合检索器（向量+BM25+降级）
                    │   │   ├── bm25.py            # BM25 关键词检索
                    │   │   ├── decay.py           # 时间衰减加权
                    │   │   ├── lifecycle.py       # 生命周期管理（衰减/清理/健康检查）
                    │   │   ├── consolidator.py    # 记忆合并器（去重/冲突解决）
                    │   │   ├── analyzer.py        # 记忆分析器（趋势/聚类/画像/质量）
                    │   │   ├── archiver.py        # 记忆归档器（冷数据迁移）
                    │   │   ├── context_builder.py # 上下文构建器（Token预算/分层注入）
                    │   │   ├── backup.py          # 备份管理器（完整/JSON/增量/校验）
                    │   │   ├── memory_cache.py    # 多层缓存（LRU+TTL）
                    │   │   ├── memory_types.py    # 扩展类型系统（5大类15子类）
                    │   │   └── exporter.py        # 导出工具（对话/记忆/完整）
                    │   │
                    │   ├── plugins/               # 插件系统
                    │   │   ├── __init__.py
                    │   │   ├── plugin_base.py     # 插件基类
                    │   │   ├── plugin_manager.py  # 插件管理器
                    │   │   └── logger_plugin.py   # 日志插件（内置）
                    │   │
                    │   ├── proactive/             # 主动消息
                    │   │   ├── __init__.py
                    │   │   ├── engine.py          # 主动消息生成引擎
                    │   │   ├── scheduler.py       # 调度器
                    │   │   └── topic_generator.py # 话题生成器
                    │   │
                    │   ├── world_book/            # 世界书
                    │   │   ├── __init__.py
                    │   │   └── world_book.py      # 世界书管理（加载/匹配/注入）
                    │   │
                    │   ├── config/                # 配置
                    │   │   ├── __init__.py
                    │   │   ├── settings.py        # 全局设置
                    │   │   └── user_settings.py   # 用户设置
                    │   │
                    │   ├── exceptions/            # 异常定义
                    │   │   ├── __init__.py
                    │   │   ├── api_exceptions.py  # API 异常
                    │   │   └── memory_exceptions.py # 记忆异常
                    │   │
                    │   └── utils/                 # 工具
                    │       ├── __init__.py
                    │       ├── logger.py          # 日志工具
                    │       ├── lru_cache.py       # LRU缓存工具
                    │       ├── retry.py           # 重试机制
                    │       └── time_utils.py      # 时间工具
                    │
                    ├── data/                      # 数据文件
                    │   ├── role_cards/
                    │   │   └── 小美.json          # 默认角色卡
                    │   └── world_books/
                    │       └── reality_world.json # 默认世界书
                    │
                    └── [第三方依赖包]             # certifi, charset_normalizer, colorama, idna, loguru, requests, urllib3, win32_setctime
```

---

## 四、Android 端架构

### 4.1 Activity 清单（11个）

| Activity | 功能 | 关键方法 |
|----------|------|----------|
| `MainActivity` | 聊天主界面，核心交互页面 | `sendMessage()`, `chatStream()`, `initChatEngine()`, 多会话管理 |
| `SettingsActivity` | 设置主页面，展示各设置子入口 | 导航到 SettingsDetailActivity |
| `SettingsDetailActivity` | 设置子页面，按 type 加载不同设置 | 账户/对话/主动消息/世界书/记忆设置 |
| `MemoryManageActivity` | 记忆管理，搜索/分页/删除/清空 | `loadMemories()`, `searchMemories()`, `deleteMemory()` |
| `CharacterManageActivity` | 角色卡管理列表 | 切换/编辑/删除角色 |
| `CharacterEditActivity` | 角色卡创建/编辑 | 表单编辑 + JSON 校验 |
| `CharacterSelectActivity` | 角色卡选择器 | 选择当前角色 |
| `CharacterActivity` | 角色详情展示 | 显示角色信息 |
| `PluginManageActivity` | 插件管理，启用/禁用/详情 | `togglePlugin()`, `getPluginDetail()` |
| `ProactiveService` | 主动推送服务（WorkManager） | 定时调度 ProactiveWorker |
| `AICompanionApp` | Application 入口 | 初始化全局配置 |

### 4.2 适配器（5个）

| 适配器 | 用途 |
|--------|------|
| `ChatAdapter` | 聊天消息列表（RecyclerView），支持 AI/用户气泡、打字动画、流式更新 |
| `MemoryAdapter` | 记忆列表，支持分页加载 |
| `CharacterListAdapter` | 角色卡列表 |
| `PluginAdapter` | 插件列表 |
| `MessageItemAnimator` | 消息入场动画（淡入+上滑） |

### 4.3 数据模型

| 模型 | 用途 |
|------|------|
| `Message` | 聊天消息（role, content, timestamp, isStreaming） |
| `MemoryItem` | 记忆条目 |
| `CharacterData` | 角色卡数据 |
| `PluginItem` | 插件信息 |
| `ConversationSession` | 会话数据模型 |
| `ConversationSessionManager` | 多会话管理器（持久化+历史管理） |

### 4.4 UI 组件

| 组件 | 说明 |
|------|------|
| `GlassCard` | 玻璃态半透明卡片，支持圆角、阴影、渐变边框 |
| `ParticleView` | 粒子动画背景，营造梦幻氛围 |
| `WorldBookSection` | 世界书配置区域 |
| `StateViewHelper` | 空白/加载/错误状态视图 |
| `ViewUtils` | 工具类（EdgeToEdge、Insets等） |

### 4.5 配置管理

| 类 | 说明 |
|----|------|
| `AppConfig` | API Key（AES-256加密存储）+ Token预设/模型/温度/上下文长度等设置 |
| `NotificationHelper` | 通知渠道创建 + 主动消息通知弹出 |
| `DeviceAdaptationHelper` | 设备适配（屏幕尺寸、API版本等） |
| `ActivityTransitionHelper` | Activity 共享元素转场动画 |

---

## 五、Python 端架构

### 5.1 桥接层（chat_bridge/）

| 文件 | 功能 | 关键函数 |
|------|------|----------|
| `_state.py` | 全局状态单例、线程池、角色卡目录 | `_ctx`, `_executor`, `_lock` |
| `_core.py` | 聊天引擎桥接 | `init()`, `chat()`, `chat_stream()`, `reset()`, `apply_params()`, `export_history()` |
| `_memory.py` | 记忆系统桥接（最复杂） | 核心CRUD + 维护 + 分析 + 标签 + 关系 + 备份 + 缓存 + 上下文构建（30+函数） |
| `_character.py` | 角色卡管理 | `set_character_card()`, `get_character_card()`, `reload_card()` |
| `_plugins.py` | 插件管理 | `list_plugins()`, `toggle_plugin()`, `get_plugin_detail()` |
| `_proactive.py` | 主动消息 | `generate_proactive_message()` |
| `_world_book.py` | 世界书管理 | `list_world_books()`, `enable/disable_world_book()`, `create/delete/update_world_book()` |
| `__init__.py` | 统一导出 | 所有桥接函数（60+） |

### 5.2 核心模块（src/）

#### 5.2.1 聊天引擎（chat_engine/）

| 文件 | 功能 |
|------|------|
| `role_player.py` | 核心对话引擎：加载角色卡 → 构建 System Prompt → 注入记忆 → 注入世界书 → 调用 API → 流式/非流式回复 |
| `prompt_builder.py` | System Prompt 构建器：组装角色设定 + 对话规则 + 示例对话 + 记忆 + 世界书 |
| `context_manager.py` | 对话上下文管理：截断历史消息、Token 预算控制 |
| `card_parser.py` | 角色卡 JSON 解析器 |
| `token_presets.py` | Token 预设配置（quality/balanced/economy） |

#### 5.2.2 记忆系统（memory/）— 详见 `HANDOVER_记忆系统.md`

| 文件 | 功能 |
|------|------|
| `orchestrator.py` | 核心编排器：remember()→recall()→build_context()→run_maintenance()→backup→cache |
| `vector_store.py` | SQLite 向量存储：CRUD + 倒排索引 + 关系图 + 标签系统 + 变更日志 |
| `extractor.py` | 记忆提取：规则模式 + LLM 模式混合，冲突检测，实体提取 |
| `retriever.py` | 混合检索：向量余弦相似度 + BM25 + 多策略降级 |
| `bm25.py` | BM25 关键词检索算法 |
| `decay.py` | 时间衰减加权 |
| `lifecycle.py` | 生命周期管理：衰减更新、重要性重校准、智能清理、健康检查 |
| `consolidator.py` | 记忆合并：相似记忆合并 + 冲突解决 |
| `analyzer.py` | 记忆分析：趋势分析、主题聚类、用户画像、质量评估 |
| `archiver.py` | 记忆归档：冷数据迁移 |
| `context_builder.py` | 上下文构建：Token预算(800) + 分层注入(核心50%/扩展30%/最近20%) + 三维排序 |
| `backup.py` | 备份管理：完整备份(SQLite API) + JSON导出 + 增量 + 自动 + 校验恢复 |
| `memory_cache.py` | 多层缓存：LRU热点(200条) + TTL检索(60s) + 统计(30s) + 画像(300s) |
| `memory_types.py` | 类型系统：5大类15子类记忆分类 + 冲突检测 + 情感分析 |
| `exporter.py` | 导出工具：对话导出、记忆导出、完整导出 |

#### 5.2.3 其他模块

| 文件 | 功能 |
|------|------|
| `app_context.py` | 全局应用上下文单例：管理 DeepSeekClient、RolePlayer、MemoryOrchestrator 生命周期 |
| `api_client/deepseek.py` | DeepSeek API 封装：chat() + embed() + embed_cached() + 重试 |
| `world_book/world_book.py` | 世界书：加载 JSON → 正则匹配关键词 → 注入到 System Prompt |
| `plugins/plugin_manager.py` | 插件管理器：加载/启用/禁用/调用插件 |
| `plugins/plugin_base.py` | 插件基类：定义插件接口 |
| `plugins/logger_plugin.py` | 内置日志插件 |
| `proactive/engine.py` | 主动消息生成引擎：根据记忆+时间+话题生成推送消息 |
| `proactive/scheduler.py` | 主动消息调度器 |
| `proactive/topic_generator.py` | 话题生成器 |
| `config/settings.py` | 全局配置（API Key、模型、超时等） |
| `config/user_settings.py` | 用户偏好设置 |
| `exceptions/` | 自定义异常类 |
| `utils/` | 工具函数（日志、LRU缓存、重试、时间） |

---

## 六、数据流

```
[Kotlin Activity]
    │
    ├── set_api_key() → AppConfig (EncryptedSharedPreferences)
    │
    ├── init() → chat_bridge._core.init()
    │   └→ AppContext.initialize() → DeepSeekClient + RolePlayer
    │
    ├── init_memory() → chat_bridge._memory.init_memory()
    │   └→ AppContext.init_memory() → VectorStore + MemoryOrchestrator
    │
    ├── chat(msg) → chat_bridge._core.chat()
    │   └→ RolePlayer.chat()
    │       ├── MemoryOrchestrator.inject_memories() → recall() → 检索记忆
    │       ├── WorldBook.match() → 匹配世界书条目
    │       ├── PromptBuilder.build() → 组装 System Prompt
    │       ├── DeepSeekClient.chat_stream() → 流式 API 调用
    │       └── MemoryOrchestrator.remember() → 提取+存储记忆
    │
    ├── manage_memory() → chat_bridge._memory.*
    │   └→ MemoryOrchestrator (CRUD / 维护 / 分析 / 备份 / 缓存)
    │
    └── proactive → ProactiveService → ProactiveWorker
        └→ chat_bridge._proactive.generate_proactive_message()
            └→ ProactiveEngine.generate()
```

---

## 七、构建配置

### 7.1 关键版本号

| 配置项 | 值 |
|--------|-----|
| compileSdk | 36 |
| minSdk | 26 |
| targetSdk | 36 |
| Kotlin | 2.0.20 |
| AGP | 8.7.0 |
| Chaquopy | 17.0.0 |
| Python | 3.10 |
| JDK | 17 |
| ABI | arm64-v8a |

### 7.2 Android 依赖

```
kotlin-stdlib:2.0.20
androidx.core:core-ktx:1.13.1
androidx.appcompat:appcompat:1.7.0
androidx.constraintlayout:constraintlayout:2.1.4
androidx.recyclerview:recyclerview:1.3.2
com.google.android.material:material:1.12.0
androidx.lifecycle:lifecycle-runtime-ktx:2.8.4
androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4
kotlinx-coroutines-android:1.8.1
androidx.security:security-crypto:1.1.0-alpha06
androidx.work:work-runtime-ktx:2.9.0
```

### 7.3 Python 依赖

```
requests>=2.32.0          # HTTP 客户端
loguru>=0.7.0             # 日志
idna==2.10                # 国际化域名
certifi>=2025.0.0         # SSL 证书
charset_normalizer>=3.4.0 # 字符编码检测
urllib3>=2.0.0            # HTTP 连接池
colorama>=0.4.0           # 终端颜色
win32_setctime>=1.0.0     # Windows 文件时间
```

> 注意：所有 Python 包通过手动提取 wheel 到源码目录，不通过 Chaquopy pip 在线安装，以避开 RECORD 文件兼容问题。

---

## 八、当前状态

| 项目 | 状态 |
|------|------|
| 代码编译 | ✅ 通过 |
| APK 构建 | ✅ 成功（app-debug.apk） |
| 设备安装 | ✅ 设备 `1dafb92f` |
| APP 启动 | ✅ 正常 |
| GitHub 推送 | ✅ commit `70902f2`，已推送至 `main` 分支 |
| 功能测试 | ⏳ 待人工验证 |

---

## 九、测试验证指南

### 9.1 基础聊天流程

1. 打开 APP → 设置 API Key（设置页 → 账户设置）
2. 选择角色卡（小美）
3. 输入消息发送 → 查看 AI 流式回复
4. 多轮对话后检查记忆是否自动存储

### 9.2 记忆系统测试

参见 `HANDOVER_记忆系统.md` 第七节，涵盖：
- 基础流程（存储/检索）
- CRUD 操作
- 维护功能（衰减/合并/清理）
- 分析功能（趋势/聚类/画像/质量）
- 备份功能（完整/JSON/校验/恢复）
- 缓存功能（命中率/失效/清理）

### 9.3 角色卡测试

- 设置页 → 角色卡管理 → 切换角色
- 创建/编辑角色卡（JSON 表单）
- 验证角色卡切换后 AI 回复风格变化

### 9.4 世界书测试

- 设置页 → 世界书设置 → 启用/禁用
- 创建/编辑世界书条目
- 验证世界书内容是否注入到对话中

### 9.5 插件测试

- 主界面 → 插件按钮 → 查看插件列表
- 启用/禁用插件
- 查看插件详情

### 9.6 多会话测试

- 主界面 → 新建会话
- 切换会话 → 验证历史消息独立
- 删除/重命名会话

### 9.7 主动消息测试

- 设置页 → 主动消息设置 → 设置间隔
- 等待定时触发 → 检查通知栏推送

### 9.8 合格标准

- [ ] 聊天正常，流式输出不卡顿
- [ ] 记忆自动存储，重启后不丢失
- [ ] 角色卡切换后 AI 风格变化
- [ ] 世界书内容正确注入
- [ ] 插件启用/禁用正常
- [ ] 多会话独立隔离
- [ ] 主动消息按间隔推送
- [ ] 无崩溃、无 ANR、无内存泄漏

---

## 十、已知问题与注意事项

### 10.1 Chaquopy 兼容性

- Python 包通过手动复制 wheel 到源码目录，非 pip 安装
- 仅支持 `arm64-v8a` ABI（真机），x86_64 已移除
- 部分 Python 库（如 numpy）因平台兼容性未引入

### 10.2 功能限制

| 限制 | 说明 |
|------|------|
| 记忆提取 | 默认每5轮才触发1次 LLM 提取，其余用规则模式 |
| 上下文构建 | 已实现但尚未接入 `chat()` 主流程 |
| 备份目录 | 自动创建在数据库文件同级 `backups/` 下 |
| 加密导出 | 依赖 `cryptography` 库，当前未包含 |

### 10.3 后续待办

| 优先级 | 任务 | 说明 |
|--------|------|------|
| **高** | 真机功能验证 | 按测试指南逐项验证 |
| **高** | 记忆系统 UI | Android 端管理界面（记忆列表、搜索、编辑、备份管理） |
| **高** | 上下文构建接入 | 将 `build_context()` 接入 `chat()` 流程 |
| 中 | 性能测试 | 大量记忆(1000+)场景下的性能 |
| 中 | 横屏适配完善 | 当前横屏布局仅基础适配 |
| 低 | 多语言支持 | 当前仅中文/英文 |
| 低 | 从繁到简 | 功能完善后审视代码精简空间 |

---

## 十一、操作流程速查

### 构建 APK

```bash
cd android
.\gradlew.bat assembleDebug
# APK 输出: app/build/outputs/apk/debug/app-debug.apk
```

### 安装到设备

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 查看日志

```bash
adb logcat -s python.stderr:V python.stdout:V
```

### Git 操作

```bash
git add <files>
git commit -m "message"
git push
```

---

## 十二、关键文件索引（快速定位）

| 想改什么 | 去哪改 |
|----------|--------|
| 聊天主界面 UI | `MainActivity.kt` + `activity_main.xml` |
| 聊天逻辑 | `_core.py` + `role_player.py` + `prompt_builder.py` |
| 记忆存储/检索 | `orchestrator.py` + `_memory.py` |
| 记忆管理界面 | `MemoryManageActivity.kt` + `MemoryAdapter.kt` |
| 角色卡管理 | `_character.py` + `CharacterManageActivity.kt` + `CharacterEditActivity.kt` |
| 世界书 | `world_book.py` + `_world_book.py` + `WorldBookSection.kt` |
| 插件系统 | `plugin_manager.py` + `_plugins.py` + `PluginManageActivity.kt` |
| 主动消息 | `engine.py` + `ProactiveService.kt` + `ProactiveWorker.kt` |
| API 调用 | `deepseek.py` + `AppConfig.kt` |
| 设置页 | `SettingsActivity.kt` + `SettingsDetailActivity.kt` |
| 多会话 | `ConversationSessionManager.kt` |
| 构建配置 | `app/build.gradle.kts` |
| 颜色/主题 | `res/values/colors.xml` + `themes.xml` |
| Python 依赖 | `requirements.txt` |
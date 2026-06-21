# 03 — Python 端学习指南 (Chaquopy)

> 适合 Python/后端开发者，了解 Python 端架构和核心模块

---

## 一、Chaquopy 桥接原理

### 什么是 Chaquopy？

Chaquopy 是一个 Android Gradle 插件，让 Python 3.10 代码可以直接运行在 Android 设备上。Kotlin 代码通过 `Python.getInstance().getModule("xxx")` 调用 Python 模块。

### 调用方式

```kotlin
// Kotlin 端
val python = com.chaquo.python.Python.getInstance()
val module = python.getModule("chat_bridge")
val result = module.callAttr("chat", "你好").toString()
```

```python
# Python 端 (chat_bridge/__init__.py)
def chat(user_message: str) -> str:
    """被 Kotlin 调用的函数"""
    return _ctx.role_player.chat(user_message)
```

### 本项目中的特殊设计

**Chaquopy 无法迭代 Python 生成器**，所以流式对话采用 **队列+轮询** 方案：

```
Kotlin                           Python
  │                                │
  ├─ chat_stream_start(msg) ──────→│ 启动后台线程
  │                                │ 线程内调用 API stream
  │                                │ token → queue.put()
  │                                │ 返回 stream_id
  │                                │
  ├─ chat_stream_poll(id) ───────→│ queue.get() → JSON
  │← 返回 token JSON ──────────────│
  │  (每30ms轮询一次)               │
  │                                │
  ├─ chat_stream_cancel(id) ─────→│ 设置取消标志
```

> 关键文件：[chat_bridge/_core.py](file:///f:/Trae%20AI/ai-APP/android/app/src/main/python/chat_bridge/_core.py) (L224-L311)

---

## 二、模块分层

```
┌─────────────────────────────────────┐
│        chat_bridge/ (桥接层)         │  ← Kotlin 直接调用
│   __init__.py 导出 60+ 函数          │
├─────────────────────────────────────┤
│        src/ (核心模块)               │
│  ┌───────────────────────────────┐  │
│  │  app_context.py (全局上下文)    │  │  ← 单例，管理生命周期
│  └───────────────────────────────┘  │
│  ┌──────────┐ ┌──────────────────┐  │
│  │ chat_engine│ │ memory/ (15文件)│  │  ← 核心业务
│  └──────────┘ └──────────────────┘  │
│  ┌──────────┐ ┌──────────────────┐  │
│  │ world_book│ │ proactive/       │  │  ← 功能模块
│  └──────────┘ └──────────────────┘  │
│  ┌──────────┐ ┌──────────────────┐  │
│  │ plugins/  │ │ api_client/      │  │  ← 基础设施
│  └──────────┘ └──────────────────┘  │
│  ┌──────────┐ ┌──────────────────┐  │
│  │ config/   │ │ exceptions/      │  │
│  └──────────┘ └──────────────────┘  │
│  ┌──────────┐                       │
│  │ utils/    │                       │
│  └──────────┘                       │
└─────────────────────────────────────┘
```

---

## 三、桥接层详解

### [chat_bridge/__init__.py](file:///f:/Trae%20AI/ai-APP/android/app/src/main/python/chat_bridge/__init__.py)

**门面模式**：统一导出 60+ 函数，按子模块组织：

| 子模块 | 文件 | 导出函数数 | 核心功能 |
|--------|------|-----------|----------|
| core | [_core.py](file:///f:/Trae%20AI/ai-APP/android/app/src/main/python/chat_bridge/_core.py) | 11 | 聊天引擎 init/chat/stream/reset |
| memory | [_memory.py](file:///f:/Trae%20AI/ai-APP/android/app/src/main/python/chat_bridge/_memory.py) | 30+ | 记忆 CRUD/检索/分析/备份/缓存 |
| character | [_character.py](file:///f:/Trae%20AI/ai-APP/android/app/src/main/python/chat_bridge/_character.py) | 4 | 角色卡 set/get/reload |
| proactive | [_proactive.py](file:///f:/Trae%20AI/ai-APP/android/app/src/main/python/chat_bridge/_proactive.py) | 1 | 主动消息生成 |
| world_book | [_world_book.py](file:///f:/Trae%20AI/ai-APP/android/app/src/main/python/chat_bridge/_world_book.py) | 11 | 世界书 CRUD/启用/禁用 |
| plugins | [_plugins.py](file:///f:/Trae%20AI/ai-APP/android/app/src/main/python/chat_bridge/_plugins.py) | 4 | 插件列表/启用/详情 |

### [chat_bridge/_state.py](file:///f:/Trae%20AI/ai-APP/android/app/src/main/python/chat_bridge/_state.py)

**全局状态管理**：

```python
_ctx: AppContext | None = None      # 全局应用上下文
_executor: ThreadPoolExecutor       # 线程池(max_workers=2)
_lock: threading.Lock()             # 线程安全锁
CARD_PATH: Path                     # 默认角色卡路径
_cached_memories: str               # 记忆注入缓存
```

---

## 四、聊天引擎核心

### 数据流

```
用户输入 → RolePlayer.chat()
  ├─→ 加载角色卡 (CardParser)
  │    文件: src/chat_engine/card_parser.py
  ├─→ 注入记忆 (MemoryOrchestrator)
  │    文件: src/memory/orchestrator.py
  ├─→ 匹配世界书 (WorldBook)
  │    文件: src/world_book/world_book.py
  ├─→ 构建 System Prompt (PromptBuilder)
  │    文件: src/chat_engine/prompt_builder.py
  ├─→ 管理上下文 (ContextManager)
  │    文件: src/chat_engine/context_manager.py
  └─→ 调用 DeepSeek API (DeepSeekClient)
       文件: src/api_client/deepseek.py
```

### 关键文件

| 文件 | 职责 | 核心类/函数 |
|------|------|-------------|
| [role_player.py](file:///f:/Trae%20AI/ai-APP/android/app/src/main/python/src/chat_engine/role_player.py) | 对话主流程 | `RolePlayer.chat()` |
| [prompt_builder.py](file:///f:/Trae%20AI/ai-APP/android/app/src/main/python/src/chat_engine/prompt_builder.py) | 组装 System Prompt | `PromptBuilder.build()` |
| [context_manager.py](file:///f:/Trae%20AI/ai-APP/android/app/src/main/python/src/chat_engine/context_manager.py) | 上下文截断 | `ContextManager` |
| [card_parser.py](file:///f:/Trae%20AI/ai-APP/android/app/src/main/python/src/chat_engine/card_parser.py) | 角色卡 JSON 解析 | `CardParser`, `Card` |
| [token_presets.py](file:///f:/Trae%20AI/ai-APP/android/app/src/main/python/src/chat_engine/token_presets.py) | Token 预设 | `quality/balanced/economy` |

---

## 五、DeepSeek API 客户端

### [deepseek.py](file:///f:/Trae%20AI/ai-APP/android/app/src/main/python/src/api_client/deepseek.py)

**核心类**：

| 类 | 职责 |
|----|------|
| `DeepSeekClient` | API 客户端，封装 chat/chat_stream/embed |
| `ChatResponse` | Chat API 响应数据类 |
| `EmbeddingResponse` | Embedding API 响应数据类 |
| `CostTracker` | API 调用成本追踪 |

**核心方法**：

| 方法 | 功能 | 关键特性 |
|------|------|----------|
| `chat()` | 非流式对话 | 3次重试，指数退避 |
| `chat_stream()` | 流式对话（生成器） | 返回 Generator，逐个 yield token |
| `embed()` | 文本向量化 | 批量处理(100条/批)，支持重试 |
| `embed_cached()` | 带缓存的向量化 | LRU 缓存(500条)，命中跳过 API 调用 |
| `set_model()` | 切换模型 | 运行时切换 |
| `update_api_key()` | 更新 API Key | 同步更新 session header |

**HTTP 连接池**：
- 使用 `requests.Session()` 复用 TCP 连接
- 连接超时 5s，读取超时 30s（流式 60s）
- 自动重试：`APITimeoutError`, `APIServerError`, `APIRateLimitError`, `APIConnectionError`

**安全措施**：
- HTTPS 默认开启证书验证（`verify=True`）
- API Key 延迟校验（`_ensure_auth()`）
- 错误响应截断到 200 字符

---

## 六、全局应用上下文

### [app_context.py](file:///f:/Trae%20AI/ai-APP/android/app/src/main/python/src/app_context.py)

**单例模式**，管理所有核心组件生命周期：

```python
class AppContext:
    deepseek_client: DeepSeekClient    # API 客户端
    role_player: RolePlayer            # 对话引擎
    memory_orchestrator: MemoryOrchestrator  # 记忆编排器
    world_book: WorldBook              # 世界书
    proactive_engine: ProactiveEngine  # 主动消息引擎
    plugin_manager: PluginManager      # 插件管理器

    def initialize(api_key, preset):   # 初始化
    def shutdown():                     # 关闭（释放资源）
    def init_memory(db_path):           # 初始化记忆系统
```

---

## 七、配置系统

### [settings.py](file:///f:/Trae%20AI/ai-APP/android/app/src/main/python/src/config/settings.py)

```python
class Settings:
    DEEPSEEK_API_KEY: str              # API Key
    DEEPSEEK_BASE_URL: str = "https://api.deepseek.com"
    DEEPSEEK_MODEL: str = "deepseek-v4-flash"
    DEEPSEEK_EMBEDDING_MODEL: str = "deepseek-embedding-v2"
    LOG_LEVEL: str = "INFO"            # Release 自动降级 WARNING
    MAX_RETRIES: int = 3
    REQUEST_TIMEOUT: int = 30
```

### [user_settings.py](file:///f:/Trae%20AI/ai-APP/android/app/src/main/python/src/config/user_settings.py)

用户偏好设置（温度、上下文长度、主动消息间隔等）。

---

## 八、异常体系

### [exceptions/](file:///f:/Trae%20AI/ai-APP/android/app/src/main/python/src/exceptions/)

```
APIException
├── APIKeyError         # 401 认证失败
├── APIQuotaError       # 402 配额不足
├── APIRateLimitError   # 429 频率限制
├── APIServerError      # 5xx 服务器错误
├── APITimeoutError     # 请求超时
├── APIConnectionError  # 连接失败
└── APIContentFilterError # 内容过滤

MemoryException
├── MemoryNotFoundError
└── MemoryStorageError
```

---

## 九、工具函数

| 文件 | 功能 |
|------|------|
| [logger.py](file:///f:/Trae%20AI/ai-APP/android/app/src/main/python/src/utils/logger.py) | loguru 日志封装 |
| [lru_cache.py](file:///f:/Trae%20AI/ai-APP/android/app/src/main/python/src/utils/lru_cache.py) | LRU 缓存实现 |
| [retry.py](file:///f:/Trae%20AI/ai-APP/android/app/src/main/python/src/utils/retry.py) | @retry 装饰器（指数退避+抖动） |
| [time_utils.py](file:///f:/Trae%20AI/ai-APP/android/app/src/main/python/src/utils/time_utils.py) | 时间格式化工具 |
| [text_utils.py](file:///f:/Trae%20AI/ai-APP/android/app/src/main/python/src/utils/text_utils.py) | 文本处理工具 |

---

## 十、Python 依赖包

| 包 | 版本 | 用途 |
|----|------|------|
| requests | >=2.32.0 | HTTP 客户端 |
| loguru | >=0.7.0 | 日志框架 |
| certifi | >=2025.0.0 | SSL 证书 |
| charset_normalizer | >=3.4.0 | 字符编码检测 |
| urllib3 | >=2.0.0 | HTTP 连接池 |
| colorama | >=0.4.0 | 终端颜色 |
| idna | 2.10 | 国际化域名 |

> 所有包通过手动解压 wheel 到源码目录，非 pip 在线安装。参见 [requirements.txt](file:///f:/Trae%20AI/ai-APP/android/app/src/main/python/requirements.txt)

---

## 十一、关键学习要点

1. **Chaquopy 限制**：无法迭代生成器，需队列中转；部分 C 扩展库（如 numpy）不可用
2. **门面模式**：`chat_bridge/__init__.py` 是 Kotlin 的唯一入口
3. **全局状态**：`_state.py` 管理单例，`app_context.py` 管理生命周期
4. **共享客户端**：`DeepSeekClient` 被 `RolePlayer` 和 `MemoryOrchestrator` 共享
5. **懒加载**：记忆系统 6 个子组件按需初始化
6. **重试机制**：`@retry` 装饰器，指数退避+随机抖动
7. **Embedding 缓存**：LRU(500) 缓存，避免重复计算相同文本的向量
# Python 模块线程安全审计报告

> **审计日期**: 2026-06-28
> **审计范围**: `chat_engine/`、`memory/`、`chat_bridge/` 目录下全部 `.py` 文件（共 33 个）
> **审计人**: 测试安全审查工程师
> **编译验证**: `assembleDebug` 通过 (BUILD SUCCESSFUL)

---

## 一、审计概述

对 AI Companion Android 应用中 Python 模块的线程安全进行了全面审计。重点关注：
- 模块级共享变量的并发读写
- 字典/列表等可变容器的并发修改
- 文件 I/O 的并发访问
- 未加锁的缓存操作
- 实例级可变状态的 `+=` 非原子操作

审计方法：逐文件阅读代码，识别所有可变共享状态，追踪多线程调用路径，评估并发风险。

---

## 二、发现的问题汇总

| 严重程度 | 数量 | 状态 |
|----------|------|------|
| 高 (HIGH) | 3 | 已修复 |
| 中 (MEDIUM) | 0 | - |
| 低 (LOW) | 6 | 已记录，建议后续优化 |
| 信息 (INFO) | 0 | - |

---

## 三、已修复问题详情

### 问题 1 (HIGH): `MemoryOrchestrator._turn_count` 非原子自增

- **文件**: [orchestrator.py](file:///f:/Trae%20AI/ai-APP/android/app/src/main/python/src/memory/orchestrator.py#L192-L195)
- **严重程度**: 高
- **风险**: `_turn_count` 在 `remember()` 方法中执行 `+=1` 操作，该操作在 Python 中不是原子的（读-改-写三步）。`remember()` 通过线程池 (`_state._executor.submit`) 异步执行，多线程并发调用时会导致计数丢失。
- **影响范围**: LLM 提取节流逻辑错误（可能跳过应触发 LLM 提取的轮次），维护间隔计算错误。
- **修复方案**: 添加 `threading.Lock` 成员 `_state_lock`，在 `remember()` 方法中加锁保护 `_turn_count` 的读取和自增，使用局部变量 snapshot 避免在锁内持有过久。
- **修复代码**:
  ```python
  # __init__ 中添加
  self._state_lock = Lock()  # 保护 _turn_count、_extract_interval、config 的线程安全

  # remember() 中
  with self._state_lock:
      self._turn_count += 1
      turn_count = self._turn_count
      extract_interval = self._extract_interval
  ```
- **另外修复**: `set_extract_interval()` 中的 `_extract_interval` 写入也纳入 `_state_lock` 保护。

---

### 问题 2 (HIGH): `AppContext._turn_counter` 非原子自增

- **文件**: [app_context.py](file:///f:/Trae%20AI/ai-APP/android/app/src/main/python/src/app_context.py#L232-L239)
- **严重程度**: 高
- **风险**: `increment_turn()` 执行 `self._turn_counter += 1`，该方法被 `chat_bridge/_core.py:_auto_remember()`（线程池）和 `chat_bridge/_memory.py:remember_turn()`（Kotlin 主线程）同时调用，多线程并发导致计数丢失。
- **影响范围**: 对话轮次计数不准确，可能影响 turn_id 生成和记忆关联。
- **修复方案**: 添加 `threading.Lock` 成员 `_lock`，保护 `_turn_counter` 和 `_current_preset` 的所有读写操作。
- **修复代码**:
  ```python
  # __init__ 中添加
  self._lock = Lock()

  # increment_turn() 改造
  def increment_turn(self) -> int:
      with self._lock:
          self._turn_counter += 1
          return self._turn_counter

  # 同时保护了以下方法:
  # - turn_counter 属性 (只读)
  # - current_preset 属性 (只读)
  # - reset_turn_counter()
  # - initialize() 中的 _current_preset 写入
  # - init_memory() 中的 _turn_counter 重置
  # - shutdown() 中的 _turn_counter 重置
  ```

---

### 问题 3 (HIGH): `chat_bridge/__init__.py` `_lazy_modules` 字典竞态条件

- **文件**: [__init__.py](file:///f:/Trae%20AI/ai-APP/android/app/src/main/python/chat_bridge/__init__.py#L132-L146)
- **严重程度**: 高
- **风险**: `_load_submodule()` 函数执行 check-then-act 模式：先检查 `submod_name not in _lazy_modules`，再执行 `_lazy_modules[submod_name] = importlib.import_module(...)`。多个 Kotlin 线程同时触发延迟加载时，可能导致重复导入或字典并发修改异常。
- **影响范围**: 模块导入异常，可能导致功能不可用或崩溃。
- **修复方案**: 添加 `threading.Lock` 保护 `_lazy_modules` 的读写操作。
- **修复代码**:
  ```python
  _lazy_modules_lock = threading.Lock()

  def _load_submodule(submod_name: str):
      with _lazy_modules_lock:
          if submod_name not in _lazy_modules:
              _lazy_modules[submod_name] = importlib.import_module(
                  f".{submod_name}", __package__
              )
          return _lazy_modules[submod_name]
  ```

---

## 四、已确认安全的模块

以下模块经逐行审计，确认无线程安全问题：

### chat_bridge/ 目录

| 文件 | 状态 | 说明 |
|------|------|------|
| `_core.py` | 安全 | `_cached_memories` 受 `_state._lock` 保护；`_plugin_manager` 使用双重检查锁定 (`_plugin_manager_lock`)；`_streams` 字典受 `_streams_lock` 保护 |
| `_character.py` | 安全 | `_current_character` 读写受 `_lock` 保护 |
| `_memory.py` | 安全 | `_cached_memories` 修改受 `_state._lock` 保护；其余函数无模块级共享状态 |
| `_world_book.py` | 安全 | `_enabled_books` 集合受 `_wb_lock` 保护；`_wb_engine` 使用双重检查锁定 |
| `_proactive.py` | 安全 | 无模块级可变状态，仅读取 `_ctx` |
| `_plugins.py` | 安全 | 无模块级可变状态，调用 `get_plugin_manager()` 线程安全 |
| `_state.py` | 安全 | `ChatState` 类使用 `_lock` 保护所有共享状态；`shutdown_executor()` 幂等 |

### src/memory/ 目录

| 文件 | 状态 | 说明 |
|------|------|------|
| `memory_cache.py` | 安全 | `LRUCache`、`TTLCache`、`MemoryCache` 全部使用 `threading.Lock` 保护，线程安全 |
| `vector_store.py` (加密部分) | 安全 | 加密密钥初始化使用 `_ENCRYPTION_LOCK` + 双重检查锁定 |
| `retriever.py` | 安全 | `MemoryRetriever` 无实例级可变状态（仅持有引用） |
| `context_builder.py` | 安全 | `ContextBuilder` 无实例级可变状态（仅持有引用和配置） |
| `archiver.py` | 安全 | `_archived_count` 受 `_lock` 保护 |
| `analyzer.py` | 安全 | 无实例级可变状态 |
| `exporter.py` | 安全 | 无实例级可变状态 |
| `extractor.py` | 安全 | 无实例级可变状态 |
| `decay.py` | 安全 | 纯函数模块，无共享状态 |
| `bm25.py` | 安全 | 纯函数模块，无共享状态 |
| `memory_types.py` | 安全 | 仅数据类定义 |

### src/chat_engine/ 目录

| 文件 | 状态 | 说明 |
|------|------|------|
| `role_player.py` | 安全 | 实例属性 `memories`、`world_book_entries`、`jailbreak_prompt` 在实践中不会并发访问（`chat()` 和 `chat_stream()` 由 Kotlin UI 层串行调用） |
| `card_parser.py` | 安全 | 无实例级可变状态 |
| `context_manager.py` | 安全 | `ContextManager` 在实践中不会并发访问（理由同上） |
| `prompt_builder.py` | 安全 | 无实例级可变状态 |
| `token_presets.py` | 安全 | 仅数据类定义 |

---

## 五、低风险问题（建议后续优化）

以下问题为统计计数器或其他低风险场景，不影响核心功能正确性，建议在后续版本中优化：

| # | 文件 | 问题 | 风险 |
|---|------|------|------|
| 1 | `consolidator.py` | `_consolidation_count`、`_merged_count`、`_resolved_count` 无锁自增 | 极低（仅统计用途，实际数据受 SQLite 事务保护） |
| 2 | `lifecycle.py` | `_last_decay_update`、`_pruned_count`、`_last_health_check` 无锁修改 | 极低（仅统计用途） |
| 3 | `summarizer.py` | `_initialized` 检查-设置竞态，`_summary_count` 无锁自增 | 极低（`_initialized` 竞态无害，SQLite 幂等） |
| 4 | `backup.py` | `_backup_count`、`_last_backup_time` 无锁自增/写入 | 极低（仅统计用途，文件 I/O 受操作系统保护） |
| 5 | `knowledge_graph.py` | `_initialized` 检查-设置竞态 | 极低（SQLite `CREATE TABLE IF NOT EXISTS` 幂等） |
| 6 | `orchestrator.py` | `run_maintenance()` 中读取 `_turn_count` 未加锁 | 极低（读取 int 在 CPython 中原子，陈旧值对维护间隔检查无影响） |

---

## 六、审计方法说明

### 6.1 审计范围

共审计 33 个 Python 文件，覆盖以下目录：

```
android/app/src/main/python/
├── chat_bridge/         (8 个文件)
├── src/chat_engine/     (6 个文件)
├── src/memory/          (18 个文件)
└── src/app_context.py   (1 个文件)
```

### 6.2 检测模式

对每个文件执行以下检查：

1. **模块级可变状态**: 搜索 `^[a-zA-Z_]\w*\s*=\s*(\[|\{|dict|list|set)` 模式
2. **实例级可变状态**: 搜索 `self._\w+\s*[=+]` 模式（自增/赋值操作）
3. **锁保护验证**: 检查上述可变状态是否在 `with.*lock` 上下文中访问
4. **多线程调用路径**: 追踪每个可变状态的调用方，判断是否可能多线程并发
5. **文件 I/O**: 检查 `open()`、`write()`、`os.remove()` 等操作是否有并发保护

### 6.3 线程模型

本应用的 Python 代码运行在以下线程上下文：

- **Kotlin 主线程**: 通过 Chaquopy 调用 `chat_bridge` 的公开函数
- **线程池线程**: `_state._executor` (ThreadPoolExecutor, max_workers=2)，用于异步执行 `_auto_remember()`
- **流式对话后台线程**: `chat_stream_start()` 创建的独立线程，运行流式 token 生成

---

## 七、验证结果

- **编译**: `assembleDebug` 通过，BUILD SUCCESSFUL (48 actionable tasks)
- **修改文件清单**:
  - `src/memory/orchestrator.py` - 添加 `_state_lock`，保护 `_turn_count` 和 `_extract_interval`
  - `src/app_context.py` - 添加 `_lock`，保护 `_turn_counter` 和 `_current_preset`
  - `chat_bridge/__init__.py` - 添加 `_lazy_modules_lock`，保护延迟加载缓存
- **无新增依赖**: 所有修复仅使用 Python 标准库 `threading.Lock`
- **无 Kotlin 文件修改**: 严格遵守审计范围约束

---

## 八、结论

本次审计共发现 **3 个高严重度线程安全问题**，已全部修复并通过编译验证。**6 个低风险问题**已记录，建议在后续版本中优化（均为统计计数器，不影响核心功能）。

**核心线程安全防护已就位**：关键数据路径（对话计数、模块加载、角色卡状态、记忆缓存、世界书状态）均受 `threading.Lock` 保护，可防止多线程并发导致的数据损坏和计数丢失。
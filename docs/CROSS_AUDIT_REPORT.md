# 项目交叉审核报告

> 审核日期：2026-06-17
> 审核范围：ai-APP 项目全部模块（Python + Kotlin + XML）
> 审核方法：逐文件遍历 + 跨模块交叉验证

---

## 一、语法/编译错误 (P0)

### P0-01: `_memory.py` 中 `_state` 模块未导入导致 NameError

- **文件路径**: [android/app/src/main/python/chat_bridge/_memory.py](file:///f:/Trae AI/ai-APP/android/app/src/main/python/chat_bridge/_memory.py#L14)
- **行号**: 第 14 行（导入）、第 68 行和第 270 行（使用）
- **问题描述**:
  第 14 行 `from ._state import _ctx` 只导入了 `_ctx`，没有导入 `_state` 模块本身。
  第 68 行 `_state._cached_memories = []` 和第 270 行 `_state._cached_memories = []` 直接引用了未导入的 `_state` 模块名，运行时将抛出 `NameError: name '_state' is not defined`。
  对比 `_core.py` 第 18-19 行，正确做法是同时导入模块和具体变量：
  ```python
  from . import _state
  from ._state import _ctx, _CARD_DIR, _current_params
  ```
- **修复建议**: 在第 14 行之前添加 `from . import _state`，或将第 68/270 行改为通过 `_ctx` 间接访问。

---

### P0-02: `SettingsActivity.kt` 调用不存在的 Python 函数 `get_memory_count`

- **文件路径**: [android/app/src/main/java/com/aicompanion/app/SettingsActivity.kt](file:///f:/Trae AI/ai-APP/android/app/src/main/java/com/aicompanion/app/SettingsActivity.kt#L92)
- **行号**: 第 92 行
- **问题描述**:
  Kotlin 端调用 `module?.callAttr("get_memory_count")`，但 Python chat_bridge 中不存在名为 `get_memory_count` 的函数。
  正确的函数名是 `get_memory_stats`（定义在 [_memory.py](file:///f:/Trae AI/ai-APP/android/app/src/main/python/chat_bridge/_memory.py#L47) 第 47 行）。
  另外，`get_memory_stats` 返回的 JSON 中字段名为 `total`（见 [orchestrator.py](file:///f:/Trae AI/ai-APP/android/app/src/main/python/chat_bridge/../src/memory/orchestrator.py#L293) 第 293 行），而 Kotlin 端读取的是 `count` 字段，也需要同步修改。
- **修复建议**:
  1. 将 `callAttr("get_memory_count")` 改为 `callAttr("get_memory_stats")`
  2. 将 `json.optInt("count", 0)` 改为 `json.optInt("total", 0)`

---

### P0-03: `AndroidManifest.xml` 将 Kotlin `object` 错误声明为 `<service>`

- **文件路径**: [android/app/src/main/AndroidManifest.xml](file:///f:/Trae AI/ai-APP/android/app/src/main/AndroidManifest.xml#L97-L100)
- **行号**: 第 97-100 行
- **问题描述**:
  AndroidManifest 中声明了 `<service android:name=".ProactiveService" ... />`，但
  [ProactiveService.kt](file:///f:/Trae AI/ai-APP/android/app/src/main/java/com/aicompanion/app/ProactiveService.kt#L18) 是一个 Kotlin `object`（工具类），不是 Android `Service` 组件。
  系统尝试通过反射实例化该 Service 时会导致 `ClassCastException` 或 `InstantiationException`。
  实际使用是通过 `ProactiveService.schedule(context)` 静态调用，内部使用 WorkManager，不需要在 Manifest 中声明。
- **修复建议**: 删除 AndroidManifest.xml 中第 96-100 行的 `<service>` 声明。

---

## 二、逻辑缺陷 (P1)

### P1-01: `proactive_enabled` 默认值不一致 —— 使用两套不同的 SharedPreferences

- **涉及文件**:
  - [SettingsActivity.kt](file:///f:/Trae AI/ai-APP/android/app/src/main/java/com/aicompanion/app/SettingsActivity.kt#L79): `prefs.getBoolean("proactive_enabled", false)` —— 使用 `"app_prefs"`，默认 `false`
  - [SettingsDetailActivity.kt](file:///f:/Trae AI/ai-APP/android/app/src/main/java/com/aicompanion/app/SettingsDetailActivity.kt#L406): `prefs.getBoolean("proactive_enabled", false)` —— 使用 `"app_prefs"`，默认 `false`
  - [AppConfig.kt](file:///f:/Trae AI/ai-APP/android/app/src/main/java/com/aicompanion/app/AppConfig.kt#L135): `getBoolean(KEY_PROACTIVE_ENABLED, true)` —— 使用 `"ai_companion_prefs"`，默认 `true`
- **问题描述**:
  `SettingsActivity` 和 `SettingsDetailActivity` 使用 `"app_prefs"` 存储主动消息开关，而 `AppConfig` 使用 `"ai_companion_prefs"`。
  这导致同一个设置在两个不同的 SharedPreferences 文件中存储，值可能不同步。
  此外，`SettingsActivity`/`SettingsDetailActivity` 用 `false` 作为默认值，`AppConfig` 用 `true`。
  但实际上 `ProactiveWorker` 和 `ProactiveService` 都没有使用 `AppConfig.isProactiveEnabled()`，而是直接读 `"app_prefs"` 中的值，所以 `AppConfig` 中的定义是死代码。
- **修复建议**: 统一使用 `AppConfig.isProactiveEnabled()` 作为唯一数据源，将 `SettingsActivity` 和 `SettingsDetailActivity` 中的 `prefs.getBoolean("proactive_enabled", ...)` 替换为 `AppConfig.isProactiveEnabled(this)`。

---

### P1-02: `SettingsActivity` 中 `proactive_interval` 的默认值与 `AppConfig` 不一致

- **涉及文件**:
  - [SettingsActivity.kt](file:///f:/Trae AI/ai-APP/android/app/src/main/java/com/aicompanion/app/SettingsActivity.kt#L80): `prefs.getLong("proactive_interval", INTERVAL_MS[2])` —— 使用 `"app_prefs"`，默认 `10800000`（3小时）
  - [AppConfig.kt](file:///f:/Trae AI/ai-APP/android/app/src/main/java/com/aicompanion/app/AppConfig.kt#L34): `DEFAULT_INTERVAL_MS = 10800000L` —— 默认值恰好一致
- **问题描述**: 虽然默认值碰巧一致，但 `SettingsActivity` 和 `AppConfig` 使用不同的 SharedPreferences 实例，可能导致配置读写不一致。
- **修复建议**: 统一使用 `AppConfig` 中的方法读写配置。

---

### P1-03: 记忆导出使用 XOR 弱加密回退

- **文件路径**: [android/app/src/main/python/chat_bridge/_memory.py](file:///f:/Trae AI/ai-APP/android/app/src/main/python/chat_bridge/_memory.py#L350-L354)
- **行号**: 第 350-354 行
- **问题描述**:
  当 `cryptography` 库不可用时，记忆导出功能使用 XOR 加密作为回退方案。XOR 加密极弱，有密钥即可轻易解密。
  实际上，`cryptography` 库在 Chaquopy 环境中可通过 pip 安装，且 `vector_store.py` 的加密模块 `_init_encryption()` 已经妥善处理了 Fernet -> XOR 的降级路径。
  但 `_memory.py` 的导出加密是独立的实现，与 `vector_store.py` 的加密体系不共享逻辑。
- **修复建议**:
  1. 移除 XOR 回退，当 `cryptography` 不可用时返回错误提示而非使用弱加密。
  2. 或者复用 `vector_store.py` 中的 `_encrypt`/`_decrypt` 函数，统一加密逻辑。

---

### P1-04: `_core.py` 模块级插件管理器初始化可能导致提前加载

- **文件路径**: [android/app/src/main/python/chat_bridge/_core.py](file:///f:/Trae AI/ai-APP/android/app/src/main/python/chat_bridge/_core.py#L24)
- **行号**: 第 24 行
- **问题描述**:
  `_plugin_manager = get_plugin_manager()` 在模块加载时执行，可能在 chat_bridge 初始化完成前就触发插件系统的加载。
  虽然 `get_plugin_manager()` 返回的是全局单例，但如果在 `init()` 调用前就访问了 `_plugin_manager`，可能会导致插件加载时缺少必要的上下文。
- **修复建议**: 将 `_plugin_manager` 改为惰性初始化（lazy property），在首次使用时才调用 `get_plugin_manager()`。

---

### P1-05: `_core.py` 中 `_streams` 字典的 `pop` 操作可能遗漏锁保护

- **文件路径**: [android/app/src/main/python/chat_bridge/_core.py](file:///f:/Trae AI/ai-APP/android/app/src/main/python/chat_bridge/_core.py#L324-L325)
- **行号**: 第 324-325 行
- **问题描述**:
  第 324 行 `stream = _streams.get(stream_id)` 未加锁，第 350 行 `_streams.pop(stream_id, None)` 在锁内。
  虽然 `get` 在读时可能不需要锁，但如果在 `get` 和 `pop` 之间另一个线程修改了 `_streams`，可能导致不一致。
  实际上这里风险较低，因为 `get` 只读取，而 `pop` 在锁内。但为了一致性，建议将 `get` 也放入锁保护范围。
- **修复建议**: 将第 324 行的 `_streams.get(stream_id)` 也放入 `with _streams_lock:` 块中。

---

## 三、安全隐患 (P1)

### P3-01: 记忆导出密码未验证强度

- **文件路径**: [android/app/src/main/python/chat_bridge/_memory.py](file:///f:/Trae AI/ai-APP/android/app/src/main/python/chat_bridge/_memory.py#L340-L342)
- **行号**: 第 340-342 行
- **问题描述**: 记忆导出时，如果提供了密码，使用 PBKDF2 生成密钥。但没有对密码进行强度验证（如最小长度），空密码或弱密码仍可导出，只是不加密。
- **修复建议**: 添加密码最小长度验证（建议至少 8 位），弱密码警告。

---

### P3-02: 日志中可能泄露敏感信息

- **涉及文件**:
  - [_core.py](file:///f:/Trae AI/ai-APP/android/app/src/main/python/chat_bridge/_core.py#L271): `_log.error(f"[流式对话] 角色扮演器返回错误: {error_msg}")` 可能包含用户对话内容
  - [_memory.py](file:///f:/Trae AI/ai-APP/android/app/src/main/python/chat_bridge/_memory.py): 多处 `_log` 输出包含记忆内容
- **问题描述**: 在 Android 环境中，日志可能被其他应用通过 logcat 读取。当前代码在 `settings.py` 中已对 Release 构建将日志级别设为 WARNING，但部分日志使用了 `_log.info` 级别，可能包含用户对话内容。
- **修复建议**: 对所有包含用户数据的日志使用 `_log.debug` 级别（Release 构建中不输出），确保 Release 构建中不泄露用户对话。

---

### P3-03: `_memory.py` 导出时的密钥生成未使用安全随机数

- **文件路径**: [android/app/src/main/python/chat_bridge/_memory.py](file:///f:/Trae AI/ai-APP/android/app/src/main/python/chat_bridge/_memory.py#L341)
- **行号**: 第 341 行
- **问题描述**: `salt = os.urandom(16)` 正确使用了 `os.urandom`，但 PBKDF2 迭代次数 100000 在 2024+ 标准下偏低。NIST 建议至少 600000 次（SHA-256）。
- **修复建议**: 将 PBKDF2 迭代次数提高到 600000。

---

## 四、代码质量问题 (P2)

### P4-01: `_memory.py` 第 14 行导入不完整

- **文件路径**: [android/app/src/main/python/chat_bridge/_memory.py](file:///f:/Trae AI/ai-APP/android/app/src/main/python/chat_bridge/_memory.py#L14)
- **行号**: 第 14 行
- **问题描述**: 与 P0-01 相同问题。导入方式与 `_core.py` 不一致，应统一风格。
- **修复建议**: 统一为 `from . import _state` + `from ._state import _ctx` 的双重导入模式。

---

### P4-02: `_core.py` `chat_stream()` 函数中有死代码

- **文件路径**: [android/app/src/main/python/chat_bridge/_core.py](file:///f:/Trae AI/ai-APP/android/app/src/main/python/chat_bridge/_core.py#L388-L389)
- **行号**: 第 388-389 行
- **问题描述**: `chat_stream()` 的 `status == "batch"` 分支（第 388-389 行）实际永远不会执行，因为 `chat_stream_poll()` 从不返回 `"batch"` 状态。这是历史遗留代码。
- **修复建议**: 移除 `status == "batch"` 分支。

---

### P4-03: `SettingsActivity.kt` 多处重复获取 Python 模块

- **文件路径**: [android/app/src/main/java/com/aicompanion/app/SettingsActivity.kt](file:///f:/Trae AI/ai-APP/android/app/src/main/java/com/aicompanion/app/SettingsActivity.kt#L91-L134)
- **行号**: 第 91、106、122 行
- **问题描述**: 在 `refreshUI()` 中，三个不同的 Python 调用分别获取了三次 `Python.getInstance().getModule("chat_bridge")`，应复用。
- **修复建议**: 提取 `val module = com.chaquo.python.Python.getInstance().getModule("chat_bridge")` 到 `refreshUI()` 的顶部，复用。

---

### P4-04: `WorldBookSection.kt` 每次操作都重新获取 Python 实例

- **文件路径**: [android/app/src/main/java/com/aicompanion/app/WorldBookSection.kt](file:///f:/Trae AI/ai-APP/android/app/src/main/java/com/aicompanion/app/WorldBookSection.kt)
- **问题描述**: 每个方法都独立调用 `com.chaquo.python.Python.getInstance().getModule("chat_bridge")`，包括 `build()`、`addWorldBookRow()`、`createWorldBook()` 等，造成大量重复代码。
- **修复建议**: 在类中缓存 `module` 引用，或通过构造函数注入。

---

### P4-05: `_core.py` 中 `_auto_remember` 函数内部重复导入

- **文件路径**: [android/app/src/main/python/chat_bridge/_core.py](file:///f:/Trae AI/ai-APP/android/app/src/main/python/chat_bridge/_core.py#L400-L413)
- **行号**: 第 407、412 行
- **问题描述**: `_auto_remember` 函数内部重新导入 `format_timestamp_iso` 和 `get_logger`，而模块顶部已经导入了 `format_timestamp_iso`（第 16 行）和 `get_logger`（第 15 行）。这是不必要的重复导入。
- **修复建议**: 移除函数内部的重复导入，使用模块顶部的导入。

---

### P4-06: `MemoryManageActivity.kt` 中 `parseAndAddItems` 解析 `type` 字段不一致

- **文件路径**: [android/app/src/main/java/com/aicompanion/app/MemoryManageActivity.kt](file:///f:/Trae AI/ai-APP/android/app/src/main/java/com/aicompanion/app/MemoryManageActivity.kt#L293)
- **行号**: 第 293 行
- **问题描述**: Kotlin 端读取 `obj.optString("type", "unknown")`，但 Python 端的 `list_with_rowid()` 返回的字段名是 `memory_type`（见 [vector_store.py](file:///f:/Trae AI/ai-APP/android/app/src/main/python/chat_bridge/../src/memory/vector_store.py#L248) `to_dict()` 第 248 行）。这会导致 Kotlin 端始终显示 `"unknown"` 类型。
- **修复建议**: 将 `obj.optString("type", "unknown")` 改为 `obj.optString("memory_type", "unknown")`。

---

## 五、跨模块一致性问题

### C5-01: Kotlin 端调用 Python 函数名校验

| Kotlin 文件 | 调用的 Python 函数 | Python 是否存在 | 状态 |
|---|---|---|---|
| [SettingsActivity.kt:L92](file:///f:/Trae AI/ai-APP/android/app/src/main/java/com/aicompanion/app/SettingsActivity.kt#L92) | `get_memory_count` | 不存在（应为 `get_memory_stats`） | **错误** |
| [SettingsActivity.kt:L107](file:///f:/Trae AI/ai-APP/android/app/src/main/java/com/aicompanion/app/SettingsActivity.kt#L107) | `get_enabled_world_books` | 存在 | 正常 |
| [SettingsActivity.kt:L123](file:///f:/Trae AI/ai-APP/android/app/src/main/java/com/aicompanion/app/SettingsActivity.kt#L123) | `get_plugin_count` | 存在 | 正常 |
| [MainActivity.kt:L210](file:///f:/Trae AI/ai-APP/android/app/src/main/java/com/aicompanion/app/MainActivity.kt#L210) | `set_api_key` | 存在 | 正常 |
| [MainActivity.kt:L220](file:///f:/Trae AI/ai-APP/android/app/src/main/java/com/aicompanion/app/MainActivity.kt#L220) | `init` | 存在 | 正常 |
| [MainActivity.kt:L225](file:///f:/Trae AI/ai-APP/android/app/src/main/java/com/aicompanion/app/MainActivity.kt#L225) | `init_memory` | 存在 | 正常 |
| [MainActivity.kt:L236](file:///f:/Trae AI/ai-APP/android/app/src/main/java/com/aicompanion/app/MainActivity.kt#L236) | `set_character_card` | 存在 | 正常 |
| [MainActivity.kt:L293](file:///f:/Trae AI/ai-APP/android/app/src/main/java/com/aicompanion/app/MainActivity.kt#L293) | `set_character_card` | 存在 | 正常 |
| [MainActivity.kt:L294](file:///f:/Trae AI/ai-APP/android/app/src/main/java/com/aicompanion/app/MainActivity.kt#L294) | `reload_card` | 存在 | 正常 |
| [MainActivity.kt:L346](file:///f:/Trae AI/ai-APP/android/app/src/main/java/com/aicompanion/app/MainActivity.kt#L346) | `chat_stream_start` | 存在 | 正常 |
| [MainActivity.kt:L366](file:///f:/Trae AI/ai-APP/android/app/src/main/java/com/aicompanion/app/MainActivity.kt#L366) | `chat_stream_poll` | 存在 | 正常 |
| [MainActivity.kt:L567](file:///f:/Trae AI/ai-APP/android/app/src/main/java/com/aicompanion/app/MainActivity.kt#L567) | `reset` | 存在 | 正常 |
| [MainActivity.kt:L685](file:///f:/Trae AI/ai-APP/android/app/src/main/java/com/aicompanion/app/MainActivity.kt#L685) | `search_conversation` | 存在 | 正常 |
| [MainActivity.kt:L767](file:///f:/Trae AI/ai-APP/android/app/src/main/java/com/aicompanion/app/MainActivity.kt#L767) | `export_history` | 存在 | 正常 |
| [MainActivity.kt:L1037](file:///f:/Trae AI/ai-APP/android/app/src/main/java/com/aicompanion/app/MainActivity.kt#L1037) | `chat_stream_cancel` | 存在 | 正常 |
| [MemoryManageActivity.kt:L188](file:///f:/Trae AI/ai-APP/android/app/src/main/java/com/aicompanion/app/MemoryManageActivity.kt#L188) | `get_memory_stats` | 存在 | 正常 |
| [MemoryManageActivity.kt:L248](file:///f:/Trae AI/ai-APP/android/app/src/main/java/com/aicompanion/app/MemoryManageActivity.kt#L248) | `search_memories` | 存在 | 正常 |
| [MemoryManageActivity.kt:L251](file:///f:/Trae AI/ai-APP/android/app/src/main/java/com/aicompanion/app/MemoryManageActivity.kt#L251) | `list_memories` | 存在 | 正常 |
| [MemoryManageActivity.kt:L335](file:///f:/Trae AI/ai-APP/android/app/src/main/java/com/aicompanion/app/MemoryManageActivity.kt#L335) | `delete_memory` | 存在 | 正常 |
| [MemoryManageActivity.kt:L370](file:///f:/Trae AI/ai-APP/android/app/src/main/java/com/aicompanion/app/MemoryManageActivity.kt#L370) | `clear_memories` | 存在 | 正常 |
| [PluginViewModel.kt:L48](file:///f:/Trae AI/ai-APP/android/app/src/main/java/com/aicompanion/app/PluginViewModel.kt#L48) | `list_plugins` | 存在 | 正常 |
| [PluginViewModel.kt:L74](file:///f:/Trae AI/ai-APP/android/app/src/main/java/com/aicompanion/app/PluginViewModel.kt#L74) | `toggle_plugin` | 存在 | 正常 |
| [PluginViewModel.kt:L96](file:///f:/Trae AI/ai-APP/android/app/src/main/java/com/aicompanion/app/PluginViewModel.kt#L96) | `get_plugin_detail` | 存在 | 正常 |
| [WorldBookSection.kt](file:///f:/Trae AI/ai-APP/android/app/src/main/java/com/aicompanion/app/WorldBookSection.kt) | `list_world_books`, `get_enabled_world_books`, `enable_world_book`, `disable_world_book`, `create_world_book`, `delete_world_book`, `get_world_book`, `update_world_book`, `add_world_book_entry`, `update_world_book_entry`, `delete_world_book_entry`, `validate_world_book` | 全部存在 | 正常 |
| [SettingsDetailActivity.kt:L145](file:///f:/Trae AI/ai-APP/android/app/src/main/java/com/aicompanion/app/SettingsDetailActivity.kt#L145) | `set_api_key` | 存在 | 正常 |
| [SettingsDetailActivity.kt:L321](file:///f:/Trae AI/ai-APP/android/app/src/main/java/com/aicompanion/app/SettingsDetailActivity.kt#L321) | `reset` | 存在 | 正常 |
| [SettingsDetailActivity.kt:L343](file:///f:/Trae AI/ai-APP/android/app/src/main/java/com/aicompanion/app/SettingsDetailActivity.kt#L343) | `clear_memories` | 存在 | 正常 |
| [SettingsDetailActivity.kt:L692](file:///f:/Trae AI/ai-APP/android/app/src/main/java/com/aicompanion/app/SettingsDetailActivity.kt#L692) | `apply_params` | 存在 | 正常 |

**结论**: 仅 `SettingsActivity.kt:L92` 的 `get_memory_count` 调用存在问题（P0-02），其余所有 Kotlin-Python 桥接调用均正确匹配。

---

### C5-02: AndroidManifest Activity 声明检查

| Manifest 中声明的 Activity | 对应的 .kt 文件 | 状态 |
|---|---|---|
| `.MainActivity` | [MainActivity.kt](file:///f:/Trae AI/ai-APP/android/app/src/main/java/com/aicompanion/app/MainActivity.kt) | 正常 |
| `.MemoryManageActivity` | [MemoryManageActivity.kt](file:///f:/Trae AI/ai-APP/android/app/src/main/java/com/aicompanion/app/MemoryManageActivity.kt) | 正常 |
| `.SettingsActivity` | [SettingsActivity.kt](file:///f:/Trae AI/ai-APP/android/app/src/main/java/com/aicompanion/app/SettingsActivity.kt) | 正常 |
| `.SettingsDetailActivity` | [SettingsDetailActivity.kt](file:///f:/Trae AI/ai-APP/android/app/src/main/java/com/aicompanion/app/SettingsDetailActivity.kt) | 正常 |
| `.CharacterManageActivity` | [CharacterManageActivity.kt](file:///f:/Trae AI/ai-APP/android/app/src/main/java/com/aicompanion/app/CharacterManageActivity.kt) | 正常 |
| `.CharacterSelectActivity` | [CharacterSelectActivity.kt](file:///f:/Trae AI/ai-APP/android/app/src/main/java/com/aicompanion/app/CharacterSelectActivity.kt) | 正常 |
| `.CharacterActivity` | [CharacterActivity.kt](file:///f:/Trae AI/ai-APP/android/app/src/main/java/com/aicompanion/app/CharacterActivity.kt) | 正常 |
| `.CharacterEditActivity` | [CharacterEditActivity.kt](file:///f:/Trae AI/ai-APP/android/app/src/main/java/com/aicompanion/app/CharacterEditActivity.kt) | 正常 |
| `.PluginManageActivity` | [PluginManageActivity.kt](file:///f:/Trae AI/ai-APP/android/app/src/main/java/com/aicompanion/app/PluginManageActivity.kt) | 正常 |
| `.ProactiveService` (Service) | [ProactiveService.kt](file:///f:/Trae AI/ai-APP/android/app/src/main/java/com/aicompanion/app/ProactiveService.kt) | **错误**（应为 object，非 Service） |

**结论**: 所有 Activity 声明都与 .kt 文件匹配。唯一问题是 `ProactiveService` 被错误声明为 `<service>`（P0-03）。

---

### C5-03: Python bridge `__init__.py` 导出与 `__all__` 一致性

- **文件路径**: [android/app/src/main/python/chat_bridge/__init__.py](file:///f:/Trae AI/ai-APP/android/app/src/main/python/chat_bridge/__init__.py)
- **问题描述**: `__init__.py` 的 `__all__` 列表中包含了 `chat_stream_cancel`（第 130 行），且 `_core.py` 中存在对应函数定义。但 `__init__.py` 的 `from ._core import` 语句（第 24-39 行）中未显式导入 `chat_stream_cancel`。虽然 `__all__` 不影响实际导入行为，但保持一致性更佳。
- **修复建议**: 在 `__init__.py` 的 `from ._core import` 中添加 `chat_stream_cancel`。

---

### C5-04: 空异常处理检查

| 检查项 | 检查范围 | 结果 |
|---|---|---|
| Python `except ... : pass` | `android/app/src/main/python/` 全部项目代码 | **未发现** |
| Kotlin `catch {}` 空块 | `android/app/src/main/java/` 全部 .kt 文件 | **未发现** |

**结论**: 项目代码中不存在空异常处理（静默吞错）问题。所有异常处理都有日志记录或错误上报。

---

### C5-05: 硬编码密钥检查

| 检查项 | 检查模式 | 结果 |
|---|---|---|
| DeepSeek API Key (`sk-` 前缀) | `sk-[a-zA-Z0-9]{20,}` | **未发现** |
| 其他密钥/密码 | 全文搜索 | **未发现** |

**结论**: 项目代码中不存在硬编码的 API Key 或密码。API Key 通过 `SettingsDetailActivity` 用户输入和 `AppConfig` 加密存储，符合安全规范。

---

### C5-06: 资源泄漏检查

| 检查项 | 检查范围 | 结果 |
|---|---|---|
| 文件句柄 | `open()` 调用是否使用 `with` 上下文管理器 | **全部正确** |
| 数据库连接 | SQLite 连接是否有关闭逻辑 | **正确** |
| HTTP Session | `requests.Session` 是否关闭 | **正确** |

**结论**: 项目代码中不存在资源泄漏问题。文件操作、数据库连接、HTTP 会话均有正确的关闭逻辑。

---

### C5-07: 布局文件控件 ID 引用检查

所有布局文件使用 Android ViewBinding 机制，Kotlin 代码通过自动生成的绑定类访问控件。

| 布局文件 | 对应的 Binding/引用方式 | Kotlin 使用文件 | 状态 |
|---|---|---|---|
| `activity_main.xml` | `ActivityMainBinding` | MainActivity.kt | 正常 |
| `activity_settings.xml` | `ActivitySettingsBinding` | SettingsActivity.kt | 正常 |
| `activity_character.xml` | `ActivityCharacterBinding` | CharacterActivity.kt | 正常 |
| `activity_memory_manage.xml` | `ActivityMemoryManageBinding` | MemoryManageActivity.kt | 正常 |
| `activity_plugin_manage.xml` | 直接 `findViewById` | PluginManageActivity.kt | 正常 |
| `item_message.xml` | Adapter `findViewById` | ChatAdapter.kt | 正常 |
| `item_memory.xml` | Adapter `findViewById` | MemoryAdapter.kt | 正常 |
| `item_plugin_card.xml` | Adapter `findViewById` | PluginAdapter.kt | 正常 |
| `item_character_card.xml` | Adapter `findViewById` | CharacterListAdapter.kt | 正常 |
| `activity_character_manage.xml` | `ActivityCharacterManageBinding` | CharacterManageActivity.kt | 正常 |
| `activity_character_edit.xml` | `ActivityCharacterEditBinding` | CharacterEditActivity.kt | 正常 |

**结论**: 所有布局文件中的控件 ID 均被对应的 Kotlin 代码正确引用，无孤立 ID 或缺失引用。

---

## 六、未完成标记 (TODO/FIXME/HACK)

### 项目代码中的标记

| 文件 | 行号 | 内容 |
|---|---|---|
| [src/config/settings.py](file:///f:/Trae AI/ai-APP/android/app/src/main/python/src/config/settings.py#L60) | 60 | `# TODO(P3-P6): Android 端 .env 文件机制不可用，需通过 Kotlin 侧 SharedPreferences 传递 API Key 给 Python 侧` |

> 注：`urllib3`、`requests`、`certifi` 等第三方库中的 TODO/FIXME 标记不在审核范围内。

---

## 七、总结

### 问题统计

| 等级 | 数量 | 说明 |
|---|---|---|
| **P0** (语法/编译错误) | **3** | 包括 1 个 Python NameError、1 个 Kotlin-Python 函数名不匹配、1 个 AndroidManifest 错误声明 |
| **P1** (逻辑缺陷/安全隐患) | **7** | 包括配置不一致、弱加密回退、SharedPreferences 分裂、日志泄露风险等 |
| **P2** (代码质量问题) | **6** | 包括重复导入、死代码、重复 Python 实例获取、字段名不一致等 |

### 专项检查结果

| 检查项 | 结果 |
|---|---|
| 空异常处理 | 未发现（所有异常处理均有日志记录） |
| 硬编码密钥 | 未发现（API Key 通过加密存储） |
| 资源泄漏 | 未发现（文件/数据库/HTTP 连接均有正确关闭逻辑） |
| 布局控件 ID 引用 | 全部正确（ViewBinding + findViewById 均匹配） |

### 整体评价

项目代码整体结构清晰，Kotlin-Python 桥接层的 28 个函数调用中仅 1 个存在错误（准确率 96.4%），Activity 声明与 .kt 文件匹配度 100%。

**最严重的问题**是 P0-01（`_memory.py` 运行时会崩溃）和 P0-02（`SettingsActivity` 记忆统计永远显示"加载中..."），这两个问题会导致用户可见的功能异常。P0-03（Manifest 中的错误 Service 声明）可能导致应用启动时崩溃。

**建议优先修复顺序**：P0-01 > P0-02 > P0-03 > P1-01 > P1-02 > P1-03 > P4-06 > 其余 P2 问题。
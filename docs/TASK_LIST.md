# AI Companion (AI伴侣) -- 任务清单

> 文档版本：V1.1 | 创建时间：2026-06-10 | 最后更新：2026-06-12

---

## 任务状态图例

| 标记 | 含义 |
|:---:|------|
| 待开始 | 任务尚未分配开发 |
| 进行中 | 开发中 |
| 已完成 | 已通过验收 |
| 已取消 | 不再需要的任务 |

---

## 一、P4 记忆系统收尾 (当前阶段)

### T4.1 -- 手机端验证完整记忆链路  [P0]

| 属性 | 值 |
|------|------|
| **状态** | 已完成 |
| **负责人** | @mcp-devops (构建) + 人工 (手机测试) |
| **预估工时** | 0.5天 |
| **前置依赖** | USB连接真机 |
| **描述** | 构建APK安装到手机，执行端到端记忆链路验证: 对话→自动记忆→新对话检索→AI提及 |
| **完成日期** | 2026-06-10 |
| **验证结果** | Chat对话✅ 记忆提取(5条)✅ 记忆存储✅ 异步存储✅ JSON通信✅ Embedding降级✅ |

**任务步骤**:
1. 执行 `gradlew assembleDebug` 构建APK
2. adb install 安装到手机
3. 测试流程: (a) 告诉AI个人信息 (b) 开始新对话 (c) 询问AI是否记得
4. logcat 确认记忆存储/检索/注入日志
5. 记录测试结果

**验收标准**:
- AI能在新对话中提及之前对话的个性化信息
- 日志完整显示记忆流程: extract→embed→store→retrieve→inject

---

### T4.2 -- 记忆可视化: 后端接口扩展  [P2]

| 属性 | 值 |
|------|------|
| **状态** | 已完成 |
| **负责人** | @backend-dev |
| **预估工时** | 0.5天 |
| **前置依赖** | T4.1 验证通过 |
| **描述** | 在 chat_bridge.py 中新增记忆CRUD接口：list_memories / get_memory / update_memory / delete_memory / clear_memories |
| **完成日期** | 2026-06-10 |

**具体任务**:
- [ ] `list_memories(type_filter="", page=1, page_size=50)` → 分页列表
- [ ] `get_memory(memory_id)` → 单条详情（含embedding维度等）
- [ ] `update_memory(memory_id, content)` → 更新内容（需重新embed）
- [ ] `delete_memory(memory_id)` → 删除单条
- [ ] `clear_memories()` → 清空全部（不重置_turn_counter）
- [ ] `search_memories(keyword)` → 关键字模糊搜索

**验收标准**: 每个接口返回标准JSON，异常有错误信息

---

### T4.3 -- 记忆可视化: Android UI  [P2]

| 属性 | 值 |
|------|------|
| **状态** | 已完成 |
| **负责人** | @frontend-dev |
| **预估工时** | 1天 |
| **前置依赖** | T4.2 完成 |
| **描述** | 在设置面板增加记忆管理界面 |
| **完成日期** | 2026-06-10 |

**具体任务**:
- [ ] 新增 `MemoryAdapter.kt` (RecyclerView 展示记忆列表)
- [ ] 新增 `activity_memory_list.xml` 或 在现有布局中增加Fragment
- [ ] 记忆列表页: 显示内容摘要/类型/时间，点击进入详情
- [ ] 记忆详情弹窗: 完整内容/类型/重要性/衰减因子/时间戳
- [ ] 编辑功能: 输入框修改content → 调用update_memory
- [ ] 删除功能: 滑动删除 / 长按菜单
- [ ] 清空功能: AlertDialog二次确认
- [ ] 搜索功能: SearchView过滤

**验收标准**: 见 REQUIREMENTS.md P4.2

---

### T4.4 -- LLM 提取模式节流 [P2]

| 属性 | 值 |
|------|------|
| **状态** | 已完成 |
| **负责人** | @backend-dev |
| **预估工时** | 0.5天 |
| **前置依赖** | T4.1 验证通过（确认基础链路正常） |
| **描述** | 在 orchestrator.remember() 中实现每N轮启用LLM提取 |
| **完成日期** | 2026-06-11 |

**具体任务**:
- [x] orchestrator 中 `_extract_interval` 改为可配置（已初始化=5）
- [x] remember() 中: `if self._turn_count % self._extract_interval == 0 → mode="llm" else → mode="rule"`
- [x] chat_bridge 新增 `set_extract_interval(n)` 接口
- [x] 日志区分当前使用的提取模式
- [ ] （可选）Android设置中增加调节选项

**验收标准**: 见 REQUIREMENTS.md P4.3

---

### T4.5 -- Embedding 缓存 (LRU) [P3]

| 属性 | 值 |
|------|------|
| **状态** | 已完成 |
| **负责人** | @backend-dev |
| **预估工时** | 0.5天 |
| **前置依赖** | 无（独立功能） |
| **描述** | 在 DeepSeekClient 中增加 LRU 缓存层 |
| **完成日期** | 2026-06-11 |

**具体任务**:
- [x] 新增 `src/utils/lru_cache.py` (使用 OrderedDict 实现)
- [x] DeepSeekClient 新增 `embed_cached(texts)` 方法
- [x] orchestrator 和 retriever 中 embed 调用切换到 `embed_cached`
- [x] 日志输出缓存命中率统计

**验收标准**: 见 REQUIREMENTS.md P4.4

---

### T4.6 -- 全量回退 OOM 防护 [P3]

| 属性 | 值 |
|------|------|
| **状态** | 已完成 |
| **负责人** | @backend-dev |
| **预估工时** | 0.5天 |
| **前置依赖** | T4.1 验证通过 |
| **描述** | retriever 全量回退时改为分页加载 |
| **完成日期** | 2026-06-11 |

**具体任务**:
- [x] VectorStore 新增 `get_page(offset, limit)` 方法
- [x] retriever 中 `_fallback_full_search()` 改为分页循环
- [x] 单页500条，保留top_k*3候选
- [x] 最后合并排序取 top_k

**验收标准**: 见 REQUIREMENTS.md P4.5

---

### T4.7 -- 记忆归档/摘要 [P4]

| 属性 | 值 |
|------|------|
| **状态** | 已完成 |
| **负责人** | @backend-dev |
| **预估工时** | 1天 |
| **前置依赖** | T4.4 (LLM提取节流已完成) |
| **描述** | 实现记忆归档流水线 |
| **完成日期** | 2026-06-14 |

**具体任务**:
- [x] 新增 `src/memory/archiver.py`
- [x] VectorStore 新增 archived 标记字段
- [x] 归档触发条件: memory_count > 500
- [x] 摘要生成: LLM 将50条记忆压缩为3条摘要
- [x] 检索权重: archived=0.2 倍衰减因子

---

## 二、P5 主动推送/通知

### T5.1 -- 定时主动消息: chat_bridge 接口 [P1]

| 属性 | 值 |
|------|------|
| **状态** | 已完成 |
| **负责人** | @backend-dev |
| **预估工时** | 0.5天 |
| **前置依赖** | T4.1 验证通过 |
| **描述** | 封装已有 proactive 引擎为 chat_bridge 可调用接口 |
| **完成日期** | 2026-06-13 |

**具体任务**:
- [x] `generate_proactive_message()` → 调用 decision_engine + topic_generator → 返回消息文本
- [x] 遵守 UserSettings 中的 proactive_enabled/wake_time/sleep_time
- [x] 调用前检查是否在活跃时间段内

**验收标准**: 调用返回符合角色人设的主动消息文本

---

### T5.2 -- Android 系统通知 + 前台服务 [P1]

| 属性 | 值 |
|------|------|
| **状态** | 已完成 |
| **负责人** | @frontend-dev |
| **预估工时** | 1天 |
| **前置依赖** | T5.1 完成 |
| **描述** | Android 端通知和后台保活 |
| **完成日期** | 2026-06-13 |

**具体任务**:
- [x] 新建 `NotificationHelper.kt` (NotificationChannel 创建)
- [x] 新建 `ProactiveService.kt` (前台服务，调用Python生成消息)
- [x] AndroidManifest.xml 注册 Service + 权限（2026-06-14 修复遗漏）
- [x] 通知点击 → 启动 MainActivity（PendingIntent 已实现）
- [x] notification icon 资源 (ic_notification.xml)（2026-06-14 完成）

**验收标准**: 见 REQUIREMENTS.md P5.2

---

### T5.3 -- WorkManager 定时器 [P2]

| 属性 | 值 |
|------|------|
| **状态** | 已完成 |
| **负责人** | @frontend-dev |
| **预估工时** | 0.5天 |
| **前置依赖** | T5.2 完成 |
| **描述** | 用WorkManager实现可靠定时触发 |
| **完成日期** | 2026-06-13 |

**具体任务**:
- [x] 新建 `ProactiveWorker.kt` (CoroutineWorker)
- [x] PeriodicWorkRequest (最小间隔15分钟)
- [x] 约束: NetworkType.CONNECTED
- [x] 在 MainActivity.initPython() 成功后 enqueue

**验收标准**: 定时触发 → Python proactive引擎 → 通知弹出

---

## 三、P6 多角色/扩展

### T6.1 -- 流式输出 (SSE) [P2]

| 属性 | 值 |
|------|------|
| **状态** | 已完成 |
| **负责人** | @backend-dev + @frontend-dev |
| **预估工时** | 1.5天 |
| **前置依赖** | T4.1 验证通过 |
| **描述** | 支持流式输出，逐token显示AI回复 |
| **完成日期** | 2026-06-14 |

**具体任务**:
- [x] DeepSeekClient 新增 `chat_stream()` (stream=True, yield 每个delta)
- [x] chat_bridge 新增 `chat_stream()` 接口
- [x] Kotlin 端通过 Chaquopy 迭代器读取
- [x] RecyclerView 逐token更新最后一条消息
- [x] 发送中禁用输入，完成后恢复

---

### T6.2 -- 多角色卡切换 [P3]

| 属性 | 值 |
|------|------|
| **状态** | 已完成 |
| **负责人** | @backend-dev + @frontend-dev |
| **预估工时** | 1天 |
| **前置依赖** | P4-P5 稳定 |
| **描述** | 支持多个角色轮流对话 |
| **完成日期** | 2026-06-14 |

**具体任务**:
- [x] CharacterStorage 多角色卡持久化存储
- [x] CharacterSelectActivity 角色卡片列表（DiffUtil 高效刷新）
- [x] 选择角色后调用 set_character_card() 切换
- [x] 编辑入口跳转 CharacterEditActivity

---

### T6.3 -- 自定义角色卡 [P3]

| 属性 | 值 |
|------|------|
| **状态** | 已完成 |
| **负责人** | @frontend-dev |
| **预估工时** | 1天 |
| **前置依赖** | T6.2 完成 |
| **描述** | 用户自建角色卡 |
| **完成日期** | 2026-06-14 |

**具体任务**:
- [x] 新建 `activity_character_edit.xml` 布局
- [x] 表单: 姓名/性格/说话风格/背景/问候语
- [x] CharacterData 数据模型
- [x] 保存到 SharedPreferences 本地存储

---

### T6.4 -- 世界书 Android 集成 [P3]

| 属性 | 值 |
|------|------|
| **状态** | 已完成 |
| **负责人** | @frontend-dev |
| **预估工时** | 1天 |
| **前置依赖** | P4-P5 稳定 |
| **描述** | 设置面板中世界书选择与启用 |
| **完成日期** | 2026-06-13 |

**具体任务**:
- [x] chat_bridge 已有 `load_world_book(name)` / `list_world_books()`
- [x] Android 设置面板中世界书列表展示 + 勾选启用

---

### T6.5 -- 对话历史导出 [P3]

| 属性 | 值 |
|------|------|
| **状态** | 已完成 |
| **负责人** | @frontend-dev |
| **预估工时** | 0.5天 |
| **前置依赖** | P4-P5 稳定 |
| **描述** | 导出对话为文件 |
| **完成日期** | 2026-06-14 |

**具体任务**:
- [x] chat_bridge 新增 `export_history(format="json")`
- [x] 支持 JSON/TXT 两种导出格式
- [x] 保存到 Download 目录
- [x] 通过 FileProvider + ShareSheet 分享

---

### T6.6 -- 深色模式 [P4]

| 属性 | 值 |
|------|------|
| **状态** | 已完成 |
| **负责人** | @frontend-dev |
| **预估工时** | 0.5天 |
| **前置依赖** | 无 |
| **描述** | Material3 暗色主题适配 |
| **完成日期** | 2026-06-14 |

**具体任务**:
- [x] 新建 `res/values-night/themes.xml`（深色主题）
- [x] 新建 `res/values-night/colors.xml`（深紫黑背景配色）
- [x] 全部颜色资源覆盖（背景/气泡/文字/毛玻璃/辅助色）

---

### T6.7 -- 对话持久化 [P3]

| 属性 | 值 |
|------|------|
| **状态** | 已完成 |
| **负责人** | @frontend-dev |
| **预估工时** | 0.25天 |
| **前置依赖** | 无 |
| **描述** | 对话历史本地持久化，重启App后恢复 |
| **完成日期** | 2026-06-14 |

**具体任务**:
- [x] ChatAdapter 新增 `getMessages()` / `replaceAll()` 方法
- [x] MainActivity `saveConversation()`: JSON 序列化写入 filesDir
- [x] MainActivity `loadConversation()`: 启动时恢复对话
- [x] 每次 AI 回复完成时自动保存

---

## 四、P6 世界书模块 (已完成)

> 详细任务计划见 [TASK_WORLD_BOOK.md](file:///f:/Trae AI/ai-APP/docs/TASK_WORLD_BOOK.md)

### WB-T1 -- chat_bridge 世界书桥接接口 [P0]

| 属性 | 值 |
|------|------|
| **状态** | 已完成 |
| **负责人** | @backend-dev |
| **预估工时** | 0.5天 |
| **前置依赖** | 无 |
| **描述** | 新建 _world_book.py，封装 WorldBookEngine 为 Chaquopy 可调用接口 |
| **完成日期** | 2026-06-14 |

**具体任务**:
- [x] `list_world_books()` / `enable_world_book()` / `disable_world_book()` / `get_enabled_world_books()`
- [x] `create_world_book()` / `delete_world_book()` / `get_world_book()` / `update_world_book()`
- [x] `_match_and_inject_for_all()` / `_reset_round_for_all()` 内部函数
- [x] `__init__.py` 导出所有世界书接口

### WB-T2 -- 世界书注入对话流程 [P0]

| 属性 | 值 |
|------|------|
| **状态** | 已完成 |
| **负责人** | @backend-dev |
| **预估工时** | 0.5天 |
| **前置依赖** | WB-T1 |
| **描述** | 在 _core.py 的 chat() 中自动注入世界书上下文 |
| **完成日期** | 2026-06-14 |

**具体任务**:
- [x] _core.py 的 chat() / chat_stream() 中调用 _match_and_inject_for_all()
- [x] 世界书上下文通过 player.world_book_entries 注入 System Prompt
- [x] reset() 中调用 _reset_round_for_all() 重置轮次计数
- [x] role_player.py 已有 world_book_entries 属性和 PromptBuilder 集成

### WB-T3 -- 打包世界书数据文件 [P1]

| 属性 | 值 |
|------|------|
| **状态** | 已完成 |
| **负责人** | @mcp-devops |
| **预估工时** | 0.25天 |
| **前置依赖** | 无 |
| **描述** | 确保 data/world_books/ 中的 JSON 随 APK 打包 |
| **完成日期** | 2026-06-14 |

**具体任务**:
- [x] cat_cafe_world.json 和 reality_world.json 已在 python/data/world_books/ 目录
- [x] _world_book.py 使用 Path(_PYTHON_ROOT) / "data" / "world_books" 路径加载

### WB-T4 -- 设置页面世界书选择 UI [P0]

| 属性 | 值 |
|------|------|
| **状态** | 已完成 |
| **负责人** | @frontend-dev |
| **预估工时** | 0.5天 |
| **前置依赖** | WB-T1, WB-T3 |
| **描述** | 在 SettingsActivity 增加世界书选择卡片，SettingsDetailActivity 增加完整管理UI |
| **完成日期** | 2026-06-14 |

**具体任务**:
- [x] activity_settings.xml 世界书卡片（cardWorldBook + tvWorldBookSummary）
- [x] strings.xml 世界书相关字符串资源
- [x] SettingsActivity 世界书摘要显示（已启用N本 / 未启用）
- [x] SettingsDetailActivity 完整世界书管理：列表/开关/创建/编辑/删除

### WB-T5 -- 端到端测试 [P1]

| 属性 | 值 |
|------|------|
| **状态** | 待人工验证 |
| **负责人** | 人工 |
| **预估工时** | 0.25天 |
| **前置依赖** | WB-T1, WB-T2, WB-T3, WB-T4 |
| **描述** | 手机端验证完整世界书链路 |

**具体任务**:
- [ ] 构建 APK 安装到手机
- [ ] 设置 → 世界书 → 启用"猫咖世界观"
- [ ] 对话中提及日常话题，验证 AI 回复体现现实世界观设定
- [ ] 切换回"不使用"，验证世界书不再注入
- [ ] 验证所有操作无崩溃

### WB-T6 -- 世界书持久化恢复 [P2]

| 属性 | 值 |
|------|------|
| **状态** | 已完成 |
| **负责人** | @frontend-dev |
| **预估工时** | 0.25天 |
| **前置依赖** | WB-T4 |
| **描述** | 世界书选择持久化到 SharedPreferences，启动时自动恢复 |
| **完成日期** | 2026-06-14 |

**具体任务**:
- [x] SettingsDetailActivity.saveEnabledWorldBooks() 保存到 SharedPreferences
- [x] MainActivity.initPython() 启动时恢复已启用的世界书
- [x] 启用/禁用/删除操作后自动保存

### WB-T7 -- 条目填充 CRUD [P1]

| 属性 | 值 |
|------|------|
| **状态** | 已完成 |
| **负责人** | @backend-dev + @frontend-dev |
| **预估工时** | 0.5天 |
| **前置依赖** | WB-T1, WB-T4 |
| **描述** | 世界书条目增删改：Python 端接口 + Android 端 UI |
| **完成日期** | 2026-06-14 |

**具体任务**:
- [x] `add_world_book_entry(name, entry_json)` — 添加单条条目
- [x] `update_world_book_entry(name, entry_id, entry_json)` — 更新条目
- [x] `delete_world_book_entry(name, entry_id)` — 删除条目
- [x] `_save_book_to_file(book)` — 条目变更后持久化到 JSON 文件
- [x] Android 条目列表对话框（showEntryListDialog）
- [x] Android 条目编辑对话框（showEntryEditDialog）— 含 ID/内容/关键词/概率/优先级
- [x] 条目删除确认对话框（confirmDeleteEntry）

### WB-T8 -- 交叉审核 [P1]

| 属性 | 值 |
|------|------|
| **状态** | 已完成 |
| **负责人** | @backend-dev + @frontend-dev |
| **预估工时** | 0.5天 |
| **前置依赖** | WB-T1, WB-T7 |
| **描述** | 世界书质量交叉审核：Schema + 语义 + 匹配能力三维度评分 |
| **完成日期** | 2026-06-14 |

**具体任务**:
- [x] `validate_world_book(name)` — 三维度审核（Schema/语义/匹配）+ 综合评分
- [x] 审核报告包含 issues 和 suggestions 两个维度
- [x] Android 审核按钮（编辑世界书对话框中的"交叉审核"按钮）
- [x] Android 审核报告展示（showAuditReportDialog）— 总分卡片 + 各维度详情

---

## 五、任务依赖图

```
已完成的 P4-P5:
  T4.1~T4.7 (记忆系统收尾) ── 全部完成
  P5.1~P5.3 (主动推送)     ── 全部完成

已完成的 P6 世界书:
  WB-T1 (Python桥接)  ── 全部完成
  WB-T2 (注入对话流程) ── 全部完成
  WB-T3 (数据打包)     ── 全部完成
  WB-T4 (Android UI)   ── 全部完成
  WB-T6 (持久化恢复)   ── 全部完成
  WB-T7 (条目填充CRUD) ── 全部完成
  WB-T8 (交叉审核)     ── 全部完成
  WB-T5 (端到端测试)   ── 待人工验证

后续 P6 其他:
  (世界书完成后按优先级: 消息搜索 > 自动化测试 > 国际化 > 插件系统)
```

---

## 六、变更记录

| 日期 | 版本 | 变更内容 |
|------|------|------|
| 2026-06-14 | V1.4 | 新增 WB-T7 条目填充 CRUD + WB-T8 交叉审核（三维度评分），世界书模块完整收官 |
| 2026-06-14 | V1.3 | 世界书模块 (WB-T1~T4) 全部完成，T5.2 通知图标收尾，更新依赖图和变更记录 |
| 2026-06-14 | V1.2 | 新增 P6 世界书模块任务 (WB-T1~T5)，详细计划见 TASK_WORLD_BOOK.md |
| 2026-06-10 | V1.0 | 初始版本，涵盖 P4收尾 + P5主动推送 + P6扩展 |

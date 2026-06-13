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
| **状态** | 待开始 |
| **负责人** | @backend-dev |
| **预估工时** | 1天 |
| **前置依赖** | T4.4 (LLM提取节流已完成) |
| **描述** | 实现记忆归档流水线 |

**具体任务**:
- [ ] 新增 `src/memory/archiver.py`
- [ ] VectorStore 新增 archived 标记字段
- [ ] 归档触发条件: memory_count > 500
- [ ] 摘要生成: LLM 将100条记忆压缩为3-5条摘要
- [ ] 检索权重: archived=0.2 倍衰减因子

---

## 二、P5 主动推送/通知

### T5.1 -- 定时主动消息: chat_bridge 接口 [P1]

| 属性 | 值 |
|------|------|
| **状态** | 待开始 |
| **负责人** | @backend-dev |
| **预估工时** | 0.5天 |
| **前置依赖** | T4.1 验证通过 |
| **描述** | 封装已有 proactive 引擎为 chat_bridge 可调用接口 |

**具体任务**:
- [ ] `generate_proactive_message()` → 调用 decision_engine + topic_generator → 返回消息文本
- [ ] 遵守 UserSettings 中的 proactive_enabled/wake_time/sleep_time
- [ ] 调用前检查是否在活跃时间段内

**验收标准**: 调用返回符合角色人设的主动消息文本

---

### T5.2 -- Android 系统通知 + 前台服务 [P1]

| 属性 | 值 |
|------|------|
| **状态** | 待开始 |
| **负责人** | @frontend-dev |
| **预估工时** | 1天 |
| **前置依赖** | T5.1 完成 |
| **描述** | Android 端通知和后台保活 |

**具体任务**:
- [ ] 新建 `NotificationHelper.kt` (NotificationChannel 创建)
- [ ] 新建 `ProactiveService.kt` (前台服务，调用Python生成消息)
- [ ] AndroidManifest.xml 注册 Service + 权限
- [ ] 通知点击 → 启动 MainActivity
- [ ] notification icon 资源 (ic_notification.xml)

**验收标准**: 见 REQUIREMENTS.md P5.2

---

### T5.3 -- WorkManager 定时器 [P2]

| 属性 | 值 |
|------|------|
| **状态** | 待开始 |
| **负责人** | @frontend-dev |
| **预估工时** | 0.5天 |
| **前置依赖** | T5.2 完成 |
| **描述** | 用WorkManager实现可靠定时触发 |

**具体任务**:
- [ ] 新建 `ProactiveWorker.kt` (CoroutineWorker)
- [ ] PeriodicWorkRequest (最小间隔15分钟)
- [ ] 约束: NetworkType.CONNECTED
- [ ] 在 MainActivity.initPython() 成功后 enqueue

**验收标准**: 定时触发 → Python proactive引擎 → 通知弹出

---

## 三、P6 多角色/扩展

### T6.1 -- 流式输出 (SSE) [P2]

| 属性 | 值 |
|------|------|
| **状态** | 待开始 |
| **负责人** | @backend-dev + @frontend-dev |
| **预估工时** | 1.5天 |
| **前置依赖** | T4.1 验证通过 |
| **描述** | 支持流式输出，逐token显示AI回复 |

**具体任务**:
- [ ] DeepSeekClient 新增 `chat_stream()` (stream=True, yield 每个delta)
- [ ] chat_bridge 新增 `chat_stream()` 接口
- [ ] Kotlin 端通过 Chaquopy 迭代器读取
- [ ] RecyclerView 逐token更新最后一条消息
- [ ] 发送中禁用输入，完成后恢复

---

### T6.2 -- 多角色卡切换 [P3]

| 属性 | 值 |
|------|------|
| **状态** | 待开始 |
| **负责人** | @backend-dev + @frontend-dev |
| **预估工时** | 1天 |
| **前置依赖** | P4-P5 稳定 |
| **描述** | 支持多个角色轮流对话 |

**具体任务**:
- [ ] chat_bridge 新增 `switch_card(card_name)` → 切换角色卡+切换记忆库
- [ ] 不同角色使用独立SQLite数据库（如 memories_{card_name}.db）
- [ ] Android 设置面板增加角色选择UI

---

### T6.3 -- 自定义角色卡 [P3]

| 属性 | 值 |
|------|------|
| **状态** | 待开始 |
| **负责人** | @frontend-dev |
| **预估工时** | 1天 |
| **前置依赖** | T6.2 完成 |
| **描述** | 用户自建角色卡 |

**具体任务**:
- [ ] 新建 `activity_card_editor.xml`
- [ ] 表单: 姓名/昵称/年龄/性别/性格/背景/说话风格/喜好/厌恶
- [ ] JSON模板填充 + 预览
- [ ] 保存到 data/role_cards/ 目录

---

### T6.4 -- 世界书 Android 集成 [P3]

| 属性 | 值 |
|------|------|
| **状态** | 待开始 |
| **负责人** | @frontend-dev |
| **预估工时** | 1天 |
| **前置依赖** | P4-P5 稳定 |
| **描述** | 已有Python实现，增加Android UI |

**具体任务**:
- [ ] chat_bridge 新增 `load_world_book(name)` / `list_world_books()`
- [ ] Android 设置面板增加世界背景选择

---

### T6.5 -- 对话历史导出 [P3]

| 属性 | 值 |
|------|------|
| **状态** | 待开始 |
| **负责人** | @frontend-dev |
| **预估工时** | 0.5天 |
| **前置依赖** | P4-P5 稳定 |
| **描述** | 导出对话为文件 |

**具体任务**:
- [ ] chat_bridge 新增 `export_history(format="json")`
- [ ] Android 端保存到 Download 目录
- [ ] 通过 ShareSheet 分享

---

### T6.6 -- 深色模式 [P4]

| 属性 | 值 |
|------|------|
| **状态** | 待开始 |
| **负责人** | @frontend-dev |
| **预估工时** | 0.5天 |
| **前置依赖** | 无 |
| **描述** | Material3 暗色主题适配 |

---

## 四、P6 世界书模块 (当前阶段)

> 详细任务计划见 [TASK_WORLD_BOOK.md](file:///f:/Trae AI/ai-APP/docs/TASK_WORLD_BOOK.md)

### WB-T1 -- chat_bridge 世界书桥接接口 [P0]

| 属性 | 值 |
|------|------|
| **状态** | 待开始 |
| **负责人** | @backend-dev |
| **预估工时** | 0.5天 |
| **前置依赖** | 无 |
| **描述** | 新建 _world_book.py，封装 WorldBookEngine 为 Chaquopy 可调用接口 |

### WB-T2 -- 世界书注入对话流程 [P0]

| 属性 | 值 |
|------|------|
| **状态** | 待开始 |
| **负责人** | @backend-dev |
| **预估工时** | 0.5天 |
| **前置依赖** | WB-T1 |
| **描述** | 在 _core.py 的 chat() 中自动注入世界书上下文 |

### WB-T3 -- 打包世界书数据文件 [P1]

| 属性 | 值 |
|------|------|
| **状态** | 待开始 |
| **负责人** | @mcp-devops |
| **预估工时** | 0.25天 |
| **前置依赖** | 无 |
| **描述** | 确保 data/world_books/ 中的 JSON 随 APK 打包 |

### WB-T4 -- 设置页面世界书选择 UI [P0]

| 属性 | 值 |
|------|------|
| **状态** | 待开始 |
| **负责人** | @frontend-dev |
| **预估工时** | 0.5天 |
| **前置依赖** | WB-T1, WB-T3 |
| **描述** | 在 SettingsActivity 增加世界书选择卡片 |

### WB-T5 -- 端到端测试 [P1]

| 属性 | 值 |
|------|------|
| **状态** | 待开始 |
| **负责人** | 人工 |
| **预估工时** | 0.25天 |
| **前置依赖** | WB-T1, WB-T2, WB-T3, WB-T4 |
| **描述** | 手机端验证完整世界书链路 |

---

## 五、任务依赖图

```
已完成的 P4-P5:
  T4.1~T4.6 (记忆系统收尾) ── 全部完成
  P5.1~P5.3 (主动推送)     ── 待后续启动

当前 P6 世界书:
  WB-T1 (Python桥接) ──► WB-T2 (注入对话流程)
      │                       │
      └───────┬───────────────┘
              ▼
           WB-T4 (Android UI) ──► WB-T5 (端到端测试)

  WB-T3 (数据打包) ── 独立并行

后续 P6 其他:
  (世界书完成后按优先级: 消息搜索 > 对话导出 > 自动化测试 > 国际化 > 插件系统)
```

---

## 六、变更记录

| 日期 | 版本 | 变更内容 |
|------|------|------|
| 2026-06-14 | V1.2 | 新增 P6 世界书模块任务 (WB-T1~T5)，详细计划见 TASK_WORLD_BOOK.md |
| 2026-06-10 | V1.0 | 初始版本，涵盖 P4收尾 + P5主动推送 + P6扩展 |

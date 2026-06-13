# 世界书模块 (World Book) -- 任务计划

> 文档版本：V1.0 | 创建时间：2026-06-14 | 负责人：@项目经理

---

## 零、为什么是世界书？

### 六项长期功能优先级评估

| 维度 | 世界书 | 消息搜索 | 对话导出 | 自动化测试 | 国际化 | 插件系统 |
|------|:---:|:---:|:---:|:---:|:---:|:---:|
| 用户价值 | **高** | 高 | 中 | 低 | 低 | 中 |
| 技术难度 | **低** | 中 | 低 | 中 | 低 | 高 |
| 现有耦合度 | **低** | 中 | 低 | 低 | 高 | 高 |
| **当前完成度** | **95%** | 0% | 0% | 0% | 10% | 5% |
| 综合优先级 | **1** | 2 | 3 | 4 | 5 | 6 |

**决策理由：**
- Python 端 `WorldBookEngine` (475行) + `WorldBookValidator` + 数据模型 **已 100% 实现**
- 仅需 chat_bridge 桥接 + Android UI，**预估总工时 1 天**
- 与现有对话流程**零侵入**：通过 role_player 的 `world_book_max_chars` 分配空间注入
- 用户价值**立即可见**：切换世界观后 AI 回复立即体现背景设定
- 已有示例数据 `cat_cafe_world.json`

---

## 一、功能概述

**目标：** 将 Python 端已完成的世界书引擎集成到 Android 应用，用户在设置中可切换不同世界观背景，AI 对话自动根据世界书注入上下文。

**核心流程：**
```
用户选择世界书
  → Kotlin 调用 chat_bridge.load_world_book(name)
  → Python WorldBookEngine 加载 JSON + 切换活跃世界书
  → 每次 chat() 调用前自动 match_and_inject(用户输入)
  → role_player 将世界书上下文注入 System Prompt
  → AI 回复体现世界观设定
```

---

## 二、可视化任务依赖图

```
                                    ┌─────────────────────┐
                                    │  T0: 审计准备材料    │
                                    │  (DeepSeek分析)      │
                                    └────────┬────────────┘
                                             │
                    ┌────────────────────────┼────────────────────────┐
                    ▼                        ▼                        ▼
           ┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
           │ T1: Python桥接  │    │ T2: 世界书注入   │    │ T3: 数据文件打包 │
           │ _world_book.py  │◄───│ 对话流程集成     │    │ (独立无依赖)     │
           │ + __init__.py   │    │ _core.py修改     │    │ +reality_world   │
           └────────┬────────┘    └────────┬────────┘    └────────┬────────┘
                    │                      │                      │
                    └──────────────────────┼──────────────────────┘
                                           │
                                           ▼
                                  ┌─────────────────┐
                                  │ T4: Android UI  │
                                  │ SettingsActivity │
                                  │ + activity_      │
                                  │ settings.xml     │
                                  │ + strings.xml    │
                                  └────────┬────────┘
                                           │
                                           ▼
                                  ┌─────────────────┐
                                  │ T6: 持久化恢复   │
                                  │ SharedPreferences│
                                  │ + 启动自动恢复   │
                                  └────────┬────────┘
                                           │
                                           ▼
                                  ┌─────────────────┐
                                  │ T5: 端到端测试  │
                                  │ (人工手机验证)   │
                                  └─────────────────┘

图例:
  ──►  强依赖 (必须前序完成)
  ◄──  数据流依赖 (T2 需要 T1 接口，但可并行开发框架)
  ...  独立无依赖 (可随时执行)
```

---

## 三、详细任务拆解

### 阶段1: Python 桥接层 (T1 + T2)

---

#### T1 -- chat_bridge 世界书桥接接口

| 属性 | 值 |
|------|------|
| **ID** | WB-T1 |
| **状态** | 待开始 |
| **负责人** | @backend-dev |
| **预估工时** | 0.5天 |
| **前置依赖** | 无 |
| **下游依赖** | WB-T2, WB-T4 |

**目标：** 新建 `chat_bridge/_world_book.py`，提供世界书列表/加载/查询接口，封装 `WorldBookEngine` 为 Chaquopy 可调用的简单函数。

**具体任务：**

1. 新建文件 `android/app/src/main/python/chat_bridge/_world_book.py`，包含：
   - `_wb_engine`: WorldBookEngine 惰性初始化单例（首次调用时从 `data/world_books/` 加载）
   - `list_world_books()` → 返回所有世界书名称和条目数列表
   - `load_world_book(name)` → 加载并激活指定世界书，返回条目详情
   - `get_active_world_book()` → 获取当前活跃世界书信息（名称、描述、条目数）
   - `deactivate_world_book()` → 取消当前活跃世界书（设为无世界书模式）
   - 所有函数返回标准 JSON: `{"status": "ok", ...}` 或 `{"status": "error", "message": "..."}`

2. 修改 `chat_bridge/__init__.py`，增加导出：
   - `from ._world_book import list_world_books, load_world_book, get_active_world_book, deactivate_world_book`
   - 更新 `__all__` 列表

**修改文件清单（2个）：**
| 操作 | 文件 |
|:---:|------|
| 新建 | `android/app/src/main/python/chat_bridge/_world_book.py` |
| 编辑 | `android/app/src/main/python/chat_bridge/__init__.py` |

**验收标准：**
- [ ] `list_world_books()` 返回 cat_cafe_world 等世界书列表
- [ ] `load_world_book("猫咖世界观")` 返回 `{"status": "ok", "entries": 4}`
- [ ] `get_active_world_book()` 返回当前活跃世界书信息
- [ ] `deactivate_world_book()` 后 `get_active_world_book()` 返回 `{"status": "ok", "active": null}`
- [ ] 不存在的世界书名返回 error 状态

**代码参考（WorldBookEngine API）：**
```
engine.list_books()          → List[Dict]  (已有)
engine.set_active(name)      → bool        (已有)
engine.get_active()          → WorldBook   (已有)
engine.match_and_inject(txt) → str         (已有)
```

---

#### T2 -- 世界书注入对话流程

| 属性 | 值 |
|------|------|
| **ID** | WB-T2 |
| **状态** | 待开始 |
| **负责人** | @backend-dev |
| **预估工时** | 0.5天 |
| **前置依赖** | WB-T1 |
| **下游依赖** | WB-T4, WB-T5 |

**目标：** 在 `_core.py` 的 `chat()` 函数中，每次用户发送消息时自动调用世界书匹配注入，将世界书上下文嵌入 System Prompt。

**具体任务：**

1. 修改 `_core.py` 的 `chat()` 函数：
   - 在记忆注入逻辑之后、`player.chat()` 调用之前，增加世界书注入
   - 调用 `_world_book._wb_engine.match_and_inject(user_input)` 获取世界书上下文
   - 如果返回非空字符串，调用 `player.inject_world_book_context(world_book_text)` 注入
   - 日志记录注入的世界书内容长度

2. 确认 `role_player.py` 中存在 `inject_world_book_context()` 方法（或等效机制）
   - 检查 [role_player.py](file:///f:/Trae AI/ai-APP/src/chat_engine/role_player.py) 中是否已有世界书注入方法
   - 如果没有，在 `RolePlayer` 中新增 `inject_world_book_context(text: str)` 方法
   - 将世界书文本存入 `_world_book_context` 实例变量
   - `_build_system_prompt()` 中在 world_book 段使用该内容

3. 在 `_core.py` 的 `reset()` 中增加世界书引擎状态重置：
   - 调用 `_world_book._wb_engine.reset_round()` 重置轮次计数

**修改文件清单（2个）：**
| 操作 | 文件 |
|:---:|------|
| 编辑 | `android/app/src/main/python/chat_bridge/_core.py` |
| 检查/编辑 | `src/chat_engine/role_player.py` |

**验收标准：**
- [ ] 加载世界书后，对话中提及关键词时 AI 回复体现世界观设定
- [ ] 日志中可见世界书注入记录
- [ ] 未加载世界书时不注入额外上下文
- [ ] reset() 后世界书轮次计数归零

---

### 阶段2: 数据准备 (T3)

---

#### T3 -- 打包世界书数据文件

| 属性 | 值 |
|------|------|
| **ID** | WB-T3 |
| **状态** | 待开始 |
| **负责人** | @mcp-devops |
| **预估工时** | 0.25天 |
| **前置依赖** | 无 |
| **下游依赖** | WB-T4, WB-T5 |

**目标：** 将 `data/world_books/` 目录中的 JSON 文件复制到 APK 可访问路径，确保 Python 端能读取。

**具体任务：**

1. 确认 `data/world_books/` 在 APK 打包时被包含：
   - 检查 `build.gradle.kts` 中 `python { }` 块的 `extractPackages` 或类似配置
   - 确保 `data/world_books/*.json` 随 Python 资源打包

2. 在 `_world_book.py` 中使用正确的路径：
   - 路径应指向 `python/data/world_books/`（与角色卡路径模式一致）
   - 参考 `_state.py` 中 `_BASE_DIR` 的计算方式

3. （可选）为 cat_cafe_world.json 增加一个更通用的默认世界书示例

**修改文件清单（1-2个）：**
| 操作 | 文件 |
|:---:|------|
| 检查 | `android/app/build.gradle.kts` |
| 可能编辑 | `android/app/src/main/python/chat_bridge/_world_book.py` |

**验收标准：**
- [ ] APK 构建后 `python/data/world_books/` 下存在 JSON 文件
- [ ] Python 端 `Path(world_books_dir).glob("*.json")` 能找到文件

---

### 阶段3: Android UI (T4)

---

#### T4 -- 设置页面世界书选择 UI

| 属性 | 值 |
|------|------|
| **ID** | WB-T4 |
| **状态** | 待开始 |
| **负责人** | @frontend-dev |
| **预估工时** | 0.5天 |
| **前置依赖** | WB-T1, WB-T3 |
| **下游依赖** | WB-T5 |

**目标：** 在设置页面新增"世界书"卡片区域，用户可以查看、选择、切换世界观背景。

**具体任务：**

1. 修改 `activity_settings.xml`：
   - 在"记忆管理卡片"和"关于卡片"之间插入"世界书卡片"
   - 包含以下行：
     - `itemWorldBook` -- 世界书选择（点击弹出单选框）
     - 可选：`itemWorldBookInfo` -- 当前世界书条目数展示
   - 复用现有 GlassCard + SettingsItemTitle 样式

2. 修改 `strings.xml`：
   - 新增字符串资源：
     - `section_world_book` = "世界书（世界观）"
     - `label_world_book_select` = "世界观选择"
     - `value_no_world_book` = "不使用"
     - `label_world_book_entries` = "包含条目"

3. 修改 `SettingsActivity.kt`：
   - 新增 `setupWorldBook()` 方法，参考 `setupRolePreset()` 的对话框模式
   - 点击后调用 Python `list_world_books()` 获取选项列表
   - 选项第一项为"不使用"
   - 选中后调用 Python `load_world_book(name)` 或 `deactivate_world_book()`
   - `refreshUI()` 中更新当前世界书名称显示
   - 在 `onCreate()` 末尾调用 `setupWorldBook()`

**修改文件清单（3个）：**
| 操作 | 文件 |
|:---:|------|
| 编辑 | `android/app/src/main/res/layout/activity_settings.xml` |
| 编辑 | `android/app/src/main/res/values/strings.xml` |
| 编辑 | `android/app/src/main/java/com/aicompanion/app/SettingsActivity.kt` |

**验收标准：**
- [ ] 设置页面新增"世界书"卡片区域
- [ ] 点击弹出世界书选择对话框，包含"不使用"+ 已有世界书列表
- [ ] 选择世界书后卡片上显示当前世界书名称和条目数
- [ ] 选择"不使用"后卡片显示"不使用"
- [ ] Python 未初始化时显示友好提示

---

### 阶段4: 端到端验证 (T5)

---

#### T5 -- 端到端测试

| 属性 | 值 |
|------|------|
| **ID** | WB-T5 |
| **状态** | 待开始 |
| **负责人** | 人工（项目经理 + 用户） |
| **预估工时** | 0.25天 |
| **前置依赖** | WB-T1, WB-T2, WB-T3, WB-T4 |
| **下游依赖** | 无 |

**目标：** 在手机上验证完整世界书链路。

**测试流程：**

1. **准备阶段：**
   - 确保 APK 已构建并安装到手机
   - API Key 已配置
   - 确认 `data/world_books/` 下的 JSON 文件被正确打包

2. **功能测试（正向）：**
   - 步骤1: 进入设置 → 世界书 → 选择"猫咖世界观"
   - 步骤2: 返回对话页 → 说"我昨天去了一家猫咖"
   - 步骤3: 观察 AI 回复是否包含猫咖世界观设定（如有灵性的猫、翻盖手机等）
   - 预期: AI 回复自然融入世界观背景

3. **功能测试（切换）：**
   - 步骤4: 回到设置 → 世界书 → 选择"不使用"
   - 步骤5: 再次聊猫咪话题
   - 预期: AI 回复不再有世界书内容注入

4. **功能测试（reset 后保留）：**
   - 步骤6: 选择世界书 → 开始新对话 → 聊相关话题
   - 预期: 世界书仍然生效

5. **异常测试：**
   - Python 未初始化时点击世界书设置，无崩溃，有提示

**验收标准：**
- [ ] 世界书选择后对话中 AI 回复体现世界观
- [ ] 切换世界书后旧设定不再出现
- [ ] 不使用世界书时对话正常
- [ ] 所有操作无崩溃

---

## 四、修改文件总览

| 文件 | 操作 | 任务 | 影响范围 |
|------|:---:|:---:|------|
| `android/.../chat_bridge/_world_book.py` | **新建** | T1 | Python 桥接层 |
| `android/.../chat_bridge/__init__.py` | 编辑 | T1 | 导出新接口 |
| `android/.../chat_bridge/_core.py` | 编辑 | T2 | 注入世界书到对话 |
| `src/chat_engine/role_player.py` | 检查/编辑 | T2 | 世界书上下文注入方法 |
| `android/app/build.gradle.kts` | 检查/编辑 | T3 | 确保数据打包 |
| `data/world_books/reality_world.json` | **新建** | T3 | 默认现实世界常识 |
| `android/.../res/layout/activity_settings.xml` | 编辑 | T4 | 新增世界书卡片 |
| `android/.../res/values/strings.xml` | 编辑 | T4 | 新增字符串资源 |
| `android/.../app/SettingsActivity.kt` | 编辑 | T4 | 世界书勾选逻辑 |

**新建文件: 2 个 | 编辑文件: 6 个 | 检查文件: 1 个**

---

## 五、人工审核节点

| 阶段 | 完成标志 | 审核人 | 审核要点 |
|:---:|------|:---:|------|
| T1 完成 | `_world_book.py` 编写完成 | 项目经理 | 接口覆盖、异常处理、JSON 规范 |
| T2 完成 | 世界书注入集成 | 项目经理 | 注入位置正确、不影响非世界书模式 |
| T3 完成 | 数据文件打包确认 | 项目经理 | APK 中文件存在 |
| T4 完成 | Android UI 实现 | 项目经理 + 用户 | UI 美观、交互流畅 |
| T5 完成 | 端到端验证通过 | 用户 | 世界观生效、切换正常 |

---

## 六、风险与缓解

| 风险 | 概率 | 影响 | 缓解措施 |
|------|:---:|:---:|------|
| role_player 缺少世界书注入方法 | 中 | 中 | T2 中预留新增方法的时间，已有 `inject_memories` 可参考 |
| APK 打包未包含 world_books 数据 | 低 | 高 | T3 中显式检查 build.gradle.kts 配置 |
| 世界书 prompt 过长导致超 token 限制 | 低 | 中 | TokenPreset 中已预留 `world_book_max_chars=15%` |
| Chaquopy 路径解析问题 | 低 | 中 | 参考 _state.py 已有的路径修复模式 |
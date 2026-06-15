# 插件管理独立页面 — 方案设计报告

> 由架构师 + 前端工程师交叉讨论生成 | 2026-06-15

---

## 一、背景与目标

将现有简陋的 `bottom_sheet_plugins.xml`（4 个占位图标）升级为**独立插件管理页面**。参考 Tavo 的 UI 与标签设置模式，但通过"往世乐土"品牌视觉语言做出显著差异化。

---

## 二、现有系统评估

### 2.1 已有资产

| 模块 | 状态 | 说明 |
|------|:--:|------|
| Python 插件引擎 | 已完成 | `BasePlugin`(4钩子) + `PluginManager`(发现/加载/卸载/启用) |
| Android 插件面板 | 占位 | `bottom_sheet_plugins.xml` 仅 4 个硬编码图标 |
| 设置页入口 | 无 | 设置页尚无插件管理跳转 |
| Kotlin 桥接 | 无 | 缺少 `_plugins.py` 和 `PluginViewModel` |

### 2.2 需要新建的关键文件（约 18 个）

| 文件 | 类型 |
|------|------|
| `PluginManageActivity.kt` | Activity |
| `PluginViewModel.kt` | ViewModel |
| `PluginAdapter.kt` | RecyclerView Adapter |
| `PluginItem.kt` | 数据模型 |
| `StarTrackItemDecoration.kt` | 自定义 View（轨道装饰器） |
| `StarIgnitionToggle.kt` | 自定义 View（星火开关） |
| `activity_plugin_manage.xml` | 主布局 |
| `item_plugin_star.xml` | 列表项布局 |
| `dialog_plugin_detail.xml` | 详情弹窗 |
| `chat_bridge/_plugins.py` | Python 桥接模块 |
| 5 个 drawable 资源 | 矢量图标 + 背景 |

---

## 三、三套 UI 方案对比

### 方案 A：「水晶回廊」Crystal Gallery

**核心概念**：每个插件是一枚悬浮的水晶，激活态发出紫色呼吸光。

```
布局：列表 + 顶部水平胶囊 Tag
卡片：发光水晶边框，16dp 圆角，2dp 紫色渐变描边，alpha 呼吸动画(0.4↔0.9)
开关：CrystalSwitch（轨道光条扫过效果）
展开：内联折叠面板（ValueAnimator 高度展开）
特色：水晶碎片旋转加载动画、碎裂空状态
```

**差异化分析**：继承了现有 GlassCard 基因但增强了发光效果，标签体系与 Tavo 类似但视觉语言完全不同。

---

### 方案 B：「星轨控制台」Star Track Console ★ 推荐

**核心概念**：插件是星轨上的星节点，激活星明亮燃烧，轨道连线代表命运羁绊。

```
布局：Tab 分类 + 星轨节点列表
卡片：StarCard（左侧 0dp/右侧 16dp 不对称圆角，无缝衔入星轨区域）
开关：StarIgnitionToggle（灰点→亮紫星+十字射线）
展开：内联展开 + 光线扫过效果
特色：ItemDecoration 绘制轨道虚线+圆点，星火点燃动画
```

**差异化分析**：完全不同的视觉隐喻 —— Tavo 是"分类筛选列表"，我们是"命运星图"。Tab 替代 Tag，空间维度（星轨区域）创造了新的信息层级。

---

### 方案 C：「精灵庭院」Fairy Garden

**核心概念**：插件是沉睡的精灵球，激活后精灵苏醒、球体发光。

```
布局：2列 Grid + 底部浮动花瓣 Tag
卡片：精灵球（60dp radialGradient 球体），激活态外发光 + 环绕光点旋转
开关：BloomToggle（灰球→紫色花瓣绽放动画）
展开：BottomSheet 弹窗 + 共享元素过渡
特色：ParticleView 背景粒子，花瓣形 Tag，精灵召唤加载动画
```

**差异化分析**：完全不同的交互模式——网格探索 vs 列表管理，精灵球概念温馨梦幻但开发成本最高。

---

### 三方案量化对比

| 维度 | 方案 A 水晶回廊 | 方案 B 星轨控制台 ★ | 方案 C 精灵庭院 |
|------|:---:|:---:|:---:|
| 信息密度 | 较高 | 适中 | 较低 |
| 操作效率 | 高 | 中高 | 中 |
| 品牌契合度 | 高 | **很高** | 中高 |
| 视觉记忆点 | 高 | **很高** | 很高 |
| 与 Tavo 差异 | 中高 | **高** | 高 |
| 实现难度 | 中 | 中高 | 高 |
| 可扩展性 | 中 | **高** | 中 |
| 开发工时 | 2天 | 2~2.5天 | 3.5~4天 |
| 代码量(估) | ~800行 | ~1000行 | ~1400行 |

---

## 四、推荐方案：方案 B「星轨控制台」

### 4.1 推荐理由

**1. 品牌叙事契合度最高**

"往世乐土"的核心 IP 意象是"记忆"、"命运"、"羁绊"。星轨的视觉隐喻 —— 每颗星代表一个可能性节点，轨道连线代表命运的联结 —— 完美契合。用户看到星轨的瞬间就能产生情感共鸣。

**2. 与 Tavo 的差异化最根本**

Tavo 是"分类筛选 + 列表展示"的经典信息架构。方案 B 通过三个维度重塑了范式：

- **空间维度**：左侧 48dp 星轨区域创造了全新的视觉层级
- **时间维度**：轨道连线的"路径感"暗示插件使用顺序和依赖关系
- **状态可视化**：星节点的明暗、大小、发光程度直接映射激活状态

这不是"优化 Tavo"，而是**超越 Tavo 的设计范式**。

**3. 信息密度与视觉表现的最佳平衡点**

方案 A 偏重效率但视觉冲击力一般；方案 C 视觉极致但信息密度低、开发成本高。方案 B 在两者之间取得了黄金平衡。

**4. 天然的可扩展性**

- 轨道连线可承载"插件依赖关系"（A 需要 B 先激活）
- Tab 可扩展"推荐"、"最近使用"等动态分类
- 星节点可升级为"星座"（插件组合一键激活）

---

### 4.2 数据流架构

```
Python (Chaquopy)
  PluginManager.get_all_plugins()
        │
        ▼
  chat_bridge/_plugins.py  ← 新增桥接模块
    ├─ list_plugins()      → JSON
    ├─ toggle_plugin()     → JSON
    ├─ get_plugin_detail() → JSON
    └─ uninstall_plugin()  → JSON
        │
  Chaquopy getModule("chat_bridge._plugins")
        │
        ▼
Kotlin
  PluginViewModel (LiveData)
        │
        ▼
  PluginManageActivity
    ├─ StarTrackItemDecoration  (轨道装饰)
    ├─ PluginAdapter            (星节点列表)
    ├─ StarIgnitionToggle       (星火开关)
    └─ PluginDetailBottomSheet  (详情弹窗)
```

### 4.3 数据模型

```kotlin
data class PluginItem(
    val name: String,           // 唯一标识
    val version: String,        // 版本号
    val description: String,    // 描述
    val category: String,       // chat / appearance / script
    val enabled: Boolean,       // 是否启用
    val callCount: Int,         // 调用次数
    val errorCount: Int,        // 异常次数
    val hooks: List<String>,    // 实现的钩子
    val author: String          // 作者
)
```

### 4.4 页面布局结构

```
┌──────────────────────────────┐
│  ← 返回    星轨控制台     ··· │  透明顶栏
├──────────────────────────────┤
│  [对话增强] [外观美化] [脚本] │  Tab 分类（下划线指示器）
├──────────────────────────────┤
│  ●──── 图片理解 ─── [✦ 开]  │  激活星节点（亮紫发光）
│  │    智能识图、以图生文      │  轨道连线（虚线）
│  │                          │
│  ○──── 语音合成 ─── [○ 关]  │  未激活星节点（暗灰）
│  │    待实现                 │
│  │                          │
│  ●──── 联网搜索 ─── [✦ 开]  │
│  │    实时联网信息检索       │
│                              │
└──────────────────────────────┘
```

### 4.5 关键交互

| 交互 | 效果 |
|------|------|
| 点击 Tab | 切换分类，星轨指示器弹性滑入 |
| 点击星火开关 | 灰点→亮紫星+射线爆开动画 (300ms) |
| 点击星节点卡片 | 展开详情（光线从左扫到右） |
| 长按星节点 | 弹出卸载/重置菜单 |
| 空状态 | 暗星图 + "该分类暂无插件" |

### 4.6 状态设计

| 状态 | 设计 |
|------|------|
| **加载中** | 一颗星星在轨道上从左到右往返移动（动画 1.5s，ease-in-out） |
| **空状态** | 空星图：深色背景上散落几颗暗淡小星点，文字"该分类下暂无插件"，左下角一道流星划过 |
| **错误状态** | 星轨断裂图标 + 错误信息 + 重试按钮 |
| **全部未激活** | 所有星节点为暗灰色，中间一颗大星提示"点击星节点激活插件" |

---

## 五、主题差异化策略

| 维度 | Tavo（推测） | 方案 B 星轨控制台 |
|------|-------------|-------------------|
| 色彩 | 浅色 + 彩色标签 | 深紫渐变 + 星轨光效 |
| 分类 | 标签 Chip 筛选 | Tab + 下划线指示器 |
| 卡片 | 标准 Material Card | 不对称圆角 + 玻璃态 + 星轨连接 |
| 开关 | SwitchCompat | StarIgnitionToggle（星火点燃） |
| 列表 | 普通列表 | 带轨道连线的星节点列表 |
| 动画 | Material 标准过渡 | 星轨弹性动画 + 光线扫过 |
| 情感 | 工具感 | 命运羁绊感 |

---

## 六、集成计划（14 步）

| 步骤 | 内容 | 层级 |
|:--:|------|:--:|
| 1 | `BasePlugin` 增加 `category`/`author`/`stats` 字段 | Python |
| 2 | 新建 `chat_bridge/_plugins.py` 桥接模块（4 个函数） | Python |
| 3 | 更新 `chat_bridge/__init__.py` 导出 | Python |
| 4 | 新建 `PluginItem.kt` 数据模型 | Kotlin |
| 5 | 新建 `StarTrackItemDecoration.kt` 轨道装饰器 | Kotlin |
| 6 | 新建 `StarIgnitionToggle.kt` 星火开关 | Kotlin |
| 7 | 新建 `PluginViewModel.kt` | Kotlin |
| 8 | 新建 `PluginAdapter.kt` | Kotlin |
| 9 | 新建 `PluginManageActivity.kt` | Kotlin |
| 10 | 新建 `activity_plugin_manage.xml` 主布局 | XML |
| 11 | 新建 `item_plugin_star.xml` 列表项 + `dialog_plugin_detail.xml` 详情弹窗 | XML |
| 12 | 新建 5 个 drawable 资源（图标+背景） | 资源 |
| 13 | 修改 `activity_settings.xml` 增加插件管理入口卡片 | XML |
| 14 | 修改 `AndroidManifest.xml` 注册新 Activity | 配置 |

---

## 七、文件清单

```
android/app/src/main/
├── java/com/aicompanion/app/
│   ├── PluginManageActivity.kt      ← 新增
│   ├── PluginViewModel.kt           ← 新增
│   ├── PluginAdapter.kt             ← 新增
│   ├── PluginItem.kt                ← 新增
│   ├── ui/
│   │   ├── StarTrackItemDecoration.kt ← 新增
│   │   └── StarIgnitionToggle.kt    ← 新增
│   ├── SettingsActivity.kt          ← 修改
│   └── MainActivity.kt              ← 修改
│
├── python/
│   ├── src/plugins/
│   │   ├── plugin_base.py           ← 修改
│   │   └── plugin_manager.py        ← 修改
│   └── chat_bridge/
│       ├── __init__.py              ← 修改
│       └── _plugins.py              ← 新增
│
└── res/
    ├── layout/
    │   ├── activity_plugin_manage.xml  ← 新增
    │   ├── item_plugin_star.xml        ← 新增
    │   ├── dialog_plugin_detail.xml    ← 新增
    │   ├── activity_settings.xml       ← 修改
    │   └── bottom_sheet_plugins.xml    ← 修改
    ├── drawable/
    │   ├── ic_plugin_star.xml          ← 新增
    │   ├── bg_star_card.xml            ← 新增
    │   ├── bg_star_track.xml           ← 新增
    │   ├── bg_star_node_active.xml     ← 新增
    │   └── bg_star_node_inactive.xml   ← 新增
    └── values/
        └── strings.xml                 ← 修改
```

---

## 八、下一步

方案 B「星轨控制台」是架构师和前端工程师交叉讨论后的**一致推荐方案**。

请确认：
1. 是否确认方案 B「星轨控制台」？还是偏好方案 A/C，或需要混合？
2. 确认后立即按集成计划的 14 个步骤实施开发。
# AI Companion — UI 系统审计报告

> **创建日期**: 2026-06-28  
> **审计范围**: 全局颜色定义、主题系统、布局文件、Kotlin 代码中的颜色使用  
> **结论**: **UI 系统存在严重混乱，三套配色方案互相冲突**

---

## 一、核心发现：三套配色方案冲突

经过对 `colors.xml`、`themes.xml`、`bg_*.xml`、各布局文件和 Kotlin 代码的全面审计，发现项目中**同时存在三套互相冲突的配色方案**：

```
┌─────────────────────────────────────────────────────────────────┐
│  方案 A: 往世乐土紫色系 (原始设计)                                │
│  主色: #9B59B6 (紫) / #8E44AD (深紫) / #BB6BD9 (浅紫)            │
│  状态: 已被覆盖，仅作为兼容别名保留                                │
├─────────────────────────────────────────────────────────────────┤
│  方案 B: 樱羽粉蓝系 (v1.0 引入)                                   │
│  主色: #B0C4DE (淡天蓝) / #FFDCE3 (淡樱粉) / #FDF0F0 (背景粉)     │
│  状态: 当前 colors.xml 定义的主题                                  │
├─────────────────────────────────────────────────────────────────┤
│  方案 C: 硬编码杂色 (散落各处)                                     │
│  颜色: #9B59B6, #FFB7C5, #FF6B6B, #FFD0D9, 0x30FFFFFF, ...      │
│  状态: 代码中 70+ 处硬编码，多数不匹配 A 或 B                      │
└─────────────────────────────────────────────────────────────────┘
```

---

## 二、问题清单

### 问题 1: 主题色已从"往世乐土紫"悄然变为"樱羽粉蓝"

**证据**：[colors.xml](file:///f:/Trae AI/ai-APP/android/app/src/main/res/values/colors.xml#L1-L6)

```xml
<!-- ===== 樱羽主题 v1.0 色板 — 亮色模式 ===== -->
<color name="sakura_pink">#FDF0F0</color>         <!-- 淡樱粉 -->
<color name="sakura_blue">#D4E8F0</color>         <!-- 淡粉蓝 -->
<color name="primary">#B0C4DE</color>             <!-- 主色：淡天蓝，不是紫色！ -->
```

**影响**：整个 APP 的实际主色是 `#B0C4DE`（淡天蓝），而非项目文档中描述的 `#9B59B6`（紫色）。`elysian_purple: #9B59B6` 仅作为"保留兼容旧引用"存在。

**你以为的**：紫色玻璃态 (#9B59B6)  
**实际上的**：粉蓝渐变 (#B0C4DE → #FFDCE3 → #FDF0F0)

---

### 问题 2: 24 处仍在使用旧紫色 `#9B59B6`

这些文件硬编码了紫色，与当前粉蓝主题不一致：

| 文件 | 用途 |
|------|------|
| `bg_launcher_gradient.xml` | 启动渐变 `#8E44AD→#9B59B6→#B07CC6` |
| `bg_btn_send.xml` | 发送按钮 `#FFB7C5→#9B59B6`（粉紫混搭） |
| `ic_launcher_foreground.xml` | 启动图标 `#9B59B6` |
| `ic_detail.xml`, `ic_creative.xml`, `ic_context.xml` | 设置图标 `#9B59B6` |
| `ic_example.xml`, `ic_frequency.xml`, `ic_key.xml` | 设置图标 `#9B59B6` |
| `ic_model.xml`, `ic_notification.xml`, `ic_plugin.xml` | 设置图标 `#9B59B6` |
| `ic_quiet.xml`, `ic_book.xml`, `ic_new_chat.xml` | 图标 `#9B59B6` |
| `vector_store.py` | 数据库默认颜色 `'#9B59B6'` |
| `orchestrator.py` | 记忆标签默认颜色 `"#9B59B6"` |
| `memory_types.py` | 记忆类型默认颜色 `"#9B59B6"` |

**结论**：大量图标和组件仍使用紫色，与当前粉蓝主题冲突。

---

### 问题 3: 46+ 处 Kotlin 代码硬编码颜色

| 文件 | 硬编码颜色 | 问题 |
|------|-----------|------|
| `ChatAdapter.kt:172` | `Color.parseColor("#FFD0D9")` | 搜索高亮粉 |
| `ChatAdapter.kt:178` | `Color.parseColor("#2D1B3A")` | 无关主题色 |
| `MainActivity.kt:636` | `Color.parseColor("#B0C4DE")` | 空状态图标 |
| `MainActivity.kt:781` | `Color.parseColor("#4CAF50")` | 硬编码绿 |
| `MemoryArchiveActivity.kt:425` | `Color.parseColor("#80E57373")` | 归档裂纹色 |
| `MemoryArchiveActivity.kt:432` | `Color.parseColor("#40FFFFFF")` | 归档光晕 |
| `MemoryArchiveActivity.kt:583-586` | 4 个硬编码渐变色 | 归档碎片 |
| `MemoryCardAdapter.kt:35-39` | `0xFFFDF0F0`, `0xFFD4E8F0`, `0xFFE8E8E8` | 记忆卡片色 |
| `VoiceController.kt:218,252` | `Color.parseColor("#FF6B6B")` | 录音红 |
| `RecordingOverlayView.kt:29` | `Color.argb(80, 0, 0, 0)` | 录音遮罩 |
| `RecordingOverlayView.kt:52,67` | `Color.parseColor("#FFB7C5")` | 混搭色 |
| `PetalView.kt:36-40` | 5 个硬编码粉色 | 花瓣动画 |
| `BlurUtils.kt:144` | `0x30FFFFFF` | 模糊遮罩 |
| `BlurUtils.kt:150` | `0xCCFFFFFF` | 模糊底色 |

**结论**：这些硬编码颜色**无法跟随主题切换**，深色模式下不会适配。

---

### 问题 4: 4 处 XML 布局硬编码颜色

| 文件 | 硬编码 |
|------|--------|
| `item_memory_card.xml:8` | `app:cardBackgroundColor="#E6FFFFFF"` |
| `item_memory_card.xml:11` | `app:strokeColor="#1AB0C4DE"` |
| `item_message_self.xml:35` | `android:background="#B0C4DE"` |
| `item_message_ai.xml:57` | `android:background="#B0C4DE"` |

---

### 问题 5: drawable 背景文件硬编码颜色

| 文件 | 硬编码 | 应使用 |
|------|--------|--------|
| `bg_sakura_header.xml` | `#FDF0F0→#FCE4E0→#F8D8D8` | `@color/sakura_gradient_*` |
| `bg_btn_primary.xml` | `#FFDCE3→#D4E8F0` | `@color/bubble_user_start` / `@color/sakura_blue` |
| `bg_btn_send.xml` | `#FFB7C5→#9B59B6` | 与主题不一致，粉色→紫色混搭 |
| `bg_launcher_gradient.xml` | `#8E44AD→#9B59B6→#B07CC6` | 纯紫色，与当前粉蓝主题完全冲突 |

---

### 问题 6: 深色模式颜色定义混乱

[values-night/colors.xml](file:///f:/Trae AI/ai-APP/android/app/src/main/res/values-night/colors.xml) 中的问题：

| 颜色名 | 亮色值 | 深色值 | 问题 |
|--------|--------|--------|------|
| `elysian_purple` | `#9B59B6` | `@color/primary` → `#B0C4DE` | 深色模式下变蓝色！ |
| `glass_edge_white` | `#FFFFFFFF` | `#2A1E35` | 命名误导，深色下是暗紫 |
| `glass_fill_white` | `#CCFFFFFF` | `#1A0E1F` | 命名误导，深色下是暗黑 |
| `sakura_pink` | `#FDF0F0` | `#FDF0F0` | 深色模式未变化！ |
| `sakura_blue` | `#D4E8F0` | `#D4E8F0` | 深色模式未变化！ |
| `sakura_sky` | `#B0C4DE` | `#B0C4DE` | 深色模式未变化！ |
| `primary` | `#B0C4DE` | `#B0C4DE` | 深色模式未变化！ |

**结论**：深色模式下大量颜色定义与亮色模式完全相同，意味着深色模式实际上没有正确适配。

---

### 问题 7: GlassCard 组件的模糊效果存在双重实现

[GlassCard.kt](file:///f:/Trae AI/ai-APP/android/app/src/main/java/com/aicompanion/app/ui/GlassCard.kt#L34-L38)：

```kotlin
init {
    background = ContextCompat.getDrawable(context, R.drawable.bg_glass_edge)
    cardElevation = 0f
    setCardBackgroundColor(Color.TRANSPARENT)
}
```

而 `bg_glass_edge.xml` 使用的是 `@color/glass_bg`（`#40B0C4DE`），又一个天蓝色玻璃态。如果 `blurEnabled=true` 时，会替换为 `BlurUtils` 生成的模糊背景，其中硬编码了 `0x30FFFFFF` 遮罩。

---

### 问题 8: 项目文档与实际 UI 严重脱节

| 文档描述 | 实际情况 |
|---------|---------|
| "往世乐土 (Elysian Realm) 深色半透明玻璃态" | 樱羽粉蓝主题，无玻璃态模糊（仅有半透明颜色模拟） |
| "紫色系 (#9B59B6)" | 主色为淡天蓝 `#B0C4DE` |
| "玻璃态卡片 + 紫色系主题色" | 粉蓝渐变卡片，无紫色 |

---

## 三、问题严重度评估

| 问题 | 严重度 | 影响范围 |
|------|:---:|------|
| 主题色悄然变更 | 🔴 严重 | 全局 |
| 70+ 处硬编码颜色 | 🔴 严重 | 深色模式失效、无法统一切换主题 |
| 深色模式适配失败 | 🔴 严重 | 暗色用户看到亮色 |
| 图标颜色不一致 | 🟡 中 | 视觉效果混乱 |
| drawable 硬编码 | 🟡 中 | 无法跟随主题 |
| 文档脱节 | 🟢 低 | 开发方向误导 |

---

## 四、根因分析

1. **没有统一的主题色常量**：开发者在不同文件中随意使用 `Color.parseColor()` 和 XML 硬编码
2. **主题切换时没有同步更新**：从"往世乐土紫"改为"樱羽粉蓝"后，只改了 `colors.xml` 的 `primary`，未清理旧代码
3. **深色模式未做实际适配**：`values-night/colors.xml` 中大量颜色与亮色模式相同
4. **没有 UI 回归检查**：缺乏对颜色一致性的自动化检查

---

## 五、修复建议

### 方案：统一到用户最初期望的"往世乐土紫色玻璃态"

由于用户明确表态"往世乐土玻璃态紫色系"是项目主题，建议：

1. **恢复紫色主色**：`primary` 改为 `#9B59B6`，而非 `#B0C4DE`
2. **清理所有硬编码**：替换为 `@color/` 引用
3. **统一深色模式**：`values-night/colors.xml` 中紫色系适配深色背景
4. **drawable 文件使用颜色资源引用**：而非硬编码
5. **Kotlin 代码使用 `ContextCompat.getColor()`**：而非 `Color.parseColor()`

### 如果需要讨论

由于当前"樱羽粉蓝"主题也有一定投入（GlassCard 样式、设置页面、气泡颜色等），是否需要**保留粉蓝主题作为可选方案**（如"浅色模式=樱羽粉蓝，深色模式=往世乐土紫"），还是**完全回归紫色系**，需要你决策。

---

## 六、修复优先级

| 优先级 | 任务 | 预估 |
|:---:|------|:---:|
| 🔴 P0 | 确定最终主题方向（紫 or 粉蓝） | 讨论 |
| 🔴 P0 | 统一 `colors.xml` 定义 | 1h |
| 🔴 P0 | 清理 XML 布局硬编码 | 1h |
| 🔴 P0 | 清理 Kotlin 硬编码 | 2h |
| 🟡 P1 | 清理 drawable 硬编码 | 1h |
| 🟡 P1 | 修复深色模式适配 | 2h |
| 🟢 P2 | 图标颜色统一 | 1h |
| 🟢 P2 | Python 端默认颜色更新 | 30min |

---

> **关键决策点**：请确认最终主题方向——是回归"往世乐土紫色系"，还是保留"樱羽粉蓝系"？这将决定后续所有 UI 工作的色板。
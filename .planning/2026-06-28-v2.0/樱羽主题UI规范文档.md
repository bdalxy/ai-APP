# 樱羽主题 UI 规范文档 v2.0

> **创建日期**: 2026-06-28  
> **版本**: v2.0（最终确定版）  
> **主题名称**: 樱羽粉蓝系（Sakura Feather Theme）  
> **适用范围**: AI Companion Android 原生应用全线 UI  

---

## 一、设计理念

### 1.1 主题定位

**樱羽（Sakura Feather）**：以樱花飘落于羽毛般轻柔的蓝色天空为意象，营造温暖、柔和、治愈的视觉氛围。整体调性偏向柔和粉蓝渐变，避免高饱和度和强对比，追求"轻盈如羽、温润如樱"的质感。

### 1.2 设计原则

| 原则 | 说明 |
|------|------|
| 柔和优先 | 所有颜色低饱和度，避免刺眼 |
| 渐变统一 | 粉→蓝为唯一渐变方向，禁止其他方向 |
| 玻璃态克制 | 仅用于卡片和模态层，不做全屏模糊 |
| 深浅一致 | 亮色/深色模式保持相同的情感温度 |

---

## 二、最终色板定义

### 2.1 核心四色（樱花四色）

| 色名 | 色值 | 占比 | 用途 |
|------|------|:---:|------|
| 淡樱粉 | `#FDF0F0` | 40% | 页面背景、大面积底色 |
| 淡粉蓝 | `#D4E8F0` | 25% | 卡片背景、渐变中段 |
| 淡天蓝 | `#B0C4DE` | 20% | 边框、点缀、主色强调 |
| 天使白 | `#FFFFFF` | 15% | 晕染提亮、文字底色 |

### 2.2 亮色模式（Light）完整色板

#### 背景色系

| 颜色资源名 | 色值 | 用途 |
|-----------|------|------|
| `bg_dark` | `#FDF0F0` | 页面主背景 |
| `bg_mid` | `#D4E8F0` | 卡片/面板背景 |
| `bg_light` | `#B0C4DE` | 分层/边框背景 |

#### 气泡色系

| 颜色资源名 | 色值 | 用途 |
|-----------|------|------|
| `bubble_ai` | `#F5F0FF` | AI 消息气泡背景 |
| `bubble_ai_glow` | `#FAF8FF` | AI 气泡光晕 |
| `bubble_ai_text` | `#4A3B3A` | AI 气泡文字 |
| `bubble_user_start` | `#FFDCE3` | 用户气泡渐变起始（粉） |
| `bubble_user_end` | `#D4E8F0` | 用户气泡渐变结束（蓝） |
| `bubble_user_text` | `#4A3B3A` | 用户气泡文字 |
| `bubble_typing` | `#F0E8F5` | 打字指示器气泡 |

#### 文字层级

| 颜色资源名 | 色值 | 用途 |
|-----------|------|------|
| `text_primary` | `#4A3B3A` | 主文字（标题、正文） |
| `text_secondary` | `#8A7B7A` | 次要文字（副标题、描述） |
| `text_tertiary` | `#9A8E8D` | 辅助文字（提示、占位符） |
| `text_on_pink` | `#4A3B3A` | 粉色背景上的文字 |

#### 玻璃态效果

| 颜色资源名 | 色值 | 用途 |
|-----------|------|------|
| `glass_bg` | `#40B0C4DE` | 毛玻璃卡片背景（25% 淡天蓝） |
| `glass_border` | `#60B0C4DE` | 毛玻璃卡片边框（37.5% 淡天蓝） |
| `glass_bg_dark` | `#14FFFFFF` | 暗色毛玻璃叠加层 |
| `glass_edge_white` | `#FFFFFFFF` | 玻璃糖纸边缘高光（白） |
| `glass_edge_blue` | `#B0C4DE` | 玻璃糖纸边缘高光（蓝） |
| `glass_fill_white` | `#CCFFFFFF` | 玻璃填充底色（80% 白） |
| `glass_edge_glow` | `#33B0C4DE` | 玻璃边缘光晕 |

#### 辅助色

| 颜色资源名 | 色值 | 用途 |
|-----------|------|------|
| `accent_green` | `#4CAF50` | 成功/在线状态 |
| `accent_orange` | `#FF9800` | 警告/中等状态 |
| `accent_red` | `#E57373` | 错误/删除/录音 |
| `status_online` | `#7DE2A0` | 在线状态指示 |

#### 光晕/装饰

| 颜色资源名 | 色值 | 用途 |
|-----------|------|------|
| `glow_pink` | `#40FFDCE3` | 粉色光晕（25% 淡樱粉） |
| `glow_blue` | `#40D4E8F0` | 蓝色光晕（25% 淡粉蓝） |

#### 输入/交互

| 颜色资源名 | 色值 | 用途 |
|-----------|------|------|
| `input_field_bg` | `#B3FFFFFF` | 输入框背景（70% 白） |
| `input_field_border` | `#60B0C4DE` | 输入框边框 |
| `send_inactive_bg` | `#18B0C4DE` | 发送按钮未激活 |
| `switch_thumb_on` | `#FFFFFF` | 开关滑块（开） |
| `switch_track_on` | `#B0C4DE` | 开关轨道（开） |
| `divider` | `#1DFFFFFF` | 分割线 |

#### 记忆类型标签

| 颜色资源名 | 色值 | 用途 |
|-----------|------|------|
| `memory_episodic` | `#8C82D2` | 情景记忆标签 |
| `memory_semantic` | `#66BB6A` | 语义记忆标签 |
| `memory_user_fact` | `#F0A040` | 用户事实标签 |
| `memory_emotional` | `#E57399` | 情感记忆标签 |
| `memory_summary` | `#64B5F6` | 摘要记忆标签 |
| `memory_default` | `#9090A0` | 默认记忆标签 |
| `memory_archive_gray` | `#E8E8E8` | 记忆档案馆褪色灰 |

#### Material Design 映射

| 颜色资源名 | 色值 | 映射到 |
|-----------|------|--------|
| `primary` | `#B0C4DE` | 淡天蓝（主色） |
| `primaryVariant` | `#8AA8C0` | 深天蓝 |
| `secondary` | `#FFDCE3` | 淡樱粉（次色） |
| `surface` | `@color/bg_mid` | 卡片背景 |
| `background` | `@color/bg_dark` | 页面背景 |
| `onPrimary` | `#4A3B3A` | 主色上文字 |
| `onSecondary` | `#4A3B3A` | 次色上文字 |
| `onSurface` | `@color/text_primary` | 表面上文字 |
| `surfaceVariant` | `#FDF0F0` | 表面变体 |
| `onSurfaceVariant` | `@color/text_secondary` | 表面变体上文字 |

#### 兼容旧引用（保留但不再使用）

| 颜色资源名 | 色值 | 说明 |
|-----------|------|------|
| `elysian_purple` | `#9B59B6` | ⚠️ 已废弃，仅兼容旧代码 |
| `elysian_pink` | `#FFDCE3` | ⚠️ 已废弃，指向 `secondary` |
| `elysian_pink_light` | `@color/sakura_pink` | ⚠️ 别名，指向 `sakura_pink` |

### 2.3 深色模式（Dark / Night）完整色板

#### 背景色系

| 颜色资源名 | 色值 | 用途 |
|-----------|------|------|
| `bg_dark` | `#1A0E1F` | 页面主背景（暖黑底） |
| `bg_mid` | `#241528` | 卡片/面板背景（暗紫） |
| `bg_light` | `#2E1D35` | 分层背景 |

#### 气泡色系

| 颜色资源名 | 色值 | 用途 |
|-----------|------|------|
| `bubble_ai` | `#2A1E35` | AI 消息气泡（暗紫） |
| `bubble_ai_glow` | `#2E2540` | AI 气泡光晕 |
| `bubble_ai_text` | `#E8DCD8` | AI 气泡文字（暖白） |
| `bubble_user_start` | `#D4859B` | 用户气泡渐变起始（暗粉） |
| `bubble_user_end` | `#7B6B8A` | 用户气泡渐变结束（暗蓝紫） |
| `bubble_user_text` | `#E8DCD8` | 用户气泡文字 |
| `bubble_typing` | `#2A1E35` | 打字指示器气泡 |

#### 文字层级

| 颜色资源名 | 色值 | 用途 |
|-----------|------|------|
| `text_primary` | `#E8DCD8` | 主文字（暖白） |
| `text_secondary` | `#B0A0C0` | 次要文字（淡紫灰） |
| `text_tertiary` | `#7A6A8A` | 辅助文字（暗紫灰） |
| `text_on_pink` | `#2D1B3A` | 粉色背景上文字（深紫） |

#### 玻璃态效果

| 颜色资源名 | 色值 | 用途 |
|-----------|------|------|
| `glass_bg` | `#40B0C4DE` | 毛玻璃卡片背景 |
| `glass_border` | `#30B0C4DE` | 毛玻璃卡片边框 |
| `glass_bg_dark` | `#14000000` | 暗色毛玻璃叠加层 |
| `glass_edge_white` | `#2A1E35` | 边缘高光（暗色模式） |
| `glass_edge_blue` | `#7B6B8A` | 边缘高光蓝 |
| `glass_fill_white` | `#1A0E1F` | 填充底色（暗色模式） |
| `glass_edge_glow` | `#33B0C4DE` | 边缘光晕 |

#### 辅助色

| 颜色资源名 | 色值 | 用途 |
|-----------|------|------|
| `accent_green` | `#66BB6A` | 成功/在线状态 |
| `accent_orange` | `#FFA726` | 警告/中等状态 |
| `accent_red` | `#E57373` | 错误/删除/录音 |
| `status_online` | `#7DE2A0` | 在线状态指示 |

#### 光晕/装饰

| 颜色资源名 | 色值 | 用途 |
|-----------|------|------|
| `glow_pink` | `#30D4859B` | 粉色光晕 |
| `glow_blue` | `#307B6B8A` | 蓝色光晕 |

#### 输入/交互

| 颜色资源名 | 色值 | 用途 |
|-----------|------|------|
| `input_field_bg` | `#14FFFFFF` | 输入框背景 |
| `input_field_border` | `#2DFFFFFF` | 输入框边框 |
| `send_inactive_bg` | `#251838` | 发送按钮未激活 |
| `switch_thumb_on` | `#FFFFFF` | 开关滑块（开） |
| `switch_track_on` | `#B0C4DE` | 开关轨道（开） |
| `divider` | `#1DFFFFFF` | 分割线 |

#### 记忆类型标签

| 颜色资源名 | 色值 | 用途 |
|-----------|------|------|
| `memory_episodic` | `#A29BFE` | 情景记忆标签 |
| `memory_semantic` | `#66BB6A` | 语义记忆标签 |
| `memory_user_fact` | `#FFA726` | 用户事实标签 |
| `memory_emotional` | `#FFB74D` | 情感记忆标签 |
| `memory_summary` | `#81C784` | 摘要记忆标签 |
| `memory_default` | `#757575` | 默认记忆标签 |
| `memory_archive_gray` | `#2A2A2A` | 记忆档案馆褪色 |

#### Material Design 映射

| 颜色资源名 | 色值 | 映射到 |
|-----------|------|--------|
| `primary` | `#B0C4DE` | 淡天蓝（主色） |
| `primaryVariant` | `#8AA8C0` | 深天蓝 |
| `secondary` | `#D4859B` | 暗粉（次色） |
| `surface` | `@color/bg_mid` | 卡片背景 |
| `background` | `@color/bg_dark` | 页面背景 |
| `onPrimary` | `#4A3B3A` | 主色上文字 |
| `onSecondary` | `#E8DCD8` | 次色上文字 |
| `onSurface` | `@color/text_primary` | 表面上文字 |
| `surfaceVariant` | `@color/bg_light` | 表面变体 |
| `onSurfaceVariant` | `@color/text_secondary` | 表面变体上文字 |

---

## 三、组件样式规范

### 3.1 卡片（Card）

#### GlassCard（毛玻璃卡片）— 默认样式

```
样式名: GlassCard
父级: Widget.MaterialComponents.CardView
背景: @color/glass_bg         （#40B0C4DE，半透明淡天蓝）
边框: @color/glass_border     （#60B0C4DE，1dp）
圆角: 12dp
阴影: 0dp（禁用，由玻璃效果替代）
```

**使用规则**：
- 所有列表项卡片、设置卡片、对话框容器使用此样式
- 禁止在 GlassCard 上叠加 `cardElevation` 阴影
- 若启用 `blurEnabled` 则背景替换为 `BlurUtils` 生成的模糊 bitmap

#### 记忆卡片（Memory Card）

```
基础色: @color/sakura_pink   （#FDF0F0）
备用色: @color/sakura_blue   （#D4E8F0）
褪色:  @color/memory_archive_gray （#E8E8E8，归档状态）
```

**使用规则**：
- 活跃记忆卡片使用粉/蓝交替底色
- 归档记忆卡片使用褪色灰 + 透明度降低
- 卡片间通过 `memory_episodic` / `memory_semantic` 等标签色区分类型

#### 世界书卡片（World Book Card）

```
背景渐变: @color/wb_sakura_start → @color/wb_sakura_mid → @color/wb_sakura_end
卡片底色: @color/wb_card_bg       （#CCFFFFFF）
边框:     @color/wb_card_border   （#40B0C4DE）
标签背景: @color/wb_tag_bg        （#30B0C4DE）
标签文字: @color/wb_tag_text      （#3A6B8C）
```

### 3.2 按钮（Button）

#### 主按钮（Primary）— 粉蓝渐变填充

```
样式名: AppButton.Primary
文字色: @color/onPrimary        （#4A3B3A）
背景:   @drawable/bg_btn_primary（渐变 #FFDCE3 → #D4E8F0）
圆角:   24dp
最小高度: @dimen/button_default
```

#### 次要按钮（Secondary）— 描边透明

```
样式名: AppButton.Secondary
文字色: @color/primary          （#B0C4DE）
描边色: @color/primary          （#B0C4DE）
圆角:   24dp
背景:   透明
```

#### 文字按钮（Text）

```
样式名: AppButton.Text
文字色: @color/primary          （#B0C4DE）
字号:   @dimen/text_body_small
```

### 3.3 输入框（Input）

```
样式名: AppInput
背景:   @drawable/bg_input_field（70% 白色 + 淡天蓝边框）
文字色: @color/text_primary
提示色: @color/text_tertiary
字号:   @dimen/text_body
内边距: 12dp 水平
```

**变体**：
- `AppInput.Search`：单行，40dp 高度
- `AppInput.Multiline`：多行，最多 4 行

### 3.4 消息气泡（Chat Bubble）

#### AI 气泡

```
背景:   @color/bubble_ai       （#F5F0FF，微暖淡紫白）
光晕:   @color/bubble_ai_glow  （#FAF8FF）
文字:   @color/bubble_ai_text  （#4A3B3A）
对齐:   左对齐
```

#### 用户气泡

```
背景渐变: @color/bubble_user_start → @color/bubble_user_end
          （#FFDCE3 淡樱粉 → #D4E8F0 淡粉蓝）
文字:     @color/bubble_user_text  （#4A3B3A）
对齐:     右对齐
```

#### 打字气泡

```
背景: @color/bubble_typing     （#F0E8F5）
动画: 三点弹跳（使用主题色）
```

### 3.5 文字层级（Typography）

| 层级 | 样式名 | 字号 | 字重 | 颜色 |
|------|--------|------|------|------|
| 页面标题 | `AppText.PageTitle` | `@dimen/text_heading` | Bold | `text_primary` |
| 卡片标题 | `AppText.CardTitle` | `@dimen/text_body` | Bold | `text_primary` |
| 卡片副标题 | `AppText.CardSubtitle` | 13sp | Normal | `text_secondary` |
| 正文 | `AppText` | `@dimen/text_body` | Normal | `text_primary` |
| 说明文字 | `AppText.Caption` | `@dimen/text_caption` | Normal | `text_tertiary` |
| 设置项标题 | `SettingsItemTitle` | 15sp | Normal | `text_primary` |
| 设置项副标题 | `SettingsItemSubtitle` | 13sp | Normal | `text_secondary` |

### 3.6 标签（Chip）

```
样式名: AppChip
字号:   11sp
文字色: @color/onPrimary
背景:   @drawable/bg_type_label
内边距: 8dp 水平 / 2dp 垂直
```

### 3.7 开关（Switch）

```
样式名: AppSwitch
滑块(开): @drawable/bg_switch_thumb（白色）
轨道(开): @drawable/bg_switch_track（淡天蓝 #B0C4DE）
```

### 3.8 分割线

```
颜色: @color/divider           （#1DFFFFFF，极淡白）
高度: 1dp
```

---

## 四、硬编码颜色清理清单

> 以下所有硬编码必须在后续开发中替换为对应的 `@color/` 资源引用或 `R.color.*`。

### 4.1 XML drawable 文件（最高优先级）

| 文件路径 | 当前硬编码 | 应替换为 |
|---------|-----------|---------|
| `res/drawable/bg_launcher_gradient.xml` | `#8E44AD→#9B59B6→#B07CC6` | `@color/sakura_blue→@color/sakura_sky→@color/primary` |
| `res/drawable/bg_btn_send.xml` | `#FFB7C5→#9B59B6` | `@color/sakura_pink→@color/primary`（纯粉蓝渐变） |
| `res/drawable/bg_sakura_header.xml` | `#FDF0F0→#FCE4E0→#F8D8D8` | `@color/wb_sakura_start→@color/wb_sakura_mid→@color/wb_sakura_end` |
| `res/drawable/bg_btn_primary.xml` | `#FFDCE3→#D4E8F0` | `@color/bubble_user_start→@color/sakura_blue` |

### 4.2 XML 矢量图标（紫色残留）

| 文件路径 | 当前硬编码 | 应替换为 |
|---------|-----------|---------|
| `res/drawable/ic_launcher_foreground.xml` | `#9B59B6` | `@color/primary` |
| `res/drawable/ic_detail.xml` | `#9B59B6` | `@color/primary` |
| `res/drawable/ic_creative.xml` | `#9B59B6` | `@color/primary` |
| `res/drawable/ic_context.xml` | `#9B59B6` | `@color/primary` |
| `res/drawable/ic_example.xml` | `#9B59B6` | `@color/primary` |
| `res/drawable/ic_frequency.xml` | `#9B59B6` | `@color/primary` |
| `res/drawable/ic_key.xml` | `#9B59B6` | `@color/primary` |
| `res/drawable/ic_model.xml` | `#9B59B6` | `@color/primary` |
| `res/drawable/ic_notification.xml` | `#9B59B6` | `@color/primary` |
| `res/drawable/ic_plugin.xml` | `#9B59B6` | `@color/primary` |
| `res/drawable/ic_quiet.xml` | `#9B59B6` | `@color/primary` |
| `res/drawable/ic_book.xml` | `#9B59B6` | `@color/primary` |
| `res/drawable/ic_new_chat.xml` | `#9B59B6` | `@color/primary` |

### 4.3 XML 布局文件

| 文件路径 | 行号 | 当前硬编码 | 应替换为 |
|---------|:---:|-----------|---------|
| `res/layout/item_memory_card.xml` | 8 | `app:cardBackgroundColor="#E6FFFFFF"` | `app:cardBackgroundColor="@color/wb_card_bg"` |
| `res/layout/item_memory_card.xml` | 11 | `app:strokeColor="#1AB0C4DE"` | `app:strokeColor="@color/wb_card_border"` |
| `res/layout/item_message_self.xml` | 35 | `android:background="#B0C4DE"` | `android:background="@drawable/bg_bubble_user"`（使用 drawable 渐变） |
| `res/layout/item_message_ai.xml` | 57 | `android:background="#B0C4DE"` | `android:background="@color/bubble_ai"` |

### 4.4 Kotlin 代码（按文件）

#### ChatAdapter.kt

| 行号 | 当前硬编码 | 应替换为 |
|:---:|-----------|---------|
| 172 | `Color.parseColor("#FFD0D9")` | `ContextCompat.getColor(context, R.color.glow_pink)` |
| 178 | `Color.parseColor("#2D1B3A")` | `ContextCompat.getColor(context, R.color.text_on_pink)` |

#### MainActivity.kt

| 行号 | 当前硬编码 | 应替换为 |
|:---:|-----------|---------|
| 636 | `Color.parseColor("#B0C4DE")` | `ContextCompat.getColor(this, R.color.primary)` |
| 781 | `Color.parseColor("#4CAF50")` | `ContextCompat.getColor(this, R.color.accent_green)` |

#### MemoryArchiveActivity.kt

| 行号 | 当前硬编码 | 应替换为 |
|:---:|-----------|---------|
| 425 | `Color.parseColor("#80E57373")` | `ContextCompat.getColor(this, R.color.accent_red)` + alpha |
| 432 | `Color.parseColor("#40FFFFFF")` | `ContextCompat.getColor(this, R.color.glass_bg_dark)` |
| 583-586 | 4 个渐变色硬编码 | 使用 `@color/wb_*` 系列 |

#### MemoryCardAdapter.kt

| 行号 | 当前硬编码 | 应替换为 |
|:---:|-----------|---------|
| 35 | `0xFFFDF0F0` | `ContextCompat.getColor(context, R.color.sakura_pink)` |
| 36 | `0xFFD4E8F0` | `ContextCompat.getColor(context, R.color.sakura_blue)` |
| 37-39 | `0xFFE8E8E8` | `ContextCompat.getColor(context, R.color.memory_archive_gray)` |

#### VoiceController.kt

| 行号 | 当前硬编码 | 应替换为 |
|:---:|-----------|---------|
| 218 | `Color.parseColor("#FF6B6B")` | `ContextCompat.getColor(context, R.color.accent_red)` |
| 252 | `Color.parseColor("#FF6B6B")` | `ContextCompat.getColor(context, R.color.accent_red)` |

#### RecordingOverlayView.kt

| 行号 | 当前硬编码 | 应替换为 |
|:---:|-----------|---------|
| 29 | `Color.argb(80, 0, 0, 0)` | `ContextCompat.getColor(context, R.color.glass_bg_dark)` |
| 52 | `Color.parseColor("#FFB7C5")` | `ContextCompat.getColor(context, R.color.bubble_user_start)` |
| 67 | `Color.parseColor("#FFB7C5")` | `ContextCompat.getColor(context, R.color.bubble_user_start)` |

#### PetalView.kt

| 行号 | 当前硬编码 | 应替换为 |
|:---:|-----------|---------|
| 36-40 | 5 个硬编码粉色 | `ContextCompat.getColor(context, R.color.sakura_pink)` / `R.color.elysian_pink` |

#### BlurUtils.kt

| 行号 | 当前硬编码 | 应替换为 |
|:---:|-----------|---------|
| 144 | `0x30FFFFFF` | `ContextCompat.getColor(context, R.color.glass_bg_dark)` |
| 150 | `0xCCFFFFFF` | `ContextCompat.getColor(context, R.color.glass_fill_white)` |

### 4.5 Python 端（Chaquopy）

| 文件路径 | 行号 | 当前硬编码 | 应替换为 |
|---------|:---:|-----------|---------|
| `src/main/python/vector_store.py` | — | `'#9B59B6'` | `'#B0C4DE'`（淡天蓝） |
| `src/main/python/orchestrator.py` | — | `"#9B59B6"` | `"#B0C4DE"`（淡天蓝） |
| `src/main/python/memory_types.py` | — | `"#9B59B6"` | `"#9090A0"`（`memory_default`） |

---

## 五、深色模式适配方案

### 5.1 适配策略

深色模式命名为 **"夜樱"（Night Sakura）**，保持与亮色模式一致的情感温度：

| 维度 | 亮色模式 | 深色模式 |
|------|---------|---------|
| 背景温度 | 暖粉白 | 暖黑紫 |
| 主色 | `#B0C4DE` 淡天蓝（不变） | `#B0C4DE` 淡天蓝（不变） |
| 卡片 | 淡粉蓝 `#D4E8F0` | 暗紫 `#241528` |
| 文字 | 暖棕 `#4A3B3A` | 暖白 `#E8DCD8` |
| 气泡渐变 | 粉→蓝 | 暗粉→暗蓝紫 |
| 玻璃态 | 半透天蓝 | 半透暗紫 |

### 5.2 实现方式

1. **资源限定符**：使用 `values-night/` 目录覆盖颜色值
2. **主题自动切换**：`Theme.AICompanion` 继承 `Theme.MaterialComponents.NoActionBar`，自动跟随系统深色模式
3. **手动切换**：通过 `AppCompatDelegate.setDefaultNightMode()` 支持用户手动选择

### 5.3 深色模式颜色映射原则

```
亮色淡樱粉 #FDF0F0  →  深色暖黑底 #1A0E1F
亮色淡粉蓝 #D4E8F0  →  深色暗紫 #241528
亮色淡天蓝 #B0C4DE  →  深色不变 #B0C4DE（主色保持一致）
亮色天使白 #FFFFFF  →  深色不变 #FFFFFF（高亮保留）
亮色文字棕 #4A3B3A  →  深色文字暖白 #E8DCD8
```

### 5.4 已知问题

当前 `values-night/colors.xml` 中以下颜色与亮色模式相同，**需确认是否为设计意图**：

| 颜色资源名 | 当前深色值 | 建议 |
|-----------|-----------|------|
| `sakura_pink` | `#FDF0F0`（与亮色相同） | 保留，作为引用别名 |
| `sakura_blue` | `#D4E8F0`（与亮色相同） | 保留，作为引用别名 |
| `sakura_sky` | `#B0C4DE`（与亮色相同） | 保留，作为引用别名 |
| `primary` | `#B0C4DE`（与亮色相同） | ✅ 正确，主色在深浅模式中保持一致 |
| `sakura_white` | `#FFFFFF`（与亮色相同） | ✅ 正确，白色保留 |

---

## 六、主题切换规则

### 6.1 切换方式

| 方式 | 说明 |
|------|------|
| 跟随系统 | 默认行为，`AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM` |
| 手动亮色 | 用户设置中强制亮色模式 |
| 手动深色 | 用户设置中强制深色模式 |
| 自动定时 | 可选：根据日出日落自动切换（后续功能） |

### 6.2 切换时注意事项

1. **Activity 重建**：切换深色模式会触发 Activity 重建，需确保 `onSaveInstanceState` 正确保存状态
2. **WebView/自定义 View**：`PetalView`、`RecordingOverlayView` 等自定义 View 需在 `onConfigurationChanged` 中重新应用颜色
3. **Python 端颜色**：Python 端颜色为静态字符串，不跟随主题切换；如需动态颜色，应由 Kotlin 端传入
4. **图片资源**：矢量图标使用 `@color/primary` 引用，自动跟随主题；位图图标需准备深色版本

### 6.3 用户设置存储

```
Key: "theme_mode"
值:  "system" | "light" | "dark"
默认: "system"
存储: SharedPreferences
```

---

## 七、开发规范

### 7.1 颜色使用铁律

> ⚠️ **绝对禁止**在任何 XML 布局、Kotlin 代码、drawable 文件中使用硬编码颜色值（如 `#FFDCE3`、`Color.parseColor("#...")`、`0xFFxxxxxx`）。

| 场景 | 正确做法 |
|------|---------|
| XML 布局 | `android:background="@color/bg_dark"` |
| XML drawable | `<solid android:color="@color/primary" />` |
| Kotlin 代码 | `ContextCompat.getColor(context, R.color.primary)` |
| Kotlin Compose | `MaterialTheme.colorScheme.primary` 或 `colorResource(R.color.primary)` |
| Python 端 | 从 Kotlin 端传入颜色值，或使用 `R.color.*` 映射 |

### 7.2 新增颜色资源流程

1. 在 `values/colors.xml` 中添加颜色定义（亮色模式）
2. 在 `values-night/colors.xml` 中添加对应的深色模式值
3. 命名遵循 `{类别}_{用途}` 格式（如 `bubble_user_start`、`memory_episodic`）
4. 禁止仅在一端添加而在另一端遗漏

### 7.3 渐变规范

- **唯一渐变方向**：粉色 → 蓝色（`#FFDCE3` → `#D4E8F0` 或变体）
- **禁止紫色渐变**：`#9B59B6` 及其变体不得出现在任何渐变中
- **渐变角度**：默认 135°（左上到右下），特殊场景可调整但需在代码注释中说明

---

## 八、版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| v1.0 | 2026-06-27 | 初版樱羽主题引入，替换往世乐土紫色系 |
| v2.0 | 2026-06-28 | 最终确定色板，整合审计报告，制定完整规范 |

---

> **本规范为项目 UI 开发的唯一权威参考。所有智能体在开发 UI 相关代码时必须遵守本规范。**
# UI 主题全面审计报告 v3.0

> **审计日期**: 2026-06-28  
> **审计范围**: 全项目（XML/Kotlin/Python/colors.xml/drawable/themes）  
> **审计标准**: 樱羽主题UI规范文档 v2.0  

---

## 一、审计结论

**你的怀疑是对的。** 亮色模式 `colors.xml` 有 **9 处旧紫色残留**（`#9B59B6` / `#8E44AD` / `#B07CC6`），加上硬编码颜色，总计 **59 处违规**。

**讽刺的是**：深色模式 `values-night/colors.xml` 反而比亮色模式更早更新为樱羽粉蓝系。这意味着深色模式下界面是正确的，但亮色模式（默认模式）下仍显示旧紫色。

---

## 二、违规分类统计

| 类别 | 数量 | 严重 | 说明 |
|------|:---:|:---:|------|
| colors.xml 亮色旧紫色 | 9 | 🔴 P0 | 颜色定义源自身就是旧紫色，drawable 引用它也会出错 |
| 矢量图标硬编码 #9B59B6 | 12 | 🟡 P1 | 设置页图标全是旧紫色 |
| Kotlin 代码硬编码 | 31 | 🟡 P1 | Color.parseColor() / Color.argb() / 0xFF |
| 布局文件硬编码 | 4 | 🟢 P2 | 消息气泡背景 |
| Python 硬编码 #9B59B6 | 3 | 🟢 P2 | 默认标签颜色 |

---

## 三、P0 严重问题：colors.xml 亮色模式旧紫色残留

> colors.xml 是颜色定义源。drawable 通过 `@color/btn_send_end` 引用，如果 colors.xml 本身定义就是 `#9B59B6`，那么所有引用它的地方都会错。

| 行号 | 颜色资源名 | 当前值（旧紫色） | 应改为（樱羽粉蓝） |
|:---:|-----------|------|------|
| 134 | `bubble_user_shadow` | `#209B59B6` | `#20FFDCE3`（淡樱粉半透） |
| 136 | `input_focused_border` | `#3D9B59B6` | `#3DB0C4DE`（淡天蓝） |
| 143 | `btn_send_end` | `#9B59B6` | `#D4E8F0`（淡粉蓝） |
| 145 | `send_inactive_v2_solid` | `#269B59B6` | `#18B0C4DE`（淡天蓝半透） |
| 146 | `send_inactive_v2_stroke` | `#409B59B6` | `#40B0C4DE`（淡天蓝半透） |
| 160 | `launcher_gradient_start` | `#8E44AD` | `#FFDCE3`（淡樱粉） |
| 161 | `launcher_gradient_center` | `#9B59B6` | `#D4E8F0`（淡粉蓝） |
| 162 | `launcher_gradient_end` | `#B07CC6` | `#B0C4DE`（淡天蓝） |
| 121 | `elysian_purple` | `#9B59B6` | `@color/primary`（与深色模式一致） |

**这些颜色由以下 drawable 引用，修复后全部自动生效：**

| 引用的 drawable | 使用的颜色 |
|------|------|
| `bg_bubble_user.xml` | `bubble_user_shadow` |
| `bg_input_field_focused.xml` | `input_focused_border` |
| `bg_btn_send.xml` | `btn_send_start` → `btn_send_end` |
| `bg_send_inactive_v2.xml` | `send_inactive_v2_solid` / `send_inactive_v2_stroke` |
| `bg_launcher_gradient.xml` | `launcher_gradient_start` / `center` / `end` |

---

## 四、P1 问题：12 个矢量图标硬编码 `#9B59B6`

| 文件 | 行号 |
|------|:---:|
| `drawable/ic_book.xml` | 4 |
| `drawable/ic_context.xml` | 4 |
| `drawable/ic_creative.xml` | 4 |
| `drawable/ic_detail.xml` | 4 |
| `drawable/ic_example.xml` | 4 |
| `drawable/ic_frequency.xml` | 4 |
| `drawable/ic_key.xml` | 4 |
| `drawable/ic_launcher_foreground.xml` | 13 |
| `drawable/ic_model.xml` | 4 |
| `drawable/ic_new_chat.xml` | 4 |
| `drawable/ic_notification.xml` | 11 |
| `drawable/ic_plugin.xml` | 9 |
| `drawable/ic_quiet.xml` | 4 |

**修复方案**：`android:fillColor="#9B59B6"` → `android:fillColor="@color/primary"`

**注意**：矢量图标中 `@color/` 引用在 Android 5.0+ 才支持（API 21）。本项目 minSdk 应 >= 21。

---

## 五、P1 问题：Kotlin 代码 31 处硬编码颜色

### 5.1 ParticleView.kt（9 处）— 粒子效果旧紫色

```kotlin
// 文件: ui/ParticleView.kt
// 问题: 粒子颜色用旧紫色系
Color.argb(128, 155, 89, 182)    // 紫色 → 应为淡天蓝
Color.argb(100, 155, 89, 182)    // 紫色 → 应为淡粉蓝
Color.argb(60, 120, 60, 140)     // 深紫 → 应为淡樱粉
Color.argb(40, 180, 160, 200)    // 灰紫 → 应为淡天蓝半透
```

### 5.2 ChatAdapter.kt（2 处）

```kotlin
Color.parseColor("#FFD0D9")      // → R.color.glow_pink
Color.parseColor("#2D1B3A")      // → R.color.text_on_pink
```

### 5.3 MemoryArchiveActivity.kt（6 处）

```kotlin
Color.parseColor("#80E57373")    // → R.color.accent_red + alpha
Color.parseColor("#40FFFFFF")    // → R.color.glass_bg_dark
Color.parseColor("#E6FFFFFF")    // → R.color.wb_card_bg
Color.parseColor("#E6FDF0F0")    // → R.color.sakura_pink + alpha
Color.parseColor("#E6D4E8F0")    // → R.color.sakura_blue + alpha
Color.parseColor("#E6FCE4E0")    // → R.color.wb_sakura_mid + alpha
```

### 5.4 MemoryCardAdapter.kt（3 处）

```kotlin
0xFFFDF0F0.toInt()               // → R.color.sakura_pink
0xFFD4E8F0.toInt()               // → R.color.sakura_blue
0xFFE8E8E8.toInt()               // → R.color.memory_archive_gray
```

### 5.5 VoiceController.kt（2 处）

```kotlin
Color.parseColor("#FF6B6B")      // → R.color.accent_red
```

### 5.6 RecordingOverlayView.kt（5 处）

```kotlin
Color.argb(80, 0, 0, 0)         // → R.color.glass_bg_dark
Color.argb(220, 45, 27, 58)     // → R.color.bg_mid (深色)
Color.parseColor("#FFB7C5")      // → R.color.bubble_user_start (×2)
Color.argb(180, 255, 183, 197)   // → R.color.bubble_user_start + alpha
```

### 5.7 PetalView.kt（5 处）

```kotlin
Color.parseColor("#FDF0F0")      // → R.color.sakura_pink
Color.parseColor("#FCE4E0")      // → R.color.wb_sakura_mid
Color.parseColor("#F8D0D8")      // → R.color.sakura_pink (变体)
Color.parseColor("#FDE8EC")      // → R.color.sakura_pink (变体)
Color.parseColor("#F9D8DC")      // → R.color.sakura_pink (变体)
```

---

## 六、P2 问题：布局文件硬编码

| 文件 | 行号 | 当前 | 应改为 |
|------|:---:|------|------|
| `layout/item_memory_card.xml` | 8 | `#E6FFFFFF` | `@color/wb_card_bg` |
| `layout/item_memory_card.xml` | 11 | `#1AB0C4DE` | `@color/wb_card_border` |
| `layout/item_message_self.xml` | 35 | `#B0C4DE` | `@drawable/bg_bubble_user`（渐变） |
| `layout/item_message_ai.xml` | 57 | `#B0C4DE` | `@color/bubble_ai` |

---

## 七、P2 问题：Python 端硬编码 `#9B59B6`

| 文件 | 行号 | 当前 | 应改为 |
|------|:---:|------|------|
| `chat_bridge/_memory.py` | 431 | `"#9B59B6"` | `"#B0C4DE"` |
| `src/memory/memory_types.py` | 211 | `"#9B59B6"` | `"#9090A0"`（`memory_default`） |
| `src/memory/orchestrator.py` | 720 | `"#9B59B6"` | `"#B0C4DE"` |
| `src/memory/vector_store.py` | 1671 | `"#9B59B6"` | `"#B0C4DE"` |
| `src/memory/vector_store.py` | 1701 | `"#9B59B6"` | `"#B0C4DE"` |

---

## 八、亮色 vs 深色不一致分析

| 颜色资源名 | 亮色值 | 深色值 | 状态 |
|-----------|------|------|:---:|
| `elysian_purple` | `#9B59B6` ❌ | `@color/primary` ✅ | 🔴 亮色未更新 |
| `btn_send_end` | `#9B59B6` ❌ | `#7B6B8A` ✅ | 🔴 亮色仍为旧紫色 |
| `launcher_gradient_*` | 紫色系 ❌ | 天蓝色系 ✅ | 🔴 亮色仍为旧紫色 |
| `bubble_user_shadow` | `#209B59B6` ❌ | `#20D4859B` ✅ | 🔴 亮色仍为旧紫色 |
| `input_focused_border` | `#3D9B59B6` ❌ | `#3DB0C4DE` ✅ | 🔴 亮色仍为旧紫色 |

**结论：深色模式已更新为樱羽粉蓝系，但亮色模式（默认、最常用）反而被遗漏了。**

---

## 九、修复优先级

| 优先级 | 范围 | 文件数 | 影响 |
|:---:|------|:---:|------|
| 🔴 P0 | colors.xml 亮色 9 处旧紫色 | 1 | 修复后 drawable 全部自动生效 |
| 🟡 P1 | 12 个矢量图标 #9B59B6 | 12 | 设置页图标颜色 |
| 🟡 P1 | Kotlin 代码 31 处 | 8 | 粒子效果/气泡/记忆卡/录音 |
| 🟢 P2 | 布局文件 4 处 | 4 | 消息气泡/记忆卡 |
| 🟢 P2 | Python 5 处 #9B59B6 | 4 | 默认标签颜色 |

---

## 十、建议修复策略

1. **先修 P0**：改 colors.xml 亮色模式 9 处旧紫色 → 一次性生效所有 drawable
2. **再修 P1 图标**：批量替换 `#9B59B6` → `@color/primary`
3. **修 P1 Kotlin**：8 个文件逐一替换硬编码
4. **修 P2 布局+Python**：4+4 个文件

**预计修复后**：所有界面统一为樱羽粉蓝系，彻底消除旧紫色。
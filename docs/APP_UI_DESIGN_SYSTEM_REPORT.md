# AI Companion APP — 整体 UI 设计体系方案报告

> 由架构师 + 前端工程师交叉讨论 + 交叉审核生成 | 2026-06-15

---

## 目录

1. [现状诊断](#一现状诊断)
2. [方案 A：Elysian Crystal（水晶宫殿）](#二方案-aelysian-crystal--三层玻璃拟态)
3. [方案 B：Luminous Flow（流动光效）](#三方案-bluminous-flow--流动光效)
4. [方案对比与推荐](#四方案对比与推荐)
5. [推荐方案详细规格](#五推荐方案详细规格)
6. [组件库规范](#六组件库规范)
7. [页面改造清单](#七页面改造清单)
8. [深色/亮色适配清单](#八深色亮色适配清单)
9. [可行性矩阵](#九可行性矩阵)
10. [实施路线图](#十实施路线图)

---

## 一、现状诊断

### 1.1 问题总览

通过对全部 8 个 Activity、24 个 drawable、2 套 colors.xml、2 套 themes.xml、5 个自定义 View 的审查，锁定以下 **10 个核心问题**：

| # | 问题 | 严重度 | 根因 |
|---|------|:--:|------|
| 1 | ParticleView 粒子颜色硬编码 `Color.argb()` | **高** | 无亮色模式感知，亮色模式下粉粒子在粉背景上消失 |
| 2 | MemoryManageActivity 使用 `?attr/colorSurface` 而非玻璃态 | **高** | 风格断裂最严重，与其余 7 个页面不统一 |
| 3 | 页面转场碎片化 | 中 | 仅 CharacterSelect 有自定义动画，其余用系统默认 |
| 4 | 亮色模式 glass_bg 使用紫色做毛玻璃，在粉色背景上不协调 | 中 | 颜色语义耦合了特定模式 |
| 5 | 设置页 GlassCard 内嵌纯文本样式，无统一卡片模板 | 中 | 无组件库抽象层 |
| 6 | 字体使用系统默认 `sans-serif`，无品牌字体 | 中 | 品牌感知弱 |
| 7 | 图标风格不一致 | 中 | 设置页用 ImageView，顶栏用 Unicode 文字图标 |
| 8 | 状态栏/导航栏颜色各页面不一致 | 中 | 未统一 ViewUtils 调用 |
| 9 | 动效零散 | 中 | MessageItemAnimator 专用，无通用动效规范 |
| 10 | 12 个 drawable 文件硬编码颜色值 | 中 | 深色/亮色模式下颜色无法自动切换 |

### 1.2 现有资产优势

| 资产 | 状态 | 评价 |
|------|------|------|
| 「往世乐土」主题世界观 | 明确 | 记忆/命运/羁绊隐喻，辨识度高 |
| 粉紫主色调 (#9B59B6 / #FFB7C5) | 成熟 | 温暖、辨识度高 |
| 玻璃拟态 (GlassCard) | 已实现 | 现代化设计语言 |
| 粒子动画 (ParticleView) | 已实现 | 灵动感强 |
| 聊天气泡双模式 | 已实现 | AI粉色/User渐变紫粉，区分度好 |
| 深色/亮色双模式 | 框架已有 | 基础色板已定义，但部分页面未适配 |

---

## 二、方案 A：Elysian Crystal — 三层玻璃拟态

### 2.1 设计哲学

**核心隐喻**：「往世乐土」是一座水晶宫殿，记忆如晶体碎片漂浮其中。界面本身就是宫殿的"玻璃墙"，用户可以透过它看见流动的粒子记忆。

**关键词**：晶体重叠、光折射、深度感知、奢华冷感

### 2.2 核心视觉

```
视觉层级（从底到顶）：
┌──────────────────────────────────┐
│  Layer 4: 前景交互层（输入框、按钮）    │  白底+8%透明度 + 毛玻璃模糊
│  Layer 3: 悬浮卡片层（GlassCard）    │  紫底+12%透明度 + 内外光晕
│  Layer 2: 装饰光层（大光晕+粒子）      │  径向渐变光晕 + 六边形晶体粒子
│  Layer 1: 深层背景（渐变底色）         │  深紫→暗紫垂直渐变
└──────────────────────────────────┘
```

**关键特征**：
- 三层玻璃效果形成深度视差
- 卡片左上角白色高光描边（模拟光源从左上照射）
- 粒子从圆形改为六边形晶体小片
- 按钮按压时产生紫粉色径向扩散光晕

### 2.3 颜色系统

采用 **语义化 Token 体系**，所有颜色通过 `attrs.xml` 声明，不再硬编码 `@color/xxx`。

新增 **Glass Token 四层**：

```
glassLayer1Bg  → 16% plum-400 (顶栏)
glassLayer2Bg  → 11% plum-400 (卡片)
glassLayer3Bg  → 6% white (输入框)
glassHighlightEdge → 20% white (高光描边)
```

双模式映射：深色使用 plum（紫）系，亮色使用 rose（粉）系。

### 2.4 优劣分析

| 维度 | 评价 |
|------|------|
| 视觉冲击力 | 极高 — 三层玻璃在 OLED 屏幕上非常震撼 |
| 主题契合度 | 极佳 — "水晶宫殿"隐喻完美契合 |
| 开发复杂度 | **高** — 三层玻璃需自定义 Canvas 绘制高光边缘 |
| 性能风险 | **中** — 多层半透明叠加 + 实时模糊可能导致低端机掉帧 |
| 亮色适配难度 | **高** — 玻璃参数需大量调参 |
| 工期 | **12-15 人天** |

---

## 三、方案 B：Luminous Flow — 流动光效

### 3.1 设计哲学

**核心隐喻**：「往世乐土」是一条发光的记忆之河，光线在暗色背景上流淌。界面元素如同河面上的浮光，轻、薄、流动。

**关键词**：光线流动、微交互、呼吸感、ACG 氛围

### 3.2 核心视觉

```
视觉层级（从底到顶）：
┌──────────────────────────────────┐
│  Layer 3: 交互层（卡片+输入框）      │  纯色半透明底 + 1dp 描边（无毛玻璃模糊）
│  Layer 2: 光流层（动态渐变光带）      │  缓慢流动的紫→粉→蓝紫渐变色带
│  Layer 1: 底色层（深色纯底）         │  纯色 #0F0A1A（深色）/ #FFF5F8（亮色）
└──────────────────────────────────┘
```

**关键特征**：
- **放弃多层玻璃**：仅保留一层半透明卡片，降低渲染压力
- **流动光带**：`GradientDrawable` + `ValueAnimator` 创建缓慢移动的径向渐变光带（2-3 个色块，8 秒周期）
- **粒子精简**：15 个圆形粒子（从 25 减至 15），只做垂直漂浮
- **微交互优先**：每个可交互元素都有触觉反馈
- **边缘发光**：输入框聚焦时仅边缘发光（`setShadowLayer`），不做外扩

### 3.3 颜色系统

与方案 A 相同的 Semantic Token 体系，但 **Glass Token 仅保留 2 层**，且通过 `text_primary` 百分比自动适配双模式：

```
glassCardBg  = 8% text_primary  → 深色=白透, 亮色=黑透（自动适配！）
glassInputBg = 4% text_primary  → 同理
```

**核心优势**：无需为双模式各写一套 glass 颜色，切换模式时自动正确。

### 3.4 优劣分析

| 维度 | 评价 |
|------|------|
| 视觉冲击力 | 中高 — 流动光带效果独特，ACG 光效风格讨喜 |
| 主题契合度 | 良好 — "记忆之河"隐喻成立 |
| 开发复杂度 | **中低** — 组件少，无实时模糊，纯 Canvas 动画 |
| 性能风险 | **低** — 全机型流畅 |
| 亮色适配难度 | **低** — glass 颜色自动适配 |
| 与现有代码差异 | **最小** — 最接近当前实现 |
| 工期 | **7-9 人天** |

---

## 四、方案对比与推荐

### 4.1 量化对比

| 维度 | 方案 A: Elysian Crystal | 方案 B: Luminous Flow |
|------|:--:|:--:|
| 视觉冲击力 | ★★★★★ | ★★★★ |
| 主题契合度 | ★★★★★ | ★★★★ |
| 开发复杂度 | ★★★★ (高) | ★★ (低) |
| 性能风险 | ★★★ (中) | ★ (低) |
| 亮色适配难度 | ★★★★ (高) | ★ (低) |
| 与现有代码差异 | ★★★★ (大) | ★ (小) |
| 可维护性 | ★★★ | ★★★★★ |
| 工期 | 12-15 天 | 7-9 天 |
| 组件数量 | 13 个 | 8 个 |

### 4.2 交叉审核结论

**架构师推荐**：方案 B  
**前端工程师推荐**：方案 B（其组件方案天然匹配方案 B 的轻量路线）  
**一致性确认**：双方均认为方案 B 在风险/收益/工期上是最优解

### 4.3 推荐：方案 B「Luminous Flow」

**推荐理由**：

1. **工期不挤占功能开发**：方案 B 7-9 天 vs 方案 A 12-15 天，节省的 5-6 天可用于世界书、TTS 等功能
2. **性能可预测**：纯 Canvas 动画，全 API 级别兼容，无低端机掉帧风险
3. **与现有代码高度兼容**：glass 颜色自动适配机制意味着只需改 `colors.xml` 定义，无需动 layout 文件
4. **ACG 风格匹配**：流动光效更接近日系手游 UI 风格（《明日方舟》《原神》），比西方奇幻水晶风更契合目标用户
5. **渐进式可落地**：可分 4 阶段逐步实施，每阶段可独立交付验收

---

## 五、推荐方案详细规格

### 5.1 颜色 Token 体系

使用 `text_primary` 的百分比 alpha 作为玻璃底色，实现双模式自动适配：

```
核心公式：
  glassCardBg  = 8% text_primary (深色→白透, 亮色→黑透)
  glassInputBg = 4% text_primary
  cardBorder   = 12% text_primary
```

### 5.2 字体层级

```
角色名/标题: "M PLUS Rounded 1c" Bold 20-24sp（Google Fonts, 免费, 日系圆体）
正文/UI:     系统默认 sans-serif 15sp
辅助文字:    系统默认 sans-serif 13sp
标签/时间:   系统默认 sans-serif 11sp
```

### 5.3 圆角体系

| 元素 | 圆角 |
|------|:--:|
| 按钮/输入框 | 24dp |
| 卡片 | 16dp |
| 气泡 | 18dp |
| Chip/Tag | 12dp |
| 对话框 | 20dp |
| 底部弹窗(top) | 24dp |

### 5.4 间距系统（4dp 基准）

| 级别 | 值 | 用途 |
|:--:|:--:|------|
| xs | 4dp | 图标-文字间距 |
| sm | 8dp | 列表项内间距 |
| md | 12dp | 卡片内边距 |
| lg | 16dp | 页面边距 |
| xl | 20dp | 卡片间距 |
| 2xl | 24dp | 大区块间距 |
| 3xl | 32dp | 页面级间距 |

### 5.5 转场动画

| 转场 | 动画 | 时长 | 插值器 |
|------|------|:--:|------|
| push (进入) | slide_in_right + fade_in | 250ms | Decelerate |
| pop (返回) | slide_out_right + fade_out | 200ms | Accelerate |
| dialog show | 缩放 95%→100% + fade_in | 250ms | Overshoot |
| dialog dismiss | 缩放 100%→95% + fade_out | 150ms | Accelerate |

统一通过 `ActivityTransitionHelper` 管理。

### 5.6 动效体系

| 动效 | 规格 |
|------|------|
| 光带流动 | 3 色块径向渐变循环移动，8s 周期 |
| 粒子漂浮 | 15 粒子垂直匀速，速度 0.5-2.0px/s |
| 按钮按压 | 缩放 100%→98%，100ms + 涟漪 |
| 消息入场 | Y+20→0 + alpha 0→1，180ms |
| Tab 切换 | 指示器颜色渐变过渡 |
| 状态切换 | crossfade 300ms |

---

## 六、组件库规范

### 6.1 组件清单

| 组件 | 实现方式 | 优先级 |
|------|----------|:--:|
| **AppButton.Primary** | MaterialButton + 粉紫渐变背景 | P1 |
| **AppButton.Secondary** | MaterialButton + OutlinedButton | P1 |
| **AppButton.Text** | MaterialButton + TextButton | P1 |
| **GlassCard** | 自定义 MaterialCardView 子类 | P1 |
| **AppInput** | TextInputLayout + TextInputEditText | P2 |
| **AppDialog** | MaterialAlertDialogBuilder | P2 |
| **AppBottomSheet** | BottomSheetDialogFragment | P3 |
| **AppSwitch** | SwitchCompat + selector drawable | P2 |
| **AppChip** | MaterialChip | P2 |
| **AppEmptyState** | include 模板 | P2 |
| **AppLoadingState** | CircularProgressIndicator | P2 |

### 6.2 关键组件代码规格

**GlassCard.kt**（新建）：

```kotlin
class GlassCard @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = R.style.GlassCard
) : MaterialCardView(context, attrs, defStyleAttr) {

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        cardBackgroundColor = ContextCompat.getColorStateList(context, R.color.glass_bg)
        strokeColor = ContextCompat.getColorStateList(context, R.color.glass_border)
    }
}
```

**AppButton.Primary**（styles.xml 新增）：

```xml
<style name="AppButton.Primary" parent="Widget.MaterialComponents.Button">
    <item name="android:textColor">@color/bubble_user_text</item>
    <item name="backgroundTint">@null</item>
    <item name="android:background">@drawable/bg_btn_primary</item>
    <item name="cornerRadius">24dp</item>
    <item name="android:minHeight">@dimen/button_default</item>
</style>
```

**bg_btn_primary.xml**（新建 drawable）：

```xml
<shape android:shape="rectangle">
    <gradient android:angle="135"
        android:startColor="@color/elysian_pink"
        android:endColor="@color/elysian_purple" />
    <corners android:radius="24dp" />
</shape>
```

**ActivityTransitionHelper.kt**（新建）：

```kotlin
object ActivityTransitionHelper {
    fun startWithSlideIn(activity: Activity, intent: Intent) {
        activity.startActivity(intent, ActivityOptions.makeCustomAnimation(
            activity, R.anim.slide_in_right, R.anim.slide_out_left).toBundle())
    }
    fun finishWithSlideOut(activity: Activity) {
        activity.finish()
        activity.overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
    }
}
```

---

## 七、页面改造清单

### 7.1 MemoryManageActivity（最优先改造）

| 元素 | 当前 | 改造后 |
|------|------|--------|
| 根布局背景 | `?attr/colorSurface` | `@drawable/bg_main_gradient` |
| 顶栏 | Button + backgroundTint | `AppButton.Text` |
| 搜索栏 | `@android:drawable/edit_text` | `bg_input_field` background |
| 统计栏 | `?attr/colorSurfaceVariant` | `@color/glass_bg` |
| 空状态 | 纯 TextView | include `layout_app_empty_state` |
| 清空按钮 | Button + `@color/accent_red` | `AppButton.Primary` 红色 |
| 底部操作栏 | `?attr/colorSurfaceVariant` | `@drawable/bg_glass_bottom` |
| 颜色引用 | 全部 `?attr/` | 统一改为 `@color/` |

### 7.2 MainActivity

| 元素 | 当前 | 改造后 |
|------|------|--------|
| 发送按钮 | TextView + bg_send_active/inactive | `AppButton.Primary` MaterialButton |
| 搜索栏背景 | `@color/bg_mid` 无圆角 | `@drawable/bg_glass_bottom` |
| 顶栏返回按钮 | TextView + "..." 字符 | ImageButton + ic_menu 图标 |

### 7.3 SettingsActivity

| 元素 | 当前 | 改造后 |
|------|------|--------|
| 卡片 | MaterialCardView + GlassCard style | `GlassCard` 自定义 View |
| 卡片内部 | 重复的图标+标题+摘要+箭头 | include `layout_app_list_item` |

### 7.4 CharacterActivity

| 元素 | 当前 | 改造后 |
|------|------|--------|
| 卡片 | MaterialCardView + GlassCard style | `GlassCard` 自定义 View |
| 自定义角色入口 | TextView + bg_input_field | `AppButton.Secondary` |

### 7.5 PluginManageActivity

| 元素 | 当前 | 改造后 |
|------|------|--------|
| 开关 | TextView + "●" 字符 | `SwitchCompat` + `AppSwitch` style |
| 分类标签 | TextView + bg_plugin_category_tag | `MaterialChip` + `AppChip.Category` |
| 详情弹窗 | AlertDialog.Builder | `MaterialAlertDialogBuilder` |

### 7.6 CharacterEditActivity

| 元素 | 当前 | 改造后 |
|------|------|--------|
| 输入框 | 纯 EditText + bg_input_field | `TextInputLayout` + `TextInputEditText` |
| 保存按钮 | TextView 模拟 | `AppButton.Primary` |

### 7.7 WorldBookManageActivity

| 元素 | 当前 | 改造后 |
|------|------|--------|
| 条目列表 | 现有样式 | 统一使用 `GlassCard` 包裹 |

---

## 八、深色/亮色适配清单

### 8.1 12 个硬编码颜色 drawable 需改造

| 文件 | 硬编码 | 改为 |
|------|--------|------|
| `bg_input_field_focused.xml` | `#3D9B59B6` | `@color/input_field_border_focused` |
| `bg_send_inactive_v2.xml` | `#269B59B6`, `#409B59B6` | `@color/send_inactive_bg_v2` |
| `bg_btn_send.xml` | `#FFB7C5`, `#9B59B6` | `@color/elysian_pink`, `@color/elysian_purple` |
| `bg_btn_send_disabled.xml` | `#251838` | `@color/send_inactive_bg` |
| `bg_switch_thumb.xml` | `#FFB7C5` | `@color/switch_thumb_on` |
| `bg_switch_track.xml` | `#251838` | `@color/switch_track_off` |
| `bg_typing_dot.xml` | `#FFB7C5` | `@color/elysian_pink` |
| `bg_type_label.xml` | `#2196F3` | `@color/memory_type_default` |
| `bg_plugin_category_tag.xml` | `#1A9B59B6`, `#409B59B6` | `@color/glass_bg`, `@color/glass_border` |
| `bg_divider.xml` | `#1DFFFFFF` | `@color/divider` |
| `bg_settings_card.xml` | `#1A1028`, `#2DFFFFFF` | `@color/glass_bg`, `@color/glass_border` |
| `bg_status_online.xml` | `#7DE2A0` | `@color/status_online` |

### 8.2 ParticleView 亮色适配

```kotlin
// 深色模式粒子
intArrayOf(
    Color.argb(128, 255, 183, 197),  // 粉色半透
    Color.argb(128, 155, 89, 182),    // 紫色半透
    Color.argb(96, 255, 255, 255)     // 白色微透
)

// 亮色模式粒子（降低透明度，移除白色）
intArrayOf(
    Color.argb(100, 155, 89, 182),    // 紫色半透
    Color.argb(100, 255, 183, 197),   // 粉色半透
    Color.argb(60, 120, 60, 140),     // 深紫微透
    Color.argb(40, 180, 160, 200)     // 灰紫微透
)
```

### 8.3 新增颜色资源

需要在 `values/colors.xml` 和 `values-night/colors.xml` 中新增约 15 个颜色定义。

---

## 九、可行性矩阵

| 设计需求 | M2 + XML | 需要 M3 | 需要 Compose |
|------|:--:|:--:|:--:|
| AppButton 统一 (MaterialButton) | 可行 | 否 | 否 |
| GlassCard 自定义 View | 可行 | 否 | 否 |
| AppInput 统一 (TextInputLayout) | 可行 | 否 | 否 |
| AppDialog 统一 (MaterialAlertDialogBuilder) | 可行 | 否 | 否 |
| AppBottomSheet (BottomSheetDialogFragment) | 可行 | 否 | 否 |
| AppSwitch (SwitchCompat) | 可行 | 否 | 否 |
| AppChip (MaterialChip) | 可行 | 否 | 否 |
| AppEmptyState / AppLoadingState (include) | 可行 | 否 | 否 |
| Activity 转场动画 (overridePendingTransition) | 可行 | 否 | 否 |
| 共享元素过渡 (transitionName) | 可行 | 否 | 否 |
| 主题切换动画 (recreate + fade) | 可行 | 否 | 否 |
| 粒子动画亮色适配 (onConfigurationChanged) | 可行 | 否 | 否 |
| 深色/亮色完整适配 (values-night) | 可行 | 否 | 否 |
| 流动光带背景动画 (FlowLightView) | 可行 | 否 | 否 |
| Material You 动态取色 | **不可行** | 需要 | 否 |
| 高级共享元素变形 | 部分可行 | 部分需要 | 推荐 |

**结论：所有方案 B 的设计需求在现有 M2 + XML 技术栈下均可实现，无需升级框架。**

---

## 十、实施路线图

### Phase 1：基础设施（2 天）

| 步骤 | 任务 | 文件 |
|:--:|------|------|
| 1.1 | 新增 `attrs.xml` 定义 Theme 属性 | `values/attrs.xml` |
| 1.2 | 重构双模式 colors.xml Token | `values/colors.xml` + `values-night/colors.xml` |
| 1.3 | 创建 `ActivityTransitionHelper` 工具类 | `ActivityTransitionHelper.kt` |
| 1.4 | 创建 `FlowLightView` 流动光带 | `FlowLightView.kt` |

### Phase 2：组件统一（2.5 天）

| 步骤 | 任务 | 文件 |
|:--:|------|------|
| 2.1 | 创建 `GlassCard` 自定义 View | `GlassCard.kt` |
| 2.2 | 创建 `AppButton` 系列 style | `styles.xml` + `bg_btn_primary.xml` |
| 2.3 | 创建 `AppChip` / `AppSwitch` / `AppInput` style | `styles.xml` + drawable |
| 2.4 | 创建 `AppEmptyState` / `AppLoadingState` 模板 | `layout_app_empty_state.xml` 等 |
| 2.5 | `ParticleView` 颜色 Theme 适配 | `ParticleView.kt` |
| 2.6 | 字体引入 (M PLUS Rounded 1c) | `res/font/` |

### Phase 3：页面改造（2 天）

| 步骤 | 任务 | 优先级 |
|:--:|------|:--:|
| 3.1 | **MemoryManageActivity 全面改造** | 最高 |
| 3.2 | SettingsActivity 卡片替换 | 高 |
| 3.3 | PluginManageActivity 组件替换 | 高 |
| 3.4 | MainActivity 按钮替换 | 中 |
| 3.5 | CharacterActivity / CharacterEditActivity 改造 | 中 |
| 3.6 | WorldBookManageActivity / SettingsDetailActivity 微调 | 低 |

### Phase 4：亮色模式 + 验证（1.5 天）

| 步骤 | 任务 |
|:--:|------|
| 4.1 | 12 个 drawable 硬编码颜色清换 |
| 4.2 | 亮色模式全局回归测试 |
| 4.3 | 低端机性能验证 (API 24 模拟器) |
| 4.4 | 视觉走查 + 微调 |

---

## 附录：文件清单

```
新增文件（约 15 个）：
├── res/values/attrs.xml                          ← 新增
├── res/drawable/bg_btn_primary.xml               ← 新增
├── res/drawable/bg_flow_light.xml                 ← 新增
├── res/layout/layout_app_empty_state.xml          ← 新增
├── res/layout/layout_app_loading_state.xml        ← 新增
├── res/layout/layout_app_list_item.xml            ← 新增
├── res/font/m_plus_rounded_1c.xml                 ← 新增
├── java/.../ActivityTransitionHelper.kt           ← 新增
├── java/.../ui/GlassCard.kt                       ← 新建（替代 style）
├── java/.../ui/FlowLightView.kt                   ← 新增
└── java/.../ui/AppBottomSheetFragment.kt          ← 新增

修改文件（约 30 个）：
├── res/values/colors.xml                          ← 重构
├── res/values-night/colors.xml                    ← 重构
├── res/values/themes.xml                          ← 新增 style
├── res/values/styles.xml                          ← 新增
├── 12 个 drawable 文件                            ← 硬编码→颜色引用
├── 8 个 layout 文件                               ← 组件替换
├── 5 个 Kotlin 文件                               ← 按钮/动画替换
```

---

> **报告结束**。本报告由架构师 + 前端工程师交叉讨论生成，并经过交叉审核确认可行性。推荐方案 B「Luminous Flow」在视觉品质、开发成本、性能三者间取得了最优平衡。
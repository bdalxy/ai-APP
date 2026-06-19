# AI伴侣APP 主题与UI设计报告

> 生成日期：2026-06-19 | 最后更新：2026-06-20 | 基于 [樱羽主题UI方案_设计报告_2026-06-18.md](design/樱羽主题UI方案_设计报告_2026-06-18.md) 初稿
> 供新 AI 会话参考

---

## 一、主题概念

**主题名称**：樱羽

**命名隐喻**：
- **樱**：淡樱粉色系，温暖、柔软、私密，如初春被窝
- **羽**：羽毛般轻盈的交互，轻量、无负担
- **玻璃糖纸**：透明、半透明质感，边缘高光描边

### 五大设计原则

1. **情感映射**：颜色冷暖对应记忆距离，越久远越冷白
2. **物理反馈**：点击下沉、飘落粒子、曲线飞入，模拟真实世界
3. **仪式感停顿**：删除、发送、加载均伴随过渡动画
4. **本地私密感**：所有记忆不可云端同步，强调"数字生命已落地"
5. **轻量可扩展**：插件系统可选、世界书可编辑

---

## 二、配色方案

### 2.1 亮色模式（樱羽）

| 颜色名称 | 色值 | 占比 | 用途 |
|------|------|:---:|------|
| 淡樱粉 | `#FDF0F0` | 60% | 页面背景、用户气泡 |
| 淡天蓝 | `#B0C4DE` ~ `#D4E8F0` | 15% | 点缀高光、次要文字 |
| 天使白 | `#FFFFFF` | 25% | 卡片底色、AI气泡 |
| 暖黑 | `#4A3B3A` | — | 正文阅读 |
| 深灰 | `#3A404A` | — | AI气泡内文 |

### 2.2 深色模式（夜樱）

| 颜色名称 | 色值 | 用途 |
|------|------|------|
| 暖黑底 | `#1E1518` | 页面背景 |
| 暗樱粉 | `#D4859B` | 用户气泡、主色 |
| 暖白 | `#E8DCD8` | 正文文字 |

### 2.3 实际实现中的颜色映射（`colors.xml`）

> 以下为当前代码中实际使用的颜色值，与樱羽设计方案的对应关系。

| 设计色 | 代码中色名 | 亮色模式值 | 深色模式值 |
|--------|----------|-----------|-----------|
| 淡樱粉 | `bg_dark` | `#FFF5F8` | `#1A0E1F` |
| 淡樱粉（卡片） | `bg_mid` | `#FFE8ED` | `#241528` |
| 淡樱粉（分层） | `bg_light` | `#FFDCE3` | `#2E1D35` |
| 天使白 | `sakura_white` | `#FFFFFF` | — |
| 淡天蓝 | `sakura_border` | `#B0C4DE` | — |
| 暖黑 | `wb_text_warm` | `#4A3B3A` | `#D0C8C6` |

### 2.4 强调色（辅助色）

| 色名 | 亮色值 | 深色值 | 用途 |
|------|--------|--------|------|
| `elysian_purple` | `#9B59B6` | `#B388FF` | 按钮、开关、强调元素（紫色辅助） |
| `elysian_pink` | `#FFB7C5` | `#CC8899` | 装饰、次要按钮 |
| `elysian_deep` | `#7B2D8B` | `#9B59B6` | 渐变终点、嵌套元素 |
| `elysian_pink_light` | `#FFD0D9` | `#AA6677` | 高亮、光晕 |

### 2.5 对话气泡

| 气泡 | 亮色 | 深色 | 设计说明 |
|------|------|------|------|
| AI 气泡 | `#F5E6FF`（天使白变体） | `#2E2040` | 1dp 淡天蓝边缘高光 |
| 用户气泡 | `#C77DFF→#9B59B6` 渐变 | `#9B59B6→#7B2D8B` | 不对称圆角（右下24dp，花瓣形态） |
| 打字气泡 | `#E8D5F5` | `#2A1E35` | AI 输入中指示 |

### 2.6 文字颜色

| 角色 | 亮色 | 深色 | 说明 |
|------|------|------|------|
| 主文字 | `#2D1B3A` | `#E8E0F0` | 高对比度 |
| 次级文字 | `#6B5A7A` | `#B0A0C0` | 中等对比度 |
| 三级文字 | `#9A8AAA` | `#7A6A8A` | 提示/占位 |

---

## 三、三页面设计规范

### 3.1 对话页（絮语）

- **布局比例**：顶部 8% + 对话流 77% + 输入区 15%
- **用户气泡**：不对称圆角，右下 24dp 大圆角（花瓣形态）
- **AI气泡**：天使白底色，1dp 淡天蓝边缘高光
- **微交互**：AI 呼吸光晕、消息曲线飞入、下拉花瓣飘落

### 3.2 记忆档案馆（时光）

- **布局比例**：标题栏 10% + 筛选器 10% + 瀑布流 80%
- **列表形式**：2列 StaggeredGrid 瀑布流
- **色温渐变**：向上滚动淡樱粉→淡天蓝→浅灰（隐喻记忆褪色）
- **长按删除**：裂纹光效→4碎片飘走（破碎动画）

### 3.3 世界书（设定页）

- **设计定位**：非人格化，强调客观常识，与"人格记忆"区分
- **布局**：顶部角色基础信息 + 常识条目列表 + 羽毛笔新增按钮
- **糖纸模态框**：半透明天使白，边缘淡天蓝高光
- 配色详见 [第五节 功能模块独立配色](#五功能模块独立配色)

---

## 四、毛玻璃效果（Glassmorphism）

### 4.1 核心颜色

| 属性 | 亮色模式 | 深色模式 |
|------|----------|----------|
| 玻璃背景 | `#809B59B6`（50%透明度紫色） | `#80B388FF`（50%透明度亮紫） |
| 玻璃边框 | `#8C9B59B6`（55%透明度紫色） | `#8CB388FF`（55%透明度亮紫） |
| 暗玻璃 | `#14FFFFFF`（8%透明度白色） | `#14000000`（8%透明度黑色） |

### 4.2 实现方式

文件：[BlurUtils.kt](file:///f:/Trae%20AI/ai-APP/android/app/src/main/java/com/aicompanion/app/ui/BlurUtils.kt)

多 API 级别降级方案：
- **API 31+**（Android 12+）：`RenderEffect.createBlurEffect()` — 原生硬件加速模糊
- **API 26-30**：`RenderScript` + `ScriptIntrinsicBlur` — GPU 加速模糊
- **API <26**：半透明遮罩降级（本项目 minSdk=26，此路径保留）

### 4.3 玻璃态组件

| 组件 | Drawable | 说明 |
|------|----------|------|
| 顶部栏 | [bg_glass_top.xml](file:///f:/Trae%20AI/ai-APP/android/app/src/main/res/drawable/bg_glass_top.xml) | 渐变透明背景 |
| 卡片 | [bg_glass_card.xml](file:///f:/Trae%20AI/ai-APP/android/app/src/main/res/drawable/bg_glass_card.xml) | 圆角半透明卡片 |
| 边缘高光 | [bg_glass_edge.xml](file:///f:/Trae%20AI/ai-APP/android/app/src/main/res/drawable/bg_glass_edge.xml) | 玻璃糖纸效果 |
| 对话框 | [GlassDialog.kt](file:///f:/Trae%20AI/ai-APP/android/app/src/main/java/com/aicompanion/app/ui/GlassDialog.kt) | 玻璃态弹窗 |

### 4.4 玻璃态样式

```xml
<!-- GlassCard: 所有卡片的标准样式 -->
<style name="GlassCard" parent="Widget.MaterialComponents.CardView">
    <item name="cardBackgroundColor">@color/glass_bg</item>
    <item name="strokeColor">@color/glass_border</item>
    <item name="strokeWidth">1dp</item>
    <item name="cardCornerRadius">12dp</item>
    <item name="cardElevation">0dp</item>
</style>
```

---

## 五、功能模块独立配色

### 5.1 世界书（淡樱粉 + 淡天蓝主题，非人格化）

与主聊天界面区分，世界书使用淡樱粉+淡天蓝配色，强调客观常识（非人格化）。

| 元素 | 颜色 | 说明 |
|------|------|------|
| 背景渐变 | `#FDF0F0→#FCE4E0→#F8D8D8` | 淡樱粉渐变 |
| 卡片 | 天使白 80% 透明 `#CCFFFFFF` | 糖纸质感 |
| 边框 | 淡天蓝 `#B0C4DE` 25% 透明 | 边缘高光 |
| 正文 | 暖黑 `#4A3B3A` | 阅读舒适 |
| 次要文字 | `#8A7B7A` | 辅助信息 |
| FAB | 淡樱粉 `#FCE4E0` | 羽毛笔按钮 |
| 糖纸模态框 | 天使白 `#F5FFFFFF` + 淡天蓝边框 | 编辑弹窗 |

### 5.2 记忆类型标签

| 类型 | 亮色 | 深色 | 语义 |
|------|------|------|------|
| 情景记忆 | `#6C5CE7` | `#A29BFE` | 紫色 — 情感事件 |
| 语义记忆 | `#4CAF50` | `#66BB6A` | 绿色 — 知识事实 |
| 用户事实 | `#FF9800` | `#FFA726` | 橙色 — 个人信息 |
| 默认 | `#9E9E9E` | `#757575` | 灰色 — 未分类 |

### 5.3 辅助功能色

| 用途 | 颜色 |
|------|------|
| 成功/在线 | `#4CAF50` / `#7DE2A0` |
| 警告 | `#FF9800` |
| 错误/删除 | `#E57373` |

---

## 六、组件样式体系

### 6.1 按钮

| 样式 | 外观 | 使用场景 |
|------|------|----------|
| `AppButton.Primary` | 粉紫渐变填充，白色文字 | 主操作（发送、确认） |
| `AppButton.Secondary` | 透明底 + 紫色描边 | 次要操作（取消、返回） |
| `AppButton.Text` | 无背景，紫色文字 | 文字链接、轻操作 |

### 6.2 输入框

| 样式 | 外观 | 使用场景 |
|------|------|----------|
| `AppInput` | 半透明白底 + 紫色边框 | 单行输入 |
| `AppInput.Search` | 继承 AppInput，40dp 高度 | 搜索框 |
| `AppInput.Multiline` | 继承 AppInput，多行 | 文本编辑 |

### 6.3 开关

| 样式 | 外观 |
|------|------|
| `AppSwitch` | 粉色滑块 + 紫色轨道 |

### 6.4 文本样式

| 样式 | 字号 | 粗细 | 使用场景 |
|------|------|------|----------|
| `AppText.PageTitle` | 20sp | Bold | 页面标题 |
| `AppText.CardTitle` | 16sp | Bold | 卡片标题 |
| `AppText.CardSubtitle` | 13sp | Normal | 卡片副标题 |
| `AppText.Caption` | 12sp | Normal | 标签/说明 |

### 6.5 标签

| 样式 | 外观 |
|------|------|
| `AppChip` | 紫色填充，白色文字，圆角 |

---

## 七、技术实现状态

| 阶段 | 内容 | 状态 |
|:---:|------|:---:|
| Phase 1 | 色彩系统重构（淡樱粉配色） | ✅ 已完成 |
| Phase 2 | 对话页微交互（呼吸光晕+飞入+花瓣） | ✅ 已完成 |
| Phase 3 | 记忆档案馆（瀑布流+色温+破碎） | ✅ 已完成 |
| Phase 4 | 高性能毛玻璃（API 31+） | ⬜ 待实现 |

---

## 八、Material Design 3 映射

主题继承 `Theme.MaterialComponents.NoActionBar`，颜色映射：

| MD3 令牌 | 映射值 | 说明 |
|----------|--------|------|
| `colorPrimary` | `elysian_purple` (#9B59B6) | 紫色 — 强调操作 |
| `colorPrimaryVariant` | `elysian_deep` (#7B2D8B) | 深紫 — 状态栏/渐变 |
| `colorSecondary` | `elysian_pink` (#FFB7C5) | 粉色 — 次要元素 |
| `colorSurface` | `bg_mid` | 淡樱粉 — 卡片表面 |
| `colorBackground` | `bg_dark` | 淡樱粉 — 主背景 |
| `colorOnPrimary` | 白色 | |
| `colorOnSecondary` | 深紫文字 | |

---

## 九、关键文件索引

| 用途 | 文件路径 |
|------|----------|
| 樱羽设计初稿 | [design/樱羽主题UI方案_设计报告_2026-06-18.md](design/樱羽主题UI方案_设计报告_2026-06-18.md) |
| 亮色主题样式 | [values/themes.xml](file:///f:/Trae%20AI/ai-APP/android/app/src/main/res/values/themes.xml) |
| 深色主题样式 | [values-night/themes.xml](file:///f:/Trae%20AI/ai-APP/android/app/src/main/res/values-night/themes.xml) |
| 亮色颜色 | [values/colors.xml](file:///f:/Trae%20AI/ai-APP/android/app/src/main/res/values/colors.xml) |
| 深色颜色 | [values-night/colors.xml](file:///f:/Trae%20AI/ai-APP/android/app/src/main/res/values-night/colors.xml) |
| 毛玻璃工具 | [ui/BlurUtils.kt](file:///f:/Trae%20AI/ai-APP/android/app/src/main/java/com/aicompanion/app/ui/BlurUtils.kt) |
| 玻璃态对话框 | [ui/GlassDialog.kt](file:///f:/Trae%20AI/ai-APP/android/app/src/main/java/com/aicompanion/app/ui/GlassDialog.kt) |
| 玻璃态 drawable | [drawable/bg_glass_*.xml](file:///f:/Trae%20AI/ai-APP/android/app/src/main/res/drawable/) |
| 主布局 | [layout/activity_main.xml](file:///f:/Trae%20AI/ai-APP/android/app/src/main/res/layout/activity_main.xml) |

---

## 十、对新 AI 的设计约束

1. **主色是淡樱粉，不是紫色** — 淡樱粉 `#FDF0F0` 占 60% 面积，紫色 `#9B59B6` 只是辅助强调色
2. **不要随意添加新颜色** — 优先使用现有颜色体系，如需新颜色需在 colors.xml 中定义并同步 values-night/
3. **不要改成 Material You** — 当前使用自定义主题色，不是动态取色
4. **不要去掉毛玻璃效果** — 玻璃糖纸质感是核心视觉特征
5. **不要移除深色模式支持** — 所有颜色必须在 values/ 和 values-night/ 同步定义
6. **不要使用硬编码颜色** — 布局文件中的颜色必须通过 `@color/xxx` 引用
7. **世界书用淡樱粉+淡天蓝** — 与主界面的紫色强调色区分，强调非人格化
8. **所有设计决策以樱羽方案为准** — 详见 [樱羽主题UI方案_设计报告_2026-06-18.md](design/樱羽主题UI方案_设计报告_2026-06-18.md)

---

> 本报告基于 DeepSeek 樱羽主题UI方案初稿编写，所有设计决策以樱羽方案为准。
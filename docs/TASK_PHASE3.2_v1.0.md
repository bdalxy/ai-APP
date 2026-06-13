# 第三阶段收尾：DiffUtil / ViewBinding 迁移

> 版本：V1.0 | 创建时间：2026-06-13 | 状态：待审核

---

## 前置现状

| 组件 | DiffUtil | ViewBinding | 备注 |
|------|:---:|:---:|------|
| ChatAdapter | ✅ 已完成 | N/A | `replaceMessages()` + `MessageDiffCallback` |
| MemoryAdapter | ✅ 已完成 | N/A | `replaceItems()` + `DiffCallback` |
| CharacterListAdapter | ❌ 未实现 | N/A | 内嵌于 CharacterManageActivity，无 DiffUtil |
| MainActivity | N/A | ❌ findViewById | 聊天主界面 |
| SettingsActivity | N/A | ❌ findViewById | 设置页面 |
| CharacterManageActivity | N/A | ❌ findViewById | 角色管理列表 |
| CharacterEditActivity | N/A | ❌ findViewById | 角色编辑 |
| MemoryManageActivity | N/A | ❌ findViewById | 记忆管理 |
| CharacterActivity | N/A | ❌ findViewById | 角色卡片页 |

---

## 任务依赖关系图

```
┌────────────────────────────────┐
│  T1: 启用 ViewBinding          │ ← 基础配置
│  优先级: P0                    │
└────────────┬───────────────────┘
             │
    ┌────────┴────────┐
    ↓                 ↓
┌─────────────────┐  ┌──────────────────────────────┐
│ T2: Activity     │  │ T3: CharacterListAdapter     │
│ ViewBinding 迁移  │  │ DiffUtil 迁移                │
│ 优先级: P1       │  │ 优先级: P2                   │
│                  │  │                              │
│ 涉及 6 个        │  │ 独立任务，无依赖              │
│ Activity         │  │                              │
└────────┬────────┘  └──────────────┬───────────────┘
         │                          │
         └──────────┬───────────────┘
                    ↓
         ┌─────────────────────┐
         │ T4: 构建 + 验证      │
         │ 优先级: P0          │
         └─────────────────────┘
```

---

## T1: 启用 ViewBinding [P0]

| 属性 | 值 |
|------|------|
| **状态** | 待开始 |
| **文件** | `android/app/build.gradle.kts` |
| **前置依赖** | 无 |

**具体任务**:
- [ ] 在 `buildFeatures` 块中启用 `viewBinding = true`
- [ ] 确认编译通过（Gradle sync 后自动生成 Binding 类）

**验收标准**:
- `./gradlew assembleDebug` 编译通过
- 每个布局 XML 自动生成对应的 Binding 类（如 `ActivityMainBinding`）

---

## T2: Activity ViewBinding 迁移 [P1]

| 属性 | 值 |
|------|------|
| **状态** | 待开始 |
| **文件** | 6 个 Activity |
| **前置依赖** | T1 完成 |

**具体任务**:

### T2.1: MainActivity
- [ ] `findViewById` → `ActivityMainBinding.inflate(layoutInflater)`
- [ ] `lateinit var rvMessages` → `binding.rvMessages`
- [ ] `lateinit var etInput` → `binding.etInput`
- [ ] 等等...

### T2.2: SettingsActivity
- [ ] 迁移所有 `findViewById` → `binding.*`
- [ ] 注意 SwitchCompat 绑定

### T2.3: CharacterManageActivity
- [ ] 迁移所有 `findViewById` → `CharacterManageActivityBinding`
- [ ] 注意 `findViewById<TextView>(R.id.btnBack)` 等

### T2.4: CharacterEditActivity
- [ ] 迁移所有 `findViewById` → `CharacterEditActivityBinding`

### T2.5: MemoryManageActivity
- [ ] 迁移所有 `findViewById` → `MemoryManageActivityBinding`
- [ ] 注意 `bindViews()` 方法中的批量绑定

### T2.6: CharacterActivity
- [ ] 迁移 `findViewById` → `CharacterActivityBinding`

**验收标准**:
- 每个 Activity 不再有 `findViewById` 调用
- 编译通过，无 lint 错误
- 运行时功能正常（点击、滚动、输入等）

---

## T3: CharacterListAdapter DiffUtil 迁移 [P2]

| 属性 | 值 |
|------|------|
| **状态** | 待开始 |
| **文件** | `CharacterManageActivity.kt` |
| **前置依赖** | 无（独立任务） |

**具体任务**:
- [ ] 在 `CharacterListAdapter` 中添加 `DiffCallback` 内部类
- [ ] 添加 `replaceItems()` 方法使用 DiffUtil 更新列表
- [ ] `loadCharacters()` 中使用 `replaceItems()` 替代直接 `notifyDataSetChanged()`
- [ ] 注意 `areItemsTheSame` 使用 `CharacterData.id`

**验收标准**:
- 角色列表切换/删除时无明显闪烁
- 编译通过

---

## T4: 构建 + 验证 [P0]

| 属性 | 值 |
|------|------|
| **状态** | 待开始 |
| **前置依赖** | T1 + T2 + T3 全部完成 |

**具体任务**:
- [ ] `./gradlew assembleDebug` 构建 APK
- [ ] 确认无编译错误
- [ ] 推送代码到 GitHub

**验收标准**:
- APK 构建成功
- 代码已推送到 GitHub

---

## 变更记录

| 日期 | 版本 | 变更内容 |
|------|------|------|
| 2026-06-13 | V1.0 | 初始版本，包含 4 个任务 |
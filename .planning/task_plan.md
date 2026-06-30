# P2 问题修复计划

> 创建: 2026-06-30 | 目标: 修复全部 22 个 P2 问题

---

## 分组A — Kotlin 简单修复（8个）

| ID | 文件 | 问题 | 方案 |
|----|------|------|------|
| ISS-067 | MainActivity.kt | 硬编码中文字符串 → Toast | 迁移到 R.string |
| ISS-072 | 多处Kotlin | Log 含文件路径 | 路径脱敏或改用 TAG |
| ISS-115 | ModuleRegistry.kt | clear() 无运行时保护 | 加 isEnabled 检查 |
| ISS-120 | CharacterData.kt | CharacterStorage 同文件 | 拆分到独立文件 |
| ISS-122 | PluginItem.kt | activityLevel 未使用 | 移除或 @Suppress |
| ISS-123 | LicenseActivity.kt | dip() 重复定义 | 复用父类/工具方法 |
| ISS-125 | RecordingOverlayView.kt | 硬编码 "0:00" | 迁移到 R.string |
| ISS-126 | CrashHandler.kt | defaultHandler 可见性过宽 | 改为 private |

## 分组B — Kotlin 安全/线程修复（9个）

| ID | 文件 | 问题 | 方案 |
|----|------|------|------|
| ISS-065 | SettingsDetailActivity | 非加密 SP | 改用 SecureStorage |
| ISS-070 | Settings Activity | API Key 明文 | injectApiKey()后清空 |
| ISS-071 | DataBackupHelper | 密码哈希不足 | 增强哈希迭代+加盐 |
| ISS-073 | 多文件 | Python 调用无空检查 | 添加 null 安全调用 |
| ISS-074 | MemoryArchiveActivity | JSON 解析无逐条异常 | 每条 try-catch |
| ISS-075 | WorldBookModule | UUID 截断碰撞 | 改用完整 UUID |
| ISS-076 | network_security_config | 未声明其他域名 | 补全 API 域名 |
| ISS-116 | BlurUtils | 无线程安全 | 添加 @Synchronized |
| ISS-117 | SessionDrawerAdapter | 长按无确认 | 添加确认对话框 |
| ISS-118 | WorldBookAdapter | 滑动冲突 | 调整手势检测 |
| ISS-119 | MessageItemAnimator | dispatchAddFinished 过早 | 延迟调用 |
| ISS-121 | AICompanionApp | IO 线程访问 Resources | 切换到主线程 |
| ISS-124 | BlurUtils | RenderScript 废弃 | 改用 RenderEffect |

## 分组C — Python 硬编码中文修复（8个）

| ID | 文件 | 问题 | 方案 |
|----|------|------|------|
| ISS-127 | weather_plugin.py | 天气描述硬编码 | 添加 TODO-i18n 标记 |
| ISS-128 | joke_plugin.py | 笑话内容硬编码 | 添加 TODO-i18n 标记 |
| ISS-129 | jailbreak_plugin.py | 提示词硬编码 | 添加 TODO-i18n 标记 |
| ISS-130 | reminder_plugin.py | 提醒消息硬编码 | 添加 TODO-i18n 标记 |
| ISS-131 | analyzer.py | 情感标签硬编码 | 添加 TODO-i18n 标记 |
| ISS-132 | 测试文件 | 硬编码中文 | 加 TODO-i18n 标记 |
| ISS-133 | topic_generator.py | 话题模板硬编码 | 添加 TODO-i18n 标记 |
| ISS-134 | bottom_sheet_plugins.xml | 图标与主题不一致 | 改用主题图标 |

---

## 执行顺序

1. 分组A + 分组C 可并行（无文件冲突）
2. 分组B 需等分组A完成（可能有间接文件依赖）
3. 编译验证
4. 更新 ISSUES.md + STATUS.md
5. Git 提交推送
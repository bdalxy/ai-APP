# 纯移动端 AI 角色扮演伴侣 -- 项目计划书

**项目代号**: AI-Companion-Mobile
**版本**: v2.0-plan
**创建日期**: 2026-05-27
**当前状态**: 规划阶段

---

## 目录

1. [项目概述](#1-项目概述)
2. [项目分阶段计划](#2-项目分阶段计划)
3. [里程碑节点](#3-里程碑节点)
4. [任务依赖关系图](#4-任务依赖关系图)
5. [风险管理计划](#5-风险管理计划)
6. [附录](#6-附录)

---

## 1. 项目概述

### 1.1 项目目标

在 Android 手机上运行一个完整的 AI 角色扮演伴侣系统。用户通过 Telegram（优先）或微信（可选）收发消息，AI 能够：

- 基于角色卡进行沉浸式角色扮演
- 记住历史对话并支持语义检索
- 主动发起消息（定时问候、话题推送）
- 发送语音消息（TTS）
- 识别语音消息（ASR）
- 联网搜索实时信息

### 1.2 现有基础

基于 `my_ai_companion` v1.0.1（Python + FastAPI + DeepSeek），已完成：

| 模块 | 完成度 | 说明 |
|------|--------|------|
| 角色卡管理 | 100% | Tavo/酒馆 JSON 格式，CRUD 完整 |
| DeepSeek API | 100% | 调用、重试、脱敏已实现 |
| Web API | 100% | FastAPI + Pydantic 验证 |
| 联网搜索 | 100% | DuckDuckGo 集成 |
| 操作日志 | 100% | 按日期分割，审计完整 |
| 记忆系统 | 60% | 关键词检索可用，向量语义检索待升级 |
| 微信适配 | 50% | 基础收发可用，稳定性待增强 |
| 语音 TTS | 0% | 未实现 |
| 语音 ASR | 0% | 未实现 |
| 主动消息 | 0% | 未实现 |
| Telegram 接入 | 0% | 未实现 |
| 移动端 UI | 0% | 未实现，仅 Web 界面 |

### 1.3 技术决策依据

基于可行性分析、架构评估、风险评估三个智能体的结论：

| 决策项 | 结论 | 理由 |
|--------|------|------|
| LLM 服务 | DeepSeek API（云端） | 手机本地 1.5B-3B 模型质量远不如 671B 云端模型 |
| 消息渠道 | Telegram（优先）+ 微信（可选） | 微信封号风险 4/5 x 5/5 = 20（致命级） |
| TTS 引擎 | Edge-TTS / ChatTTS | 手机端可运行，无需 PC 辅助 |
| 声线克隆 | PC 辅助训练，手机推理 | 手机无法完成训练，仅部署推理模型 |
| 向量数据库 | ChromaDB | 轻量、Python 原生、适合 Termux |
| 移动端 UI | Kotlin + Jetpack Compose | 原生 Android 体验最佳 |
| 后端运行时 | Termux + Python 3.x | 复用现有 Python 代码，降低迁移成本 |
| 定时任务 | WorkManager（Kotlin 侧）/ APScheduler（Python 侧） | 双保险机制 |

### 1.4 架构概览

```
+------------------+     +------------------+     +------------------+
|  Telegram Bot    |     |  微信小号(可选)   |     |  Kotlin App UI   |
|  (python-        |     |  (itchat-uos     |     |  (Jetpack        |
|   telegram-bot)  |     |   非官方协议)    |     |   Compose)       |
+--------+---------+     +--------+---------+     +--------+---------+
         |                        |                        |
         +------------------------+------------------------+
                                  |
                       +----------v----------+
                       |   消息路由 & 分发    |
                       |   (wx_adapter 扩展)  |
                       +----------+----------+
                                  |
                       +----------v----------+
                       |   FastAPI 后端服务   |
                       |   (Termux 运行)      |
                       +----------+----------+
                                  |
          +-----------+-----------+-----------+
          |           |           |           |
    +-----v----+ +---v----+ +---v----+ +----v----+
    | 角色扮演  | | 记忆   | | 语音   | | 主动    |
    | 引擎     | | 系统   | | 系统   | | 消息    |
    | (已有)   | | (升级) | | (新增) | | (新增)  |
    +----------+ +--------+ +--------+ +---------+
          |           |           |           |
          +-----------+-----------+-----------+
                      |
              +-------v-------+
              |  DeepSeek API  |
              |  (云端调用)    |
              +---------------+
```

---

## 2. 项目分阶段计划

---

### Phase 1: 基础设施搭建（预计 5 天）

**目标**: 在 Android Termux 上建立可运行的开发环境，用 Kotlin 搭建移动端 UI 骨架。

**前置条件**: 无

#### 任务清单

| ID | 任务 | 优先级 | 预估工时 | 依赖 | 负责人 |
|----|------|--------|----------|------|--------|
| P1-1 | Termux 环境配置脚本：自动安装 Python 3.x、pip 依赖、创建目录结构 | P0 | 0.5天 | 无 | @backend-dev |
| P1-2 | 项目依赖移动端适配：检查所有 requirements.txt 包在 Android/ARM 下的兼容性，替换不兼容包 | P0 | 1天 | P1-1 | @backend-dev |
| P1-3 | Kotlin Android 项目初始化：创建 Gradle 项目、配置 Jetpack Compose、WebView 组件 | P0 | 1.5天 | 无 | @frontend-dev |
| P1-4 | Kotlin 与 Python 后端通信层：实现 HttpURLConnection 封装，调用 FastAPI 接口 | P0 | 1天 | P1-3, P1-2 | @frontend-dev |
| P1-5 | 启动/停止脚本：编写 Termux 一键启动脚本（start.sh / stop.sh），含进程守护 | P1 | 0.5天 | P1-2 | @backend-dev |
| P1-6 | .env 配置移动端适配：新增移动端专有配置项（TTS 提供方、消息渠道选择、主动消息开关） | P1 | 0.5天 | P1-2 | @backend-dev |

**交付物**:
- `scripts/setup_termux.sh` -- 一键环境配置脚本
- `scripts/start.sh` / `scripts/stop.sh` -- 启动停止脚本
- `android/` 目录 -- Kotlin Android 项目骨架
- `.env.example` 更新 -- 含移动端配置项
- `requirements-android.txt` -- Android 兼容依赖清单

**验收标准**:
1. 在 Termux 中执行 `setup_termux.sh` 后能成功运行 `python api_server.py`
2. Kotlin 项目编译通过，WebView 能加载 `http://localhost:8000/chat`
3. `start.sh` 能以后台模式启动服务，`stop.sh` 能正确终止
4. 所有 Python 依赖在 ARM 架构下无报错导入

---

### Phase 2: 核心对话增强（预计 6 天）

**目标**: 增强角色扮演体验，支持多轮对话、上下文长度智能管理、情感记忆。

**前置条件**: Phase 1 完成

#### 任务清单

| ID | 任务 | 优先级 | 预估工时 | 依赖 | 负责人 |
|----|------|--------|----------|------|--------|
| P2-1 | 角色卡系统增强：支持嵌套关系、世界观设定、多角色切换时上下文保留 | P0 | 1天 | P1-2 | @backend-dev |
| P2-2 | 上下文长度智能管理：Token 计数、动态裁剪策略、重要信息保护 | P0 | 1.5天 | P2-1 | @backend-dev |
| P2-3 | 角色扮演质量优化：System Prompt 模板引擎、对话示例注入、角色一致性校验 | P0 | 1天 | P2-1 | @backend-dev |
| P2-4 | 情感追踪模块：基于对话内容提取情感标签，影响角色回复语气 | P1 | 1.5天 | P2-3 | @backend-dev |
| P2-5 | 移动端聊天界面：Kotlin Compose 实现原生聊天 UI（消息气泡、角色头像、打字动画） | P0 | 1天 | P1-4 | @frontend-dev |

**交付物**:
- `chat_engine/system_prompt.py` -- System Prompt 模板引擎
- `chat_engine/token_manager.py` -- Token 计算与上下文裁剪
- `chat_engine/emotion_tracker.py` -- 情感追踪器
- `cards/` 目录 -- 至少 3 个高质量角色卡
- `android/app/.../ChatScreen.kt` -- 手机端聊天界面

**验收标准**:
1. 连续 50 轮对话后角色人设不崩塌（人工评审）
2. Token 计数误差 < 5%
3. 情感标签能正确反映对话情感走向（测试用例句：骂角色 -> 检测到负面情感 -> 角色回复带委屈/生气语气）
4. Kotlin 聊天界面流畅滚动，消息发送延迟 < 500ms

---

### Phase 3: 消息渠道接入（预计 7 天）

**目标**: 优先接入 Telegram，微信作为可选渠道（需用户明确确认接受风险）。

**前置条件**: Phase 2 完成

**风险警示**: 微信接入使用非官方协议，封号风险极高（4/5 x 5/5 = 20）。**必须使用小号，且需用户在了解风险后明确同意。**

#### 任务清单

| ID | 任务 | 优先级 | 预估工时 | 依赖 | 负责人 |
|----|------|--------|----------|------|--------|
| P3-1 | Telegram Bot 创建与接入：使用 python-telegram-bot 库，实现文本收发、命令处理 | P0 | 1.5天 | P2-1 | @backend-dev |
| P3-2 | 消息路由模块：统一消息格式（Sender/Content/Type/Timestamp），多渠道分发抽象层 | P0 | 1天 | P3-1 | @backend-dev |
| P3-3 | Telegram 多媒体支持：图片接收与转发 AI 分析、语音消息下载与转发 | P1 | 1天 | P3-2 | @backend-dev |
| P3-4 | Telegram 内联键盘：命令菜单、角色切换按钮、快捷操作 | P1 | 0.5天 | P3-1 | @backend-dev |
| P3-5 | 微信小号接入（可选，需用户确认）：基于现有 wechat_hook.py 增强稳定性 | P2 | 2天 | P3-2 | @backend-dev |
| P3-6 | 微信风控规避：消息发送频率控制、内容随机化、异常检测与自动暂停 | P2 | 1天 | P3-5 | @backend-dev |

**交付物**:
- `wx_adapter/telegram_bot.py` -- Telegram Bot 适配器
- `wx_adapter/message_router.py` -- 统一消息路由
- `wx_adapter/wechat_hook.py` 更新 -- 增强稳定性 + 风控
- `docs/TELEGRAM_SETUP.md` -- Telegram Bot 配置指南

**验收标准**:
1. Telegram Bot 能接收文本消息，3 秒内返回 AI 角色回复
2. Telegram Bot 支持 `/character` 命令切换角色，`/search` 命令联网搜索
3. 消息路由模块能正确区分 Telegram 和微信消息源，统一处理
4. 微信消息发送间隔 >= 1.5 秒，连续异常后自动暂停 5 分钟
5. 用户在启动微信接入前看到明确的风险警告弹窗

---

### Phase 4: 记忆系统升级（预计 5 天）

**目标**: 从关键词检索升级为向量语义检索，支持长期记忆提取，多用户隔离。

**前置条件**: Phase 2 完成

#### 任务清单

| ID | 任务 | 优先级 | 预估工时 | 依赖 | 负责人 |
|----|------|--------|----------|------|--------|
| P4-1 | ChromaDB 集成：在 Termux 中配置 ChromaDB，替换/增强现有 JSON/SQLite 存储 | P0 | 1天 | P1-2 | @backend-dev |
| P4-2 | 向量嵌入管道：每条消息自动生成 embedding 并存入 ChromaDB，选择轻量 embedding 模型 | P0 | 1天 | P4-1 | @backend-dev |
| P4-3 | 语义检索接口：`search_memory(query, user_id, top_k=5)` 返回语义相似历史消息 | P0 | 1天 | P4-2 | @backend-dev |
| P4-4 | 长期记忆提取：基于 LLM 从对话中提取重要事实，存入 `long_term_memory` 表 | P1 | 1.5天 | P4-3 | @backend-dev |
| P4-5 | 多用户记忆隔离：按 user_id 分区存储，不同联系人/群组独立记忆空间 | P1 | 0.5天 | P4-3 | @backend-dev |
| P4-6 | 记忆衰减策略：根据时间衰减旧记忆权重，重要事实长期保留 | P2 | 待定 | P4-4 | @backend-dev |

**交付物**:
- `memory/vector_store.py` -- ChromaDB 向量存储封装
- `memory/embedding.py` -- 嵌入模型调用封装
- `memory/long_term.py` -- 长期记忆提取器
- `memory/storage.py` 更新 -- 多用户支持

**验收标准**:
1. 用户问"我之前说过我喜欢吃什么？"时，系统能检索到相关历史并正确回答
2. 向量检索延迟 < 200ms（本地 ChromaDB）
3. 长期记忆能提取至少 3 类事实：偏好、关系、事件
4. 两个不同 Telegram 用户对话，记忆完全隔离

---

### Phase 5: 语音系统（预计 5 天）

**目标**: 实现 TTS（文字转语音）和 ASR（语音转文字），支持角色专属声线。

**前置条件**: Phase 2 完成

#### 任务清单

| ID | 任务 | 优先级 | 预估工时 | 依赖 | 负责人 |
|----|------|--------|----------|------|--------|
| P5-1 | TTS 引擎集成：Edge-TTS 作为默认引擎，ChatTTS 作为备选，流式音频生成 | P0 | 1.5天 | P1-2 | @backend-dev |
| P5-2 | 角色声线映射：角色卡新增 voice 字段，TTS 调用时自动切换音色/语速/语调 | P0 | 0.5天 | P5-1, P2-1 | @backend-dev |
| P5-3 | ASR 语音识别：集成 faster-whisper（本地）或 SiliconFlow Whisper API（云端） | P0 | 1.5天 | P1-2 | @backend-dev |
| P5-4 | 语音消息流水线：收到语音 -> ASR 转文字 -> AI 回复 -> TTS 转语音 -> 发送 | P0 | 1天 | P5-1, P5-3, P3-2 | @backend-dev |
| P5-5 | 声线克隆推理部署（可选）：将 PC 训练的 GPT-SoVITS 模型导出为 ONNX，手机端推理 | P2 | 待定 | P5-1 | @backend-dev |

**交付物**:
- `voice/tts_engine.py` -- TTS 引擎封装
- `voice/asr_engine.py` -- ASR 引擎封装
- `voice/pipeline.py` -- 语音消息全流程
- `cards/` 角色卡新增 `voice` 字段定义
- `docs/VOICE_SETUP.md` -- 语音系统配置指南

**验收标准**:
1. 文字消息能转为语音 .ogg 文件，中文自然度可接受
2. 语音消息能在 3 秒内转为文字，中文准确率 >= 90%
3. 不同角色使用不同音色（如小雪用女声，大叔用男声）
4. 完整的"收到语音 -> 文字回复 -> 语音回复"链路延迟 < 8 秒

---

### Phase 6: 主动消息引擎（预计 4 天）

**目标**: AI 能主动发起消息，模拟真实伴侣的日常问候和话题分享。

**前置条件**: Phase 3, Phase 4 完成

#### 任务清单

| ID | 任务 | 优先级 | 预估工时 | 依赖 | 负责人 |
|----|------|--------|----------|------|--------|
| P6-1 | 主动消息策略引擎：基于时间（早/中/晚）、用户活跃度、上次对话间隔，决策是否发消息 | P0 | 1天 | P4-3 | @backend-dev |
| P6-2 | 话题生成器：基于角色设定 + 用户兴趣（从长期记忆中提取）+ 当日热门/节日，生成话题 | P0 | 1.5天 | P6-1, P4-4 | @backend-dev |
| P6-3 | 定时调度器：APScheduler 集成，支持 Cron 表达式配置发送时间窗口 | P0 | 0.5天 | P6-1 | @backend-dev |
| P6-4 | 用户反馈闭环：记录用户对主动消息的回复率、回复速度，动态调整发送频率 | P1 | 1天 | P6-3 | @backend-dev |

**交付物**:
- `proactive/engine.py` -- 主动消息策略引擎
- `proactive/topic_generator.py` -- 话题生成器
- `proactive/scheduler.py` -- 定时调度器
- 配置项新增：`PROACTIVE_ENABLED`、`PROACTIVE_TIME_WINDOWS`

**验收标准**:
1. 用户超过 4 小时未对话，AI 主动发送一条问候消息
2. 主动消息内容与角色人设一致（如小雪不会讨论编程话题）
3. 用户回复率 < 30% 时自动降低发送频率（如从 1 次/4h 降到 1 次/8h）
4. 凌晨 0:00-6:00 不发送任何主动消息

---

### Phase 7: 优化与发布（预计 5 天）

**目标**: 性能优化、稳定性增强、打包发布。

**前置条件**: Phase 1-6 全部完成

#### 任务清单

| ID | 任务 | 优先级 | 预估工时 | 依赖 | 负责人 |
|----|------|--------|----------|------|--------|
| P7-1 | 性能优化：API 响应延迟优化、并发消息队列、内存占用监控 | P0 | 1天 | 全部 | @backend-dev |
| P7-2 | 移动端 UI 打磨：Material 3 主题、深色模式、加载动画、错误状态处理 | P0 | 1天 | P2-5 | @frontend-dev |
| P7-3 | 异常恢复机制：网络断开重连、进程崩溃自动重启、数据一致性检查 | P0 | 1天 | P1-5 | @backend-dev |
| P7-4 | 端到端测试：编写完整对话流程的集成测试用例 | P0 | 1天 | 全部 | @qa-tester |
| P7-5 | APK 打包与签名：Gradle 配置 release build、代码混淆、签名 | P1 | 0.5天 | P7-2 | @frontend-dev |
| P7-6 | 用户文档：安装指南、功能说明、常见问题 | P1 | 0.5天 | 全部 | @backend-dev |

**交付物**:
- `android/app/release/app-release.apk` -- 签名的 APK 安装包
- `tests/integration/` -- 集成测试用例
- `docs/USER_GUIDE.md` -- 用户指南
- `docs/FAQ.md` -- 常见问题

**验收标准**:
1. API 响应 P95 延迟 < 3 秒
2. 连续运行 24 小时无崩溃
3. 网络断开后恢复，消息队列不丢数据
4. 集成测试覆盖：注册 -> 选角色 -> 对话 -> 记忆检索 -> 主动消息 全链路
5. APK 在 Android 10+ 设备上安装并正常运行

---

## 3. 里程碑节点

### M1: MVP 可用（Phase 1-3 完成）

- **预计时间**: 项目启动后 18 天
- **包含功能**:
  - Termux 环境一键部署
  - Kotlin 原生聊天界面
  - 增强的角色扮演对话
  - Telegram Bot 完整接入
  - 微信小号接入（可选）
- **验收方式**: 
  1. 在 Android 手机上完成 Termux 部署 + Kotlin App 安装
  2. 通过 Telegram 发送 10 轮角色对话，角色人设稳定
  3. 通过 Telegram 使用 `/search` 命令获取搜索增强回复
- **风险缓冲**: +3 天（总 21 天）

### M2: 完整体验（Phase 1-5 完成）

- **预计时间**: 项目启动后 28 天
- **包含功能**: M1 + 向量语义记忆 + 长期记忆 + TTS 语音 + ASR 语音识别
- **验收方式**:
  1. 问"我之前说过的XXX"，AI 正确检索历史回答
  2. 发送一条语音消息，收到文字 + 语音双重回复
  3. 不同角色使用不同音色
- **风险缓冲**: +3 天（总 31 天）

### M3: 产品发布（Phase 1-7 完成）

- **预计时间**: 项目启动后 37 天
- **包含功能**: M2 + 主动消息 + 性能优化 + APK 打包
- **验收方式**:
  1. 用户 4 小时不回复后收到 AI 主动问候
  2. APK 安装后无需 Termux 命令行操作即可使用
  3. 24 小时稳定性测试通过
- **风险缓冲**: +4 天（总 41 天）

---

## 4. 任务依赖关系图

```
Phase 1 (基础设施)
  P1-1 ──> P1-2 ──> P1-5
                      P1-6
  P1-3 ──> P1-4
           │
Phase 2 (核心对话)    │
  P1-2 ──> P2-1 ──> P2-2
           │    │──> P2-3 ──> P2-4
           │
  P1-4 ──> P2-5
           │
Phase 3 (消息渠道)    │
  P2-1 ──> P3-1 ──> P3-2 ──> P3-5 ──> P3-6
           │    │──> P3-4     (微信可选)
           │    │──> P3-3
           │
Phase 4 (记忆升级)    │
  P1-2 ──> P4-1 ──> P4-2 ──> P4-3 ──> P4-4 ──> P4-6
                      │    │──> P4-5
                      │
Phase 5 (语音系统)    │
  P1-2 ──> P5-1 ──> P5-2
  │         │
  │    P5-3 ─────────> P5-4
  │    P3-2 ─────────> P5-4
  │
  P5-1 ──> P5-5 (可选)
           │
Phase 6 (主动消息)    │
  P4-3 ──> P6-1 ──> P6-3
  │         │
  P4-4 ──> P6-2 ──> P6-3
           │         │
           │    P6-4 │
           │         │
Phase 7 (优化发布)    │
  全部Phases ──> P7-1 ──> P7-3
                P7-2 ──> P7-5
                P7-4
                P7-6
```

### 关键路径

```
P1-1 -> P1-2 -> P2-1 -> P3-1 -> P3-2 -> P5-4 -> P7-1 -> P7-3 -> P7-4 -> P7-6
```

关键路径总工时: 0.5 + 1 + 1 + 1.5 + 1 + 1 + 1 + 1 + 1 + 0.5 = **9.5 天**

---

## 5. 风险管理计划

### 5.1 风险登记表

| ID | 风险描述 | 概率 (1-5) | 影响 (1-5) | 风险值 | 等级 | 应对策略 |
|----|----------|-----------|-----------|--------|------|----------|
| R1 | 微信封号 | 4 | 5 | **20** | 致命 | **规避**: 默认使用 Telegram；微信作为可选功能，需用户签署风险确认 |
| R2 | 手机性能不足（ChromaDB + TTS 同时运行导致卡顿） | 4 | 4 | **16** | 极高 | **缓解**: ChromaDB 限制最大索引量；TTS 使用轻量模型；提供性能模式开关 |
| R3 | Termux 依赖兼容性问题（ARM 架构下 pip 包不可用） | 3 | 4 | **12** | 高 | **缓解**: P1-2 提前验证所有依赖；准备备选包列表；必要时使用纯 Python 实现 |
| R4 | DeepSeek API 不稳定（限流、超时） | 3 | 4 | **12** | 高 | **缓解**: 已有重试机制；增加本地缓存回复；降级策略（离线模式返回预设回复） |
| R5 | ChromaDB 在 Termux 中无法正常编译（需 C++ 扩展） | 3 | 3 | **9** | 中 | **备选**: 使用纯 Python 的 Numpy + FAISS 替代方案；回退到 SQLite 关键词检索 |
| R6 | Whisper 模型太大（手机存储不足） | 3 | 3 | **9** | 中 | **备选**: 使用云端 ASR API（SiliconFlow Whisper）；faster-whisper tiny 模型仅 75MB |
| R7 | Telegram Bot API 被墙（国内网络环境） | 3 | 4 | **12** | 高 | **缓解**: 文档说明需要代理；提供代理配置项；微信作为备选渠道 |
| R8 | 用户期望与 AI 能力不匹配（觉得不够"聪明"） | 4 | 2 | **8** | 中 | **缓解**: 明确文档说明能力边界；提供角色卡质量指南；推荐使用 DeepSeek-V3 模型 |

### 5.2 风险应对时间表

| 阶段 | 需关注风险 | 检查动作 |
|------|-----------|----------|
| Phase 1 | R3, R5 | P1-2 完成后，在真实 Termux 环境验证所有依赖 |
| Phase 3 | R1, R7 | P3-1 完成后，在国内网络环境测试 Telegram Bot |
| Phase 4 | R5 | P4-1 前验证 ChromaDB 是否可编译；若不通过立即切换备选方案 |
| Phase 5 | R2, R6 | P5-1 和 P5-3 完成后，同时运行 TTS + ASR + ChromaDB 做压力测试 |
| Phase 7 | R8 | P7-6 编写用户文档时，明确标注功能边界和已知限制 |

### 5.3 降级策略（Plan B）

如果关键风险同时发生（如 Telegram 不可用 + ChromaDB 无法编译 + 手机性能不足），降级为：

```
最小可用版本 (MVP-Lite):
  - 消息渠道: 微信小号（接受封号风险）
  - 记忆系统: SQLite 关键词检索（已有）
  - 语音: 全部使用云端 API（SiliconFlow TTS + ASR）
  - 主动消息: 简单定时触发器
  - UI: WebView 加载现有 Web 聊天界面
  - 预计节省: 减少 Phase 4、Phase 5 一半工时（约 5 天）
```

---

## 6. 附录

### 6.1 角色卡格式（v2.0 扩展）

```json
{
  "name": "角色名称",
  "personality": "性格特征",
  "background": "背景故事",
  "dialogue_style": "对话风格",
  "description": "角色描述",
  "scenario": "场景设定",
  "example_dialogues": [
    {"user": "你好", "bot": "你好呀！"}
  ],
  "voice": {
    "provider": "edge-tts",
    "voice_id": "zh-CN-XiaoxiaoNeural",
    "speed": 1.0,
    "pitch": 0.0
  },
  "world_setting": "世界观描述（可选）",
  "relationships": {
    "user": "与用户的关系描述"
  },
  "topics_of_interest": ["话题1", "话题2"],
  "proactive_style": "主动消息风格描述"
}
```

### 6.2 配置文件（.env 新增项）

```env
# ========== v2.0 移动端新增 ==========

# 消息渠道选择: telegram | wechat | both
MESSAGE_CHANNEL=telegram

# Telegram Bot 配置
TELEGRAM_BOT_TOKEN=your_bot_token_here
TELEGRAM_PROXY_URL=http://127.0.0.1:7890

# 微信配置（可选，高风险）
WECHAT_ENABLED=false

# 语音配置
TTS_PROVIDER=edge-tts
TTS_LANGUAGE=zh-CN
ASR_PROVIDER=faster-whisper
ASR_MODEL_SIZE=tiny

# 主动消息配置
PROACTIVE_ENABLED=true
PROACTIVE_MIN_INTERVAL_HOURS=4
PROACTIVE_TIME_WINDOWS=08:00-12:00,14:00-22:00

# ChromaDB 配置
CHROMA_PERSIST_DIR=./data/chromadb
EMBEDDING_MODEL=all-MiniLM-L6-v2
MAX_VECTOR_ENTRIES=10000

# 性能配置
PERFORMANCE_MODE=balanced
MAX_CONCURRENT_REQUESTS=3
```

### 6.3 团队与职责

| 角色 | 代号 | 职责范围 |
|------|------|----------|
| 项目经理 | @pm | 需求分析、任务分配、进度跟踪 |
| 架构师 | @architect | 系统设计、技术选型、代码审查 |
| 后端开发 | @backend-dev | Python/FastAPI 后端、AI 集成、数据存储 |
| 前端开发 | @frontend-dev | Kotlin/Compose 移动端 UI |
| 测试工程师 | @qa-tester | 测试用例编写、自动化测试、质量把关 |
| 运维工程师 | @mcp-devops | Termux 部署、打包发布、环境配置 |

### 6.4 变更记录

| 日期 | 版本 | 变更内容 | 变更人 |
|------|------|----------|--------|
| 2026-05-27 | v2.0-plan | 初始版本，基于三个智能体分析结论创建 | @pm |
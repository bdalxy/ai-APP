# AI 伴侣 APP 参考项目深度研究报告

> **分析日期**：2026-06-20  
> **目标**：聚焦与 Android AI 伴侣 APP 相关的开源项目，排除桌宠展示层，专注后端架构、记忆、成长、情绪等核心能力

---

## 目录

1. [记忆系统](#一记忆系统)
2. [AI 成长与技能进化](#二ai-成长与技能进化)
3. [用户建模与个性化](#三用户建模与个性化)
4. [语音交互管道](#四语音交互管道)
5. [情绪系统](#五情绪系统)
6. [个人 AI 架构参考](#六个人-ai-架构参考)
7. [与你的项目对比与行动建议](#七与你的项目对比与行动建议)

---

## 一、记忆系统

### 1.1 Hermes Agent — 三层记忆架构

| 层级 | 存储 | 检索 | 特点 |
|------|------|------|------|
| L1 文件记忆 | `MEMORY.md` + `USER.md` | SQLite FTS5 全文搜索 | 人可读可编辑，Git 可管理 |
| L2 用户建模 | Honcho 集成 | 辩证查询 | 跨会话用户画像 |
| L3 外部插件 | 8 个 Provider | 向量/图谱/全息 | 按需扩展 |

**信任评分机制**：记忆确认次数 ↑ → 权重 ↑，记忆被推翻 → 权重 ↓，实现自我纠错。

### 1.2 Hindsight — 四种记忆类型（91.4% LongMemEval）

| 记忆类型 | 存储内容 | 示例 |
|----------|----------|------|
| **World（事实）** | 客观事实 | "Alice 在 Google 做软件工程师" |
| **Experiences（经历）** | 智能体自身行为 | "我推荐了 Python 给 Bob" |
| **Opinions（观点）** | 带置信度的信念 | "我不该再碰炉子"（置信度 0.99） |
| **Observations（观察）** | 反思推导的模型 | "卷发棒、烤箱、火也是热的，也不该碰" |

**三种核心操作**：Retain（存储）→ Recall（TEMPR 检索）→ Reflect（反思）

**实体解析** + **双时间戳**（occurrence_time + mention_time）

### 1.3 GBrain — Markdown 记忆库

| 特点 | 实现 |
|------|------|
| 存储格式 | 纯 Markdown 文件，Git 版本控制 |
| 检索 | Postgres + pgvector（HNSW）+ tsvector（关键词），RRF 融合 |
| 本地模式 | PGLite（WASM Postgres），零配置 |

### 1.4 Mem0 生态对比

| 系统 | 定位 | 适用场景 |
|------|------|----------|
| **Mem0** | 最流行记忆层 | 55K Stars，但图谱记忆需付费 |
| **Letta** | 虚拟状态机 | 长期运行智能体，状态持久化 |
| **Supermemory** | AI 第二大脑 | 浏览器优先，书签/笔记记忆 |
| **Cognee** | 图谱原生 | 复杂推理，实体关系建模 |
| **Zep** | 企业级 | 会话历史 + 用户事实提取 |

---

## 二、AI 成长与技能进化

### 2.1 Hermes Agent — 技能自进化循环

```
用户完成任务 → 自动提炼为 SKILL.md → 下次同类任务自动加载 → 使用中自我改进
                                                                    ↓
                                                            Curator 管理生命周期
                                                            过期归档，可恢复
```

- 兼容 `agentskills.io` 开放标准
- Curator 后台进程：追踪使用次数、自动标记 stale → archive
- Pinned 技能永不归档

### 2.2 技能格式标准

```yaml
---
name: skill-name
description: "≤60字符，一句话，句号结束"
version: 1.0.0
platforms: [linux, macos, windows]
metadata:
  hermes:
    tags: [tag1, tag2]
    category: category-name
---

# Skill Title
## When to Use
## Prerequisites
## How to Run
## Procedure
## Pitfalls
## Verification
```

---

## 三、用户建模与个性化

### 3.1 Honcho — 辩证用户建模

跨会话构建用户画像，辩证查询「我偏好什么？」，用户沟通风格、工作模式建模。

**对你项目的借鉴**：用户画像可存储为结构化 JSON：
```json
{
  "preferences": {
    "reply_style": "简洁",
    "humor_level": "中等",
    "formality": "随和"
  },
  "patterns": {
    "active_hours": ["20:00-23:00"],
    "common_topics": ["编程", "游戏", "日常生活"]
  },
  "facts": {
    "name": "xxx",
    "occupation": "xxx",
    "interests": ["xxx"]
  }
}
```

### 3.2 OpenHuman — Memory Tree

三层树状记忆：Source Tree（来源树）→ Topic Tree（主题树）→ Global Tree（全局树），数据管道包含 source adapters → canonicalize → chunker → content_store → score → trees。

---

## 四、语音交互管道

### 4.1 Open-LLM-VTuber — 离线语音管道

完整管道：Microphone → VAD → ASR → LLM → Emotion Parse → TTS → Speaker + Live2D Avatar

可选后端：

| 模块 | 本地方案 | 云端方案 |
|------|----------|----------|
| ASR | sherpa-onnx, FunASR, Faster-Whisper, Whisper.cpp | Groq Whisper, Azure |
| LLM | Ollama, LM Studio, vLLM, GGUF | OpenAI, Claude, Gemini, DeepSeek |
| TTS | sherpa-onnx, MeloTTS, GPTSoVITS, CosyVoice, Bark | Edge TTS, Fish Audio, Azure |

Android 端已有 sherpa-onnx Android 版本，可直接用于 ASR + TTS。

---

## 五、情绪系统

### 5.1 AI Virtual Pet — 情绪分数追踪

- 初始心情分数：81（0-100）
- AI 回复中嵌入情绪标记：`(+5)` 或 `(-3)`
- 互动影响：抚摸 +5，喂食过多 -3

**对你项目的借鉴**：
```json
{
  "mood": 75,        // 心情 0-100
  "affection": 60,   // 好感度 0-100
  "energy": 80,      // 活力 0-100
  "trust": 50        // 信任度 0-100
}
```

### 5.2 Petzy — 双区域行为 + 动态属性

Fun Zone（娱乐）+ Intellectual Zone（学习），动态属性：affection, vitality, thirst, happiness，18 种语言。

---

## 六、个人 AI 架构参考

### 6.1 OpenHuman — 桌面级个人 AI

| 组件 | 技术 | 对你项目的启发 |
|------|------|--------------|
| 记忆 | Memory Tree 三层 | 分层摘要，Android 可用 Room + 文件 |
| 集成 | 118+ 外部服务 | 你的 APP 可考虑日历/提醒集成 |
| 吉祥物 | 3D 桌面 Mascot | 对应你的角色卡系统 |
| 模型路由 | 按任务选模型 | 复杂任务用 DeepSeek，简单任务可本地小模型 |

---

## 七、与你的项目对比与行动建议

### 7.1 能力差距分析

| 能力 | 你当前状态 | 参考项目 | 差距 |
|------|----------|----------|------|
| **记忆存储** | BM25 + 向量 + 摘要 | Hindsight 四种记忆 | 缺少记忆类型分层 |
| **记忆纠错** | 无 | Hermes 信任评分 | 缺少自我纠错 |
| **用户画像** | 基础记忆 | Honcho 辩证建模 | 缺少结构化画像 |
| **技能学习** | 无 | Hermes 技能系统 | 核心缺失 |
| **情绪系统** | 无 | AI Virtual Pet 情绪分数 | 核心缺失 |
| **语音交互** | 计划中 | Open-LLM-VTuber 管道 | 待开发 |
| **实体解析** | 无 | Hindsight 实体解析 | 可增强 |

### 7.2 建议优先级（按投入产出比）

| 优先级 | 功能 | 参考项目 | 难度 | 价值 |
|--------|------|----------|------|------|
| **P0** | 情绪系统 | AI Virtual Pet | 低 | 高 — 让 AI 伴侣有"情感" |
| **P0** | 用户画像 | Honcho | 中 | 高 — 让 AI 真正"懂你" |
| **P1** | 信任评分 | Hermes Agent | 低 | 中 — 记忆自我纠错 |
| **P1** | 记忆类型分层 | Hindsight | 中 | 中 — 更精细的记忆 |
| **P2** | 技能学习 | Hermes Agent | 高 | 高 — 但实现复杂 |
| **P2** | 语音管道 | Open-LLM-VTuber | 中 | 中 — 依赖 sherpa-onnx |
| **P3** | 实体解析 | Hindsight | 中 | 低 — 当前可暂用简单匹配 |

### 7.3 快速实现参考

**情绪系统（P0）**：
```python
# System Prompt 要求
"你的回复末尾必须包含情绪标记，格式为 [mood:±N]，
N 为 -10 到 +10 的整数，表示用户消息对你情绪的影响。"

# 解析示例
import re
match = re.search(r'\[mood:([+-]?\d+)\]', response)
if match:
    delta = int(match.group(1))
    mood = max(0, min(100, mood + delta))
```

**用户画像（P0）**：
```python
# 每次对话结束后，LLM 提炼用户画像更新
profile_update_prompt = """
从以下对话中提取用户信息，JSON 格式：
{
  "facts": {"name": "xxx", ...},
  "preferences": {"style": "xxx"},
  "patterns": {"active_time": "xxx"}
}
"""
```

**信任评分（P1）**：
```python
{
  "content": "用户喜欢简洁回复",
  "confidence": 0.8,
  "confirmed_count": 3,
  "contradicted": false,
  "last_updated": "2026-06-20"
}
```

---

> **参考项目仓库**：Hermes Agent 已克隆至 `f:\Trae AI\ai-APP\Hermes Agent\hermes-agent-repo\`
> 其余项目为线上调研，需要深入了解可进一步克隆分析。
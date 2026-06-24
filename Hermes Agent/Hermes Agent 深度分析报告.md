# Hermes Agent 深度分析报告

> **分析日期**：2026-06-20  
> **项目地址**：https://github.com/NousResearch/hermes-agent  
> **开发团队**：Nous Research（Hermes/Nomos/Psyche 模型家族）  
> **许可证**：MIT  
> **版本**：v0.16.0  
> **GitHub Stars**：95,000+  
> **语言**：Python 82.6% + TypeScript 13.5%

---

## 一、项目定位

Hermes Agent 是一个**自我进化的开源 AI 智能体框架**，核心卖点是内置"学习循环"——它会从每次对话中学习，自动创建可复用技能，构建长期记忆，越用越聪明。

它**不是**：
- IDE 绑定的编程助手（如 Copilot）
- 单一模型的聊天包装器
- 无状态的问答机器
- 云端托管服务

它是**一个持久化的、运行在你自己的基础设施上的自我改进智能体**。

---

## 二、核心架构

### 2.1 三层架构

```
┌─────────────────────────────────────┐
│          Agent Core Layer            │
│  核心决策引擎，管理推理循环           │
│  run_agent.py (~12k LOC)            │
├─────────────────────────────────────┤
│        Tool Interface Layer          │
│  70+ 工具，28 个工具集               │
│  tools/ + model_tools.py            │
├─────────────────────────────────────┤
│       Memory Management Layer        │
│  三层记忆：短期 + 长期 + 外部插件     │
│  memory_manager.py + 8 个 Provider  │
└─────────────────────────────────────┘
```

### 2.2 三层记忆系统（最核心创新）

| 层级 | 实现 | 功能 |
|------|------|------|
| **第1层：文件记忆** | `MEMORY.md` + `USER.md` | 纯 Markdown 文件，人可读可编辑，SQLite FTS5 全文搜索 |
| **第2层：用户建模** | Honcho 集成 | AI 原生用户建模，辩证查询，"我偏好什么？"跨会话回忆 |
| **第3层：外部插件** | 8 个可选 Provider | Neo4j 知识图谱、Pinecone/Weaviate/Chroma 向量存储、HRR 全息记忆 |

**信任评分机制**：记忆在多次确认后权重增加，被新信息推翻后权重降低，实现自我纠错。

### 2.3 技能系统（学习循环的核心）

```
用户完成任务 → 自动提炼为 Skill → 写入 SKILL.md
                                    ↓
                              下次同类任务自动加载
                                    ↓
                              使用中自我改进
```

- 兼容 `agentskills.io` 开放标准
- Curator 后台进程自动管理技能生命周期（过期归档、恢复）
- 内置 `skills/` 目录 + `optional-skills/` 可选技能

### 2.4 插件系统

```
plugins/
├── memory/          # 记忆后端（honcho, mem0, supermemory, hindsight 等）
├── model-providers/ # 推理后端（openrouter, anthropic, deepseek 等）
├── context_engine/  # 上下文引擎
├── image_gen/       # 图片生成
├── cron/            # 定时任务
├── google_meet/     # Google Meet 集成
├── spotify/         # Spotify 集成
└── ...
```

**插件不修改核心文件** — 所有能力通过 ABC 抽象基类 + 钩子机制扩展。

---

## 三、技术栈

| 组件 | 技术选型 |
|------|----------|
| 核心语言 | Python 3.11+ |
| 桌面应用 | Electron + React + TypeScript + Vite |
| TUI | Ink (React 终端 UI) + Node.js JSON-RPC |
| 数据存储 | SQLite (FTS5 全文搜索) + 本地文件系统 |
| 多平台 | CLI / TUI / Electron Desktop / Dashboard |
| 消息平台 | Telegram, Discord, Slack, WhatsApp, Signal, WeChat, 飞书, 钉钉, 元宝等 20+ |
| 模型支持 | OpenRouter(200+), Nous Portal, OpenAI, Anthropic, DeepSeek, Kimi, MiniMax, NVIDIA NIM 等 |
| 终端后端 | 本地 / Docker / SSH / Daytona / Modal / Singularity |
| 部署 | 一键安装脚本，支持 Linux/macOS/WSL2/Windows 原生/Termux |

---

## 四、关键特性

### 4.1 多平台消息网关
单个 `hermes gateway` 命令启动后，同一智能体可同时服务 Telegram、Discord、Slack、WhatsApp、Signal、微信、飞书等 20+ 平台，跨平台会话连续。

### 4.2 子智能体委托
`delegate_task` 工具支持并行派生隔离子智能体，支持 orchestrator 角色（智能体可再派生子智能体），深度上限可配置。

### 4.3 定时任务（Cron）
内置 cron 调度器，自然语言描述定时任务，支持模型切换、多平台投递、脚本预处理、任务链（前一个任务输出注入后续任务）。

### 4.4 Kanban 多智能体协作
SQLite 持久化工作板，多 Profile/Worker 协作处理共享任务，支持原子认领、自动阻塞失败任务、Web Dashboard。

### 4.5 MCP 协议
原生支持 MCP 客户端，可连接任何 MCP Server 扩展工具能力，内置 MCP 目录。

### 4.6 研究用途
批量轨迹生成、轨迹压缩（用于训练下一代工具调用模型），支持 Modal 无服务器部署。

### 4.7 桌面应用
Electron 桌面应用，支持 `@assistant-ui/react` 渲染，与 TUI 和 CLI 共享同一后端。

### 4.8 ACP 协议
VS Code / Zed / JetBrains IDE 集成，通过 ACP 适配器实现。

---

## 五、与你的项目对比

| 维度 | Hermes Agent | 你的 AI 伴侣 (ai-APP) |
|------|-------------|----------------------|
| **平台** | 桌面 + 多消息平台 | Android 原生 |
| **语言** | Python + TypeScript | Kotlin + Chaquopy Python |
| **模型** | 多模型切换 | DeepSeek API |
| **记忆** | 三层记忆（文件+用户建模+向量库） | BM25 + 向量存储 + 摘要 |
| **技能** | 自动创建 + 自我改进 + 开放标准 | 暂无 |
| **多端** | 20+ 消息平台 | 单一 Android 应用 |
| **部署** | 自托管服务器 | 本地设备 |
| **UI** | CLI/TUI/Desktop/Dashboard | Android XML Material Design 3 |

### 可借鉴的设计思路

1. **技能自进化**：从对话中自动提炼技能，下次自动复用 → 你的 AI 伴侣可以实现类似"从对话中学习用户偏好"的机制
2. **Honcho 用户建模**：跨会话构建用户画像 → 比简单的 BM25 记忆更深入
3. **三层记忆架构**：文件记忆（人可读）+ 向量检索 + 图谱推理 → 你已有一部分基础
4. **MCP 协议**：接入 MCP 可扩展工具能力 → 你的项目可以接入 MCP 工具生态
5. **Curator 技能生命周期管理**：自动归档过期技能，保持技能库精简
6. **信任评分**：记忆权重机制，让记忆自我纠错
7. **插件系统**：ABC 抽象基类 + 钩子 + 自动发现 → 扩展性设计参考

---

## 六、代码规模

| 指标 | 数值 |
|------|------|
| 总提交 | 11,304+ |
| 核心文件 | 500+ |
| 测试文件 | ~900 个 |
| 测试用例 | ~17,000 个 |
| run_agent.py | ~12,000 行 |
| cli.py | ~11,000 行 |
| 支持平台 | 20+ 消息平台 |
| 内置工具 | 70+ |
| 工具集 | 28 个 |
| 记忆 Provider | 8 个 |
| 语言支持 | 16 种语言 |

---

## 七、总结

Hermes Agent 是目前最活跃的开源 AI 智能体框架之一，95,000+ GitHub Stars 和 568B 日 token 处理量证明了其社区影响力。其核心价值在于：

1. **真正的学习能力**：不是简单的向量存储，而是自动技能创建 + 自我改进
2. **三层记忆架构**：文件记忆 + 用户建模 + 外部插件，分层明确
3. **极致的可扩展性**：插件系统、MCP 协议、多模型支持、多平台网关
4. **开发者友好**：MIT 开源、一键安装、自托管、数据私有

对于你的 AI 伴侣项目，Hermes Agent 在**技能系统**、**用户建模**和**记忆架构**方面的设计思路最值得参考。

---

> **仓库已克隆至**：`f:\Trae AI\ai-APP\Hermes Agent\hermes-agent-repo\`
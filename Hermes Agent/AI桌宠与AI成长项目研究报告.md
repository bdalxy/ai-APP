# AI 桌宠与 AI 成长项目研究报告

> **分析日期**：2026-06-20  
> **目的**：调研 AI 桌面宠物、AI 伴侣、AI 成长/记忆系统相关开源项目，为 AI 伴侣 (ai-APP) 项目提供参考

---

## 目录

1. [AI 桌面宠物类](#一ai-桌面宠物类)
2. [AI 伴侣/虚拟人](#二ai-伴侣虚拟人)
3. [AI 记忆系统](#三ai-记忆系统)
4. [AI 个人助手类](#四ai-个人助手类)
5. [与你的项目对比总结](#五与你的项目对比总结)

---

## 一、AI 桌面宠物类

### 1.1 CodeWalkers

| 属性 | 内容 |
|------|------|
| **GitHub** | https://github.com/you-want/CodeWalkers |
| **技术栈** | Tauri v2 + React + Rust + TypeScript + TailwindCSS |
| **平台** | Windows / macOS / Linux |

**核心亮点**：
- 双角色系统（Ethan/Luna），Dock/任务栏上方自由漫步
- Canvas Alpha 像素检测，透明区域鼠标穿透
- AI 终端内嵌（Gemini CLI / Claude / Copilot）
- 定时提醒，极致轻量（Tauri + Rust）

### 1.2 Open-LLM-VTuber

| 属性 | 内容 |
|------|------|
| **GitHub** | https://github.com/Open-LLM-VTuber/Open-LLM-VTuber |
| **技术栈** | Python 3.10+ (FastAPI) + Electron 桌面客户端 |
| **Stars** | 10,447+ |
| **许可证** | MIT |

**核心亮点**：
- 完全离线：Ollama / LM Studio 本地推理
- 语音交互：实时 ASR + VAD（语音活动检测），支持打断
- Live2D 角色：情绪映射 → Cubism 表情
- 桌面宠物模式：透明窗口 + 全局置顶 + 鼠标穿透
- 视觉感知：摄像头、屏幕捕获、截图 → 多模态 LLM 反应

**架构流程**：
```
Microphone → VAD → ASR (FunASR/Whisper) → LLM → Emotion Parse → TTS → Speaker + Live2D
```

### 1.3 Claude Status Pet

| 属性 | 内容 |
|------|------|
| **GitHub** | https://github.com/moeyui1/claude-status-pet |
| **技术栈** | Rust + Tauri |
| **大小** | ~5MB, ~20MB RAM |

**核心亮点**：实时状态显示（读取/编辑/搜索/运行/思考），10+ 角色，CSS 动画，多会话支持。

### 1.4 Vellium

| 属性 | 内容 |
|------|------|
| **GitHub** | https://github.com/tg-prplx/vellium |
| **技术栈** | Electron + React + TypeScript + Vite + SQLite |

**核心亮点**：桌面宠物 + 情绪状态 + Agents 工作区（Ask/Build/Research），安全门控，MCP + RAG。

### 1.5 lil agents（macOS）

| 属性 | 内容 |
|------|------|
| **GitHub** | https://github.com/ryanstephen/lil-agents |
| **技术栈** | Swift (macOS 原生) |
| **Stars** | 909 |

**核心亮点**：Dock 上方角色，点击弹出 AI 终端，支持 Claude Code / Codex / Copilot / Gemini CLI。

### 1.6 VPet（虚拟桌宠模拟器）

| 属性 | 内容 |
|------|------|
| **GitHub** | https://github.com/LorisYounger/VPet |
| **技术栈** | C# + WPF |

开源桌宠软件标杆，可内置到任何 WPF 应用。

### 1.7 Super Agent Party

| 属性 | 内容 |
|------|------|
| **GitHub** | https://github.com/heshengtao/super-agent-party |
| **技术栈** | 3D 桌面伴侣 |

**核心亮点**：3D 角色、多平台集成（微信/QQ/B站）、RAG、联网搜索、长期记忆、角色卡、MCP、A2A、ComfyUI。

---

## 二、AI 伴侣/虚拟人

### 2.1 OpenHuman

| 属性 | 内容 |
|------|------|
| **GitHub** | https://github.com/TinyHumansAI/OpenHuman |
| **技术栈** | Rust + React + Tauri v2 |
| **Stars** | 6,300+ |

**核心亮点**：
- Memory Tree 三层树状记忆：Source Tree → Topic Tree → Global Tree
- 118+ 外部服务集成（Gmail、Notion、GitHub、Slack 等）
- 桌面 Mascot：带"脸"的吉祥物，能说话、响应环境
- 长期记忆：跨周记住用户信息

### 2.2 Petzy

| 属性 | 内容 |
|------|------|
| **类型** | 学术研究项目 |
| **技术栈** | React JS + Three.js + Blender 3D + FastAPI + Firebase |

**核心亮点**：双区域行为（Fun Zone + Intellectual Zone），情绪识别，18 种语言，动态属性（affection/vitality/thirst/happiness），3D 角色。

### 2.3 AI Virtual Pet

| 属性 | 内容 |
|------|------|
| **GitHub** | https://github.com/ym2244/AI-virtual-pet |
| **技术栈** | Python + PyQt5 + Google Gemini Pro API |

**核心亮点**：双模式（AI 模式 + Pet 模式），情绪分数追踪（0-100），AI 回复中嵌入情绪标记 `(+5)` 或 `(-3)`。

---

## 三、AI 记忆系统

### 3.1 Hindsight

| 属性 | 内容 |
|------|------|
| **开发商** | Vectorize |
| **Stars** | 12,800+ |
| **基准** | LongMemEval 91.4%（最高记录） |

**四种记忆类型**：World（事实）、Experiences（经历）、Opinions（观点，带置信度）、Observations（观察，反思推导）

**三种操作**：Retain → Recall（TEMPR 检索）→ Reflect（反思）

**实体解析** + **双时间戳**（occurrence time + mention time）

### 3.2 GBrain

| 属性 | 内容 |
|------|------|
| **GitHub** | https://github.com/garrytan/gbrain |
| **开发者** | Garry Tan (YC CEO) |
| **Stars** | 14,000+ |

**核心亮点**：Markdown 记忆库（Git 版本控制），Postgres 检索（HNSW + tsvector），30+ MCP 操作，PGLite 零配置本地模式。

### 3.3 Mem0 及替代品生态

| 系统 | Stars | 核心特点 |
|------|-------|----------|
| **Mem0** | 55,000 | 最流行开源记忆层，图谱记忆需付费 |
| **Letta** | - | 虚拟状态机记忆，适合长期运行智能体 |
| **Supermemory** | - | AI 第二大脑，浏览器优先 |
| **Cognee** | - | 图谱原生记忆，适合复杂推理 |
| **Zep** | - | 企业级记忆，会话历史 + 用户事实 |

---

## 四、AI 个人助手类

| 项目 | 定位 | 特点 |
|------|------|------|
| **Vellum** | 最佳综合开源 | 身份驱动 + 主动式 |
| **OpenClaw** | 最佳多渠道 | 24 个消息平台，最大社区 |
| **AnythingLLM** | 最佳私有文档理解 | RAG 本地文件，完全离线 |
| **Jan.ai** | 最佳零配置本地模型 | 零设置、零订阅费 |

---

## 五、与你的项目对比总结

| 维度 | 你的 ai-APP | 竞品/参考项目 |
|------|------------|-------------|
| **平台** | Android 原生 | 大部分是桌面端（Windows/macOS/Linux） |
| **技术栈** | Kotlin + Chaquopy Python | 多为 Tauri/Electron + Python/TS |
| **角色呈现** | 暂无可视化角色 | Open-LLM-VTuber（Live2D）、CodeWalkers（像素动画）、OpenHuman（3D Mascot） |
| **语音交互** | 计划中 | Open-LLM-VTuber（完整离线语音管道） |
| **记忆系统** | BM25 + 向量存储 | Hindsight（4种记忆）、Hermes（3层）、OpenHuman（Memory Tree） |
| **情绪系统** | 暂无 | AI Virtual Pet（情绪分数）、Petzy（动态属性） |
| **技能/成长** | 暂无 | Hermes Agent（自动技能创建 + 自我改进） |
| **多端** | 单设备 | Hermes（20+ 平台）、OpenClaw（24 平台） |

### 核心建议

1. **角色可视化**：参考 Open-LLM-VTuber 的 Live2D 方案或 CodeWalkers 的像素动画，Android 端可用 Lottie 动画或 Spine 2D
2. **记忆升级**：参考 Hindsight 的四种记忆类型（事实/经历/观点/观察），从目前的 BM25 升级到更精细的记忆分层
3. **情绪系统**：参考 AI Virtual Pet 的情绪分数 + AI 回复标记模式，让 AI 伴侣有情感反馈
4. **技能成长**：参考 Hermes Agent 的技能自进化，让 AI 伴侣从对话中自动学习用户偏好
5. **语音交互**：参考 Open-LLM-VTuber 的离线语音管道（ASR → LLM → TTS），Android 可用 Vosk/Sherpa 等本地方案
6. **用户建模**：参考 Honcho 的辩证用户建模，跨会话构建用户画像

---

> **注**：以上项目除 Hermes Agent 已克隆外，其余均为线上调研，未本地克隆。如需深入了解某个项目，可以进一步克隆分析。
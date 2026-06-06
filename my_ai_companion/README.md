# WeiXinAI - 微信 AI 角色扮演聊天系统

**版本**: v1.0.1  
**状态**: 测试版本  
**安全级别**: 高

---

## 功能特性

- 🎭 **角色卡管理** - 支持 Tavo/酒馆 JSON 格式导入导出
- 💬 **AI 对话** - 基于角色卡的沉浸式角色扮演
- 🔍 **联网搜索** - 实时获取网络信息
- 📡 **RESTful API** - 提供 Web API 接口
- 🔐 **安全配置** - 环境变量管理，敏感信息脱敏
- 📝 **操作日志** - 完整的操作审计记录
- 📱 **微信接入** - 通过非官方协议接入微信（⚠️ 风险提示）
- 🧠 **记忆系统** - 三级记忆配置（基础/标准/高级）

---

## 快速开始

### 1. 安装依赖

```bash
pip install -r requirements.txt
```

### 2. 配置环境变量

复制 `.env.example` 为 `.env` 并配置：

```bash
cp .env.example .env
```

编辑 `.env` 文件：

```env
# AI 配置
AI_MODE=cloud
DEEPSEEK_API_KEY=your_api_key_here
DEEPSEEK_API_URL=https://api.deepseek.com/v1/chat/completions
MODEL_NAME=deepseek-v4

# 搜索配置
SEARCH_PROVIDER=duckduckgo

# 平台配置
PLATFORM=wechat
MEMORY_LEVEL=standard
```

### 3. 启动服务

#### 方式一：Web API 服务

```bash
python api_server.py
```

访问 API 文档: http://localhost:8000/docs

#### 方式二：微信适配器（⚠️ 使用小号！）

```bash
python wx_adapter/wechat_hook.py
```

#### 方式三：本地 Web 聊天界面

```bash
python wx_adapter/local_chat.py
```

---

## 目录结构

```
my_ai_companion/
├── api_server.py              # 🌐 RESTful API 服务
├── main.py                    # 🚀 主程序入口
├── api_client/                # 🤖 AI API 封装
│   ├── __init__.py
│   ├── base.py                # 抽象基类
│   ├── deepseek.py            # DeepSeek API 客户端
│   └── web_search.py          # 联网搜索客户端
├── chat_engine/               # 🎭 角色扮演引擎
│   ├── __init__.py
│   ├── card_parser.py         # 角色卡解析器
│   ├── context_manager.py     # 上下文管理
│   └── role_player.py         # 角色扮演执行器
├── config/                    # ⚙️ 安全配置模块
│   ├── __init__.py
│   └── settings.py            # 安全配置管理
├── memory/                    # 🧠 记忆系统
│   └── storage.py             # 记忆存储管理器
├── utils/                     # 🛠️ 工具模块
│   ├── __init__.py
│   ├── logger.py              # 操作日志管理器
│   └── device_detector.py     # 设备检测模块
├── wx_adapter/                # 📱 微信适配器
│   ├── local_chat.py          # 本地 Web 聊天界面
│   └── wechat_hook.py         # 微信消息适配器（非官方协议）
├── cards/                     # 🎴 角色卡存储
│   └── xiaoxue.json           # 示例角色卡
├── templates/                 # 📄 模板文件
│   └── chat.html              # Web 聊天界面模板
├── requirements.txt           # 📦 依赖列表
├── .env.example               # 🔒 环境变量模板
└── .gitignore                 # 🚫 Git 忽略配置
```

---

## API 接口

| 接口 | 方法 | 描述 |
|------|------|------|
| `/api/health` | GET | 健康检查 |
| `/api/characters` | GET | 获取角色卡列表 |
| `/api/character/{name}` | GET/POST/DELETE | 角色卡管理 |
| `/api/config` | POST | 配置 API |
| `/api/chat` | POST | 基础对话 |
| `/api/search` | POST | 联网搜索 |

---

## 微信命令

在微信中使用以下命令：

| 命令 | 说明 |
|------|------|
| `/help` | 显示帮助信息 |
| `/list` | 列出所有角色 |
| `/character <角色名>` | 切换角色 |
| `/clear` | 清空对话记录 |
| `/group on/off` | 开启/关闭群聊回复 |
| `/stats` | 显示统计信息 |

---

## 安全说明

### 🔐 隐私保护措施

1. **API Key 私有化** - 使用私有属性存储，防止意外泄露
2. **敏感数据脱敏** - 日志和响应中自动脱敏敏感信息
3. **环境变量管理** - 所有敏感配置通过 `.env` 文件管理
4. **Git 忽略配置** - `.env` 文件已加入 `.gitignore`
5. **输入参数验证** - 使用 Pydantic 进行参数校验
6. **CORS 安全配置** - 支持跨域请求控制

### ⚠️ 风险提示

**微信接入风险**:
- 使用非官方协议接入微信存在封号风险
- **强烈建议使用小号测试**
- 控制消息发送频率（建议 ≥ 1 秒/条）
- 避免大规模或商业用途

---

## 角色卡格式

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
  ]
}
```

---

## 记忆系统

| 级别 | 容量 | 存储方式 |
|------|------|----------|
| basic | 100 条 | JSON 文件 |
| standard | 500 条 | JSON 文件 |
| advanced | 无限制 | SQLite |

---

## 测试

```bash
# 测试角色卡管理
python -c "from chat_engine.card_parser import CardManager; m = CardManager('./cards'); print(f'✅ 加载 {len(m.list_cards())} 个角色卡')"

# 测试 API 服务
python -c "from api_server import app; print('✅ API 服务加载成功')"

# 测试记忆系统
python -c "from memory.storage import get_memory_storage; m = get_memory_storage('standard'); print('✅ 记忆系统初始化成功')"
```

---

## License

MIT License

---

**安全声明**: 本项目严格遵守隐私保护原则，所有敏感信息均通过环境变量管理，不硬编码任何密钥或凭证。

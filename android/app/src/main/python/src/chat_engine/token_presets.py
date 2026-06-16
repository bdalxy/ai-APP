"""
Token 预设配置模块。

提供三种预设模式，控制 API 调用的 Token 消耗和对话体验之间的平衡。

预设说明：
    quality  - 聊天体验优先：完整角色卡+示例对话+大上下文+高温度+v4-pro
    balanced - 平衡：中等
    economy  - 省Token优先：精简System Prompt+无示例对话+小上下文+低温度+v4-flash

使用方式:
    from src.chat_engine.token_presets import PRESETS, get_preset
    preset = get_preset("balanced")
    player = RolePlayer(client, **preset.role_player_params)
"""

from __future__ import annotations

from dataclasses import dataclass, field


@dataclass
class TokenPreset:
    """Token 预设配置。

    控制 RolePlayer、PromptBuilder 和 ContextManager 的参数，
    实现不同级别的 Token 消耗策略。

    Attributes:
        key: 预设标识符（quality/balanced/economy）。
        label: 中文显示名称。
        description: 预设描述。
        model: DeepSeek 模型名称。
        temperature: 采样温度 (0~2)。
        max_tokens: API 每次回复最大 token 数。
        context_window: 上下文窗口最大 token 数。
        system_prompt_chars: System Prompt 最大字符数。
        card_max_chars: 角色卡部分最大字符数。
        world_book_max_chars: 世界书部分最大字符数。
        memories_max_chars: 记忆部分最大字符数。
        guideline_max_chars: 对话指引部分最大字符数。
        max_example_dialogues: 最多包含的示例对话条数。
        include_guideline: 是否包含对话指引。
        include_creator_notes: 是否包含创作者备注。
    """

    key: str = "balanced"
    label: str = "平衡"
    description: str = ""

    # API 参数
    model: str = "deepseek-v4-flash"
    temperature: float = 0.7
    max_tokens: int = 1000

    # 上下文窗口
    context_window: int = 2000

    # Prompt 构建参数
    system_prompt_chars: int = 1200
    card_max_chars: int = 700
    world_book_max_chars: int = 300
    memories_max_chars: int = 200
    guideline_max_chars: int = 150

    # 内容控制
    max_example_dialogues: int = 2
    include_guideline: bool = True
    include_creator_notes: bool = False


# =============================================================================
# 预设定义
# =============================================================================

PRESETS: dict[str, TokenPreset] = {
    "quality": TokenPreset(
        key="quality",
        label="聊天体验优先",
        description="完整角色卡 + 示例对话 + 大上下文窗口 + 高创意度 + v4-pro 模型",
        model="deepseek-v4-pro",
        temperature=0.9,
        max_tokens=2000,
        context_window=4000,
        system_prompt_chars=2000,
        card_max_chars=1200,
        world_book_max_chars=400,
        memories_max_chars=300,
        guideline_max_chars=200,
        max_example_dialogues=3,
        include_guideline=True,
        include_creator_notes=False,
    ),
    "efficient": TokenPreset(
        key="efficient",
        label="高效（100%体验/80%消耗）",
        description="智能压缩System Prompt + 保持聊天体验 + v4-flash 模型。通过精简单词和去冗余实现80%Token消耗",
        model="deepseek-v4-flash",
        temperature=0.85,
        max_tokens=1500,
        context_window=3000,
        system_prompt_chars=1600,
        card_max_chars=900,
        world_book_max_chars=300,
        memories_max_chars=250,
        guideline_max_chars=150,
        max_example_dialogues=2,
        include_guideline=True,
        include_creator_notes=False,
    ),
    "balanced": TokenPreset(
        key="balanced",
        label="平衡",
        description="精简角色卡 + 1条示例 + 中等上下文 + 中等创意度 + v4-flash 模型",
        model="deepseek-v4-flash",
        temperature=0.7,
        max_tokens=1000,
        context_window=2000,
        system_prompt_chars=1200,
        card_max_chars=700,
        world_book_max_chars=300,
        memories_max_chars=200,
        guideline_max_chars=120,
        max_example_dialogues=1,
        include_guideline=True,
        include_creator_notes=False,
    ),
    "ultra": TokenPreset(
        key="ultra",
        label="极致省Token（90%体验/50%消耗）",
        description="大幅精简Prompt + 省略非关键记忆和世界书 + 小型上下文 + v4-flash 模型。约50%Token消耗",
        model="deepseek-v4-flash",
        temperature=0.65,
        max_tokens=800,
        context_window=1500,
        system_prompt_chars=1000,
        card_max_chars=600,
        world_book_max_chars=200,
        memories_max_chars=120,
        guideline_max_chars=0,
        max_example_dialogues=0,
        include_guideline=False,
        include_creator_notes=False,
    ),
    "economy": TokenPreset(
        key="economy",
        label="省Token优先",
        description="最简角色卡 + 无示例对话 + 小上下文 + 低创意度 + v4-flash 模型",
        model="deepseek-v4-flash",
        temperature=0.5,
        max_tokens=500,
        context_window=1000,
        system_prompt_chars=600,
        card_max_chars=400,
        world_book_max_chars=150,
        memories_max_chars=100,
        guideline_max_chars=80,
        max_example_dialogues=0,
        include_guideline=True,
        include_creator_notes=False,
    ),
}


def get_preset(key: str = "balanced") -> TokenPreset:
    """获取指定预设配置。

    Args:
        key: 预设键名（quality/balanced/economy），默认 balanced。

    Returns:
        TokenPreset 实例。
    """
    return PRESETS.get(key, PRESETS["balanced"])


def list_presets() -> dict[str, dict[str, str]]:
    """列出所有可用预设。

    Returns:
        {"key": {"label": str, "description": str}, ...}
    """
    return {
        k: {"label": v.label, "description": v.description}
        for k, v in PRESETS.items()
    }
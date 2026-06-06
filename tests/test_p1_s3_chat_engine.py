"""P1-S3 聊天引擎验证脚本。

测试角色卡解析、上下文管理、Prompt 构建和角色扮演流程。
"""

import sys
from pathlib import Path
# 确保项目根目录在 sys.path 中
_project_root = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(_project_root))

from src.chat_engine.card_parser import Card, CardParser, CardParseError
from src.chat_engine.context_manager import ContextManager
from src.chat_engine.prompt_builder import PromptBuilder
from src.chat_engine.role_player import RolePlayer, RolePlayerError


def test_card_parser():
    """测试角色卡解析和验证。"""
    print("=" * 60)
    print("1. 测试角色卡解析器 (CardParser)")
    print("=" * 60)

    parser = CardParser()
    card = parser.from_file("data/role_cards/小美.json")

    print(f"   角色名: {card.name}")
    print(f"   昵称: {card.nickname}")
    print(f"   性别: {card.gender}")
    print(f"   年龄: {card.age}")
    print(f"   性格: {card.personality[:30]}...")
    print(f"   背景: {card.background[:30]}...")
    print(f"   说话风格: {card.speaking_style[:30]}...")
    print(f"   喜好: {card.likes}")
    print(f"   厌恶: {card.dislikes}")
    print(f"   示例对话: {len(card.example_dialogues)} 条")
    print(f"   版本: {card.version}")
    print("   [PASS] 角色卡解析成功")

    # 测试 to_prompt_text
    prompt_text = card.to_prompt_text()
    print(f"   to_prompt_text: {len(prompt_text)} 字符")
    assert "小美" in prompt_text
    assert "性格" in prompt_text
    assert "背景" in prompt_text
    assert "示例对话" in prompt_text
    print("   [PASS] to_prompt_text 格式正确")


def test_required_fields():
    """测试必填字段验证。"""
    print("\n" + "=" * 60)
    print("2. 测试必填字段验证")
    print("=" * 60)

    parser = CardParser()

    # 缺少 personality
    try:
        parser.from_dict({"name": "test", "background": "bg", "speaking_style": "ss"}, "test")
        assert False, "应该抛出异常"
    except CardParseError as e:
        print(f"   缺少 personality: {e}")
        print("   [PASS]")

    # 缺少 background
    try:
        parser.from_dict({"name": "test", "personality": "p", "speaking_style": "ss"}, "test")
        assert False, "应该抛出异常"
    except CardParseError as e:
        print(f"   缺少 background: {e}")
        print("   [PASS]")

    # 全部必填字段都有
    card = parser.from_dict(
        {"name": "张三", "personality": "温和", "background": "普通人", "speaking_style": "正常"},
        "test"
    )
    assert card.name == "张三"
    print(f"   全部必填字段通过: {card.name}")
    print("   [PASS]")


def test_context_manager():
    """测试上下文管理器。"""
    print("\n" + "=" * 60)
    print("3. 测试上下文管理器 (ContextManager)")
    print("=" * 60)

    ctx = ContextManager(max_tokens=200)

    # 添加消息
    ctx.add_message("user", "你好呀！今天天气真好！")
    ctx.add_message("assistant", "是呀，阳光明媚呢~")
    ctx.add_message("user", "我们去公园散步吧！")

    assert ctx.count() == 3
    print(f"   添加 3 条消息: count={ctx.count()}")
    print(f"   token 估算: {ctx.get_token_estimate()}")

    # 获取上下文
    context = ctx.get_context()
    assert len(context) == 3
    assert context[0]["role"] == "user"
    assert context[2]["role"] == "user"
    print(f"   get_context: {len(context)} 条, 第一条={context[0]['content'][:10]}...")
    print("   [PASS]")

    # 清空
    ctx.clear()
    assert ctx.count() == 0
    print(f"   clear 后 count={ctx.count()}")
    print("   [PASS]")

    # 测试自动裁剪
    print("\n   测试自动裁剪...")
    ctx2 = ContextManager(max_tokens=50)
    ctx2.add_message("user", "这是一条非常长的消息用来触发裁剪机制" * 5)
    ctx2.add_message("assistant", "回复消息" * 3)
    ctx2.add_message("user", "最后一条消息")
    print(f"   裁剪后: count={ctx2.count()}, token_estimate={ctx2.get_token_estimate()}")
    assert ctx2.count() >= 1  # 至少保留最后一条
    print("   [PASS] 自动裁剪")


def test_prompt_builder():
    """测试 Prompt 组装器。"""
    print("\n" + "=" * 60)
    print("4. 测试 Prompt 组装器 (PromptBuilder)")
    print("=" * 60)

    parser = CardParser()
    card = parser.from_file("data/role_cards/小美.json")
    builder = PromptBuilder()

    # 基础 System Prompt
    sp = builder.build_system_prompt(card, None, None)
    assert "小美" in sp
    assert "对话指引" in sp
    assert len(sp) <= builder.MAX_SYSTEM_PROMPT_CHARS
    print(f"   System Prompt 基础: {len(sp)} 字符 (<={builder.MAX_SYSTEM_PROMPT_CHARS})")
    print("   [PASS]")

    # 带世界书
    sp2 = builder.build_system_prompt(
        card,
        world_book_entries=["这是一个魔法世界，天空是紫色的"],
        memories=None
    )
    assert "世界设定" in sp2
    assert "天空是紫色的" in sp2
    print(f"   System Prompt + 世界书: {len(sp2)} 字符")
    print("   [PASS]")

    # 带记忆
    sp3 = builder.build_system_prompt(
        card,
        world_book_entries=None,
        memories=["用户喜欢草莓蛋糕", "用户怕冷"]
    )
    assert "相关记忆" in sp3
    assert "草莓蛋糕" in sp3
    print(f"   System Prompt + 记忆: {len(sp3)} 字符")
    print("   [PASS]")

    # 全部
    sp4 = builder.build_system_prompt(
        card,
        world_book_entries=["魔法世界"],
        memories=["用户喜欢甜食"]
    )
    print(f"   System Prompt 完整: {len(sp4)} 字符")
    print("   [PASS]")

    # build_messages
    ctx = ContextManager(max_tokens=4000)
    ctx.add_message("user", "你好")
    ctx.add_message("assistant", "你好呀~")
    messages = builder.build_messages(sp, ctx)
    assert len(messages) == 3
    assert messages[0]["role"] == "system"
    assert messages[1]["role"] == "user"
    assert messages[2]["role"] == "assistant"
    print(f"   build_messages: {len(messages)} 条, roles={[m['role'] for m in messages]}")
    print("   [PASS]")


def test_role_player():
    """测试 RolePlayer 初始化和状态。"""
    print("\n" + "=" * 60)
    print("5. 测试角色扮演主流程 (RolePlayer)")
    print("=" * 60)

    from src.api_client.deepseek import DeepSeekClient

    client = DeepSeekClient()
    player = RolePlayer(client, max_context_tokens=4000, temperature=0.9)

    # 初始状态
    assert not player.is_ready()
    print(f"   初始状态: is_ready={player.is_ready()}")
    print("   [PASS]")

    # 加载角色卡
    card = player.load_card("data/role_cards/小美.json")
    assert player.is_ready()
    assert card.name == "小美"
    print(f"   加载角色卡: {card.name}, is_ready={player.is_ready()}")
    print("   [PASS]")

    # 获取角色卡信息
    info = player.get_card_info()
    assert info["name"] == "小美"
    assert info["nickname"] == "美美"
    print(f"   card_info: {info}")
    print("   [PASS]")

    # 注入记忆
    player.inject_memories(["用户喜欢草莓蛋糕"])
    assert len(player.memories) == 1
    print(f"   注入记忆: {len(player.memories)} 条")
    print("   [PASS]")

    # 清空记忆
    player.clear_memories()
    assert len(player.memories) == 0
    print(f"   清空记忆: {len(player.memories)} 条")
    print("   [PASS]")

    # 未加载角色卡时 chat 应抛出异常
    player2 = RolePlayer(client)
    try:
        player2.chat("你好")
        assert False, "应该抛出 RolePlayerError"
    except RolePlayerError as e:
        print(f"   未加载角色卡 chat: {e}")
        print("   [PASS]")

    # 上下文操作
    player.context.add_message("user", "测试")
    player.context.add_message("assistant", "测试回复")
    assert player.get_context_token_estimate() > 0
    print(f"   上下文 token: {player.get_context_token_estimate()}")
    player.clear_context()
    assert player.get_context_token_estimate() == 0
    print(f"   清空上下文: token={player.get_context_token_estimate()}")
    print("   [PASS]")

    # 世界书提取
    entries = RolePlayer._extract_world_book_entries([
        {"content": "魔法世界"},
        {"content": "天空是紫色"}
    ])
    assert len(entries) == 2
    print(f"   世界书提取(list): {entries}")
    print("   [PASS]")

    entries2 = RolePlayer._extract_world_book_entries({
        "entries": [{"content": "setting1"}, {"content": "setting2"}]
    })
    assert len(entries2) == 2
    print(f"   世界书提取(dict): {entries2}")
    print("   [PASS]")


def test_package_imports():
    """测试包级导入。"""
    print("\n" + "=" * 60)
    print("6. 测试包级导入")
    print("=" * 60)

    from src.chat_engine import (
        Card, CardParser, CardParseError,
        ContextManager, PromptBuilder,
        RolePlayer, RolePlayerError,
    )
    print("   from src.chat_engine import * 全部成功")
    print("   [PASS]")


def main():
    """运行所有测试。"""
    print("\n" + "=" * 60)
    print("P1-S3 聊天引擎 - 验证测试")
    print("=" * 60)

    test_card_parser()
    test_required_fields()
    test_context_manager()
    test_prompt_builder()
    test_role_player()
    test_package_imports()

    print("\n" + "=" * 60)
    print("全部验证通过! P1-S3 聊天引擎实现完成。")
    print("=" * 60)


if __name__ == "__main__":
    main()

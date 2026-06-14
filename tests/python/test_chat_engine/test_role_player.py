"""测试角色扮演器 RolePlayer。

使用 mock DeepSeekClient，不实际调用 API。
"""

import pytest
from unittest.mock import MagicMock, patch

from src.chat_engine.role_player import RolePlayer, RolePlayerError
from src.api_client.deepseek import DeepSeekClient, ChatResponse


class TestRolePlayerInit:
    """RolePlayer 初始化测试。"""

    def test_init_basic(self, mock_deepseek_client):
        """测试基本初始化。"""
        player = RolePlayer(
            client=mock_deepseek_client,
            max_context_tokens=2000,
            temperature=0.8,
            max_tokens=1000,
        )
        assert player is not None
        assert player.is_ready() is False  # 未加载角色卡
        assert player.temperature == 0.8
        assert player.max_tokens == 1000

    def test_init_with_preset_string(self, mock_deepseek_client):
        """测试使用预设字符串初始化。"""
        player = RolePlayer(
            client=mock_deepseek_client,
            preset="balanced",
        )
        assert player is not None
        assert player.temperature > 0

    def test_init_with_preset_object(self, mock_deepseek_client):
        """测试使用预设对象初始化。"""
        from src.chat_engine.token_presets import get_preset
        preset = get_preset("economy")
        player = RolePlayer(
            client=mock_deepseek_client,
            preset=preset,
        )
        assert player is not None

    def test_init_with_model(self, mock_deepseek_client):
        """测试指定模型初始化。"""
        player = RolePlayer(
            client=mock_deepseek_client,
            model="deepseek-chat",
        )
        mock_deepseek_client.set_model.assert_called_with("deepseek-chat")


class TestRolePlayerCard:
    """RolePlayer 角色卡加载测试。"""

    def test_load_card_from_file(self, mock_deepseek_client):
        """测试加载角色卡文件。"""
        import json
        import tempfile
        import os

        card_data = {
            "name": "测试角色",
            "nickname": "小测",
            "gender": "女",
            "personality": "友善",
            "background": "来自测试世界的AI助手",
            "speaking_style": "温柔",
            "backstory": "来自测试世界",
        }
        with tempfile.NamedTemporaryFile(
            mode="w", suffix=".json", delete=False, encoding="utf-8"
        ) as f:
            json.dump(card_data, f)
            tmp_path = f.name

        try:
            player = RolePlayer(client=mock_deepseek_client)
            card = player.load_card(tmp_path)
            assert card is not None
            assert card.name == "测试角色"
            assert player.is_ready() is True
        finally:
            os.unlink(tmp_path)

    def test_load_card_file_not_found(self, mock_deepseek_client):
        """测试加载不存在的文件。"""
        player = RolePlayer(client=mock_deepseek_client)
        with pytest.raises(FileNotFoundError):
            player.load_card("non_existent_file.json")

    def test_get_card_info(self, mock_deepseek_client):
        """测试获取角色卡信息。"""
        import json
        import tempfile
        import os

        card_data = {
            "name": "信息角色",
            "nickname": "信信",
            "gender": "男",
            "personality": "理性",
            "background": "擅长信息处理",
            "speaking_style": "简洁",
        }
        with tempfile.NamedTemporaryFile(
            mode="w", suffix=".json", delete=False, encoding="utf-8"
        ) as f:
            json.dump(card_data, f)
            tmp_path = f.name

        try:
            player = RolePlayer(client=mock_deepseek_client)
            player.load_card(tmp_path)
            info = player.get_card_info()
            assert info["name"] == "信息角色"
            assert info["nickname"] == "信信"
        finally:
            os.unlink(tmp_path)

    def test_get_card_info_without_card(self, mock_deepseek_client):
        """测试未加载角色卡时获取信息抛出异常。"""
        player = RolePlayer(client=mock_deepseek_client)
        with pytest.raises(RolePlayerError, match="角色卡未加载"):
            player.get_card_info()


class TestRolePlayerChat:
    """RolePlayer 对话测试。"""

    def test_chat_without_card(self, mock_deepseek_client):
        """测试未加载角色卡时对话抛出异常。"""
        player = RolePlayer(client=mock_deepseek_client)
        with pytest.raises(RolePlayerError, match="角色卡未加载"):
            player.chat("你好")

    def test_chat_basic(self, mock_deepseek_client):
        """测试基本对话（mock API）。"""
        import json
        import tempfile
        import os

        card_data = {
            "name": "对话角色",
            "nickname": "对对话",
            "gender": "女",
            "personality": "活泼",
            "background": "喜欢聊天的AI",
            "speaking_style": "亲切",
        }
        with tempfile.NamedTemporaryFile(
            mode="w", suffix=".json", delete=False, encoding="utf-8"
        ) as f:
            json.dump(card_data, f)
            tmp_path = f.name

        try:
            player = RolePlayer(client=mock_deepseek_client)
            player.load_card(tmp_path)

            reply = player.chat("你好")
            assert reply == "这是一个测试回复。"
            mock_deepseek_client.chat.assert_called_once()
        finally:
            os.unlink(tmp_path)

    def test_chat_empty_input(self, mock_deepseek_client):
        """测试空输入对话。"""
        import json
        import tempfile
        import os

        card_data = {"name": "测试", "nickname": "测", "gender": "女", "personality": "中性", "background": "测试背景", "speaking_style": "简洁"}
        with tempfile.NamedTemporaryFile(
            mode="w", suffix=".json", delete=False, encoding="utf-8"
        ) as f:
            json.dump(card_data, f)
            tmp_path = f.name

        try:
            player = RolePlayer(client=mock_deepseek_client)
            player.load_card(tmp_path)
            reply = player.chat("   ")
            assert reply == ""
        finally:
            os.unlink(tmp_path)


class TestRolePlayerContext:
    """RolePlayer 上下文管理测试。"""

    def test_clear_context(self, mock_deepseek_client):
        """测试清空上下文。"""
        player = RolePlayer(client=mock_deepseek_client)
        context_before = player.get_context()
        assert len(context_before) == 0

        player.clear_context()
        assert len(player.get_context()) == 0

    def test_get_context_token_estimate(self, mock_deepseek_client):
        """测试获取上下文 token 估算。"""
        player = RolePlayer(client=mock_deepseek_client)
        estimate = player.get_context_token_estimate()
        assert estimate >= 0


class TestRolePlayerWorldBook:
    """RolePlayer 世界书加载测试。"""

    def test_load_world_book(self, mock_deepseek_client):
        """测试加载世界书。"""
        import json
        import tempfile
        import os

        wb_data = {
            "name": "测试世界书",
            "entries": [
                {"id": "e1", "keys": ["苹果"], "content": "关于苹果的设定"},
            ],
        }
        with tempfile.NamedTemporaryFile(
            mode="w", suffix=".json", delete=False, encoding="utf-8"
        ) as f:
            json.dump(wb_data, f)
            tmp_path = f.name

        try:
            player = RolePlayer(client=mock_deepseek_client)
            entries = player.load_world_book(tmp_path)
            assert len(entries) == 1
        finally:
            os.unlink(tmp_path)

    def test_load_world_book_not_found(self, mock_deepseek_client):
        """测试加载不存在的世界书。"""
        player = RolePlayer(client=mock_deepseek_client)
        with pytest.raises(FileNotFoundError):
            player.load_world_book("non_existent_wb.json")


class TestRolePlayerMemories:
    """RolePlayer 记忆注入测试。"""

    def test_inject_memories(self, mock_deepseek_client):
        """测试注入记忆。"""
        player = RolePlayer(client=mock_deepseek_client)
        player.inject_memories(["用户喜欢草莓", "用户住在上海"])
        assert len(player.memories) == 2

    def test_clear_memories(self, mock_deepseek_client):
        """测试清空记忆。"""
        player = RolePlayer(client=mock_deepseek_client)
        player.inject_memories(["记忆1", "记忆2"])
        player.clear_memories()
        assert len(player.memories) == 0

    def test_memories_cleared_after_chat(self, mock_deepseek_client):
        """测试对话后记忆自动清空。"""
        import json
        import tempfile
        import os

        card_data = {"name": "测试", "nickname": "测", "gender": "女", "personality": "中性", "background": "测试背景", "speaking_style": "简洁"}
        with tempfile.NamedTemporaryFile(
            mode="w", suffix=".json", delete=False, encoding="utf-8"
        ) as f:
            json.dump(card_data, f)
            tmp_path = f.name

        try:
            player = RolePlayer(client=mock_deepseek_client)
            player.load_card(tmp_path)
            player.inject_memories(["本轮记忆"])
            player.chat("你好")
            # 对话后记忆应清空
            assert player.memories == []
        finally:
            os.unlink(tmp_path)
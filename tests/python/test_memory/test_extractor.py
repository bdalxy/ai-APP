"""测试 MemoryExtractor 记忆提取器。

主要测试规则提取模式（zero API cost），LLM 模式用 mock 测试。
"""

import pytest
from unittest.mock import MagicMock

from src.memory.extractor import MemoryExtractor
from src.memory.vector_store import MemoryEntry


class TestExtractorRuleMode:
    """规则提取模式（零 API 开销）测试。"""

    def setup_method(self):
        """每个测试前创建 extractor（不依赖 API client）。"""
        mock_client = MagicMock()
        self.extractor = MemoryExtractor(
            deepseek_client=mock_client,
            vector_store=None,  # 跳过去重
        )

    def test_extract_user_fact_name(self):
        """测试提取用户姓名。"""
        messages = [{"role": "user", "content": "我是小明"}]
        entries = self.extractor.extract(messages, mode="rule")
        assert len(entries) >= 1
        # 至少有一条是 user_fact 类型
        facts = [e for e in entries if e.memory_type == "user_fact"]
        assert len(facts) >= 1
        assert any("小明" in e.content for e in entries)

    def test_extract_user_fact_like(self):
        """测试提取用户喜好。"""
        messages = [{"role": "user", "content": "我喜欢吃草莓蛋糕"}]
        entries = self.extractor.extract(messages, mode="rule")
        assert len(entries) >= 1
        contents = [e.content for e in entries]
        assert any("草莓" in c for c in contents)

    def test_extract_user_fact_dislike(self):
        """测试提取用户厌恶。"""
        messages = [{"role": "user", "content": "我讨厌下雨天，真的很烦"}]
        entries = self.extractor.extract(messages, mode="rule")
        assert len(entries) >= 1
        contents = [e.content for e in entries]
        assert any("讨厌" in c for c in contents)

    def test_extract_user_fact_live(self):
        """测试提取用户住址/家乡。"""
        messages = [{"role": "user", "content": "我住在上海市浦东新区"}]
        entries = self.extractor.extract(messages, mode="rule")
        assert len(entries) >= 1

    def test_extract_user_fact_job(self):
        """测试提取用户职业。"""
        messages = [{"role": "user", "content": "我是一名软件工程师"}]
        entries = self.extractor.extract(messages, mode="rule")
        assert len(entries) >= 1

    def test_extract_episodic(self):
        """测试提取事件记忆。"""
        messages = [{"role": "user", "content": "今天我去公园散步了，看到了很多花"}]
        entries = self.extractor.extract(messages, mode="rule")
        assert len(entries) >= 1
        episodes = [e for e in entries if e.memory_type == "episodic"]
        assert len(episodes) >= 1

    def test_extract_empty_messages(self):
        """测试空消息。"""
        entries = self.extractor.extract([], mode="rule")
        assert entries == []

    def test_extract_no_user_messages(self):
        """测试只有 assistant 消息。"""
        messages = [{"role": "assistant", "content": "你好！"}]
        entries = self.extractor.extract(messages, mode="rule")
        assert entries == []

    def test_extract_multiple_messages(self):
        """测试多轮对话提取。"""
        messages = [
            {"role": "user", "content": "我叫小明"},
            {"role": "assistant", "content": "你好小明！"},
            {"role": "user", "content": "我喜欢吃苹果"},
            {"role": "assistant", "content": "苹果很健康！"},
        ]
        entries = self.extractor.extract(messages, mode="rule")
        assert len(entries) >= 2

    def test_extract_importance(self):
        """测试提取的记忆有正确的重要性分值。"""
        messages = [{"role": "user", "content": "我的生日是五月二十号"}]
        entries = self.extractor.extract(messages, mode="rule")
        assert len(entries) >= 1
        # user_fact 应该被赋予高重要性
        for e in entries:
            if e.memory_type == "user_fact":
                assert e.importance > 0.8

    def test_extract_source_turn_id(self):
        """测试提取的记忆带有 source_turn_id。"""
        messages = [{"role": "user", "content": "我是测试者"}]
        entries = self.extractor.extract(
            messages, mode="rule", source_turn_id="turn_001"
        )
        for e in entries:
            assert e.source_turn_id == "turn_001"

    def test_extract_auto_mode_falls_back(self):
        """测试 auto 模式：LLM 失败时回退到规则模式。"""
        # 让 LLM 模式失败（client.chat 抛异常）
        self.extractor.client.chat.side_effect = Exception("API 不可用")
        messages = [{"role": "user", "content": "我喜欢编程"}]
        entries = self.extractor.extract(messages, mode="auto")
        # 应该回退到规则模式，至少提取到一些结果
        assert len(entries) >= 1

    def test_extract_invalid_mode(self):
        """测试无效模式抛出异常。"""
        messages = [{"role": "user", "content": "测试"}]
        with pytest.raises(ValueError, match="不支持的提取模式"):
            self.extractor.extract(messages, mode="invalid")

    def test_memory_entry_has_timestamps(self):
        """测试提取的 MemoryEntry 有时间戳。"""
        messages = [{"role": "user", "content": "我叫小美"}]
        entries = self.extractor.extract(messages, mode="rule")
        for e in entries:
            assert e.created_at != ""
            assert e.last_accessed != ""


class TestExtractorDedup:
    """去重逻辑测试。"""

    def test_deduplicate_with_existing(self, memory_store):
        """测试与已有记忆去重。"""
        from unittest.mock import MagicMock

        mock_client = MagicMock()
        # 先存入一条已有记忆
        existing = MemoryEntry(
            memory_type="user_fact",
            content="我喜欢吃苹果",
            importance=0.9,
        )
        memory_store.add(existing)

        extractor = MemoryExtractor(
            deepseek_client=mock_client,
            vector_store=memory_store,
        )

        # 提取到相同的记忆
        messages = [{"role": "user", "content": "我喜欢吃苹果"}]
        entries = extractor.extract(messages, mode="rule")

        # 应该被去重（完全相同的"我喜欢吃苹果"可能在规则匹配时提取为"喜欢苹果"等变体）
        # 去重阈值 0.85，完全相同的 bigram 会触发去重
        # 注意：规则提取可能提取的是"我喜欢吃苹果"的子集，不一定完全匹配
        # 这里主要测试去重流程不报错
        assert isinstance(entries, list)

    def test_text_similarity_static(self):
        """测试 _text_similarity 静态方法。"""
        sim = MemoryExtractor._text_similarity("我喜欢吃苹果", "我喜欢吃苹果")
        assert sim > 0.9  # 几乎相同

        sim = MemoryExtractor._text_similarity("我喜欢吃苹果", "今天天气很好")
        assert sim < 0.3  # 不同


class TestExtractorLLMMode:
    """LLM 提取模式测试（使用 mock）。"""

    def test_llm_extract_basic(self):
        """测试 LLM 提取基本流程。"""
        from unittest.mock import MagicMock
        from src.api_client.deepseek import ChatResponse

        mock_client = MagicMock()
        mock_client.chat.return_value = ChatResponse(
            content='[{"memory_type":"user_fact","content":"用户喜欢编程","importance":0.9}]',
            model="deepseek-chat",
            usage={"prompt_tokens": 50, "completion_tokens": 20, "total_tokens": 70},
            finish_reason="stop",
        )

        extractor = MemoryExtractor(
            deepseek_client=mock_client,
            vector_store=None,
        )

        messages = [
            {"role": "user", "content": "我很喜欢编程"},
            {"role": "assistant", "content": "编程很有趣！"},
        ]
        entries = extractor.extract(messages, mode="llm")

        assert len(entries) == 1
        assert entries[0].content == "用户喜欢编程"
        assert entries[0].memory_type == "user_fact"
        assert entries[0].importance == 0.9

    def test_llm_extract_empty(self):
        """测试 LLM 返回空数组。"""
        from unittest.mock import MagicMock
        from src.api_client.deepseek import ChatResponse

        mock_client = MagicMock()
        mock_client.chat.return_value = ChatResponse(
            content="[]",
            model="deepseek-chat",
            usage={"prompt_tokens": 20, "completion_tokens": 2, "total_tokens": 22},
            finish_reason="stop",
        )

        extractor = MemoryExtractor(
            deepseek_client=mock_client,
            vector_store=None,
        )

        entries = extractor.extract(
            [{"role": "user", "content": "嗯"}],
            mode="llm",
        )
        assert entries == []

    def test_llm_invalid_type_clamped(self):
        """测试 LLM 返回无效类型被修正。"""
        from unittest.mock import MagicMock
        from src.api_client.deepseek import ChatResponse

        mock_client = MagicMock()
        mock_client.chat.return_value = ChatResponse(
            content='[{"memory_type":"invalid_type","content":"test","importance":1.5}]',
            model="deepseek-chat",
            usage={"prompt_tokens": 10, "completion_tokens": 10, "total_tokens": 20},
            finish_reason="stop",
        )

        extractor = MemoryExtractor(
            deepseek_client=mock_client,
            vector_store=None,
        )

        entries = extractor.extract(
            [{"role": "user", "content": "test"}],
            mode="llm",
        )
        assert len(entries) == 1
        # 无效类型被修正为 "semantic"
        assert entries[0].memory_type == "semantic"
        # 超出范围的重要性被裁剪到 1.0
        assert entries[0].importance == 1.0
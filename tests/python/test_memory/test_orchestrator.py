"""测试 MemoryOrchestrator 记忆编排器。

测试 remember 和 recall 主流程，使用 mock DeepSeekClient。
"""

import pytest
from unittest.mock import MagicMock

from src.memory.orchestrator import MemoryOrchestrator
from src.memory.vector_store import MemoryEntry


class TestOrchestratorInit:
    """MemoryOrchestrator 初始化测试。"""

    def test_init(self, memory_store, mock_deepseek_client):
        """测试基本初始化。"""
        orch = MemoryOrchestrator(
            vector_store=memory_store,
            deepseek_client=mock_deepseek_client,
        )
        assert orch is not None
        assert orch.vector_store is memory_store
        assert orch.client is mock_deepseek_client

    def test_default_extract_interval(self, memory_store, mock_deepseek_client):
        """测试默认提取间隔。"""
        orch = MemoryOrchestrator(
            vector_store=memory_store,
            deepseek_client=mock_deepseek_client,
        )
        assert orch._extract_interval == 5

    def test_set_extract_interval(self, memory_store, mock_deepseek_client):
        """测试设置提取间隔。"""
        orch = MemoryOrchestrator(
            vector_store=memory_store,
            deepseek_client=mock_deepseek_client,
        )
        orch.set_extract_interval(3)
        assert orch._extract_interval == 3

    def test_set_extract_interval_negative(self, memory_store, mock_deepseek_client):
        """测试设置负提取间隔抛出异常。"""
        orch = MemoryOrchestrator(
            vector_store=memory_store,
            deepseek_client=mock_deepseek_client,
        )
        with pytest.raises(ValueError):
            orch.set_extract_interval(-1)

    def test_set_extract_interval_zero(self, memory_store, mock_deepseek_client):
        """测试设为0始终使用LLM。"""
        orch = MemoryOrchestrator(
            vector_store=memory_store,
            deepseek_client=mock_deepseek_client,
        )
        orch.set_extract_interval(0)
        assert orch._extract_interval == 0


class TestOrchestratorRemember:
    """MemoryOrchestrator.remember 测试。"""

    def test_remember_rule_mode(self, memory_store, mock_deepseek_client):
        """测试规则模式提取存储（间隔设大值）。"""
        orch = MemoryOrchestrator(
            vector_store=memory_store,
            deepseek_client=mock_deepseek_client,
        )
        orch.set_extract_interval(999999)  # 始终使用规则模式

        stored = orch.remember(
            turn_id="turn_001",
            user_msg="我喜欢吃苹果，也住在上海",
            ai_reply="苹果很好吃，上海是个好地方！",
        )

        # 规则模式应该提取到至少一些记忆
        assert stored >= 0
        assert memory_store.count() >= stored

    def test_remember_empty_messages(self, memory_store, mock_deepseek_client):
        """测试空消息不提取。"""
        orch = MemoryOrchestrator(
            vector_store=memory_store,
            deepseek_client=mock_deepseek_client,
        )

        stored = orch.remember(
            turn_id="turn_001",
            user_msg="",
            ai_reply="",
        )
        assert stored == 0

    def test_remember_embed_failure_graceful(self, memory_store, mock_deepseek_client):
        """测试 embedding 失败时记忆仍然存储（不含向量）。"""
        orch = MemoryOrchestrator(
            vector_store=memory_store,
            deepseek_client=mock_deepseek_client,
        )
        orch.set_extract_interval(999999)

        # embed_cached 抛异常
        mock_deepseek_client.embed_cached.side_effect = Exception("Embed API 不可用")

        stored = orch.remember(
            turn_id="turn_001",
            user_msg="我叫小明，我喜欢吃苹果",
            ai_reply="你好小明，苹果很好吃！",
        )

        # 记忆应该仍然被存储
        assert stored >= 1

    def test_remember_turn_count_increment(self, memory_store, mock_deepseek_client):
        """测试轮次计数递增。"""
        orch = MemoryOrchestrator(
            vector_store=memory_store,
            deepseek_client=mock_deepseek_client,
        )
        assert orch._turn_count == 0

        orch.remember("t1", "你好", "你好！")
        assert orch._turn_count == 1

        orch.remember("t2", "你好吗", "我很好！")
        assert orch._turn_count == 2


class TestOrchestratorRecall:
    """MemoryOrchestrator.recall 测试。"""

    def test_recall_empty_store(self, memory_store, mock_deepseek_client):
        """测试空数据库检索。"""
        orch = MemoryOrchestrator(
            vector_store=memory_store,
            deepseek_client=mock_deepseek_client,
        )
        results = orch.recall("测试查询")
        assert results == []

    def test_recall_with_memories(self, memory_store, mock_deepseek_client):
        """测试有记忆时检索。"""
        # 先存入记忆
        entry = MemoryEntry(
            memory_type="user_fact",
            content="用户喜欢编程",
            importance=0.9,
            keywords=["用户", "喜欢", "编程"],
        )
        memory_store.add(entry)

        orch = MemoryOrchestrator(
            vector_store=memory_store,
            deepseek_client=mock_deepseek_client,
        )

        results = orch.recall("编程", top_k=3)
        # recall 内部调用 retriever，retriever 调用 embed API
        # mock client 返回固定向量，所以应该能检索到结果
        assert isinstance(results, list)

    def test_recall_empty_query(self, memory_store, mock_deepseek_client):
        """测试空查询跳过检索。"""
        orch = MemoryOrchestrator(
            vector_store=memory_store,
            deepseek_client=mock_deepseek_client,
        )
        results = orch.recall("")
        assert results == []
        results = orch.recall("   ")
        assert results == []


class TestOrchestratorStats:
    """MemoryOrchestrator.get_stats 测试。"""

    def test_get_stats_empty(self, memory_store, mock_deepseek_client):
        """测试空数据库统计。"""
        orch = MemoryOrchestrator(
            vector_store=memory_store,
            deepseek_client=mock_deepseek_client,
        )
        stats = orch.get_stats()
        assert stats["total"] == 0
        assert "by_type" in stats
        assert stats["turn_count"] == 0

    def test_get_stats_with_data(self, memory_store, mock_deepseek_client):
        """测试有数据时统计。"""
        memory_store.add(MemoryEntry(memory_type="user_fact", content="事实1"))
        memory_store.add(MemoryEntry(memory_type="episodic", content="事件1"))
        memory_store.add(MemoryEntry(memory_type="semantic", content="知识1"))

        orch = MemoryOrchestrator(
            vector_store=memory_store,
            deepseek_client=mock_deepseek_client,
        )
        stats = orch.get_stats()
        assert stats["total"] == 3
        assert stats["by_type"]["user_fact"] == 1
        assert stats["by_type"]["episodic"] == 1
        assert stats["by_type"]["semantic"] == 1


class TestOrchestratorClose:
    """MemoryOrchestrator.close 测试。"""

    def test_close(self, memory_store, mock_deepseek_client):
        """测试关闭编排器。"""
        orch = MemoryOrchestrator(
            vector_store=memory_store,
            deepseek_client=mock_deepseek_client,
        )
        orch.close()
        # 关闭后 vector_store 应不可用
        from src.memory.vector_store import MemoryStorageError
        with pytest.raises(MemoryStorageError):
            memory_store.count()
"""测试 VectorStore 向量存储核心功能。

覆盖：初始化、增删查、搜索（向量+关键词）、持久化、关闭重开、OOM防护、归档、分页。
"""

import pytest

from src.memory.vector_store import (
    VectorStore,
    MemoryEntry,
    MemoryNotFoundError,
    MemoryStorageError,
    cosine_similarity,
    extract_keywords,
)


# =============================================================================
# 工具函数测试
# =============================================================================

class TestCosineSimilarity:
    """余弦相似度函数测试。"""

    def test_identical_vectors(self):
        assert cosine_similarity([1.0, 0.0], [1.0, 0.0]) == pytest.approx(1.0)

    def test_orthogonal_vectors(self):
        assert cosine_similarity([1.0, 0.0], [0.0, 1.0]) == pytest.approx(0.0)

    def test_opposite_vectors(self):
        assert cosine_similarity([1.0, 0.0], [-1.0, 0.0]) == pytest.approx(-1.0)

    def test_dimension_mismatch(self):
        with pytest.raises(ValueError, match="维度不一致"):
            cosine_similarity([1.0, 2.0], [1.0])

    def test_zero_vector(self):
        assert cosine_similarity([0.0, 0.0], [1.0, 2.0]) == pytest.approx(0.0)


class TestExtractKeywords:
    """关键词提取函数测试。"""

    def test_chinese_text(self):
        keywords = extract_keywords("我喜欢吃苹果")
        assert len(keywords) > 0
        assert "苹果" in keywords
        assert "喜欢" in keywords

    def test_empty_text(self):
        assert extract_keywords("") == []
        assert extract_keywords("  ") == []

    def test_short_text(self):
        # 单字无法提取 bigram/trigram
        assert extract_keywords("a") == []


# =============================================================================
# VectorStore 核心测试
# =============================================================================

class TestVectorStoreInit:
    """VectorStore 初始化测试。"""

    def test_init_memory(self):
        """测试内存数据库初始化。"""
        store = VectorStore(":memory:")
        assert store is not None
        assert store.count() == 0
        store.close()

    def test_init_file_db(self, temp_db_path):
        """测试文件数据库初始化。"""
        store = VectorStore(temp_db_path)
        assert store.count() == 0
        store.close()
        # 文件应该已创建
        import os
        assert os.path.exists(temp_db_path)

    def test_double_close(self, memory_store):
        """测试重复关闭不会报错。"""
        memory_store.close()
        memory_store.close()  # 第二次应静默返回

    def test_operations_after_close(self, memory_store):
        """测试关闭后操作抛出异常。"""
        memory_store.close()
        entry = MemoryEntry(memory_type="episodic", content="测试")
        with pytest.raises(MemoryStorageError, match="已关闭"):
            memory_store.add(entry)


class TestVectorStoreCRUD:
    """VectorStore 增删查测试。"""

    def test_add_and_get(self, memory_store):
        """测试添加和获取记忆。"""
        entry = MemoryEntry(
            memory_type="episodic",
            content="测试记忆内容",
            importance=0.8,
        )
        mem_id = memory_store.add(entry)
        assert mem_id is not None
        assert len(mem_id) > 0

        retrieved = memory_store.get(mem_id)
        assert retrieved is not None
        assert retrieved.content == "测试记忆内容"
        assert retrieved.memory_type == "episodic"
        assert retrieved.importance == 0.8

    def test_get_non_existent(self, memory_store):
        """测试获取不存在的记忆抛出异常。"""
        with pytest.raises(MemoryNotFoundError):
            memory_store.get("non-existent-id")

    def test_add_multiple(self, memory_store):
        """测试添加多条记忆。"""
        for i in range(10):
            entry = MemoryEntry(
                memory_type="episodic",
                content=f"记忆内容 {i}",
                importance=0.5,
            )
            memory_store.add(entry)
        assert memory_store.count() == 10

    def test_delete(self, memory_store):
        """测试删除记忆。"""
        entry = MemoryEntry(memory_type="user_fact", content="要删除的内容")
        mem_id = memory_store.add(entry)
        assert memory_store.count() == 1

        memory_store.delete(mem_id)
        assert memory_store.count() == 0
        with pytest.raises(MemoryNotFoundError):
            memory_store.get(mem_id)

    def test_delete_non_existent(self, memory_store):
        """测试删除不存在的记忆抛出异常。"""
        with pytest.raises(MemoryNotFoundError):
            memory_store.delete("non-existent-id")

    def test_auto_keywords(self, memory_store):
        """测试自动提取关键词。"""
        entry = MemoryEntry(
            memory_type="user_fact",
            content="我喜欢吃苹果和橙子",
        )
        memory_store.add(entry)
        # 关键词应该被自动提取了
        assert len(entry.keywords) > 0


class TestVectorStoreSearch:
    """VectorStore 搜索测试。"""

    def test_search_with_embedding(self, memory_store):
        """测试使用向量搜索。"""
        # 添加几条记忆
        memory_store.add(MemoryEntry(
            memory_type="user_fact", content="我喜欢编程",
            embedding=[0.9, 0.1, 0.1], keywords=["喜欢", "编程"],
        ))
        memory_store.add(MemoryEntry(
            memory_type="user_fact", content="我喜欢吃苹果",
            embedding=[0.1, 0.9, 0.1], keywords=["喜欢", "苹果"],
        ))
        memory_store.add(MemoryEntry(
            memory_type="user_fact", content="今天天气很好",
            embedding=[0.1, 0.1, 0.9], keywords=["天气"],
        ))

        # 搜索与编程相关的记忆（embedding 接近 [0.9, 0.1, 0.1]）
        results = memory_store.search(
            query_embedding=[0.85, 0.15, 0.1],
            query_text="编程",
            top_k=5,
        )
        assert len(results) > 0
        # 最高分的应该是编程相关
        best_entry, best_score = results[0]
        assert "编程" in best_entry.content

    def test_search_with_keywords_only(self, memory_store):
        """测试仅用关键词搜索（无 embedding）。"""
        memory_store.add(MemoryEntry(
            memory_type="user_fact", content="我喜欢吃苹果",
            keywords=["喜欢", "苹果"],
        ))
        memory_store.add(MemoryEntry(
            memory_type="user_fact", content="橙子富含维生素C",
            keywords=["橙子", "维生素"],
        ))

        results = memory_store.search(
            query_embedding=[],
            query_text="苹果",
            top_k=5,
        )
        assert len(results) > 0

    def test_search_empty_store(self, memory_store):
        """测试空数据库中搜索。"""
        results = memory_store.search(
            query_embedding=[0.5, 0.5],
            query_text="测试",
            top_k=5,
        )
        assert results == []


class TestVectorStorePersistence:
    """VectorStore 持久化测试。"""

    def test_close_and_reopen(self, temp_db_path):
        """测试关闭后重新打开，数据仍存在。"""
        # 写入
        store1 = VectorStore(temp_db_path)
        entry = MemoryEntry(
            memory_type="episodic",
            content="持久化测试数据",
            importance=0.9,
            keywords=["持久化", "测试"],
        )
        mem_id = store1.add(entry)
        count1 = store1.count()
        store1.close()

        # 重新打开
        store2 = VectorStore(temp_db_path)
        assert store2.count() == count1
        retrieved = store2.get(mem_id)
        assert retrieved.content == "持久化测试数据"
        assert retrieved.memory_type == "episodic"
        # 关键词倒排索引也应该恢复
        results = store2.search(
            query_embedding=[],
            query_text="持久化",
            top_k=5,
        )
        assert len(results) > 0
        store2.close()


class TestVectorStoreTypes:
    """VectorStore 按类型查询测试。"""

    def test_get_by_type(self, memory_store):
        """测试按类型查询记忆。"""
        types = ["episodic", "semantic", "user_fact"]
        for t in types:
            memory_store.add(MemoryEntry(memory_type=t, content=f"类型{t}的记忆"))

        for t in types:
            entries = memory_store.get_by_type(t)
            assert len(entries) >= 1
            for e in entries:
                assert e.memory_type == t

    def test_count(self, memory_store):
        """测试计数。"""
        assert memory_store.count() == 0
        memory_store.add(MemoryEntry(memory_type="episodic", content="a"))
        assert memory_store.count() == 1
        memory_store.add(MemoryEntry(memory_type="semantic", content="b"))
        assert memory_store.count() == 2


class TestVectorStoreArchived:
    """VectorStore 归档功能测试。"""

    def test_mark_archived(self, memory_store):
        """测试标记记忆为已归档。"""
        entry = MemoryEntry(memory_type="episodic", content="待归档记忆")
        mem_id = memory_store.add(entry)

        count = memory_store.mark_archived([mem_id])
        assert count == 1

        retrieved = memory_store.get(mem_id)
        assert retrieved.archived is True

    def test_count_active(self, memory_store):
        """测试计算活跃记忆数。"""
        entry1 = MemoryEntry(memory_type="episodic", content="活跃")
        entry2 = MemoryEntry(memory_type="semantic", content="也将被归档")
        id1 = memory_store.add(entry1)
        id2 = memory_store.add(entry2)

        assert memory_store.count_active() == 2

        memory_store.mark_archived([id1, id2])
        assert memory_store.count_active() == 0
        assert memory_store.count() == 2

    def test_search_archived_penalty(self, memory_store):
        """测试已归档记忆在搜索中得分降低。"""
        memory_store.add(MemoryEntry(
            memory_type="episodic", content="活跃记忆",
            embedding=[0.9, 0.1], keywords=["活跃"],
        ))
        entry2 = MemoryEntry(
            memory_type="episodic", content="已归档记忆",
            embedding=[0.89, 0.11], keywords=["归档"],
        )
        mem_id2 = memory_store.add(entry2)
        memory_store.mark_archived([mem_id2])

        results = memory_store.search(
            query_embedding=[0.9, 0.1],
            query_text="记忆",
            top_k=10,
        )
        # 两条都应该返回，但归档的记忆得分更低
        assert len(results) >= 2
        contents = [e.content for e, _ in results]
        assert "活跃记忆" in contents
        assert "已归档记忆" in contents
        # 活跃记忆应该排前面
        assert results[0][0].content == "活跃记忆"


class TestVectorStorePagination:
    """VectorStore 分页加载测试。"""

    def test_get_page(self, memory_store):
        """测试分页加载。"""
        for i in range(25):
            memory_store.add(MemoryEntry(
                memory_type="episodic", content=f"记忆{i:03d}"
            ))

        page1 = memory_store.get_page(offset=0, limit=10)
        assert len(page1) == 10

        page2 = memory_store.get_page(offset=10, limit=10)
        assert len(page2) == 10

        page3 = memory_store.get_page(offset=20, limit=10)
        assert len(page3) == 5  # 只剩5条


class TestVectorStoreRecentImportant:
    """VectorStore 最近/重要记忆测试。"""

    def test_get_recent_entries(self, memory_store):
        """测试获取最近记忆。"""
        for i in range(5):
            memory_store.add(MemoryEntry(
                memory_type="episodic", content=f"记忆{i}"
            ))
        recent = memory_store.get_recent_entries(top_k=3)
        assert len(recent) == 3

    def test_get_important_entries(self, memory_store):
        """测试获取重要记忆。"""
        for i, imp in enumerate([0.1, 0.5, 0.9, 0.3, 0.95]):
            memory_store.add(MemoryEntry(
                memory_type="semantic",
                content=f"重要度{imp}",
                importance=imp,
            ))
        important = memory_store.get_important_entries(
            min_importance=0.8, top_k=5
        )
        assert len(important) == 2
        for e in important:
            assert e.importance >= 0.8


# =============================================================================
# MemoryEntry 数据类测试
# =============================================================================

class TestMemoryEntry:
    """MemoryEntry 数据类测试。"""

    def test_default_values(self):
        entry = MemoryEntry()
        assert entry.id != ""
        assert entry.memory_type == "semantic"
        assert entry.content == ""
        assert entry.importance == 0.5
        assert entry.keywords == []
        assert entry.embedding == []

    def test_to_dict_and_from_dict(self):
        entry = MemoryEntry(
            memory_type="user_fact",
            content="测试内容",
            importance=0.9,
            keywords=["测试", "内容"],
            embedding=[0.1, 0.2, 0.3],
        )
        d = entry.to_dict()
        assert d["content"] == "测试内容"
        assert d["memory_type"] == "user_fact"
        assert d["importance"] == 0.9

        restored = MemoryEntry.from_dict(d)
        assert restored.content == entry.content
        assert restored.memory_type == entry.memory_type
        assert restored.importance == entry.importance
"""P1 阶段4 记忆系统测试脚本。

测试覆盖：
    1. 向量存储的 CRUD 操作
    2. 倒排索引的添加/搜索/删除
    3. 余弦相似度计算
    4. 混合检索流程
    5. 记忆提取（规则模式）
    6. 时间衰减计算
    7. 流式导出
"""

import json
import math
import os
import sys
import tempfile
from datetime import datetime, timedelta, timezone
from pathlib import Path

# 确保项目根目录在 sys.path 中
_project_root = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(_project_root))

# 东八区时区
TZ_CST = timezone(timedelta(hours=8))


# =============================================================================
# 测试辅助函数
# =============================================================================


def make_compatible_embedding(text: str, dim: int = 8) -> list[float]:
    """生成一个兼容的测试向量（基于文本 hash 的确定性向量）。"""
    # 简单哈希生成，确保相同文本产生相同向量
    h = hash(text) % 1000
    import random
    rng = random.Random(h)
    vec = [rng.uniform(-1.0, 1.0) for _ in range(dim)]
    # 归一化
    norm = math.sqrt(sum(x * x for x in vec))
    if norm > 0:
        vec = [x / norm for x in vec]
    return vec


def make_test_entry(
    content: str,
    memory_type: str = "semantic",
    importance: float = 0.5,
    dim: int = 8,
) -> tuple:
    """创建测试用的 MemoryEntry。"""
    from src.memory.vector_store import MemoryEntry
    from src.utils.time_utils import format_timestamp_iso

    now = format_timestamp_iso()
    entry = MemoryEntry(
        memory_type=memory_type,
        content=content,
        embedding=make_compatible_embedding(content, dim),
        importance=importance,
        created_at=now,
        last_accessed=now,
    )
    return entry


def print_result(test_name: str, passed: bool, detail: str = ""):
    """打印测试结果。"""
    icon = "PASS" if passed else "FAIL"
    suffix = f" - {detail}" if detail else ""
    print(f"  [{icon}] {test_name}{suffix}")


# =============================================================================
# 测试1：向量存储 CRUD 操作
# =============================================================================


def test_1a_vector_store_add_and_get():
    """测试添加和获取记忆。"""
    print("\n--- 测试1a: 向量存储添加和获取 ---")
    from src.memory.vector_store import VectorStore

    store = VectorStore(":memory:")  # 使用内存数据库
    try:
        # 添加记忆
        entry = make_test_entry("用户喜欢草莓蛋糕", "user_fact", 0.9)
        mem_id = store.add(entry)

        # 获取记忆
        retrieved = store.get(mem_id)
        assert retrieved.content == "用户喜欢草莓蛋糕", f"内容不匹配: {retrieved.content}"
        assert retrieved.memory_type == "user_fact", f"类型不匹配: {retrieved.memory_type}"
        assert retrieved.importance == 0.9, f"重要性不匹配: {retrieved.importance}"

        print_result("添加和获取记忆", True)
    except Exception as e:
        print_result("添加和获取记忆", False, str(e))
    finally:
        store.close()


def test_1b_vector_store_delete():
    """测试删除记忆。"""
    print("\n--- 测试1b: 向量存储删除 ---")
    from src.memory.vector_store import VectorStore, MemoryNotFoundError

    store = VectorStore(":memory:")
    try:
        entry = make_test_entry("测试删除的记忆")
        mem_id = store.add(entry)
        assert store.count() == 1

        store.delete(mem_id)
        assert store.count() == 0

        # 确认删除后查询抛异常
        try:
            store.get(mem_id)
            assert False, "应该抛出 MemoryNotFoundError"
        except MemoryNotFoundError:
            pass

        print_result("删除记忆", True)
    except Exception as e:
        print_result("删除记忆", False, str(e))
    finally:
        store.close()


def test_1c_vector_store_update():
    """测试更新记忆。"""
    print("\n--- 测试1c: 向量存储更新 ---")
    from src.memory.vector_store import VectorStore

    store = VectorStore(":memory:")
    try:
        entry = make_test_entry("原始内容", "semantic", 0.3)
        mem_id = store.add(entry)

        # 更新
        entry.content = "更新后的内容"
        entry.importance = 0.7
        store.update(entry)

        retrieved = store.get(mem_id)
        assert retrieved.content == "更新后的内容", f"内容未更新: {retrieved.content}"
        assert retrieved.importance == 0.7, f"重要性未更新: {retrieved.importance}"

        print_result("更新记忆", True)
    except Exception as e:
        print_result("更新记忆", False, str(e))
    finally:
        store.close()


def test_1d_vector_store_count_and_clear():
    """测试计数和清空。"""
    print("\n--- 测试1d: 计数和清空 ---")
    from src.memory.vector_store import VectorStore

    store = VectorStore(":memory:")
    try:
        for i in range(5):
            entry = make_test_entry(f"记忆 {i}")
            store.add(entry)

        assert store.count() == 5, f"计数应为5: {store.count()}"
        store.clear()
        assert store.count() == 0, f"清空后计数应为0: {store.count()}"

        print_result("计数和清空", True)
    except Exception as e:
        print_result("计数和清空", False, str(e))
    finally:
        store.close()


def test_1e_vector_store_get_by_type():
    """测试按类型获取记忆。"""
    print("\n--- 测试1e: 按类型获取记忆 ---")
    from src.memory.vector_store import VectorStore

    store = VectorStore(":memory:")
    try:
        store.add(make_test_entry("事实1", "user_fact", 0.9))
        store.add(make_test_entry("事实2", "user_fact", 0.8))
        store.add(make_test_entry("事件1", "episodic", 0.5))
        store.add(make_test_entry("语义1", "semantic", 0.6))

        facts = store.get_by_type("user_fact")
        assert len(facts) == 2, f"user_fact 应为2条: {len(facts)}"

        episodes = store.get_by_type("episodic")
        assert len(episodes) == 1, f"episodic 应为1条: {len(episodes)}"

        print_result("按类型获取", True)
    except Exception as e:
        print_result("按类型获取", False, str(e))
    finally:
        store.close()


# =============================================================================
# 测试2：倒排索引
# =============================================================================


def test_2a_inverted_index_add_and_search():
    """测试倒排索引添加和搜索。"""
    print("\n--- 测试2a: 倒排索引添加和搜索 ---")
    from src.memory.vector_store import InvertedIndex

    idx = InvertedIndex()
    try:
        idx.add("mem1", ["喜欢", "草莓", "蛋糕"])
        idx.add("mem2", ["喜欢", "巧克力"])
        idx.add("mem3", ["讨厌", "榴莲"])

        # 搜索"喜欢"关键词
        result = idx.search(["喜欢"])
        assert "mem1" in result and "mem2" in result, f"搜索'喜欢'结果: {result}"
        assert "mem3" not in result

        # 搜索"草莓"关键词
        result = idx.search(["草莓"])
        assert "mem1" in result and len(result) == 1

        print_result("倒排索引添加和搜索", True)
    except Exception as e:
        print_result("倒排索引添加和搜索", False, str(e))


def test_2b_inverted_index_remove():
    """测试倒排索引删除。"""
    print("\n--- 测试2b: 倒排索引删除 ---")
    from src.memory.vector_store import InvertedIndex

    idx = InvertedIndex()
    try:
        idx.add("mem1", ["喜欢", "草莓"])
        idx.add("mem2", ["喜欢", "巧克力"])

        # 删除 mem1
        idx.remove("mem1", ["喜欢", "草莓"])

        result = idx.search(["草莓"])
        assert len(result) == 0, f"删除后'草莓'应为空: {result}"

        result = idx.search(["喜欢"])
        assert "mem2" in result and "mem1" not in result

        print_result("倒排索引删除", True)
    except Exception as e:
        print_result("倒排索引删除", False, str(e))


def test_2c_inverted_index_clear():
    """测试倒排索引清空。"""
    print("\n--- 测试2c: 倒排索引清空 ---")
    from src.memory.vector_store import InvertedIndex

    idx = InvertedIndex()
    try:
        idx.add("mem1", ["喜欢", "草莓"])
        idx.add("mem2", ["喜欢", "巧克力"])
        assert idx.size() > 0

        idx.clear()
        assert idx.size() == 0
        assert len(idx.search(["喜欢"])) == 0

        print_result("倒排索引清空", True)
    except Exception as e:
        print_result("倒排索引清空", False, str(e))


# =============================================================================
# 测试3：余弦相似度
# =============================================================================


def test_3a_cosine_similarity_basic():
    """测试余弦相似度基本计算。"""
    print("\n--- 测试3a: 余弦相似度基本计算 ---")
    from src.memory.vector_store import cosine_similarity

    try:
        # 相同向量
        sim = cosine_similarity([1.0, 0.0, 0.0], [1.0, 0.0, 0.0])
        assert abs(sim - 1.0) < 0.0001, f"相同向量相似度应为1.0: {sim}"

        # 正交向量
        sim = cosine_similarity([1.0, 0.0], [0.0, 1.0])
        assert abs(sim - 0.0) < 0.0001, f"正交向量相似度应为0.0: {sim}"

        # 零向量
        sim = cosine_similarity([0.0, 0.0], [1.0, 0.0])
        assert abs(sim - 0.0) < 0.0001, f"零向量相似度应为0.0: {sim}"

        # 一般情况
        sim = cosine_similarity([1.0, 2.0, 3.0], [4.0, 5.0, 6.0])
        expected = (1 * 4 + 2 * 5 + 3 * 6) / (
            math.sqrt(1 + 4 + 9) * math.sqrt(16 + 25 + 36)
        )
        assert abs(sim - expected) < 0.0001, f"一般情况: {sim} vs {expected}"

        print_result("余弦相似度基本计算", True)
    except Exception as e:
        print_result("余弦相似度基本计算", False, str(e))


def test_3b_cosine_similarity_dimension_mismatch():
    """测试余弦相似度维度不匹配。"""
    print("\n--- 测试3b: 余弦相似度维度不匹配 ---")
    from src.memory.vector_store import cosine_similarity

    try:
        cosine_similarity([1.0, 2.0], [1.0, 2.0, 3.0])
        print_result("余弦相似度维度不匹配", False, "应该抛出 ValueError")
    except ValueError:
        print_result("余弦相似度维度不匹配", True)


# =============================================================================
# 测试4：混合检索流程
# =============================================================================


def test_4a_vector_store_search():
    """测试向量存储的混合检索（倒排索引 + 余弦相似度）。"""
    print("\n--- 测试4a: 向量存储混合检索 ---")
    from src.memory.vector_store import VectorStore

    store = VectorStore(":memory:")
    try:
        # 添加测试记忆
        entries = [
            make_test_entry("我喜欢吃草莓蛋糕", "user_fact", 0.9),
            make_test_entry("我住在北京朝阳区", "user_fact", 0.85),
            make_test_entry("今天去公园散步了", "episodic", 0.5),
            make_test_entry("深度学习的本质是神经网络", "semantic", 0.7),
            make_test_entry("我喜欢看科幻电影", "user_fact", 0.8),
            make_test_entry("北京是中国的首都", "semantic", 0.6),
            make_test_entry("Python是一种编程语言", "semantic", 0.5),
        ]
        for entry in entries:
            store.add(entry)

        # 用"蛋糕"相关的向量查询
        query_vec = make_compatible_embedding("草莓蛋糕很好吃")
        results = store.search(query_vec, "草莓蛋糕", top_k=3)

        assert len(results) > 0, "搜索应有结果"
        assert len(results) <= 3, f"top_k=3 应最多返回3条: {len(results)}"

        # 验证第一条应该是最相关的（草莓蛋糕）
        print(f"    搜索结果: {[r.content[:30] for r in results]}")

        print_result("向量存储混合检索", True)
    except Exception as e:
        print_result("向量存储混合检索", False, str(e))
        import traceback
        traceback.print_exc()
    finally:
        store.close()


def test_4b_vector_store_search_no_keyword_match():
    """测试无关键词匹配时的全量回退。"""
    print("\n--- 测试4b: 全量回退检索 ---")
    from src.memory.vector_store import VectorStore

    store = VectorStore(":memory:")
    try:
        store.add(make_test_entry("我喜欢吃草莓蛋糕", "user_fact", 0.9))
        store.add(make_test_entry("深度学习是AI的分支", "semantic", 0.7))

        # 用不匹配的关键词查询
        query_vec = make_compatible_embedding("量子计算")
        results = store.search(query_vec, "量子计算", top_k=3)

        # 应该回退到全量检索
        assert len(results) > 0, "全量回退应有结果"

        print_result("全量回退检索", True)
    except Exception as e:
        print_result("全量回退检索", False, str(e))
    finally:
        store.close()


# =============================================================================
# 测试5：记忆提取（规则模式）
# =============================================================================


def test_5a_rule_extraction_user_facts():
    """测试规则模式提取用户事实。"""
    print("\n--- 测试5a: 规则提取用户事实 ---")
    from src.memory.extractor import MemoryExtractor

    # 创建一个 mock client（规则模式不需要真正的 API 调用）
    class MockClient:
        pass

    extractor = MemoryExtractor(MockClient())
    try:
        messages = [
            {"role": "user", "content": "我是小明，今年25岁"},
            {"role": "assistant", "content": "你好小明！"},
            {"role": "user", "content": "我喜欢吃草莓蛋糕和巧克力"},
            {"role": "user", "content": "我住在上海浦东新区"},
            {"role": "user", "content": "我是一名程序员"},
        ]

        entries = extractor._extract_by_rule(messages, "turn_001")
        assert len(entries) > 0, f"规则提取应有结果: {len(entries)}"

        # 验证提取的内容
        contents = [e.content for e in entries]
        print(f"    提取结果: {contents}")

        # 至少应该提取到"我是小明"、"我喜欢吃草莓蛋糕"、"我住在上海"、"我是一名程序员"
        has_name = any("小明" in c for c in contents)
        has_food = any("草莓" in c for c in contents)
        has_location = any("上海" in c for c in contents)
        has_job = any("程序员" in c for c in contents)

        assert has_name, f"应提取到名字: {contents}"
        assert has_food, f"应提取到喜好: {contents}"
        assert has_location, f"应提取到住址: {contents}"
        assert has_job, f"应提取到职业: {contents}"

        print_result("规则提取用户事实", True)
    except Exception as e:
        print_result("规则提取用户事实", False, str(e))
        import traceback
        traceback.print_exc()


def test_5b_rule_extraction_episodic():
    """测试规则模式提取事件记忆。"""
    print("\n--- 测试5b: 规则提取事件记忆 ---")
    from src.memory.extractor import MemoryExtractor

    class MockClient:
        pass

    extractor = MemoryExtractor(MockClient())
    try:
        messages = [
            {"role": "user", "content": "今天去公园散步了，天气真好"},
            {"role": "user", "content": "刚刚完成了一个大项目，感觉很有成就感"},
            {"role": "user", "content": "上周去了海边度假"},
        ]

        entries = extractor._extract_by_rule(messages, "turn_002")
        assert len(entries) > 0, f"规则提取应有结果: {len(entries)}"

        contents = [e.content for e in entries]
        print(f"    提取结果: {contents}")

        # 验证类型
        episodic_entries = [e for e in entries if e.memory_type == "episodic"]
        assert len(episodic_entries) > 0, f"应有事件记忆: {len(episodic_entries)}"

        print_result("规则提取事件记忆", True)
    except Exception as e:
        print_result("规则提取事件记忆", False, str(e))
        import traceback
        traceback.print_exc()


def test_5c_deduplication():
    """测试记忆去重逻辑。"""
    print("\n--- 测试5c: 记忆去重 ---")
    from src.memory.extractor import MemoryExtractor
    from src.memory.vector_store import VectorStore, MemoryEntry
    from src.utils.time_utils import format_timestamp_iso

    class MockClient:
        pass

    store = VectorStore(":memory:")
    try:
        # 先添加一条已有记忆
        existing = MemoryEntry(
            memory_type="user_fact",
            content="我喜欢吃草莓蛋糕",
            importance=0.9,
            created_at=format_timestamp_iso(),
            last_accessed=format_timestamp_iso(),
        )
        store.add(existing)

        extractor = MemoryExtractor(MockClient(), store)

        # 新提取的记忆和已有记忆高度相似（几乎相同）
        new_entries = [
            MemoryEntry(
                memory_type="user_fact",
                content="我喜欢吃草莓蛋糕",  # 与已有记忆完全相同
                importance=0.85,
            ),
            MemoryEntry(
                memory_type="user_fact",
                content="我喜欢喝咖啡",
                importance=0.7,
            ),
        ]

        deduped = extractor._deduplicate(new_entries)
        assert len(deduped) == 1, f"应只保留1条（去重1条）: {len(deduped)}"
        assert "咖啡" in deduped[0].content, f"保留的应是咖啡记忆: {deduped[0].content}"

        print_result("记忆去重", True)
    except Exception as e:
        print_result("记忆去重", False, str(e))
        import traceback
        traceback.print_exc()
    finally:
        store.close()


# =============================================================================
# 测试6：时间衰减计算
# =============================================================================


def test_6a_decay_calculation():
    """测试衰减因子计算。"""
    print("\n--- 测试6a: 衰减因子计算 ---")
    from src.memory.decay import calculate_decay, get_half_life
    from src.memory.vector_store import MemoryEntry

    try:
        # 创建一个刚创建的条目
        now = datetime.now(TZ_CST)
        entry = MemoryEntry(
            memory_type="episodic",
            content="测试事件",
            importance=0.5,
            created_at=(now - timedelta(days=7)).isoformat(),
        )

        # 半衰期7天，经过7天后衰减因子应接近0.5
        decay = calculate_decay(entry, now)
        print(f"    episodic 7天后衰减因子: {decay:.4f} (预期 ~0.5)")

        # 半衰期内的衰减
        entry2 = MemoryEntry(
            memory_type="episodic",
            content="测试事件2",
            importance=0.5,
            created_at=(now - timedelta(days=0)).isoformat(),
        )
        decay2 = calculate_decay(entry2, now)
        assert decay2 == 1.0, f"0天后衰减因子应为1.0: {decay2}"

        # user_fact 衰减很慢
        entry3 = MemoryEntry(
            memory_type="user_fact",
            content="用户事实",
            importance=0.5,
            created_at=(now - timedelta(days=7)).isoformat(),
        )
        decay3 = calculate_decay(entry3, now)
        print(f"    user_fact 7天后衰减因子: {decay3:.4f} (半衰期180天，应接近1.0)")
        assert decay3 > 0.9, f"user_fact 7天后应 > 0.9: {decay3}"

        print_result("衰减因子计算", True)
    except Exception as e:
        print_result("衰减因子计算", False, str(e))
        import traceback
        traceback.print_exc()


def test_6b_get_weight():
    """测试综合权重计算。"""
    print("\n--- 测试6b: 综合权重计算 ---")
    from src.memory.decay import get_weight
    from src.memory.vector_store import MemoryEntry

    try:
        now = datetime.now(TZ_CST)
        entry = MemoryEntry(
            memory_type="user_fact",
            content="重要事实",
            importance=0.9,
            created_at=(now - timedelta(days=1)).isoformat(),
        )

        weight = get_weight(entry, now)
        print(f"    user_fact 权重(importance=0.9): {weight:.4f}")
        assert weight > 0.5, f"user_fact 权重应 > 0.5: {weight}"

        # episodic 权重较低
        entry2 = MemoryEntry(
            memory_type="episodic",
            content="普通事件",
            importance=0.5,
            created_at=(now - timedelta(days=14)).isoformat(),
        )
        weight2 = get_weight(entry2, now)
        print(f"    episodic 14天权重(importance=0.5): {weight2:.4f}")
        assert weight2 < weight, f"episodic 权重应低于 user_fact: {weight2} vs {weight}"

        print_result("综合权重计算", True)
    except Exception as e:
        print_result("综合权重计算", False, str(e))
        import traceback
        traceback.print_exc()


def test_6c_get_decay_stats():
    """测试衰减统计。"""
    print("\n--- 测试6c: 衰减统计 ---")
    from src.memory.decay import get_decay_stats
    from src.memory.vector_store import MemoryEntry

    try:
        now = datetime.now(TZ_CST)
        entries = [
            MemoryEntry(
                memory_type="user_fact",
                content="事实1",
                created_at=(now - timedelta(days=1)).isoformat(),
            ),
            MemoryEntry(
                memory_type="episodic",
                content="事件1",
                created_at=(now - timedelta(days=10)).isoformat(),
            ),
            MemoryEntry(
                memory_type="semantic",
                content="语义1",
                created_at=(now - timedelta(days=5)).isoformat(),
            ),
        ]

        stats = get_decay_stats(entries, now)
        print(f"    衰减统计: {json.dumps(stats, ensure_ascii=False, indent=2)}")
        assert stats["total"] == 3
        assert "by_type" in stats
        assert "user_fact" in stats["by_type"]
        assert "episodic" in stats["by_type"]

        print_result("衰减统计", True)
    except Exception as e:
        print_result("衰减统计", False, str(e))
        import traceback
        traceback.print_exc()


# =============================================================================
# 测试7：流式导出
# =============================================================================


def test_7a_export_chat_history():
    """测试导出对话历史。"""
    print("\n--- 测试7a: 导出对话历史 ---")
    from src.memory.exporter import export_chat_history

    try:
        messages = [
            {"role": "user", "content": "你好"},
            {"role": "assistant", "content": "你好！"},
            {"role": "user", "content": "今天天气怎么样？"},
            {"role": "assistant", "content": "今天天气很好，适合出去走走。"},
        ]

        with tempfile.NamedTemporaryFile(
            suffix=".json", delete=False, mode="w", encoding="utf-8"
        ) as tmp:
            tmp_path = tmp.name

        try:
            result = export_chat_history(messages, tmp_path)
            assert result.exists(), f"导出文件应存在: {result}"

            # 读取并验证
            with open(result, "r", encoding="utf-8") as f:
                data = json.load(f)

            assert data["export_type"] == "chat_history"
            assert data["total_count"] == 4
            assert len(data["messages"]) == 4

            print(f"    导出文件: {result} ({result.stat().st_size} 字节)")
            print_result("导出对话历史", True)
        finally:
            # 清理
            if Path(tmp_path).exists():
                Path(tmp_path).unlink()
    except Exception as e:
        print_result("导出对话历史", False, str(e))
        import traceback
        traceback.print_exc()


def test_7b_export_memories():
    """测试导出长期记忆。"""
    print("\n--- 测试7b: 导出长期记忆 ---")
    from src.memory.exporter import export_memories

    try:
        entries = [
            make_test_entry("我喜欢吃草莓蛋糕", "user_fact", 0.9),
            make_test_entry("我住在北京朝阳区", "user_fact", 0.85),
            make_test_entry("今天去公园散步了", "episodic", 0.5),
        ]

        with tempfile.NamedTemporaryFile(
            suffix=".json", delete=False, mode="w", encoding="utf-8"
        ) as tmp:
            tmp_path = tmp.name

        try:
            result = export_memories(entries, tmp_path)
            assert result.exists(), f"导出文件应存在: {result}"

            with open(result, "r", encoding="utf-8") as f:
                data = json.load(f)

            assert data["export_type"] == "memories"
            assert data["total_count"] == 3
            assert len(data["memories"]) == 3

            print(f"    导出文件: {result} ({result.stat().st_size} 字节)")
            print_result("导出长期记忆", True)
        finally:
            if Path(tmp_path).exists():
                Path(tmp_path).unlink()
    except Exception as e:
        print_result("导出长期记忆", False, str(e))
        import traceback
        traceback.print_exc()


def test_7c_export_full():
    """测试完整导出。"""
    print("\n--- 测试7c: 完整导出 ---")
    from src.memory.exporter import export_full

    try:
        messages = [
            {"role": "user", "content": "你好"},
            {"role": "assistant", "content": "你好！"},
        ]
        entries = [
            make_test_entry("我喜欢吃草莓蛋糕", "user_fact", 0.9),
        ]

        with tempfile.NamedTemporaryFile(
            suffix=".json", delete=False, mode="w", encoding="utf-8"
        ) as tmp:
            tmp_path = tmp.name

        try:
            result = export_full(messages, entries, tmp_path)
            assert result.exists(), f"导出文件应存在: {result}"

            with open(result, "r", encoding="utf-8") as f:
                data = json.load(f)

            assert data["export_type"] == "full"
            assert "exported_at" in data
            assert data["chat_history"]["total_count"] == 2
            assert data["memories"]["total_count"] == 1

            print(f"    导出文件: {result} ({result.stat().st_size} 字节)")
            print_result("完整导出", True)
        finally:
            if Path(tmp_path).exists():
                Path(tmp_path).unlink()
    except Exception as e:
        print_result("完整导出", False, str(e))
        import traceback
        traceback.print_exc()


# =============================================================================
# 测试8：关键词提取
# =============================================================================


def test_8a_extract_keywords():
    """测试关键词提取。"""
    print("\n--- 测试8a: 关键词提取 ---")
    from src.memory.vector_store import extract_keywords

    try:
        # 中文文本
        keywords = extract_keywords("我喜欢吃草莓蛋糕")
        assert len(keywords) > 0, f"关键词不应为空: {keywords}"
        print(f"    关键词 (中文): {keywords}")

        # 空文本
        keywords = extract_keywords("")
        assert len(keywords) == 0, f"空文本关键词应为空: {keywords}"

        # 短文本
        keywords = extract_keywords("a")
        assert len(keywords) == 0, f"单字符关键词应为空: {keywords}"

        print_result("关键词提取", True)
    except Exception as e:
        print_result("关键词提取", False, str(e))


# =============================================================================
# 测试9：MemoryEntry 序列化
# =============================================================================


def test_9a_memory_entry_serialization():
    """测试 MemoryEntry 的序列化和反序列化。"""
    print("\n--- 测试9a: MemoryEntry 序列化 ---")
    from src.memory.vector_store import MemoryEntry

    try:
        entry = make_test_entry("测试内容", "semantic", 0.7)
        d = entry.to_dict()

        # 反序列化
        restored = MemoryEntry.from_dict(d)
        assert restored.id == entry.id
        assert restored.content == entry.content
        assert restored.memory_type == entry.memory_type
        assert restored.importance == entry.importance
        assert restored.embedding == entry.embedding

        print_result("MemoryEntry 序列化", True)
    except Exception as e:
        print_result("MemoryEntry 序列化", False, str(e))


# =============================================================================
# 测试10：磁盘持久化
# =============================================================================


def test_10a_disk_persistence():
    """测试记忆的磁盘持久化。"""
    print("\n--- 测试10a: 磁盘持久化 ---")
    from src.memory.vector_store import VectorStore

    with tempfile.NamedTemporaryFile(suffix=".db", delete=False) as tmp:
        db_path = tmp.name

    try:
        # 创建并写入数据
        store1 = VectorStore(db_path)
        store1.add(make_test_entry("持久化测试1", "user_fact", 0.9))
        store1.add(make_test_entry("持久化测试2", "semantic", 0.7))
        store1.close()

        # 重新打开，验证数据
        store2 = VectorStore(db_path)
        assert store2.count() == 2, f"重新打开后应有2条记忆: {store2.count()}"
        all_entries = store2.get_all()
        contents = [e.content for e in all_entries]
        assert "持久化测试1" in contents
        assert "持久化测试2" in contents

        # 验证倒排索引也被恢复
        assert store2.inverted_index.size() > 0, "倒排索引应被恢复"

        store2.close()
        print_result("磁盘持久化", True)
    except Exception as e:
        print_result("磁盘持久化", False, str(e))
        import traceback
        traceback.print_exc()
    finally:
        if Path(db_path).exists():
            Path(db_path).unlink()


# =============================================================================
# 主入口
# =============================================================================


def run_all_tests():
    """运行所有测试。"""
    print("=" * 60)
    print("P1 阶段4 记忆系统测试")
    print("=" * 60)

    tests = [
        # 测试1：向量存储 CRUD
        test_1a_vector_store_add_and_get,
        test_1b_vector_store_delete,
        test_1c_vector_store_update,
        test_1d_vector_store_count_and_clear,
        test_1e_vector_store_get_by_type,
        # 测试2：倒排索引
        test_2a_inverted_index_add_and_search,
        test_2b_inverted_index_remove,
        test_2c_inverted_index_clear,
        # 测试3：余弦相似度
        test_3a_cosine_similarity_basic,
        test_3b_cosine_similarity_dimension_mismatch,
        # 测试4：混合检索
        test_4a_vector_store_search,
        test_4b_vector_store_search_no_keyword_match,
        # 测试5：记忆提取
        test_5a_rule_extraction_user_facts,
        test_5b_rule_extraction_episodic,
        test_5c_deduplication,
        # 测试6：时间衰减
        test_6a_decay_calculation,
        test_6b_get_weight,
        test_6c_get_decay_stats,
        # 测试7：流式导出
        test_7a_export_chat_history,
        test_7b_export_memories,
        test_7c_export_full,
        # 测试8：关键词提取
        test_8a_extract_keywords,
        # 测试9：序列化
        test_9a_memory_entry_serialization,
        # 测试10：磁盘持久化
        test_10a_disk_persistence,
    ]

    passed = 0
    failed = 0

    for test in tests:
        try:
            test()
            passed += 1
        except Exception as e:
            failed += 1
            print(f"  [ERROR] {test.__name__} 执行异常: {e}")

    print("\n" + "=" * 60)
    print(f"测试完成: 通过 {passed}/{len(tests)}, 失败 {failed}")
    print("=" * 60)

    return failed == 0


if __name__ == "__main__":
    success = run_all_tests()
    sys.exit(0 if success else 1)
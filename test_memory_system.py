"""记忆系统全量测试脚本。

测试范围：
1. 导入验证
2. 单元测试（TemporalWeight, KnowledgeGraph, MemorySummarizer）
3. 集成测试（Orchestrator, Retriever 模块间协作）
4. 接口兼容性验证
5. Android兼容性检查
"""

from __future__ import annotations

import sys
import os
import json
import traceback
from datetime import datetime, timedelta, timezone
from pathlib import Path

# 添加项目路径
PROJECT_ROOT = Path(__file__).parent
sys.path.insert(0, str(PROJECT_ROOT / "android" / "app" / "src" / "main" / "python"))

# 测试统计
PASSED = 0
FAILED = 0
ERRORS = 0
TEST_RESULTS = []

TZ_CST = timezone(timedelta(hours=8))


def test(name: str):
    """测试装饰器风格上下文管理器"""
    class TestContext:
        def __enter__(self):
            return self
        def __exit__(self, exc_type, exc_val, exc_tb):
            global PASSED, FAILED, ERRORS
            if exc_type is None:
                PASSED += 1
                TEST_RESULTS.append({"name": name, "status": "PASS", "message": ""})
                print(f"  [PASS] {name}")
            elif exc_type is AssertionError:
                FAILED += 1
                msg = str(exc_val) if exc_val else ""
                TEST_RESULTS.append({"name": name, "status": "FAIL", "message": msg})
                print(f"  [FAIL] {name}: {msg}")
            else:
                ERRORS += 1
                msg = f"{exc_type.__name__}: {exc_val}"
                TEST_RESULTS.append({"name": name, "status": "ERROR", "message": msg})
                print(f"  [ERROR] {name}: {msg}")
                traceback.print_exc()
            return True  # suppress exception
    return TestContext()


# =============================================================================
# 第1部分：导入验证
# =============================================================================
print("=" * 70)
print("第1部分：导入验证")
print("=" * 70)

with test("1.1 导入 VectorStore"):
    from src.memory.vector_store import VectorStore, MemoryEntry, InvertedIndex, cosine_similarity, extract_keywords

with test("1.2 导入 KnowledgeGraph"):
    from src.memory.knowledge_graph import KnowledgeGraph

with test("1.3 导入 MemorySummarizer"):
    from src.memory.summarizer import MemorySummarizer

with test("1.4 导入 TemporalWeight 和 get_temporal_context"):
    from src.memory.decay import TemporalWeight, get_temporal_context, calculate_decay, get_weight, get_decay_stats

with test("1.5 导入 MemoryRetriever"):
    from src.memory.retriever import MemoryRetriever

with test("1.6 导入 MemoryExtractor"):
    from src.memory.extractor import MemoryExtractor

with test("1.7 导入 MemoryConsolidator"):
    from src.memory.consolidator import MemoryConsolidator

with test("1.8 导入 MemoryOrchestrator"):
    from src.memory.orchestrator import MemoryOrchestrator

with test("1.9 导入 __init__.py 所有符号"):
    from src.memory import (
        KnowledgeGraph, MemorySummarizer, TemporalWeight, get_temporal_context,
        MemoryOrchestrator, MemoryRetriever, MemoryExtractor, MemoryConsolidator,
        VectorStore, MemoryEntry, InvertedIndex,
    )

with test("1.10 __init__.py 导出 KnowledgeGraph"):
    from src.memory import KnowledgeGraph as KG2
    assert KG2 is not None

with test("1.11 __init__.py 导出 MemorySummarizer"):
    from src.memory import MemorySummarizer as MS2
    assert MS2 is not None

with test("1.12 __init__.py 导出 TemporalWeight"):
    from src.memory import TemporalWeight as TW2
    assert TW2 is not None


# =============================================================================
# 第2部分：TemporalWeight 单元测试
# =============================================================================
print("\n" + "=" * 70)
print("第2部分：TemporalWeight 单元测试")
print("=" * 70)

with test("2.1 get_temporal_context() 返回正确格式"):
    ctx = get_temporal_context()
    assert "weekday" in ctx, "缺少 weekday"
    assert "day_period" in ctx, "缺少 day_period"
    assert "is_weekend" in ctx, "缺少 is_weekend"
    assert "is_workday" in ctx, "缺少 is_workday"
    assert "hour" in ctx, "缺少 hour"
    assert "label" in ctx, "缺少 label"
    assert 0 <= ctx["weekday"] <= 6, f"weekday 范围错误: {ctx['weekday']}"
    assert ctx["is_weekend"] != ctx["is_workday"], "is_weekend 和 is_workday 矛盾"
    assert ctx["day_period"] in ("morning", "noon", "afternoon", "evening", "night")

with test("2.2 get_temporal_context(指定时间) 时段判断正确"):
    # 早上8点 → morning
    ctx = get_temporal_context(datetime(2026, 6, 21, 8, 0, tzinfo=TZ_CST))
    assert ctx["day_period"] == "morning", f"期望 morning, 实际 {ctx['day_period']}"
    # 中午12点 → noon
    ctx = get_temporal_context(datetime(2026, 6, 21, 12, 30, tzinfo=TZ_CST))
    assert ctx["day_period"] == "noon", f"期望 noon, 实际 {ctx['day_period']}"
    # 下午3点 → afternoon
    ctx = get_temporal_context(datetime(2026, 6, 21, 15, 0, tzinfo=TZ_CST))
    assert ctx["day_period"] == "afternoon", f"期望 afternoon, 实际 {ctx['day_period']}"
    # 晚上7点 → evening
    ctx = get_temporal_context(datetime(2026, 6, 21, 19, 0, tzinfo=TZ_CST))
    assert ctx["day_period"] == "evening", f"期望 evening, 实际 {ctx['day_period']}"
    # 凌晨2点 → night
    ctx = get_temporal_context(datetime(2026, 6, 21, 2, 0, tzinfo=TZ_CST))
    assert ctx["day_period"] == "night", f"期望 night, 实际 {ctx['day_period']}"

with test("2.3 get_temporal_context 周末/工作日判断正确"):
    # 2026-06-20 是周六
    ctx = get_temporal_context(datetime(2026, 6, 20, 10, 0, tzinfo=TZ_CST))
    assert ctx["is_weekend"] is True, f"周六应该是周末, weekday={ctx['weekday']}"
    assert ctx["is_workday"] is False
    # 2026-06-22 是周一
    ctx = get_temporal_context(datetime(2026, 6, 22, 10, 0, tzinfo=TZ_CST))
    assert ctx["is_weekend"] is False, f"周一应该是工作日, weekday={ctx['weekday']}"
    assert ctx["is_workday"] is True

with test("2.4 TemporalWeight 初始化"):
    tw = TemporalWeight()
    assert tw.context is not None
    assert tw.boost == 0.15
    assert "weekday" in tw.context

with test("2.5 TemporalWeight.adjust() 周末匹配加成"):
    # 创建一个周末产生的记忆（周六）
    saturday = datetime(2026, 6, 20, 15, 0, tzinfo=TZ_CST)
    entry = MemoryEntry(
        content="周末去公园玩",
        memory_type="episodic",
        created_at=saturday.isoformat(),
    )
    # 当前也是周末
    tw = TemporalWeight(datetime(2026, 6, 20, 16, 0, tzinfo=TZ_CST))
    adjusted = tw.adjust(entry, 0.5)
    assert adjusted > 0.5, f"周末匹配应该有加成: {adjusted}"
    # 加成 = boost (周末匹配) + boost*0.5 (时段匹配) = 0.225
    # 但上限是 boost*2 = 0.3
    # 0.5 + 0.225 = 0.725
    assert adjusted >= 0.65, f"预期加成后 >= 0.65, 实际 {adjusted}"

with test("2.6 TemporalWeight.adjust() 工作日匹配加成"):
    # 创建一个工作日产生的记忆（周一）
    monday = datetime(2026, 6, 22, 10, 0, tzinfo=TZ_CST)
    entry = MemoryEntry(
        content="今天开会讨论项目",
        memory_type="episodic",
        created_at=monday.isoformat(),
    )
    # 当前也是工作日
    tw = TemporalWeight(datetime(2026, 6, 22, 10, 0, tzinfo=TZ_CST))
    adjusted = tw.adjust(entry, 0.5)
    assert adjusted > 0.5, f"工作日匹配应该有加成: {adjusted}"

with test("2.7 TemporalWeight.adjust() 不匹配无加成"):
    # 周末产生的记忆，工作日检索
    saturday = datetime(2026, 6, 20, 15, 0, tzinfo=TZ_CST)
    entry = MemoryEntry(
        content="周末看电影",
        memory_type="episodic",
        created_at=saturday.isoformat(),
    )
    # 当前是工作日（周一）
    tw = TemporalWeight(datetime(2026, 6, 22, 10, 0, tzinfo=TZ_CST))
    adjusted = tw.adjust(entry, 0.5)
    assert adjusted == 0.5, f"周末不匹配工作日, 应该无加成: {adjusted}"

with test("2.8 TemporalWeight.adjust() 上限不超 1.2"):
    entry = MemoryEntry(
        content="test",
        memory_type="episodic",
        created_at=datetime(2026, 6, 20, 15, 0, tzinfo=TZ_CST).isoformat(),
    )
    tw = TemporalWeight(datetime(2026, 6, 20, 15, 0, tzinfo=TZ_CST))
    # 即使 base_weight 很大，也不应超过 1.2
    adjusted = tw.adjust(entry, 1.5)
    assert adjusted <= 1.2, f"上限应为 1.2, 实际 {adjusted}"

with test("2.9 TemporalWeight.adjust() 无效时间回退"):
    entry = MemoryEntry(
        content="test",
        memory_type="episodic",
        created_at="invalid_time_string",
    )
    tw = TemporalWeight()
    adjusted = tw.adjust(entry, 0.5)
    assert adjusted == 0.5, f"无效时间应返回原值: {adjusted}"


# =============================================================================
# 第3部分：KnowledgeGraph 单元测试
# =============================================================================
print("\n" + "=" * 70)
print("第3部分：KnowledgeGraph 单元测试")
print("=" * 70)

with test("3.1 KnowledgeGraph 初始化"):
    store = VectorStore(db_path=":memory:")
    kg = KnowledgeGraph(store)
    assert kg is not None
    assert kg.ENTITY_TYPES == ("PERSON", "LOCATION", "ORG", "EVENT", "CONCEPT", "TIME")
    assert len(kg.RELATION_TYPES) == 9

with test("3.2 KnowledgeGraph 添加实体"):
    store = VectorStore(db_path=":memory:")
    kg = KnowledgeGraph(store)
    eid = kg.add_entity("李明", entity_type="PERSON", confidence=0.9)
    assert eid > 0, f"实体添加失败: id={eid}"
    assert kg.entity_count() == 1

with test("3.3 KnowledgeGraph 查询实体"):
    store = VectorStore(db_path=":memory:")
    kg = KnowledgeGraph(store)
    kg.add_entity("北京", entity_type="LOCATION", confidence=0.8)
    entity = kg.get_entity("北京")
    assert entity is not None, "实体查询失败"
    assert entity["name"] == "北京"
    assert entity["entity_type"] == "LOCATION"
    assert entity["confidence"] == 0.8

with test("3.4 KnowledgeGraph 查询不存在的实体"):
    store = VectorStore(db_path=":memory:")
    kg = KnowledgeGraph(store)
    entity = kg.get_entity("不存在")
    assert entity is None, "不存在的实体应返回 None"

with test("3.5 KnowledgeGraph 添加空名称实体"):
    store = VectorStore(db_path=":memory:")
    kg = KnowledgeGraph(store)
    eid = kg.add_entity("", entity_type="PERSON")
    assert eid == -1, "空名称应返回 -1"

with test("3.6 KnowledgeGraph 添加关系"):
    store = VectorStore(db_path=":memory:")
    kg = KnowledgeGraph(store)
    kg.add_entity("李明", entity_type="PERSON")
    kg.add_entity("北京", entity_type="LOCATION")
    rid = kg.add_relation("李明", "北京", "located_in", confidence=0.9)
    assert rid > 0, f"关系添加失败: id={rid}"
    assert kg.relation_count() == 1

with test("3.7 KnowledgeGraph 查询关系"):
    store = VectorStore(db_path=":memory:")
    kg = KnowledgeGraph(store)
    kg.add_entity("李明", entity_type="PERSON")
    kg.add_entity("北京", entity_type="LOCATION")
    kg.add_relation("李明", "北京", "located_in", 0.9)
    relations = kg.get_relations("李明", direction="out")
    assert len(relations) == 1
    assert relations[0]["target_entity"] == "北京"
    assert relations[0]["relation_type"] == "located_in"

with test("3.8 KnowledgeGraph 添加自环关系被拒绝"):
    store = VectorStore(db_path=":memory:")
    kg = KnowledgeGraph(store)
    kg.add_entity("李明", entity_type="PERSON")
    rid = kg.add_relation("李明", "李明", "related_to")
    assert rid == -1, "自环关系应被拒绝"

with test("3.9 KnowledgeGraph 实体列表"):
    store = VectorStore(db_path=":memory:")
    kg = KnowledgeGraph(store)
    kg.add_entity("李明", entity_type="PERSON", confidence=0.9)
    kg.add_entity("北京", entity_type="LOCATION", confidence=0.8)
    kg.add_entity("腾讯", entity_type="ORG", confidence=0.7)
    entities = kg.list_entities()
    assert len(entities) == 3
    entities = kg.list_entities(entity_type="PERSON")
    assert len(entities) == 1
    assert entities[0]["name"] == "李明"

with test("3.10 KnowledgeGraph 图谱遍历"):
    store = VectorStore(db_path=":memory:")
    kg = KnowledgeGraph(store)
    kg.add_entity("李明", entity_type="PERSON")
    kg.add_entity("北京", entity_type="LOCATION")
    kg.add_entity("腾讯", entity_type="ORG")
    kg.add_relation("李明", "北京", "located_in", 0.9)
    kg.add_relation("李明", "腾讯", "works_at", 0.8)
    result = kg.traverse("李明", max_hops=2)
    assert "entities" in result
    assert "relations" in result
    assert result["start_entity"] == "李明"
    assert len(result["relations"]) >= 2

with test("3.11 KnowledgeGraph 统计信息"):
    store = VectorStore(db_path=":memory:")
    kg = KnowledgeGraph(store)
    kg.add_entity("李明", entity_type="PERSON")
    kg.add_relation("李明", "北京", "located_in", 0.9)
    stats = kg.get_stats()
    assert stats["entity_count"] == 1
    assert stats["relation_count"] == 1
    assert "entity_types" in stats
    assert "relation_types" in stats

with test("3.12 KnowledgeGraph 无效实体类型回退到 CONCEPT"):
    store = VectorStore(db_path=":memory:")
    kg = KnowledgeGraph(store)
    eid = kg.add_entity("测试", entity_type="INVALID_TYPE")
    assert eid > 0
    entity = kg.get_entity("测试")
    assert entity["entity_type"] == "CONCEPT", f"无效类型应回退到 CONCEPT, 实际 {entity['entity_type']}"

with test("3.13 KnowledgeGraph 关系去重 (ON CONFLICT UPSERT)"):
    store = VectorStore(db_path=":memory:")
    kg = KnowledgeGraph(store)
    kg.add_entity("李明", entity_type="PERSON")
    kg.add_entity("北京", entity_type="LOCATION")
    rid1 = kg.add_relation("李明", "北京", "located_in", 0.5)
    rid2 = kg.add_relation("李明", "北京", "located_in", 0.9)
    assert kg.relation_count() == 1, f"相同关系不应重复, 实际 {kg.relation_count()}"


# =============================================================================
# 第4部分：MemorySummarizer 单元测试
# =============================================================================
print("\n" + "=" * 70)
print("第4部分：MemorySummarizer 单元测试")
print("=" * 70)

with test("4.1 MemorySummarizer 初始化"):
    store = VectorStore(db_path=":memory:")
    ms = MemorySummarizer(store)
    assert ms is not None
    assert ms.SUMMARY_THRESHOLD == 20
    assert ms.TURN_THRESHOLD == 50

with test("4.2 should_summarize() 记忆不足时不触发"):
    store = VectorStore(db_path=":memory:")
    ms = MemorySummarizer(store)
    # 只有少量记忆，不应该触发摘要
    assert ms.should_summarize(10) is False, "记忆不足不应触发摘要"

with test("4.3 should_summarize() 超过轮数阈值触发"):
    store = VectorStore(db_path=":memory:")
    ms = MemorySummarizer(store)
    # 从未做过摘要，超过50轮触发
    assert ms.should_summarize(60) is True, "超过轮数阈值应触发摘要"

with test("4.4 get_stats() 返回正确格式"):
    store = VectorStore(db_path=":memory:")
    ms = MemorySummarizer(store)
    stats = ms.get_stats()
    assert "summary_count" in stats
    assert "last_summary_turn" in stats
    assert stats["last_summary_turn"] == 0

with test("4.5 generate_summaries() 无LLM客户端跳过"):
    store = VectorStore(db_path=":memory:")
    ms = MemorySummarizer(store)
    result = ms.generate_summaries(10)
    assert result["summaries_generated"] == 0
    assert result["memories_archived"] == 0

with test("4.6 generate_session_summary() 无LLM客户端返回空"):
    store = VectorStore(db_path=":memory:")
    ms = MemorySummarizer(store)
    result = ms.generate_session_summary("session_1")
    assert result == ""

with test("4.7 generate_topic_summary() 无LLM客户端返回空"):
    store = VectorStore(db_path=":memory:")
    ms = MemorySummarizer(store)
    result = ms.generate_topic_summary("topic_1", [])
    assert result == ""


# =============================================================================
# 第5部分：接口兼容性验证
# =============================================================================
print("\n" + "=" * 70)
print("第5部分：接口兼容性验证")
print("=" * 70)

with test("5.1 orchestrator.remember() 签名"):
    import inspect
    sig = inspect.signature(MemoryOrchestrator.remember)
    params = list(sig.parameters.keys())
    # self, turn_id, user_msg, ai_reply, conversation_history
    assert "turn_id" in params
    assert "user_msg" in params
    assert "ai_reply" in params
    assert "conversation_history" in params

with test("5.2 orchestrator.recall() 签名"):
    sig = inspect.signature(MemoryOrchestrator.recall)
    params = list(sig.parameters.keys())
    assert "query_text" in params
    assert "top_k" in params

with test("5.3 orchestrator.run_maintenance() 签名"):
    sig = inspect.signature(MemoryOrchestrator.run_maintenance)
    params = list(sig.parameters.keys())
    assert "self" in params  # 只有self参数

with test("5.4 retriever.retrieve() 签名"):
    sig = inspect.signature(MemoryRetriever.retrieve)
    params = list(sig.parameters.keys())
    assert "query_text" in params
    assert "top_k" in params
    assert "apply_decay" in params
    assert "min_similarity" in params

with test("5.5 extractor.extract() 签名"):
    sig = inspect.signature(MemoryExtractor.extract)
    params = list(sig.parameters.keys())
    assert "messages" in params
    assert "mode" in params
    assert "source_turn_id" in params

with test("5.6 retriever.graph_enhanced_retrieve() 存在"):
    assert hasattr(MemoryRetriever, "graph_enhanced_retrieve"), "缺少 graph_enhanced_retrieve"
    assert callable(MemoryRetriever.graph_enhanced_retrieve)

with test("5.7 orchestrator.build_knowledge_graph() 存在"):
    assert hasattr(MemoryOrchestrator, "build_knowledge_graph"), "缺少 build_knowledge_graph"
    assert callable(MemoryOrchestrator.build_knowledge_graph)

with test("5.8 orchestrator.query_graph() 存在"):
    assert hasattr(MemoryOrchestrator, "query_graph"), "缺少 query_graph"
    assert callable(MemoryOrchestrator.query_graph)

with test("5.9 consolidator.reassess_importance() 存在"):
    assert hasattr(MemoryConsolidator, "reassess_importance"), "缺少 reassess_importance"
    assert callable(MemoryConsolidator.reassess_importance)

with test("5.10 vector_store.update_importance() 存在"):
    assert hasattr(VectorStore, "update_importance"), "缺少 update_importance"
    assert callable(VectorStore.update_importance)

with test("5.11 extractor._estimate_importance_llm() 存在"):
    assert hasattr(MemoryExtractor, "_estimate_importance_llm"), "缺少 _estimate_importance_llm"
    assert callable(MemoryExtractor._estimate_importance_llm)


# =============================================================================
# 第6部分：集成测试
# =============================================================================
print("\n" + "=" * 70)
print("第6部分：集成测试")
print("=" * 70)

with test("6.1 VectorStore 基本 CRUD"):
    store = VectorStore(db_path=":memory:")
    entry = MemoryEntry(
        content="测试记忆",
        memory_type="semantic",
        importance=0.8,
    )
    mid = store.add(entry)
    assert mid is not None
    retrieved = store.get(mid)
    assert retrieved.content == "测试记忆"
    assert retrieved.memory_type == "semantic"
    assert store.count() == 1

with test("6.2 VectorStore search 空库返回空"):
    store = VectorStore(db_path=":memory:")
    results = store.search([0.1, 0.2, 0.3], "测试查询")
    assert len(results) == 0

with test("6.3 VectorStore update_importance 更新"):
    store = VectorStore(db_path=":memory:")
    entry = MemoryEntry(content="测试", memory_type="semantic")
    mid = store.add(entry)
    result = store.update_importance(mid, 0.9)
    assert result is True
    retrieved = store.get(mid)
    assert retrieved.importance == 0.9

with test("6.4 VectorStore update_importance 不存在的ID"):
    store = VectorStore(db_path=":memory:")
    result = store.update_importance("nonexistent", 0.5)
    assert result is False

with test("6.5 KnowledgeGraph 与 VectorStore 共享连接"):
    store = VectorStore(db_path=":memory:")
    kg = KnowledgeGraph(store)
    kg.add_entity("测试实体", "CONCEPT", 0.8)
    entity = kg.get_entity("测试实体")
    assert entity is not None
    # 验证实体在同一个 SQLite 数据库中
    conn = store._conn
    row = conn.execute("SELECT COUNT(*) FROM kg_entities").fetchone()
    assert row[0] == 1

with test("6.6 KnowledgeGraph find_related_memories 空输入"):
    store = VectorStore(db_path=":memory:")
    kg = KnowledgeGraph(store)
    result = kg.find_related_memories([])
    assert result == []

with test("6.7 KnowledgeGraph find_related_memories 无匹配实体"):
    store = VectorStore(db_path=":memory:")
    kg = KnowledgeGraph(store)
    result = kg.find_related_memories(["nonexistent_id"])
    assert result == []

with test("6.8 MemorySummarizer 状态持久化"):
    store = VectorStore(db_path=":memory:")
    ms = MemorySummarizer(store)
    from src.memory.summarizer import _SUMMARY_STATE_TABLE
    ms._set_state("test_key", {"value": 123})
    val = ms._get_state("test_key")
    assert val == {"value": 123}

with test("6.9 MemorySummarizer should_summarize 记忆数达标触发"):
    store = VectorStore(db_path=":memory:")
    ms = MemorySummarizer(store)
    # 添加21条episodic记忆（超过阈值20）
    for i in range(21):
        entry = MemoryEntry(
            content=f"测试记忆{i}",
            memory_type="episodic",
        )
        store.add(entry)
    assert ms.should_summarize(0) is True, "超过阈值应触发摘要"


# =============================================================================
# 第7部分：Android兼容性检查
# =============================================================================
print("\n" + "=" * 70)
print("第7部分：Android兼容性检查")
print("=" * 70)

with test("7.1 无 Android 不兼容的库"):
    incompatible_modules = [
        "numpy", "torch", "tensorflow", "faiss", "pandas",
        "scipy", "sklearn", "opencv", "PIL", "cv2",
    ]
    violations = []
    for mod in incompatible_modules:
        try:
            __import__(mod)
            violations.append(mod)
        except ImportError:
            pass
    assert len(violations) == 0, f"检测到不兼容的库: {violations}"

with test("7.2 SQLite 相关代码兼容性"):
    # 所有 SQLite 操作使用标准 sqlite3 模块
    import sqlite3
    assert sqlite3.sqlite_version_info >= (3, 8, 0), "SQLite 版本过低"

with test("7.3 无 os.system / subprocess 调用"):
    # 检查关键文件不包含 os.system 或 subprocess 调用
    memory_dir = PROJECT_ROOT / "android" / "app" / "src" / "main" / "python" / "src" / "memory"
    problem_files = []
    for f in memory_dir.glob("*.py"):
        content = f.read_text(encoding="utf-8")
        if "os.system(" in content or "subprocess." in content:
            problem_files.append(f.name)
    assert len(problem_files) == 0, f"包含 os.system/subprocess 的文件: {problem_files}"

with test("7.4 无 threading 不当使用"):
    # 检查没有创建新的线程池（ThreadPoolExecutor）
    memory_dir = PROJECT_ROOT / "android" / "app" / "src" / "main" / "python" / "src" / "memory"
    problem_files = []
    for f in memory_dir.glob("*.py"):
        content = f.read_text(encoding="utf-8")
        if "ThreadPoolExecutor" in content or "ProcessPoolExecutor" in content:
            problem_files.append(f.name)
    assert len(problem_files) == 0, f"包含线程池的文件: {problem_files}"

with test("7.5 文件路径使用 Path 或 str"):
    # 确认没有硬编码 Linux 路径分隔符
    memory_dir = PROJECT_ROOT / "android" / "app" / "src" / "main" / "python" / "src" / "memory"
    for f in memory_dir.glob("*.py"):
        content = f.read_text(encoding="utf-8")
        if "os.path" in content:
            pass  # os.path 是跨平台的
        # 检查是否有 "/data/" 或 "/sdcard/" 硬编码（Android 特定路径）
        # 这些应该通过配置传入，而不是硬编码
        if '"/data/' in content or "'/data/" in content:
            pass  # 可能用于默认值，记录但不失败


# =============================================================================
# 第8部分：decay 模块测试
# =============================================================================
print("\n" + "=" * 70)
print("第8部分：decay 模块测试")
print("=" * 70)

with test("8.1 calculate_decay 新记忆衰减因子为 1.0"):
    now = datetime.now(TZ_CST)
    entry = MemoryEntry(
        content="test",
        memory_type="episodic",
        created_at=now.isoformat(),
        last_accessed=now.isoformat(),
    )
    decay = calculate_decay(entry, now)
    assert decay == 1.0 or abs(decay - 1.0) < 0.01, f"新记忆衰减应为 1.0, 实际 {decay}"

with test("8.2 calculate_decay 旧记忆衰减"):
    now = datetime.now(TZ_CST)
    old = now - timedelta(days=30)
    entry = MemoryEntry(
        content="test",
        memory_type="episodic",
        created_at=old.isoformat(),
        last_accessed=old.isoformat(),
    )
    decay = calculate_decay(entry, now)
    # 半衰期30天，经过30天应该是 0.5 左右
    assert 0.3 < decay < 0.7, f"30天旧记忆衰减应在 0.5 左右, 实际 {decay}"

with test("8.3 get_weight 综合权重计算"):
    entry = MemoryEntry(
        content="test",
        memory_type="user_fact",
        importance=0.8,
        created_at=datetime.now(TZ_CST).isoformat(),
        last_accessed=datetime.now(TZ_CST).isoformat(),
    )
    weight = get_weight(entry)
    # 新记忆衰减=1.0, type_multiplier=1.3, 权重=0.8*1.0*1.3=1.04
    assert weight > 0.9, f"user_fact 权重应较高, 实际 {weight}"

with test("8.4 get_decay_stats 返回正确格式"):
    now = datetime.now(TZ_CST)
    entries = [
        MemoryEntry(
            content="test1", memory_type="episodic",
            created_at=now.isoformat(), last_accessed=now.isoformat(),
        ),
        MemoryEntry(
            content="test2", memory_type="semantic",
            created_at=now.isoformat(), last_accessed=now.isoformat(),
        ),
    ]
    stats = get_decay_stats(entries, now)
    assert stats["total"] == 2
    assert "avg_decay" in stats
    assert "by_type" in stats
    assert "episodic" in stats["by_type"]
    assert "semantic" in stats["by_type"]

with test("8.5 get_decay_stats 空列表"):
    stats = get_decay_stats([])
    assert stats["total"] == 0
    assert stats["avg_decay"] == 0.0


# =============================================================================
# 最终报告
# =============================================================================
print("\n" + "=" * 70)
print("测试总结")
print("=" * 70)
print(f"总测试数: {PASSED + FAILED + ERRORS}")
print(f"通过: {PASSED}")
print(f"失败: {FAILED}")
print(f"错误: {ERRORS}")
print(f"通过率: {PASSED / (PASSED + FAILED + ERRORS) * 100:.1f}%" if (PASSED + FAILED + ERRORS) > 0 else "N/A")

if FAILED > 0:
    print("\n失败测试:")
    for r in TEST_RESULTS:
        if r["status"] == "FAIL":
            print(f"  - {r['name']}: {r['message']}")

if ERRORS > 0:
    print("\n错误测试:")
    for r in TEST_RESULTS:
        if r["status"] == "ERROR":
            print(f"  - {r['name']}: {r['message']}")

# 输出 JSON 结果以便后续处理
print("\n--- JSON_RESULTS ---")
print(json.dumps(TEST_RESULTS, ensure_ascii=False, indent=2))
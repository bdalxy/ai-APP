"""知识图谱自动构建模块。

从现有记忆中自动提取实体和关系，构建记忆知识图谱。
支持查询实体、关系、图谱遍历，用于增强检索。

核心类：
    - KnowledgeGraph: 知识图谱主类，管理实体和关系的存储与查询

实体类型：
    - PERSON: 人物
    - LOCATION: 地点
    - ORG: 组织
    - EVENT: 事件
    - CONCEPT: 概念
    - TIME: 时间

关系类型（扩展自 memory_relations 表）：
    - has_property, located_in, part_of, works_at, knows,
      occurred_at, causes, related_to, same_as

依赖：
    - src.memory.vector_store: VectorStore, MemoryEntry
    - src.api_client.deepseek: DeepSeekClient（LLM 实体/关系提取）
    - src.utils.logger: get_logger 日志实例
    - src.utils.time_utils: format_timestamp_iso 时间格式化
"""

from __future__ import annotations

import json
import re
import sqlite3
from typing import Any

from src.memory.vector_store import MemoryEntry, VectorStore
from src.utils.logger import get_logger
from src.utils.time_utils import format_timestamp_iso


# =============================================================================
# 知识图谱 LLM 提取 Prompt
# =============================================================================

_ENTITY_EXTRACTION_PROMPT = """你是一个知识图谱实体提取助手。请从以下记忆中提取实体。

实体类型：
- PERSON: 具体人物（如"李明"、"张三"）
- LOCATION: 地点（如"北京"、"上海"）
- ORG: 组织（如"腾讯"、"北京大学"）
- EVENT: 事件（如"生日派对"、"毕业典礼"）
- CONCEPT: 抽象概念（如"幸福"、"自由"）
- TIME: 时间点（如"2024年1月"、"上周"）

记忆内容：
{memories}

请以 JSON 数组格式输出，每个元素包含：
- "name": 实体名称
- "entity_type": 实体类型（PERSON/LOCATION/ORG/EVENT/CONCEPT/TIME）
- "confidence": 提取置信度（0.0~1.0）

如果没有明确的实体，返回空数组：[]。

请只输出 JSON 数组，不要包含其他内容。"""

_RELATION_EXTRACTION_PROMPT = """你是一个知识图谱关系提取助手。请从以下记忆和实体列表中提取实体间的关系。

可用关系类型：
- has_property: 拥有属性（如"李明 has_property 27岁"）
- located_in: 位于（如"李明 located_in 北京"）
- part_of: 属于（如"张三 part_of 腾讯"）
- works_at: 工作于（如"李明 works_at 腾讯"）
- knows: 认识（如"李明 knows 张三"）
- occurred_at: 发生于（如"毕业典礼 occurred_at 2024年"）
- causes: 导致（如"失恋 causes 难过"）
- related_to: 相关（通用关系）
- same_as: 等同（同一实体的不同名称）

实体列表：
{entities}

记忆内容：
{memories}

请以 JSON 数组格式输出，每个元素包含：
- "source": 源实体名称
- "target": 目标实体名称
- "relation": 关系类型
- "confidence": 置信度（0.0~1.0）

如果没有明确的关系，返回空数组：[]。

请只输出 JSON 数组，不要包含其他内容。"""


# =============================================================================
# 知识图谱表结构
# =============================================================================

_KG_ENTITIES_TABLE = "kg_entities"
_KG_RELATIONS_TABLE = "kg_relations"

_CREATE_KG_ENTITIES_SQL = f"""
CREATE TABLE IF NOT EXISTS {_KG_ENTITIES_TABLE} (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    entity_type TEXT NOT NULL DEFAULT 'CONCEPT',
    properties TEXT NOT NULL DEFAULT '{{}}',
    confidence REAL NOT NULL DEFAULT 0.5,
    extracted_from TEXT NOT NULL DEFAULT '',
    created_at TEXT NOT NULL DEFAULT '',
    updated_at TEXT NOT NULL DEFAULT ''
)
"""

_CREATE_KG_RELATIONS_SQL = f"""
CREATE TABLE IF NOT EXISTS {_KG_RELATIONS_TABLE} (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    source_entity TEXT NOT NULL,
    target_entity TEXT NOT NULL,
    relation_type TEXT NOT NULL DEFAULT 'related_to',
    confidence REAL NOT NULL DEFAULT 0.5,
    extracted_from TEXT NOT NULL DEFAULT '',
    created_at TEXT NOT NULL DEFAULT ''
)
"""

_CREATE_KG_INDEXES_SQL = [
    f"CREATE INDEX IF NOT EXISTS idx_kg_entities_name ON {_KG_ENTITIES_TABLE}(name)",
    f"CREATE INDEX IF NOT EXISTS idx_kg_entities_type ON {_KG_ENTITIES_TABLE}(entity_type)",
    f"CREATE INDEX IF NOT EXISTS idx_kg_relations_source ON {_KG_RELATIONS_TABLE}(source_entity)",
    f"CREATE INDEX IF NOT EXISTS idx_kg_relations_target ON {_KG_RELATIONS_TABLE}(target_entity)",
    f"CREATE UNIQUE INDEX IF NOT EXISTS idx_kg_entities_name_unique "
    f"ON {_KG_ENTITIES_TABLE}(name)",
    f"CREATE UNIQUE INDEX IF NOT EXISTS idx_kg_relations_unique "
    f"ON {_KG_RELATIONS_TABLE}(source_entity, target_entity, relation_type)",
]


class KnowledgeGraph:
    """知识图谱管理器。

    从记忆中自动提取实体和关系，构建知识图谱。
    支持实体查询、关系查询、图谱遍历。

    Attributes:
        _store: 向量存储实例（共享 SQLite 连接）。
        _client: DeepSeek API 客户端（可选，用于 LLM 提取）。
        _initialized: 表是否已初始化。
    """

    # 实体类型中文映射
    ENTITY_TYPES: tuple[str, ...] = (
        "PERSON", "LOCATION", "ORG", "EVENT", "CONCEPT", "TIME",
    )

    # 关系类型
    RELATION_TYPES: tuple[str, ...] = (
        "has_property", "located_in", "part_of", "works_at", "knows",
        "occurred_at", "causes", "related_to", "same_as",
    )

    def __init__(
        self,
        vector_store: VectorStore,
        deepseek_client: Any = None,
    ) -> None:
        """初始化知识图谱。

        Args:
            vector_store: 向量存储实例（共享 SQLite 连接）。
            deepseek_client: DeepSeek API 客户端（可选）。
        """
        self._store = vector_store
        self._client = deepseek_client
        self._log = get_logger()
        self._initialized = False
        self._log.info("KnowledgeGraph 初始化完成")

    # =========================================================================
    # 表初始化
    # =========================================================================

    def _ensure_tables(self) -> None:
        """确保知识图谱表存在（懒初始化）。"""
        if self._initialized:
            return
        try:
            with self._store.lock:
                conn = self._store._conn
                conn.execute(_CREATE_KG_ENTITIES_SQL)
                conn.execute(_CREATE_KG_RELATIONS_SQL)
                for idx_sql in _CREATE_KG_INDEXES_SQL:
                    try:
                        conn.execute(idx_sql)
                    except sqlite3.Error as e:
                        self._log.warning(f"[知识图谱] 索引创建失败（不影响功能）: {e}")
                conn.commit()
            self._initialized = True
            self._log.info("[知识图谱] 表初始化完成")
        except sqlite3.Error as e:
            self._log.warning(f"[知识图谱] 表初始化失败: {e}")

    # =========================================================================
    # 实体管理
    # =========================================================================

    def add_entity(
        self,
        name: str,
        entity_type: str = "CONCEPT",
        confidence: float = 0.5,
        properties: dict[str, str] | None = None,
        extracted_from: str = "",
    ) -> int:
        """添加或更新实体。

        Args:
            name: 实体名称。
            entity_type: 实体类型。
            confidence: 置信度。
            properties: 实体属性字典。
            extracted_from: 提取来源记忆 ID。

        Returns:
            实体 ID。
        """
        self._ensure_tables()
        name = name.strip()
        if not name:
            return -1
        if entity_type not in self.ENTITY_TYPES:
            entity_type = "CONCEPT"
        now = format_timestamp_iso()
        props_json = json.dumps(properties or {}, ensure_ascii=False)
        with self._store.lock:
            conn = self._store._conn
            sql = (
                f"INSERT INTO {_KG_ENTITIES_TABLE} "
                "(name, entity_type, properties, confidence, extracted_from, created_at, updated_at) "
                "VALUES (?, ?, ?, ?, ?, ?, ?) "
                "ON CONFLICT(name) DO UPDATE SET "
                "confidence = MAX(confidence, excluded.confidence), "
                "properties = excluded.properties, "
                "updated_at = excluded.updated_at"
            )
            try:
                cursor = conn.execute(
                    sql, (name, entity_type, props_json, confidence, extracted_from, now, now)
                )
                conn.commit()
                self._log.debug(f"[知识图谱] 添加实体: {name} ({entity_type})")
                return cursor.lastrowid
            except sqlite3.Error as e:
                self._log.debug(f"[知识图谱] 添加实体失败: {e}")
                return -1

    def get_entity(self, name: str) -> dict | None:
        """查询实体。

        Args:
            name: 实体名称。

        Returns:
            实体字典，不存在返回 None。
        """
        self._ensure_tables()
        conn = self._store._conn
        sql = f"SELECT * FROM {_KG_ENTITIES_TABLE} WHERE name = ?"
        try:
            row = conn.execute(sql, (name,)).fetchone()
            if row:
                return {
                    "id": row[0], "name": row[1], "entity_type": row[2],
                    "properties": json.loads(row[3]) if row[3] else {},
                    "confidence": row[4], "extracted_from": row[5],
                    "created_at": row[6], "updated_at": row[7],
                }
            return None
        except sqlite3.Error as e:
            self._log.debug(f"[知识图谱] 查询实体失败: {e}")
            return None

    def list_entities(
        self,
        entity_type: str = "",
        limit: int = 100,
    ) -> list[dict]:
        """列出实体。

        Args:
            entity_type: 实体类型过滤（空字符串表示全部）。
            limit: 返回上限。

        Returns:
            实体字典列表。
        """
        self._ensure_tables()
        conn = self._store._conn
        if entity_type and entity_type in self.ENTITY_TYPES:
            sql = (
                f"SELECT * FROM {_KG_ENTITIES_TABLE} "
                "WHERE entity_type = ? ORDER BY confidence DESC LIMIT ?"
            )
            rows = conn.execute(sql, (entity_type, limit)).fetchall()
        else:
            sql = (
                f"SELECT * FROM {_KG_ENTITIES_TABLE} "
                "ORDER BY confidence DESC LIMIT ?"
            )
            rows = conn.execute(sql, (limit,)).fetchall()
        return [
            {
                "id": r[0], "name": r[1], "entity_type": r[2],
                "properties": json.loads(r[3]) if r[3] else {},
                "confidence": r[4], "extracted_from": r[5],
                "created_at": r[6], "updated_at": r[7],
            }
            for r in rows
        ]

    def entity_count(self) -> int:
        """获取实体总数。"""
        self._ensure_tables()
        conn = self._store._conn
        try:
            row = conn.execute(
                f"SELECT COUNT(*) FROM {_KG_ENTITIES_TABLE}"
            ).fetchone()
            return row[0] if row else 0
        except sqlite3.Error:
            return 0

    # =========================================================================
    # 关系管理
    # =========================================================================

    def add_relation(
        self,
        source_entity: str,
        target_entity: str,
        relation_type: str = "related_to",
        confidence: float = 0.5,
        extracted_from: str = "",
    ) -> int:
        """添加实体间关系。

        Args:
            source_entity: 源实体名称。
            target_entity: 目标实体名称。
            relation_type: 关系类型。
            confidence: 置信度。
            extracted_from: 提取来源记忆 ID。

        Returns:
            关系 ID。
        """
        self._ensure_tables()
        source_entity = source_entity.strip()
        target_entity = target_entity.strip()
        if not source_entity or not target_entity or source_entity == target_entity:
            return -1
        if relation_type not in self.RELATION_TYPES:
            relation_type = "related_to"
        now = format_timestamp_iso()
        with self._store.lock:
            conn = self._store._conn
            sql = (
                f"INSERT INTO {_KG_RELATIONS_TABLE} "
                "(source_entity, target_entity, relation_type, confidence, extracted_from, created_at) "
                "VALUES (?, ?, ?, ?, ?, ?) "
                "ON CONFLICT(source_entity, target_entity, relation_type) DO UPDATE SET "
                "confidence = MAX(confidence, excluded.confidence)"
            )
            try:
                cursor = conn.execute(
                    sql, (source_entity, target_entity, relation_type, confidence, extracted_from, now)
                )
                conn.commit()
                self._log.debug(
                    f"[知识图谱] 添加关系: {source_entity} --{relation_type}--> {target_entity}"
                )
                return cursor.lastrowid
            except sqlite3.Error as e:
                self._log.debug(f"[知识图谱] 添加关系失败: {e}")
                return -1

    def get_relations(
        self,
        entity_name: str,
        direction: str = "both",
        limit: int = 50,
    ) -> list[dict]:
        """查询实体的关系。

        Args:
            entity_name: 实体名称。
            direction: 方向，"out"（出边）/ "in"（入边）/ "both"（双向）。
            limit: 返回上限。

        Returns:
            关系字典列表。
        """
        self._ensure_tables()
        conn = self._store._conn
        if direction == "out":
            sql = (
                f"SELECT * FROM {_KG_RELATIONS_TABLE} "
                "WHERE source_entity = ? ORDER BY confidence DESC LIMIT ?"
            )
            rows = conn.execute(sql, (entity_name, limit)).fetchall()
        elif direction == "in":
            sql = (
                f"SELECT * FROM {_KG_RELATIONS_TABLE} "
                "WHERE target_entity = ? ORDER BY confidence DESC LIMIT ?"
            )
            rows = conn.execute(sql, (entity_name, limit)).fetchall()
        else:
            sql = (
                f"SELECT * FROM {_KG_RELATIONS_TABLE} "
                "WHERE source_entity = ? OR target_entity = ? "
                "ORDER BY confidence DESC LIMIT ?"
            )
            rows = conn.execute(sql, (entity_name, entity_name, limit)).fetchall()
        return [
            {
                "id": r[0], "source_entity": r[1], "target_entity": r[2],
                "relation_type": r[3], "confidence": r[4],
                "extracted_from": r[5], "created_at": r[6],
            }
            for r in rows
        ]

    def relation_count(self) -> int:
        """获取关系总数。"""
        self._ensure_tables()
        conn = self._store._conn
        try:
            row = conn.execute(
                f"SELECT COUNT(*) FROM {_KG_RELATIONS_TABLE}"
            ).fetchone()
            return row[0] if row else 0
        except sqlite3.Error:
            return 0

    # =========================================================================
    # 图谱遍历：从实体出发，沿关系图遍历 N 跳
    # =========================================================================

    def traverse(
        self,
        start_entity: str,
        max_hops: int = 2,
        relation_types: list[str] | None = None,
        min_confidence: float = 0.3,
    ) -> dict[str, Any]:
        """从起始实体出发，沿关系图遍历。

        Args:
            start_entity: 起始实体名称。
            max_hops: 最大跳数（1-3）。
            relation_types: 允许的关系类型过滤。
            min_confidence: 最低置信度。

        Returns:
            遍历结果字典: {"entities": [...], "relations": [...], "paths": [...]}
        """
        self._ensure_tables()
        max_hops = max(1, min(max_hops, 3))
        conn = self._store._conn

        visited_entities: set[str] = {start_entity}
        all_relations: list[dict] = []
        frontier: set[str] = {start_entity}

        for hop in range(max_hops):
            if not frontier:
                break
            next_frontier: set[str] = set()
            # 批量查询当前 front 的所有关系
            placeholders = ",".join("?" for _ in frontier)
            where_clause = (
                f"WHERE (source_entity IN ({placeholders}) OR target_entity IN ({placeholders})) "
                f"AND confidence >= ?"
            )
            params = list(frontier) + list(frontier) + [min_confidence]

            if relation_types:
                type_placeholders = ",".join("?" for _ in relation_types)
                where_clause += (
                    f" AND relation_type IN ({type_placeholders})"
                )
                params.extend(relation_types)

            sql = (
                f"SELECT * FROM {_KG_RELATIONS_TABLE} {where_clause} "
                "ORDER BY confidence DESC LIMIT 200"
            )
            try:
                rows = conn.execute(sql, params).fetchall()
            except sqlite3.Error:
                break

            for row in rows:
                rel = {
                    "id": row[0], "source_entity": row[1], "target_entity": row[2],
                    "relation_type": row[3], "confidence": row[4],
                    "extracted_from": row[5], "created_at": row[6],
                }
                all_relations.append(rel)
                if rel["source_entity"] not in visited_entities:
                    next_frontier.add(rel["source_entity"])
                    visited_entities.add(rel["source_entity"])
                if rel["target_entity"] not in visited_entities:
                    next_frontier.add(rel["target_entity"])
                    visited_entities.add(rel["target_entity"])

            frontier = next_frontier

        # 查询所有访问过的实体详情
        entities: list[dict] = []
        if visited_entities:
            placeholders = ",".join("?" for _ in visited_entities)
            sql = (
                f"SELECT * FROM {_KG_ENTITIES_TABLE} "
                f"WHERE name IN ({placeholders})"
            )
            try:
                rows = conn.execute(sql, list(visited_entities)).fetchall()
                entities = [
                    {
                        "id": r[0], "name": r[1], "entity_type": r[2],
                        "properties": json.loads(r[3]) if r[3] else {},
                        "confidence": r[4], "extracted_from": r[5],
                    }
                    for r in rows
                ]
            except sqlite3.Error:
                pass

        return {
            "entities": entities,
            "relations": all_relations,
            "start_entity": start_entity,
            "hops": max_hops,
        }

    # =========================================================================
    # LLM 驱动的实体和关系提取
    # =========================================================================

    def extract_from_memories(
        self,
        entries: list[MemoryEntry],
    ) -> dict[str, int]:
        """使用 LLM 从记忆列表中提取实体和关系。

        Args:
            entries: 记忆条目列表。

        Returns:
            统计字典: {"entities_added": int, "relations_added": int}。
        """
        if not self._client or not entries:
            return {"entities_added": 0, "relations_added": 0}

        # 构建记忆文本
        memory_texts = [
            f"[{e.memory_type}] {e.content}"
            for e in entries[:20]  # 限制数量，避免 prompt 过长
        ]
        memory_text = "\n".join(memory_texts)

        entities_added = 0
        relations_added = 0

        # 1. 提取实体
        try:
            entities = self._extract_entities_by_llm(memory_text)
            for ent in entities:
                eid = self.add_entity(
                    name=ent.get("name", ""),
                    entity_type=ent.get("entity_type", "CONCEPT"),
                    confidence=float(ent.get("confidence", 0.5)),
                    extracted_from=entries[0].id if entries else "",
                )
                if eid > 0:
                    entities_added += 1
            self._log.info(
                f"[知识图谱] LLM 提取 {len(entities)} 个实体, 新增 {entities_added}"
            )
        except Exception as e:
            self._log.warning(f"[知识图谱] 实体提取失败: {e}")

        # 2. 提取关系（需要先有实体列表）
        if entities_added > 0:
            try:
                entity_names = [e.get("name", "") for e in entities if e.get("name")]
                relations = self._extract_relations_by_llm(memory_text, entity_names)
                for rel in relations:
                    rid = self.add_relation(
                        source_entity=rel.get("source", ""),
                        target_entity=rel.get("target", ""),
                        relation_type=rel.get("relation", "related_to"),
                        confidence=float(rel.get("confidence", 0.5)),
                        extracted_from=entries[0].id if entries else "",
                    )
                    if rid > 0:
                        relations_added += 1
                self._log.info(
                    f"[知识图谱] LLM 提取 {len(relations)} 个关系, 新增 {relations_added}"
                )
            except Exception as e:
                self._log.warning(f"[知识图谱] 关系提取失败: {e}")

        return {
            "entities_added": entities_added,
            "relations_added": relations_added,
        }

    def _extract_entities_by_llm(self, memory_text: str) -> list[dict]:
        """调用 LLM 提取实体。"""
        if not self._client:
            return []
        prompt = _ENTITY_EXTRACTION_PROMPT.replace("{memories}", memory_text)
        response = self._client.chat(
            messages=[{"role": "user", "content": prompt}],
            temperature=0.3,
            max_tokens=1000,
        )
        raw = response.content.strip()
        # 清理 markdown 代码块
        if raw.startswith("```"):
            raw = re.sub(r"^```(?:json)?\s*", "", raw)
            raw = re.sub(r"\s*```$", "", raw)
        try:
            items = json.loads(raw)
            if isinstance(items, list):
                return items
            return []
        except json.JSONDecodeError:
            # 尝试正则提取
            match = re.search(r"\[[\s\S]*\]", raw)
            if match:
                try:
                    return json.loads(match.group(0))
                except json.JSONDecodeError:
                    pass
            return []

    def _extract_relations_by_llm(
        self, memory_text: str, entity_names: list[str]
    ) -> list[dict]:
        """调用 LLM 提取实体间关系。"""
        if not self._client or len(entity_names) < 2:
            return []
        prompt = _RELATION_EXTRACTION_PROMPT.replace(
            "{memories}", memory_text
        ).replace("{entities}", json.dumps(entity_names, ensure_ascii=False))
        response = self._client.chat(
            messages=[{"role": "user", "content": prompt}],
            temperature=0.3,
            max_tokens=1000,
        )
        raw = response.content.strip()
        if raw.startswith("```"):
            raw = re.sub(r"^```(?:json)?\s*", "", raw)
            raw = re.sub(r"\s*```$", "", raw)
        try:
            items = json.loads(raw)
            if isinstance(items, list):
                return items
            return []
        except json.JSONDecodeError:
            match = re.search(r"\[[\s\S]*\]", raw)
            if match:
                try:
                    return json.loads(match.group(0))
                except json.JSONDecodeError:
                    pass
            return []

    # =========================================================================
    # 图谱增强记忆检索：找到与记忆关联的其他记忆
    # =========================================================================

    def find_related_memories(
        self,
        memory_ids: list[str],
        max_relations: int = 20,
    ) -> list[dict]:
        """通过记忆的实体关系，找到关联的记忆。

        流程：
            1. 找到每条记忆提取的实体
            2. 遍历实体间的关系（1-2跳）
            3. 找到关联实体对应的其他记忆
            4. 合并去重

        Args:
            memory_ids: 源记忆 ID 列表。
            max_relations: 最大返回关联记忆数。

        Returns:
            关联记忆的 (memory_id, relation_type, confidence) 列表。
        """
        self._ensure_tables()
        if not memory_ids:
            return []

        conn = self._store._conn

        # 1. 找到源记忆提取的实体
        placeholders = ",".join("?" for _ in memory_ids)
        # extracted_from 字段存储的是记忆 ID
        entity_sql = (
            f"SELECT DISTINCT name FROM {_KG_ENTITIES_TABLE} "
            f"WHERE extracted_from IN ({placeholders})"
        )
        try:
            rows = conn.execute(entity_sql, memory_ids).fetchall()
        except sqlite3.Error:
            return []
        source_entities = [r[0] for r in rows]
        if not source_entities:
            return []

        # 2. 遍历关系到 2 跳
        related_entities: set[str] = set()
        entity_relations: dict[str, list[tuple[str, str, float]]] = {}
        frontier = set(source_entities)

        for _ in range(2):
            if not frontier:
                break
            next_frontier: set[str] = set()
            placeholders = ",".join("?" for _ in frontier)
            rel_sql = (
                f"SELECT source_entity, target_entity, relation_type, confidence "
                f"FROM {_KG_RELATIONS_TABLE} "
                f"WHERE source_entity IN ({placeholders}) OR target_entity IN ({placeholders})"
            )
            try:
                rel_rows = conn.execute(rel_sql, list(frontier) + list(frontier)).fetchall()
            except sqlite3.Error:
                break
            for src, tgt, rtype, conf in rel_rows:
                if src not in source_entities and src not in related_entities:
                    if src not in source_entities:
                        next_frontier.add(src)
                        related_entities.add(src)
                if tgt not in source_entities and tgt not in related_entities:
                    if tgt not in source_entities:
                        next_frontier.add(tgt)
                        related_entities.add(tgt)
                # 记录关系
                for ent in source_entities:
                    if src == ent:
                        entity_relations.setdefault(tgt, []).append((ent, rtype, conf))
                    if tgt == ent:
                        entity_relations.setdefault(src, []).append((ent, rtype, conf))
            frontier = next_frontier

        if not related_entities:
            return []

        # 3. 找到关联实体对应的记忆
        placeholders = ",".join("?" for _ in related_entities)
        mem_sql = (
            f"SELECT DISTINCT name, extracted_from, confidence "
            f"FROM {_KG_ENTITIES_TABLE} "
            f"WHERE name IN ({placeholders})"
        )
        try:
            mem_rows = conn.execute(mem_sql, list(related_entities)).fetchall()
        except sqlite3.Error:
            return []

        related_memories: list[dict] = []
        seen_ids: set[str] = set()
        for ent_name, mem_id, conf in mem_rows:
            if not mem_id or mem_id in memory_ids or mem_id in seen_ids:
                continue
            seen_ids.add(mem_id)
            rels = entity_relations.get(ent_name, [])
            rel_type = rels[0][1] if rels else "related_to"
            rel_conf = max(r[2] for r in rels) if rels else conf
            related_memories.append({
                "memory_id": mem_id,
                "relation_type": rel_type,
                "confidence": rel_conf,
                "via_entity": ent_name,
            })
            if len(related_memories) >= max_relations:
                break

        return related_memories

    # =========================================================================
    # 统计信息
    # =========================================================================

    def get_stats(self) -> dict:
        """获取知识图谱统计信息。

        Returns:
            统计字典。
        """
        return {
            "entity_count": self.entity_count(),
            "relation_count": self.relation_count(),
            "entity_types": self.ENTITY_TYPES,
            "relation_types": self.RELATION_TYPES,
        }
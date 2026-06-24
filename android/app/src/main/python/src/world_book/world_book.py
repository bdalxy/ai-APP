# -*- coding: utf-8 -*-
"""
世界书 (World Book) 模块
实现 Tavo 兼容格式的世界书加载、正则匹配引擎和条目管理

JSON Schema (Tavo 兼容扩展):
{
  "version": "1.0",
  "name": "世界观名称",
  "description": "世界观描述",
  "entries": [
    {
      "id": "entry_001",
      "keys": ["关键词1", "关键词2"],           // 主关键词 (OR 逻辑匹配)
      "key_secondary": ["次要关键词"],          // 次要关键词 (AND 逻辑匹配)
      "content": "触发时注入的上下文内容",
      "comment": "备注说明",
      "constant": false,                       // true=始终注入, false=触发时注入
      "selective": true,                       // true=选择性注入, false=强制注入
      "probability": 100,                      // 触发概率 (0-100)
      "priority": 0,                           // 优先级 (越高越优先, 冲突时使用)
      "regex_keys": ["正则表达式"],             // 正则匹配模式 (可选)
      "case_sensitive": false,                  // 是否区分大小写
      "max_injections": 5,                     // 单次对话最大注入次数
      "cooldown_rounds": 0,                    // 冷却轮数 (0=无冷却)
      "created_at": 1234567890.0               // 创建时间戳 (同优先级倒序排列)
    }
  ]
}
"""

import json
import os
import random
import re
import threading
import time
from typing import Dict, Any, Optional, List, Set, Tuple
from pathlib import Path
from dataclasses import dataclass, field

from src.utils.logger import get_logger

logger = get_logger()


# ============================================================
# 数据模型
# ============================================================

@dataclass
class WorldBookEntry:
    """世界书条目"""
    id: str
    keys: List[str] = field(default_factory=list)
    key_secondary: List[str] = field(default_factory=list)
    content: str = ""
    comment: str = ""
    constant: bool = False
    selective: bool = True
    probability: int = 100
    priority: int = 0
    regex_keys: List[str] = field(default_factory=list)
    case_sensitive: bool = False
    max_injections: int = 5
    cooldown_rounds: int = 0
    created_at: float = 0.0  # 创建时间戳，同优先级时倒序排列

    # 运行时状态 (非持久化)
    _injection_count: int = 0
    _last_round: int = -1

    def can_inject(self, current_round: int) -> bool:
        """检查是否可以注入 (冷却检查)"""
        if self._injection_count >= self.max_injections:
            return False
        if self.cooldown_rounds > 0 and self._last_round >= 0:
            if current_round - self._last_round < self.cooldown_rounds:
                return False
        return True

    def record_injection(self, current_round: int) -> None:
        """记录一次注入"""
        self._injection_count += 1
        self._last_round = current_round

    def reset_state(self) -> None:
        """重置运行时状态"""
        self._injection_count = 0
        self._last_round = -1


@dataclass
class WorldBook:
    """世界书"""
    name: str
    description: str = ""
    version: str = "1.0"
    entries: List[WorldBookEntry] = field(default_factory=list)
    source_path: str = ""

    def __post_init__(self):
        self._lock = threading.Lock()

    def get_constant_entries(self) -> List[WorldBookEntry]:
        """获取始终注入的条目"""
        return [e for e in self.entries if e.constant]

    def get_triggered_entries(
        self,
        text: str,
        current_round: int = 0,
        max_entries: int = 5
    ) -> List[WorldBookEntry]:
        """
        获取触发匹配的条目

        排序规则:
        1. 匹配类型: 精确匹配(0) > 模糊匹配(1) > 正则匹配(2)
        2. priority 数值降序 (越高越优先)
        3. created_at 时间戳降序 (越新越优先)

        Args:
            text: 当前对话文本
            current_round: 当前对话轮次
            max_entries: 最大返回条目数 (默认5)

        Returns:
            匹配的条目列表 (按优先级规则排序)
        """
        matched: List[Tuple[int, int, float, WorldBookEntry]] = []  # (match_type, priority, created_at, entry)

        for entry in self.entries:
            if entry.constant:
                continue  # 常量条目由 get_constant_entries 处理

            if not entry.can_inject(current_round):
                continue

            match_type = self._match_entry(entry, text)
            if match_type >= 0:
                # 优先级取负值以实现降序排列（Python sort 默认升序）
                matched.append((match_type, -entry.priority, -entry.created_at, entry))

        # 多级排序: match_type 升序 → priority 降序 → created_at 降序
        matched.sort(key=lambda x: (x[0], x[1], x[2]))

        # 取前 max_entries 个
        result = [entry for _, _, _, entry in matched[:max_entries]]

        # 记录注入
        for entry in result:
            entry.record_injection(current_round)

        return result

    def _match_entry(self, entry: WorldBookEntry, text: str) -> int:
        """
        匹配单条条目

        返回匹配类型:
        -1: 不匹配
         0: 精确匹配 (最佳)
         1: 模糊匹配
         2: 正则匹配

        匹配逻辑:
        1. 正则匹配: regex_keys 中任意一个匹配成功 → 返回 2
        2. 关键词匹配:
           - keys (主关键词): 任意一个匹配 (OR 逻辑)
           - key_secondary (次要关键词): 如果存在, 则必须全部匹配 (AND 逻辑)
           - 匹配类型取所有关键词中的最佳值 (精确 > 模糊)
        3. 概率检查: 匹配成功后按 probability 概率触发
        """
        # 步骤1: 正则匹配
        if entry.regex_keys:
            for pattern in entry.regex_keys:
                try:
                    # ReDoS 防护：检查正则长度和复杂度
                    if len(pattern) > _MAX_REGEX_LENGTH:
                        logger.warning(f"[世界书] 正则过长 ({len(pattern)}字符), 已跳过: {pattern[:50]}...")
                        continue
                    # ReDoS 防护：检测连续量词（如 ++、**、{2,}+）和嵌套量词（如 (a+)+、[a-z]+*）
                    if re.search(r'[\+\*\{][\s]*[\+\*\{]', pattern) or \
                       re.search(r'\([^)]*\)[\s]*[\+\*\{]', pattern) or \
                       re.search(r'\[[^\]]*\][\s]*[\+\*\{]', pattern):
                        logger.warning(f"[世界书] 正则可能引起 ReDoS, 已跳过: {pattern[:50]}")
                        continue
                    flags = 0 if entry.case_sensitive else re.IGNORECASE
                    if re.search(pattern, text, flags):
                        return 2 if self._check_probability(entry) else -1
                except re.error as e:
                    logger.warning(f"正则表达式错误 [{entry.id}]: {pattern} - {e}")

        # 步骤2: 关键词匹配
        if not entry.keys and not entry.regex_keys:
            return -1  # 没有触发条件

        best_match_type = -1  # 最佳匹配类型: 0=精确, 1=模糊

        # 主关键词匹配 (OR 逻辑)
        if entry.keys:
            key_matched = False
            for key in entry.keys:
                mt = self._keyword_match(key, text, entry.case_sensitive)
                if mt >= 0:
                    key_matched = True
                    if mt == 0:  # 精确匹配已经是最佳
                        best_match_type = 0
                        break
                    best_match_type = 1  # 模糊匹配
            if not key_matched:
                return -1

        # 次要关键词匹配 (AND 逻辑)
        if entry.key_secondary:
            for key in entry.key_secondary:
                if self._keyword_match(key, text, entry.case_sensitive) < 0:
                    return -1

        if best_match_type < 0:
            return -1

        # 步骤3: 概率检查
        return best_match_type if self._check_probability(entry) else -1

    def _keyword_match(self, keyword: str, text: str, case_sensitive: bool) -> int:
        """关键词匹配，返回匹配类型: 0=精确匹配, 1=模糊匹配, -1=不匹配"""
        if case_sensitive:
            search_text = text
            search_key = keyword
        else:
            search_text = text.lower()
            search_key = keyword.lower()

        if search_key not in search_text:
            return -1

        # 精确匹配: 关键词前后均为非字母数字/中文的字符（或边界）
        escaped = re.escape(search_key)
        pattern = r'(?<![a-zA-Z0-9\u4e00-\u9fff])' + escaped + r'(?![a-zA-Z0-9\u4e00-\u9fff])'
        if re.search(pattern, search_text):
            return 0  # 精确匹配
        return 1  # 模糊匹配

    def _check_probability(self, entry: WorldBookEntry) -> bool:
        """概率检查"""
        if entry.probability >= 100:
            return True
        if entry.probability <= 0:
            return False

        return random.randint(1, 100) <= entry.probability

    def build_context(self, text: str, current_round: int = 0) -> str:
        """
        构建世界书上下文

        Args:
            text: 当前对话文本
            current_round: 当前对话轮次

        Returns:
            世界书上下文文本
        """
        parts = []

        # 常量条目
        for entry in self.get_constant_entries():
            if entry.can_inject(current_round):
                parts.append(entry.content)
                entry.record_injection(current_round)

        # 触发条目
        for entry in self.get_triggered_entries(text, current_round):
            parts.append(entry.content)

        return "\n\n".join(parts) if parts else ""

    def reset_all_state(self) -> None:
        """重置所有条目的运行时状态"""
        for entry in self.entries:
            entry.reset_state()

    # ── 运行时 CRUD（线程安全）──

    def add_entry(self, entry: WorldBookEntry) -> None:
        """动态添加条目到内存并持久化。

        Args:
            entry: 要添加的 WorldBookEntry 实例
        """
        with self._lock:
            # 检查 ID 重复
            if any(e.id == entry.id for e in self.entries):
                raise ValueError(f"条目 ID '{entry.id}' 已存在")
            self.entries.append(entry)
            self._save_to_file_locked()

        logger.info(f"[世界书] {self.name}: 已添加条目 {entry.id} (共 {len(self.entries)} 条)")

    def update_entry(self, key: str, **kwargs) -> None:
        """更新已有条目（按 ID 匹配）并持久化。

        Args:
            key: 条目的 ID
            **kwargs: 要更新的字段，如 content="新内容", probability=80

        Raises:
            ValueError: 条目不存在
        """
        with self._lock:
            for i, entry in enumerate(self.entries):
                if entry.id == key:
                    for attr, value in kwargs.items():
                        if hasattr(entry, attr):
                            setattr(entry, attr, value)
                    self._save_to_file_locked()
                    logger.info(f"[世界书] {self.name}: 已更新条目 {key}")
                    return
            raise ValueError(f"条目 '{key}' 不存在")

    def delete_entry(self, key: str) -> None:
        """删除条目（按 ID 匹配）并持久化。

        Args:
            key: 条目的 ID

        Raises:
            ValueError: 条目不存在
        """
        with self._lock:
            before = len(self.entries)
            self.entries = [e for e in self.entries if e.id != key]
            if len(self.entries) == before:
                raise ValueError(f"条目 '{key}' 不存在")
            self._save_to_file_locked()

        logger.info(f"[世界书] {self.name}: 已删除条目 {key} (共 {len(self.entries)} 条)")

    def save_to_file(self, filepath: str = None) -> None:
        """将当前内存中的条目持久化回 JSON 文件。

        Args:
            filepath: 目标文件路径，默认使用 self.source_path
        """
        target = filepath or self.source_path
        if not target:
            raise ValueError("无法保存：未指定文件路径且 source_path 为空")

        entries_data = []
        for entry in self.entries:
            entries_data.append({
                "id": entry.id,
                "keys": entry.keys,
                "key_secondary": entry.key_secondary,
                "content": entry.content,
                "comment": entry.comment,
                "constant": entry.constant,
                "selective": entry.selective,
                "probability": entry.probability,
                "priority": entry.priority,
                "regex_keys": entry.regex_keys,
                "case_sensitive": entry.case_sensitive,
                "max_injections": entry.max_injections,
                "cooldown_rounds": entry.cooldown_rounds,
                "created_at": entry.created_at,
            })

        data = {
            "version": self.version,
            "name": self.name,
            "description": self.description,
            "entries": entries_data,
        }

        # 原子写入
        tmp_path = target + ".tmp"
        try:
            with open(tmp_path, 'w', encoding='utf-8') as f:
                json.dump(data, f, ensure_ascii=False, indent=2)
            os.replace(tmp_path, target)
        except Exception:
            if os.path.exists(tmp_path):
                try:
                    os.remove(tmp_path)
                except OSError:
                    pass
            raise

        logger.debug(f"[世界书] 持久化完成: {target}")

    def _save_to_file_locked(self) -> None:
        """内部方法：持久化到 self.source_path（调用者需持有 _lock）。"""
        if self.source_path:
            self.save_to_file(self.source_path)


# ============================================================
# 世界书匹配引擎
# ============================================================

# 正则表达式安全限制
_MAX_REGEX_LENGTH = 200  # 正则表达式最大长度
_MAX_REGEX_REPETITION = 10  # 最大重复次数限制


class WorldBookEngine:
    """
    世界书匹配引擎

    负责:
    - 加载和管理多个世界书
    - 切换活跃世界书
    - 匹配并注入上下文
    """

    def __init__(self, books_dir: str = "world_books"):
        self.books_dir = Path(books_dir)
        self.books_dir.mkdir(parents=True, exist_ok=True)

        self._books: Dict[str, WorldBook] = {}
        self._active_book_id: Optional[str] = None
        self._current_round: int = 0

        # 加载已有世界书
        self._load_all()

        logger.info(f"世界书引擎初始化完成 (已加载 {len(self._books)} 本)")

    # ---- 加载与管理 ----

    def _load_all(self) -> None:
        """加载所有世界书"""
        for json_file in self.books_dir.glob("*.json"):
            try:
                self._load_book(str(json_file))
            except Exception as e:
                logger.error(f"加载世界书失败 {json_file}: {e}")

    def _load_book(self, file_path: str) -> Optional[WorldBook]:
        """从文件加载世界书"""
        with open(file_path, 'r', encoding='utf-8') as f:
            data = json.load(f)

        entries = []
        for entry_data in data.get("entries", []):
            entry = WorldBookEntry(
                id=entry_data.get("id", ""),
                keys=entry_data.get("keys", []),
                key_secondary=entry_data.get("key_secondary", []),
                content=entry_data.get("content", ""),
                comment=entry_data.get("comment", ""),
                constant=entry_data.get("constant", False),
                selective=entry_data.get("selective", True),
                probability=entry_data.get("probability", 100),
                priority=entry_data.get("priority", 0),
                regex_keys=entry_data.get("regex_keys", []),
                case_sensitive=entry_data.get("case_sensitive", False),
                max_injections=entry_data.get("max_injections", 5),
                cooldown_rounds=entry_data.get("cooldown_rounds", 0),
                created_at=entry_data.get("created_at", 0.0),
            )
            entries.append(entry)

        book = WorldBook(
            name=data.get("name", Path(file_path).stem),
            description=data.get("description", ""),
            version=data.get("version", "1.0"),
            entries=entries,
            source_path=file_path
        )

        self._books[book.name] = book
        logger.info(f"世界书加载成功: {book.name} ({len(entries)} 条)")
        return book

    def add_book(self, file_path: str) -> Optional[WorldBook]:
        """添加世界书"""
        return self._load_book(file_path)

    def remove_book(self, name: str) -> bool:
        """移除世界书"""
        if name not in self._books:
            return False
        if self._active_book_id == name:
            self._active_book_id = None
        del self._books[name]
        return True

    def list_books(self) -> List[Dict[str, Any]]:
        """列出所有世界书"""
        return [
            {
                "name": book.name,
                "description": book.description,
                "version": book.version,
                "entry_count": len(book.entries),
                "is_active": book.name == self._active_book_id
            }
            for book in self._books.values()
        ]

    # ---- 切换 ----

    def set_active(self, name: str) -> bool:
        """
        切换活跃世界书

        Args:
            name: 世界书名称

        Returns:
            是否切换成功
        """
        if name not in self._books:
            logger.warning(f"世界书不存在: {name}")
            return False

        # 重置旧世界书状态
        if self._active_book_id and self._active_book_id in self._books:
            self._books[self._active_book_id].reset_all_state()

        self._active_book_id = name
        self._books[name].reset_all_state()
        self._current_round = 0

        logger.info(f"切换世界书: {name}")
        return True

    def get_active(self) -> Optional[WorldBook]:
        """获取活跃世界书"""
        if self._active_book_id and self._active_book_id in self._books:
            return self._books[self._active_book_id]
        return None

    # ---- 访问器 ----

    def has_book(self, name: str) -> bool:
        """检查指定名称的世界书是否存在。"""
        return name in self._books

    def get_book(self, name: str) -> Optional[WorldBook]:
        """获取指定名称的世界书，不存在返回 None。"""
        return self._books.get(name)

    def get_current_round(self) -> int:
        """获取当前对话轮次。"""
        return self._current_round

    def increment_round(self) -> int:
        """递增并返回当前对话轮次。"""
        self._current_round += 1
        return self._current_round

    def load_book_file(self, file_path: str) -> Optional[WorldBook]:
        """从文件加载世界书（公开接口）。"""
        return self._load_book(file_path)

    # ---- 匹配 ----

    def match_and_inject(self, text: str) -> str:
        """
        匹配并注入世界书上下文

        Args:
            text: 当前对话文本

        Returns:
            世界书上下文文本 (如果没有匹配则返回空字符串)
        """
        book = self.get_active()
        if not book:
            return ""

        self._current_round += 1
        context = book.build_context(text, self._current_round)

        if context:
            logger.debug(f"世界书注入: {len(context)} 字符")

        return context

    def reset_round(self) -> None:
        """重置对话轮次和所有状态"""
        self._current_round = 0
        for book in self._books.values():
            book.reset_all_state()


# ============================================================
# JSON Schema 验证
# ============================================================

class WorldBookValidator:
    """世界书 JSON Schema 验证器"""

    REQUIRED_TOP_FIELDS = ["entries"]
    REQUIRED_ENTRY_FIELDS = ["id", "content"]

    @classmethod
    def validate(cls, data: Dict[str, Any]) -> List[str]:
        """
        验证世界书 JSON 数据

        Returns:
            错误信息列表 (空列表表示验证通过)
        """
        errors = []

        if not isinstance(data, dict):
            return ["世界书数据必须是 JSON 对象"]

        # 顶层字段验证
        for field in cls.REQUIRED_TOP_FIELDS:
            if field not in data:
                errors.append(f"缺少顶层字段: {field}")

        if "entries" not in data:
            return errors

        entries = data["entries"]
        if not isinstance(entries, list):
            errors.append("entries 必须是数组")
            return errors

        if len(entries) == 0:
            errors.append("entries 不能为空")

        # 条目验证
        seen_ids: Set[str] = set()
        for i, entry in enumerate(entries):
            prefix = f"entries[{i}]"

            if not isinstance(entry, dict):
                errors.append(f"{prefix} 必须是 JSON 对象")
                continue

            for field in cls.REQUIRED_ENTRY_FIELDS:
                if field not in entry:
                    errors.append(f"{prefix} 缺少字段: {field}")

            # ID 唯一性检查
            entry_id = entry.get("id", "")
            if entry_id in seen_ids:
                errors.append(f"{prefix} ID 重复: {entry_id}")
            seen_ids.add(entry_id)

            # 字段类型验证
            if "keys" in entry and not isinstance(entry["keys"], list):
                errors.append(f"{prefix}.keys 必须是字符串数组")
            if "key_secondary" in entry and not isinstance(entry["key_secondary"], list):
                errors.append(f"{prefix}.key_secondary 必须是字符串数组")
            if "probability" in entry:
                prob = entry["probability"]
                if not isinstance(prob, (int, float)) or prob < 0 or prob > 100:
                    errors.append(f"{prefix}.probability 必须在 0-100 之间")
            if "priority" in entry and not isinstance(entry["priority"], int):
                errors.append(f"{prefix}.priority 必须是整数")
            if "regex_keys" in entry:
                for j, pattern in enumerate(entry["regex_keys"]):
                    try:
                        # ReDoS 防护：拒绝过长或包含连续量词的正则
                        if len(pattern) > 200:
                            raise ValueError(
                                f"正则表达式过长（{len(pattern)}字符），最大200字符"
                            )
                        if re.search(r'[\+\*\{][\s]*[\+\*\{]', pattern):
                            raise ValueError(
                                f"正则表达式包含连续量词，可能导致 ReDoS"
                            )
                        re.compile(pattern)
                    except (re.error, ValueError) as e:
                        errors.append(f"{prefix}.regex_keys[{j}] 正则表达式无效: {e}")

        return errors


# ============================================================
# 工厂函数
# ============================================================

def get_world_book_engine(books_dir: str = "world_books") -> WorldBookEngine:
    """获取世界书引擎实例"""
    return WorldBookEngine(books_dir=books_dir)
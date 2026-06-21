"""
世界书桥接模块 — 封装 WorldBookEngine 为 Chaquopy 可调用的简单接口。

支持多世界书同时启用（勾选机制）：
- enable_world_book(name) / disable_world_book(name)
- 每次 chat() 调用时对所有已启用的世界书执行 match_and_inject
"""

import json
import os
import re
import threading
from pathlib import Path

from ._state import _PYTHON_ROOT  # 复用 _state.py 的路径设置（避免重复）

from src.world_book.world_book import WorldBookEngine
from src.utils.logger import get_logger

# 世界书名称安全校验正则：仅允许中文、字母、数字、中划线、下划线、空格，1-64字符
_WB_NAME_PATTERN = re.compile(r'^[\w\u4e00-\u9fff\- ]{1,64}$')

# ReDoS 防护：限制正则表达式最大长度和复杂度
_MAX_REGEX_LENGTH = 200
# 检测嵌套量词（如 (a+)+, (a*)* 等经典 ReDoS 模式）
_REDOS_NESTED_PATTERN = re.compile(
    r'\([^)]*\)[\s]*[\+\*\{]|'    # 分组后直接跟量词（如 (a)+）
    r'\[[^\]]*\][\s]*[\+\*\{]'     # 字符类后直接跟量词
)
# 检测重复量词（如 a++ 或 a**）
_REDOS_DOUBLE_QUANTIFIER = re.compile(r'[\+\*\{][\s]*[\+\*\{]')


def _validate_regex_safe(pattern: str) -> None:
    """校验正则表达式是否安全，防止 ReDoS 攻击。

    Args:
        pattern: 用户提供的正则表达式字符串

    Raises:
        ValueError: 正则表达式存在安全风险
    """
    if len(pattern) > _MAX_REGEX_LENGTH:
        raise ValueError(
            f"正则表达式过长（{len(pattern)}字符），最大允许 {_MAX_REGEX_LENGTH} 字符"
        )
    if _REDOS_DOUBLE_QUANTIFIER.search(pattern):
        raise ValueError(
            f"正则表达式包含连续量词（如 ++ 或 **），可能导致 ReDoS: {pattern}"
        )


def _sanitize_world_book_name(name: str) -> str:
    """校验世界书名称是否安全，防止路径遍历攻击。

    Args:
        name: 世界书名称

    Returns:
        校验通过的原始名称

    Raises:
        ValueError: 名称包含非法字符或超出长度限制
    """
    if not _WB_NAME_PATTERN.match(name):
        raise ValueError(f"非法的世界书名称（仅允许中英文/数字/中划线/下划线/空格，1-64字符）: {name}")

    # 二次验证：确保拼接后的路径在 data/world_books/ 目录内
    target = (Path(_PYTHON_ROOT) / "data" / "world_books" / f"{name}.json").resolve()
    allowed_dir = (Path(_PYTHON_ROOT) / "data" / "world_books").resolve()
    if not str(target).startswith(str(allowed_dir)):
        raise ValueError(f"路径遍历攻击被阻止: {name}")

    return name

_log = get_logger()

# 惰性初始化的引擎单例
_wb_engine: WorldBookEngine | None = None
# 已启用的世界书名称集合（受 _wb_lock 保护）
_enabled_books: set[str] = set()
# 线程锁，保护 _wb_engine 初始化和 _enabled_books 读写
_wb_lock = threading.Lock()


def _get_engine() -> WorldBookEngine:
    """获取或初始化 WorldBookEngine 单例。"""
    global _wb_engine
    if _wb_engine is None:
        with _wb_lock:
            if _wb_engine is None:  # 双重检查锁定
                world_books_dir = str(Path(_PYTHON_ROOT) / "data" / "world_books")
                _log.info(f"[世界书] 初始化引擎, 数据目录: {world_books_dir}")
                _wb_engine = WorldBookEngine(books_dir=world_books_dir)
    return _wb_engine


def list_world_books() -> str:
    """返回所有可用世界书列表。

    Returns:
        JSON: {"status": "ok", "books": [{"name": "...", "description": "...", "entries": N}, ...]}
    """
    try:
        engine = _get_engine()
        books = engine.list_books()
        return json.dumps({"status": "ok", "books": books}, ensure_ascii=False)
    except Exception as e:
        _log.error(f"[世界书] list_world_books 失败: {e}")
        return json.dumps({"status": "error", "message": str(e)}, ensure_ascii=False)


def enable_world_book(name: str) -> str:
    """启用指定世界书。

    Args:
        name: 世界书名称

    Returns:
        JSON: {"status": "ok", "name": "...", "enabled_count": N}
    """
    try:
        engine = _get_engine()

        # 检查世界书是否存在
        if name not in engine._books:
            available = [b["name"] for b in engine.list_books()]
            return json.dumps({
                "status": "error",
                "message": f"世界书 '{name}' 不存在",
                "available": available,
            }, ensure_ascii=False)

        with _wb_lock:
            _enabled_books.add(name)
            count = len(_enabled_books)
        _log.info(f"[世界书] 已启用: {name} (当前启用: {count} 本)")
        return json.dumps({
            "status": "ok",
            "name": name,
            "enabled_count": count,
        }, ensure_ascii=False)
    except Exception as e:
        _log.error(f"[世界书] enable_world_book({name}) 失败: {e}")
        return json.dumps({"status": "error", "message": str(e)}, ensure_ascii=False)


def disable_world_book(name: str) -> str:
    """禁用指定世界书。

    Args:
        name: 世界书名称

    Returns:
        JSON: {"status": "ok", "name": "...", "enabled_count": N}
    """
    try:
        with _wb_lock:
            _enabled_books.discard(name)
            count = len(_enabled_books)
        _log.info(f"[世界书] 已禁用: {name} (当前启用: {count} 本)")
        return json.dumps({
            "status": "ok",
            "name": name,
            "enabled_count": count,
        }, ensure_ascii=False)
    except Exception as e:
        _log.error(f"[世界书] disable_world_book({name}) 失败: {e}")
        return json.dumps({"status": "error", "message": str(e)}, ensure_ascii=False)


def get_enabled_world_books() -> str:
    """返回当前已启用的世界书列表。

    Returns:
        JSON: {"status": "ok", "enabled": ["name1", "name2", ...]}
    """
    with _wb_lock:
        enabled = sorted(_enabled_books)
    return json.dumps({
        "status": "ok",
        "enabled": enabled,
    }, ensure_ascii=False)


def create_world_book(name: str, description: str = "", entries_json: str = "[]") -> str:
    """创建新的世界书。

    Args:
        name: 世界书名称
        description: 描述
        entries_json: 条目的JSON数组字符串

    Returns:
        JSON: {"status": "ok", "name": "..."}
    """
    try:
        name = _sanitize_world_book_name(name)

        engine = _get_engine()

        # 检查是否已存在
        existing = [b["name"] for b in engine.list_books()]
        if name in existing:
            return json.dumps({
                "status": "error",
                "message": f"世界书 '{name}' 已存在",
            }, ensure_ascii=False)

        # 构建完整的世界书JSON
        entries = json.loads(entries_json) if entries_json else []
        data = {
            "version": "1.0",
            "name": name,
            "description": description,
            "entries": entries,
        }

        # 写入文件
        file_path = _PYTHON_ROOT + "/data/world_books/" + name + ".json"
        # 确保目录存在
        os.makedirs(os.path.dirname(file_path), exist_ok=True)
        with open(file_path, 'w', encoding='utf-8') as f:
            json.dump(data, f, ensure_ascii=False, indent=2)

        # 重新加载
        engine._load_book(file_path)
        _log.info(f"[世界书] 已创建: {name}")
        return json.dumps({"status": "ok", "name": name}, ensure_ascii=False)
    except Exception as e:
        _log.error(f"[世界书] create_world_book({name}) 失败: {e}")
        return json.dumps({"status": "error", "message": str(e)}, ensure_ascii=False)


def delete_world_book(name: str) -> str:
    """删除指定世界书。

    Args:
        name: 世界书名称

    Returns:
        JSON: {"status": "ok", "name": "..."}
    """
    try:
        name = _sanitize_world_book_name(name)
        # 从启用集合中移除（线程安全）
        with _wb_lock:
            _enabled_books.discard(name)

        # 删除文件
        file_path = _PYTHON_ROOT + "/data/world_books/" + name + ".json"
        if os.path.exists(file_path):
            os.remove(file_path)
            _log.info(f"[世界书] 已删除文件: {file_path}")

        # 从引擎中移除
        engine = _get_engine()
        engine.remove_book(name)
        _log.info(f"[世界书] 已删除: {name}")
        return json.dumps({"status": "ok", "name": name}, ensure_ascii=False)
    except Exception as e:
        _log.error(f"[世界书] delete_world_book({name}) 失败: {e}")
        return json.dumps({"status": "error", "message": str(e)}, ensure_ascii=False)


def get_world_book(name: str) -> str:
    """获取指定世界书的完整数据。

    Args:
        name: 世界书名称

    Returns:
        JSON: {"status": "ok", "book": {...}} 或错误
    """
    try:
        engine = _get_engine()
        if name not in engine._books:
            return json.dumps({
                "status": "error",
                "message": f"世界书 '{name}' 不存在",
            }, ensure_ascii=False)

        book = engine._books[name]
        entries_data = []
        for entry in book.entries:
            entries_data.append({
                "id": entry.id,
                "keys": entry.keys,
                "key_secondary": entry.key_secondary,
                "content": entry.content,
                "comment": entry.comment,
                "constant": entry.constant,
                "probability": entry.probability,
                "priority": entry.priority,
            })

        return json.dumps({
            "status": "ok",
            "book": {
                "name": book.name,
                "description": book.description,
                "version": book.version,
                "entry_count": len(book.entries),
                "entries": entries_data,
            },
        }, ensure_ascii=False)
    except Exception as e:
        _log.error(f"[世界书] get_world_book({name}) 失败: {e}")
        return json.dumps({"status": "error", "message": str(e)}, ensure_ascii=False)


def update_world_book(name: str, description: str = "", entries_json: str = "[]") -> str:
    """更新世界书的描述和条目（覆盖写入）。

    Args:
        name: 世界书名称
        description: 新描述
        entries_json: 新条目的JSON数组字符串

    Returns:
        JSON: {"status": "ok", "name": "..."}
    """
    try:
        engine = _get_engine()

        # 构建数据
        entries = json.loads(entries_json) if entries_json else []
        data = {
            "version": "1.0",
            "name": name,
            "description": description,
            "entries": entries,
        }

        # 写入文件
        file_path = _PYTHON_ROOT + "/data/world_books/" + name + ".json"
        os.makedirs(os.path.dirname(file_path), exist_ok=True)
        with open(file_path, 'w', encoding='utf-8') as f:
            json.dump(data, f, ensure_ascii=False, indent=2)

        # 重新加载：先移除旧的，再加载新的
        engine.remove_book(name)
        engine._load_book(file_path)
        _log.info(f"[世界书] 已更新: {name}")
        return json.dumps({"status": "ok", "name": name}, ensure_ascii=False)
    except Exception as e:
        _log.error(f"[世界书] update_world_book({name}) 失败: {e}")
        return json.dumps({"status": "error", "message": str(e)}, ensure_ascii=False)


def _match_and_inject_for_all(user_input: str) -> str:
    """对所有已启用的世界书执行匹配注入，合并结果。

    Args:
        user_input: 用户输入文本

    Returns:
        合并后的世界书上下文文本（空字符串表示无匹配）
    """
    if not _enabled_books:
        return ""

    engine = _get_engine()
    # 统一递增轮次计数（所有世界书共享同一轮次）
    engine._current_round += 1
    current_round = engine._current_round
    results = []

    with _wb_lock:
        enabled_snapshot = sorted(_enabled_books)
    for name in enabled_snapshot:
        try:
            if name in engine._books:
                book = engine._books[name]
                injected = book.build_context(user_input, current_round)
                if injected.strip():
                    results.append(injected)
                    _log.debug(f"[世界书] {name} 匹配注入: {len(injected)} 字符")
        except Exception as e:
            _log.warning(f"[世界书] {name} 匹配注入失败: {e}")

    return "\n\n".join(results) if results else ""


def _reset_round_for_all() -> None:
    """重置所有世界书的轮次计数（开始新对话时调用）。"""
    engine = _get_engine()
    engine.reset_round()
    _log.debug("[世界书] 已重置所有世界书轮次计数")


# ============================================================
# 条目 CRUD（单条增删改）
# ============================================================

def add_world_book_entry(name: str, entry_json: str) -> str:
    """向指定世界书添加一条条目。

    Args:
        name: 世界书名称
        entry_json: 条目 JSON 字符串，如:
            {"id":"e01","keys":["猫"],"content":"猫有灵性","probability":100,"priority":10}

    Returns:
        JSON: {"status": "ok", "entry_id": "..."} 或错误
    """
    try:
        engine = _get_engine()
        if name not in engine._books:
            return json.dumps({
                "status": "error", "message": f"世界书 '{name}' 不存在"
            }, ensure_ascii=False)

        entry_data = json.loads(entry_json)
        book = engine._books[name]

        # 检查 ID 重复
        new_id = entry_data.get("id", "")
        if not new_id:
            return json.dumps({
                "status": "error", "message": "条目必须包含 id 字段"
            }, ensure_ascii=False)
        existing_ids = {e.id for e in book.entries}
        if new_id in existing_ids:
            return json.dumps({
                "status": "error", "message": f"条目 ID '{new_id}' 已存在"
            }, ensure_ascii=False)

        # 构建条目对象
        from src.world_book.world_book import WorldBookEntry
        entry = WorldBookEntry(
            id=new_id,
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
        )
        book.entries.append(entry)

        # 持久化到文件
        _save_book_to_file(book)
        _log.info(f"[世界书] {name}: 已添加条目 {new_id}")
        return json.dumps({
            "status": "ok", "entry_id": new_id,
            "total_entries": len(book.entries),
        }, ensure_ascii=False)
    except json.JSONDecodeError as e:
        return json.dumps({"status": "error", "message": f"条目 JSON 解析失败: {e}"}, ensure_ascii=False)
    except Exception as e:
        _log.error(f"[世界书] add_world_book_entry({name}) 失败: {e}")
        return json.dumps({"status": "error", "message": str(e)}, ensure_ascii=False)


def update_world_book_entry(name: str, entry_id: str, entry_json: str) -> str:
    """更新指定世界书的某条条目（按 ID 匹配）。

    Args:
        name: 世界书名称
        entry_id: 要更新的条目 ID
        entry_json: 新的条目 JSON 字符串

    Returns:
        JSON: {"status": "ok", "entry_id": "..."} 或错误
    """
    try:
        engine = _get_engine()
        if name not in engine._books:
            return json.dumps({
                "status": "error", "message": f"世界书 '{name}' 不存在"
            }, ensure_ascii=False)

        entry_data = json.loads(entry_json)
        book = engine._books[name]

        # 查找目标条目
        target_idx = None
        for i, e in enumerate(book.entries):
            if e.id == entry_id:
                target_idx = i
                break
        if target_idx is None:
            return json.dumps({
                "status": "error", "message": f"条目 '{entry_id}' 不存在"
            }, ensure_ascii=False)

        # 构建新条目（保留 id）
        from src.world_book.world_book import WorldBookEntry
        new_entry = WorldBookEntry(
            id=entry_id,
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
        )
        book.entries[target_idx] = new_entry

        _save_book_to_file(book)
        _log.info(f"[世界书] {name}: 已更新条目 {entry_id}")
        return json.dumps({"status": "ok", "entry_id": entry_id}, ensure_ascii=False)
    except json.JSONDecodeError as e:
        return json.dumps({"status": "error", "message": f"条目 JSON 解析失败: {e}"}, ensure_ascii=False)
    except Exception as e:
        _log.error(f"[世界书] update_world_book_entry({name}, {entry_id}) 失败: {e}")
        return json.dumps({"status": "error", "message": str(e)}, ensure_ascii=False)


def delete_world_book_entry(name: str, entry_id: str) -> str:
    """删除指定世界书的某条条目。

    Args:
        name: 世界书名称
        entry_id: 要删除的条目 ID

    Returns:
        JSON: {"status": "ok", "entry_id": "..."} 或错误
    """
    try:
        engine = _get_engine()
        if name not in engine._books:
            return json.dumps({
                "status": "error", "message": f"世界书 '{name}' 不存在"
            }, ensure_ascii=False)

        book = engine._books[name]
        before = len(book.entries)
        book.entries = [e for e in book.entries if e.id != entry_id]
        after = len(book.entries)

        if before == after:
            return json.dumps({
                "status": "error", "message": f"条目 '{entry_id}' 不存在"
            }, ensure_ascii=False)

        _save_book_to_file(book)
        _log.info(f"[世界书] {name}: 已删除条目 {entry_id}")
        return json.dumps({
            "status": "ok", "entry_id": entry_id,
            "total_entries": after,
        }, ensure_ascii=False)
    except Exception as e:
        _log.error(f"[世界书] delete_world_book_entry({name}, {entry_id}) 失败: {e}")
        return json.dumps({"status": "error", "message": str(e)}, ensure_ascii=False)


def _save_book_to_file(book) -> None:
    """将世界书持久化到 JSON 文件（原子写入）。

    先写入 .tmp 临时文件，再用 os.replace() 原子替换到目标路径，
    防止写入过程中崩溃导致文件损坏。

    Args:
        book: WorldBook 实例，需包含 name/version/description/entries 属性。
    """
    entries_data = []
    for entry in book.entries:
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
        })

    data = {
        "version": book.version,
        "name": book.name,
        "description": book.description,
        "entries": entries_data,
    }

    file_path = _PYTHON_ROOT + "/data/world_books/" + book.name + ".json"
    os.makedirs(os.path.dirname(file_path), exist_ok=True)

    # 原子写入：先写入临时文件，再用 os.replace() 原子替换
    tmp_path = file_path + ".tmp"
    try:
        with open(tmp_path, 'w', encoding='utf-8') as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
        os.replace(tmp_path, file_path)
        _log.debug(f"[世界书] 原子写入完成: {file_path}")
    except Exception:
        # 清理可能残留的临时文件
        if os.path.exists(tmp_path):
            try:
                os.remove(tmp_path)
            except OSError:
                pass
        raise


# ============================================================
# 交叉审核
# ============================================================

def validate_world_book(name: str) -> str:
    """对指定世界书执行交叉审核（Schema + 语义 + 匹配能力）。

    审核维度:
    1. Schema 审核 — 字段完整性、类型正确性、ID 唯一性
    2. 语义审核 — 关键词质量、内容长度、覆盖度
    3. 匹配能力审核 — 正则有效性、entry 间冲突检测
    4. 统计汇总 — 条目数、常量数、总关键词数

    Args:
        name: 世界书名称

    Returns:
        JSON: {
            "status": "ok",
            "report": {
                "score": 85,        // 百分制综合评分
                "passed": true,     // 是否通过（≥60分）
                "dimensions": [
                    {"name": "Schema审核", "score": 100, "issues": [...], "suggestions": [...]},
                    {"name": "语义审核", "score": 80, "issues": [...], "suggestions": [...]},
                    {"name": "匹配能力", "score": 75, "issues": [...], "suggestions": [...]},
                ],
                "summary": {
                    "total_entries": N,
                    "constant_entries": N,
                    "total_keywords": N,
                    "avg_content_length": N,
                }
            }
        }
    """
    try:
        engine = _get_engine()
        if name not in engine._books:
            return json.dumps({
                "status": "error", "message": f"世界书 '{name}' 不存在"
            }, ensure_ascii=False)

        book = engine._books[name]
        entries = book.entries

        # ---- 维度1: Schema 审核 ----
        schema_issues = []
        schema_suggestions = []

        # 构建完整数据用于 WorldBookValidator
        entries_raw = []
        for e in entries:
            entries_raw.append({
                "id": e.id, "keys": e.keys,
                "key_secondary": e.key_secondary,
                "content": e.content, "comment": e.comment,
                "constant": e.constant, "selective": e.selective,
                "probability": e.probability, "priority": e.priority,
                "regex_keys": e.regex_keys, "case_sensitive": e.case_sensitive,
                "max_injections": e.max_injections,
                "cooldown_rounds": e.cooldown_rounds,
            })
        data = {"version": book.version, "name": book.name,
                "description": book.description, "entries": entries_raw}

        from src.world_book.world_book import WorldBookValidator
        schema_errors = WorldBookValidator.validate(data)
        for err in schema_errors:
            schema_issues.append({"level": "error", "message": err})

        if not entries:
            schema_issues.append({"level": "error", "message": "世界书没有任何条目"})
            schema_suggestions.append("请至少添加一条条目，否则世界书无法生效")

        schema_score = 100 if not schema_issues else max(0, 100 - len(schema_issues) * 15)

        # ---- 维度2: 语义审核 ----
        semantic_issues = []
        semantic_suggestions = []

        for e in entries:
            # 内容过短
            if len(e.content) < 10:
                semantic_issues.append({
                    "level": "warning",
                    "message": f"条目 [{e.id}] 内容过短（{len(e.content)}字），建议至少20字以确保上下文足够"
                })

            # 内容过长
            if len(e.content) > 500:
                semantic_issues.append({
                    "level": "info",
                    "message": f"条目 [{e.id}] 内容较长（{len(e.content)}字），可能占用较多 token 预算"
                })

            # 非constant条目缺少关键词
            if not e.constant and not e.keys and not e.regex_keys:
                semantic_issues.append({
                    "level": "warning",
                    "message": f"条目 [{e.id}] 不是常量条目但没有任何匹配关键词/正则，永远不会被触发"
                })
                semantic_suggestions.append(
                    f"为条目 [{e.id}] 添加至少一个 keywords 或 regex_keys，或将其设为 constant=true"
                )

            # 关键词过短
            for key in e.keys:
                if len(key) < 2:
                    semantic_issues.append({
                        "level": "warning",
                        "message": f"条目 [{e.id}] 关键词 '{key}' 过短（{len(key)}字），可能误匹配"
                    })

        semantic_score = 100 if not semantic_issues else max(0, 100 - len(semantic_issues) * 10)

        # ---- 维度3: 匹配能力审核 ----
        match_issues = []
        match_suggestions = []

        # 检查正则表达式
        for e in entries:
            for pattern in e.regex_keys:
                try:
                    _validate_regex_safe(pattern)  # ReDoS 防护
                    re.compile(pattern)
                except (re.error, ValueError) as err:
                    match_issues.append({
                        "level": "error",
                        "message": f"条目 [{e.id}] 正则表达式无效: {pattern} → {err}"
                    })

        # 检查是否有过于相似的关键词（可能冲突）
        all_keywords = []
        for e in entries:
            for key in e.keys:
                all_keywords.append((e.id, key.lower()))

        for i in range(len(all_keywords)):
            for j in range(i + 1, len(all_keywords)):
                kw1, kw2 = all_keywords[i][1], all_keywords[j][1]
                # 完全相同的关键词属于不同条目
                if kw1 == kw2:
                    match_issues.append({
                        "level": "info",
                        "message": f"条目 [{all_keywords[i][0]}] 和 [{all_keywords[j][0]}] 共享相同关键词 '{kw1}'，可能同时触发"
                    })
                # 一个包含另一个
                elif kw1 in kw2 or kw2 in kw1:
                    match_issues.append({
                        "level": "info",
                        "message": f"关键词 '{kw1}' 和 '{kw2}' 相似（包含关系），可能同时触发"
                    })

        if not entries:
            match_suggestions.append("添加条目后，系统将自动审核正则表达式有效性和关键词冲突")

        match_score = 100 if not match_issues else max(0, 100 - len(match_issues) * 5)

        # ---- 综合评分 ----
        weights = {"schema": 0.4, "semantic": 0.35, "match": 0.25}
        total_score = int(
            schema_score * weights["schema"]
            + semantic_score * weights["semantic"]
            + match_score * weights["match"]
        )

        # ---- 统计汇总 ----
        total_keywords = sum(len(e.keys) + len(e.key_secondary) for e in entries)
        constant_count = sum(1 for e in entries if e.constant)
        avg_content_len = int(sum(len(e.content) for e in entries) / len(entries)) if entries else 0

        return json.dumps({
            "status": "ok",
            "report": {
                "score": total_score,
                "passed": total_score >= 60,
                "dimensions": [
                    {
                        "name": "Schema 审核",
                        "score": schema_score,
                        "issues": schema_issues,
                        "suggestions": schema_suggestions,
                    },
                    {
                        "name": "语义审核",
                        "score": semantic_score,
                        "issues": semantic_issues,
                        "suggestions": semantic_suggestions,
                    },
                    {
                        "name": "匹配能力",
                        "score": match_score,
                        "issues": match_issues,
                        "suggestions": match_suggestions,
                    },
                ],
                "summary": {
                    "total_entries": len(entries),
                    "constant_entries": constant_count,
                    "total_keywords": total_keywords,
                    "avg_content_length": avg_content_len,
                },
            },
        }, ensure_ascii=False)
    except Exception as e:
        _log.error(f"[世界书] validate_world_book({name}) 失败: {e}")
        return json.dumps({"status": "error", "message": str(e)}, ensure_ascii=False)
"""流式导出模块。

提供对话历史和长期记忆的导出功能，支持分批写入以避免 OOM。
纯 Python 实现，Chaquopy 兼容。
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from src.memory.vector_store import MemoryEntry
from src.utils.logger import get_logger
from src.utils.time_utils import format_timestamp_iso


_EXPORT_ENCODING: str = "utf-8"
_INDENT: int = 2


def export_chat_history(messages: list[dict[str, str]], output_path: str | Path) -> Path:
    output_path = Path(output_path).resolve()
    temp_path = output_path.with_suffix(output_path.suffix + ".tmp")
    log = get_logger()
    log.info(f"[导出] 开始导出对话历史: 共 {len(messages)} 条消息 -> {output_path}")
    try:
        output_path.parent.mkdir(parents=True, exist_ok=True)
        data = {
            "export_type": "chat_history",
            "total_count": len(messages),
            "messages": messages,
        }
        with open(temp_path, "w", encoding=_EXPORT_ENCODING) as f:
            json.dump(data, f, ensure_ascii=False, indent=_INDENT)
        temp_path.replace(output_path)
        log.info(f"[导出] 对话历史导出完成: {output_path}")
    except OSError as e:
        log.error(f"[导出] 对话历史导出失败: {e}")
        raise
    finally:
        if temp_path.exists():
            try:
                temp_path.unlink()
            except OSError:
                pass
    return output_path


def export_memories(memories: list[MemoryEntry], output_path: str | Path) -> Path:
    output_path = Path(output_path).resolve()
    temp_path = output_path.with_suffix(output_path.suffix + ".tmp")
    log = get_logger()
    log.info(f"[导出] 开始导出长期记忆: 共 {len(memories)} 条 -> {output_path}")
    try:
        output_path.parent.mkdir(parents=True, exist_ok=True)
        data = {
            "export_type": "memories",
            "total_count": len(memories),
            "memories": [m.to_dict() for m in memories],
        }
        with open(temp_path, "w", encoding=_EXPORT_ENCODING) as f:
            json.dump(data, f, ensure_ascii=False, indent=_INDENT)
        temp_path.replace(output_path)
        log.info(f"[导出] 长期记忆导出完成: {output_path}")
    except OSError as e:
        log.error(f"[导出] 长期记忆导出失败: {e}")
        if temp_path.exists():
            temp_path.unlink()
        raise
    return output_path


def export_full(messages: list[dict[str, str]], memories: list[MemoryEntry], output_path: str | Path) -> Path:
    output_path = Path(output_path).resolve()
    temp_path = output_path.with_suffix(output_path.suffix + ".tmp")
    log = get_logger()
    log.info(f"[导出] 开始完整导出: 消息={len(messages)}, 记忆={len(memories)} -> {output_path}")
    try:
        output_path.parent.mkdir(parents=True, exist_ok=True)
        data: dict[str, Any] = {
            "export_type": "full",
            "exported_at": format_timestamp_iso(),
            "chat_history": {"total_count": len(messages), "messages": messages},
            "memories": {"total_count": len(memories), "memories": [m.to_dict() for m in memories]},
        }
        with open(temp_path, "w", encoding=_EXPORT_ENCODING) as f:
            json.dump(data, f, ensure_ascii=False, indent=_INDENT)
        temp_path.replace(output_path)
        log.info(f"[导出] 完整导出完成: {output_path}")
    except OSError as e:
        log.error(f"[导出] 完整导出失败: {e}")
        if temp_path.exists():
            temp_path.unlink()
        raise
    return output_path

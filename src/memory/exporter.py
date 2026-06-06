"""流式导出模块 - 分批导出对话历史和长期记忆为 JSON 文件。"""
from __future__ import annotations
import json
from pathlib import Path
from typing import Any
from src.memory.vector_store import MemoryEntry
from src.utils.logger import get_logger

_DEFAULT_BATCH_SIZE = 100
_EXPORT_ENCODING = "utf-8"
_INDENT = 2

def export_chat_history(messages: list[dict[str, str]], output_path: str | Path, batch_size: int = _DEFAULT_BATCH_SIZE) -> Path:
    output_path = Path(output_path).resolve()
    temp_path = output_path.with_suffix(output_path.suffix + ".tmp")
    log = get_logger()
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with open(temp_path, "w", encoding=_EXPORT_ENCODING) as f:
        f.write("{\n")
        f.write(f'  "export_type": "chat_history",\n')
        f.write(f'  "total_count": {len(messages)},\n')
        f.write('  "messages": [\n')
        for i, msg in enumerate(messages):
            json_str = json.dumps(msg, ensure_ascii=False, indent=_INDENT)
            indented = "\n".join(("  " + line) if line.strip() else line for line in json_str.split("\n"))
            f.write(indented)
            f.write(",\n" if i < len(messages) - 1 else "\n")
        f.write("  ]\n}\n")
    temp_path.replace(output_path)
    return output_path

def export_memories(memories: list[MemoryEntry], output_path: str | Path, batch_size: int = _DEFAULT_BATCH_SIZE) -> Path:
    output_path = Path(output_path).resolve()
    temp_path = output_path.with_suffix(output_path.suffix + ".tmp")
    log = get_logger()
    output_path.parent.mkdir(parents=True, exist_ok=True)
    memory_dicts = [m.to_dict() for m in memories]
    with open(temp_path, "w", encoding=_EXPORT_ENCODING) as f:
        f.write("{\n")
        f.write(f'  "export_type": "memories",\n')
        f.write(f'  "total_count": {len(memory_dicts)},\n')
        f.write('  "memories": [\n')
        for i, mem_dict in enumerate(memory_dicts):
            json_str = json.dumps(mem_dict, ensure_ascii=False, indent=_INDENT)
            indented = "\n".join(("  " + line) if line.strip() else line for line in json_str.split("\n"))
            f.write(indented)
            f.write(",\n" if i < len(memory_dicts) - 1 else "\n")
        f.write("  ]\n}\n")
    temp_path.replace(output_path)
    return output_path

def export_full(messages: list[dict[str, str]], memories: list[MemoryEntry], output_path: str | Path) -> Path:
    from src.utils.time_utils import format_timestamp_iso
    output_path = Path(output_path).resolve()
    temp_path = output_path.with_suffix(output_path.suffix + ".tmp")
    output_path.parent.mkdir(parents=True, exist_ok=True)
    data: dict[str, Any] = {"export_type": "full", "exported_at": format_timestamp_iso(), "chat_history": {"total_count": len(messages), "messages": messages}, "memories": {"total_count": len(memories), "memories": [m.to_dict() for m in memories]}}
    with open(temp_path, "w", encoding=_EXPORT_ENCODING) as f:
        json.dump(data, f, ensure_ascii=False, indent=_INDENT)
    temp_path.replace(output_path)
    return output_path

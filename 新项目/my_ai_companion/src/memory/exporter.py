"""流式导出模块。

提供对话历史和长期记忆的导出功能，支持分批写入以避免 OOM。
纯 Python 实现，Chaquopy 兼容。

核心方法：
    - export_chat_history: 导出对话历史为 JSON
    - export_memories: 导出长期记忆为 JSON

依赖：
    - src.memory.vector_store: MemoryEntry
    - src.utils.logger: get_logger 日志实例
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from src.memory.vector_store import MemoryEntry
from src.utils.logger import get_logger


# 默认分批大小
_DEFAULT_BATCH_SIZE: int = 100
# 导出文件编码
_EXPORT_ENCODING: str = "utf-8"
# 缩进空格数
_INDENT: int = 2


def export_chat_history(
    messages: list[dict[str, str]],
    output_path: str | Path,
    batch_size: int = _DEFAULT_BATCH_SIZE,
) -> Path:
    """分批导出对话历史为 JSON 文件。

    使用临时文件 + 原子重命名，保证导出过程中文件不会被损坏。

    输出格式:
    {
        "export_type": "chat_history",
        "total_count": N,
        "messages": [...]
    }

    Args:
        messages: 对话消息列表，每项为 {"role": str, "content": str}。
        output_path: 输出文件路径。
        batch_size: 每批写入的消息数（默认 100）。

    Returns:
        输出文件的绝对路径。

    Raises:
        OSError: 文件写入失败时。
    """
    output_path = Path(output_path).resolve()
    temp_path = output_path.with_suffix(output_path.suffix + ".tmp")
    log = get_logger()

    log.info(f"[导出] 开始导出对话历史: 共 {len(messages)} 条消息 -> {output_path}")

    try:
        # 确保父目录存在
        output_path.parent.mkdir(parents=True, exist_ok=True)

        with open(temp_path, "w", encoding=_EXPORT_ENCODING) as f:
            # 写入文件头
            f.write("{\n")
            f.write(f'  "export_type": "chat_history",\n')
            f.write(f'  "total_count": {len(messages)},\n')
            f.write('  "messages": [\n')

            for i, msg in enumerate(messages):
                # 序列化单条消息
                json_str = json.dumps(msg, ensure_ascii=False, indent=_INDENT)
                # 给 JSON 对象加上缩进
                indented = "\n".join(
                    ("  " + line) if line.strip() else line
                    for line in json_str.split("\n")
                )
                f.write(indented)

                if i < len(messages) - 1:
                    f.write(",\n")
                else:
                    f.write("\n")

            # 写入文件尾
            f.write("  ]\n")
            f.write("}\n")

        # 原子重命名
        temp_path.replace(output_path)
        log.info(f"[导出] 对话历史导出完成: {output_path}")

    except OSError as e:
        log.error(f"[导出] 对话历史导出失败: {e}")
        # 清理临时文件
        if temp_path.exists():
            temp_path.unlink()
        raise

    return output_path


def export_memories(
    memories: list[MemoryEntry],
    output_path: str | Path,
    batch_size: int = _DEFAULT_BATCH_SIZE,
) -> Path:
    """分批导出长期记忆为 JSON 文件。

    使用临时文件 + 原子重命名，保证导出过程中文件不会被损坏。

    输出格式:
    {
        "export_type": "memories",
        "total_count": N,
        "memories": [
            { "id": ..., "memory_type": ..., "content": ..., ... },
            ...
        ]
    }

    Args:
        memories: 记忆条目列表。
        output_path: 输出文件路径。
        batch_size: 每批处理的记忆数（默认 100）。

    Returns:
        输出文件的绝对路径。

    Raises:
        OSError: 文件写入失败时。
    """
    output_path = Path(output_path).resolve()
    temp_path = output_path.with_suffix(output_path.suffix + ".tmp")
    log = get_logger()

    log.info(f"[导出] 开始导出长期记忆: 共 {len(memories)} 条 -> {output_path}")

    try:
        # 确保父目录存在
        output_path.parent.mkdir(parents=True, exist_ok=True)

        # 转换所有记忆为字典
        memory_dicts = [m.to_dict() for m in memories]

        with open(temp_path, "w", encoding=_EXPORT_ENCODING) as f:
            # 写入文件头
            f.write("{\n")
            f.write(f'  "export_type": "memories",\n')
            f.write(f'  "total_count": {len(memory_dicts)},\n')
            f.write('  "memories": [\n')

            for i, mem_dict in enumerate(memory_dicts):
                # 序列化记忆
                json_str = json.dumps(mem_dict, ensure_ascii=False, indent=_INDENT)
                indented = "\n".join(
                    ("  " + line) if line.strip() else line
                    for line in json_str.split("\n")
                )
                f.write(indented)

                if i < len(memory_dicts) - 1:
                    f.write(",\n")
                else:
                    f.write("\n")

            # 写入文件尾
            f.write("  ]\n")
            f.write("}\n")

        # 原子重命名
        temp_path.replace(output_path)
        log.info(f"[导出] 长期记忆导出完成: {output_path}")

    except OSError as e:
        log.error(f"[导出] 长期记忆导出失败: {e}")
        # 清理临时文件
        if temp_path.exists():
            temp_path.unlink()
        raise

    return output_path


def export_full(
    messages: list[dict[str, str]],
    memories: list[MemoryEntry],
    output_path: str | Path,
) -> Path:
    """导出完整的对话数据（对话历史 + 长期记忆）。

    输出格式:
    {
        "export_type": "full",
        "chat_history": { ... },
        "memories": { ... },
        "exported_at": "ISO 8601"
    }

    Args:
        messages: 对话消息列表。
        memories: 记忆条目列表。
        output_path: 输出文件路径。

    Returns:
        输出文件的绝对路径。

    Raises:
        OSError: 文件写入失败时。
    """
    from src.utils.time_utils import format_timestamp_iso

    output_path = Path(output_path).resolve()
    temp_path = output_path.with_suffix(output_path.suffix + ".tmp")
    log = get_logger()

    log.info(
        f"[导出] 开始完整导出: 消息={len(messages)}, 记忆={len(memories)} -> {output_path}"
    )

    try:
        output_path.parent.mkdir(parents=True, exist_ok=True)

        # 构建完整数据
        data: dict[str, Any] = {
            "export_type": "full",
            "exported_at": format_timestamp_iso(),
            "chat_history": {
                "total_count": len(messages),
                "messages": messages,
            },
            "memories": {
                "total_count": len(memories),
                "memories": [m.to_dict() for m in memories],
            },
        }

        # 先写入临时文件
        with open(temp_path, "w", encoding=_EXPORT_ENCODING) as f:
            json.dump(data, f, ensure_ascii=False, indent=_INDENT)

        # 原子重命名
        temp_path.replace(output_path)
        log.info(f"[导出] 完整导出完成: {output_path}")

    except OSError as e:
        log.error(f"[导出] 完整导出失败: {e}")
        if temp_path.exists():
            temp_path.unlink()
        raise

    return output_path
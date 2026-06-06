# -*- coding: utf-8 -*-
"""
记忆导出模块
支持导出聊天记录和长期记忆为 JSON 格式

Android 导出路径: 通过 Kotlin 侧获取 Context.getExternalFilesDir() 或
Environment.getExternalStoragePublicDirectory(Downloads) 传入 Python 侧
"""

import json
import logging
import time
from typing import Dict, Any, Optional, List
from pathlib import Path
from datetime import datetime

logger = logging.getLogger(__name__)


class MemoryExporter:
    """
    记忆导出器

    导出功能:
    1. 导出聊天记录: 从 SQLite/JSON 中导出所有对话
    2. 导出长期记忆: 从向量存储中导出所有向量条目
    3. 导出格式: JSON (可扩展为 TXT/HTML)
    4. 安全: 支持 AES 加密导出 (可选)
    """

    def __init__(self, memory_storage=None, vector_store=None):
        """
        Args:
            memory_storage: MemoryStorage 实例 (聊天记录)
            vector_store: VectorStore 实例 (长期记忆)
        """
        self.memory_storage = memory_storage
        self.vector_store = vector_store

    # ================================================================
    # 导出聊天记录
    # ================================================================

    def export_chat_history(
        self,
        output_path: str,
        conversation_id: str = None,
        include_metadata: bool = True,
        format_type: str = "json"
    ) -> Dict[str, Any]:
        """
        导出聊天记录

        Args:
            output_path: 输出文件路径 (由 Kotlin 侧传入 Android 可用路径)
            conversation_id: 对话 ID (None = 导出全部)
            include_metadata: 是否包含元数据
            format_type: 导出格式 (json)

        Returns:
            {"success": bool, "path": str, "message_count": int, "error": str}
        """
        try:
            if not self.memory_storage:
                return {"success": False, "error": "记忆存储未初始化"}

            # 获取消息
            if conversation_id:
                messages = self.memory_storage.get_messages(conversation_id)
            else:
                # 导出全部对话
                messages = self._get_all_messages()

            if not messages:
                return {"success": False, "error": "没有可导出的聊天记录"}

            # 构建导出数据
            export_data = {
                "version": "1.0",
                "export_time": datetime.now().isoformat(),
                "export_type": "chat_history",
                "conversation_id": conversation_id,
                "message_count": len(messages),
                "messages": messages
            }

            if include_metadata:
                export_data["metadata"] = {
                    "app_version": "1.0.0",
                    "memory_level": self.memory_storage.level.value if self.memory_storage else "unknown",
                    "export_format": format_type
                }

            # 写入文件
            output_path = Path(output_path)
            output_path.parent.mkdir(parents=True, exist_ok=True)

            if format_type == "json":
                with open(output_path, 'w', encoding='utf-8') as f:
                    json.dump(export_data, f, ensure_ascii=False, indent=2)

            logger.info(f"聊天记录导出成功: {output_path} ({len(messages)} 条)")
            return {
                "success": True,
                "path": str(output_path),
                "message_count": len(messages)
            }

        except Exception as e:
            logger.error(f"导出聊天记录失败: {e}")
            return {"success": False, "error": str(e)}

    # ================================================================
    # 导出长期记忆
    # ================================================================

    def export_long_term_memory(
        self,
        output_path: str,
        include_vectors: bool = False,
        format_type: str = "json"
    ) -> Dict[str, Any]:
        """
        导出长期记忆 (向量条目)

        Args:
            output_path: 输出文件路径
            include_vectors: 是否包含完整向量 (文件会很大, 默认不包含)
            format_type: 导出格式 (json)

        Returns:
            {"success": bool, "path": str, "entry_count": int, "error": str}
        """
        try:
            if not self.vector_store:
                return {"success": False, "error": "向量存储未初始化"}

            entries = self.vector_store.get_all_entries()
            if not entries:
                return {"success": False, "error": "没有可导出的长期记忆"}

            # 如果不包含向量则移除
            if not include_vectors:
                for entry in entries:
                    entry.pop("vector", None)

            export_data = {
                "version": "1.0",
                "export_time": datetime.now().isoformat(),
                "export_type": "long_term_memory",
                "entry_count": len(entries),
                "entries": entries
            }

            output_path = Path(output_path)
            output_path.parent.mkdir(parents=True, exist_ok=True)

            with open(output_path, 'w', encoding='utf-8') as f:
                json.dump(export_data, f, ensure_ascii=False, indent=2)

            logger.info(f"长期记忆导出成功: {output_path} ({len(entries)} 条)")
            return {
                "success": True,
                "path": str(output_path),
                "entry_count": len(entries)
            }

        except Exception as e:
            logger.error(f"导出长期记忆失败: {e}")
            return {"success": False, "error": str(e)}

    # ================================================================
    # 导出全部数据
    # ================================================================

    def export_all(
        self,
        output_dir: str,
        include_vectors: bool = False
    ) -> Dict[str, Any]:
        """
        导出全部数据 (聊天记录 + 长期记忆)

        Args:
            output_dir: 输出目录
            include_vectors: 是否包含完整向量

        Returns:
            {"success": bool, "results": [...], "error": str}
        """
        output_dir = Path(output_dir)
        output_dir.mkdir(parents=True, exist_ok=True)

        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")

        results = []

        # 导出聊天记录
        chat_path = output_dir / f"chat_history_{timestamp}.json"
        chat_result = self.export_chat_history(str(chat_path))
        results.append({"type": "chat_history", **chat_result})

        # 导出长期记忆
        memory_path = output_dir / f"long_term_memory_{timestamp}.json"
        memory_result = self.export_long_term_memory(
            str(memory_path), include_vectors=include_vectors
        )
        results.append({"type": "long_term_memory", **memory_result})

        all_success = all(r.get("success", False) for r in results)

        return {
            "success": all_success,
            "output_dir": str(output_dir),
            "results": results
        }

    # ================================================================
    # 工具方法
    # ================================================================

    def _get_all_messages(self) -> List[Dict[str, Any]]:
        """获取所有对话的消息"""
        if not self.memory_storage:
            return []

        # 尝试从 storage 中获取所有消息
        messages = []
        try:
            # 通过导出方法获取
            import tempfile
            temp_path = Path(tempfile.gettempdir()) / "temp_export.json"
            if self.memory_storage.export_memory(str(temp_path)):
                with open(temp_path, 'r', encoding='utf-8') as f:
                    messages = json.load(f)
                temp_path.unlink(missing_ok=True)
        except Exception as e:
            logger.warning(f"获取全部消息失败: {e}")

        return messages

    # ================================================================
    # 加密导出 (预留接口)
    # ================================================================

    def export_encrypted(
        self,
        output_path: str,
        password: str,
        export_type: str = "all"
    ) -> Dict[str, Any]:
        """
        加密导出 (使用 AES-256-GCM)

        注意: 此功能需要 cryptography 库, 在初期 APK 精简阶段暂不引入。
        提供此接口供后续扩展使用。

        Args:
            output_path: 输出文件路径
            password: 加密密码
            export_type: 导出类型 (chat_history / long_term_memory / all)

        Returns:
            {"success": bool, "path": str, "error": str}
        """
        try:
            from cryptography.hazmat.primitives.ciphers.aead import AESGCM
            import os as crypto_os
            import base64
        except ImportError:
            return {
                "success": False,
                "error": "加密功能需要 cryptography 库, 当前未安装。"
                       "请运行: pip install cryptography"
            }

        try:
            # 1. 生成导出数据
            if export_type == "chat_history":
                result = self.export_chat_history(output_path + ".tmp")
            elif export_type == "long_term_memory":
                result = self.export_long_term_memory(output_path + ".tmp")
            else:
                result = self.export_all(
                    str(Path(output_path).parent), include_vectors=False
                )

            if not result.get("success"):
                return result

            # 2. 读取临时文件
            tmp_path = output_path + ".tmp"
            with open(tmp_path, 'rb') as f:
                plaintext = f.read()

            # 3. 加密
            key = AESGCM.generate_key()
            aesgcm = AESGCM(key)
            nonce = crypto_os.urandom(12)
            ciphertext = aesgcm.encrypt(nonce, plaintext, None)

            # 4. 写入加密文件 (nonce + ciphertext)
            with open(output_path, 'wb') as f:
                f.write(nonce + ciphertext)

            # 5. 清理临时文件
            Path(tmp_path).unlink(missing_ok=True)

            logger.info(f"加密导出成功: {output_path}")
            return {"success": True, "path": output_path}

        except Exception as e:
            logger.error(f"加密导出失败: {e}")
            return {"success": False, "error": str(e)}

    # ================================================================
    # 获取 Android 导出路径
    # ================================================================

    @staticmethod
    def get_export_path(base_dir: str = None, file_type: str = "chat_history") -> str:
        """
        生成导出文件路径

        Args:
            base_dir: Android 基础目录 (由 Kotlin 侧传入)
                      如: /storage/emulated/0/Download
            file_type: 文件类型 (chat_history / long_term_memory / all)

        Returns:
            完整的导出文件路径
        """
        if base_dir is None:
            base_dir = str(Path.cwd() / "exports")

        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        filename = f"{file_type}_{timestamp}.json"

        return str(Path(base_dir) / filename)


def get_exporter(memory_storage=None, vector_store=None) -> MemoryExporter:
    """获取记忆导出器实例"""
    return MemoryExporter(memory_storage=memory_storage, vector_store=vector_store)
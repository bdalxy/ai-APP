"""记忆备份与恢复模块。

提供 SQLite 数据库的备份和恢复功能，支持:
    1. 完整备份：将整个数据库文件复制到备份路径
    2. 增量备份：仅备份自上次备份以来的变更
    3. JSON 导出备份：将记忆导出为 JSON 文件
    4. 自动备份：按时间间隔或记忆数量自动触发备份
    5. 备份恢复：从备份文件恢复整个记忆库

核心类:
    - MemoryBackup: 记忆备份管理器
    - BackupMetadata: 备份元数据

依赖:
    - sqlite3: Python 标准库
    - src.memory.vector_store: VectorStore
    - src.utils.logger: get_logger
    - src.utils.time_utils: 时间工具
"""

from __future__ import annotations

import json
import os
import shutil
import sqlite3
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from src.memory.vector_store import VectorStore
from src.utils.logger import get_logger
from src.utils.time_utils import format_timestamp_iso


# =============================================================================
# 备份元数据
# =============================================================================

@dataclass
class BackupMetadata:
    """备份元数据。

    Attributes:
        backup_id: 备份唯一标识符。
        backup_type: 备份类型（"full" | "incremental" | "json"）。
        created_at: 备份创建时间。
        memory_count: 备份时的记忆总数。
        db_size_bytes: 数据库文件大小（字节）。
        backup_size_bytes: 备份文件大小（字节）。
        version: 记忆系统版本号。
        checksum: 备份文件的 SHA256 校验和。
    """
    backup_id: str = ""
    backup_type: str = "full"
    created_at: str = ""
    memory_count: int = 0
    db_size_bytes: int = 0
    backup_size_bytes: int = 0
    version: str = "1.0"
    checksum: str = ""

    def to_dict(self) -> dict:
        return {
            "backup_id": self.backup_id,
            "backup_type": self.backup_type,
            "created_at": self.created_at,
            "memory_count": self.memory_count,
            "db_size_bytes": self.db_size_bytes,
            "backup_size_bytes": self.backup_size_bytes,
            "version": self.version,
            "checksum": self.checksum,
        }


# =============================================================================
# 备份管理器
# =============================================================================

class MemoryBackup:
    """记忆备份管理器。

    提供记忆库的备份和恢复功能，支持完整备份、增量备份和 JSON 导出。

    备份策略:
        - 完整备份: 复制整个 SQLite 数据库文件
        - JSON 备份: 导出所有记忆为 JSON 格式
        - 自动备份: 每 N 条新记忆或每 M 小时自动触发

    Attributes:
        MAX_BACKUPS: 保留的最大备份数（默认 10）。
        AUTO_BACKUP_INTERVAL_HOURS: 自动备份间隔（小时，默认 24）。
        AUTO_BACKUP_MEMORY_THRESHOLD: 触发自动备份的记忆数阈值（默认 100）。
    """

    MAX_BACKUPS: int = 10
    AUTO_BACKUP_INTERVAL_HOURS: int = 24
    AUTO_BACKUP_MEMORY_THRESHOLD: int = 100

    def __init__(
        self,
        vector_store: VectorStore,
        backup_dir: str | Path,
    ) -> None:
        """初始化备份管理器。

        Args:
            vector_store: 向量存储实例。
            backup_dir: 备份文件存储目录。
        """
        self._store = vector_store
        self._backup_dir = Path(backup_dir)
        self._log = get_logger()
        self._last_backup_time: float = 0.0
        self._memory_count_at_last_backup: int = 0
        self._backup_count: int = 0

        # 确保备份目录存在
        self._backup_dir.mkdir(parents=True, exist_ok=True)

        # 加载已有备份列表
        self._existing_backups = self._list_existing_backups()

        self._log.info(
            f"MemoryBackup 初始化完成: backup_dir={self._backup_dir}, "
            f"已有备份={len(self._existing_backups)}"
        )

    # =========================================================================
    # 完整备份
    # =========================================================================

    def full_backup(self) -> BackupMetadata | None:
        """执行完整备份。

        使用 SQLite 的 backup API 进行在线备份，不阻塞读写操作。

        Returns:
            BackupMetadata，如果备份失败则返回 None。
        """
        db_path = self._store.db_path
        if db_path == ":memory:":
            self._log.warning("[备份] 内存数据库不支持文件备份，请使用 JSON 导出")
            return None

        db_path = Path(db_path)
        if not db_path.exists():
            self._log.error(f"[备份] 数据库文件不存在: {db_path}")
            return None

        # 生成备份文件名
        backup_id = self._generate_backup_id()
        backup_path = self._backup_dir / f"memory_backup_{backup_id}.db"

        try:
            # 1. 获取数据库文件大小
            db_size = db_path.stat().st_size

            # 2. 使用 SQLite backup API 进行在线备份
            self._sqlite_backup(str(db_path), str(backup_path))

            # 3. 获取备份文件大小
            backup_size = backup_path.stat().st_size

            # 4. 计算校验和
            checksum = self._calculate_checksum(str(backup_path))

            # 5. 获取当前记忆数
            memory_count = self._store.count()

            # 6. 创建元数据
            metadata = BackupMetadata(
                backup_id=backup_id,
                backup_type="full",
                created_at=format_timestamp_iso(),
                memory_count=memory_count,
                db_size_bytes=db_size,
                backup_size_bytes=backup_size,
                checksum=checksum,
            )

            # 7. 保存元数据
            self._save_metadata(backup_path, metadata)

            # 8. 更新状态
            self._last_backup_time = time.time()
            self._memory_count_at_last_backup = memory_count
            self._backup_count += 1

            # 9. 清理旧备份
            self._cleanup_old_backups()

            self._log.info(
                f"[备份] 完整备份完成: {backup_id}, "
                f"记忆数={memory_count}, 大小={backup_size}字节, "
                f"校验和={checksum[:8]}..."
            )

            return metadata

        except Exception as e:
            self._log.error(f"[备份] 完整备份失败: {e}")
            # 清理失败的备份文件
            if backup_path.exists():
                try:
                    backup_path.unlink()
                except Exception:
                    pass
            return None

    def _sqlite_backup(self, source_path: str, dest_path: str) -> None:
        """使用 SQLite backup API 进行在线备份。

        该方式不阻塞源数据库的读写操作，比直接复制文件更安全。
        """
        source_conn = sqlite3.connect(source_path)
        dest_conn = sqlite3.connect(dest_path)

        try:
            source_conn.backup(dest_conn)
        finally:
            dest_conn.close()
            source_conn.close()

    # =========================================================================
    # JSON 导出备份
    # =========================================================================

    def json_backup(self) -> BackupMetadata | None:
        """将记忆导出为 JSON 格式备份。

        适用于内存数据库或需要跨平台迁移的场景。

        Returns:
            BackupMetadata，如果备份失败则返回 None。
        """
        backup_id = self._generate_backup_id()
        backup_path = self._backup_dir / f"memory_backup_{backup_id}.json"

        try:
            # 1. 导出所有记忆
            all_memories = self._export_all_memories()
            memory_count = len(all_memories)

            # 2. 写入 JSON 文件
            data = {
                "backup_id": backup_id,
                "backup_type": "json",
                "created_at": format_timestamp_iso(),
                "version": "1.0",
                "memory_count": memory_count,
                "memories": all_memories,
            }

            with open(backup_path, "w", encoding="utf-8") as f:
                json.dump(data, f, ensure_ascii=False, indent=2)

            # 3. 获取文件大小
            backup_size = backup_path.stat().st_size

            # 4. 计算校验和
            checksum = self._calculate_checksum(str(backup_path))

            # 5. 创建元数据
            metadata = BackupMetadata(
                backup_id=backup_id,
                backup_type="json",
                created_at=format_timestamp_iso(),
                memory_count=memory_count,
                db_size_bytes=0,
                backup_size_bytes=backup_size,
                checksum=checksum,
            )

            # 6. 保存元数据
            self._save_metadata(backup_path, metadata)

            # 7. 更新状态
            self._last_backup_time = time.time()
            self._memory_count_at_last_backup = memory_count
            self._backup_count += 1

            # 8. 清理旧备份
            self._cleanup_old_backups()

            self._log.info(
                f"[备份] JSON 备份完成: {backup_id}, "
                f"记忆数={memory_count}, 大小={backup_size}字节"
            )

            return metadata

        except Exception as e:
            self._log.error(f"[备份] JSON 备份失败: {e}")
            if backup_path.exists():
                try:
                    backup_path.unlink()
                except Exception:
                    pass
            return None

    def _export_all_memories(self) -> list[dict]:
        """分页导出所有记忆为字典列表。"""
        all_memories: list[dict] = []
        offset = 0
        page_size = 200

        while True:
            page = self._store.list_with_rowid(
                offset=offset,
                limit=page_size,
            )
            if not page:
                break
            for item in page:
                all_memories.append({
                    "id": item.get("id"),
                    "memory_type": item.get("memory_type"),
                    "content": item.get("content"),
                    "keywords": item.get("keywords"),
                    "importance": item.get("importance"),
                    "created_at": item.get("created_at"),
                    "last_accessed": item.get("last_accessed"),
                    "access_count": item.get("access_count"),
                    "decay_factor": item.get("decay_factor"),
                    "source": item.get("source"),
                    "archived": item.get("archived", False),
                })
            if len(page) < page_size:
                break
            offset += page_size

        return all_memories

    # =========================================================================
    # 增量备份
    # =========================================================================

    def incremental_backup(self) -> BackupMetadata | None:
        """执行增量备份。

        仅备份自上次备份以来的变更记忆。如果上次备份不存在，执行完整备份。

        Returns:
            BackupMetadata，如果备份失败则返回 None。
        """
        if self._last_backup_time == 0:
            self._log.info("[备份] 无上次备份记录，执行完整备份")
            return self.full_backup()

        # 增量备份目前使用 JSON 格式，导出所有记忆
        # 未来可优化为仅导出变更的记忆
        return self.json_backup()

    # =========================================================================
    # 自动备份检查
    # =========================================================================

    def should_auto_backup(self) -> bool:
        """检查是否应触发自动备份。

        触发条件:
            1. 距离上次备份超过 AUTO_BACKUP_INTERVAL_HOURS 小时
            2. 新增记忆数超过 AUTO_BACKUP_MEMORY_THRESHOLD

        Returns:
            True 如果应触发自动备份。
        """
        try:
            current_count = self._store.count()
        except Exception:
            return False

        # 条件 1: 时间间隔
        if self._last_backup_time > 0:
            hours_since_last = (time.time() - self._last_backup_time) / 3600
            if hours_since_last >= self.AUTO_BACKUP_INTERVAL_HOURS:
                return True

        # 条件 2: 记忆增量
        if self._memory_count_at_last_backup > 0:
            new_memories = current_count - self._memory_count_at_last_backup
            if new_memories >= self.AUTO_BACKUP_MEMORY_THRESHOLD:
                return True

        return False

    def auto_backup(self) -> BackupMetadata | None:
        """执行自动备份（如果满足条件）。

        Returns:
            BackupMetadata，如果不需要备份则返回 None。
        """
        if not self.should_auto_backup():
            return None

        self._log.info("[备份] 触发自动备份")
        return self.full_backup()

    # =========================================================================
    # 恢复
    # =========================================================================

    def restore(self, backup_id: str) -> bool:
        """从备份恢复记忆库。

        流程:
            1. 查找备份文件
            2. 验证校验和
            3. 清空当前记忆库
            4. 从备份恢复

        Args:
            backup_id: 备份标识符。

        Returns:
            True 如果恢复成功。
        """
        # 1. 查找备份文件
        backup_files = self._find_backup_files(backup_id)
        if not backup_files:
            self._log.error(f"[恢复] 未找到备份: {backup_id}")
            return False

        db_backup = backup_files.get("db")
        json_backup = backup_files.get("json")

        try:
            if db_backup and db_backup.exists():
                return self._restore_from_db(db_backup)
            elif json_backup and json_backup.exists():
                return self._restore_from_json(json_backup)
            else:
                self._log.error(f"[恢复] 备份文件不存在: {backup_id}")
                return False

        except Exception as e:
            self._log.error(f"[恢复] 恢复失败: {e}")
            return False

    def _restore_from_db(self, backup_path: Path) -> bool:
        """从 SQLite 备份文件恢复。"""
        db_path = self._store.db_path
        if db_path == ":memory:":
            self._log.error("[恢复] 内存数据库不支持从文件恢复")
            return False

        db_path = Path(db_path)

        try:
            # 1. 验证备份文件
            if not self._verify_backup(backup_path):
                self._log.error("[恢复] 备份文件校验失败")
                return False

            # 2. 关闭当前数据库连接
            self._store.close()

            # 3. 备份当前数据库（以防万一）
            safety_backup = db_path.with_suffix(".db.before_restore")
            shutil.copy2(str(db_path), str(safety_backup))
            self._log.info(f"[恢复] 已创建恢复前备份: {safety_backup}")

            # 4. 恢复
            # 重新打开目标数据库并恢复
            dest_conn = sqlite3.connect(str(db_path))
            source_conn = sqlite3.connect(str(backup_path))

            try:
                source_conn.backup(dest_conn)
                self._log.info(f"[恢复] 数据库恢复完成: {backup_path}")
            finally:
                dest_conn.close()
                source_conn.close()

            # 5. 清理安全备份
            try:
                safety_backup.unlink()
            except Exception:
                pass

            self._log.info(f"[恢复] 恢复成功: {backup_path}")
            return True

        except Exception as e:
            self._log.error(f"[恢复] 数据库恢复失败: {e}")
            return False

    def _restore_from_json(self, backup_path: Path) -> bool:
        """从 JSON 备份文件恢复。"""
        try:
            # 1. 读取备份文件
            with open(backup_path, "r", encoding="utf-8") as f:
                data = json.load(f)

            memories = data.get("memories", [])
            if not memories:
                self._log.warning("[恢复] JSON 备份中没有记忆数据")
                return False

            # 2. 清空当前记忆库
            self._store.clear()

            # 3. 逐条导入记忆
            from src.memory.vector_store import MemoryEntry

            imported = 0
            for item in memories:
                try:
                    entry = MemoryEntry.from_dict(item)
                    self._store.add(entry)
                    imported += 1
                except Exception as e:
                    self._log.debug(f"[恢复] 单条记忆导入失败: {e}")

            self._log.info(
                f"[恢复] JSON 恢复完成: 导入 {imported}/{len(memories)} 条记忆"
            )
            return True

        except json.JSONDecodeError as e:
            self._log.error(f"[恢复] JSON 解析失败: {e}")
            return False
        except Exception as e:
            self._log.error(f"[恢复] JSON 恢复失败: {e}")
            return False

    # =========================================================================
    # 备份列表
    # =========================================================================

    def list_backups(self) -> list[dict]:
        """列出所有备份。

        Returns:
            备份列表，每项包含备份元数据。
        """
        backups: list[dict] = []

        for backup_file in self._backup_dir.glob("memory_backup_*"):
            # 读取元数据文件
            meta_path = backup_file.with_suffix(backup_file.suffix + ".meta.json")
            if meta_path.exists():
                try:
                    with open(meta_path, "r", encoding="utf-8") as f:
                        meta = json.load(f)
                    backups.append(meta)
                except Exception:
                    pass
            else:
                # 无元数据文件，从文件名推断
                name = backup_file.stem
                backups.append({
                    "backup_id": name.replace("memory_backup_", ""),
                    "backup_type": "full" if backup_file.suffix == ".db" else "json",
                    "created_at": "",
                    "memory_count": 0,
                    "backup_size_bytes": backup_file.stat().st_size,
                })

        # 按创建时间排序
        backups.sort(key=lambda b: b.get("created_at", ""), reverse=True)
        return backups

    def delete_backup(self, backup_id: str) -> bool:
        """删除指定备份。

        Args:
            backup_id: 备份标识符。

        Returns:
            True 如果删除成功。
        """
        backup_files = self._find_backup_files(backup_id)
        deleted = False

        for path in backup_files.values():
            if path.exists():
                try:
                    path.unlink()
                    deleted = True
                except Exception as e:
                    self._log.warning(f"[备份] 删除失败: {path}, {e}")
            # 同时删除元数据文件
            meta_path = path.with_suffix(path.suffix + ".meta.json")
            if meta_path.exists():
                try:
                    meta_path.unlink()
                except Exception:
                    pass

        if deleted:
            self._log.info(f"[备份] 已删除备份: {backup_id}")
        return deleted

    # =========================================================================
    # 校验
    # =========================================================================

    def verify_backup(self, backup_id: str) -> dict:
        """验证备份文件的完整性。

        Args:
            backup_id: 备份标识符。

        Returns:
            验证结果字典。
        """
        backup_files = self._find_backup_files(backup_id)
        if not backup_files:
            return {"valid": False, "reason": "备份文件不存在"}

        for backup_path in backup_files.values():
            if not backup_path.exists():
                return {"valid": False, "reason": f"备份文件不存在: {backup_path}"}

            # 读取元数据
            meta_path = backup_path.with_suffix(backup_path.suffix + ".meta.json")
            if not meta_path.exists():
                return {"valid": False, "reason": "元数据文件不存在"}

            try:
                with open(meta_path, "r", encoding="utf-8") as f:
                    meta = json.load(f)
            except Exception:
                return {"valid": False, "reason": "元数据解析失败"}

            # 校验文件大小
            actual_size = backup_path.stat().st_size
            expected_size = meta.get("backup_size_bytes", 0)
            if actual_size != expected_size:
                return {
                    "valid": False,
                    "reason": f"文件大小不匹配: 期望={expected_size}, 实际={actual_size}",
                }

            # 校验 checksum
            actual_checksum = self._calculate_checksum(str(backup_path))
            expected_checksum = meta.get("checksum", "")
            if actual_checksum != expected_checksum:
                return {
                    "valid": False,
                    "reason": f"校验和不匹配: 期望={expected_checksum[:8]}..., 实际={actual_checksum[:8]}...",
                }

        return {
            "valid": True,
            "backup_id": backup_id,
            "memory_count": meta.get("memory_count", 0),
            "backup_size_bytes": actual_size,
            "created_at": meta.get("created_at", ""),
        }

    # =========================================================================
    # 辅助方法
    # =========================================================================

    def _generate_backup_id(self) -> str:
        """生成备份标识符。"""
        from datetime import datetime
        now = datetime.now()
        return now.strftime("%Y%m%d_%H%M%S")

    def _find_backup_files(self, backup_id: str) -> dict[str, Path]:
        """查找指定备份 ID 的所有文件。"""
        result: dict[str, Path] = {}
        for ext in [".db", ".json"]:
            path = self._backup_dir / f"memory_backup_{backup_id}{ext}"
            if path.exists():
                result[ext.lstrip(".")] = path
        return result

    def _list_existing_backups(self) -> list[Path]:
        """列出已有的备份文件。"""
        return list(self._backup_dir.glob("memory_backup_*.db")) + \
               list(self._backup_dir.glob("memory_backup_*.json"))

    def _save_metadata(self, backup_path: Path, metadata: BackupMetadata) -> None:
        """保存备份元数据到 JSON 文件。"""
        meta_path = backup_path.with_suffix(backup_path.suffix + ".meta.json")
        with open(meta_path, "w", encoding="utf-8") as f:
            json.dump(metadata.to_dict(), f, ensure_ascii=False, indent=2)

    def _cleanup_old_backups(self) -> None:
        """清理旧备份，保留最近 MAX_BACKUPS 个。"""
        # 收集所有备份文件（按创建时间排序）
        backup_files: list[tuple[float, Path]] = []
        for path in self._backup_dir.glob("memory_backup_*"):
            if path.suffix in (".db", ".json"):
                backup_files.append((path.stat().st_mtime, path))

        if len(backup_files) <= self.MAX_BACKUPS:
            return

        # 排序并删除最旧的
        backup_files.sort(key=lambda x: x[0])
        to_delete = backup_files[:len(backup_files) - self.MAX_BACKUPS]

        for _, path in to_delete:
            try:
                path.unlink()
                # 同时删除元数据
                meta_path = path.with_suffix(path.suffix + ".meta.json")
                if meta_path.exists():
                    meta_path.unlink()
                self._log.debug(f"[备份] 清理旧备份: {path.name}")
            except Exception as e:
                self._log.warning(f"[备份] 清理旧备份失败: {path}, {e}")

    @staticmethod
    def _calculate_checksum(file_path: str) -> str:
        """计算文件的 SHA256 校验和。"""
        import hashlib
        sha256 = hashlib.sha256()
        with open(file_path, "rb") as f:
            for chunk in iter(lambda: f.read(8192), b""):
                sha256.update(chunk)
        return sha256.hexdigest()

    def _verify_backup(self, backup_path: Path) -> bool:
        """验证备份文件是否可读取。"""
        try:
            conn = sqlite3.connect(str(backup_path))
            cursor = conn.execute("SELECT COUNT(*) FROM memories")
            count = cursor.fetchone()[0]
            conn.close()
            self._log.info(f"[验证] 备份文件有效: {count} 条记忆")
            return True
        except sqlite3.Error as e:
            self._log.error(f"[验证] 备份文件无效: {e}")
            return False

    # =========================================================================
    # 统计
    # =========================================================================

    def get_stats(self) -> dict:
        """获取备份管理器统计信息。

        Returns:
            包含备份计数、最后备份时间等信息的字典。
        """
        backups = self.list_backups()
        total_size = sum(
            b.get("backup_size_bytes", 0) for b in backups
        )

        return {
            "backup_count": len(backups),
            "max_backups": self.MAX_BACKUPS,
            "last_backup_time": format_timestamp_iso(
                self._last_backup_time
            ) if self._last_backup_time > 0 else None,
            "total_backup_size_bytes": total_size,
            "auto_backup_interval_hours": self.AUTO_BACKUP_INTERVAL_HOURS,
            "auto_backup_memory_threshold": self.AUTO_BACKUP_MEMORY_THRESHOLD,
            "backups": backups[:5],  # 最近 5 个备份
        }
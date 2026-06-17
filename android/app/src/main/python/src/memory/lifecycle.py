"""记忆生命周期管理模块。

管理记忆从创建到消亡的完整生命周期，包括:
    1. 衰减更新（decay update）：定期重新计算记忆的衰减因子
    2. 重要性重校准（importance recalibration）：基于访问模式调整重要性
    3. 智能清理（smart pruning）：自动清理低价值记忆
    4. 健康检查（health check）：监控记忆库的健康状态

生命周期阶段:
    活跃 (active) → 衰减 (decaying) → 休眠 (dormant) → 归档 (archived) → 清理 (pruned)

核心类:
    - MemoryLifecycle: 记忆生命周期管理器

依赖:
    - src.memory.vector_store: VectorStore, MemoryEntry
    - src.memory.decay: calculate_decay, HALF_LIFE_DAYS
    - src.memory.memory_types: analyze_sentiment, estimate_importance
    - src.utils.logger: get_logger 日志实例
    - src.utils.time_utils: format_timestamp_iso 时间格式化
"""

from __future__ import annotations

import math
from datetime import datetime, timedelta, timezone
from typing import Any

from src.memory.decay import (
    HALF_LIFE_DAYS,
    DEFAULT_HALF_LIFE,
    calculate_decay,
    get_weight,
    _parse_iso_datetime,
)
from src.memory.memory_types import analyze_sentiment, estimate_importance
from src.memory.vector_store import MemoryEntry, VectorStore
from src.utils.logger import get_logger
from src.utils.time_utils import format_timestamp_iso, now_cst

# 东八区时区
TZ_CST = timezone(timedelta(hours=8))


class MemoryLifecycle:
    """记忆生命周期管理器。

    管理记忆从创建到消亡的完整生命周期。
    支持衰减更新、重要性重校准、智能清理和健康检查。

    Attributes:
        DECAY_UPDATE_INTERVAL: 衰减更新间隔（默认 24 小时）。
        PRUNE_THRESHOLD: 清理阈值（默认 1000 条记忆时触发）。
        PRUNE_COUNT: 每次清理的最大记忆数（默认 100）。
        DORMANT_THRESHOLD: 休眠阈值（decay_factor 低于此值视为休眠，默认 0.1）。
        HEALTH_CHECK_INTERVAL: 健康检查间隔（默认 100 轮）。
    """

    DECAY_UPDATE_INTERVAL: int = 86400              # 24 小时
    PRUNE_THRESHOLD: int = 800                       # 原 1000，更早触发清理
    PRUNE_COUNT: int = 150                            # 原 100，提高单次清理上限
    DORMANT_THRESHOLD: float = 0.1                    # 休眠阈值
    HEALTH_CHECK_INTERVAL: int = 100                  # 健康检查间隔

    def __init__(self, vector_store: VectorStore) -> None:
        """初始化生命周期管理器。

        Args:
            vector_store: 向量存储实例。
        """
        self._store = vector_store
        self._log = get_logger()
        self._last_decay_update: datetime | None = None
        self._last_health_check: int = 0
        self._pruned_count: int = 0
        self._log.info("MemoryLifecycle 初始化完成")

    # =========================================================================
    # 衰减更新
    # =========================================================================

    def update_decay(self, force: bool = False) -> int:
        """更新所有记忆的衰减因子。

        遍历所有活跃记忆，重新计算 decay_factor 并更新数据库。
        默认每 DECAY_UPDATE_INTERVAL 秒只执行一次，force=True 时强制执行。

        Args:
            force: 是否强制执行（忽略间隔限制）。

        Returns:
            更新的记忆条数。
        """
        if not force and self._last_decay_update is not None:
            elapsed = (now_cst() - self._last_decay_update).total_seconds()
            if elapsed < self.DECAY_UPDATE_INTERVAL:
                self._log.debug(
                    f"[衰减] 距上次更新仅 {elapsed:.0f} 秒，跳过 "
                    f"(间隔={self.DECAY_UPDATE_INTERVAL}s)"
                )
                return 0

        now = now_cst()
        updated_count = 0
        error_count = 0

        # 分页处理，避免全量加载
        offset = 0
        page_size = 500

        while True:
            page = self._store.get_page(offset, page_size)
            if not page:
                break

            for entry in page:
                try:
                    # 重新计算衰减因子
                    new_decay = calculate_decay(entry, now)

                    # 只在变化超过 1% 时才更新
                    if abs(new_decay - entry.decay_factor) > 0.01:
                        entry.decay_factor = new_decay
                        # 更新数据库
                        self._store.update(
                            self._store._get_rowid_for_entry(entry),
                            entry,
                        )
                        updated_count += 1

                except Exception as e:
                    error_count += 1
                    self._log.debug(f"[衰减] 更新失败: {e}")

            offset += len(page)
            if len(page) < page_size:
                break

        self._last_decay_update = now
        self._log.info(
            f"[衰减] 完成: 更新 {updated_count} 条, 错误 {error_count} 条"
        )
        return updated_count

    # =========================================================================
    # 重要性重校准
    # =========================================================================

    def recalibrate_importance(self) -> int:
        """重新校准所有记忆的重要性分数。

        基于以下因素调整重要性:
            1. 访问频率：访问次数越多，重要性越高（最多 +0.15）
            2. 衰减程度：衰减越严重，重要性越低（最多 -0.1）
            3. 情感强度：情感记忆的重要性提升（+0.05）
            4. 时间衰减：记忆越旧，重要性略微降低

        调整后的重要性在 0.0 ~ 1.0 之间。

        Returns:
            重校准的记忆条数。
        """
        recalibrated = 0
        offset = 0
        page_size = 500

        while True:
            page = self._store.get_page(offset, page_size)
            if not page:
                break

            for entry in page:
                try:
                    original = entry.importance
                    new_importance = original

                    # 1. 访问频率加成
                    access_bonus = min(entry.access_count * 0.01, 0.15)
                    new_importance += access_bonus

                    # 2. 衰减惩罚
                    if entry.decay_factor < 0.3:
                        new_importance -= 0.1
                    elif entry.decay_factor < 0.5:
                        new_importance -= 0.05

                    # 3. 情感强度加成
                    sentiment = analyze_sentiment(entry.content)
                    if sentiment["sentiment"] != "neutral":
                        new_importance += 0.05

                    # 4. 时间老化
                    try:
                        created = _parse_iso_datetime(entry.created_at)
                        age_days = (now_cst() - created).total_seconds() / 86400
                        if age_days > 90:
                            new_importance -= 0.05
                        elif age_days > 30:
                            new_importance -= 0.02
                    except (ValueError, TypeError):
                        pass

                    # 限制范围
                    new_importance = max(0.05, min(1.0, new_importance))

                    # 只在变化超过 5% 时才更新
                    if abs(new_importance - original) > 0.05:
                        entry.importance = new_importance
                        try:
                            rowid = self._store._get_rowid_for_entry(entry)
                            self._store.update(rowid, entry)
                            recalibrated += 1
                        except Exception:
                            pass

                except Exception as e:
                    self._log.debug(f"[重校准] 失败: {e}")

            offset += len(page)
            if len(page) < page_size:
                break

        self._log.info(f"[重校准] 完成: 重校准 {recalibrated} 条记忆")
        return recalibrated

    # =========================================================================
    # 智能清理
    # =========================================================================

    def should_prune(self) -> bool:
        """检查是否应该触发清理。

        Returns:
            True 如果记忆总数超过 PRUNE_THRESHOLD。
        """
        try:
            return self._store.count() > self.PRUNE_THRESHOLD
        except Exception as e:
            self._log.warning(f"[清理] 检查记忆数失败: {e}")
            return False

    def prune(self) -> int:
        """智能清理低价值记忆。

        清理策略（按优先级排序）:
            1. 已归档且衰减因子 < 0.05 的记忆（几乎被遗忘）
            2. 衰减因子 < 0.01 的记忆（完全遗忘）
            3. 重要性 < 0.1 且访问次数 = 0 的记忆（无用记忆）
            4. 重要性 < 0.2 且创建超过 180 天（旧且不重要）

        Returns:
            清理的记忆条数。
        """
        if not self.should_prune():
            self._log.debug("[清理] 记忆数未超过阈值，跳过")
            return 0

        pruned = 0
        now = now_cst()

        # 策略 1: 已归档 + 几乎遗忘
        try:
            entries = self._store.get_page(0, self.PRUNE_COUNT * 2)
            to_delete: list[str] = []

            for entry in entries:
                if len(to_delete) >= self.PRUNE_COUNT:
                    break

                # 策略 1: 已归档且衰减极低
                if entry.archived and entry.decay_factor < 0.05:
                    to_delete.append(entry.id)
                    continue

                # 策略 2: 完全遗忘
                if entry.decay_factor < 0.01:
                    to_delete.append(entry.id)
                    continue

                # 策略 3: 无用记忆
                if entry.importance < 0.1 and entry.access_count == 0:
                    to_delete.append(entry.id)
                    continue

                # 策略 4: 旧且不重要
                try:
                    created = _parse_iso_datetime(entry.created_at)
                    age_days = (now - created).total_seconds() / 86400
                    if age_days > 180 and entry.importance < 0.2:
                        to_delete.append(entry.id)
                        continue
                except (ValueError, TypeError):
                    pass

            # 执行删除
            for mem_id in to_delete:
                try:
                    self._store.delete(mem_id)
                    pruned += 1
                except Exception as e:
                    self._log.debug(f"[清理] 删除失败: {e}")

        except Exception as e:
            self._log.error(f"[清理] 异常: {e}")

        self._pruned_count += pruned
        self._log.info(
            f"[清理] 完成: 清理 {pruned} 条记忆, "
            f"累计清理 {self._pruned_count} 条, "
            f"剩余 {self._store.count()} 条"
        )
        return pruned

    # =========================================================================
    # 健康检查
    # =========================================================================

    def health_check(self, turn_count: int) -> dict[str, Any]:
        """执行记忆库健康检查。

        检查项:
            1. 总记忆数是否合理
            2. 归档比例是否过高
            3. 平均衰减因子是否过低
            4. 类型分布是否均衡
            5. 是否有异常记忆（无内容、无类型等）

        Args:
            turn_count: 当前对话轮次。

        Returns:
            健康检查报告字典。
        """
        if turn_count - self._last_health_check < self.HEALTH_CHECK_INTERVAL:
            return {"status": "skipped", "reason": "间隔不足"}

        self._last_health_check = turn_count

        try:
            total = self._store.count()
            active = self._store.count_active()
            archived = total - active

            # 获取样本进行统计
            sample = self._store.get_page(0, min(200, total))
            if not sample:
                return {"status": "ok", "total": 0, "issues": []}

            # 计算指标
            avg_importance = sum(e.importance for e in sample) / len(sample)
            avg_decay = sum(e.decay_factor for e in sample) / len(sample)
            avg_access = sum(e.access_count for e in sample) / len(sample)

            # 类型分布
            type_dist: dict[str, int] = {}
            for e in sample:
                type_dist[e.memory_type] = type_dist.get(e.memory_type, 0) + 1

            issues: list[str] = []

            # 问题 1: 归档比例过高
            if total > 0 and archived / total > 0.7:
                issues.append(
                    f"归档比例过高 ({archived}/{total} = {archived/total:.0%})，"
                    f"建议执行清理"
                )

            # 问题 2: 平均衰减过低
            if avg_decay < 0.1:
                issues.append(
                    f"平均衰减因子过低 ({avg_decay:.3f})，"
                    f"大量记忆接近遗忘"
                )

            # 问题 3: 平均重要性过低
            if avg_importance < 0.3:
                issues.append(
                    f"平均重要性偏低 ({avg_importance:.3f})，"
                    f"建议提高提取质量"
                )

            # 问题 4: 记忆数过多
            if total > 2000:
                issues.append(
                    f"记忆数过多 ({total} 条)，"
                    f"建议执行清理或归档"
                )

            # 问题 5: 类型分布不均
            if len(type_dist) <= 2 and total > 20:
                issues.append(
                    f"类型分布过于集中: {type_dist}，"
                    f"建议启用 LLM 提取模式"
                )

            report = {
                "status": "warning" if issues else "ok",
                "total": total,
                "active": active,
                "archived": archived,
                "archived_ratio": round(archived / max(total, 1), 3),
                "avg_importance": round(avg_importance, 3),
                "avg_decay": round(avg_decay, 3),
                "avg_access": round(avg_access, 1),
                "type_distribution": type_dist,
                "issues": issues,
                "turn_count": turn_count,
            }

            self._log.info(
                f"[健康检查] 状态={report['status']}, "
                f"总计={total}, 活跃={active}, "
                f"平均衰减={avg_decay:.3f}, 问题数={len(issues)}"
            )

            return report

        except Exception as e:
            self._log.error(f"[健康检查] 异常: {e}")
            return {"status": "error", "message": str(e)}

    # =========================================================================
    # 统计信息
    # =========================================================================

    def get_stats(self) -> dict:
        """获取生命周期管理器统计信息。

        Returns:
            包含清理计数、阈值等信息的字典。
        """
        return {
            "pruned_count": self._pruned_count,
            "prune_threshold": self.PRUNE_THRESHOLD,
            "prune_count_per_run": self.PRUNE_COUNT,
            "dormant_threshold": self.DORMANT_THRESHOLD,
            "decay_update_interval": self.DECAY_UPDATE_INTERVAL,
            "last_decay_update": self._last_decay_update.isoformat()
                if self._last_decay_update else None,
            "health_check_interval": self.HEALTH_CHECK_INTERVAL,
        }
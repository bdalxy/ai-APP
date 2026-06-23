"""主动消息调度器模块。

负责定时检查和触发主动消息推送，使用 threading.Timer 实现 30 分钟轮询。
调度器状态持久化到 data/proactive_state.json。

状态文件路径: data/proactive_state.json
状态文件结构:
    {
        "last_sent_time": "2024-01-01T08:00:00+08:00",
        "total_sent_count": 5,
        "version": "1.0"
    }

依赖：
    - src.config.settings: Settings 单例（获取 DATA_DIR）
    - src.utils.logger: get_logger 日志实例
    - src.utils.time_utils: now_cst, format_timestamp_iso
"""

from __future__ import annotations

import json
import threading
from datetime import datetime
from pathlib import Path
from typing import TYPE_CHECKING

from src.config.settings import settings
from src.utils.logger import get_logger
from src.utils.time_utils import now_cst, format_timestamp_iso

if TYPE_CHECKING:
    from src.proactive.engine import ProactiveEngine


_STATE_FILENAME = "proactive_state.json"

_DEFAULT_STATE: dict[str, object] = {
    "last_sent_time": None,
    "total_sent_count": 0,
    "version": "1.0",
}


class ProactiveScheduler:
    """主动消息调度器。

    使用 threading.Timer 实现定时轮询，每 30 分钟检查一次是否需要推送。
    支持 start_timer() / stop_timer() 控制定时器生命周期。
    """

    _DEFAULT_INTERVAL_SECONDS: int = 1800  # 30 分钟

    def __init__(self) -> None:
        self._file_path: Path = settings.DATA_DIR / _STATE_FILENAME
        self._lock: threading.Lock = threading.Lock()
        self._log = get_logger()
        self._file_path.parent.mkdir(parents=True, exist_ok=True)
        self._state: dict[str, object] = self._load()
        self._timer: threading.Timer | None = None
        self._timer_running: bool = False
        self._engine: "ProactiveEngine | None" = None
        self._log.info(f"ProactiveScheduler 初始化完成: total_sent={self._state['total_sent_count']}")

    def _load(self) -> dict[str, object]:
        if not self._file_path.exists():
            self._log.info("状态文件不存在，使用默认状态")
            return dict(_DEFAULT_STATE)
        try:
            with open(self._file_path, "r", encoding="utf-8") as f:
                loaded: dict[str, object] = json.load(f)
            state = dict(_DEFAULT_STATE)
            state.update(loaded)
            self._log.debug(f"状态已加载: {self._file_path}")
            return state
        except (json.JSONDecodeError, OSError) as e:
            self._log.error(f"加载状态文件失败: {e}，使用默认状态")
            return dict(_DEFAULT_STATE)

    def _save(self) -> None:
        try:
            temp_path = self._file_path.with_suffix(".tmp")
            with open(temp_path, "w", encoding="utf-8") as f:
                json.dump(self._state, f, ensure_ascii=False, indent=2)
            temp_path.replace(self._file_path)
            self._log.debug(f"状态已保存: {self._file_path}")
        except OSError as e:
            self._log.error(f"保存状态文件失败: {e}")
            raise

    def get_last_sent_time(self) -> datetime | None:
        raw = self._state.get("last_sent_time")
        if raw is None or not isinstance(raw, str):
            return None
        try:
            return datetime.fromisoformat(raw)
        except (ValueError, TypeError):
            self._log.warning(f"无法解析上次发送时间: {raw}")
            return None

    def update_last_sent_time(self, sent_time: datetime | None = None) -> None:
        if sent_time is None:
            sent_time = now_cst()
        iso_time = format_timestamp_iso(sent_time)
        with self._lock:
            self._state["last_sent_time"] = iso_time
            count = self._state.get("total_sent_count", 0)
            if isinstance(count, (int, float)):
                self._state["total_sent_count"] = int(count) + 1
            else:
                self._state["total_sent_count"] = 1
            self._save()
        self._log.info(f"[调度器] 上次发送时间已更新: {iso_time}, 累计发送: {self._state['total_sent_count']}")

    def get_next_scheduled_time(self) -> datetime | None:
        last = self.get_last_sent_time()
        if last is None:
            return None
        from datetime import timedelta
        return last + timedelta(seconds=self._DEFAULT_INTERVAL_SECONDS)

    def set_engine(self, engine: "ProactiveEngine") -> None:
        """设置主动消息引擎，供定时器回调使用。"""
        self._engine = engine

    def check_and_send(self) -> bool:
        """执行一次主动消息检查与发送。

        Returns:
            True 如果成功发送了一条消息，False 如果跳过或失败。
        """
        if self._engine is None:
            self._log.debug("[调度器] 引擎未注入，跳过")
            return False

        try:
            # 延迟导入避免循环依赖
            from src.proactive.engine import ProactiveEngine
            from src.chat_engine.role_player import _ctx as chat_ctx

            player = chat_ctx.player
            if player is None:
                self._log.debug("[调度器] 角色未加载，跳过")
                return False

            retriever = chat_ctx.orchestrator.retriever if chat_ctx.orchestrator else None
            api_client = chat_ctx.client
            if api_client is None:
                self._log.debug("[调度器] API 客户端未初始化，跳过")
                return False

            last_sent = self.get_last_sent_time()
            message = self._engine.decide_and_generate(
                player, retriever, api_client, last_sent
            )
            if message is not None:
                self.update_last_sent_time()
                self._log.info(f"[调度器] 定时推送成功: {message[:50]}...")
                return True
            else:
                self._log.debug("[调度器] 决策跳过，不发送")
                return False

        except Exception as e:
            self._log.error(f"[调度器] check_and_send 异常: {e}")
            return False

    # =========================================================================
    # 定时器
    # =========================================================================

    def start_timer(self, interval_seconds: int | None = None) -> None:
        """启动定时器，周期性检查是否需要推送。

        Args:
            interval_seconds: 检查间隔秒数，默认 1800（30 分钟）。
        """
        if interval_seconds is None:
            interval_seconds = self._DEFAULT_INTERVAL_SECONDS

        if self._timer_running:
            self._log.debug("[调度器] 定时器已在运行，跳过")
            return

        self._timer_running = True
        self._log.info(f"[调度器] 启动定时器，间隔={interval_seconds}s ({interval_seconds // 60} 分钟)")

        def _tick() -> None:
            if not self._timer_running:
                return
            try:
                self.check_and_send()
            except Exception as e:
                self._log.error(f"[调度器] 定时回调异常: {e}")
            # 调度下一次
            if self._timer_running:
                self._timer = threading.Timer(interval_seconds, _tick)
                self._timer.daemon = True
                self._timer.start()

        self._timer = threading.Timer(interval_seconds, _tick)
        self._timer.daemon = True
        self._timer.start()

    def stop_timer(self) -> None:
        """停止定时器。"""
        self._timer_running = False
        if self._timer is not None:
            self._timer.cancel()
            self._timer = None
        self._log.info("[调度器] 定时器已停止")

    def is_timer_running(self) -> bool:
        """检查定时器是否在运行。"""
        return self._timer_running

    def get_total_sent_count(self) -> int:
        count = self._state.get("total_sent_count", 0)
        if isinstance(count, (int, float)):
            return int(count)
        return 0

    def get_state(self) -> dict[str, object]:
        return dict(self._state)

    def reset(self) -> None:
        with self._lock:
            self._state = dict(_DEFAULT_STATE)
            self._save()
        self._log.info("[调度器] 状态已重置")

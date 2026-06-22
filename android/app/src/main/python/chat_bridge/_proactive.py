"""
主动消息桥接模块 — generate_proactive_message。
"""
import json

from src.proactive.engine import ProactiveEngine
from src.utils.logger import get_logger

from ._state import _ctx

_log = get_logger()


def _check_python_ready() -> str | None:
    """检查 Python 环境是否已就绪，返回错误消息或 None（表示就绪）。"""
    if _ctx.player is None:
        return "聊天引擎未初始化，请先调用 init()"
    if _ctx.client is None:
        return "API 客户端未初始化，请先调用 init()"
    # 验证核心模块可正常导入
    try:
        from src.proactive.engine import ProactiveEngine  # noqa: F811
    except ImportError as e:
        return f"Python 主动消息模块加载失败: {e}"
    return None


def generate_proactive_message() -> str:
    """生成一条主动推送消息，供 WorkManager 定时调用。"""
    ready_error = _check_python_ready()
    if ready_error is not None:
        return json.dumps({"status": "error", "message": ready_error})

    try:
        engine = ProactiveEngine()
        player = _ctx.player
        retriever = _ctx.orchestrator.retriever if _ctx.orchestrator else None
        api_client = _ctx.client

        # 直接调用 generate_proactive_message，跳过 decide_and_generate 的重复检查
        # Android 端 ProactiveWorker 已处理：开关、间隔、免打扰时段的决策逻辑
        result = engine.generate_proactive_message(player, retriever, api_client)

        if result is None:
            return json.dumps({"status": "skip", "message": "消息生成失败（API 异常或无合适话题）"})

        title = player.card.name if player.card else "AI伴侣"
        _log.info(f"[主动消息] 生成成功，即将推送: {result[:50]}...")
        return json.dumps({"status": "ok", "title": title, "message": result})

    except Exception as e:
        _log.error(f"[主动消息] 异常: {e}")
        return json.dumps({"status": "error", "message": str(e)})

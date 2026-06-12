"""
主动消息桥接模块 — generate_proactive_message。
"""
import json

from src.proactive.engine import ProactiveEngine
from src.utils.logger import get_logger

from ._state import _ctx

_log = get_logger()


def generate_proactive_message() -> str:
    """生成一条主动推送消息，供 WorkManager 定时调用。"""
    if _ctx.player is None:
        return json.dumps({"status": "error", "message": "聊天引擎未初始化，请先调用 init()"})

    if _ctx.client is None:
        return json.dumps({"status": "error", "message": "API 客户端未初始化，请先调用 init()"})

    try:
        engine = ProactiveEngine()
        card = _ctx.player
        retriever = _ctx.orchestrator.retriever if _ctx.orchestrator else None
        api_client = _ctx.client

        result = engine.decide_and_generate(card, retriever, api_client)

        if result is None:
            return json.dumps({"status": "skip", "message": "当前不满足主动消息发送条件"})

        title = card.card.name if card.card else "AI伴侣"
        _log.info(f"[主动消息] 生成成功，即将推送: {result[:50]}...")
        return json.dumps({"status": "ok", "title": title, "message": result})

    except Exception as e:
        _log.error(f"[主动消息] 异常: {e}")
        return json.dumps({"status": "error", "message": str(e)})
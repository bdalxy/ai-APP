"""主动消息决策引擎 - 判断是否发送 + 生成消息。"""
from __future__ import annotations
from datetime import datetime
from typing import TYPE_CHECKING
from src.config.user_settings import user_settings
from src.utils.logger import get_logger
from src.utils.time_utils import is_within_time_range, now_cst, time_diff_seconds
if TYPE_CHECKING:
    from src.chat_engine.role_player import RolePlayer
    from src.memory.retriever import MemoryRetriever
    from src.api_client.deepseek import DeepSeekClient

class ProactiveEngine:
    def __init__(self) -> None:
        self._log = get_logger()

    def decide_and_generate(self, card: "RolePlayer", retriever: "MemoryRetriever | None", api_client: "DeepSeekClient", last_sent_time: datetime | None = None, now: datetime | None = None) -> str | None:
        if now is None:
            now = now_cst()
        proactive_enabled = user_settings.get("proactive_enabled")
        if not proactive_enabled:
            return None
        if not self.should_send_message(now, last_sent_time):
            return None
        return self.generate_proactive_message(card, retriever, api_client, now)

    def should_send_message(self, current_time: datetime | None = None, last_sent_time: datetime | None = None) -> bool:
        if current_time is None:
            current_time = now_cst()
        wake_time = user_settings.get("wake_time")
        sleep_time = user_settings.get("sleep_time")
        if isinstance(wake_time, str) and isinstance(sleep_time, str):
            if not is_within_time_range(wake_time, sleep_time, current_time):
                return False
        if last_sent_time is not None:
            interval_minutes = user_settings.get("proactive_interval_minutes")
            if isinstance(interval_minutes, (int, float)):
                elapsed_seconds = time_diff_seconds(last_sent_time, current_time)
                if elapsed_seconds / 60.0 < float(interval_minutes):
                    return False
        return True

    def generate_proactive_message(self, role_player: "RolePlayer", retriever: "MemoryRetriever | None", api_client: "DeepSeekClient", now: datetime | None = None) -> str | None:
        if now is None:
            now = now_cst()
        recent_memories: list[str] = []
        if retriever is not None:
            try:
                recent_entries = retriever.get_recent(top_k=5)
                recent_memories = [e.content for e in recent_entries]
            except Exception:
                pass
        card = role_player.card
        if card is None:
            return None
        hour = now.hour
        time_period = "早上" if 5 <= hour < 12 else ("下午" if 12 <= hour < 18 else "晚上")
        time_str = now.strftime("%H:%M")
        time_style_map = {"早上": "用轻松温暖的语气问候早安。", "下午": "用活泼自然的语气发起话题。", "晚上": "用温柔体贴的语气问候。"}
        time_style = time_style_map.get(time_period, time_style_map["下午"])
        memory_text = ""
        if recent_memories:
            memory_lines = "\n".join(f"- {m}" for m in recent_memories[:3])
            memory_text = f"\n\n最近互动记忆：\n{memory_lines}"
        prompt = f"""你是 {card.name}，性格：{card.personality}，说话风格：{card.speaking_style}。
时间：{time_str} {time_period}。{time_style}{memory_text}
请以角色身份主动发起一段自然的对话（不超过3句话）。直接输出要说的话。"""
        try:
            response = api_client.chat(messages=[{"role": "user", "content": prompt}], temperature=0.9, max_tokens=200)
            return response.content.strip()
        except Exception:
            return None

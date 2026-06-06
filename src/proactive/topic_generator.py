"""话题生成器 - 根据时间段、角色卡和记忆生成话题。"""
from __future__ import annotations
from datetime import datetime
from typing import TYPE_CHECKING
from src.utils.logger import get_logger
if TYPE_CHECKING:
    from src.api_client.deepseek import DeepSeekClient
    from src.chat_engine.card_parser import Card

class TopicGenerator:
    _TIME_PERIOD_CONFIG: dict[str, dict[str, str]] = {
        "早上": {"label": "早上", "topic_direction": "早安问候、关心睡眠", "style": "轻松温暖"},
        "下午": {"label": "下午", "topic_direction": "分享趣事、关心状态", "style": "活泼自然"},
        "晚上": {"label": "晚上", "topic_direction": "关心一天、提醒休息", "style": "温柔体贴"},
    }

    def __init__(self) -> None:
        self._log = get_logger()

    def generate_topic(self, card: "Card", memories: list[str], time_of_day: str | None = None, api_client: "DeepSeekClient | None" = None) -> str:
        if time_of_day is None:
            time_of_day = self._get_time_period(datetime.now())
        if api_client is not None:
            return self._generate_with_ai(card, memories, time_of_day, api_client)
        return self._generate_with_template(card, time_of_day)

    @staticmethod
    def _get_time_period(now: datetime) -> str:
        hour = now.hour
        if 5 <= hour < 12:
            return "早上"
        elif 12 <= hour < 18:
            return "下午"
        return "晚上"

    def _generate_with_ai(self, card: "Card", memories: list[str], time_of_day: str, api_client: "DeepSeekClient") -> str:
        config = self._TIME_PERIOD_CONFIG[time_of_day]
        memory_text = ""
        if memories:
            memory_lines = "\n".join(f"- {m}" for m in memories[:3])
            memory_text = f"\n\n最近互动记忆：\n{memory_lines}"
        prompt = f"""你是 {card.name}，性格：{card.personality}，风格：{card.speaking_style}。
时间是{time_of_day}。话题方向：{config['topic_direction']}。风格：{config['style']}。{memory_text}
请生成一段自然的话题开场白（不超过3句话）。直接输出。"""
        try:
            response = api_client.chat(messages=[{"role": "user", "content": prompt}], temperature=0.9, max_tokens=200)
            return response.content.strip()
        except Exception:
            return self._generate_with_template(card, time_of_day)

    def _generate_with_template(self, card: "Card", time_of_day: str) -> str:
        import random
        templates = {
            "早上": ["早上好呀~今天天气不错呢！", "新的一天开始啦！有什么计划吗？", "早安~感觉今天会是美好的一天！"],
            "下午": ["下午好~今天忙了些什么呀？", "午后的阳光好舒服呢~", "嘿，下午茶时间到了！"],
            "晚上": ["晚上好~今天过得怎么样？", "天都黑了，今天很充实吧？", "晚安前想和你聊聊天~"]
        }
        options = templates.get(time_of_day, templates["下午"])
        return random.choice(options)

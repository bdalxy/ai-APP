"""话题生成器模块。

根据角色卡信息、最近记忆和当前时间段，调用 AI 生成自然的主动聊天话题。
三个时间段（早上/下午/晚上）各有不同的风格和话题方向。

依赖：
    - src.api_client.deepseek: DeepSeekClient（调用 AI 生成话题）
    - src.chat_engine.card_parser: Card（角色卡数据结构）
    - src.utils.logger: get_logger 日志实例
"""

from __future__ import annotations

import random
from datetime import datetime
from typing import TYPE_CHECKING

from src.utils.logger import get_logger

if TYPE_CHECKING:
    from src.api_client.deepseek import DeepSeekClient
    from src.chat_engine.card_parser import Card


class TopicGenerator:
    """话题生成器。

    根据时间段、角色卡和记忆，调用 AI 生成自然的话题内容。
    可独立使用，也可被 ProactiveEngine 调用。
    """

    _TIME_PERIOD_CONFIG: dict[str, dict[str, str]] = {
        "早上": {
            "label": "早上",
            "topic_direction": "早安问候、关心睡眠质量、分享清晨心情、聊聊今天的计划",
            "style": "轻松温暖，语气柔和，像刚睡醒的朋友在打招呼",
        },
        "下午": {
            "label": "下午",
            "topic_direction": "分享趣事、关心工作/学习状态、聊聊下午茶或休闲话题",
            "style": "活泼自然，可以带点幽默感，像朋友在工作间隙闲聊",
        },
        "晚上": {
            "label": "晚上",
            "topic_direction": "关心一天过得如何、聊聊晚餐、提醒休息、晚安问候",
            "style": "温柔体贴，让人放松，像睡前朋友发来的关心",
        },
    }

    def __init__(self) -> None:
        self._log = get_logger()
        self._log.info("TopicGenerator 初始化完成")

    def generate_topic(self, card: "Card", memories: list[str], time_of_day: str | None = None, api_client: "DeepSeekClient | None" = None, user_preferences: list[str] | None = None) -> str:
        if time_of_day is None:
            time_of_day = self._get_time_period(datetime.now())
        if time_of_day not in self._TIME_PERIOD_CONFIG:
            raise ValueError(f"无效的时间段: {time_of_day}")
        prefs = user_preferences or []
        self._log.info(f"[话题生成] 时间段={time_of_day}, 记忆数={len(memories)}, 偏好数={len(prefs)}, API={'可用' if api_client else '不可用'}")
        if api_client is not None:
            return self._generate_with_ai(card, memories, time_of_day, api_client, prefs)
        else:
            return self._generate_with_template(card, time_of_day)

    def generate_morning_topic(self, card: "Card", memories: list[str]) -> str:
        return self._generate_with_template(card, "早上")

    def generate_afternoon_topic(self, card: "Card", memories: list[str]) -> str:
        return self._generate_with_template(card, "下午")

    def generate_evening_topic(self, card: "Card", memories: list[str]) -> str:
        return self._generate_with_template(card, "晚上")

    @staticmethod
    def _get_time_period(now: datetime) -> str:
        hour = now.hour
        if 5 <= hour < 12:
            return "早上"
        elif 12 <= hour < 18:
            return "下午"
        else:
            return "晚上"

    def _generate_with_ai(self, card: "Card", memories: list[str], time_of_day: str, api_client: "DeepSeekClient", user_preferences: list[str]) -> str:
        config = self._TIME_PERIOD_CONFIG[time_of_day]
        memory_text = ""
        if memories:
            memory_lines = "\n".join(f"- {m}" for m in memories[:3])
            memory_text = f"\n\n你和用户最近的互动记忆：\n{memory_lines}"
        preference_text = ""
        if user_preferences:
            pref_lines = "\n".join(f"- {p}" for p in user_preferences[:5])
            preference_text = f"\n\n用户的兴趣和偏好：\n{pref_lines}\n请优先围绕用户的兴趣偏好来发起话题。"
        prompt = f"""你是一个名为 {card.name} 的角色，性格是{card.personality}，说话风格是{card.speaking_style}。

当前是{time_of_day}，你需要主动发起话题。
话题方向：{config['topic_direction']}
风格要求：{config['style']}{memory_text}{preference_text}

请生成一段自然的话题开场白，不超过3句话。
要求：
1. 直接以角色身份说话，不要加任何前缀或说明。
2. 语气自然，像一个真实的朋友在聊天。
3. 严格遵循角色的性格和说话风格。
4. 不要使用"你好"、"在吗"等机械开场白。"""
        try:
            response = api_client.chat(messages=[{"role": "user", "content": prompt}], temperature=0.9, max_tokens=200)
            result = response.content.strip()
            self._log.info(f"[话题生成] AI 生成成功: {result[:50]}...")
            return result
        except Exception as e:
            self._log.warning(f"[话题生成] AI 生成失败，回退到模板: {e}")
            return self._generate_with_template(card, time_of_day)

    def _generate_with_template(self, card: "Card", time_of_day: str) -> str:
        name = card.nickname or card.name
        templates = {
            "早上": [
                f"早上好呀~今天天气不错呢，昨晚睡得好吗？",
                f"新的一天开始啦！今天有什么计划吗？",
                f"早安~刚刚醒来，感觉今天会是美好的一天呢！",
            ],
            "下午": [
                f"下午好~今天忙了些什么呀？",
                f"午后的阳光好舒服，让人忍不住想打个盹呢~",
                f"嘿，下午茶时间到了！今天有什么有趣的事情吗？",
            ],
            "晚上": [
                f"晚上好~今天过得怎么样？",
                f"天都黑了，今天一定很充实吧？",
                f"晚安前想和你聊聊天，今天有什么开心的事吗？",
            ],
        }
        options = templates.get(time_of_day, templates["下午"])
        topic = random.choice(options)
        self._log.info(f"[话题生成] 模板生成: {topic[:50]}...")
        return topic

"""主动消息决策引擎模块。

负责判断是否应该发送主动消息，以及生成主动消息内容。
P1 阶段为手动触发模式（/proactive 命令），定时推送留到 P2。

决策因子：
    1. 用户是否开启了主动消息（proactive_enabled）
    2. 当前时间是否在用户活跃时段（wake_time ~ sleep_time）
    3. 距离上次发送是否超过最小间隔（proactive_interval_minutes）

依赖：
    - src.config.user_settings: UserSettings 单例
    - src.chat_engine.role_player: RolePlayer（获取角色信息）
    - src.memory.retriever: MemoryRetriever（获取最近记忆）
    - src.api_client.deepseek: DeepSeekClient（生成主动消息）
    - src.utils.logger: get_logger 日志实例
    - src.utils.time_utils: is_within_time_range, now_cst, time_diff_seconds
"""

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
    """主动消息决策引擎。

    整合决策逻辑和消息生成，提供 decide_and_generate() 一站式方法。
    """

    _SKIP_DISABLED = "主动消息功能未开启"
    _SKIP_NOT_ACTIVE_TIME = "当前不在活跃时段"
    _SKIP_INTERVAL_NOT_MET = "距离上次发送时间未满间隔"
    _SKIP_NO_TOPIC = "无法生成合适的话题"

    def __init__(self) -> None:
        self._log = get_logger()
        self._last_sent_time: datetime | None = None
        self._log.info("ProactiveEngine 初始化完成")

    def decide_and_generate(
        self,
        card: "RolePlayer",
        retriever: "MemoryRetriever | None",
        api_client: "DeepSeekClient",
        last_sent_time: datetime | None = None,
        now: datetime | None = None,
    ) -> str | None:
        if now is None:
            now = now_cst()
        proactive_enabled = user_settings.get("proactive_enabled")
        if not proactive_enabled:
            self._log.info(f"[主动消息] 跳过: {self._SKIP_DISABLED}")
            return None
        if not self.should_send_message(now, last_sent_time):
            return None
        message = self.generate_proactive_message(card, retriever, api_client, now)
        return message

    def should_send_message(self, current_time: datetime | None = None, last_sent_time: datetime | None = None) -> bool:
        if current_time is None:
            current_time = now_cst()
        wake_time = user_settings.get("wake_time")
        sleep_time = user_settings.get("sleep_time")
        if isinstance(wake_time, str) and isinstance(sleep_time, str):
            if not is_within_time_range(wake_time, sleep_time, current_time):
                self._log.info(f"[主动消息] 跳过: {self._SKIP_NOT_ACTIVE_TIME} ({wake_time} ~ {sleep_time})")
                return False
        if last_sent_time is not None:
            interval_minutes = user_settings.get("proactive_interval_minutes")
            if isinstance(interval_minutes, (int, float)):
                elapsed_seconds = time_diff_seconds(last_sent_time, current_time)
                elapsed_minutes = elapsed_seconds / 60.0
                if elapsed_minutes < float(interval_minutes):
                    remaining = float(interval_minutes) - elapsed_minutes
                    self._log.info(f"[主动消息] 跳过: {self._SKIP_INTERVAL_NOT_MET} (已过 {elapsed_minutes:.0f} 分钟, 还需等待 {remaining:.0f} 分钟)")
                    return False
        self._log.info("[主动消息] 决策通过，允许发送")
        return True

    def generate_proactive_message(self, role_player: "RolePlayer", retriever: "MemoryRetriever | None", api_client: "DeepSeekClient", now: datetime | None = None) -> str | None:
        if now is None:
            now = now_cst()
        recent_memories: list[str] = []
        if retriever is not None:
            try:
                recent_entries = retriever.get_recent(top_k=5)
                recent_memories = [e.content for e in recent_entries]
            except Exception as e:
                self._log.warning(f"[主动消息] 获取最近记忆失败: {e}")
        card = role_player.card
        if card is None:
            self._log.warning("[主动消息] 角色卡未加载，无法生成主动消息")
            return None
        prompt = self._build_proactive_prompt(
            card_name=card.name, card_personality=card.personality,
            card_speaking_style=card.speaking_style, recent_memories=recent_memories, now=now,
        )
        try:
            response = api_client.chat(messages=[{"role": "user", "content": prompt}], temperature=0.9, max_tokens=200)
            result = response.content.strip()
            self._log.info(f"[主动消息] 生成成功: {result[:50]}...")
            return result
        except Exception as e:
            self._log.error(f"[主动消息] 生成失败: {e}")
            return None

    def _get_time_period(self, now: datetime) -> str:
        hour = now.hour
        if 5 <= hour < 12:
            return "早上"
        elif 12 <= hour < 18:
            return "下午"
        else:
            return "晚上"

    def _build_proactive_prompt(self, card_name: str, card_personality: str, card_speaking_style: str, recent_memories: list[str], now: datetime) -> str:
        time_period = self._get_time_period(now)
        time_str = now.strftime("%H:%M")
        time_style_map = {
            "早上": "用轻松温暖的语气问候早安，可以关心一下对方昨晚睡得好不好，分享今天的好天气或心情。",
            "下午": "用活泼自然的语气发起话题，可以聊聊今天遇到的有趣事情，或者关心对方在做什么。",
            "晚上": "用温柔体贴的语气问候，关心对方今天过得怎么样，提醒早点休息。",
        }
        time_style = time_style_map.get(time_period, time_style_map["下午"])
        memory_text = ""
        if recent_memories:
            memory_lines = "\n".join(f"- {m}" for m in recent_memories[:3])
            memory_text = f"\n\n你和用户最近的互动记忆：\n{memory_lines}"
        prompt = f"""你是一个名为 {card_name} 的角色，正在和用户进行日常聊天。

你的性格：{card_personality}
你的说话风格：{card_speaking_style}

现在的时间是 {time_str}，{time_period}。
{time_style}{memory_text}

请以 {card_name} 的身份，主动向用户发起一段自然的对话。要求：
1. 不要超过 3 句话，保持简洁自然。
2. 基于最近记忆和当前时间段，让对话内容合理且有趣。
3. 严格遵循你的性格和说话风格。
4. 不要提及"记忆"、"角色"等设定，像真实朋友一样聊天。
5. 不要使用"你好"、"在吗"等过于机械的开场白。

请直接输出你要说的话，不要加任何前缀或说明。"""
        return prompt

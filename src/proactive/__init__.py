"""主动消息引擎模块。

提供主动消息的决策、生成和调度功能。

P1 阶段（手动触发）：
    - ProactiveEngine: 决策引擎，判断是否发送 + 生成消息
    - TopicGenerator: 话题生成器，根据时间段和角色生成话题
    - ProactiveScheduler: 调度器，记录上次发送时间

P2 阶段（定时推送）：
    - 将扩展 ProactiveScheduler 的定时检查功能

使用方式:
    from src.proactive import ProactiveEngine, TopicGenerator, ProactiveScheduler

    engine = ProactiveEngine()
    scheduler = ProactiveScheduler()
    generator = TopicGenerator()

    # 手动触发
    message = engine.decide_and_generate(player, retriever, client,
                                         last_sent_time=scheduler.get_last_sent_time())
    if message:
        print(message)
        scheduler.update_last_sent_time()
"""

from src.proactive.engine import ProactiveEngine
from src.proactive.topic_generator import TopicGenerator
from src.proactive.scheduler import ProactiveScheduler

__all__ = [
    "ProactiveEngine",
    "TopicGenerator",
    "ProactiveScheduler",
]

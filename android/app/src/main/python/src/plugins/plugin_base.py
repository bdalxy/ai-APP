"""插件基类 — 定义管道钩子接口"""

import time
from abc import ABC
from typing import Optional, List


class BasePlugin(ABC):
    """插件基类。
    
    所有插件必须继承此类。插件可以通过重写钩子方法在对话管道中插入自定义逻辑。
    
    钩子执行顺序：
        1. pre_process(user_input) → 修改/过滤用户输入
        2. [AI 生成回复]
        3. post_process(ai_reply) → 修改/增强 AI 回复
        4. on_turn_end(user_input, ai_reply) → 回合结束回调
        5. on_memory_extracted(memory) → 新记忆提取回调
    """

    # ---- 插件元信息（子类必须定义） ----
    name: str = "base"
    """插件名称（唯一标识）"""

    version: str = "1.0.0"
    """插件版本"""

    description: str = "基础插件"
    """插件描述"""

    enabled: bool = True
    """是否启用"""

    category: str = "script"
    """插件分类：chat(对话增强) / appearance(外观美化) / script(脚本工具)"""

    author: str = "AI Companion Team"
    """插件作者"""

    icon: str = "sparkle"
    """插件图标标识"""

    # ---- 依赖与冲突（预留） ----
    dependencies: List[str] = []
    """依赖的其他插件名称列表"""

    conflicts: List[str] = []
    """冲突的插件名称列表"""

    # ---- 统计字段（由 PluginManager 维护，实例级别避免多实例共享） ----

    def __init__(self):
        self._call_count: int = 0
        self._error_count: int = 0
        self._install_time: float = time.time()
        self._last_call_time: float = 0.0
        self._last_error: str = ""

    def pre_process(self, user_input: str) -> Optional[str]:
        """预处理用户输入。
        
        在发送给 AI 之前调用。可以修改、过滤或记录用户输入。
        
        Args:
            user_input: 用户原始输入
        
        Returns:
            修改后的用户输入，或 None 表示不修改。
            返回空字符串 "" 表示跳过此输入（不发送给 AI）。
        """
        return None

    def post_process(self, ai_reply: str) -> Optional[str]:
        """后处理 AI 回复。
        
        在 AI 生成回复后、显示给用户之前调用。
        
        Args:
            ai_reply: AI 原始回复
        
        Returns:
            修改后的 AI 回复，或 None 表示不修改。
        """
        return None

    def on_turn_end(self, user_input: str, ai_reply: str) -> None:
        """回合结束回调。
        
        在完整对话回合结束后调用（AI 回复已显示给用户）。
        可用于日志记录、统计、触发通知等。
        
        Args:
            user_input: 用户输入
            ai_reply: AI 回复
        """
        pass

    def on_memory_extracted(self, memory: dict) -> None:
        """新记忆提取回调。
        
        当系统从对话中提取出新记忆时调用。
        memory 格式: {"content": "...", "memory_type": "...", "importance": 0.8}
        
        Args:
            memory: 提取的记忆数据
        """
        pass
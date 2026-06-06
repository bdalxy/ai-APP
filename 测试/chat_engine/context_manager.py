# -*- coding: utf-8 -*-
"""
上下文管理器
管理对话历史上下文，支持长对话和记忆检索
"""

import logging
from typing import List, Dict, Any, Optional
from collections import deque

logger = logging.getLogger(__name__)


class ContextManager:
    """对话上下文管理器"""
    
    def __init__(
        self,
        max_history: int = 20,
        max_tokens: int = 4000
    ):
        """
        初始化上下文管理器
        
        Args:
            max_history: 最大保存的历史消息数
            max_tokens: 最大 token 数（估算）
        """
        self.max_history = max_history
        self.max_tokens = max_tokens
        self.messages = deque(maxlen=max_history)
        self.system_prompt = None
        
        logger.info(f"上下文管理器初始化完成 (max_history={max_history})")
    
    def add_message(
        self,
        role: str,
        content: str,
        metadata: Dict[str, Any] = None
    ):
        """
        添加消息到上下文
        
        Args:
            role: 角色 (user / assistant / system)
            content: 消息内容
            metadata: 额外元数据
        """
        message = {
            "role": role,
            "content": content
        }
        
        if metadata:
            message["metadata"] = metadata
        
        self.messages.append(message)
        logger.debug(f"添加消息: {role} ({len(content)} 字符)")
    
    def add_user_message(self, content: str):
        """添加用户消息"""
        self.add_message("user", content)
    
    def add_assistant_message(self, content: str):
        """添加助手消息"""
        self.add_message("assistant", content)
    
    def set_system_prompt(self, prompt: str):
        """
        设置系统提示词
        
        Args:
            prompt: 系统提示词文本
        """
        self.system_prompt = prompt
        logger.debug("系统提示词已设置")
    
    def get_messages(self) -> List[Dict[str, str]]:
        """
        获取消息列表
        
        Returns:
            消息字典列表
        """
        return list(self.messages)
    
    def get_recent_messages(self, count: int = 10) -> List[Dict[str, str]]:
        """
        获取最近的消息
        
        Args:
            count: 消息数量
            
        Returns:
            最近的消息列表
        """
        messages = list(self.messages)
        return messages[-count:] if len(messages) > count else messages
    
    def get_conversation_string(self) -> str:
        """
        获取对话文本
        
        Returns:
            格式化的对话文本
        """
        lines = []
        for msg in self.messages:
            role = msg["role"]
            content = msg["content"]
            if role == "user":
                lines.append(f"用户: {content}")
            elif role == "assistant":
                lines.append(f"助手: {content}")
            else:
                lines.append(f"{role}: {content}")
        
        return "\n".join(lines)
    
    def clear(self):
        """清除所有消息"""
        self.messages.clear()
        logger.debug("上下文已清除")
    
    def get_context_length(self) -> int:
        """
        获取上下文长度
        
        Returns:
            消息数量
        """
        return len(self.messages)
    
    def estimate_tokens(self) -> int:
        """
        估算当前上下文的 token 数
        
        Returns:
            估算的 token 数
        """
        # 简单估算：中文约 2 字符 = 1 token，英文约 4 字符 = 1 token
        total_chars = 0
        for msg in self.messages:
            total_chars += len(msg["content"])
        
        # 添加角色标签的开销
        total_chars += len(self.messages) * 10
        
        return total_chars // 3  # 粗略估算
    
    def is_near_limit(self) -> bool:
        """
        检查是否接近 token 限制
        
        Returns:
            是否接近限制
        """
        estimated = self.estimate_tokens()
        return estimated > self.max_tokens * 0.8
    
    def trim_context(self, target_messages: int = None):
        """
        裁剪上下文
        
        Args:
            target_messages: 目标消息数，默认为一半
        """
        if target_messages is None:
            target_messages = self.max_history // 2
        
        if len(self.messages) > target_messages:
            # 保留前几条和后几条
            keep_first = target_messages // 3
            keep_last = target_messages - keep_first
            
            all_messages = list(self.messages)
            trimmed = all_messages[:keep_first] + all_messages[-keep_last:]
            
            self.messages.clear()
            for msg in trimmed:
                self.messages.append(msg)
            
            logger.info(f"上下文已裁剪为 {len(trimmed)} 条消息")
    
    def to_dict(self) -> Dict[str, Any]:
        """
        导出为字典
        
        Returns:
            包含上下文信息的字典
        """
        return {
            "messages": list(self.messages),
            "system_prompt": self.system_prompt,
            "stats": {
                "message_count": len(self.messages),
                "estimated_tokens": self.estimate_tokens(),
                "max_history": self.max_history,
                "max_tokens": self.max_tokens
            }
        }
    
    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> 'ContextManager':
        """
        从字典恢复上下文管理器
        
        Args:
            data: 包含上下文信息的字典
            
        Returns:
            ContextManager 实例
        """
        manager = cls(
            max_history=data.get("max_history", 20),
            max_tokens=data.get("max_tokens", 4000)
        )
        
        if "messages" in data:
            for msg in data["messages"]:
                manager.messages.append(msg)
        
        if "system_prompt" in data:
            manager.system_prompt = data["system_prompt"]
        
        return manager

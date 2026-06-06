# -*- coding: utf-8 -*-
"""
API 客户端基类
定义不同 AI API 的统一接口
"""

from abc import ABC, abstractmethod
from typing import List, Dict, Any, Optional


class BaseAPIClient(ABC):
    """AI API 客户端抽象基类"""
    
    @abstractmethod
    def chat(
        self,
        messages: List[Dict[str, str]],
        system_prompt: str = None,
        temperature: float = 0.7,
        max_tokens: int = 2048,
        **kwargs
    ) -> Dict[str, Any]:
        """
        发送对话请求
        
        Args:
            messages: 消息列表
            system_prompt: 系统提示词
            temperature: 温度参数
            max_tokens: 最大 token 数
            **kwargs: 其他参数
            
        Returns:
            API 响应字典
        """
        pass
    
    @abstractmethod
    def chat_simple(
        self,
        user_message: str,
        system_prompt: str = None,
        **kwargs
    ) -> str:
        """
        简化的对话接口
        
        Args:
            user_message: 用户消息
            system_prompt: 系统提示词
            **kwargs: 其他参数
            
        Returns:
            AI 回复文本
        """
        pass
    
    @abstractmethod
    def test_connection(self) -> Dict[str, Any]:
        """
        测试连接状态
        
        Returns:
            连接状态字典
        """
        pass
    
    @abstractmethod
    def close(self):
        """关闭客户端，释放资源"""
        pass

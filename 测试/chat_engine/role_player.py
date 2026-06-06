# -*- coding: utf-8 -*-
"""
角色扮演执行器
整合角色卡、API客户端和记忆系统，提供完整的角色扮演对话功能
"""

import os
import logging
from typing import List, Dict, Any, Optional, TYPE_CHECKING

if TYPE_CHECKING:
    from api_client.deepseek import DeepSeekAPIClient
    from memory.storage import MemoryStorage

logger = logging.getLogger(__name__)


class RolePlayer:
    """角色扮演执行器"""
    
    def __init__(
        self,
        memory_storage: 'MemoryStorage' = None,
        config: Dict[str, Any] = None
    ):
        """
        初始化角色扮演执行器
        
        Args:
            memory_storage: 记忆存储实例
            config: 配置字典
        """
        self.memory_storage = memory_storage
        self.config = config or {}
        self.character_card = None
        self.api_client = None
        self.context_manager = None
        
        # 加载上下文管理器
        from .context_manager import ContextManager
        self.context_manager = ContextManager(
            max_history=self.config.get('MAX_CONTEXT_LENGTH', 20)
        )
        
        logger.info("角色扮演执行器初始化完成")
    
    def load_character_card(self, card_path: str) -> bool:
        """
        加载角色卡
        
        Args:
            card_path: 角色卡文件路径
            
        Returns:
            是否加载成功
        """
        from .card_parser import CardParser, CharacterCard
        
        try:
            card = CardParser.load_from_file(card_path)
            if card:
                self.character_card = card
                logger.info(f"角色卡加载成功: {card.name}")
                return True
            else:
                logger.error("角色卡加载失败")
                return False
        except Exception as e:
            logger.error(f"加载角色卡异常: {e}")
            return False
    
    def load_character(self, card_data: Dict[str, Any]) -> bool:
        """
        加载角色卡数据
        
        Args:
            card_data: 角色卡字典数据
            
        Returns:
            是否加载成功
        """
        from .card_parser import CardParser, CharacterCard
        
        try:
            card = CardParser.parse(card_data)
            if card:
                self.character_card = card
                logger.info(f"角色卡加载成功: {card.name}")
                return True
            return False
        except Exception as e:
            logger.error(f"加载角色卡异常: {e}")
            return False
    
    def set_api_client(self, api_client: 'DeepSeekAPIClient'):
        """
        设置 API 客户端
        
        Args:
            api_client: DeepSeek API 客户端实例
        """
        self.api_client = api_client
        logger.info("API 客户端已设置")
    
    def chat(
        self,
        user_message: str,
        temperature: float = 0.8,
        max_tokens: int = 2048,
        recall_memory: bool = True,
        **kwargs
    ) -> str:
        """
        发送对话请求
        
        Args:
            user_message: 用户消息
            temperature: 生成温度
            max_tokens: 最大 token 数
            recall_memory: 是否检索记忆
            **kwargs: 其他 API 参数
            
        Returns:
            AI 生成的回复文本
        """
        if not self.api_client:
            logger.error("API 客户端未设置")
            return "抱歉，AI 服务暂不可用。"
        
        if not self.character_card:
            logger.warning("角色卡未加载，使用默认设置")
        
        try:
            # 1. 构建消息列表
            messages = self.context_manager.get_messages()
            messages.append({"role": "user", "content": user_message})
            
            # 2. 检索相关记忆（如果启用）
            memory_context = ""
            if recall_memory and self.memory_storage:
                relevant_memories = self.memory_storage.retrieve(user_message, limit=3)
                if relevant_memories:
                    memory_context = "\n".join([m['content'] for m in relevant_memories])
                    logger.debug(f"检索到 {len(relevant_memories)} 条相关记忆")
            
            # 3. 构建系统提示词
            system_prompt = self._build_system_prompt(memory_context)
            
            # 4. 调用 API
            result = self.api_client.chat(
                messages=messages,
                system_prompt=system_prompt,
                temperature=temperature,
                max_tokens=max_tokens,
                **kwargs
            )
            
            # 5. 提取回复
            if result.get("choices") and len(result["choices"]) > 0:
                reply = result["choices"][0]["message"]["content"]
                
                # 6. 保存对话到上下文
                self.context_manager.add_message("user", user_message)
                self.context_manager.add_message("assistant", reply)
                
                # 7. 保存到记忆（如果启用）
                if self.memory_storage:
                    self.memory_storage.add_conversation(
                        user_message=user_message,
                        ai_response=reply,
                        character_name=self.character_card.name if self.character_card else "AI"
                    )
                
                return reply
            
            return "抱歉，未能获取到有效回复。"
            
        except Exception as e:
            logger.error(f"对话生成失败: {e}")
            return f"抱歉，发生了错误: {str(e)}"
    
    def _build_system_prompt(self, memory_context: str = "") -> str:
        """
        构建系统提示词
        
        Args:
            memory_context: 记忆上下文
            
        Returns:
            系统提示词文本
        """
        if not self.character_card:
            return "你是一个友好的 AI 助手。"
        
        # 获取角色卡的基础提示词
        prompt = self.character_card.build_system_prompt(include_examples=True)
        
        # 添加记忆上下文
        if memory_context:
            prompt += f"\n\n【相关记忆】\n{memory_context}"
        
        # 添加角色扮演指导
        prompt += "\n\n【角色扮演规则】\n"
        prompt += "1. 始终保持角色设定，用角色的性格和风格进行对话\n"
        prompt += "2. 可以自由发挥，描述角色的动作、表情和心理活动\n"
        prompt += "3. 回复应该是自然、口语化的对话形式\n"
        prompt += "4. 如果不确定如何回复，可以描述角色的反应和思考过程"
        
        return prompt
    
    def clear_context(self):
        """清除对话上下文"""
        if self.context_manager:
            self.context_manager.clear()
        logger.info("对话上下文已清除")
    
    def get_context_summary(self) -> Dict[str, Any]:
        """
        获取上下文摘要
        
        Returns:
            包含上下文信息的字典
        """
        return {
            "character_name": self.character_card.name if self.character_card else None,
            "character_loaded": self.character_card is not None,
            "api_client_configured": self.api_client is not None,
            "memory_enabled": self.memory_storage is not None,
            "context_length": len(self.context_manager.messages) if self.context_manager else 0
        }
    
    def set_temperature(self, temperature: float):
        """
        设置默认温度
        
        Args:
            temperature: 温度值 (0.0-2.0)
        """
        self.default_temperature = max(0.0, min(2.0, temperature))
        logger.info(f"默认温度已设置为: {self.default_temperature}")
    
    def export_conversation(self, format: str = "text") -> str:
        """
        导出对话记录
        
        Args:
            format: 导出格式 (text / json)
            
        Returns:
            格式化的对话文本或 JSON
        """
        if not self.context_manager:
            return ""
        
        if format == "json":
            import json
            return json.dumps({
                "character": self.character_card.to_dict() if self.character_card else None,
                "messages": self.context_manager.messages
            }, ensure_ascii=False, indent=2)
        else:
            # 文本格式
            lines = []
            if self.character_card:
                lines.append(f"=== 角色: {self.character_card.name} ===\n")
            
            for msg in self.context_manager.messages:
                role = "用户" if msg["role"] == "user" else "AI"
                lines.append(f"{role}: {msg['content']}")
            
            return "\n".join(lines)

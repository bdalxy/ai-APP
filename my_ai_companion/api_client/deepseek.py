# -*- coding: utf-8 -*-
"""
DeepSeek API 客户端
云端模式 AI 调用模块
"""

import os
import json
import logging
from typing import List, Dict, Optional, Any
import requests

logger = logging.getLogger(__name__)

def mask_api_key(api_key: str) -> str:
    """API Key 脱敏"""
    if not api_key:
        return "***"
    if len(api_key) <= 8:
        return "***"
    return api_key[:4] + "*" * (len(api_key) - 8) + api_key[-4:]

class APIError(Exception):
    """API 调用异常"""
    def __init__(self, message: str, response: requests.Response = None):
        super().__init__(message)
        self.message = message
        self.response = response
        self.error_data = response.json() if (response and response.text) else {}

from .base import BaseAPIClient


class DeepSeekAPIClient(BaseAPIClient):
    """DeepSeek API 客户端封装"""
    
    DEFAULT_API_URL = "https://api.deepseek.com/v1/chat/completions"
    DEFAULT_MODEL = "deepseek-v4"
    SUPPORTED_MODELS = ["deepseek-v4", "deepseek-chat", "deepseek-coder", "deepseek-reasoner"]
    
    def __init__(
        self,
        api_key: str = None,
        api_url: str = None,
        model: str = None,
        timeout: int = 60,
        max_retries: int = 3
    ):
        self._api_key = api_key or os.getenv('DEEPSEEK_API_KEY')
        self.api_url = api_url or os.getenv('DEEPSEEK_API_URL', self.DEFAULT_API_URL)
        self.model = model or os.getenv('MODEL_NAME', self.DEFAULT_MODEL)
        self.timeout = timeout
        self.max_retries = max_retries
        
        if not self._api_key:
            raise ValueError("DeepSeek API Key 未设置！")
        
        self.session = requests.Session()
        self.session.headers.update({
            'Authorization': f'Bearer {self._api_key}',
            'Content-Type': 'application/json'
        })
        
        logger.info(f"DeepSeek API 客户端初始化完成")
        logger.info(f"API 端点: {self.api_url}")
        logger.info(f"使用模型: {self.model}")
        logger.info(f"API Key: {mask_api_key(self._api_key)}")
    
    def chat(
        self,
        messages: List[Dict[str, str]],
        system_prompt: str = None,
        temperature: float = 0.7,
        max_tokens: int = 2048,
        top_p: float = 1.0,
        frequency_penalty: float = 0.0,
        presence_penalty: float = 0.0,
        stop: List[str] = None,
        stream: bool = False,
        **kwargs
    ) -> Dict[str, Any]:
        """发送对话请求"""
        full_messages = []
        if system_prompt:
            full_messages.append({"role": "system", "content": system_prompt})
        full_messages.extend(messages)
        
        payload = {
            "model": self.model,
            "messages": full_messages,
            "temperature": temperature,
            "max_tokens": max_tokens,
            "top_p": top_p,
            "frequency_penalty": frequency_penalty,
            "presence_penalty": presence_penalty,
            "stream": stream,
            **{k: v for k, v in kwargs.items() if k not in ["model", "messages"]}
        }
        if stop:
            payload["stop"] = stop
        
        return self._request(payload)
    
    def _request(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        """发送 HTTP 请求"""
        last_error = None
        
        for attempt in range(self.max_retries):
            try:
                logger.debug(f"API 请求（第 {attempt + 1} 次尝试）")
                response = self.session.post(self.api_url, json=payload, timeout=self.timeout)
                
                if response.status_code == 200:
                    result = response.json()
                    logger.debug(f"API 响应成功")
                    return result
                elif response.status_code == 401:
                    raise APIError("API 认证失败！请检查 API Key 是否正确。", response)
                elif response.status_code == 403:
                    raise APIError("API 访问被拒绝！可能是 API Key 已失效或余额不足。", response)
                elif response.status_code == 429:
                    raise APIError("API 请求过于频繁！请稍后再试。", response)
                elif response.status_code >= 500:
                    last_error = APIError(f"DeepSeek 服务器错误 ({response.status_code})", response)
                    logger.warning(f"服务器错误，第 {attempt + 1} 次重试...")
                    continue
                else:
                    raise APIError(f"API 请求失败 ({response.status_code}): {response.text}", response)
            except requests.exceptions.Timeout:
                last_error = APIError("API 请求超时！", None)
                logger.warning(f"请求超时，第 {attempt + 1} 次重试...")
            except requests.exceptions.ConnectionError:
                last_error = APIError("网络连接失败！请检查网络状况。", None)
                logger.warning(f"连接失败，第 {attempt + 1} 次重试...")
            except requests.exceptions.RequestException as e:
                last_error = APIError(f"请求异常: {str(e)}", None)
                logger.warning(f"请求异常，第 {attempt + 1} 次重试...")
        
        raise last_error or APIError("API 请求失败", None)
    
    def chat_simple(
        self,
        user_message: str,
        system_prompt: str = None,
        character_card: Dict = None,
        temperature: float = 0.7
    ) -> str:
        """简化的对话接口"""
        messages = [{"role": "user", "content": user_message}]
        
        if character_card:
            role_prompt = self._build_character_prompt(character_card)
            system_prompt = f"{role_prompt}\n\n{system_prompt}" if system_prompt else role_prompt
        
        result = self.chat(messages=messages, system_prompt=system_prompt, temperature=temperature)
        
        if result.get("choices") and len(result["choices"]) > 0:
            return result["choices"][0]["message"]["content"]
        return ""
    
    def _build_character_prompt(self, character_card: Dict) -> str:
        """根据角色卡构建提示词"""
        parts = []
        for field in ["name", "personality", "background", "dialogue_style", "description", "scenario"]:
            if character_card.get(field):
                parts.append(f"【{field}】{character_card[field]}")
        return "\n".join(parts)
    
    def test_connection(self) -> Dict[str, Any]:
        """测试 API 连接状态"""
        try:
            result = self.chat(messages=[{"role": "user", "content": "你好"}], max_tokens=10, temperature=0.1)
            return {"success": True, "message": "API 连接成功！", "model": self.model, "response": result}
        except APIError as e:
            return {"success": False, "message": f"API 连接失败: {str(e)}", "error_type": "APIError"}
        except Exception as e:
            return {"success": False, "message": f"未知错误: {str(e)}", "error_type": "UnknownError"}
    
    def close(self):
        """关闭会话，释放资源"""
        if self.session:
            self.session.close()
            logger.info("DeepSeek API 客户端会话已关闭")

def create_client(api_key: str = None, api_url: str = None, model: str = None) -> DeepSeekAPIClient:
    """创建 DeepSeek API 客户端的便捷函数"""
    return DeepSeekAPIClient(api_key=api_key, api_url=api_url, model=model)

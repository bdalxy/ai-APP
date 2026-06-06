# -*- coding: utf-8 -*-
"""
API 客户端模块
提供对各种 AI API 的封装
"""

from .deepseek import DeepSeekAPIClient, APIError, create_client
from .web_search import WebSearchClient, create_search_client

__all__ = [
    'DeepSeekAPIClient', 
    'APIError', 
    'create_client',
    'WebSearchClient',
    'create_search_client'
]

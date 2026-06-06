# -*- coding: utf-8 -*-
"""
联网搜索模块
提供网络搜索能力，使AI能够获取实时信息
"""

import os
import logging
import requests
from typing import Dict, Any, List, Optional

logger = logging.getLogger(__name__)

class WebSearchClient:
    """联网搜索客户端"""
    
    def __init__(self, api_key: str = None):
        self._api_key = api_key or os.getenv('SEARCH_API_KEY')
        self.session = requests.Session()
        self.session.headers.update({"User-Agent": "WeiXinAI/1.0"})
    
    def search(self, query: str, max_results: int = 5) -> Dict[str, Any]:
        """执行联网搜索"""
        logger.info(f"执行联网搜索: {query}")
        
        search_provider = os.getenv('SEARCH_PROVIDER', 'bing').lower()
        
        try:
            if search_provider == 'bing':
                return self._search_bing(query, max_results)
            elif search_provider == 'google':
                return self._search_google(query, max_results)
            else:
                return self._search_fallback(query, max_results)
        except Exception as e:
            logger.error(f"搜索失败: {e}")
            return {"success": False, "message": str(e), "results": []}
    
    def _search_bing(self, query: str, max_results: int) -> Dict[str, Any]:
        """使用 Bing Search API"""
        endpoint = os.getenv('BING_SEARCH_ENDPOINT', 'https://api.bing.microsoft.com/v7.0/search')
        
        if not self._api_key:
            return self._search_fallback(query, max_results)
        
        params = {"q": query, "count": max_results, "mkt": "zh-CN", "responseFilter": "Webpages"}
        headers = {"Ocp-Apim-Subscription-Key": self._api_key}
        
        response = self.session.get(endpoint, params=params, headers=headers, timeout=30)
        response.raise_for_status()
        
        data = response.json()
        results = []
        if "webPages" in data and "value" in data["webPages"]:
            results = [{"title": item.get("name", ""), "url": item.get("url", ""), 
                        "summary": item.get("snippet", ""), "source": "Bing"} 
                       for item in data["webPages"]["value"][:max_results]]
        
        return {"success": True, "query": query, "results": results}
    
    def _search_google(self, query: str, max_results: int) -> Dict[str, Any]:
        """使用 Google Custom Search API"""
        endpoint = "https://www.googleapis.com/customsearch/v1"
        
        if not self._api_key:
            return self._search_fallback(query, max_results)
        
        cx = os.getenv('GOOGLE_CSE_ID')
        if not cx:
            return self._search_fallback(query, max_results)
        
        params = {"q": query, "num": max_results, "cx": cx, "key": self._api_key}
        
        response = self.session.get(endpoint, params=params, timeout=30)
        response.raise_for_status()
        
        data = response.json()
        results = []
        if "items" in data:
            results = [{"title": item.get("title", ""), "url": item.get("link", ""), 
                        "summary": item.get("snippet", ""), "source": "Google"} 
                       for item in data["items"][:max_results]]
        
        return {"success": True, "query": query, "results": results}
    
    def _search_fallback(self, query: str, max_results: int) -> Dict[str, Any]:
        """备用搜索方法 - 无API时使用"""
        try:
            url = "https://api.duckduckgo.com/"
            params = {"q": query, "format": "json", "no_html": "1", "no_redirect": "1"}
            
            response = self.session.get(url, params=params, timeout=30)
            response.raise_for_status()
            
            data = response.json()
            results = []
            
            if "RelatedTopics" in data:
                for topic in data["RelatedTopics"][:max_results]:
                    if isinstance(topic, dict) and "Text" in topic:
                        results.append({"title": topic.get("Text", ""), 
                                        "url": topic.get("FirstURL", ""), 
                                        "summary": topic.get("Text", ""), 
                                        "source": "DuckDuckGo"})
            
            if not results and "Abstract" in data and data["Abstract"]:
                results.append({"title": data.get("Heading", query), 
                                "url": data.get("AbstractURL", ""), 
                                "summary": data.get("Abstract", ""), 
                                "source": "DuckDuckGo"})
            
            if results:
                return {"success": True, "query": query, "results": results}
        except Exception as e:
            logger.warning(f"备用搜索失败: {e}")
        
        return {"success": True, "query": query, 
                "results": self._generate_sample_results(query),
                "note": "使用演示模式，如需真实搜索请配置 SEARCH_API_KEY"}
    
    def _generate_sample_results(self, query: str) -> List[Dict[str, str]]:
        """生成演示用的示例搜索结果"""
        return [
            {"title": f"关于 {query} 的最新信息", 
             "url": f"https://example.com/search?q={query}", 
             "summary": f"这里是关于 '{query}' 的详细信息摘要。", 
             "source": "演示数据"},
            {"title": f"{query} - 维基百科", 
             "url": f"https://zh.wikipedia.org/wiki/{query}", 
             "summary": f"维基百科关于 '{query}' 的条目。", 
             "source": "演示数据"}
        ]
    
    def close(self):
        """关闭会话"""
        self.session.close()

def create_search_client(api_key: str = None) -> WebSearchClient:
    """创建搜索客户端"""
    return WebSearchClient(api_key=api_key)

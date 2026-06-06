# -*- coding: utf-8 -*-
"""
WeiXinAI Web 服务
提供 REST API 供前端调用
"""

import os
import sys
import logging
from fastapi import FastAPI, HTTPException

logger = logging.getLogger(__name__)
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field
from typing import List, Dict, Any, Optional
import uvicorn

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from chat_engine.card_parser import CardManager, CharacterCard
from api_client.deepseek import DeepSeekAPIClient
from api_client.web_search import WebSearchClient
from core.application import get_app

app = FastAPI(title="WeiXinAI API", version="1.0", docs_url="/docs")

# CORS 安全配置 - 仅允许本地访问
app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost", "http://127.0.0.1"],
    allow_credentials=True,
    allow_methods=["GET", "POST"],
    allow_headers=["*"],
)

card_manager = CardManager(cards_dir="./cards")
card_manager.load_all()
search_client = WebSearchClient()


def _api_client():
    """延迟获取 API 客户端（通过应用容器）"""
    app_instance = get_app()
    return app_instance.api_client if app_instance.is_ready else None

class ChatRequest(BaseModel):
    message: str = Field(..., min_length=1, max_length=2000, description="用户消息内容")
    character_name: Optional[str] = Field(None, max_length=50, description="角色卡名称")
    temperature: float = Field(0.8, ge=0, le=2, description="生成温度")

class ChatResponse(BaseModel):
    success: bool
    reply: str
    character_name: Optional[str] = None
    search_used: bool = False
    search_results: List[Dict] = []

class CharacterListResponse(BaseModel):
    success: bool
    characters: List[Dict[str, Any]]

class CharacterRequest(BaseModel):
    name: str = Field(..., min_length=1, max_length=50, pattern=r'^[\w\u4e00-\u9fa5]+$', description="角色名称")
    personality: str = Field("", max_length=500, description="角色性格")
    background: str = Field("", max_length=2000, description="背景故事")
    dialogue_style: str = Field("", max_length=500, description="对话风格")
    description: str = Field("", max_length=500, description="角色描述")
    scenario: str = Field("", max_length=1000, description="场景设定")

class ConfigRequest(BaseModel):
    api_key: str = Field(..., min_length=10, max_length=200, description="API密钥")
    model: str = Field("deepseek-v4", max_length=50, description="模型名称")
    api_url: Optional[str] = Field(None, max_length=200, description="API地址")

class SearchRequest(BaseModel):
    query: str = Field(..., min_length=1, max_length=200, description="搜索查询")
    max_results: int = Field(5, ge=1, le=20, description="最大结果数")

def mask_sensitive_data(data: str, keep_start: int = 4, keep_end: int = 4) -> str:
    """敏感数据脱敏"""
    if not data or len(data) <= keep_start + keep_end:
        return "***"
    return data[:keep_start] + "*" * (len(data) - keep_start - keep_end) + data[-keep_end:]

@app.get("/api/health")
async def health():
    """健康检查"""
    client = _api_client()
    return {
        "status": "healthy",
        "api_configured": client is not None,
        "model": client.model if client else None
    }

@app.get("/api/characters")
async def list_characters():
    """获取角色卡列表"""
    cards = card_manager.list_cards()
    return CharacterListResponse(
        success=True,
        characters=[{"name": c.name, "personality": c.personality, "dialogue_style": c.dialogue_style} for c in cards]
    )

@app.get("/api/character/{name}")
async def get_character(name: str):
    """获取单个角色卡"""
    card = card_manager.get_card(name)
    if not card:
        raise HTTPException(status_code=404, detail="角色卡不存在")
    return card.to_dict()

@app.post("/api/character")
async def create_character(data: CharacterRequest):
    """创建角色卡"""
    card = CharacterCard(**data.dict())
    errors = card.validate()
    if errors:
        raise HTTPException(status_code=400, detail=errors)
    card_manager.save_card(card)
    return {"success": True, "message": "角色卡创建成功"}

@app.delete("/api/character/{name}")
async def delete_character(name: str):
    """删除角色卡"""
    success = card_manager.delete_card(name)
    if not success:
        raise HTTPException(status_code=404, detail="角色卡不存在")
    return {"success": True, "message": "角色卡删除成功"}

@app.post("/api/config")
async def configure_api(data: ConfigRequest):
    """配置 API Key"""
    app_instance = get_app()
    try:
        app_instance._bootstrapped = False
        app_instance.api_client = DeepSeekAPIClient(**data.dict())
        result = app_instance.api_client.test_connection()
        if result["success"]:
            app_instance._bootstrapped = True
            return {"success": True, "message": "API 配置成功"}
        else:
            app_instance.api_client = None
            raise HTTPException(status_code=400, detail=result["message"])
    except Exception as e:
        app_instance.api_client = None
        raise HTTPException(status_code=400, detail="API 配置失败")

@app.get("/api/config/status")
async def get_config_status():
    """获取配置状态（不返回敏感信息）"""
    client = _api_client()
    return {
        "api_configured": client is not None,
        "model": client.model if client else None
    }

@app.post("/api/chat")
async def chat(request: ChatRequest):
    """发送聊天消息"""
    client = _api_client()
    if not client:
        raise HTTPException(status_code=400, detail="请先配置 API Key")

    card = None
    if request.character_name:
        card = card_manager.get_card(request.character_name)
        if not card:
            raise HTTPException(status_code=404, detail="角色卡不存在")

    try:
        system_prompt = card.build_system_prompt() if card else ""
        response = client.chat_simple(
            user_message=request.message,
            system_prompt=system_prompt,
            temperature=request.temperature
        )
        return ChatResponse(success=True, reply=response, character_name=card.name if card else None)
    except Exception as e:
        logger.error(f"聊天请求处理失败: {e}")
        raise HTTPException(status_code=500, detail="聊天请求处理失败")

@app.post("/api/chat_with_card")
async def chat_with_card(request: ChatRequest):
    """使用角色卡聊天（兼容接口）"""
    return await chat(request)

@app.post("/api/search")
async def search(request: SearchRequest):
    """执行联网搜索"""
    try:
        result = search_client.search(request.query, request.max_results)
        return result
    except Exception as e:
        raise HTTPException(status_code=500, detail="搜索请求处理失败")

@app.post("/api/chat_with_search")
async def chat_with_search(request: ChatRequest):
    """带联网搜索的聊天"""
    client = _api_client()
    if not client:
        raise HTTPException(status_code=400, detail="请先配置 API Key")

    card = None
    if request.character_name:
        card = card_manager.get_card(request.character_name)
        if not card:
            raise HTTPException(status_code=404, detail="角色卡不存在")

    try:
        search_results = None
        search_keywords = ["最新", "现在", "今天", "最近", "新闻", "天气", "股票", "比赛"]
        if any(kw in request.message for kw in search_keywords):
            search_results = search_client.search(request.message, max_results=3)

        system_prompt = card.build_system_prompt() if card else ""
        if search_results and search_results.get("success") and search_results.get("results"):
            search_info = "\n\n【最新搜索信息】\n"
            for i, r in enumerate(search_results["results"], 1):
                search_info += f"{i}. [{r['title']}]({r['url']})\n{r['summary']}\n\n"
            system_prompt += search_info

        response = client.chat_simple(
            user_message=request.message,
            system_prompt=system_prompt,
            temperature=request.temperature
        )

        return ChatResponse(
            success=True,
            reply=response,
            character_name=card.name if card else None,
            search_used=search_results is not None,
            search_results=search_results.get("results", []) if search_results else []
        )
    except Exception as e:
        raise HTTPException(status_code=500, detail="带搜索的聊天请求处理失败")

def start_server(host: str = "127.0.0.1", port: int = 8000):
    """启动 Web 服务"""
    print(f"🚀 WeiXinAI API 服务启动")
    print(f"📍 地址: http://{host}:{port}")
    print(f"📖 文档: http://{host}:{port}/docs")
    print(f"⚠️  安全提示: 服务仅监听本地连接")
    uvicorn.run("api_server:app", host=host, port=port, reload=False)

if __name__ == "__main__":
    start_server()

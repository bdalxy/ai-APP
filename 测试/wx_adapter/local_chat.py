# -*- coding: utf-8 -*-
"""
微信消息适配器 - 本地聊天模式
支持移动端本地部署，AI与用户在同一设备上通过Web界面交互
"""

import os
import sys
import json
import asyncio
from datetime import datetime
from typing import Dict, Any, Optional, List
from pathlib import Path

project_root = Path(__file__).parent.parent
sys.path.insert(0, str(project_root))

from chat_engine.card_parser import CardManager, CharacterCard
from chat_engine.role_player import RolePlayer
from memory.storage import MemoryStorage, get_memory_storage
from api_client.deepseek import DeepSeekAPIClient
from config.settings import load_settings


class LocalChatAdapter:
    """本地聊天适配器"""
    
    def __init__(self):
        self.settings = load_settings()
        self.card_manager = CardManager(cards_dir="cards")
        self.memory = get_memory_storage(level=self.settings.MEMORY_LEVEL)
        
        self.role_player = None
        self._init_role_player()
        
        self.current_conversation_id = "local_chat"
        self.current_character = None
        self.messages = []
    
    def _init_role_player(self):
        """初始化角色播放器"""
        try:
            api_client = DeepSeekAPIClient(
                api_key=self.settings.DEEPSEEK_API_KEY,
                api_url=self.settings.DEEPSEEK_API_URL
            )
            self.role_player = RolePlayer(
                memory=self.memory,
                api_client=api_client
            )
        except Exception as e:
            print(f"初始化角色播放器失败: {e}")
            self.role_player = None
    
    def get_characters(self) -> List[Dict[str, Any]]:
        """获取可用角色卡列表"""
        cards = self.card_manager.list_cards()
        return [card.to_dict() for card in cards]
    
    def select_character(self, character_name: str) -> bool:
        """选择角色卡"""
        card = self.card_manager.get_card(character_name)
        if card:
            self.current_character = card
            if self.role_player:
                self.role_player.current_card = card
            return True
        return False
    
    def create_character(self, character_data: Dict[str, Any]) -> bool:
        """创建新角色卡"""
        try:
            card = CharacterCard.from_dict(character_data)
            self.card_manager.save_card(card)
            return True
        except Exception as e:
            print(f"创建角色卡失败: {e}")
            return False
    
    def delete_character(self, character_name: str) -> bool:
        """删除角色卡"""
        return self.card_manager.delete_card(character_name)
    
    async def send_message(self, message: str) -> str:
        """发送消息并获取回复"""
        if not self.role_player:
            return "抱歉，AI服务未初始化，请检查配置。"
        
        self.memory.save_message(
            conversation_id=self.current_conversation_id,
            role="user",
            content=message
        )
        
        self.messages.append({
            "role": "user",
            "content": message,
            "timestamp": datetime.now().isoformat()
        })
        
        try:
            response = await self.role_player.generate_response(
                message=message,
                conversation_id=self.current_conversation_id
            )
            
            self.memory.save_message(
                conversation_id=self.current_conversation_id,
                role="assistant",
                content=response
            )
            
            self.messages.append({
                "role": "assistant",
                "content": response,
                "timestamp": datetime.now().isoformat()
            })
            
            return response
        except Exception as e:
            error_msg = f"获取回复失败: {str(e)}"
            print(error_msg)
            return error_msg
    
    def get_history(self, limit: int = 50) -> List[Dict[str, Any]]:
        """获取历史消息"""
        return self.messages[-limit:]
    
    def clear_history(self):
        """清空历史消息"""
        self.messages = []
        self.memory.delete_conversation(self.current_conversation_id)
    
    def get_memory_stats(self) -> Dict[str, Any]:
        """获取记忆系统统计"""
        return self.memory.get_stats()
    
    def get_settings(self) -> Dict[str, Any]:
        """获取当前设置"""
        return {
            "memory_level": self.settings.MEMORY_LEVEL,
            "api_url": self.settings.DEEPSEEK_API_URL,
            "model_name": self.settings.MODEL_NAME,
            "platform": self.settings.PLATFORM
        }
    
    def update_settings(self, settings_data: Dict[str, Any]) -> bool:
        """更新设置"""
        try:
            if "memory_level" in settings_data:
                level = settings_data["memory_level"]
                if level in ["basic", "standard", "advanced"]:
                    self.settings.MEMORY_LEVEL = level
                    self.memory = get_memory_storage(level=level)
            if "api_url" in settings_data:
                self.settings.DEEPSEEK_API_URL = settings_data["api_url"]
            if "model_name" in settings_data:
                self.settings.MODEL_NAME = settings_data["model_name"]
            # 重新初始化角色播放器
            self._init_role_player()
            return True
        except Exception as e:
            print(f"更新设置失败: {e}")
            return False


def create_local_chat_app():
    """创建本地聊天应用"""
    from fastapi import FastAPI, Request, WebSocket, WebSocketDisconnect
    from fastapi.middleware.cors import CORSMiddleware
    from fastapi.templating import Jinja2Templates
    import uvicorn
    
    app = FastAPI(title="WeiXinAI 本地聊天", description="移动端本地部署的AI聊天界面")
    
    # CORS 安全配置 - 仅允许本地访问
    app.add_middleware(
        CORSMiddleware,
        allow_origins=["http://localhost", "http://127.0.0.1"],
        allow_credentials=True,
        allow_methods=["GET", "POST"],
        allow_headers=["*"],
    )
    
    chat_adapter = LocalChatAdapter()
    templates = Jinja2Templates(directory="templates")
    
    @app.get("/")
    async def index(request: Request):
        """聊天界面"""
        characters = chat_adapter.get_characters()
        return templates.TemplateResponse("chat.html", {
            "request": request,
            "characters": characters,
            "current_character": chat_adapter.current_character.name if chat_adapter.current_character else None
        })
    
    @app.get("/settings")
    async def settings_page(request: Request):
        """设置页面"""
        settings = chat_adapter.get_settings()
        return templates.TemplateResponse("settings.html", {
            "request": request,
            "settings": settings,
            "memory_levels": ["basic", "standard", "advanced"],
            "memory_descriptions": {
                "basic": "基础模式 (100条消息)",
                "standard": "标准模式 (500条消息)",
                "advanced": "高级模式 (无限制)"
            }
        })
    
    @app.get("/api/characters")
    async def api_get_characters():
        """获取角色列表"""
        characters = chat_adapter.get_characters()
        return {"success": True, "characters": characters}
    
    @app.post("/api/select_character")
    async def api_select_character(data: Dict[str, str]):
        """选择角色"""
        name = data.get("name")
        success = chat_adapter.select_character(name)
        return {"success": success, "character": name}
    
    @app.post("/api/send_message")
    async def api_send_message(data: Dict[str, str]):
        """发送消息"""
        message = data.get("message", "")
        # 输入验证
        if not message or not message.strip():
            return {"success": False, "error": "消息不能为空"}
        if len(message) > 2000:
            return {"success": False, "error": "消息过长（最大2000字符）"}
        
        response = await chat_adapter.send_message(message.strip())
        return {"success": True, "response": response}
    
    @app.get("/api/history")
    async def api_get_history(limit: int = 50):
        """获取历史消息"""
        history = chat_adapter.get_history(limit)
        return {"success": True, "history": history}
    
    @app.post("/api/clear_history")
    async def api_clear_history():
        """清空历史"""
        chat_adapter.clear_history()
        return {"success": True}
    
    @app.get("/api/stats")
    async def api_get_stats():
        """获取统计信息"""
        return {
            "success": True,
            "memory_stats": chat_adapter.get_memory_stats(),
            "current_character": chat_adapter.current_character.name if chat_adapter.current_character else None,
            "message_count": len(chat_adapter.messages)
        }
    
    @app.get("/api/settings")
    async def api_get_settings():
        """获取设置"""
        return {"success": True, "settings": chat_adapter.get_settings()}
    
    @app.post("/api/settings")
    async def api_update_settings(data: Dict[str, Any]):
        """更新设置"""
        success = chat_adapter.update_settings(data)
        return {"success": success, "settings": chat_adapter.get_settings()}
    
    @app.websocket("/ws/chat")
    async def websocket_chat(websocket: WebSocket):
        await websocket.accept()
        try:
            while True:
                data = await websocket.receive_json()
                action = data.get("action")
                
                if action == "send":
                    message = data.get("message", "")
                    response = await chat_adapter.send_message(message)
                    await websocket.send_json({
                        "type": "response",
                        "response": response
                    })
                
                elif action == "select_character":
                    name = data.get("name")
                    success = chat_adapter.select_character(name)
                    await websocket.send_json({
                        "type": "character_selected",
                        "success": success,
                        "name": name
                    })
                
                elif action == "get_history":
                    history = chat_adapter.get_history()
                    await websocket.send_json({
                        "type": "history",
                        "history": history
                    })
                
                elif action == "clear_history":
                    chat_adapter.clear_history()
                    await websocket.send_json({
                        "type": "history_cleared"
                    })
                
                elif action == "get_characters":
                    characters = chat_adapter.get_characters()
                    await websocket.send_json({
                        "type": "characters",
                        "characters": characters
                    })
                
                elif action == "get_settings":
                    settings = chat_adapter.get_settings()
                    await websocket.send_json({
                        "type": "settings",
                        "settings": settings
                    })
                
                elif action == "update_settings":
                    success = chat_adapter.update_settings(data.get("settings", {}))
                    await websocket.send_json({
                        "type": "settings_updated",
                        "success": success,
                        "settings": chat_adapter.get_settings()
                    })
        except WebSocketDisconnect:
            print("WebSocket 连接断开")
    
    return app


def run_local_chat(host: str = "0.0.0.0", port: int = 8000):
    """启动本地聊天服务"""
    import uvicorn
    
    app = create_local_chat_app()
    
    templates_dir = Path("templates")
    templates_dir.mkdir(exist_ok=True)
    
    create_chat_template()
    create_settings_template()
    
    print(f"\n🚀 WeiXinAI 本地聊天服务启动")
    print(f"📍 地址: http://{host}:{port}")
    print(f"📱 移动端访问: 在浏览器中输入设备IP:8000")
    print(f"⚙️ 设置页面: http://{host}:{port}/settings")
    
    uvicorn.run(app, host=host, port=port)


def create_chat_template():
    """创建聊天界面模板"""
    template_content = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
    <title>WeiXinAI - AI 聊天</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); min-height: 100vh; display: flex; flex-direction: column; }
        .header { background: rgba(255,255,255,0.95); padding: 16px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); display: flex; justify-content: space-between; align-items: center; position: sticky; top: 0; z-index: 100; }
        .header h1 { font-size: 18px; color: #333; font-weight: 600; }
        .header-buttons { display: flex; gap: 8px; }
        .header-btn { padding: 8px 12px; border: none; border-radius: 16px; font-size: 14px; cursor: pointer; background: #f0f0f0; color: #333; }
        .header-btn:hover { background: #e0e0e0; }
        .header-btn.settings { background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); color: white; }
        .character-selector { position: relative; }
        .character-btn { padding: 8px 16px; background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); color: white; border: none; border-radius: 20px; font-size: 14px; cursor: pointer; display: flex; align-items: center; gap: 8px; }
        .character-btn:hover { opacity: 0.9; }
        .character-dropdown { position: absolute; right: 0; top: 100%; margin-top: 8px; background: white; border-radius: 12px; box-shadow: 0 4px 20px rgba(0,0,0,0.15); min-width: 200px; overflow: hidden; display: none; z-index: 200; }
        .character-dropdown.show { display: block; }
        .character-item { padding: 12px 16px; cursor: pointer; border-bottom: 1px solid #eee; display: flex; justify-content: space-between; align-items: center; }
        .character-item:last-child { border-bottom: none; }
        .character-item:hover { background: #f5f5f5; }
        .character-item.active { background: #eef2ff; }
        .character-item.active::after { content: '✓'; color: #667eea; font-weight: bold; }
        .chat-container { flex: 1; overflow-y: auto; padding: 16px; padding-bottom: 100px; }
        .message { margin-bottom: 16px; display: flex; animation: fadeIn 0.3s ease; }
        @keyframes fadeIn { from { opacity: 0; transform: translateY(10px); } to { opacity: 1; transform: translateY(0); } }
        .message.user { justify-content: flex-end; }
        .message.assistant { justify-content: flex-start; }
        .message-bubble { max-width: 85%; padding: 12px 16px; border-radius: 20px; }
        .user .message-bubble { background: #07c160; color: white; border-bottom-right-radius: 4px; }
        .assistant .message-bubble { background: white; color: #333; border-bottom-left-radius: 4px; box-shadow: 0 2px 8px rgba(0,0,0,0.08); }
        .message-content { font-size: 15px; line-height: 1.6; word-break: break-word; }
        .message-time { font-size: 11px; opacity: 0.7; margin-top: 4px; text-align: right; }
        .typing-indicator { display: flex; gap: 4px; padding: 8px 16px; background: white; border-radius: 20px; border-bottom-left-radius: 4px; }
        .typing-dot { width: 8px; height: 8px; background: #ccc; border-radius: 50%; animation: typing 1.4s infinite ease-in-out; }
        .typing-dot:nth-child(1) { animation-delay: 0s; }
        .typing-dot:nth-child(2) { animation-delay: 0.2s; }
        .typing-dot:nth-child(3) { animation-delay: 0.4s; }
        @keyframes typing { 0%, 80%, 100% { transform: scale(0.8); opacity: 0.5; } 40% { transform: scale(1); opacity: 1; } }
        .input-area { position: fixed; bottom: 0; left: 0; right: 0; background: rgba(255,255,255,0.98); padding: 12px 16px; padding-bottom: calc(12px + env(safe-area-inset-bottom)); box-shadow: 0 -2px 10px rgba(0,0,0,0.1); display: flex; gap: 12px; }
        .input-wrapper { flex: 1; background: #f0f0f0; border-radius: 24px; padding: 0 16px; display: flex; align-items: center; }
        .input-wrapper input { flex: 1; border: none; background: transparent; padding: 12px 0; font-size: 15px; outline: none; }
        .send-btn { width: 48px; height: 48px; border-radius: 50%; background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); border: none; color: white; font-size: 18px; cursor: pointer; display: flex; align-items: center; justify-content: center; }
        .send-btn:hover { opacity: 0.9; }
        .send-btn:disabled { opacity: 0.5; }
        .empty-state { display: flex; flex-direction: column; align-items: center; justify-content: center; padding: 60px 20px; color: white; text-align: center; }
        .empty-state h2 { font-size: 24px; margin-bottom: 16px; }
        .clear-btn { position: fixed; top: 80px; right: 16px; padding: 8px 16px; background: rgba(255,255,255,0.9); border: none; border-radius: 16px; font-size: 13px; cursor: pointer; box-shadow: 0 2px 8px rgba(0,0,0,0.1); }
        .clear-btn:hover { background: white; }
        .status-bar { font-size: 12px; color: rgba(255,255,255,0.8); text-align: center; padding: 8px; }
    </style>
</head>
<body>
    <div class="header">
        <h1>🤖 WeiXinAI</h1>
        <div class="header-buttons">
            <div class="character-selector">
                <button class="character-btn" onclick="toggleCharacterDropdown()">
                    <span id="current-character">选择角色</span>
                    <span>▼</span>
                </button>
                <div class="character-dropdown" id="character-dropdown">
                    <div class="character-item" onclick="selectCharacter(null)">
                        <span>无角色（通用模式）</span>
                    </div>
                </div>
            </div>
            <button class="header-btn settings" onclick="goToSettings()">⚙️ 设置</button>
        </div>
    </div>
    
    <button class="clear-btn" onclick="clearHistory()" style="display: none;" id="clear-btn">清空记录</button>
    
    <div class="status-bar" id="status-bar">
        记忆级别: <span id="memory-level">标准</span> | 消息数: <span id="message-count">0</span>
    </div>
    
    <div class="chat-container" id="chat-container">
        <div class="empty-state" id="empty-state">
            <h2>👋 你好！</h2>
            <p>开始与 AI 对话吧<br>可以选择一个角色开启角色扮演</p>
        </div>
    </div>
    
    <div class="input-area">
        <div class="input-wrapper">
            <input type="text" id="message-input" placeholder="输入消息..." onkeydown="handleKeyDown(event)">
        </div>
        <button class="send-btn" id="send-btn" onclick="sendMessage()" disabled>→</button>
    </div>
    
    <script>
        let ws;
        let characters = [];
        let messageCount = 0;
        
        function initWebSocket() {
            const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
            const wsUrl = `${protocol}//${window.location.host}/ws/chat`;
            ws = new WebSocket(wsUrl);
            
            ws.onopen = function() {
                console.log('WebSocket 连接成功');
                fetchCharacters();
                loadHistory();
                fetchSettings();
            };
            
            ws.onmessage = function(event) {
                const data = JSON.parse(event.data);
                
                if (data.type === 'response') {
                    addMessage('assistant', data.response);
                    document.getElementById('typing-indicator')?.remove();
                    updateStats();
                } else if (data.type === 'history') {
                    loadHistoryMessages(data.history);
                } else if (data.type === 'characters') {
                    loadCharacters(data.characters);
                } else if (data.type === 'character_selected') {
                    document.getElementById('current-character').textContent = data.name || '选择角色';
                    document.getElementById('character-dropdown').classList.remove('show');
                } else if (data.type === 'history_cleared') {
                    document.getElementById('chat-container').innerHTML = `
                        <div class="empty-state" id="empty-state">
                            <h2>👋 你好！</h2>
                            <p>开始与 AI 对话吧<br>可以选择一个角色开启角色扮演</p>
                        </div>
                    `;
                    document.getElementById('clear-btn').style.display = 'none';
                    messageCount = 0;
                    updateStats();
                } else if (data.type === 'settings') {
                    document.getElementById('memory-level').textContent = data.settings.memory_level;
                } else if (data.type === 'settings_updated') {
                    document.getElementById('memory-level').textContent = data.settings.memory_level;
                }
            };
            
            ws.onerror = function(error) { console.error('WebSocket 错误:', error); };
            ws.onclose = function() { setTimeout(initWebSocket, 5000); };
        }
        
        function fetchCharacters() { sendWsMessage({ action: 'get_characters' }); }
        function fetchSettings() { sendWsMessage({ action: 'get_settings' }); }
        
        function loadCharacters(charList) {
            characters = charList;
            const dropdown = document.getElementById('character-dropdown');
            const currentChar = document.getElementById('current-character').textContent;
            
            charList.forEach(char => {
                const item = document.createElement('div');
                item.className = 'character-item' + (char.name === currentChar ? ' active' : '');
                item.textContent = char.name;
                item.onclick = () => selectCharacter(char.name);
                dropdown.appendChild(item);
            });
        }
        
        function selectCharacter(name) { sendWsMessage({ action: 'select_character', name: name }); }
        function toggleCharacterDropdown() { document.getElementById('character-dropdown').classList.toggle('show'); }
        function loadHistory() { sendWsMessage({ action: 'get_history' }); }
        
        function loadHistoryMessages(messages) {
            const container = document.getElementById('chat-container');
            container.innerHTML = '';
            
            if (messages.length === 0) {
                container.innerHTML = '<div class="empty-state" id="empty-state"><h2>👋 你好！</h2><p>开始与 AI 对话吧<br>可以选择一个角色开启角色扮演</p></div>';
                return;
            }
            
            messages.forEach(msg => addMessage(msg.role, msg.content, msg.timestamp));
            messageCount = messages.length;
            updateStats();
            scrollToBottom();
        }
        
        function sendMessage() {
            const input = document.getElementById('message-input');
            const message = input.value.trim();
            if (!message) return;
            
            input.value = '';
            document.getElementById('send-btn').disabled = true;
            
            addMessage('user', message);
            addTypingIndicator();
            scrollToBottom();
            
            sendWsMessage({ action: 'send', message: message });
        }
        
        function sendWsMessage(data) {
            if (ws && ws.readyState === WebSocket.OPEN) {
                ws.send(JSON.stringify(data));
            }
        }
        
        function addMessage(role, content, timestamp) {
            const container = document.getElementById('chat-container');
            document.getElementById('empty-state')?.remove();
            
            const messageDiv = document.createElement('div');
            messageDiv.className = `message ${role}`;
            const time = timestamp ? formatTime(timestamp) : formatTime(new Date().toISOString());
            
            messageDiv.innerHTML = `<div class="message-bubble"><div class="message-content">${escapeHtml(content)}</div><div class="message-time">${time}</div></div>`;
            container.appendChild(messageDiv);
            messageCount++;
            document.getElementById('clear-btn').style.display = 'block';
        }
        
        function addTypingIndicator() {
            const container = document.getElementById('chat-container');
            const indicator = document.createElement('div');
            indicator.className = 'message assistant';
            indicator.id = 'typing-indicator';
            indicator.innerHTML = '<div class="typing-indicator"><div class="typing-dot"></div><div class="typing-dot"></div><div class="typing-dot"></div></div>';
            container.appendChild(indicator);
            scrollToBottom();
        }
        
        function handleKeyDown(event) {
            if (event.key === 'Enter' && !event.shiftKey) {
                event.preventDefault();
                sendMessage();
            }
        }
        
        function clearHistory() {
            if (confirm('确定要清空所有聊天记录吗？')) {
                sendWsMessage({ action: 'clear_history' });
            }
        }
        
        function goToSettings() { window.location.href = '/settings'; }
        function scrollToBottom() { document.getElementById('chat-container').scrollTop = document.getElementById('chat-container').scrollHeight; }
        
        function formatTime(isoString) {
            const date = new Date(isoString);
            return `${date.getHours().toString().padStart(2, '0')}:${date.getMinutes().toString().padStart(2, '0')}`;
        }
        
        function escapeHtml(text) { const div = document.createElement('div'); div.textContent = text; return div.innerHTML; }
        function updateStats() { document.getElementById('message-count').textContent = messageCount; }
        
        document.getElementById('message-input').addEventListener('input', e => {
            document.getElementById('send-btn').disabled = !e.target.value.trim();
        });
        
        document.addEventListener('click', function(e) {
            const dropdown = document.getElementById('character-dropdown');
            const selector = document.querySelector('.character-selector');
            if (!selector.contains(e.target)) dropdown.classList.remove('show');
        });
        
        document.addEventListener('DOMContentLoaded', initWebSocket);
    </script>
</body>
</html>
    """
    
    with open("templates/chat.html", "w", encoding="utf-8") as f:
        f.write(template_content)


def create_settings_template():
    """创建设置页面模板"""
    template_content = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
    <title>WeiXinAI - 设置</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); min-height: 100vh; }
        .header { background: rgba(255,255,255,0.95); padding: 16px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); display: flex; justify-content: space-between; align-items: center; }
        .header h1 { font-size: 18px; color: #333; font-weight: 600; }
        .back-btn { padding: 8px 16px; background: #f0f0f0; border: none; border-radius: 16px; font-size: 14px; cursor: pointer; }
        .back-btn:hover { background: #e0e0e0; }
        .settings-container { padding: 16px; padding-top: 24px; }
        .settings-card { background: white; border-radius: 16px; padding: 20px; margin-bottom: 16px; box-shadow: 0 2px 12px rgba(0,0,0,0.1); }
        .settings-card h2 { font-size: 16px; color: #333; margin-bottom: 16px; display: flex; align-items: center; gap: 8px; }
        .setting-item { display: flex; justify-content: space-between; align-items: center; padding: 12px 0; border-bottom: 1px solid #f0f0f0; }
        .setting-item:last-child { border-bottom: none; }
        .setting-label { font-size: 15px; color: #333; }
        .setting-value { font-size: 15px; color: #666; }
        .select-wrapper { position: relative; }
        .select-wrapper select { padding: 8px 32px 8px 12px; border: 1px solid #ddd; border-radius: 8px; font-size: 14px; appearance: none; background: white; cursor: pointer; }
        .select-wrapper::after { content: '▼'; position: absolute; right: 8px; top: 50%; transform: translateY(-50%); pointer-events: none; color: #999; font-size: 12px; }
        .btn { padding: 12px 24px; border: none; border-radius: 8px; font-size: 15px; cursor: pointer; transition: opacity 0.2s; }
        .btn-primary { background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); color: white; }
        .btn-primary:hover { opacity: 0.9; }
        .btn-secondary { background: #f0f0f0; color: #333; }
        .btn-secondary:hover { background: #e0e0e0; }
        .btn-group { display: flex; gap: 12px; margin-top: 20px; }
        .btn-group .btn { flex: 1; }
        .status-message { padding: 12px; border-radius: 8px; margin-top: 16px; display: none; }
        .status-success { background: #e8f5e9; color: #2e7d32; }
        .status-error { background: #ffebee; color: #c62828; }
        .info-text { font-size: 13px; color: #999; margin-top: 8px; }
        .memory-description { font-size: 13px; color: #666; margin-top: 8px; padding-left: 8px; }
    </style>
</head>
<body>
    <div class="header">
        <button class="back-btn" onclick="goBack()">← 返回</button>
        <h1>⚙️ 设置</h1>
        <div style="width: 60px;"></div>
    </div>
    
    <div class="settings-container">
        <div class="settings-card">
            <h2>🧠 记忆设置</h2>
            <div class="setting-item">
                <div>
                    <div class="setting-label">记忆级别</div>
                    <div class="memory-description" id="memory-desc">选择记忆模式</div>
                </div>
                <div class="select-wrapper">
                    <select id="memory-level" onchange="updateMemoryDesc()">
                        <option value="basic">基础模式</option>
                        <option value="standard">标准模式</option>
                        <option value="advanced">高级模式</option>
                    </select>
                </div>
            </div>
        </div>
        
        <div class="settings-card">
            <h2>🔧 API 设置</h2>
            <div class="setting-item">
                <div class="setting-label">API 地址</div>
                <input type="text" id="api-url" class="setting-value" style="border: none; text-align: right; color: #666;" readonly>
            </div>
            <div class="setting-item">
                <div class="setting-label">模型名称</div>
                <input type="text" id="model-name" class="setting-value" style="border: none; text-align: right; color: #666;" readonly>
            </div>
        </div>
        
        <div class="settings-card">
            <h2>📊 系统状态</h2>
            <div class="setting-item">
                <div class="setting-label">当前平台</div>
                <div class="setting-value" id="platform">Web</div>
            </div>
            <div class="setting-item">
                <div class="setting-label">消息数量</div>
                <div class="setting-value" id="message-count">-</div>
            </div>
        </div>
        
        <div class="btn-group">
            <button class="btn btn-secondary" onclick="resetSettings()">恢复默认</button>
            <button class="btn btn-primary" onclick="saveSettings()">保存设置</button>
        </div>
        
        <div class="status-message" id="status-message"></div>
    </div>
    
    <script>
        const memoryDescriptions = {
            basic: '基础模式 (100条消息) - 适合低配置设备',
            standard: '标准模式 (500条消息) - 适合大多数设备',
            advanced: '高级模式 (无限制) - 适合高性能设备'
        };
        
        async function loadSettings() {
            const response = await fetch('/api/settings');
            const data = await response.json();
            
            if (data.success) {
                document.getElementById('memory-level').value = data.settings.memory_level;
                document.getElementById('api-url').value = data.settings.api_url;
                document.getElementById('model-name').value = data.settings.model_name;
                document.getElementById('platform').textContent = data.settings.platform;
                updateMemoryDesc();
            }
            
            const statsResponse = await fetch('/api/stats');
            const statsData = await statsResponse.json();
            if (statsData.success) {
                document.getElementById('message-count').textContent = statsData.message_count;
            }
        }
        
        function updateMemoryDesc() {
            const level = document.getElementById('memory-level').value;
            document.getElementById('memory-desc').textContent = memoryDescriptions[level];
        }
        
        async function saveSettings() {
            const settings = {
                memory_level: document.getElementById('memory-level').value,
                api_url: document.getElementById('api-url').value,
                model_name: document.getElementById('model-name').value
            };
            
            const response = await fetch('/api/settings', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(settings)
            });
            
            const data = await response.json();
            
            const statusEl = document.getElementById('status-message');
            if (data.success) {
                statusEl.textContent = '设置保存成功！';
                statusEl.className = 'status-message status-success';
            } else {
                statusEl.textContent = '保存失败，请重试';
                statusEl.className = 'status-message status-error';
            }
            statusEl.style.display = 'block';
            setTimeout(() => statusEl.style.display = 'none', 3000);
        }
        
        function resetSettings() {
            document.getElementById('memory-level').value = 'standard';
            updateMemoryDesc();
        }
        
        function goBack() { window.location.href = '/'; }
        
        document.addEventListener('DOMContentLoaded', loadSettings);
    </script>
</body>
</html>
    """
    
    with open("templates/settings.html", "w", encoding="utf-8") as f:
        f.write(template_content)


if __name__ == "__main__":
    run_local_chat()

# -*- coding: utf-8 -*-
"""
记忆系统模块
实现对话记忆的存储和检索功能，支持多级记忆配置
"""

import os
import json
import sqlite3
import logging
from datetime import datetime, timedelta
from typing import Dict, Any, Optional, List, Tuple
from enum import Enum
from pathlib import Path

logger = logging.getLogger(__name__)


class MemoryLevel(Enum):
    """记忆级别枚举"""
    BASIC = "basic"        # 基础：保留最近100条对话
    STANDARD = "standard"  # 标准：保留最近500条对话，支持检索
    ADVANCED = "advanced"  # 高级：无限制（仅受设备性能影响）


class MemoryStorage:
    """记忆存储管理器"""
    
    VERSION = "1.0.0"
    
    def __init__(self, level: str = "standard", data_dir: str = "data"):
        """
        初始化记忆存储
        
        Args:
            level: 记忆级别 (basic/standard/advanced)
            data_dir: 数据存储目录
        """
        self.level = MemoryLevel(level.lower())
        self.data_dir = Path(data_dir)
        self.data_dir.mkdir(parents=True, exist_ok=True)
        
        # 根据记忆级别设置容量限制（None 表示无限制）
        self.capacity = {
            MemoryLevel.BASIC: 100,
            MemoryLevel.STANDARD: 500,
            MemoryLevel.ADVANCED: None  # 无限制，仅受设备性能影响
        }[self.level]
        
        # 初始化存储
        self._init_storage()
        
        logger.info(f"记忆系统初始化完成 (级别: {self.level.value}, 容量: {self.capacity})")
    
    def _init_storage(self):
        """初始化存储系统"""
        if self.level == MemoryLevel.ADVANCED:
            # 高级模式使用 SQLite
            self.db_path = self.data_dir / "memory.db"
            self._init_sqlite()
        else:
            # 基础和标准模式使用 JSON 文件
            self.json_path = self.data_dir / "memory.json"
            if not self.json_path.exists():
                with open(self.json_path, 'w', encoding='utf-8') as f:
                    json.dump({}, f, ensure_ascii=False, indent=2)
    
    def _init_sqlite(self):
        """初始化 SQLite 数据库"""
        with sqlite3.connect(self.db_path) as conn:
            cursor = conn.cursor()
            
            # 创建对话记录表
            cursor.execute('''
                CREATE TABLE IF NOT EXISTS conversations (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    conversation_id TEXT NOT NULL,
                    role TEXT NOT NULL,
                    content TEXT NOT NULL,
                    timestamp DATETIME DEFAULT CURRENT_TIMESTAMP,
                    metadata TEXT
                )
            ''')
            
            # 创建索引
            cursor.execute('''
                CREATE INDEX IF NOT EXISTS idx_conversation_id 
                ON conversations(conversation_id)
            ''')
            
            cursor.execute('''
                CREATE INDEX IF NOT EXISTS idx_timestamp 
                ON conversations(timestamp)
            ''')
            
            conn.commit()
    
    def save_message(self, conversation_id: str, role: str, content: str, 
                     metadata: Optional[Dict[str, Any]] = None):
        """
        保存对话消息
        
        Args:
            conversation_id: 对话ID
            role: 角色 (user/assistant/system)
            content: 消息内容
            metadata: 附加元数据
        """
        if self.level == MemoryLevel.ADVANCED:
            self._save_message_sqlite(conversation_id, role, content, metadata)
        else:
            self._save_message_json(conversation_id, role, content, metadata)
    
    def _save_message_json(self, conversation_id: str, role: str, content: str, 
                          metadata: Optional[Dict[str, Any]]):
        """使用 JSON 存储消息"""
        with open(self.json_path, 'r', encoding='utf-8') as f:
            data = json.load(f)
        
        if conversation_id not in data:
            data[conversation_id] = []
        
        message = {
            "role": role,
            "content": content,
            "timestamp": datetime.now().isoformat(),
            "metadata": metadata or {}
        }
        
        data[conversation_id].append(message)
        
        # 限制对话数量
        if len(data[conversation_id]) > self.capacity:
            data[conversation_id] = data[conversation_id][-self.capacity:]
        
        with open(self.json_path, 'w', encoding='utf-8') as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
    
    def _save_message_sqlite(self, conversation_id: str, role: str, content: str,
                            metadata: Optional[Dict[str, Any]]):
        """使用 SQLite 存储消息"""
        with sqlite3.connect(self.db_path) as conn:
            cursor = conn.cursor()
            
            cursor.execute('''
                INSERT INTO conversations 
                (conversation_id, role, content, timestamp, metadata)
                VALUES (?, ?, ?, ?, ?)
            ''', (conversation_id, role, content, datetime.now().isoformat(), 
                  json.dumps(metadata or {}, ensure_ascii=False)))
            
            conn.commit()
            
            # 高级模式也需要清理旧数据
            self._cleanup_old_messages(conversation_id)
    
    def _cleanup_old_messages(self, conversation_id: str):
        """清理超出容量限制的旧消息"""
        if self.capacity is None:
            return
        
        if self.level == MemoryLevel.ADVANCED:
            with sqlite3.connect(self.db_path) as conn:
                cursor = conn.cursor()
                
                # 获取对话数量
                cursor.execute('''
                    SELECT COUNT(*) FROM conversations 
                    WHERE conversation_id = ?
                ''', (conversation_id,))
                count = cursor.fetchone()[0]
                
                if count > self.capacity:
                    # 删除最旧的消息
                    cursor.execute('''
                        DELETE FROM conversations 
                        WHERE conversation_id = ? 
                        AND id NOT IN (
                            SELECT id FROM conversations 
                            WHERE conversation_id = ?
                            ORDER BY timestamp DESC 
                            LIMIT ?
                        )
                    ''', (conversation_id, conversation_id, self.capacity))
                    conn.commit()
    
    def get_messages(self, conversation_id: str, limit: Optional[int] = None) -> List[Dict[str, Any]]:
        """
        获取对话历史消息
        
        Args:
            conversation_id: 对话ID
            limit: 返回消息数量限制
            
        Returns:
            消息列表，按时间排序
        """
        if self.level == MemoryLevel.ADVANCED:
            return self._get_messages_sqlite(conversation_id, limit)
        else:
            return self._get_messages_json(conversation_id, limit)
    
    def _get_messages_json(self, conversation_id: str, limit: Optional[int]) -> List[Dict[str, Any]]:
        """从 JSON 获取消息"""
        try:
            with open(self.json_path, 'r', encoding='utf-8') as f:
                data = json.load(f)
        except (FileNotFoundError, json.JSONDecodeError):
            return []
        
        messages = data.get(conversation_id, [])
        
        if limit is not None:
            messages = messages[-limit:]
        
        return sorted(messages, key=lambda x: x["timestamp"])
    
    def _get_messages_sqlite(self, conversation_id: str, limit: Optional[int]) -> List[Dict[str, Any]]:
        """从 SQLite 获取消息"""
        with sqlite3.connect(self.db_path) as conn:
            cursor = conn.cursor()
            
            query = '''
                SELECT role, content, timestamp, metadata 
                FROM conversations 
                WHERE conversation_id = ? 
                ORDER BY timestamp ASC
            '''
            
            params = [conversation_id]
            
            if limit is not None:
                query += ' LIMIT ?'
                params.append(limit)
            
            cursor.execute(query, params)
            
            messages = []
            for row in cursor.fetchall():
                role, content, timestamp, metadata = row
                messages.append({
                    "role": role,
                    "content": content,
                    "timestamp": timestamp,
                    "metadata": json.loads(metadata) if metadata else {}
                })
            
            return messages
    
    def search_memory(self, conversation_id: str, query: str, 
                      max_results: int = 5) -> List[Dict[str, Any]]:
        """
        搜索记忆中相关的消息（仅标准和高级模式支持）
        
        Args:
            conversation_id: 对话ID（None 表示全局搜索）
            query: 搜索关键词
            max_results: 最大返回数量
            
        Returns:
            匹配的消息列表
        """
        if self.level == MemoryLevel.BASIC:
            logger.warning("基础模式不支持搜索功能")
            return []
        
        if self.level == MemoryLevel.ADVANCED:
            return self._search_memory_sqlite(conversation_id, query, max_results)
        else:
            return self._search_memory_json(conversation_id, query, max_results)
    
    def _search_memory_json(self, conversation_id: str, query: str, 
                           max_results: int) -> List[Dict[str, Any]]:
        """在 JSON 中搜索"""
        try:
            with open(self.json_path, 'r', encoding='utf-8') as f:
                data = json.load(f)
        except (FileNotFoundError, json.JSONDecodeError):
            return []
        
        results = []
        query_lower = query.lower()
        
        target_conversations = [conversation_id] if conversation_id else data.keys()
        
        for conv_id in target_conversations:
            for message in data.get(conv_id, []):
                if query_lower in message["content"].lower():
                    results.append({
                        "conversation_id": conv_id,
                        **message
                    })
        
        return sorted(results, key=lambda x: x["timestamp"], reverse=True)[:max_results]
    
    def _search_memory_sqlite(self, conversation_id: str, query: str,
                             max_results: int) -> List[Dict[str, Any]]:
        """在 SQLite 中搜索"""
        with sqlite3.connect(self.db_path) as conn:
            cursor = conn.cursor()
            
            query_sql = '''
                SELECT conversation_id, role, content, timestamp, metadata 
                FROM conversations 
                WHERE content LIKE ?
            '''
            
            params = [f'%{query}%']
            
            if conversation_id:
                query_sql += ' AND conversation_id = ?'
                params.append(conversation_id)
            
            query_sql += ' ORDER BY timestamp DESC LIMIT ?'
            params.append(max_results)
            
            cursor.execute(query_sql, params)
            
            results = []
            for row in cursor.fetchall():
                conv_id, role, content, timestamp, metadata = row
                results.append({
                    "conversation_id": conv_id,
                    "role": role,
                    "content": content,
                    "timestamp": timestamp,
                    "metadata": json.loads(metadata) if metadata else {}
                })
            
            return results
    
    def delete_conversation(self, conversation_id: str) -> bool:
        """
        删除指定对话的所有记忆
        
        Args:
            conversation_id: 对话ID
            
        Returns:
            是否成功
        """
        try:
            if self.level == MemoryLevel.ADVANCED:
                with sqlite3.connect(self.db_path) as conn:
                    cursor = conn.cursor()
                    cursor.execute(
                        'DELETE FROM conversations WHERE conversation_id = ?',
                        (conversation_id,)
                    )
                    conn.commit()
            else:
                with open(self.json_path, 'r', encoding='utf-8') as f:
                    data = json.load(f)
                
                if conversation_id in data:
                    del data[conversation_id]
                
                with open(self.json_path, 'w', encoding='utf-8') as f:
                    json.dump(data, f, ensure_ascii=False, indent=2)
            
            logger.info(f"已删除对话记忆: {conversation_id}")
            return True
        except Exception as e:
            logger.error(f"删除对话记忆失败: {e}")
            return False
    
    def clear_all(self) -> bool:
        """
        清空所有记忆
        
        Returns:
            是否成功
        """
        try:
            if self.level == MemoryLevel.ADVANCED:
                with sqlite3.connect(self.db_path) as conn:
                    cursor = conn.cursor()
                    cursor.execute('DELETE FROM conversations')
                    conn.commit()
            else:
                with open(self.json_path, 'w', encoding='utf-8') as f:
                    json.dump({}, f, ensure_ascii=False, indent=2)
            
            logger.info("已清空所有记忆")
            return True
        except Exception as e:
            logger.error(f"清空记忆失败: {e}")
            return False
    
    def get_stats(self) -> Dict[str, Any]:
        """获取记忆统计信息"""
        if self.level == MemoryLevel.ADVANCED:
            with sqlite3.connect(self.db_path) as conn:
                cursor = conn.cursor()
                
                # 总消息数
                cursor.execute('SELECT COUNT(*) FROM conversations')
                total_messages = cursor.fetchone()[0]
                
                # 对话数量
                cursor.execute('SELECT COUNT(DISTINCT conversation_id) FROM conversations')
                total_conversations = cursor.fetchone()[0]
                
                # 最近活动时间
                cursor.execute('SELECT MAX(timestamp) FROM conversations')
                last_activity = cursor.fetchone()[0]
        else:
            try:
                with open(self.json_path, 'r', encoding='utf-8') as f:
                    data = json.load(f)
            except (FileNotFoundError, json.JSONDecodeError):
                data = {}
            
            total_messages = sum(len(messages) for messages in data.values())
            total_conversations = len(data)
            last_activity = None
            for messages in data.values():
                if messages:
                    ts = messages[-1]["timestamp"]
                    if not last_activity or ts > last_activity:
                        last_activity = ts
        
        return {
            "version": self.VERSION,
            "level": self.level.value,
            "capacity": self.capacity,
            "total_messages": total_messages,
            "total_conversations": total_conversations,
            "last_activity": last_activity
        }
    
    def export_memory(self, output_path: str) -> bool:
        """
        导出记忆数据
        
        Args:
            output_path: 输出文件路径
            
        Returns:
            是否成功
        """
        try:
            if self.level == MemoryLevel.ADVANCED:
                all_messages = []
                with sqlite3.connect(self.db_path) as conn:
                    cursor = conn.cursor()
                    cursor.execute('''
                        SELECT conversation_id, role, content, timestamp, metadata 
                        FROM conversations 
                        ORDER BY timestamp ASC
                    ''')
                    for row in cursor.fetchall():
                        conv_id, role, content, timestamp, metadata = row
                        all_messages.append({
                            "conversation_id": conv_id,
                            "role": role,
                            "content": content,
                            "timestamp": timestamp,
                            "metadata": json.loads(metadata) if metadata else {}
                        })
            else:
                with open(self.json_path, 'r', encoding='utf-8') as f:
                    data = json.load(f)
                all_messages = []
                for conv_id, messages in data.items():
                    for msg in messages:
                        all_messages.append({
                            "conversation_id": conv_id,
                            **msg
                        })
            
            with open(output_path, 'w', encoding='utf-8') as f:
                json.dump(all_messages, f, ensure_ascii=False, indent=2)
            
            logger.info(f"记忆数据导出成功: {output_path}")
            return True
        except Exception as e:
            logger.error(f"导出记忆失败: {e}")
            return False


def get_memory_storage(level: str = "standard", data_dir: str = "data") -> MemoryStorage:
    """获取记忆存储实例"""
    return MemoryStorage(level, data_dir)


# 示例用法
if __name__ == "__main__":
    # 测试基础模式
    print("=== 测试基础模式 ===")
    memory_basic = get_memory_storage("basic")
    memory_basic.save_message("test_conv", "user", "你好")
    memory_basic.save_message("test_conv", "assistant", "你好！很高兴认识你")
    print("基础模式统计:", memory_basic.get_stats())
    print("基础模式消息:", memory_basic.get_messages("test_conv"))
    
    # 测试标准模式
    print("\n=== 测试标准模式 ===")
    memory_std = get_memory_storage("standard", "data/std")
    memory_std.save_message("test_conv", "user", "今天天气怎么样？")
    memory_std.save_message("test_conv", "assistant", "今天天气晴朗，温度25度")
    print("标准模式统计:", memory_std.get_stats())
    print("搜索结果:", memory_std.search_memory("test_conv", "天气"))
    
    # 测试高级模式
    print("\n=== 测试高级模式 ===")
    memory_adv = get_memory_storage("advanced", "data/adv")
    memory_adv.save_message("test_conv", "user", "人工智能是什么？")
    memory_adv.save_message("test_conv", "assistant", "人工智能是模拟人类智能的技术")
    print("高级模式统计:", memory_adv.get_stats())
    print("高级模式消息:", memory_adv.get_messages("test_conv"))
    print("高级搜索结果:", memory_adv.search_memory("test_conv", "人工智能"))
    
    # 清理测试数据
    memory_basic.clear_all()
    memory_std.clear_all()
    memory_adv.clear_all()
    print("\n=== 测试完成 ===")

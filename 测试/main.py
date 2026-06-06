#!/usr/bin/env python3
"""
WeiXinAI - 微信 AI 角色扮演聊天系统
主程序入口
"""

import os
import sys
import logging
import argparse
from pathlib import Path

# 配置日志
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)

class WeiXinAI:
    def __init__(self, config):
        self.config = config
        self.platform = config.get('PLATFORM', 'wechat')
        self.ai_mode = config.get('AI_MODE', 'cloud')
        
        # 初始化各模块
        self.memory_storage = None
        self.chat_engine = None
        self.adapter = None
    
    def load_modules(self):
        """加载所需模块"""
        try:
            from memory.storage import MemoryStorage
            from chat_engine.role_player import RolePlayer
            from api_client.deepseek import DeepSeekAPIClient
            
            # 加载记忆系统
            memory_level = self.config.get('MEMORY_LEVEL', 'standard')
            self.memory_storage = MemoryStorage(level=memory_level)
            logger.info(f"记忆系统已初始化 (级别: {memory_level})")
            
            # 加载聊天引擎
            self.chat_engine = RolePlayer(self.memory_storage, self.config)
            logger.info("聊天引擎已加载")
            
            # 加载平台适配器
            self.load_adapter()
            
            return True
        except Exception as e:
            logger.error(f"模块加载失败: {e}")
            return False
    
    def load_adapter(self):
        """加载平台适配器"""
        if self.platform == 'wechat':
            from wx_adapter.wechat_hook import WeChatAdapter
            self.adapter = WeChatAdapter(self.config)
            logger.info("微信适配器已加载")
        elif self.platform == 'qq':
            logger.warning("QQ适配器暂未实现，使用微信适配器代替")
            from wx_adapter.wechat_hook import WeChatAdapter
            self.adapter = WeChatAdapter(self.config)
        else:
            raise ValueError(f"不支持的平台: {self.platform}")
    
    def load_character_card(self, card_path):
        """加载角色卡"""
        from chat_engine.card_parser import CardParser
        
        if not os.path.exists(card_path):
            logger.warning(f"角色卡文件不存在: {card_path}")
            return False
        
        card = CardParser.load_from_file(card_path)
        if card:
            self.chat_engine.load_character_card(card_path)
            logger.info(f"角色卡已加载: {card.name}")
            return True
        else:
            logger.error("角色卡加载失败")
            return False
    
    def run(self):
        """运行主程序"""
        logger.info("=" * 50)
        logger.info("WeiXinAI 角色扮演聊天系统启动")
        logger.info("=" * 50)
        
        # 加载模块
        if not self.load_modules():
            logger.error("模块加载失败，程序退出")
            return False
        
        # 加载默认角色卡
        default_card = "./cards/example_character.json"
        if os.path.exists(default_card):
            self.load_character_card(default_card)
        
        # 启动适配器
        try:
            logger.info(f"启动 {self.platform} 适配器...")
            self.adapter.start()
            return True
        except Exception as e:
            logger.error(f"适配器启动失败: {e}")
            return False
    
    def stop(self):
        """停止程序"""
        logger.info("正在停止 WeiXinAI...")
        if self.adapter:
            self.adapter.stop()
        logger.info("WeiXinAI 已停止")

def load_config():
    """加载配置"""
    config = {}
    
    # 从环境变量加载
    config['AI_MODE'] = os.getenv('AI_MODE', 'cloud')
    config['DEEPSEEK_API_KEY'] = os.getenv('DEEPSEEK_API_KEY', '')
    config['DEEPSEEK_API_URL'] = os.getenv('DEEPSEEK_API_URL', 'https://api.deepseek.com/v1/chat/completions')
    config['PLATFORM'] = os.getenv('PLATFORM', 'wechat')
    config['MEMORY_LEVEL'] = os.getenv('MEMORY_LEVEL', 'standard')
    config['LOCAL_MODEL_PATH'] = os.getenv('LOCAL_MODEL_PATH', '')
    
    # 从 .env 文件加载（如果存在）
    env_file = Path('.env')
    if env_file.exists():
        with open(env_file, 'r') as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith('#'):
                    if '=' in line:
                        key, value = line.split('=', 1)
                        os.environ[key.strip()] = value.strip()
                        config[key.strip()] = value.strip()
    
    return config

def main():
    parser = argparse.ArgumentParser(description='WeiXinAI - 微信 AI 角色扮演聊天系统')
    parser.add_argument('--platform', '-p', choices=['wechat', 'qq'], default='wechat',
                        help='消息平台 (默认: wechat)')
    parser.add_argument('--card', '-c', default='./cards/example_character.json',
                        help='角色卡路径')
    parser.add_argument('--ai-mode', choices=['cloud', 'local'], default='cloud',
                        help='AI 运行模式 (默认: cloud)')
    
    args = parser.parse_args()
    
    # 加载配置
    config = load_config()
    config['PLATFORM'] = args.platform
    config['AI_MODE'] = args.ai_mode
    
    # 创建并运行程序
    app = WeiXinAI(config)
    
    try:
        if app.run():
            logger.info("系统运行中，按 Ctrl+C 停止...")
            # 保持运行
            import time
            while True:
                time.sleep(1)
    except KeyboardInterrupt:
        logger.info("收到中断信号")
    finally:
        app.stop()

if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""
WeiXinAI - 微信 AI 角色扮演聊天系统
主程序入口
"""

import os
import sys
import time
import logging
import argparse
from pathlib import Path

from config.settings import load_settings
from core.application import Application

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)


def run_wechat_app(app: Application) -> None:
    """启动微信适配器"""
    from wx_adapter.wechat_hook import WeChatAdapter

    adapter = WeChatAdapter()
    # 使用 Application 已有的实例注入
    adapter.memory = app.memory
    adapter.role_player = app.role_player

    logger.info("正在启动微信适配器...")
    adapter.run()


def run_local_app(app: Application) -> None:
    """启动本地聊天 Web 服务"""
    from wx_adapter.local_chat import run_local_chat

    run_local_chat(host=app.settings.API_HOST, port=app.settings.API_PORT)


def main():
    parser = argparse.ArgumentParser(description='WeiXinAI - 微信 AI 角色扮演聊天系统')
    parser.add_argument('--platform', '-p', choices=['wechat', 'qq', 'local'], default='wechat',
                        help='消息平台 (默认: wechat)')
    parser.add_argument('--card', '-c', default='./cards/example_character.json',
                        help='角色卡路径')
    parser.add_argument('--ai-mode', choices=['cloud', 'local'], default='cloud',
                        help='AI 运行模式 (默认: cloud)')

    args = parser.parse_args()

    # 加载配置（统一使用 config/settings.py）
    settings = load_settings()
    settings.PLATFORM = args.platform
    settings.AI_MODE = args.ai_mode

    # 创建并初始化 Application 容器
    app = Application(settings=settings)

    try:
        app.bootstrap()

        # 加载默认角色卡
        default_card = Path(args.card)
        if default_card.exists():
            if app.role_player:
                app.role_player.load_character_card(str(default_card))
                logger.info(f"角色卡已加载: {default_card.name}")

        logger.info("系统运行中，按 Ctrl+C 停止...")

        # 按平台启动
        if args.platform == 'local':
            run_local_app(app)
        else:
            run_wechat_app(app)

        # 微信适配器之外的模式保持前台运行
        if args.platform != 'wechat':
            while True:
                time.sleep(1)

    except KeyboardInterrupt:
        logger.info("收到中断信号")
    except Exception as e:
        logger.error(f"程序异常: {e}")
    finally:
        app.shutdown()


if __name__ == '__main__':
    main()
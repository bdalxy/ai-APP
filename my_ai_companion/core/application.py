# -*- coding: utf-8 -*-
"""
统一应用容器
管理所有模块的生命周期，提供依赖注入
"""

import logging
from typing import Optional

from config.settings import Settings, load_settings
from api_client.deepseek import DeepSeekAPIClient
from chat_engine.card_parser import CardManager
from chat_engine.role_player import RolePlayer
from memory.storage import get_memory_storage, MemoryStorage

logger = logging.getLogger(__name__)


class Application:
    """统一应用容器 — 管理所有模块的创建、初始化和销毁"""

    def __init__(self, settings: Optional[Settings] = None):
        """
        初始化应用容器

        Args:
            settings: 应用配置，为 None 时自动从环境变量加载
        """
        self.settings = settings or load_settings()
        self.api_client: Optional[DeepSeekAPIClient] = None
        self.card_manager: Optional[CardManager] = None
        self.memory: Optional[MemoryStorage] = None
        self.role_player: Optional[RolePlayer] = None
        self._bootstrapped = False

    def bootstrap(self) -> None:
        """初始化所有模块"""
        if self._bootstrapped:
            logger.warning("应用已初始化，跳过重复 bootstrap")
            return

        logger.info("=" * 50)
        logger.info("WeiXinAI 应用容器启动")
        logger.info("=" * 50)

        # 验证配置
        self.settings.ensure_valid()

        # 1. 初始化 API 客户端
        self.api_client = DeepSeekAPIClient(
            api_key=self.settings.DEEPSEEK_API_KEY,
            api_url=self.settings.DEEPSEEK_API_URL
        )
        logger.info("API 客户端已初始化")

        # 2. 初始化记忆系统
        self.memory = get_memory_storage(level=self.settings.MEMORY_LEVEL)
        logger.info(f"记忆系统已初始化 (级别: {self.settings.MEMORY_LEVEL})")

        # 3. 初始化角色卡管理器
        self.card_manager = CardManager(cards_dir="cards")
        self.card_manager.load_all()
        logger.info(f"角色卡管理器已初始化 ({len(self.card_manager.list_cards())} 张卡片)")

        # 4. 初始化角色扮演引擎
        self.role_player = RolePlayer(
            memory=self.memory,
            api_client=self.api_client,
            config={"MAX_CONTEXT_LENGTH": 20}
        )
        logger.info("角色扮演引擎已初始化")

        self._bootstrapped = True
        logger.info("所有模块初始化完成")

    def shutdown(self) -> None:
        """关闭所有模块，释放资源"""
        logger.info("正在关闭应用...")
        if self.api_client:
            try:
                self.api_client.close()
            except Exception as e:
                logger.warning(f"关闭 API 客户端时出错: {e}")
        self._bootstrapped = False
        logger.info("应用已关闭")

    @property
    def is_ready(self) -> bool:
        """检查应用是否就绪"""
        return self._bootstrapped and self.api_client is not None


# 全局应用实例（单例模式）
_app_instance: Optional[Application] = None


def get_app(settings: Optional[Settings] = None) -> Application:
    """
    获取全局应用实例

    Args:
        settings: 首次调用时使用的配置，后续调用忽略

    Returns:
        Application 实例
    """
    global _app_instance
    if _app_instance is None:
        _app_instance = Application(settings=settings)
    return _app_instance


def reset_app() -> None:
    """重置全局应用实例（主要用于测试）"""
    global _app_instance
    if _app_instance is not None:
        _app_instance.shutdown()
    _app_instance = None
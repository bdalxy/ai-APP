"""应用上下文管理模块。

提供 AppContext 单例类，统一管理 DeepSeekClient、RolePlayer 和
MemoryOrchestrator 的生命周期，解决切换预设/模型时的资源泄露问题。

核心职责：
    - 持有共享对象（client、player、orchestrator）的引用
    - initialize(): 先关闭旧资源，再创建新资源
    - shutdown(): 按顺序关闭 orchestrator → player → client

依赖：
    - src.api_client.deepseek: DeepSeekClient
    - src.chat_engine.role_player: RolePlayer
    - src.memory.orchestrator: MemoryOrchestrator
    - src.memory.vector_store: VectorStore
    - src.utils.logger: get_logger
"""

from __future__ import annotations

from typing import TYPE_CHECKING

from src.api_client.deepseek import DeepSeekClient
from src.chat_engine.role_player import RolePlayer
from src.memory.orchestrator import MemoryOrchestrator
from src.memory.vector_store import VectorStore
from src.utils.logger import get_logger

if TYPE_CHECKING:
    pass


class AppContext:
    """应用上下文单例。

    统一管理共享对象（DeepSeekClient、RolePlayer、MemoryOrchestrator）的生命周期。
    使用单例模式，全局唯一实例。

    Attributes:
        _client: DeepSeek API 客户端实例。
        _player: 角色扮演对话引擎实例。
        _orchestrator: 记忆编排器实例。
        _turn_counter: 对话轮次计数器。
        _current_preset: 当前使用的 Token 预设名称。
    """

    _instance: "AppContext | None" = None

    def __init__(self) -> None:
        """初始化应用上下文（只创建空引用，不创建实际资源）。"""
        self._client: DeepSeekClient | None = None
        self._player: RolePlayer | None = None
        self._orchestrator: MemoryOrchestrator | None = None
        self._turn_counter: int = 0
        self._current_preset: str = "balanced"
        self._log = get_logger()

    # =========================================================================
    # 单例访问
    # =========================================================================

    @classmethod
    def get_instance(cls) -> "AppContext":
        """获取全局单例实例。

        Returns:
            AppContext 全局单例。
        """
        if cls._instance is None:
            cls._instance = cls()
        return cls._instance

    # =========================================================================
    # 属性访问
    # =========================================================================

    @property
    def client(self) -> DeepSeekClient | None:
        """获取 DeepSeek API 客户端。"""
        return self._client

    @property
    def player(self) -> RolePlayer | None:
        """获取角色扮演对话引擎。"""
        return self._player

    @property
    def orchestrator(self) -> MemoryOrchestrator | None:
        """获取记忆编排器。"""
        return self._orchestrator

    @property
    def turn_counter(self) -> int:
        """获取当前对话轮次计数。"""
        return self._turn_counter

    @property
    def current_preset(self) -> str:
        """获取当前 Token 预设名称。"""
        return self._current_preset

    # =========================================================================
    # 生命周期管理
    # =========================================================================

    def initialize(self, preset: str = "balanced", model: str = "") -> RolePlayer:
        """初始化聊天引擎。

        先调用 shutdown() 清理旧资源，再创建新的 DeepSeekClient 和 RolePlayer。
        注意：记忆系统（orchestrator）需单独调用 init_memory() 初始化。

        Args:
            preset: Token 预设模式 ("quality"/"balanced"/"economy")。
            model: 模型名称，空字符串表示使用预设默认模型。

        Returns:
            新创建的 RolePlayer 实例。

        Raises:
            ValueError: API Key 未配置时。
        """
        # 1. 先清理旧资源
        self.shutdown()

        # 2. 创建新资源
        from src.config.settings import settings
        if not settings.DEEPSEEK_API_KEY:
            raise ValueError("API Key 未配置，请先设置 API Key")

        self._client = DeepSeekClient()
        self._current_preset = preset

        self._player = RolePlayer(
            self._client,
            preset=preset,
            model=model if model else None,
        )

        self._log.info(
            f"AppContext 初始化完成: preset={preset}, model={model or '默认'}"
        )
        return self._player

    def init_memory(self, db_path: str) -> MemoryOrchestrator:
        """初始化记忆系统。

        创建 VectorStore 和 MemoryOrchestrator，使用与 player 共享的
        DeepSeekClient。如果已有旧的 orchestrator，先关闭它。

        Args:
            db_path: 数据库文件所在目录路径。

        Returns:
            新创建的 MemoryOrchestrator 实例。

        Raises:
            RuntimeError: 聊天引擎未初始化时。
        """
        import os

        if self._player is None or self._client is None:
            raise RuntimeError("聊天引擎未初始化，请先调用 initialize()")

        # 关闭旧的 orchestrator
        if self._orchestrator is not None:
            try:
                self._orchestrator.close()
            except Exception:
                self._log.warning("[清理] 关闭旧 orchestrator 时忽略异常（通常无害）")
            self._orchestrator = None

        # 构建数据库文件路径
        db_file = os.path.join(db_path.rstrip("/").rstrip("\\"), "memories.db")

        # 创建 VectorStore 和 MemoryOrchestrator
        vector_store = VectorStore(db_file)
        self._orchestrator = MemoryOrchestrator(
            vector_store=vector_store,
            deepseek_client=self._client,
        )
        self._turn_counter = 0

        self._log.info(
            f"记忆系统初始化完成: db={db_file}, 记忆数={vector_store.count()}"
        )
        return self._orchestrator

    def shutdown(self) -> None:
        """按顺序关闭所有资源：orchestrator → player → client。

        关闭顺序说明：
        1. orchestrator 关闭时会关闭 vector_store（数据库连接）
        2. player 本身无需 close，但其持有的 client 需要关闭
        3. client 关闭 HTTP session

        所有关闭操作的异常都会被捕获并记录日志，确保后续资源仍会被尝试关闭。
        """
        # 1. 关闭 orchestrator（内部会关闭 vector_store）
        if self._orchestrator is not None:
            try:
                self._orchestrator.close()
            except Exception as e:
                self._log.warning(f"[清理] 关闭 orchestrator 失败: {e}")
            self._orchestrator = None

        # 2. 关闭 player 持有的 client（不重复关闭，player 只是引用）
        if self._player is not None:
            self._player = None

        # 3. 关闭 DeepSeekClient
        if self._client is not None:
            try:
                self._client.close()
            except Exception as e:
                self._log.warning(f"[清理] 关闭 DeepSeekClient 失败: {e}")
            self._client = None

        self._turn_counter = 0
        self._log.info("AppContext 已关闭所有资源")

    def reset_turn_counter(self) -> None:
        """重置对话轮次计数器。"""
        self._turn_counter = 0

    def increment_turn(self) -> int:
        """递增对话轮次计数器并返回新值。

        Returns:
            递增后的轮次计数。
        """
        self._turn_counter += 1
        return self._turn_counter

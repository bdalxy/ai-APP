"""记忆相关异常类。

用于向量存储、记忆检索、记忆持久化等场景的错误处理。
"""


class MemoryException(Exception):
    """记忆模块基础异常。

    Attributes:
        message: 错误描述
        detail: 附加上下文信息（如记忆 ID、集合名称等）
    """

    def __init__(self, message: str, detail: str | None = None) -> None:
        """初始化记忆异常。

        Args:
            message: 人类可读的错误描述。
            detail: 可选的附加上下文信息。
        """
        full_message = f"{message}" + (f" (详情: {detail})" if detail else "")
        super().__init__(full_message)
        self.message: str = message
        self.detail: str | None = detail

    def __repr__(self) -> str:
        """返回详细的异常表示。"""
        return f"{self.__class__.__name__}(message={self.message!r}, detail={self.detail!r})"


class MemoryNotFoundError(MemoryException):
    """记忆不存在异常。

    在查询特定记忆 ID 或集合时，若目标不存在则抛出。
    """

    def __init__(self, message: str = "记忆不存在", detail: str | None = None) -> None:
        super().__init__(message, detail=detail)


class MemoryStorageError(MemoryException):
    """记忆存储异常。

    在持久化记忆到 ChromaDB 或文件时发生 I/O 错误时抛出。
    """

    def __init__(self, message: str = "记忆存储失败", detail: str | None = None) -> None:
        super().__init__(message, detail=detail)
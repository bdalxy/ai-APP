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
        full_message = f"{message}" + (f" (详情: {detail})" if detail else "")
        super().__init__(full_message)
        self.message: str = message
        self.detail: str | None = detail

    def __repr__(self) -> str:
        return f"{self.__class__.__name__}(message={self.message!r}, detail={self.detail!r})"


class MemoryNotFoundError(MemoryException):
    """记忆不存在异常。"""

    def __init__(self, message: str = "记忆不存在", detail: str | None = None) -> None:
        super().__init__(message, detail=detail)


class MemoryStorageError(MemoryException):
    """记忆存储异常。"""

    def __init__(self, message: str = "记忆存储失败", detail: str | None = None) -> None:
        super().__init__(message, detail=detail)

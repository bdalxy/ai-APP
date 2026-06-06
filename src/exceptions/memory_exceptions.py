"""记忆相关异常类。"""
class MemoryException(Exception):
    def __init__(self, message: str, detail: str | None = None) -> None:
        full_message = f"{message}" + (f" (详情: {detail})" if detail else "")
        super().__init__(full_message)
        self.message = message
        self.detail = detail

class MemoryNotFoundError(MemoryException):
    def __init__(self, message: str = "记忆不存在", detail: str | None = None) -> None:
        super().__init__(message, detail=detail)

class MemoryStorageError(MemoryException):
    def __init__(self, message: str = "记忆存储失败", detail: str | None = None) -> None:
        super().__init__(message, detail=detail)

"""自定义 API 异常类。

所有 API 异常继承自 APIException 基类。
每种异常携带 HTTP 状态码，便于上层统一处理。
"""


class APIException(Exception):
    """API 调用基础异常。

    Attributes:
        message: 错误描述
        status_code: HTTP 状态码（None 表示非 HTTP 错误）
    """

    def __init__(self, message: str, status_code: int | None = None) -> None:
        super().__init__(message)
        self.message: str = message
        self.status_code: int | None = status_code

    def __repr__(self) -> str:
        return f"{self.__class__.__name__}(message={self.message!r}, status_code={self.status_code})"


class APIKeyError(APIException):
    """认证失败（API Key 无效或未提供），对应 HTTP 401。"""

    def __init__(self, message: str = "API 密钥无效或未提供") -> None:
        super().__init__(message, status_code=401)


class APIQuotaError(APIException):
    """余额不足或配额耗尽，对应 HTTP 402。"""

    def __init__(self, message: str = "API 配额不足，请检查账户余额") -> None:
        super().__init__(message, status_code=402)


class APITimeoutError(APIException):
    """请求超时。"""

    def __init__(self, message: str = "API 请求超时") -> None:
        super().__init__(message, status_code=None)


class APIRateLimitError(APIException):
    """请求频率超限，对应 HTTP 429。"""

    def __init__(self, message: str = "API 请求频率过高，请稍后重试") -> None:
        super().__init__(message, status_code=429)


class APIServerError(APIException):
    """服务器内部错误，对应 HTTP 5xx。"""

    def __init__(self, message: str = "API 服务器内部错误", status_code: int = 500) -> None:
        super().__init__(message, status_code=status_code)


class APIContentFilterError(APIException):
    """内容被过滤（安全审查），对应 HTTP 400。"""

    def __init__(self, message: str = "内容被安全审查过滤") -> None:
        super().__init__(message, status_code=400)

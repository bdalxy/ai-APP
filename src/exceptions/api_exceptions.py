"""自定义 API 异常类。"""
class APIException(Exception):
    def __init__(self, message: str, status_code: int | None = None) -> None:
        super().__init__(message)
        self.message = message
        self.status_code = status_code

class APIKeyError(APIException):
    def __init__(self, message: str = "API 密钥无效或未提供") -> None:
        super().__init__(message, status_code=401)

class APIQuotaError(APIException):
    def __init__(self, message: str = "API 配额不足") -> None:
        super().__init__(message, status_code=402)

class APITimeoutError(APIException):
    def __init__(self, message: str = "API 请求超时") -> None:
        super().__init__(message, status_code=None)

class APIRateLimitError(APIException):
    def __init__(self, message: str = "请求频率过高") -> None:
        super().__init__(message, status_code=429)

class APIServerError(APIException):
    def __init__(self, message: str = "服务器内部错误", status_code: int = 500) -> None:
        super().__init__(message, status_code=status_code)

class APIContentFilterError(APIException):
    def __init__(self, message: str = "内容被过滤") -> None:
        super().__init__(message, status_code=400)

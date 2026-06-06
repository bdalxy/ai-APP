"""DeepSeek API 客户端模块。

封装 DeepSeek Chat Completion 和 Embedding API 调用，
提供统一的错误处理、成本追踪和 HTTP 连接池复用。

依赖：
    - requests: HTTP 请求（连接池复用）
    - src.config.settings: Settings 单例（API Key、模型、Base URL）
    - src.exceptions: 自定义异常类（APIKeyError 等）
    - src.utils.retry: @retry 重试装饰器
    - src.utils.logger: get_logger 日志实例

使用方式:
    client = DeepSeekClient()
    response = client.chat([{"role": "user", "content": "你好"}])
    print(response.content)
    print(client.get_total_cost())
"""

from dataclasses import dataclass
from typing import Any

import requests

from src.config.settings import settings
from src.exceptions import (
    APIContentFilterError,
    APIException,
    APIKeyError,
    APIQuotaError,
    APIRateLimitError,
    APIServerError,
    APITimeoutError,
)
from src.utils.logger import get_logger
from src.utils.retry import retry


@dataclass
class ChatResponse:
    content: str
    model: str
    usage: dict[str, int]
    finish_reason: str

@dataclass
class EmbeddingResponse:
    embeddings: list[list[float]]
    model: str
    usage: dict[str, int]

class CostTracker:
    PRICING: dict[str, dict[str, float]] = {
        "deepseek-v4-flash": {"input": 1.0, "output": 2.0},
        "deepseek-v4-pro": {"input": 1.0, "output": 2.0},
        "deepseek-embedding-v2": {"input": 0.01, "output": 0.0},
        "default": {"input": 1.0, "output": 2.0},
    }
    def __init__(self) -> None:
        self._total_input_tokens: int = 0
        self._total_output_tokens: int = 0
        self._total_cost: float = 0.0
        self._log = get_logger()

    def record(self, model: str, prompt_tokens: int, completion_tokens: int = 0) -> None:
        pricing = self.PRICING.get(model, self.PRICING["default"])
        input_cost = prompt_tokens * pricing["input"] / 1_000_000
        output_cost = completion_tokens * pricing["output"] / 1_000_000
        self._total_input_tokens += prompt_tokens
        self._total_output_tokens += completion_tokens
        self._total_cost += input_cost + output_cost

    def get_total_cost(self) -> float:
        return self._total_cost

    def get_total_tokens(self) -> dict[str, int]:
        return {"input_tokens": self._total_input_tokens, "output_tokens": self._total_output_tokens, "total_tokens": self._total_input_tokens + self._total_output_tokens}

class DeepSeekClient:
    CONNECT_TIMEOUT: float = 5.0
    READ_TIMEOUT: float = 30.0
    EMBED_BATCH_SIZE: int = 100

    def __init__(self) -> None:
        self._api_key: str = settings.DEEPSEEK_API_KEY
        self._base_url: str = settings.DEEPSEEK_BASE_URL.rstrip("/")
        self._chat_model: str = settings.DEEPSEEK_MODEL
        self._embed_model: str = settings.DEEPSEEK_EMBEDDING_MODEL
        self.session = requests.Session()
        self.session.headers.update({"Authorization": f"Bearer {self._api_key}", "Content-Type": "application/json"})
        self.cost_tracker = CostTracker()
        self._log = get_logger()

    def _handle_http_error(self, response: requests.Response) -> None:
        status_code = response.status_code
        try:
            error_body = response.json()
        except Exception:
            error_body = {}
        if isinstance(error_body, dict):
            error_msg = error_body.get("error", {}).get("message", response.text)
        else:
            error_msg = response.text
        if status_code == 401:
            raise APIKeyError(f"认证失败 (401): {error_msg}")
        elif status_code == 402:
            raise APIQuotaError(f"配额不足 (402): {error_msg}")
        elif status_code == 429:
            raise APIRateLimitError(f"请求频率过高 (429): {error_msg}")
        elif status_code >= 500:
            raise APIServerError(f"服务器错误 ({status_code}): {error_msg}", status_code=status_code)
        elif status_code == 400:
            lower_msg = str(error_msg).lower()
            if "content" in lower_msg or "safety" in lower_msg or "filter" in lower_msg:
                raise APIContentFilterError(f"内容被过滤 (400): {error_msg}")
            raise APIException(f"请求参数错误 (400): {error_msg}", status_code=400)
        else:
            raise APIException(f"未知错误 ({status_code}): {error_msg}", status_code=status_code)

    @retry(max_retries=3, base_delay=1.0, max_delay=30.0, retryable_exceptions=(APITimeoutError, APIServerError, APIRateLimitError))
    def chat(self, messages: list[dict[str, str]], temperature: float = 0.7, max_tokens: int = 2000, stream: bool = False) -> ChatResponse:
        if not messages:
            raise ValueError("messages 不能为空")
        url = f"{self._base_url}/v1/chat/completions"
        payload = {"model": self._chat_model, "messages": messages, "temperature": temperature, "max_tokens": max_tokens, "stream": False}
        try:
            response = self.session.post(url, json=payload, timeout=(self.CONNECT_TIMEOUT, self.READ_TIMEOUT))
        except requests.exceptions.Timeout:
            raise APITimeoutError(f"请求超时")
        except requests.exceptions.ConnectionError as e:
            raise APIException(f"连接失败: {e}")
        if response.status_code != 200:
            self._handle_http_error(response)
        data = response.json()
        choice = data["choices"][0]
        usage_raw = data.get("usage", {})
        result = ChatResponse(content=choice["message"]["content"], model=data.get("model", self._chat_model), usage={"prompt_tokens": usage_raw.get("prompt_tokens", 0), "completion_tokens": usage_raw.get("completion_tokens", 0), "total_tokens": usage_raw.get("total_tokens", 0)}, finish_reason=choice.get("finish_reason", "unknown"))
        self.cost_tracker.record(model=result.model, prompt_tokens=result.usage["prompt_tokens"], completion_tokens=result.usage["completion_tokens"])
        return result

    @retry(max_retries=3, base_delay=1.0, max_delay=30.0, retryable_exceptions=(APITimeoutError, APIServerError, APIRateLimitError))
    def embed(self, texts: str | list[str]) -> EmbeddingResponse:
        if isinstance(texts, str):
            texts = [texts]
        if not texts:
            raise ValueError("texts 不能为空")
        all_embeddings: list[list[float]] = []
        total_prompt_tokens = 0
        for i in range(0, len(texts), self.EMBED_BATCH_SIZE):
            batch = texts[i : i + self.EMBED_BATCH_SIZE]
            url = f"{self._base_url}/v1/embeddings"
            payload = {"model": self._embed_model, "input": batch}
            try:
                response = self.session.post(url, json=payload, timeout=(self.CONNECT_TIMEOUT, self.READ_TIMEOUT))
            except requests.exceptions.Timeout:
                raise APITimeoutError("Embedding 超时")
            except requests.exceptions.ConnectionError as e:
                raise APIException(f"连接失败: {e}")
            if response.status_code != 200:
                self._handle_http_error(response)
            data = response.json()
            total_prompt_tokens += data.get("usage", {}).get("total_tokens", 0)
            items = sorted(data["data"], key=lambda x: x.get("index", 0))
            for item in items:
                all_embeddings.append(item["embedding"])
        result = EmbeddingResponse(embeddings=all_embeddings, model=self._embed_model, usage={"prompt_tokens": total_prompt_tokens, "completion_tokens": 0, "total_tokens": total_prompt_tokens})
        self.cost_tracker.record(model=self._embed_model, prompt_tokens=total_prompt_tokens, completion_tokens=0)
        return result

    def get_total_cost(self) -> float:
        return self.cost_tracker.get_total_cost()

    def get_total_tokens(self) -> dict[str, int]:
        return self.cost_tracker.get_total_tokens()

    def close(self) -> None:
        self.session.close()

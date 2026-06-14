"""pytest 配置和共享 fixtures。

在导入任何源码模块之前设置必要的环境变量（如 DEEPSEEK_API_KEY），
因为 src.config.settings 在模块加载时会读取环境变量。
"""

import os
import sys
import tempfile
import shutil

import pytest

# =============================================================================
# 在导入源码前必须设置环境变量
# =============================================================================
os.environ.setdefault("DEEPSEEK_API_KEY", "sk-test-key-for-unit-tests")
os.environ.setdefault("DEEPSEEK_MODEL", "deepseek-chat")
os.environ.setdefault("DEEPSEEK_EMBEDDING_MODEL", "deepseek-embedding-v2")
os.environ.setdefault("DEEPSEEK_BASE_URL", "https://api.deepseek.com")
os.environ.setdefault("LOG_LEVEL", "WARNING")

# =============================================================================
# 将 Python 源码路径加入 sys.path
# =============================================================================
PYTHON_SRC = os.path.abspath(os.path.join(
    os.path.dirname(__file__),
    "..", "android", "app", "src", "main", "python"
))
if PYTHON_SRC not in sys.path:
    sys.path.insert(0, PYTHON_SRC)

# 也加入 src 子模块路径
SRC_PATH = os.path.join(PYTHON_SRC, "src")
if SRC_PATH not in sys.path:
    sys.path.insert(0, SRC_PATH)


# =============================================================================
# Fixtures
# =============================================================================

@pytest.fixture
def temp_db_path():
    """创建临时数据库文件路径，测试结束后自动清理。"""
    tmp_dir = tempfile.mkdtemp(prefix="test_memory_")
    db_path = os.path.join(tmp_dir, "test_memory.db")
    yield db_path
    try:
        shutil.rmtree(tmp_dir, ignore_errors=True)
    except Exception:
        pass


@pytest.fixture
def memory_store():
    """创建基于 :memory: 数据库的 VectorStore 实例，测试结束后关闭。"""
    from src.memory.vector_store import VectorStore
    store = VectorStore(":memory:")
    yield store
    try:
        store.close()
    except Exception:
        pass


@pytest.fixture
def sample_conversation():
    """返回测试用的对话数据列表。"""
    return [
        {"role": "user", "content": "我喜欢吃苹果"},
        {"role": "assistant", "content": "苹果很好吃！"},
        {"role": "user", "content": "我也喜欢橙子"},
        {"role": "assistant", "content": "橙子富含维生素C"},
    ]


@pytest.fixture
def mock_deepseek_client():
    """返回 mock 的 DeepSeekClient，不实际调用 API。"""
    from unittest.mock import MagicMock
    mock = MagicMock()
    # 默认 ChatResponse
    from src.api_client.deepseek import ChatResponse
    mock.chat.return_value = ChatResponse(
        content="这是一个测试回复。",
        model="deepseek-chat",
        usage={"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15},
        finish_reason="stop",
    )
    # 默认 EmbeddingResponse
    from src.api_client.deepseek import EmbeddingResponse
    mock.embed.return_value = EmbeddingResponse(
        embeddings=[[0.1] * 768],
        model="deepseek-embedding-v2",
        usage={"prompt_tokens": 5, "completion_tokens": 0, "total_tokens": 5},
    )
    mock.embed_cached.return_value = EmbeddingResponse(
        embeddings=[[0.1] * 768],
        model="deepseek-embedding-v2",
        usage={"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0},
    )
    return mock
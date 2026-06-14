#!/usr/bin/env python3
"""运行所有 Python 单元测试。

用法:
    python tests/python/run_tests.py
    python tests/python/run_tests.py -v
    python tests/python/run_tests.py test_memory/test_vector_store.py -v

注意：需要在项目根目录 `f:\\Trae AI\\ai-APP\\` 下运行此脚本，
因为 conftest.py 中的路径引用基于项目根目录。
"""

import sys
import os

# 确保项目根目录在 sys.path 中
# 此脚本位于 tests/python/，项目根目录是上两级
PROJECT_ROOT = os.path.abspath(
    os.path.join(os.path.dirname(__file__), "..", "..")
)
sys.path.insert(0, PROJECT_ROOT)

# 确保 Python 源码路径在 sys.path 中（conftest.py 也会设置，但此处作为保险）
PYTHON_SRC = os.path.join(
    PROJECT_ROOT, "android", "app", "src", "main", "python"
)
if PYTHON_SRC not in sys.path:
    sys.path.insert(0, PYTHON_SRC)

SRC_PATH = os.path.join(PYTHON_SRC, "src")
if SRC_PATH not in sys.path:
    sys.path.insert(0, SRC_PATH)


def main():
    import pytest

    # 基础参数
    args = [
        os.path.dirname(__file__),  # 测试目录
        "-v",
        "--tb=short",
        "--color=yes",
        "-p", "no:cacheprovider",   # 禁用缓存，每次重新运行
    ]

    # 如果安装了 pytest-cov，添加覆盖率参数
    try:
        import pytest_cov  # noqa: F401
        # 使用源码完整路径，确保 coverage 能正确追踪
        src_path = os.path.join(PYTHON_SRC, "src")
        args.extend([
            f"--cov={src_path}",
            "--cov-report=term-missing",
        ])
    except ImportError:
        pass

    # 支持命令行传入额外参数
    # 例如: python run_tests.py -k "test_add" --tb=long
    args.extend(sys.argv[1:])

    exit_code = pytest.main(args)
    sys.exit(exit_code)


if __name__ == "__main__":
    main()
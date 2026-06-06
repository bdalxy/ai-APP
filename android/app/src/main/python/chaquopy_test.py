"""
Chaquopy 环境验证脚本
P2 阶段核心验证：确认 requests、sqlite3 在 Android 中正常运行
"""
import sys
import os

def run_tests():
    """运行所有 P2 验证测试，返回结果字典"""
    results = {
        "python_version": test_python_version(),
        "sqlite3": test_sqlite3(),
        "requests": test_requests(),
        "file_io": test_file_io(),
        "android_paths": test_android_paths(),
    }
    return results


def test_python_version():
    """验证 Python 版本"""
    try:
        version = f"{sys.version_info.major}.{sys.version_info.minor}"
        return {"status": "ok", "version": version}
    except Exception as e:
        return {"status": "error", "detail": str(e)}


def test_sqlite3():
    """验证 sqlite3 在 Chaquopy 中可用"""
    try:
        import sqlite3
        conn = sqlite3.connect(":memory:")
        cursor = conn.cursor()
        cursor.execute("CREATE TABLE test (id INTEGER, name TEXT)")
        cursor.execute("INSERT INTO test VALUES (1, 'hello')")
        cursor.execute("SELECT * FROM test")
        row = cursor.fetchone()
        conn.close()
        return {"status": "ok", "row": str(row)}
    except Exception as e:
        return {"status": "error", "detail": str(e)}


def test_requests():
    """验证 requests 库可用（不实际请求 API，只验证导入和基本功能）"""
    try:
        import requests
        session = requests.Session()
        session.headers.update({"User-Agent": "AICompanion/1.0"})
        return {"status": "ok", "version": requests.__version__}
    except Exception as e:
        return {"status": "error", "detail": str(e)}


def test_file_io():
    """验证文件读写功能"""
    try:
        test_path = "/data/data/com.aicompanion.app/files/test.txt"
        with open(test_path, "w", encoding="utf-8") as f:
            f.write("Chaquopy test")
        with open(test_path, "r", encoding="utf-8") as f:
            content = f.read()
        os.remove(test_path)
        return {"status": "ok", "content": content}
    except Exception as e:
        return {"status": "error", "detail": str(e)}


def test_android_paths():
    """验证 Android 文件路径可用"""
    try:
        from android.os import Environment
        data_dir = str(Environment.getDataDirectory())
        return {"status": "ok", "data_dir": data_dir}
    except Exception as e:
        return {"status": "error", "detail": str(e)}


if __name__ == "__main__":
    results = run_tests()
    for name, result in results.items():
        status = "OK" if result["status"] == "ok" else "FAIL"
        print(f"{status} {name}: {result}")

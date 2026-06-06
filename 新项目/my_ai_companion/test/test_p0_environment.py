"""P0 环境验证脚本 - 验证所有核心依赖和目录结构是否正常。"""
import sys
import os

def check_python_version():
    """验证 Python 版本"""
    version = sys.version_info
    if version >= (3, 10):
        print(f"✅ Python 版本: {version.major}.{version.minor}.{version.micro} (要求 3.10+)")
        return True
    print(f"❌ Python 版本: {version.major}.{version.minor} (需要 3.10+)")
    return False


def check_dependencies():
    """验证核心依赖是否可导入"""
    deps = {
        "requests": "HTTP 请求",
        "dotenv": "环境变量管理",
        "loguru": "日志",
        "json5": "JSON5 解析",
    }
    all_ok = True
    for module, desc in deps.items():
        try:
            __import__(module)
            print(f"✅ {module} ({desc})")
        except ImportError:
            print(f"❌ {module} ({desc}) - 未安装")
            all_ok = False
    return all_ok


def check_directory_structure():
    """验证项目目录结构"""
    base_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    
    required_dirs = [
        "src/chat_engine",
        "src/api_client",
        "src/memory",
        "src/proactive",
        "src/world_book",
        "src/exceptions",
        "src/utils",
        "src/config",
        "data/world_books",
        "data/memories",
        "data/archives",
        "test",
    ]
    
    all_ok = True
    for d in required_dirs:
        path = os.path.join(base_dir, d)
        if os.path.isdir(path):
            print(f"✅ {d}/")
        else:
            print(f"❌ {d}/ - 缺失")
            all_ok = False
    return all_ok


def check_config_files():
    """验证配置文件"""
    base_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    
    files = [
        ".env.example",
        ".gitignore",
        "requirements.txt",
        "pyproject.toml",
    ]
    
    all_ok = True
    for f in files:
        path = os.path.join(base_dir, f)
        if os.path.isfile(path):
            print(f"✅ {f}")
        else:
            print(f"❌ {f} - 缺失")
            all_ok = False
    return all_ok


def main():
    print("=" * 60)
    print("  P0 环境验证")
    print("=" * 60)
    print()
    
    results = []
    
    print("📌 Python 版本检查")
    results.append(check_python_version())
    print()
    
    print("📌 核心依赖检查")
    results.append(check_dependencies())
    print()
    
    print("📌 目录结构检查")
    results.append(check_directory_structure())
    print()
    
    print("📌 配置文件检查")
    results.append(check_config_files())
    print()
    
    print("=" * 60)
    if all(results):
        print("✅ 所有检查通过！P0 环境配置完成。")
    else:
        print("❌ 部分检查未通过，请根据上述提示修复。")
    print("=" * 60)
    
    return 0 if all(results) else 1


if __name__ == "__main__":
    sys.exit(main())
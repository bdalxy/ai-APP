#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
验证 P0 问题修复测试脚本
"""

import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import unittest
from unittest.mock import patch, MagicMock


class TestFixVerification(unittest.TestCase):
    """验证修复的测试用例"""
    
    def test_deepseek_client_inherits_base(self):
        """验证 DeepSeekAPIClient 正确继承 BaseAPIClient"""
        from api_client.deepseek import DeepSeekAPIClient
        from api_client.base import BaseAPIClient
        
        self.assertTrue(issubclass(DeepSeekAPIClient, BaseAPIClient), 
                        "DeepSeekAPIClient 应该继承 BaseAPIClient")
        
        # 验证抽象方法已实现
        client = DeepSeekAPIClient(api_key="test_key")
        self.assertTrue(hasattr(client, 'chat'), "缺少 chat 方法")
        self.assertTrue(hasattr(client, 'chat_simple'), "缺少 chat_simple 方法")
        self.assertTrue(hasattr(client, 'test_connection'), "缺少 test_connection 方法")
        self.assertTrue(hasattr(client, 'close'), "缺少 close 方法")
        client.close()
    
    def test_role_player_init_params(self):
        """验证 RolePlayer 初始化参数匹配"""
        from chat_engine.role_player import RolePlayer
        
        # 测试初始化，参数应该匹配
        memory_mock = MagicMock()
        api_client_mock = MagicMock()
        config = {'MAX_CONTEXT_LENGTH': 20}
        
        player = RolePlayer(memory=memory_mock, api_client=api_client_mock, config=config)
        
        self.assertEqual(player.memory_storage, memory_mock)
        self.assertEqual(player.api_client, api_client_mock)
        self.assertEqual(player.config, config)
        self.assertIsNotNone(player.context_manager)
    
    def test_main_no_missing_imports(self):
        """验证 main.py 不存在的模块引用已移除"""
        # 通过成功导入来验证没有不存在的模块引用
        import main
        
        # 验证关键类存在
        self.assertTrue(hasattr(main, 'WeiXinAI'), "WeiXinAI 类应该存在")
        self.assertTrue(hasattr(main, 'load_config'), "load_config 函数应该存在")
        self.assertTrue(hasattr(main, 'main'), "main 函数应该存在")
    
    def test_wechat_hook_no_dynamic_import(self):
        """验证 wechat_hook.py 不安全的动态导入已移除"""
        # 使用mock避免itchat依赖问题，直接读取源代码文件
        import os
        import inspect
        
        # 获取wechat_hook.py文件路径
        hook_path = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), 
                                'wx_adapter', 'wechat_hook.py')
        
        with open(hook_path, 'r', encoding='utf-8') as f:
            source = f.read()
        
        # 验证不包含危险的动态导入模式
        self.assertNotIn('exec(', source, "不应包含 exec() 动态执行")
        self.assertNotIn('eval(', source, "不应包含 eval() 动态执行")
        self.assertNotIn('__import__(', source, "不应包含 __import__() 动态导入")
        self.assertNotIn('importlib.import_module(', source, "不应包含动态模块导入")
    
    def test_config_validation_strengthened(self):
        """验证配置验证已加强"""
        from config.settings import Settings
        
        # 测试验证方法存在并正常工作
        settings = Settings()
        
        self.assertTrue(hasattr(settings, 'validate'), "缺少 validate 方法")
        self.assertTrue(hasattr(settings, 'ensure_valid'), "缺少 ensure_valid 方法")
        
        # 测试验证功能
        result = settings.validate()
        self.assertIn('valid', result)
        self.assertIn('issues', result)
        self.assertIn('warnings', result)
        
        # 测试无效配置的验证
        invalid_settings = Settings(MEMORY_LEVEL="invalid", API_PORT=70000)
        result = invalid_settings.validate()
        self.assertFalse(result['valid'])
        self.assertTrue(len(result['issues']) > 0)
    
    def test_storage_no_sql_injection(self):
        """验证 SQL 注入风险已修复"""
        from memory.storage import MemoryStorage
        
        # 使用参数化查询的模式应该存在
        import inspect
        source = inspect.getsource(MemoryStorage)
        
        # 验证没有字符串格式化的 SQL 查询（真正的SQL注入风险）
        # 检查是否存在 f-string 格式化的SQL
        self.assertNotIn('cursor.execute(f"', source)
        self.assertNotIn("cursor.execute(f'", source)
        
        # 检查是否存在 % 格式化的SQL
        self.assertNotIn('cursor.execute(%', source)
        
        # 验证使用参数化查询（检查 ? 占位符模式）
        self.assertIn('VALUES (?, ?, ', source)
        
        # 验证存在参数化调用模式
        # 检查是否有 ", (" 模式（参数化查询的特征）
        self.assertIn(', (conversation_id,', source)
    
    def test_device_detector_no_bare_except(self):
        """验证裸异常捕获已修复"""
        from utils import device_detector
        
        # 检查源代码中是否存在裸 except
        import inspect
        source = inspect.getsource(device_detector)
        
        # 不应存在裸 except
        # 正确的写法是 except Exception: 或更具体的异常类型
        lines = source.split('\n')
        for i, line in enumerate(lines):
            # 检查是否有裸 except（except: 或 except Exception: 以外的形式）
            if 'except:' in line and 'Exception:' not in line:
                # 检查是否是注释或字符串中的内容
                if '#' not in line.split('except')[0] and '"' not in line.split('except')[0]:
                    self.fail(f"第 {i+1} 行存在裸异常捕获: {line.strip()}")


if __name__ == '__main__':
    print("=== 开始验证 P0 问题修复 ===")
    print()
    
    suite = unittest.TestLoader().loadTestsFromTestCase(TestFixVerification)
    runner = unittest.TextTestRunner(verbosity=2)
    result = runner.run(suite)
    
    print()
    print("=== 验证结果汇总 ===")
    print(f"测试总数: {result.testsRun}")
    print(f"失败: {len(result.failures)}")
    print(f"错误: {len(result.errors)}")
    print(f"跳过: {len(result.skipped)}")
    
    if result.wasSuccessful():
        print("✅ 所有修复验证通过！")
    else:
        print("❌ 部分修复验证失败，请检查上述错误信息")
        sys.exit(1)

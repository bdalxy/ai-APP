# -*- coding: utf-8 -*-
"""
操作日志模块
记录所有增删改查操作，便于审计和追溯
"""

import os
import json
import logging
from datetime import datetime
from typing import Dict, Any, Optional, List
from enum import Enum
from pathlib import Path

logger = logging.getLogger(__name__)

class OperationType(Enum):
    """操作类型枚举"""
    CREATE = "CREATE"      # 创建
    READ = "READ"          # 读取
    UPDATE = "UPDATE"      # 更新
    DELETE = "DELETE"      # 删除
    CONFIG = "CONFIG"      # 配置
    SEARCH = "SEARCH"      # 搜索
    CHAT = "CHAT"          # 对话
    IMPORT = "IMPORT"      # 导入
    EXPORT = "EXPORT"      # 导出
    SYSTEM = "SYSTEM"      # 系统操作

class Logger:
    """操作日志管理器"""
    
    VERSION = "1.0.0"
    LOG_DIR = "logs"
    
    def __init__(self, app_version: str = "1.0.0"):
        """
        初始化日志管理器
        
        Args:
            app_version: 应用版本号
        """
        self.app_version = app_version
        self.log_dir = Path(self.LOG_DIR)
        self.log_dir.mkdir(parents=True, exist_ok=True)
        
        # 加载历史日志索引
        self._load_index()
        
        logger.info(f"日志管理器初始化完成 (版本: {self.VERSION})")
    
    def _load_index(self):
        """加载日志索引"""
        self.index_file = self.log_dir / "index.json"
        if self.index_file.exists():
            with open(self.index_file, 'r', encoding='utf-8') as f:
                self.index = json.load(f)
        else:
            self.index = {
                "version": self.VERSION,
                "entries": [],
                "total_records": 0
            }
    
    def _save_index(self):
        """保存日志索引"""
        with open(self.index_file, 'w', encoding='utf-8') as f:
            json.dump(self.index, f, ensure_ascii=False, indent=2)
    
    def _get_log_filename(self) -> str:
        """生成日志文件名（按日期）"""
        today = datetime.now().strftime("%Y%m%d")
        return f"operations_{today}.log"
    
    def _get_log_filepath(self) -> Path:
        """获取日志文件路径"""
        return self.log_dir / self._get_log_filename()
    
    def log(
        self,
        operation: OperationType,
        target: str,
        details: Dict[str, Any] = None,
        success: bool = True,
        error_message: str = None,
        user_info: Dict[str, Any] = None
    ):
        """
        记录操作日志
        
        Args:
            operation: 操作类型
            target: 操作目标（如角色卡名称、API端点等）
            details: 详细信息字典
            success: 是否成功
            error_message: 错误信息（失败时）
            user_info: 用户信息（如IP、用户ID等）
        """
        log_entry = {
            "timestamp": datetime.now().isoformat(),
            "version": self.app_version,
            "operation": operation.value,
            "target": target,
            "details": details or {},
            "success": success,
            "error_message": error_message,
            "user_info": user_info or {},
            "record_id": self._generate_record_id()
        }
        
        # 写入日志文件
        self._write_to_file(log_entry)
        
        # 更新索引
        self._update_index(log_entry)
        
        # 控制台日志
        status = "✅" if success else "❌"
        logger.info(f"{status} [{operation.value}] {target} - {details}")
    
    def _generate_record_id(self) -> str:
        """生成唯一记录ID"""
        timestamp = datetime.now().strftime("%Y%m%d%H%M%S")
        sequence = str(self.index["total_records"] + 1).zfill(6)
        return f"LOG-{timestamp}-{sequence}"
    
    def _write_to_file(self, entry: Dict[str, Any]):
        """写入日志文件"""
        filepath = self._get_log_filepath()
        
        # 追加模式写入
        with open(filepath, 'a', encoding='utf-8') as f:
            line = json.dumps(entry, ensure_ascii=False)
            f.write(line + "\n")
    
    def _update_index(self, entry: Dict[str, Any]):
        """更新日志索引"""
        index_entry = {
            "record_id": entry["record_id"],
            "timestamp": entry["timestamp"],
            "operation": entry["operation"],
            "target": entry["target"],
            "filename": self._get_log_filename(),
            "success": entry["success"]
        }
        
        self.index["entries"].append(index_entry)
        self.index["total_records"] += 1
        
        # 保持索引文件大小，只保留最近1000条索引
        if len(self.index["entries"]) > 1000:
            self.index["entries"] = self.index["entries"][-1000:]
        
        self._save_index()
    
    def query_logs(
        self,
        operation: OperationType = None,
        target: str = None,
        start_time: datetime = None,
        end_time: datetime = None,
        success: bool = None
    ) -> List[Dict[str, Any]]:
        """
        查询日志
        
        Args:
            operation: 操作类型过滤
            target: 目标过滤
            start_time: 开始时间
            end_time: 结束时间
            success: 是否成功过滤
        
        Returns:
            匹配的日志条目列表
        """
        results = []
        
        for index_entry in self.index["entries"]:
            # 应用过滤条件
            if operation and index_entry["operation"] != operation.value:
                continue
            if target and target not in index_entry["target"]:
                continue
            if success is not None and index_entry["success"] != success:
                continue
            
            # 时间过滤
            entry_time = datetime.fromisoformat(index_entry["timestamp"])
            if start_time and entry_time < start_time:
                continue
            if end_time and entry_time > end_time:
                continue
            
            # 读取完整日志条目
            full_entry = self._read_entry_from_file(index_entry["filename"], index_entry["record_id"])
            if full_entry:
                results.append(full_entry)
        
        return sorted(results, key=lambda x: x["timestamp"], reverse=True)
    
    def _read_entry_from_file(self, filename: str, record_id: str) -> Optional[Dict[str, Any]]:
        """从日志文件读取指定条目"""
        filepath = self.log_dir / filename
        if not filepath.exists():
            return None
        
        with open(filepath, 'r', encoding='utf-8') as f:
            for line in f:
                try:
                    entry = json.loads(line.strip())
                    if entry.get("record_id") == record_id:
                        return entry
                except json.JSONDecodeError:
                    continue
        
        return None
    
    def get_stats(self) -> Dict[str, Any]:
        """获取日志统计信息"""
        stats = {
            "total_records": self.index["total_records"],
            "version": self.VERSION,
            "app_version": self.app_version,
            "log_files": len(list(self.log_dir.glob("operations_*.log"))),
            "operations": {}
        }
        
        # 按操作类型统计
        for op in OperationType:
            count = sum(1 for e in self.index["entries"] if e["operation"] == op.value)
            stats["operations"][op.value] = count
        
        return stats
    
    def export_logs(self, output_path: str, format_type: str = "json") -> bool:
        """
        导出日志
        
        Args:
            output_path: 输出路径
            format_type: 导出格式 (json/text)
        
        Returns:
            是否成功
        """
        try:
            if format_type == "json":
                all_logs = []
                for log_file in sorted(self.log_dir.glob("operations_*.log")):
                    with open(log_file, 'r', encoding='utf-8') as f:
                        for line in f:
                            try:
                                all_logs.append(json.loads(line.strip()))
                            except json.JSONDecodeError:
                                continue
                
                with open(output_path, 'w', encoding='utf-8') as f:
                    json.dump(all_logs, f, ensure_ascii=False, indent=2)
            else:
                # 文本格式
                with open(output_path, 'w', encoding='utf-8') as f:
                    f.write(f"=== WeiXinAI 操作日志导出 ===\n")
                    f.write(f"版本: {self.VERSION}\n")
                    f.write(f"导出时间: {datetime.now().isoformat()}\n")
                    f.write(f"总记录数: {self.index['total_records']}\n")
                    f.write("="*50 + "\n\n")
                    
                    for log_file in sorted(self.log_dir.glob("operations_*.log")):
                        f.write(f"--- {log_file.name} ---\n")
                        with open(log_file, 'r', encoding='utf-8') as f_log:
                            f.write(f_log.read())
                        f.write("\n")
            
            logger.info(f"日志导出成功: {output_path}")
            return True
        except Exception as e:
            logger.error(f"日志导出失败: {e}")
            return False
    
    def clear_old_logs(self, days_to_keep: int = 30):
        """
        清理旧日志
        
        Args:
            days_to_keep: 保留天数
        """
        cutoff_date = datetime.now() - datetime.timedelta(days=days_to_keep)
        cutoff_str = cutoff_date.strftime("%Y%m%d")
        
        deleted_count = 0
        for log_file in self.log_dir.glob("operations_*.log"):
            file_date = log_file.name.replace("operations_", "").replace(".log", "")
            if file_date < cutoff_str:
                log_file.unlink()
                deleted_count += 1
                logger.info(f"删除旧日志: {log_file.name}")
        
        # 清理索引中已删除文件的条目
        self.index["entries"] = [
            e for e in self.index["entries"]
            if (self.log_dir / e["filename"]).exists()
        ]
        self._save_index()
        
        logger.info(f"清理完成，共删除 {deleted_count} 个日志文件")

def get_logger(app_version: str = "1.0.0") -> Logger:
    """获取日志管理器实例"""
    return Logger(app_version)

# 示例用法
if __name__ == "__main__":
    log = get_logger("1.0.0")
    
    # 记录示例操作
    log.log(
        operation=OperationType.CREATE,
        target="角色卡",
        details={"name": "测试角色", "action": "创建角色卡"},
        success=True,
        user_info={"ip": "127.0.0.1"}
    )
    
    log.log(
        operation=OperationType.CHAT,
        target="AI对话",
        details={"message": "你好", "character": "测试角色"},
        success=True
    )
    
    log.log(
        operation=OperationType.CONFIG,
        target="API配置",
        details={"api_key": "sk-****", "model": "deepseek-v4"},
        success=True
    )
    
    # 查询日志
    logs = log.query_logs(operation=OperationType.CREATE)
    print(f"查询到 {len(logs)} 条创建操作日志")
    
    # 打印统计信息
    stats = log.get_stats()
    print("日志统计:", stats)
    
    # 导出日志
    log.export_logs("logs/export_all.json")

# -*- coding: utf-8 -*-
"""
设备检测模块
用于检测设备性能并推荐合适的配置设置

**隐私说明**:
- 本模块仅获取设备硬件信息（CPU核心数、内存大小、设备型号等）
- 所有检测都在本地完成，不会上传任何数据到服务器
- 不收集任何个人身份信息（如用户ID、位置、联系人等）
- 用户可以选择不使用此功能或手动配置
"""

import os
import platform
import sys
from typing import Dict, Any, Optional, Tuple
from dataclasses import dataclass

try:
    import psutil
except ImportError:
    psutil = None


@dataclass
class DeviceInfo:
    """设备信息数据类"""
    os_type: str              # 操作系统类型
    os_version: str           # 操作系统版本
    device_model: str         # 设备型号
    cpu_cores: int            # CPU核心数
    cpu_arch: str             # CPU架构
    total_memory: float       # 总内存（GB）
    available_memory: float   # 可用内存（GB）
    disk_space: float         # 磁盘空间（GB）
    is_mobile: bool           # 是否为移动设备


class DeviceDetector:
    """设备检测器"""
    
    def __init__(self):
        self._platform = platform.system().lower()
    
    def detect(self) -> DeviceInfo:
        """
        检测设备信息
        
        Returns:
            DeviceInfo: 设备信息对象
        """
        return DeviceInfo(
            os_type=self._get_os_type(),
            os_version=self._get_os_version(),
            device_model=self._get_device_model(),
            cpu_cores=self._get_cpu_cores(),
            cpu_arch=self._get_cpu_arch(),
            total_memory=self._get_total_memory(),
            available_memory=self._get_available_memory(),
            disk_space=self._get_disk_space(),
            is_mobile=self._is_mobile()
        )
    
    def _get_os_type(self) -> str:
        """获取操作系统类型"""
        return self._platform
    
    def _get_os_version(self) -> str:
        """获取操作系统版本"""
        if self._platform == 'windows':
            return platform.version()
        elif self._platform == 'darwin':
            return platform.mac_ver()[0]
        elif self._platform == 'linux':
            try:
                with open('/etc/os-release', 'r') as f:
                    for line in f:
                        if line.startswith('VERSION='):
                            return line.split('=')[1].strip().strip('"')
            except FileNotFoundError:
                pass
            return platform.release()
        return platform.release()
    
    def _get_device_model(self) -> str:
        """获取设备型号"""
        if self._platform == 'windows':
            try:
                import winreg
                with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, 
                                   r"HARDWARE\DESCRIPTION\System\BIOS") as key:
                    return winreg.QueryValueEx(key, "SystemProductName")[0]
            except (FileNotFoundError, OSError):
                return "Windows PC"
        elif self._platform == 'darwin':
            try:
                import subprocess
                result = subprocess.run(
                    ['sysctl', '-n', 'hw.model'],
                    capture_output=True, text=True
                )
                return result.stdout.strip()
            except (FileNotFoundError, OSError, ModuleNotFoundError):
                return "Mac"
        elif self._platform == 'linux':
            try:
                with open('/proc/device-tree/model', 'r') as f:
                    return f.read().strip()
            except (FileNotFoundError, OSError):
                try:
                    with open('/sys/devices/virtual/dmi/id/product_name', 'r') as f:
                        return f.read().strip()
                except (FileNotFoundError, OSError):
                    return "Linux Device"
        return "Unknown"
    
    def _get_cpu_cores(self) -> int:
        """获取CPU核心数"""
        if psutil:
            return psutil.cpu_count(logical=True) or 4
        return os.cpu_count() or 4
    
    def _get_cpu_arch(self) -> str:
        """获取CPU架构"""
        return platform.machine()
    
    def _get_total_memory(self) -> float:
        """获取总内存（GB）"""
        if psutil:
            return round(psutil.virtual_memory().total / (1024 ** 3), 2)
        return 4.0  # 默认值
    
    def _get_available_memory(self) -> float:
        """获取可用内存（GB）"""
        if psutil:
            return round(psutil.virtual_memory().available / (1024 ** 3), 2)
        return 2.0  # 默认值
    
    def _get_disk_space(self) -> float:
        """获取磁盘空间（GB）"""
        if psutil:
            disk = psutil.disk_usage('/')
            return round(disk.total / (1024 ** 3), 2)
        return 64.0  # 默认值
    
    def _is_mobile(self) -> bool:
        """判断是否为移动设备"""
        model = self._get_device_model().lower()
        mobile_keywords = ['iphone', 'ipad', 'android', 'pixel', 'galaxy', 
                           'xiaomi', 'huawei', 'oppo', 'vivo', 'redmi']
        return any(keyword in model for keyword in mobile_keywords)
    
    def recommend_memory_level(self) -> str:
        """
        根据设备性能推荐记忆级别
        
        Returns:
            str: 推荐的记忆级别 (basic/standard/advanced)
        """
        info = self.detect()
        
        # 根据设备类型和性能决定推荐级别
        if info.is_mobile:
            # 移动设备推荐较低级别以节省资源
            if info.total_memory >= 8:
                return "standard"
            else:
                return "basic"
        else:
            # 桌面设备
            if info.total_memory >= 16:
                return "advanced"
            elif info.total_memory >= 8:
                return "standard"
            else:
                return "basic"
    
    def recommend_settings(self) -> Dict[str, Any]:
        """
        获取完整的推荐配置
        
        Returns:
            Dict: 推荐的配置字典
        """
        info = self.detect()
        memory_level = self.recommend_memory_level()
        
        return {
            "device_info": {
                "os_type": info.os_type,
                "os_version": info.os_version,
                "device_model": info.device_model,
                "cpu_cores": info.cpu_cores,
                "cpu_arch": info.cpu_arch,
                "total_memory_gb": info.total_memory,
                "available_memory_gb": info.available_memory,
                "disk_space_gb": info.disk_space,
                "is_mobile": info.is_mobile
            },
            "recommendations": {
                "memory_level": memory_level,
                "ai_mode": "cloud" if info.is_mobile else "cloud",
                "max_concurrent_conversations": 3 if info.is_mobile else 10,
                "suggestion": self._get_suggestion(info, memory_level)
            }
        }
    
    def _get_suggestion(self, info: DeviceInfo, memory_level: str) -> str:
        """生成配置建议文字"""
        if info.is_mobile:
            if memory_level == "basic":
                return f"您的设备是移动设备，内存 {info.total_memory}GB。推荐使用基础记忆模式，以节省系统资源。"
            else:
                return f"您的设备是移动设备，内存 {info.total_memory}GB。推荐使用标准记忆模式。"
        else:
            if memory_level == "advanced":
                return f"您的设备配置较高（内存 {info.total_memory}GB），推荐使用高级记忆模式，享受完整的记忆体验。"
            elif memory_level == "standard":
                return f"您的设备内存 {info.total_memory}GB，推荐使用标准记忆模式，平衡性能和功能。"
            else:
                return f"您的设备内存 {info.total_memory}GB，推荐使用基础记忆模式以优化性能。"


def get_device_detector() -> DeviceDetector:
    """获取设备检测器实例"""
    return DeviceDetector()


# 示例用法
if __name__ == "__main__":
    detector = get_device_detector()
    
    print("=== 设备检测结果 ===")
    info = detector.detect()
    print(f"操作系统: {info.os_type} {info.os_version}")
    print(f"设备型号: {info.device_model}")
    print(f"CPU: {info.cpu_cores} 核心, {info.cpu_arch}")
    print(f"内存: {info.total_memory} GB (可用: {info.available_memory} GB)")
    print(f"磁盘空间: {info.disk_space} GB")
    print(f"是否移动设备: {'是' if info.is_mobile else '否'}")
    
    print("\n=== 推荐配置 ===")
    settings = detector.recommend_settings()
    print(f"推荐记忆级别: {settings['recommendations']['memory_level']}")
    print(f"建议: {settings['recommendations']['suggestion']}")
    
    print("\n=== 隐私声明 ===")
    print("✓ 所有检测均在本地完成")
    print("✓ 不上传任何数据")
    print("✓ 不收集个人身份信息")
    print("✓ 用户可自由选择是否使用此功能")

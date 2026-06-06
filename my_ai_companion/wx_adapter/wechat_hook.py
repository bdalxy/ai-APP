# -*- coding: utf-8 -*-
"""
微信消息适配器 - 使用非官方协议

⚠️ 风险警告：
1. 使用此功能可能导致微信账号被封禁
2. 强烈建议使用小号测试
3. 请勿用于商业用途或频繁发送消息
4. 作者不对任何账号封禁负责

**功能特点**:
- 接收微信消息并自动回复
- 支持角色卡选择
- 集成记忆系统
- 支持私聊和群聊
"""

import sys
import time
from pathlib import Path
from typing import Dict, Any, Optional

# 添加项目根目录到路径
project_root = Path(__file__).parent.parent
sys.path.insert(0, str(project_root))

import itchat

from chat_engine.card_parser import CardManager, CharacterCard
from chat_engine.role_player import RolePlayer
from memory.storage import get_memory_storage
from api_client.deepseek import DeepSeekAPIClient
from config.settings import load_settings


class WeChatAdapter:
    """微信消息适配器"""
    
    def __init__(self):
        print("⚠️ 警告：使用非官方协议接入微信，可能导致账号被封禁！")
        print("⚠️ 请确保使用小号测试！")
        
        self.settings = load_settings()
        self.card_manager = CardManager(cards_dir="cards")
        self.memory = get_memory_storage(level=self.settings.MEMORY_LEVEL)
        
        # 初始化角色播放器
        self.role_player = None
        self._init_role_player()
        
        # 当前选中的角色
        self.current_character = None
        
        # 消息统计
        self.message_count = 0
        
        # 白名单（只回复指定用户）
        self.whitelist = []
        
        # 是否只回复私聊
        self.only_private = True
    
    def _init_role_player(self):
        """初始化角色播放器"""
        try:
            api_client = DeepSeekAPIClient(
                api_key=self.settings.DEEPSEEK_API_KEY,
                api_url=self.settings.DEEPSEEK_API_URL
            )
            self.role_player = RolePlayer(
                memory=self.memory,
                api_client=api_client
            )
            print("✅ AI 角色播放器初始化成功")
        except Exception as e:
            print(f"❌ 初始化角色播放器失败: {e}")
            self.role_player = None
    
    def set_character(self, character_name: str) -> bool:
        """选择角色卡"""
        card = self.card_manager.get_card(character_name)
        if card:
            self.current_character = card
            if self.role_player:
                self.role_player.character_card = card
            print(f"✅ 已选择角色: {character_name}")
            return True
        print(f"❌ 未找到角色: {character_name}")
        return False
    
    def add_to_whitelist(self, user_name: str):
        """添加用户到白名单"""
        if user_name not in self.whitelist:
            self.whitelist.append(user_name)
            print(f"✅ 用户 {user_name} 已添加到白名单")
    
    def set_only_private(self, enabled: bool):
        """设置是否只回复私聊"""
        self.only_private = enabled
        print(f"✅ 仅私聊模式: {'开启' if enabled else '关闭'}")
    
    def _get_conversation_id(self, from_user: str, group_name: str = None) -> str:
        """生成对话ID"""
        if group_name:
            return f"group_{group_name}"
        return f"private_{from_user}"
    
    def _generate_response(self, message: str, conversation_id: str) -> str:
        """生成回复消息"""
        if not self.role_player:
            return "抱歉，AI服务未初始化，请检查配置。"
        
        try:
            # 保存用户消息到记忆
            self.memory.save_message(
                conversation_id=conversation_id,
                role="user",
                content=message
            )
            
            # 获取AI回复
            response = self.role_player.chat(
                user_message=message,
                conversation_id=conversation_id
            )
            
            # 保存回复到记忆
            self.memory.save_message(
                conversation_id=conversation_id,
                role="assistant",
                content=response
            )
            
            return response
        except Exception as e:
            error_msg = f"获取回复失败: {str(e)}"
            print(error_msg)
            return "抱歉，我现在有点忙，稍后再聊~"
    
    def _is_allowed(self, msg) -> bool:
        """检查是否允许回复"""
        # 如果有白名单且用户不在白名单中
        if self.whitelist and msg['FromUserName'] not in self.whitelist:
            return False
        
        # 如果只回复私聊且消息来自群聊
        if self.only_private and msg['FromUserName'].startswith('@@'):
            return False
        
        return True
    
    @itchat.msg_register(itchat.content.TEXT)
    def on_text_message(self, msg):
        """处理文本消息"""
        # 跳过自己发送的消息
        if msg['FromUserName'] == msg['ToUserName']:
            return
        
        # 检查是否允许回复
        if not self._is_allowed(msg):
            return
        
        self.message_count += 1
        
        # 获取发送者信息
        from_user = msg['FromUserName']
        group_name = None
        
        # 判断是否是群聊
        is_group = from_user.startswith('@@')
        
        # 获取群聊名称
        if is_group:
            group_name = itchat.search_chatrooms(userName=from_user)[0]['NickName']
            sender_name = msg['ActualNickName']
        else:
            sender_info = itchat.search_friends(userName=from_user)
            sender_name = sender_info['NickName'] if sender_info else "未知用户"
        
        message = msg['Text'].strip()
        
        print(f"\n[{time.strftime('%H:%M:%S')}] {'群聊' if is_group else '私聊'} {sender_name}: {message}")
        
        # 命令处理
        if message.startswith('/'):
            self._handle_command(message, from_user)
            return
        
        # 生成回复
        conversation_id = self._get_conversation_id(from_user, group_name)
        response = self._generate_response(message, conversation_id)
        
        print(f"[{time.strftime('%H:%M:%S')}] AI回复: {response[:50]}...")
        
        # 发送回复
        itchat.send_msg(response, toUserName=from_user)
    
    def _handle_command(self, command: str, to_user: str):
        """处理命令"""
        cmd_parts = command.split()
        cmd = cmd_parts[0].lower()
        
        if cmd == '/help':
            help_text = """
🤖 WeiXinAI 命令帮助：

/help - 显示帮助信息
/character <角色名> - 切换角色
/list - 列出所有角色
/clear - 清空当前对话记录
/whitelist add <备注> - 添加用户到白名单
/whitelist clear - 清空白名单
/group on - 允许群聊回复
/group off - 禁止群聊回复
/stats - 显示统计信息
"""
            itchat.send_msg(help_text.strip(), toUserName=to_user)
        
        elif cmd == '/character' and len(cmd_parts) > 1:
            character_name = ' '.join(cmd_parts[1:])
            if self.set_character(character_name):
                itchat.send_msg(f"已切换角色为: {character_name}", toUserName=to_user)
            else:
                itchat.send_msg(f"未找到角色: {character_name}", toUserName=to_user)
        
        elif cmd == '/list':
            cards = self.card_manager.list_cards()
            if cards:
                names = "\n".join([f"• {card.name}" for card in cards])
                itchat.send_msg(f"可用角色:\n{names}", toUserName=to_user)
            else:
                itchat.send_msg("暂无角色卡，请先创建", toUserName=to_user)
        
        elif cmd == '/clear':
            conversation_id = self._get_conversation_id(to_user)
            self.memory.delete_conversation(conversation_id)
            itchat.send_msg("已清空当前对话记录", toUserName=to_user)
        
        elif cmd.startswith('/whitelist'):
            if len(cmd_parts) >= 2:
                if cmd_parts[1] == 'add' and len(cmd_parts) > 2:
                    remark = ' '.join(cmd_parts[2:])
                    user = itchat.search_friends(remarkName=remark)
                    if user:
                        self.add_to_whitelist(user[0]['UserName'])
                        itchat.send_msg(f"已添加 {remark} 到白名单", toUserName=to_user)
                    else:
                        itchat.send_msg(f"未找到用户: {remark}", toUserName=to_user)
                elif cmd_parts[1] == 'clear':
                    self.whitelist = []
                    itchat.send_msg("已清空白名单", toUserName=to_user)
            else:
                itchat.send_msg("白名单命令: /whitelist add <备注> 或 /whitelist clear", toUserName=to_user)
        
        elif cmd.startswith('/group'):
            if len(cmd_parts) > 1:
                if cmd_parts[1] == 'on':
                    self.set_only_private(False)
                    itchat.send_msg("已开启群聊回复", toUserName=to_user)
                elif cmd_parts[1] == 'off':
                    self.set_only_private(True)
                    itchat.send_msg("已关闭群聊回复", toUserName=to_user)
            else:
                itchat.send_msg("群聊模式命令: /group on 或 /group off", toUserName=to_user)
        
        elif cmd == '/stats':
            stats = self.memory.get_stats()
            stats_text = f"""
📊 统计信息：
• 消息数量: {self.message_count}
• 记忆级别: {self.settings.MEMORY_LEVEL}
• 当前角色: {self.current_character.name if self.current_character else '无'}
• 存储消息数: {stats.get('message_count', 0)}
"""
            itchat.send_msg(stats_text.strip(), toUserName=to_user)
        
        else:
            itchat.send_msg(f"未知命令: {cmd}\n输入 /help 查看帮助", toUserName=to_user)
    
    def run(self, hot_reload: bool = True):
        """启动微信监听"""
        print("\n🚀 正在启动微信适配器...")
        print("📱 请使用微信扫码登录（建议使用小号）")
        print("⚠️ 注意：登录后请勿手动关闭微信网页")
        
        # 清除之前的登录缓存
        itchat.logout()
        
        # 登录
        itchat.auto_login(
            hotReload=hot_reload,
            enableCmdQR=True,
            exitCallback=self._on_exit
        )
        
        print("✅ 微信登录成功！")
        print(f"📋 当前角色: {self.current_character.name if self.current_character else '无'}")
        print("💬 开始监听消息...")
        
        # 发送欢迎消息
        self._send_welcome_message()
        
        # 保持运行
        itchat.run()
    
    def _send_welcome_message(self):
        """发送欢迎消息给文件传输助手"""
        welcome_text = """
🤖 WeiXinAI 已启动！

命令列表：
/help - 显示帮助信息
/list - 列出所有角色
/character <角色名> - 切换角色
/clear - 清空对话记录
/stats - 显示统计信息

开始聊天吧！
        """.strip()
        
        try:
            file_helper = itchat.search_mps(name='文件传输助手')[0]
            itchat.send_msg(welcome_text, toUserName=file_helper['UserName'])
        except IndexError:
            logger.warning("未找到文件传输助手，跳过欢迎消息发送")
        except Exception as e:
            logger.error(f"发送欢迎消息失败: {e}")
    
    def _on_exit(self):
        """退出回调"""
        print("\n👋 微信适配器已退出")


def main():
    """主函数"""
    # 显示风险提示
    print("=" * 60)
    print("⚠️  微信消息适配器 - 风险警告")
    print("=" * 60)
    print("• 使用非官方协议接入微信")
    print("• 可能导致微信账号被封禁")
    print("• 强烈建议使用小号测试")
    print("• 请勿用于商业用途")
    print("=" * 60)
    
    # 创建适配器
    adapter = WeChatAdapter()
    
    # 启动
    try:
        adapter.run()
    except KeyboardInterrupt:
        print("\n👋 收到退出信号，正在退出...")
        itchat.logout()


if __name__ == "__main__":
    main()

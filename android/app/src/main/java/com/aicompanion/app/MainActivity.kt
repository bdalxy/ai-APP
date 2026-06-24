package com.aicompanion.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityOptionsCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aicompanion.app.databinding.ActivityMainBinding
import com.aicompanion.app.views.RecordingOverlayView
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * AI 角色扮演聊天主界面 (P3)
 *
 * 通过 Chaquopy 调用 Python chat_bridge 模块，
 * 实现与 AI 角色的实时对话。
 *
 * 职责拆分：
 * - ChatViewModel：消息管理、流式对话、搜索、导出
 * - VoiceController：语音识别、录制、播放、TTS
 * - ConversationCoordinator：多会话切换、保存/加载
 * - MainActivity：生命周期、权限、导航、UI 初始化、录音覆盖层
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val FLING_THRESHOLD = 150f
        private const val FLING_RATIO = 1.5f
        private const val SCROLL_BOTTOM_THRESHOLD = 3
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyLanguage(newBase))
    }

    private lateinit var pythonModule: com.chaquo.python.PyObject
    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: ChatAdapter
    private lateinit var gestureDetector: GestureDetector

    // ======================== 协调器 ========================

    private lateinit var chatViewModel: ChatViewModel
    private lateinit var voiceController: VoiceController
    private lateinit var conversationCoordinator: ConversationCoordinator

    // ======================== 录音覆盖层 ========================

    /** 录音中覆盖层 View */
    private lateinit var recordingOverlay: RecordingOverlayView

    // ======================== Activity Result 回调 ========================

    /** 角色选择回调 */
    private val characterSelectLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            syncCharacterToPython()
        }
    }

    /** 录音权限请求回调 */
    private val voicePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            voiceController.startVoiceRecognition()
        } else {
            Toast.makeText(this, R.string.toast_voice_permission_denied, Toast.LENGTH_SHORT).show()
        }
    }

    /** 图片选择回调 */
    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            binding.etInput.setText(binding.etInput.text.toString() + " [图片: $it]")
            binding.etInput.setSelection(binding.etInput.text.length)
            Toast.makeText(this, "图片已选择", Toast.LENGTH_SHORT).show()
        }
    }

    // ======================== 生命周期 ========================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewUtils.setupEdgeToEdge(this)
        applyInsets(binding.mainRoot)

        // 创建 Adapter
        adapter = ChatAdapter(mutableListOf(),
            onMessageLongClick = { message, position ->
                chatViewModel.showMessageContextMenu(message, position)
            },
            onVoiceClick = { filePath, play ->
                if (play) voiceController.playVoiceMessage(filePath)
                else voiceController.pauseVoiceMessage()
            },
            onMessagesTrimmed = {
                binding.tvArchiveHint.visibility = View.VISIBLE
            }
        )
        binding.rvMessages.adapter = adapter
        binding.rvMessages.layoutManager = LinearLayoutManager(this)
        binding.rvMessages.itemAnimator = MessageItemAnimator()

        // 创建三个协调器
        chatViewModel = ChatViewModel(this, binding, adapter, lifecycleScope)
        voiceController = VoiceController(
            context = this,
            binding = binding,
            lifecycleScope = lifecycleScope,
            adapter = adapter,
            isStreamingProvider = { chatViewModel.isStreaming },
            hasRecordPermission = {
                checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            },
            requestRecordPermission = {
                voicePermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            }
        )
        conversationCoordinator = ConversationCoordinator(this, adapter, lifecycleScope)

        // 设置回调
        setupCallbacks()

        // 绑定 UI 事件
        setupUI()

        // 初始化多会话管理器
        ConversationSessionManager.init(this)

        // 异步初始化 Python
        initializePythonAsync()

        

        // 初始化完成
    }

    override fun onDestroy() {
        super.onDestroy()
        chatViewModel.destroy()
        voiceController.destroy()
        if (::recordingOverlay.isInitialized) {
            recordingOverlay.hide()
        }
    }

    override fun onStop() {
        super.onStop()
        // 进入后台时保存当前会话并取消活跃流
        if (::conversationCoordinator.isInitialized) {
            conversationCoordinator.saveConversation()
        }
        if (::chatViewModel.isInitialized && chatViewModel.isStreaming) {
            chatViewModel.cancelActiveStream()
        }
    }

    override fun onResume() {
        super.onResume()
        // 从其他页面返回时刷新：确保 Python 缓存和会话列表是最新的
        if (::pythonModule.isInitialized && !chatViewModel.isStreaming) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    pythonModule.callAttr("invalidate_cache")
                } catch (_: Exception) {
                    // 缓存失效失败不影响主流程
                }
            }
        }
        // 只在消息列表为空时从磁盘加载（避免覆盖当前对话）
        if (::conversationCoordinator.isInitialized && ::chatViewModel.isInitialized) {
            if (chatViewModel.getMessageCount() == 0) {
                conversationCoordinator.loadConversation()
            } else {
                // 已显示消息，仅刷新会话列表元数据
                conversationCoordinator.refreshSessionMeta()
            }
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev != null) gestureDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    // ======================== 回调设置 ========================

    private fun setupCallbacks() {
        // ChatViewModel 回调
        chatViewModel.callback = object : ChatViewModel.ChatCallback {
            override fun onConversationNeedSave() {
                conversationCoordinator.saveConversation()
            }

            override fun onStreamComplete(fullContent: String) {
                voiceController.speakAIContentIfNeeded(fullContent)
            }

            override fun onStreamSentence(sentence: String) {
                voiceController.speakSentenceStreaming(sentence)
            }

            override fun onNewMessageSent() {
                voiceController.stopTtsAndClear()
            }
        }

        // VoiceController 回调
        voiceController.callback = object : VoiceController.VoiceCallback {
            override fun onRecordingOverlayShow() {
                recordingOverlay.show()
            }

            override fun onRecordingOverlayHide() {
                recordingOverlay.hide()
            }

            override fun onRecordingDurationUpdate(durationStr: String) {
                recordingOverlay.updateDuration(durationStr)
            }

            override fun onVoiceInputTriggered() {
                // 语音识别结果已填入输入框，wasVoiceInput 已在 VoiceController 内部标记
                // 用户手动点击发送后会触发 TTS 自动朗读
            }

            override fun onVoiceInputReady(text: String) {
                // 语音识别完成后自动发送
                chatViewModel.sendMessage()
            }

            override fun onError(error: String) {
                Toast.makeText(this@MainActivity, error, Toast.LENGTH_SHORT).show()
            }
        }

        // ConversationCoordinator 回调
        conversationCoordinator.callback = object : ConversationCoordinator.ConversationCallback {
            override fun onSessionChanged(messages: List<Message>) {
                chatViewModel.replaceMessages(messages)
                if (messages.isNotEmpty()) {
                    binding.rvMessages.scrollToPosition(messages.size - 1)
                }
                // 同步对话历史到 Python 引擎
                syncHistoryToPython(messages)
            }

            override fun onSessionCreated(name: String) {
                Toast.makeText(this@MainActivity, getString(R.string.toast_session_created, name), Toast.LENGTH_SHORT).show()
            }

            override fun onSessionDeleted() {
                Toast.makeText(this@MainActivity, R.string.toast_session_deleted, Toast.LENGTH_SHORT).show()
            }

            override fun onError(error: String) {
                Toast.makeText(this@MainActivity, error, Toast.LENGTH_SHORT).show()
            }

            override fun onPythonResetNeeded() {
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val module = com.chaquo.python.Python.getInstance().getModule("chat_bridge")
                        module?.callAttr("reset")
                    } catch (e: Exception) {
                        Log.w("MainActivity", "重置 Python 引擎失败: ${e.message}")
                    }
                }
            }
        }
    }

    // ======================== UI 事件绑定 ========================

    private fun setupUI() {
        // 发送按钮
        binding.btnSend.setOnClickListener { chatViewModel.sendMessage() }

        // 语音按钮（触摸事件 → VoiceController）
        binding.btnVoice.setOnTouchListener { _, event ->
            voiceController.onVoiceButtonTouch(event)
            true
        }
        // 语音按钮长按 → 打开插件面板
        binding.btnVoice.setOnLongClickListener {
            showPluginPanel()
            true
        }

        // 设置按钮
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnSettings.setOnLongClickListener {
            chatViewModel.showExportDialog()
            true
        }

        // 新建会话按钮
        binding.btnNewChat?.setOnClickListener {
            conversationCoordinator.showNewChatDialog()
        }

        // 搜索按钮
        binding.btnSearch?.setOnClickListener {
            chatViewModel.toggleSearchMode()
        }

        // 滚动到底部按钮
        binding.btnScrollBottom.setOnClickListener {
            binding.rvMessages.smoothScrollToPosition(adapter.itemCount - 1)
        }

        // RecyclerView 滚动监听
        binding.rvMessages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
                val lastVisible = layoutManager.findLastVisibleItemPosition()
                val totalItems = adapter.itemCount
                binding.btnScrollBottom.visibility = if (lastVisible < totalItems - SCROLL_BOTTOM_THRESHOLD) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
            }
        })

        // 点击聊天区域收起键盘
        binding.rvMessages.setOnTouchListener { _, _ ->
            chatViewModel.hideKeyboard()
            false
        }

        // 输入框文本变化
        binding.etInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                chatViewModel.updateSendButton(!s.isNullOrBlank())
            }
        })

        // 输入框发送动作
        binding.etInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                chatViewModel.sendMessage()
                true
            } else false
        }

        // 角色名称点击 → 会话列表
        binding.tvTitle.setOnClickListener {
            conversationCoordinator.showSessionListDialog()
        }
        binding.tvTitle.setOnLongClickListener {
            openCharacterSelect()
            true
        }
        binding.ivAvatar.setOnClickListener { openCharacterSelect() }

        // 左滑手势 → 角色选择
        gestureDetector = GestureDetector(this, object : GestureDetector.OnGestureListener {
            override fun onDown(e: MotionEvent): Boolean = false
            override fun onShowPress(e: MotionEvent) {}
            override fun onSingleTapUp(e: MotionEvent): Boolean = false
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean = false
            override fun onLongPress(e: MotionEvent) {}
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                val diffX = e2.x - e1.x
                if (diffX > FLING_THRESHOLD && Math.abs(velocityX) > Math.abs(velocityY) * FLING_RATIO && velocityX > 0) {
                    openCharacterSelect()
                    return true
                }
                return false
            }
        })
    }

    // ======================== Python 初始化 ========================

    /**
     * 获取 Python 模块引用
     * @return chat_bridge 模块的 PyObject
     */
    private fun initPythonModule(): com.chaquo.python.PyObject {
        return com.chaquo.python.Python.getInstance().getModule("chat_bridge")
    }

    /**
     * Python 异步初始化编排入口
     */
    private fun initializePythonAsync() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    binding.tvStatus.text = getString(R.string.status_initializing)
                }
                val module = initPythonModule()
                if (!initChatBridge(module)) return@launch
                initMemorySystem(module)
                val character = loadCharacterCard(module)
                restoreWorldBooks(module)
                completeInit(module, character)
            } catch (e: Exception) {
                Log.e("MainActivity", "Python 初始化失败", e)
                withContext(Dispatchers.Main) {
                    binding.tvStatus.text = getString(R.string.error_init_failed, e.message)
                }
            }
        }
    }

    /**
     * 注入 API Key 到 Python 模块
     * @return true 成功，false 失败（API Key 为空）
     */
    private suspend fun injectApiKey(module: com.chaquo.python.PyObject): Boolean {
        val apiKey = AppConfig.getApiKey(this)
        if (apiKey.isNullOrBlank()) {
            withContext(Dispatchers.Main) {
                binding.tvStatus.text = getString(R.string.error_init_api_key)
            }
            return false
        }
        module.callAttr("set_api_key", apiKey)
        return true
    }

    /**
     * 初始化聊天引擎参数
     */
    private fun initChatEngine(module: com.chaquo.python.PyObject) {
        val ctxSize = AppConfig.getContextSize(this)
        val temp = AppConfig.getTemperature(this).toDouble()
        val maxTk = AppConfig.getMaxTokens(this)
        val dialogues = AppConfig.getExampleDialogues(this)
        val model = AppConfig.getModel(this).let {
            if (it.isBlank()) "" else it
        }
        module.callAttr("init", ctxSize, temp, maxTk, dialogues, model)
    }

    /**
     * 初始化聊天桥接：注入 API Key 并配置聊天引擎
     * @return true 成功，false 失败
     */
    private suspend fun initChatBridge(module: com.chaquo.python.PyObject): Boolean {
        if (!injectApiKey(module)) return false
        initChatEngine(module)
        return true
    }

    /**
     * 初始化记忆系统
     */
    private fun initMemorySystem(module: com.chaquo.python.PyObject) {
        module.callAttr("init_memory", filesDir.absolutePath)
    }

    /**
     * 加载角色卡到 Python 模块
     * @return 当前角色对象
     */
    private fun loadCharacterCard(module: com.chaquo.python.PyObject): CharacterData {
        val character = CharacterStorage.getCurrent(this)
        val charJson = JSONObject().apply {
            put("name", character.name)
            put("personality", character.personality)
            put("speaking_style", character.speakingStyle)
            put("backstory", character.backstory)
            put("greeting", character.greeting)
            put("emotional_tendency", character.emotionalTendency)
            put("self_identity", character.selfIdentity)
        }.toString()
        module.callAttr("set_character_card", charJson)
        return character
    }

    /**
     * 恢复已启用的世界书
     */
    private fun restoreWorldBooks(module: com.chaquo.python.PyObject) {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val savedBooks = prefs.getString("enabled_world_books", "") ?: ""
        if (savedBooks.isNotBlank()) {
            savedBooks.split(",").filter { it.isNotBlank() }.forEach { name ->
                module.callAttr("enable_world_book", name.trim())
            }
        }
    }

    /**
     * 完成初始化：设置 Python 模块、更新 UI、初始化语音、通知、加载会话
     */
    private suspend fun completeInit(module: com.chaquo.python.PyObject, character: CharacterData) {
        withContext(Dispatchers.Main) {
            pythonModule = module
            chatViewModel.pythonModule = module
            binding.tvStatus.text = ""
            binding.tvTitle.text = character.name
            voiceController.init()
            // 初始化录音覆盖层
            recordingOverlay = RecordingOverlayView(this@MainActivity)
            (binding.root as android.view.ViewGroup).addView(recordingOverlay)
            NotificationHelper.createChannel(this@MainActivity)
            ProactiveService.schedule(this@MainActivity)
            conversationCoordinator.loadConversation()
        }
    }

    // ======================== 角色选择 ========================

    private fun openCharacterSelect() {
        val intent = Intent(this, CharacterSelectActivity::class.java)
        characterSelectLauncher.launch(intent, ActivityOptionsCompat.makeCustomAnimation(
            this, R.anim.slide_in_left, R.anim.slide_out_right
        ))
    }

    private fun syncCharacterToPython() {
        if (!::pythonModule.isInitialized) return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val character = CharacterStorage.getCurrent(this@MainActivity)
                val charJson = JSONObject().apply {
                    put("name", character.name)
                    put("personality", character.personality)
                    put("speaking_style", character.speakingStyle)
                    put("backstory", character.backstory)
                    put("greeting", character.greeting)
                    put("emotional_tendency", character.emotionalTendency)
                    put("self_identity", character.selfIdentity)
                }.toString()
                pythonModule.callAttr("set_character_card", charJson)
                pythonModule.callAttr("reload_card")
                withContext(Dispatchers.Main) {
                    binding.tvTitle.text = character.name
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "角色卡同步失败", e)
            }
        }
    }

    /**
     * 将已加载的对话历史同步到 Python 引擎的 ContextManager。
     * 确保 APP 重启后 AI 能看到之前的对话。
     */
    private fun syncHistoryToPython(messages: List<Message>) {
        if (!::pythonModule.isInitialized || messages.isEmpty()) return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val jsonArray = JSONArray()
                for (msg in messages) {
                    val obj = JSONObject()
                    obj.put("role", if (msg.isUser) "user" else "assistant")
                    obj.put("content", msg.content)
                    jsonArray.put(obj)
                }
                pythonModule.callAttr("restore_history", jsonArray.toString())
                Log.d("MainActivity", "对话历史已同步到 Python 引擎，共 ${messages.size} 条")
            } catch (e: Exception) {
                Log.w("MainActivity", "同步对话历史到 Python 失败: ${e.message}")
            }
        }
    }

    // ======================== 插件面板 ========================

    /**
     * 显示插件面板 BottomSheet
     * 包含：图片、语音、插件管理、联网搜索开关
     */
    private fun showPluginPanel() {
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_plugins, null)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(sheetView)

        // 1. 图片：触发图片选择
        sheetView.findViewById<View>(R.id.pluginImage)?.setOnClickListener {
            dialog.dismiss()
            imagePickerLauncher.launch("image/*")
        }

        // 2. 语音：触发语音识别（短按识别）
        sheetView.findViewById<View>(R.id.pluginVoice)?.setOnClickListener {
            dialog.dismiss()
            if (!voiceController.speechManager.isRecording) {
                if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    voiceController.startVoiceRecognition()
                } else {
                    voicePermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                }
            }
        }

        // 3. 插件管理：跳转 PluginManageActivity
        sheetView.findViewById<View>(R.id.pluginExtension)?.setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, PluginManageActivity::class.java))
        }

        // 4. 联网搜索：切换开关
        val webIcon = sheetView.findViewById<ImageView>(R.id.pluginWebIcon)
        val webStatus = sheetView.findViewById<TextView>(R.id.pluginWebStatus)
        val isWebEnabled = AppConfig.getWebSearchEnabled(this)
        updateWebSearchUI(webIcon, webStatus, isWebEnabled)

        sheetView.findViewById<View>(R.id.pluginWeb)?.setOnClickListener {
            val current = AppConfig.getWebSearchEnabled(this)
            val newState = !current
            AppConfig.setWebSearchEnabled(this, newState)
            updateWebSearchUI(webIcon, webStatus, newState)
            Toast.makeText(
                this,
                if (newState) "联网搜索已开启" else "联网搜索已关闭",
                Toast.LENGTH_SHORT
            ).show()
        }

        dialog.show()
    }

    private fun updateWebSearchUI(icon: ImageView?, status: TextView?, enabled: Boolean) {
        icon?.alpha = if (enabled) 1.0f else 0.5f
        status?.text = if (enabled) getString(R.string.plugin_web_search_on)
                       else getString(R.string.plugin_web_search_off)
        status?.setTextColor(
            if (enabled) Color.parseColor("#4CAF50")
            else resources.getColor(R.color.text_tertiary, theme)
        )
    }

    // ======================== 屏幕适配 ========================

    private fun applyInsets(root: android.view.ViewGroup) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(v.paddingLeft, systemBars.top, v.paddingRight, 0)
            val contentLayout = (v as? android.view.ViewGroup)?.getChildAt(1) as? android.view.ViewGroup
            val bottomInset = maxOf(systemBars.bottom, ime.bottom)
            (contentLayout?.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let { lp ->
                if (lp.bottomMargin != bottomInset) {
                    lp.bottomMargin = bottomInset
                    contentLayout?.requestLayout()
                }
            }
            insets
        }
    }

    // ======================== 录音覆盖层 UI ========================
    // 已抽取为 RecordingOverlayView，通过 VoiceController 回调控制
}
package com.aicompanion.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.drawerlayout.widget.DrawerLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityOptionsCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aicompanion.app.databinding.ActivityMainBinding
import com.aicompanion.app.module.ModuleRegistry
import com.aicompanion.app.module.tts.TtsModule
import com.aicompanion.app.module.tts.TtsModuleImpl
import com.aicompanion.app.views.RecordingOverlayView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
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
            Toast.makeText(this, R.string.toast_image_selected, Toast.LENGTH_SHORT).show()
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
        adapter = ChatAdapter(
            onMessageLongClick = { message, position ->
                // 长按消息添加触觉反馈
                binding.rvMessages.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                chatViewModel.showMessageContextMenu(message, position)
            },
            onVoiceClick = { filePath, play ->
                if (play) voiceController.playVoiceMessage(filePath)
                else voiceController.pauseVoiceMessage()
            },
            onMessagesTrimmed = {
                chatViewModel.onMessagesTrimmed()
            },
            onDataChanged = {
                updateChatEmptyState()
            }
        )
        binding.rvMessages.adapter = adapter
        binding.rvMessages.layoutManager = LinearLayoutManager(this)
        binding.rvMessages.itemAnimator = MessageItemAnimator()

        // 创建 ChatViewModel（通过 ViewModelProvider + 自定义 Factory）
        chatViewModel = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                return ChatViewModel(application, adapter) as T
            }
        }).get(ChatViewModel::class.java)

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

        // 观察 ViewModel LiveData
        observeViewModel()

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
        // v2.0: 清理 TtsModuleImpl 的 SpeechManager 引用
        try {
            val ttsModule = ModuleRegistry.getOrNull<TtsModule>()
            if (ttsModule is TtsModuleImpl) {
                ttsModule.clearSpeechManager()
            }
        } catch (_: Exception) {}
        // chatViewModel 清理由 onCleared() 自动完成，无需手动调用
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
                } catch (e: Exception) {
                    Log.w("MainActivity", "缓存失效失败（不影响主流程）: ${e.message}")
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

    // ======================== ViewModel 观察 ========================

    private fun observeViewModel() {
        // 输入框启用状态
        chatViewModel.inputEnabled.observe(this) { enabled ->
            binding.etInput.isEnabled = enabled
        }

        // 发送按钮状态
        chatViewModel.sendButtonState.observe(this) { state ->
            binding.btnSend.isEnabled = state.enabled
            binding.btnSend.setBackgroundResource(state.bgRes)
            if (state.text.isNotEmpty()) {
                binding.btnSend.text = state.text
            }
            if (state.triggerAnimation) {
                binding.btnSend.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                if (state.enabled) {
                    binding.btnSend.scaleX = 0.8f
                    binding.btnSend.scaleY = 0.8f
                    binding.btnSend.animate()
                        .scaleX(1f).scaleY(1f)
                        .setDuration(200)
                        .withEndAction { binding.btnSend.setLayerType(View.LAYER_TYPE_NONE, null) }
                        .start()
                } else {
                    binding.btnSend.animate()
                        .scaleX(0.8f).scaleY(0.8f)
                        .setDuration(150)
                        .withEndAction {
                            binding.btnSend.scaleX = 1f
                            binding.btnSend.scaleY = 1f
                            binding.btnSend.setLayerType(View.LAYER_TYPE_NONE, null)
                        }
                        .start()
                }
            }
        }

        // 搜索栏可见性
        chatViewModel.searchBarVisible.observe(this) { visible ->
            binding.layoutSearch?.visibility = if (visible) View.VISIBLE else View.GONE
        }

        // 搜索结果计数
        chatViewModel.searchResultCountText.observe(this) { text ->
            binding.tvSearchResultCount?.text = text
        }

        chatViewModel.searchResultCountVisible.observe(this) { visible ->
            binding.tvSearchResultCount?.visibility = if (visible) View.VISIBLE else View.GONE
        }

        // 归档提示
        chatViewModel.archiveHintVisible.observe(this) { visible ->
            binding.tvArchiveHint.visibility = if (visible) View.VISIBLE else View.GONE
        }

        // 滚动到指定位置
        chatViewModel.scrollToPosition.observe(this) { event ->
            if (event.smooth) {
                binding.rvMessages.smoothScrollToPosition(event.position)
            } else {
                binding.rvMessages.scrollToPosition(event.position)
            }
        }

        // Toast 事件
        chatViewModel.toastEvent.observe(this) { event ->
            event.getContentIfNotHandled()?.let { message ->
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        }

        // Snackbar 事件
        chatViewModel.snackbarEvent.observe(this) { event ->
            event.getContentIfNotHandled()?.let { data ->
                val snackbar = Snackbar.make(binding.root, data.message, Snackbar.LENGTH_LONG)
                if (data.showRetry) {
                    snackbar.setAction(R.string.action_retry) { chatViewModel.retryLastMessage() }
                }
                snackbar.show()
            }
        }

        // 输入框文本操作（编辑模式填充）
        chatViewModel.inputTextAction.observe(this) { event ->
            event.getContentIfNotHandled()?.let { action ->
                when (action) {
                    is InputTextAction.Set -> {
                        binding.etInput.setText(action.text)
                        binding.etInput.setSelection(action.selection)
                    }
                }
            }
        }

        // 清空输入框
        chatViewModel.clearInput.observe(this) { event ->
            event.getContentIfNotHandled()?.let {
                binding.etInput.text.clear()
            }
        }

        // 请求输入框焦点
        chatViewModel.requestInputFocus.observe(this) { event ->
            event.getContentIfNotHandled()?.let {
                binding.etInput.requestFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(binding.etInput, InputMethodManager.SHOW_IMPLICIT)
            }
        }

        // 请求搜索框焦点
        chatViewModel.requestSearchFocus.observe(this) { event ->
            event.getContentIfNotHandled()?.let {
                binding.etSearch?.requestFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                binding.etSearch?.let { view -> imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT) }
            }
        }

        // 隐藏键盘
        chatViewModel.hideKeyboard.observe(this) { event ->
            event.getContentIfNotHandled()?.let {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                val focusedView = currentFocus
                if (focusedView != null) {
                    imm?.hideSoftInputFromWindow(focusedView.windowToken, 0)
                } else {
                    imm?.hideSoftInputFromWindow(binding.etInput.windowToken, 0)
                }
            }
        }

        // 清空搜索框
        chatViewModel.clearSearchText.observe(this) { event ->
            event.getContentIfNotHandled()?.let {
                binding.etSearch?.setText("")
            }
        }

        // 消息上下文菜单
        chatViewModel.contextMenuEvent.observe(this) { event ->
            event.getContentIfNotHandled()?.let { (message, position) ->
                val items = mutableListOf(getString(R.string.action_copy))
                if (message.isUser) {
                    items.add(getString(R.string.action_edit))
                    items.add(getString(R.string.btn_delete))
                }
                MaterialAlertDialogBuilder(this)
                    .setItems(items.toTypedArray()) { _, which ->
                        when (which) {
                            0 -> chatViewModel.copyMessage(message.content)
                            1 -> {
                                if (message.isUser) {
                                    chatViewModel.startEditingMessage(message, position)
                                } else {
                                    chatViewModel.deleteMessage(position)
                                }
                            }
                            2 -> chatViewModel.deleteMessage(position)
                        }
                    }
                    .show()
            }
        }
    }

    // ======================== 回调设置 ========================

    private fun setupCallbacks() {
        // ChatViewModel 回调
        chatViewModel.callback = object : ChatViewModel.ChatCallback {
            override fun onConversationNeedSave() {
                conversationCoordinator.saveConversation()
            }

            override fun onStreamComplete(fullContent: String) {
                // 如果开启了自动朗读，逐句模式已在 onStreamSentence 中处理，
                // 跳过全文朗读避免与逐句队列冲突
                if (!AppConfig.getAutoReadAloud(this@MainActivity)) {
                    voiceController.speakAIContentIfNeeded(fullContent)
                }
            }

            override fun onStreamSentence(sentence: String) {
                voiceController.speakSentenceStreaming(sentence)
            }

            override fun onNewMessageSent() {
                voiceController.resetVoiceInputFlag()
                voiceController.stopTtsAndClear()
            }

            override fun onShowExportFormatDialog() {
                val formats = arrayOf(
                    getString(R.string.export_format_json),
                    getString(R.string.export_format_txt)
                )
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(getString(R.string.label_choose_export_format))
                    .setItems(formats) { _, which ->
                        val format = if (which == 0) "json" else "txt"
                        chatViewModel.exportConversation(format)
                    }
                    .setNegativeButton(getString(R.string.btn_cancel), null)
                    .show()
            }

            override fun onShareFile(uri: Uri, format: String) {
                try {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = if (format == "json") "application/json" else "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(
                        Intent.createChooser(shareIntent, getString(R.string.chooser_share_conversation))
                    )
                } catch (e: Exception) {
                    Log.w("MainActivity", "分享失败: ${e.message}")
                }
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
                chatViewModel.sendMessage(text)
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
        binding.btnSend.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            chatViewModel.sendMessage(binding.etInput.text.toString())
        }

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

        // 搜索按钮：点击触发搜索模式
        binding.btnSearch?.setOnClickListener {
            chatViewModel.toggleSearchMode()
        }

        // 设置按钮：点击直接进入设置页
        binding.btnSettings?.setOnClickListener {
            ActivityTransitionHelper.startWithSlideIn(this, Intent(this, SettingsActivity::class.java))
        }

        // 新建会话按钮
        binding.btnNewChat?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            conversationCoordinator.showNewChatDialog()
        }

        // 抽屉按钮
        binding.btnDrawerNewChat.setOnClickListener {
            binding.drawerLayout.closeDrawers()
            conversationCoordinator.showNewChatDialog()
        }
        binding.btnDrawerExport.setOnClickListener {
            binding.drawerLayout.closeDrawers()
            chatViewModel.showExportDialog()
        }
        binding.btnDrawerSearch.setOnClickListener {
            binding.drawerLayout.closeDrawers()
            chatViewModel.toggleSearchMode()
        }
        binding.btnDrawerSettings?.setOnClickListener {
            binding.drawerLayout.closeDrawers()
            ActivityTransitionHelper.startWithSlideIn(this, Intent(this, SettingsActivity::class.java))
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
                // 更新 isAtBottom 状态（供 ChatViewModel 流式输出时判断是否自动滚动）
                chatViewModel.isAtBottom = !recyclerView.canScrollVertically(1)
            }
        })

        // 点击聊天区域收起键盘
        binding.rvMessages.setOnTouchListener { _, _ ->
            chatViewModel.requestHideKeyboard()
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
                chatViewModel.sendMessage(binding.etInput.text.toString())
                true
            } else false
        }

        // 角色名称/头像点击 → 打开会话抽屉
        binding.tvTitle.setOnClickListener {
            openSessionDrawer()
        }
        binding.tvTitle.setOnLongClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            openCharacterSelect()
            true
        }
        binding.ivAvatar.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            openCharacterSelect()
        }

        // 搜索框文本变化（从 ChatViewModel 移出的监听器）
        binding.etSearch?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                chatViewModel.onSearchTextChanged(s?.toString() ?: "")
            }
        })

        // 搜索取消按钮
        binding.btnCancel?.setOnClickListener {
            if (binding.etSearch?.text?.isNotEmpty() == true) {
                binding.etSearch?.setText("")  // 触发 TextWatcher → 恢复全部消息
            } else {
                chatViewModel.onSearchCancelClicked()
            }
        }

        // 左滑手势 → 打开会话抽屉
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
                    openSessionDrawer()
                    return true
                }
                return false
            }
        })

        // 初始化会话抽屉列表
        setupSessionDrawer()
    }

    /** 打开会话抽屉 */
    private fun openSessionDrawer() {
        binding.drawerLayout.openDrawer(binding.drawerSessionList)
        refreshSessionList()
    }

    /** 初始化会话列表抽屉 */
    private fun setupSessionDrawer() {
        binding.rvSessionList.layoutManager = LinearLayoutManager(this)
        refreshSessionList()
    }

    /** 刷新会话抽屉列表 */
    private fun refreshSessionList() {
        val sessions = ConversationSessionManager.getSessions()
        val currentId = ConversationSessionManager.getCurrentSessionId()
        val adapter = SessionDrawerAdapter(
            sessions,
            currentId,
            onSelect = { session ->
                binding.drawerLayout.closeDrawers()
                conversationCoordinator.switchToSession(session.id)
            },
            onDelete = { session ->
                conversationCoordinator.deleteSession(session.id)
                refreshSessionList()
            }
        )
        binding.rvSessionList.adapter = adapter
    }

    // ======================== Python 初始化 ========================

    /**
     * 获取 Python 模块引用
     * @return chat_bridge 模块的 PyObject
     */
    private fun initPythonModule(): com.chaquo.python.PyObject? {
        return com.chaquo.python.Python.getInstance().getModule("chat_bridge")
    }

    /**
     * Python 异步初始化编排入口。
     * 在启动画面（splashOverlay）中显示逐步初始化进度，
     * 替代原有的单一"正在初始化..."文字，让用户感知加载进度。
     */
    private fun initializePythonAsync() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 步骤1：加载 Python 对话引擎模块
                withContext(Dispatchers.Main) {
                    binding.tvSplashStatus.text = getString(R.string.splash_loading_engine)
                }
                val module = initPythonModule()
                if (module == null) {
                    Log.e("MainActivity", "无法获取 Python chat_bridge 模块")
                    withContext(Dispatchers.Main) {
                        dismissSplash()
                        Toast.makeText(this@MainActivity, R.string.error_init_python_module, Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                // 步骤2：注入 API Key
                withContext(Dispatchers.Main) {
                    binding.tvSplashStatus.text = getString(R.string.splash_injecting_key)
                }
                if (!injectApiKey(module)) return@launch

                // 步骤3：初始化聊天引擎参数
                withContext(Dispatchers.Main) {
                    binding.tvSplashStatus.text = getString(R.string.splash_init_engine)
                }
                initChatEngine(module)

                // 步骤4：初始化记忆系统
                withContext(Dispatchers.Main) {
                    binding.tvSplashStatus.text = getString(R.string.splash_loading_memory)
                }
                initMemorySystem(module)

                // 步骤5：加载角色卡
                withContext(Dispatchers.Main) {
                    binding.tvSplashStatus.text = getString(R.string.splash_loading_character)
                }
                val character = loadCharacterCard(module)

                // 步骤6：恢复世界书
                withContext(Dispatchers.Main) {
                    binding.tvSplashStatus.text = getString(R.string.splash_loading_worldbook)
                }
                restoreWorldBooks(module)

                // 完成初始化
                completeInit(module, character)
            } catch (e: Exception) {
                Log.e("MainActivity", "Python 初始化失败", e)
                withContext(Dispatchers.Main) {
                    dismissSplash()
                    binding.tvSplashStatus.text = getString(R.string.error_init_failed, e.message ?: "")
                }
            }
        }
    }

    /**
     * 注入 API Key 到 Python 模块
     * @return true 成功，false 失败（API Key 为空）
     */
    private suspend fun injectApiKey(module: com.chaquo.python.PyObject): Boolean {
        var apiKey = AppConfig.getApiKey(this)
        if (apiKey.isNullOrBlank()) {
            withContext(Dispatchers.Main) {
                binding.tvStatus.text = getString(R.string.error_init_api_key)
                binding.tvStatus.setTextColor(getColor(R.color.accent_red))
                dismissSplash()
                showApiKeyMissingDialog()
            }
            return false
        }
        module.callAttr("set_api_key", apiKey)
        // 传递后立即清空 Kotlin 端变量，减少明文在内存中的停留时间
        apiKey = ""
        return true
    }

    /**
     * 初始化聊天引擎参数
     */
    private fun initChatEngine(module: com.chaquo.python.PyObject) {
        val ctxSize = AppConfig.getContextSize(this)
        val temp = AppConfig.getTemperature(this).toDouble()
        val topP = AppConfig.getTopP(this).toDouble()
        val freqPenalty = AppConfig.getFrequencyPenalty(this).toDouble()
        val presPenalty = AppConfig.getPresencePenalty(this).toDouble()
        val maxTk = AppConfig.getMaxTokens(this)
        val dialogues = AppConfig.getExampleDialogues(this)
        val model = AppConfig.getModel(this).let {
            if (it.isBlank()) "" else it
        }
        module.callAttr("init", ctxSize, temp, topP, freqPenalty, presPenalty, maxTk, dialogues, model)
    }

    /**
     * 初始化记忆系统
     */
    private fun initMemorySystem(module: com.chaquo.python.PyObject) {
        try {
            if (!filesDir.exists()) filesDir.mkdirs()
            module.callAttr("init_memory", filesDir.absolutePath)
        } catch (e: Exception) {
            Log.e("MainActivity", "初始化记忆系统失败: ${e.message}")
        }
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
            put("core_traits", character.coreTraits)
            put("taboo_topics", character.tabooTopics)
            put("role_anchor", character.roleAnchor)
            put("world_book_id", character.worldBookId)
        }.toString()
        module.callAttr("set_character_card", charJson)
        return character
    }

    /**
     * 恢复已启用的世界书，并自动启用角色绑定的世界书
     */
    private fun restoreWorldBooks(module: com.chaquo.python.PyObject) {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val savedBooks = prefs.getString("enabled_world_books", "") ?: ""
        val enabledNames = mutableSetOf<String>()
        if (savedBooks.isNotBlank()) {
            savedBooks.split(",").filter { it.isNotBlank() }.forEach { name ->
                try {
                    module.callAttr("enable_world_book", name.trim())
                    enabledNames.add(name.trim())
                } catch (e: Exception) {
                    Log.w("MainActivity", "恢复世界书失败: $name", e)
                }
            }
        }

        // 自动启用角色绑定的世界书（如果尚未启用）
        val character = CharacterStorage.getCurrent(this)
        if (character.worldBookId.isNotBlank() && character.worldBookId !in enabledNames) {
            try {
                module.callAttr("enable_world_book", character.worldBookId)
                enabledNames.add(character.worldBookId)
                Log.d("MainActivity", "已自动启用角色绑定的世界书: ${character.worldBookId}")
            } catch (e: Exception) {
                Log.w("MainActivity", "自动启用世界书失败: ${character.worldBookId}", e)
            }
        }

        // 持久化当前启用的世界书列表
        prefs.edit().putString("enabled_world_books", enabledNames.joinToString(",")).apply()
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
            // v2.0: 将 SpeechManager 注入到 TtsModuleImpl（模块化架构）
            val ttsModule = ModuleRegistry.get<TtsModule>()
            if (ttsModule is TtsModuleImpl) {
                ttsModule.setSpeechManager(voiceController.speechManager)
                Log.d("MainActivity", "SpeechManager 已注入到 TtsModuleImpl")
            }
            // 初始化录音覆盖层
            recordingOverlay = RecordingOverlayView(this@MainActivity)
            (binding.root as android.view.ViewGroup).addView(recordingOverlay)
            NotificationHelper.createChannel(this@MainActivity)
            ProactiveService.schedule(this@MainActivity)
            conversationCoordinator.loadConversation()
            // 冷启动过渡动画：淡出启动画面
            dismissSplash()
        }
    }

    /**
     * 冷启动过渡动画：淡出启动画面
     */
    private fun dismissSplash() {
        binding.splashOverlay?.animate()
            ?.alpha(0f)
            ?.setDuration(400)
            ?.setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    binding.splashOverlay?.visibility = View.GONE
                    updateChatEmptyState()
                }
            })
            ?.start()
    }

    /**
     * 显示 API Key 未配置的引导对话框，避免用户被卡在启动画面。
     */
    private fun showApiKeyMissingDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_api_key_missing_title)
            .setMessage(R.string.dialog_api_key_missing_message)
            .setPositiveButton(R.string.dialog_api_key_missing_goto_settings) { _, _ ->
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            .setNegativeButton(R.string.dialog_api_key_missing_later, null)
            .setCancelable(false)
            .show()
    }

    /**
     * 更新聊天空状态显示
     */
    private fun updateChatEmptyState() {
        val isEmpty = adapter.itemCount == 0
        if (isEmpty) {
            binding.layoutChatEmpty?.visibility = View.VISIBLE
            binding.rvMessages.visibility = View.GONE
            // 设置空状态文案
            val ivEmpty = binding.layoutChatEmpty?.findViewById<ImageView>(R.id.ivEmptyIcon)
            val tvTitle = binding.layoutChatEmpty?.findViewById<TextView>(R.id.tvEmptyTitle)
            val tvDesc = binding.layoutChatEmpty?.findViewById<TextView>(R.id.tvEmptyDesc)
            ivEmpty?.setImageResource(R.drawable.ic_feather)
            ivEmpty?.setColorFilter(resources.getColor(R.color.sakura_sky, theme))
            tvTitle?.text = getString(R.string.empty_chat_title)
            tvDesc?.text = getString(R.string.empty_chat_desc)
        } else {
            binding.layoutChatEmpty?.visibility = View.GONE
            binding.rvMessages.visibility = View.VISIBLE
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
                    put("world_book_id", character.worldBookId)
                }.toString()
                pythonModule.callAttr("set_character_card", charJson)
                pythonModule.callAttr("reload_card")

                // v2.0: 通过 CharacterModule 同步角色卡（模块化架构）
                // 后续可逐步迁移到 ModuleRegistry.get<CharacterModule>().syncToPython()

                // 自动启用角色绑定的世界书
                if (character.worldBookId.isNotBlank()) {
                    try {
                        pythonModule.callAttr("enable_world_book", character.worldBookId)
                        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
                        val savedBooks = prefs.getString("enabled_world_books", "") ?: ""
                        val enabledNames = savedBooks.split(",").filter { it.isNotBlank() }.toMutableSet()
                        enabledNames.add(character.worldBookId)
                        prefs.edit().putString("enabled_world_books", enabledNames.joinToString(",")).apply()
                    } catch (e: Exception) {
                        Log.w("MainActivity", "自动启用世界书失败: ${character.worldBookId}", e)
                    }
                }

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
            ActivityTransitionHelper.startWithSlideIn(this, Intent(this, PluginManageActivity::class.java))
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
                if (newState) getString(R.string.toast_online_search_on) else getString(R.string.toast_online_search_off),
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
            if (enabled) resources.getColor(R.color.accent_green, theme)
            else resources.getColor(R.color.text_tertiary, theme)
        )
    }

    // ======================== 屏幕适配 ========================

    private fun applyInsets(root: android.view.ViewGroup) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(v.paddingLeft, systemBars.top, v.paddingRight, 0)
            val contentLayout = v.findViewById<android.view.ViewGroup?>(R.id.layoutContent)
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
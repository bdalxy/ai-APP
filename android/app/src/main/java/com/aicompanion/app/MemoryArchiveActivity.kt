package com.aicompanion.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.aicompanion.app.databinding.ActivityMemoryArchiveBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.random.Random

/**
 * 记忆档案馆页面。
 *
 * 核心功能：
 * 1. 2 列瀑布流展示记忆卡片（日期 + 诗意摘要 + 情感标签）
 * 2. 色温渐变：向上滑动浏览更早记忆时，颜色从淡樱粉逐渐过渡到冷色调
 * 3. 长按破碎删除：长按1.2秒触发裂纹效果，确认后卡片碎片飘走
 *
 * 数据来源：通过 Chaquopy 调用 Python chat_bridge.list_memories() 获取记忆列表。
 *
 * 实现参考：色温渐变算法参考设计文档，破碎动画使用 ViewPropertyAnimator。
 */
class MemoryArchiveActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MemoryArchive"
        private const val PAGE_SIZE = 30
        /** 长按触发破碎动画的延迟（毫秒） */
        private const val LONG_PRESS_DURATION_MS = 1200L
    }

    // ── UI 绑定 ──
    private lateinit var binding: ActivityMemoryArchiveBinding
    private lateinit var adapter: MemoryCardAdapter

    // ── 数据状态 ──
    private val cardDataList = mutableListOf<MemoryCardData>()
    private var isLoading = false
    private var hasMore = true
    private var currentPage = 1
    private var loadedCount = 0

    // ── 色温渐变 ──
    private val warmColor = Color.parseColor("#FDF0F0")  // 淡樱粉
    private val coolColor = Color.parseColor("#D4E8F0")  // 淡天蓝
    private val grayColor = Color.parseColor("#E8E8E8")  // 浅灰

    // ── 破碎删除状态 ──
    private var pendingDeleteCard: MemoryCardData? = null
    private var pendingDeletePosition: Int = -1
    private var pendingDeleteView: View? = null
    private val longPressHandler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null

    // ── 音效 ──
    private var soundPool: SoundPool? = null
    private var shatterSoundId: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMemoryArchiveBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 适配刘海屏/状态栏
        ViewUtils.setupEdgeToEdge(this)
        ViewUtils.applyInsets(binding.archiveRoot)

        // 初始化音效
        initSoundPool()

        // 设置 RecyclerView
        setupRecyclerView()

        // 设置监听器
        setupListeners()

        // 初始加载
        loadMemories(page = 1)
    }

    override fun onDestroy() {
        super.onDestroy()
        longPressHandler.removeCallbacksAndMessages(null)
        soundPool?.release()
    }

    // ── 音效初始化 ──

    /**
     * 初始化 SoundPool 用于播放破碎音效。
     * 使用系统内置的点击音效作为破碎声。
     */
    private fun initSoundPool() {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        soundPool = SoundPool.Builder()
            .setMaxStreams(1)
            .setAudioAttributes(audioAttributes)
            .build()

        // 加载系统点击音效作为破碎声（使用 AudioEffect 的 KEY_CLICK）
        try {
            shatterSoundId = soundPool?.load(
                this,
                android.media.AudioManager.FX_KEY_CLICK,
                0
            ) ?: 0
        } catch (e: Exception) {
            Log.w(TAG, "音效加载失败: ${e.message}")
        }
    }

    /** 播放破碎音效（"叮"声） */
    private fun playShatterSound() {
        try {
            soundPool?.play(shatterSoundId, 0.3f, 0.3f, 1, 0, 1.0f)
        } catch (e: Exception) {
            Log.w(TAG, "播放音效失败: ${e.message}")
        }
    }

    // ── RecyclerView 设置 ──

    private fun setupRecyclerView() {
        // 2 列瀑布流
        val layoutManager = StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)
        // 防止 item 跳动
        layoutManager.gapStrategy = StaggeredGridLayoutManager.GAP_HANDLING_NONE

        adapter = MemoryCardAdapter { card, view, position ->
            onCardLongPressed(card, view, position)
        }

        binding.rvArchive.adapter = adapter
        binding.rvArchive.layoutManager = layoutManager
        // 预加载优化
        binding.rvArchive.setItemViewCacheSize(20)

        // ── 滚动监听：色温渐变 + 触底加载更多 ──
        binding.rvArchive.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)

                // 色温渐变：根据滚动位置计算进度
                updateColorTemperature(recyclerView)

                // 触底加载更多
                if (dy > 0 && !isLoading && hasMore) {
                    val layoutMgr = recyclerView.layoutManager as? StaggeredGridLayoutManager
                        ?: return
                    val lastVisiblePositions = IntArray(layoutMgr.spanCount)
                    layoutMgr.findLastVisibleItemPositions(lastVisiblePositions)
                    val lastVisiblePos = lastVisiblePositions.maxOrNull() ?: 0
                    val totalItems = adapter.itemCount

                    if (totalItems > 0 && lastVisiblePos >= totalItems - 5) {
                        loadNextPage()
                    }
                }
            }
        })
    }

    // ── 色温渐变 ──

    /**
     * 根据 RecyclerView 的滚动位置更新背景色温。
     *
     * 策略：
     * - 计算滚动进度（scrollY / maxScrollY）
     * - 进度 0.0 → 淡樱粉（最近记忆，温暖）
     * - 进度 0.5 → 淡天蓝（中间记忆，过渡）
     * - 进度 1.0 → 浅灰（最早记忆，褪色）
     */
    private fun updateColorTemperature(recyclerView: RecyclerView) {
        val layoutMgr = recyclerView.layoutManager as? StaggeredGridLayoutManager ?: return
        val firstVisiblePositions = IntArray(layoutMgr.spanCount)
        layoutMgr.findFirstVisibleItemPositions(firstVisiblePositions)
        val firstVisiblePos = firstVisiblePositions.minOrNull() ?: 0

        val totalItems = adapter.itemCount
        if (totalItems <= 0) return

        // 计算进度：根据第一个可见 item 在总列表中的位置
        val progress = (firstVisiblePos.toFloat() / totalItems.toFloat()).coerceIn(0f, 1f)

        // 更新适配器中的进度（用于卡片背景色温）
        adapter.scrollProgress = progress

        // 更新页面背景色温
        val bgColor = interpolateColor(progress, warmColor, coolColor, grayColor)
        binding.archiveRoot.setBackgroundColor(bgColor)

        // 通知适配器刷新卡片的色温（可以通过 notifyItemRangeChanged 或 invalidate）
        // 为了性能，每滚动 3 个 item 才刷新一次
        if (firstVisiblePos % 3 == 0) {
            adapter.notifyItemRangeChanged(
                0.coerceAtLeast(firstVisiblePos - 5),
                (totalItems - firstVisiblePos + 5).coerceAtMost(totalItems)
            )
        }
    }

    /**
     * 三阶段色温插值。
     * progress 0.0→0.5: warmColor → coolColor
     * progress 0.5→1.0: coolColor → grayColor
     */
    private fun interpolateColor(progress: Float, warm: Int, cool: Int, gray: Int): Int {
        return when {
            progress < 0.5f -> {
                val p = progress / 0.5f
                blendColors(warm, cool, p)
            }
            else -> {
                val p = (progress - 0.5f) / 0.5f
                blendColors(cool, gray, p)
            }
        }
    }

    /**
     * 使用 ArgbEvaluator 进行颜色混合。
     */
    private fun blendColors(color1: Int, color2: Int, ratio: Float): Int {
        return android.animation.ArgbEvaluator().evaluate(ratio, color1, color2) as Int
    }

    // ── 监听器设置 ──

    private fun setupListeners() {
        // 返回按钮
        binding.btnBack.setOnClickListener { finish() }

        // 取消删除
        binding.btnCancelDelete.setOnClickListener {
            hideDeleteConfirm()
        }

        // 确认删除
        binding.btnConfirmDelete.setOnClickListener {
            pendingDeleteCard?.let { card ->
                pendingDeleteView?.let { view ->
                    executeShatterAnimation(view, card)
                }
            }
        }
    }

    // ── 数据加载 ──

    /** 加载记忆列表 */
    private fun loadMemories(page: Int) {
        if (isLoading) return
        isLoading = true

        if (page > 1) {
            // binding.tvLoadMore.visibility = View.VISIBLE
        }

        lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    val py = com.chaquo.python.Python.getInstance()
                    val module = py.getModule("chat_bridge")
                    module.callAttr("list_memories", "", page, PAGE_SIZE).toString()
                }
            } catch (e: Exception) {
                Log.w(TAG, "加载记忆列表失败: ${e.message}")
                null
            }

            isLoading = false
            // binding.tvLoadMore.visibility = View.GONE

            if (result != null) {
                try {
                    parseAndAddItems(result)
                } catch (e: Exception) {
                    Log.w(TAG, "解析记忆列表失败: ${e.message}")
                }
            }

            // 更新空状态
            showEmpty(cardDataList.isEmpty())
        }
    }

    /** 解析 JSON 并转换为 MemoryCardData */
    private fun parseAndAddItems(jsonStr: String) {
        val json = JSONObject(jsonStr)
        val itemsArray: JSONArray? = json.optJSONArray("items")

        if (itemsArray == null || itemsArray.length() == 0) {
            hasMore = false
            if (currentPage == 1) showEmpty(true)
            return
        }

        val parsedCards = mutableListOf<MemoryCardData>()
        for (i in 0 until itemsArray.length()) {
            val obj = itemsArray.getJSONObject(i)
            val memoryItem = MemoryItem(
                rowid = obj.optInt("rowid", 0),
                id = obj.optString("id", ""),
                type = obj.optString("memory_type", "episodic"),
                content = obj.optString("content", ""),
                createdAt = obj.optString("created_at", ""),
                importance = obj.optDouble("importance", 0.0)
            )
            parsedCards.add(MemoryCardData.fromMemoryItem(memoryItem))
        }

        adapter.addAll(parsedCards)
        cardDataList.addAll(parsedCards)
        loadedCount = cardDataList.size

        // 判断是否还有更多
        val total = json.optInt("total", 0)
        hasMore = loadedCount < total

        showEmpty(cardDataList.isEmpty())

        Log.d(TAG, "已加载记忆卡片: 本页=${parsedCards.size}, 总计=${loadedCount}, 还有更多=${hasMore}")
    }

    /** 加载下一页 */
    private fun loadNextPage() {
        if (!hasMore || isLoading) return
        currentPage++
        loadMemories(page = currentPage)
    }

    // ── 长按破碎删除 ──

    /**
     * 卡片长按处理。
     *
     * 流程：
     * 1. 取消之前的待处理操作
     * 2. 记录待删除卡片信息
     * 3. 启动 1.2 秒延迟触发裂纹效果
     * 4. 显示底部确认按钮
     */
    private fun onCardLongPressed(card: MemoryCardData, view: View, position: Int) {
        // 取消之前的操作
        cancelPendingDelete()

        pendingDeleteCard = card
        pendingDeletePosition = position
        pendingDeleteView = view

        // 1.2 秒后触发裂纹效果
        val runnable = Runnable {
            showCrackEffect(view)
            playShatterSound()
        }
        longPressRunnable = runnable
        longPressHandler.postDelayed(runnable, LONG_PRESS_DURATION_MS)

        // 显示底部确认按钮
        showDeleteConfirm()

        // 卡片高亮/缩放反馈
        view.animate()
            .scaleX(1.05f)
            .scaleY(1.05f)
            .setDuration(200)
            .start()
    }

    /** 取消待处理的删除操作 */
    private fun cancelPendingDelete() {
        longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
        longPressRunnable = null

        // 恢复卡片缩放
        pendingDeleteView?.animate()
            ?.scaleX(1.0f)
            ?.scaleY(1.0f)
            ?.setDuration(150)
            ?.start()

        pendingDeleteCard = null
        pendingDeletePosition = -1
        pendingDeleteView = null
        hideDeleteConfirm()
    }

    /** 显示裂纹效果 — 在 ViewOverlay 上绘制裂纹线条 */
    private fun showCrackEffect(view: View) {
        val crackView = object : View(this) {
            private val crackPaint = Paint().apply {
                color = Color.parseColor("#80E57373")  // 半透明红色裂纹
                strokeWidth = 2.5f
                style = Paint.Style.STROKE
                isAntiAlias = true
                strokeCap = Paint.Cap.ROUND
            }
            private val glowPaint = Paint().apply {
                color = Color.parseColor("#40FFFFFF")  // 白色光晕
                strokeWidth = 5f
                style = Paint.Style.STROKE
                isAntiAlias = true
                strokeCap = Paint.Cap.ROUND
            }
            private val crackPaths = mutableListOf<Path>()

            init {
                // 生成随机裂纹路径
                generateCrackPaths()
            }

            private fun generateCrackPaths() {
                val w = view.width.toFloat()
                val h = view.height.toFloat()
                val cx = w / 2f
                val cy = h / 2f

                // 主裂纹：从中心向四周辐射
                val numCracks = Random.nextInt(3, 6)
                for (i in 0 until numCracks) {
                    val angle = (360f / numCracks) * i + Random.nextFloat() * 30f
                    val rad = Math.toRadians(angle.toDouble())
                    val endX = cx + (w * 0.6f * Math.cos(rad)).toFloat()
                    val endY = cy + (h * 0.6f * Math.sin(rad)).toFloat()

                    val path = Path().apply {
                        moveTo(cx, cy)
                        // 添加抖动，使裂纹看起来更自然
                        val midX = (cx + endX) / 2f + Random.nextFloat() * 20f - 10f
                        val midY = (cy + endY) / 2f + Random.nextFloat() * 20f - 10f
                        quadTo(midX, midY, endX, endY)
                    }
                    crackPaths.add(path)
                }

                // 次级裂纹：从主裂纹中途分支
                val numBranches = Random.nextInt(2, 4)
                for (i in 0 until numBranches) {
                    val startX = cx + Random.nextFloat() * w * 0.3f - w * 0.15f
                    val startY = cy + Random.nextFloat() * h * 0.3f - h * 0.15f
                    val endX = startX + Random.nextFloat() * 60f - 30f
                    val endY = startY + Random.nextFloat() * 60f - 30f

                    val path = Path().apply {
                        moveTo(startX, startY)
                        lineTo(endX, endY)
                    }
                    crackPaths.add(path)
                }
            }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                // 绘制光晕
                for (path in crackPaths) {
                    canvas.drawPath(path, glowPaint)
                }
                // 绘制裂纹
                for (path in crackPaths) {
                    canvas.drawPath(path, crackPaint)
                }
            }
        }

        // 设置裂纹视图的尺寸与卡片一致
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val rootLocation = IntArray(2)
        binding.archiveRoot.getLocationOnScreen(rootLocation)

        crackView.layoutParams = ViewGroup.LayoutParams(view.width, view.height)
        crackView.x = location[0] - rootLocation[0].toFloat()
        crackView.y = location[1] - rootLocation[1].toFloat()

        binding.archiveRoot.addView(crackView)

        // 裂纹出现动画：从透明到可见
        crackView.alpha = 0f
        crackView.animate()
            .alpha(1f)
            .setDuration(300)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                // 裂纹持续显示 1.5 秒后渐隐
                crackView.animate()
                    .alpha(0f)
                    .setDuration(500)
                    .setStartDelay(1000)
                    .withEndAction {
                        binding.archiveRoot.removeView(crackView)
                    }
                    .start()
            }
            .start()
    }

    /** 显示底部删除确认按钮 */
    private fun showDeleteConfirm() {
        binding.layoutDeleteConfirm.visibility = View.VISIBLE
        binding.layoutDeleteConfirm.alpha = 0f
        binding.layoutDeleteConfirm.translationY = 80f
        binding.layoutDeleteConfirm.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(300)
            .setInterpolator(OvershootInterpolator(0.8f))
            .start()
    }

    /** 隐藏底部删除确认按钮 */
    private fun hideDeleteConfirm() {
        binding.layoutDeleteConfirm.animate()
            .alpha(0f)
            .translationY(80f)
            .setDuration(200)
            .withEndAction {
                binding.layoutDeleteConfirm.visibility = View.GONE
            }
            .start()
    }

    /**
     * 执行破碎动画：将卡片分解为 4 片向屏幕上方飘走。
     *
     * 实现方式：
     * 1. 获取卡片在屏幕上的位置
     * 2. 创建 4 个半透明碎片 View
     * 3. 使用 ObjectAnimator 将碎片分别向上、向外飘走
     * 4. 动画结束后从列表移除并调用 Python 删除
     */
    private fun executeShatterAnimation(view: View, card: MemoryCardData) {
        hideDeleteConfirm()
        playShatterSound()

        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val rootLocation = IntArray(2)
        binding.archiveRoot.getLocationOnScreen(rootLocation)

        val cardX = location[0] - rootLocation[0]
        val cardY = location[1] - rootLocation[1]
        val cardW = view.width
        val cardH = view.height

        // 先隐藏原始卡片
        view.visibility = View.INVISIBLE

        // 创建 4 个碎片
        val fragmentColors = listOf(
            Color.parseColor("#E6FFFFFF"),
            Color.parseColor("#E6FDF0F0"),
            Color.parseColor("#E6D4E8F0"),
            Color.parseColor("#E6FCE4E0")
        )

        val fragments = mutableListOf<View>()
        val fragmentWidth = cardW / 2
        val fragmentHeight = cardH / 2

        // 碎片位置偏移（左上、右上、左下、右下）
        val offsets = listOf(
            Pair(0, 0),                              // 左上
            Pair(fragmentWidth, 0),                   // 右上
            Pair(0, fragmentHeight),                  // 左下
            Pair(fragmentWidth, fragmentHeight)       // 右下
        )

        for (i in 0 until 4) {
            val fragment = View(this).apply {
                layoutParams = ViewGroup.LayoutParams(fragmentWidth, fragmentHeight)
                x = cardX + offsets[i].first.toFloat()
                y = cardY + offsets[i].second.toFloat()
                setBackgroundColor(fragmentColors[i])
                alpha = 0.9f
                // 设置圆角（通过 clipToOutline）
                clipToOutline = true
            }
            fragments.add(fragment)
            binding.archiveRoot.addView(fragment)
        }

        // 动画：4 个碎片向不同方向飘走
        val animators = mutableListOf<Animator>()

        // 碎片移动方向和旋转
        val flyDirections = listOf(
            Pair(-80f, -200f),   // 左上方
            Pair(80f, -250f),    // 右上方
            Pair(-120f, -150f),  // 左上方（较远）
            Pair(120f, -180f)    // 右上方（较远）
        )
        val rotations = listOf(-45f, 60f, -30f, 50f)

        for (i in 0 until 4) {
            val fragment = fragments[i]
            val dir = flyDirections[i]

            val txAnim = ObjectAnimator.ofFloat(fragment, "translationX", 0f, dir.first)
            val tyAnim = ObjectAnimator.ofFloat(fragment, "translationY", 0f, dir.second)
            val rotAnim = ObjectAnimator.ofFloat(fragment, "rotation", 0f, rotations[i])
            val alphaAnim = ObjectAnimator.ofFloat(fragment, "alpha", 0.9f, 0f)

            val set = AnimatorSet().apply {
                playTogether(txAnim, tyAnim, rotAnim, alphaAnim)
                duration = 800
                interpolator = AccelerateInterpolator(1.2f)
            }
            animators.add(set)
        }

        // 所有碎片同时开始动画
        val allAnimators = AnimatorSet().apply {
            playTogether(animators)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // 清理碎片 View
                    for (fragment in fragments) {
                        binding.archiveRoot.removeView(fragment)
                    }
                    // 从数据源删除
                    deleteCardFromData(card)
                }
            })
        }

        allAnimators.start()
    }

    /**
     * 从数据源删除卡片并同步 Python 后端。
     */
    private fun deleteCardFromData(card: MemoryCardData) {
        // 从列表移除
        val index = cardDataList.indexOfFirst { it.rowid == card.rowid }
        if (index >= 0) {
            cardDataList.removeAt(index)
            adapter.removeAt(index)
        }

        // 调用 Python 删除
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val py = com.chaquo.python.Python.getInstance()
                    val module = py.getModule("chat_bridge")
                    module.callAttr("delete_memory", card.rowid)
                }
                Log.d(TAG, "记忆已删除: rowid=${card.rowid}, content=${card.content.take(30)}...")
            } catch (e: Exception) {
                Log.w(TAG, "删除记忆失败: ${e.message}")
            }
        }

        // 更新空状态
        showEmpty(cardDataList.isEmpty())
    }

    // ── 辅助方法 ──

    /** 控制空状态提示 */
    private fun showEmpty(show: Boolean) {
        binding.layoutEmpty.visibility = if (show) View.VISIBLE else View.GONE
        binding.rvArchive.visibility = if (show) View.GONE else View.VISIBLE
        if (show) {
            val emptyIcon = binding.layoutEmpty.findViewById<ImageView>(R.id.ivEmptyIcon)
            val emptyTitle = binding.layoutEmpty.findViewById<TextView>(R.id.tvEmptyTitle)
            val emptyDesc = binding.layoutEmpty.findViewById<TextView>(R.id.tvEmptyDesc)
            emptyIcon.setImageResource(R.drawable.ic_settings_memory)
            emptyTitle.setText(R.string.empty_memory_archive)
            emptyDesc.visibility = View.GONE
        }
    }
}
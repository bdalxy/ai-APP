package com.aicompanion.app

import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * 通用状态视图助手 — 管理 RecyclerView 的三种状态切换。
 *
 * 状态：
 * - DATA: 显示列表数据
 * - LOADING: 显示加载中（RecyclerView 隐藏，loading 文本显示）
 * - EMPTY: 显示空状态提示
 * - ERROR: 显示错误信息 + 重试按钮
 *
 * 使用方式：
 *   val stateHelper = StateViewHelper(rvList, tvEmpty, tvLoading, tvError, btnRetry)
 *   stateHelper.showLoading()
 *   stateHelper.showData()
 *   stateHelper.showEmpty("还没有任何记忆")
 *   stateHelper.showError("加载失败", onClick = { retry() })
 */
class StateViewHelper(
    private val recyclerView: RecyclerView,
    private val emptyView: TextView,
    private val loadingView: TextView,
    private val errorView: TextView,
    private val retryButton: TextView? = null,
) {
    enum class State { DATA, LOADING, EMPTY, ERROR }

    var currentState: State = State.LOADING
        private set

    fun showLoading() {
        currentState = State.LOADING
        recyclerView.visibility = View.GONE
        emptyView.visibility = View.GONE
        loadingView.visibility = View.VISIBLE
        errorView.visibility = View.GONE
        retryButton?.visibility = View.GONE
    }

    fun showData() {
        currentState = State.DATA
        recyclerView.visibility = View.VISIBLE
        emptyView.visibility = View.GONE
        loadingView.visibility = View.GONE
        errorView.visibility = View.GONE
        retryButton?.visibility = View.GONE
    }

    fun showEmpty(message: String) {
        currentState = State.EMPTY
        recyclerView.visibility = View.GONE
        emptyView.visibility = View.VISIBLE
        loadingView.visibility = View.GONE
        errorView.visibility = View.GONE
        retryButton?.visibility = View.GONE
        emptyView.text = message
    }

    fun showError(message: String, onClick: (() -> Unit)? = null) {
        currentState = State.ERROR
        recyclerView.visibility = View.GONE
        emptyView.visibility = View.GONE
        loadingView.visibility = View.GONE
        errorView.visibility = View.VISIBLE
        errorView.text = message
        if (retryButton != null && onClick != null) {
            retryButton.visibility = View.VISIBLE
            retryButton.setOnClickListener { onClick() }
        }
    }
}
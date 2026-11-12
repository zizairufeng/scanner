package com.example.scanstock.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.scanstock.data.ScanHistoryStore
import com.example.scanstock.domain.HistoryQuery
import com.example.scanstock.domain.HistoryRange
import com.example.scanstock.domain.ScanRecord
import com.example.scanstock.logging.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 历史面板的全部状态。
 *
 * 为什么与 [ScanUiState] 分开：这个面板的生命周期跟着弹层走，
 * 打开/关闭几十次也不该让主屏状态变一下（主屏重组代价是相机预览）。
 * 拆开之后，搜索的每次输入只驱动这个 StateFlow。
 */
data class HistoryUiState(
    /** 用户输入的码值片段，原样保留（不 trim，否则打字过程中光标会跳） */
    val query: String = "",

    /** 时间范围，默认 7 天：昨天扫的今天还要查 */
    val range: HistoryRange = HistoryRange.WEEK,

    /** 只看本次运行。清空列表后想找回「刚才那批」时用 */
    val sessionOnly: Boolean = false,

    /** 正在读盘（大范围搜索时会有短暂的真值） */
    val loading: Boolean = false,

    /** 命中记录，**新的在前** */
    val records: List<ScanRecord> = emptyList(),

    /** 命中数超过上限，只显示最新的那部分 */
    val truncated: Boolean = false,

    /** 这次搜索实际读了多少个分片文件 */
    val scannedDays: Int = 0,

    /** 历史总览（多少天 / 多少条 / 多大） */
    val stats: ScanHistoryStore.Stats? = null,

    /** 清空操作完成次数。UI 用它触发一次提示（与主屏 flashTick 同一套做法） */
    val clearTick: Int = 0,

    /** 最近一次清空是否成功 */
    val clearOk: Boolean = true
) {
    val searching: Boolean get() = query.isNotBlank()
}

/**
 * 历史面板的 ViewModel（v1.8.9）。
 *
 * 与 [ScanViewModel] 共用同一个进程级 [ScanHistoryStore]：
 * 一个在写、一个在读，各自持有实例会互相踩同一个文件的写入位置。
 */
class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val store = ScanHistoryStore.get(application.filesDir) { message, error ->
        AppLog.w(TAG, message, error)
    }

    private val _state = MutableStateFlow(HistoryUiState())
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()

    /** 本次运行的标识，界面上「只看这一批」用的就是它 */
    val session: String get() = store.session

    private var searchJob: Job? = null
    private var statsJob: Job? = null

    init {
        refreshStats()
        scheduleSearch(immediate = true)
    }

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
        scheduleSearch()
    }

    fun setRange(range: HistoryRange) {
        if (_state.value.range == range) return
        _state.update { it.copy(range = range) }
        // 范围切换是明确的动作，不需要防抖
        scheduleSearch(immediate = true)
    }

    fun toggleSessionOnly() {
        _state.update { it.copy(sessionOnly = !it.sessionOnly) }
        scheduleSearch(immediate = true)
    }

    /** 打开面板 / 回到前台时调一次：补上「在别处刚扫的那几条」与最新统计 */
    fun refresh() {
        refreshStats()
        scheduleSearch(immediate = true)
    }

    /**
     * 执行搜索。
     *
     * 防抖放在这里而不是界面里：输入框每敲一个字符都会触发一轮磁盘扫描，
     * 100ms 的窗口能把「连续输入」合成一次。搜索本身在 IO 线程上跑，
     * 主线程只拿最终结果。
     */
    private fun scheduleSearch(immediate: Boolean = false) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            if (!immediate) delay(SEARCH_DEBOUNCE_MS)
            val snapshot = _state.value
            _state.update { it.copy(loading = true) }
            val now = System.currentTimeMillis()
            val from = HistoryQuery.fromMillis(snapshot.range, now)
            // 上界恒为「不限」：范围选项只决定左边界，未来的时间戳本来就不存在
            val session = if (snapshot.sessionOnly) store.session else null
            val result = withContext(Dispatchers.IO) {
                store.search(
                    needle = snapshot.query,
                    fromMillis = from,
                    toMillis = Long.MAX_VALUE,
                    session = session
                )
            }
            _state.update {
                it.copy(
                    loading = false,
                    records = result.records,
                    truncated = result.truncated,
                    scannedDays = result.scannedDays
                )
            }
        }
    }

    private fun refreshStats() {
        statsJob?.cancel()
        statsJob = viewModelScope.launch {
            val stats = withContext(Dispatchers.IO) { store.stats() }
            _state.update { it.copy(stats = stats) }
        }
    }

    /** 清空全部历史（界面必须先让用户二次确认） */
    fun clearAll() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val ok = withContext(Dispatchers.IO) { store.clear() }
            AppLog.i(TAG, "清空扫描历史：${if (ok) "成功" else "超时未完成"}")
            _state.update { it.copy(loading = false, clearTick = it.clearTick + 1, clearOk = ok) }
            refreshStats()
            scheduleSearch(immediate = true)
        }
    }

    private companion object {
        const val TAG = "History"

        /** 输入防抖窗口。比写盘防抖（500ms）短：搜索是读，用户期待即时反馈 */
        const val SEARCH_DEBOUNCE_MS = 120L
    }
}

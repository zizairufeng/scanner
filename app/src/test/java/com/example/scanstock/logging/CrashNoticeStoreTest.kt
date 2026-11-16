package com.example.scanstock.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 崩溃提示「已读」状态的持久化测试（纯 JVM，真文件）。
 *
 * 这里要证明的不是「能写个文件」，而是用户那句抱怨的闭环：
 * 「忽略了还一直弹」→ 标记落盘 → 换实例（=重启）仍不弹；
 * 同时真的新崩溃不能被这个标记吞掉。
 */
class CrashNoticeStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val dir: File get() = File(tmp.root, AppLog.LOG_DIR)

    private fun store(): CrashNoticeStore = CrashNoticeStore(dir)

    private fun report(name: String): File = File(dir, name)

    private fun reports(vararg names: String): List<File> = names.map(::report)

    @Test
    fun `没有任何标记时全部算未读`() {
        val items = reports("crash-2026-11-16_090000-main.txt", "crash-2026-11-15_090000-main.txt")
        assertEquals(items, store().unseen(items))
    }

    @Test
    fun `标记之后同一批报告不再算未读`() {
        val store = store()
        val items = reports("crash-2026-11-16_090000-main.txt", "crash-2026-11-15_090000-main.txt")
        store.markSeen(items.map { it.name })

        assertTrue(store.unseen(items).isEmpty())
    }

    @Test
    fun `换成新实例仍然记得（模拟进程重启）`() {
        val oldInstance = store()
        val items = reports("crash-2026-11-11_091819-main.txt")
        oldInstance.markSeen(items.map { it.name })

        val afterRestart = store()
        assertTrue("已读标记必须跨实例存在", afterRestart.unseen(items).isEmpty())
    }

    @Test
    fun `出现新报告时仍然会提醒，不会被旧标记吞掉`() {
        val store = store()
        val old = reports("crash-2026-11-11_091819-main.txt")
        store.markSeen(old.map { it.name })

        val fresh = report("crash-2026-11-16_230000-main.txt")
        assertEquals(
            "新崩溃必须重新弹提示",
            listOf(fresh),
            store.unseen(listOf(fresh) + old)
        )
    }

    @Test
    fun `只标记一部分时，剩下的仍在未读里`() {
        val store = store()
        val first = report("crash-2026-11-16_090000-main.txt")
        val second = report("crash-2026-11-15_090000-main.txt")
        store.markSeen(listOf(first.name))

        assertEquals(listOf(second), store.unseen(listOf(first, second)))
    }

    @Test
    fun `标记文件有空行和垃圾行也不影响判断`() {
        dir.mkdirs()
        File(dir, CrashNoticeStore.MARKER_FILE).writeText(
            "\ncrash-2026-11-16_090000-main.txt\n\nnot-a-report\n   \n",
            Charsets.UTF_8
        )
        val good = report("crash-2026-11-16_090000-main.txt")
        val junk = report("not-a-report")

        assertTrue(store().unseen(listOf(good, junk)).isEmpty())
    }

    @Test
    fun `标记数量有上限，不会无限增长`() {
        val store = store()
        val names = (0 until 50).map { "crash-2026-11-01_${"%06d".format(it)}-main.txt" }
        store.markSeen(names)

        val lines = File(dir, CrashNoticeStore.MARKER_FILE).readLines()
        assertEquals(32, lines.size)
        assertEquals(names.takeLast(32), lines)
    }

    @Test
    fun `清空标记后恢复成全部未读`() {
        val store = store()
        val items = reports("crash-2026-11-16_090000-main.txt")
        store.markSeen(items.map { it.name })
        store.clear()

        assertEquals(items, store.unseen(items))
    }

    @Test
    fun `标记文件名不能和崩溃报告前缀冲突`() {
        assertTrue(
            "seen-crash.txt 若以 crash- 开头，会被 crashReports() 自己统计进去",
            !CrashNoticeStore.MARKER_FILE.startsWith(AppLog.CRASH_PREFIX)
        )
    }
}

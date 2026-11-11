package com.example.scanstock.util

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RejectionTolerantExecutor] 的单测。
 *
 * 要守住的两条：
 *  1. 池子活着时，任务照常执行——包装层不能把正常路径也吃掉；
 *  2. 池子 shutdown() 之后再提交，**不抛异常**、只记账——这正是 v1.8.7
 *     那次闪退（RejectedExecutionException 落在主线程）要堵住的路径。
 */
class RejectionTolerantExecutorTest {

    @Test
    fun `未关闭时任务照常执行`() {
        val raw = Executors.newSingleThreadExecutor()
        val executor = RejectionTolerantExecutor(raw, name = "test") { _, _ -> }
        val latch = CountDownLatch(1)
        val runs = AtomicInteger(0)

        try {
            executor.execute {
                runs.incrementAndGet()
                latch.countDown()
            }
            assertTrue("任务没有被执行", latch.await(2, TimeUnit.SECONDS))
            assertEquals(1, runs.get())
            assertEquals(0L, executor.rejectedCount())
        } finally {
            raw.shutdownNow()
        }
    }

    @Test
    fun `关闭后提交只记账不抛异常`() {
        val raw = Executors.newSingleThreadExecutor()
        val rejectedTotal = AtomicInteger(0)
        val executor = RejectionTolerantExecutor(raw, name = "test") { _, _ ->
            rejectedTotal.incrementAndGet()
        }

        raw.shutdown()
        // 等池子真正进入 TERMINATED：只有在 TERMINATED / SHUTDOWN 之后，
        // 裸 ThreadPoolExecutor 才会走 AbortPolicy 抛异常
        raw.awaitTermination(2, TimeUnit.SECONDS)

        val runs = AtomicInteger(0)
        repeat(3) {
            executor.execute { runs.incrementAndGet() }
        }

        assertEquals("被拒绝的任务一次都不该执行", 0, runs.get())
        assertEquals("三次提交都要记账", 3L, executor.rejectedCount())
        assertEquals(3, rejectedTotal.get())
    }
}

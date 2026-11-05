package com.example.scanstock.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 准入规则过滤器单测。
 *
 * 重点覆盖两类「会出事」的情况：
 * 1. 用户写错的正则——必须既不崩溃、也不能悄悄放行所有码；
 * 2. 多条件组合——三条是「与」关系，漏掉一条就会放进不该要的码。
 */
class CodeFilterTest {

    @Test
    fun `未启用时全部放行`() {
        val rule = CodeRule(enabled = false, prefix = "69", length = 13)
        assertTrue(CodeFilter.accepts("ABC", rule))
        assertTrue(CodeFilter.accepts("", rule))
    }

    @Test
    fun `启用但条件全空等同不限制`() {
        val rule = CodeRule(enabled = true)
        assertTrue(rule.isBlank())
        assertTrue(CodeFilter.accepts("任意内容", rule))
    }

    @Test
    fun `前缀匹配才通过`() {
        val rule = CodeRule(enabled = true, prefix = "69")
        assertTrue(CodeFilter.accepts("6901234567892", rule))
        assertFalse(CodeFilter.accepts("4901234567892", rule))
    }

    @Test
    fun `长度不符被拒绝`() {
        val rule = CodeRule(enabled = true, length = 13)
        assertTrue(CodeFilter.accepts("6901234567892", rule))   // 13 位
        assertFalse(CodeFilter.accepts("690123456789", rule))   // 12 位
    }

    @Test
    fun `前缀与长度同时满足才通过`() {
        val rule = CodeRule(enabled = true, prefix = "69", length = 13)
        assertTrue(CodeFilter.accepts("6901234567892", rule))
        assertFalse("前缀对但长度错", CodeFilter.accepts("690123456789", rule))
        assertFalse("长度对但前缀错", CodeFilter.accepts("4901234567892", rule))
    }

    @Test
    fun `正则匹配才通过`() {
        val rule = CodeRule(enabled = true, regex = "^69\\d{11}$")
        assertTrue(CodeFilter.accepts("6901234567892", rule))
        assertFalse(CodeFilter.accepts("6801234567892", rule))
    }

    @Test
    fun `非法正则不崩溃且不放行`() {
        val rule = CodeRule(enabled = true, regex = "^69\\d{11")   // 少一个右括号
        // 关键：这里不能抛异常——调用点在每个识别帧上，一抛就是连续崩溃
        assertFalse(CodeFilter.accepts("6901234567892", rule))
        assertEquals("非法正则应被记录下来供 UI 提示", "^69\\d{11", CodeFilter.lastError)
        // validate 也要能报出来，让设置面板当场提示用户
        assertNotNull(CodeFilter.validate(rule))
        CodeFilter.reset()
    }

    @Test
    fun `validate对合法规则不报错`() {
        assertNull(CodeFilter.validate(CodeRule(enabled = false)))
        assertNull(CodeFilter.validate(CodeRule(enabled = true, prefix = "69", length = 13)))
        assertNull(CodeFilter.validate(CodeRule(enabled = true, regex = "^69\\d{11}$")))
    }

    @Test
    fun `validate拒绝含空格的前缀`() {
        assertNotNull("条码值一般不含空格，前缀里写空格多半是手误",
            CodeFilter.validate(CodeRule(enabled = true, prefix = "69 01")))
    }

    @Test
    fun `reset清空错误态`() {
        CodeFilter.accepts("X", CodeRule(enabled = true, regex = "["))
        assertNotNull(CodeFilter.lastError)
        CodeFilter.reset()
        assertNull(CodeFilter.lastError)
    }

    @Test
    fun `空码值在启用规则时被拒绝`() {
        assertFalse(CodeFilter.accepts("", CodeRule(enabled = true)))
    }
}

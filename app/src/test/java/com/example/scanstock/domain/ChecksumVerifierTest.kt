package com.example.scanstock.domain

import com.example.scanstock.domain.ChecksumVerifier.CheckResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 校验位自校验的单测。
 *
 * 这一组测试的定位和别处不同：它守的是**误杀**风险。
 * 校验位是「不通过就丢弃」，一旦算法写错，表现是「用户扫得出来但 App 不认」——
 * 比误识更难排查（用户看不到任何错误提示）。
 *
 * 所以合法码的用例必须覆盖四种码制、且用**真实商品码**（不是自己编的），
 * 篡改用例则必须覆盖「改数据位」「改校验位」「改最左位」「改最右数据位」四个角落。
 */
class ChecksumVerifierTest {

    // ─────────────────────────── 合法码：四种码制 ───────────────────────────

    @Test
    fun `真实 EAN-13 商品码全部通过`() {
        // 均为真实存在的商品码，不是构造出来的
        listOf(
            "4006381333931",   // Stabilo 笔
            "5901234123457",   // 波兰产示例码
            "9780201379624",   // ISBN 书号（978 前缀）
            "0075678164125"    // 美国产示例码（前导 0）
        ).forEach { code ->
            assertEquals("$code 应当通过校验", CheckResult.VALID, ChecksumVerifier.verify("EAN-13", code))
        }
    }

    @Test
    fun `真实 EAN-8 码通过`() {
        listOf("96385074", "73513537").forEach { code ->
            assertEquals("$code 应当通过校验", CheckResult.VALID, ChecksumVerifier.verify("EAN-8", code))
        }
    }

    @Test
    fun `真实 UPC-A 码通过`() {
        // UPC-A 常见前导 0，这里同时覆盖「首位是 0」和「首位非 0」两种
        listOf("036000291452", "042100005264", "123456789012").forEach { code ->
            assertEquals("$code 应当通过校验", CheckResult.VALID, ChecksumVerifier.verify("UPC-A", code))
        }
    }

    @Test
    fun `真实 UPC-E 码通过`() {
        assertEquals(CheckResult.VALID, ChecksumVerifier.verify("UPC-E", "01234565"))
    }

    // ─────────────────────────── 篡改：必须判 INVALID ───────────────────────────

    /**
     * 这是本类存在的**唯一理由**：识别库把某一位解错时，校验位会立刻对不上。
     *
     * 逐个位置篡改，确保没有任何一位被漏掉（例如实现里少乘了一个权重，
     * 可能恰好只在某些位置上暴露）。
     */
    @Test
    fun `EAN-13 改动任意一位都会被判定为不合法`() {
        val original = "4006381333931"
        for (pos in original.indices) {
            val digit = original[pos] - '0'
            // 换成另一个数字（+1 取模，保证一定不同）
            val tampered = original.substring(0, pos) +
                ('0' + ((digit + 1) % 10)) +
                original.substring(pos + 1)
            assertEquals(
                "把第 $pos 位从 $digit 改成 ${(digit + 1) % 10} 后应当判为不合法",
                CheckResult.INVALID,
                ChecksumVerifier.verify("EAN-13", tampered)
            )
        }
    }

    @Test
    fun `EAN-8 改动任意一位都会被判定为不合法`() {
        val original = "96385074"
        for (pos in original.indices) {
            val digit = original[pos] - '0'
            val tampered = original.substring(0, pos) +
                ('0' + ((digit + 1) % 10)) +
                original.substring(pos + 1)
            assertEquals(
                "第 $pos 位被改后应当判为不合法",
                CheckResult.INVALID,
                ChecksumVerifier.verify("EAN-8", tampered)
            )
        }
    }

    @Test
    fun `UPC-A 改动任意一位都会被判定为不合法`() {
        val original = "036000291452"
        for (pos in original.indices) {
            val digit = original[pos] - '0'
            val tampered = original.substring(0, pos) +
                ('0' + ((digit + 1) % 10)) +
                original.substring(pos + 1)
            assertEquals(
                "第 $pos 位被改后应当判为不合法",
                CheckResult.INVALID,
                ChecksumVerifier.verify("UPC-A", tampered)
            )
        }
    }

    /**
     * 相邻两位互换（1↔7、6↔8 这类形近误判的典型结果）也应当被抓到。
     *
     * 注意：mod-10 对**某些**互换是无感的（这是它的已知局限，不是 bug），
     * 所以这里只断言「至少能被抓到一部分」，不假装它能抓住全部。
     */
    @Test
    fun `相邻位互换能被部分捕获`() {
        val original = "4006381333931"
        var caught = 0
        for (pos in 0 until original.length - 1) {
            val a = original[pos]
            val b = original[pos + 1]
            if (a == b) continue
            val swapped = original.substring(0, pos) + b + a + original.substring(pos + 2)
            if (ChecksumVerifier.verify("EAN-13", swapped) == CheckResult.INVALID) caught++
        }
        assertTrue("相邻互换至少应抓到一半以上，实际 $caught", caught >= 5)
    }

    // ─────────────────────────── 不可校验：必须放行 ───────────────────────────

    @Test
    fun `长度不符时不判错而是交还给调用方`() {
        // 宁可放过不可错杀：长度不对说明这里没有判断依据
        assertEquals(CheckResult.NOT_APPLICABLE, ChecksumVerifier.verify("EAN-13", "400638133393"))
        assertEquals(CheckResult.NOT_APPLICABLE, ChecksumVerifier.verify("EAN-13", "40063813339310"))
        assertEquals(CheckResult.NOT_APPLICABLE, ChecksumVerifier.verify("EAN-8", "9638507"))
        assertEquals(CheckResult.NOT_APPLICABLE, ChecksumVerifier.verify("UPC-A", "03600029145"))
    }

    @Test
    fun `含非数字时不判错而是交还给调用方`() {
        assertEquals(CheckResult.NOT_APPLICABLE, ChecksumVerifier.verify("EAN-13", "400638133393A"))
        assertEquals(CheckResult.NOT_APPLICABLE, ChecksumVerifier.verify("EAN-13", "4006381333 31"))
        assertEquals(CheckResult.NOT_APPLICABLE, ChecksumVerifier.verify("UPC-A", "03600029145-"))
    }

    @Test
    fun `空值不判错`() {
        assertEquals(CheckResult.NOT_APPLICABLE, ChecksumVerifier.verify("EAN-13", ""))
    }

    /**
     * 没有校验位的码制必须明确返回「不适用」，而不是碰巧返回 VALID——
     * 后者会让调用方（和以后读代码的人）以为「进来的码都验过了」。
     */
    @Test
    fun `无校验位的码制返回不适用`() {
        listOf(
            "QR Code" to "https://example.com/abc",
            "Data Matrix" to "LOT20261008",
            "Code-128" to "ABC-12345678",
            "Code-39" to "CODE39TEST",
            "PDF417" to "RAW-DATA",
            "未知(-1)" to "1234567890123"
        ).forEach { (format, value) ->
            assertEquals(
                "$format 没有可校验的校验位",
                CheckResult.NOT_APPLICABLE,
                ChecksumVerifier.verify(format, value)
            )
        }
    }

    // ─────────────────────────── 校验位计算本身 ───────────────────────────

    @Test
    fun `mod10 校验位算法与标准一致`() {
        // body -> 期望校验位，均为按 EAN/UPC 标准算出的已知值
        assertEquals(1, ChecksumVerifier.computeMod10CheckDigit("400638133393"))
        assertEquals(7, ChecksumVerifier.computeMod10CheckDigit("590123412345"))
        assertEquals(4, ChecksumVerifier.computeMod10CheckDigit("978020137962"))
        assertEquals(5, ChecksumVerifier.computeMod10CheckDigit("007567816412"))
        assertEquals(7, ChecksumVerifier.computeMod10CheckDigit("7351353"))     // EAN-8
        assertEquals(4, ChecksumVerifier.computeMod10CheckDigit("9638507"))     // EAN-8
        assertEquals(2, ChecksumVerifier.computeMod10CheckDigit("03600029145")) // UPC-A
        assertEquals(5, ChecksumVerifier.computeMod10CheckDigit("0123456"))     // UPC-E
    }

    @Test
    fun `校验位取值恒在 0 到 9`() {
        // 全 0 → 加权和为 0 → 校验位 0
        assertEquals(0, ChecksumVerifier.computeMod10CheckDigit("000000000000"))
        // 全 9 → 加权和 216 → 216 % 10 = 6 → 校验位 4
        // （这条曾经把期望写成 0，暴露的是「手算不如让测试算」——公式对不对由
        //  「补上算出的校验位后必然自洽」那条反向构造来保证）
        assertEquals(4, ChecksumVerifier.computeMod10CheckDigit("999999999999"))
        for (seed in 0..99) {
            val body = (seed.toString().repeat(6)).take(12).padEnd(12, '7')
            val digit = ChecksumVerifier.computeMod10CheckDigit(body)
            assertTrue("校验位 $digit 越界", digit in 0..9)
        }
    }

    /**
     * 反向构造：随便挑一批 12 位数字，把算出来的校验位补上去，必须判 VALID。
     *
     * 这条能抓住「计算」与「验证」两侧用了不同权重顺序的错误——
     * 单独看任何一个方向都可能自洽，只有拼起来才能发现。
     */
    @Test
    fun `补上算出的校验位后必然自洽`() {
        val bodies = listOf(
            "123456789012", "000000000000", "999999999999",
            "400638133393", "590123412345", "111111111111"
        )
        bodies.forEach { body ->
            val full = body + ChecksumVerifier.computeMod10CheckDigit(body)
            assertEquals("$full 应当自洽", CheckResult.VALID, ChecksumVerifier.verify("EAN-13", full))
        }
    }

    // ─────────────────────────── 白名单 ───────────────────────────

    @Test
    fun `支持校验的码制白名单与实现一致`() {
        listOf("EAN-13", "EAN-8", "UPC-A", "UPC-E").forEach {
            assertTrue("$it 应当被标记为可校验", ChecksumVerifier.supportsChecksum(it))
        }
        listOf("QR Code", "Data Matrix", "Code-128", "Code-39", "PDF417").forEach {
            assertFalse("$it 不应被标记为可校验", ChecksumVerifier.supportsChecksum(it))
        }
    }

    /**
     * 白名单与实际校验能力必须同步：不能出现「supportsChecksum 说支持、
     * 但 verify 永远返回 NOT_APPLICABLE」这种自相矛盾。
     */
    @Test
    fun `白名单里的码制在正确输入下确实会给出结论`() {
        val samples = mapOf(
            "EAN-13" to "4006381333931",
            "EAN-8" to "96385074",
            "UPC-A" to "036000291452",
            "UPC-E" to "01234565"
        )
        samples.forEach { (format, value) ->
            assertTrue("$format 应当被声明为可校验", ChecksumVerifier.supportsChecksum(format))
            assertEquals(
                "$format 的合法码应当给出 VALID 而不是 NOT_APPLICABLE",
                CheckResult.VALID,
                ChecksumVerifier.verify(format, value)
            )
        }
    }

    @Test
    fun `码制名大小写与空格变化不会被误认为可校验`() {
        // formatName 来自内部映射，理论上不会变；这里锁住「不做模糊匹配」的意图，
        // 避免以后有人加上 trim/lowercase 后把 "EAN-13 " 之类的脏值放进来乱判
        assertEquals(CheckResult.NOT_APPLICABLE, ChecksumVerifier.verify("ean-13", "4006381333931"))
        assertEquals(CheckResult.NOT_APPLICABLE, ChecksumVerifier.verify("EAN-13 ", "4006381333931"))
        assertEquals(CheckResult.NOT_APPLICABLE, ChecksumVerifier.verify("EAN13", "4006381333931"))
    }
}

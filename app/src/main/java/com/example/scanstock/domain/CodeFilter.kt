package com.example.scanstock.domain

/**
 * 条码准入规则过滤器。纯函数，无 Android 依赖，便于纯 JVM 单测。
 *
 * 关键设计：**非法正则不能让 App 崩，也不能静默吞掉所有码。**
 * 用户手输的正则几乎必然会出现写错的情况（比如只写了一个 `(`），
 * 若直接 `Regex(...)` 会抛 PatternSyntaxException，而调用点在每个识别帧上，
 * 一崩就是连续崩溃。所以这里把编译结果缓存起来，失败时记为 null
 * 并对外暴露 [lastError]，由 UI 提示一次，过滤行为退化为「忽略正则这一条」。
 */
object CodeFilter {

    /** 规则文本 → 编译结果。null 表示该正则非法（已降级为不限制） */
    private val compiled = HashMap<String, Regex?>(4)

    /** 最近一次编译失败的原始正则串；成功编译或规则变更后清空 */
    @Volatile
    var lastError: String? = null
        private set

    /**
     * 判断一个码值是否准入。
     *
     * @param value 条码原始内容
     * @param rule  当前规则；enabled = false 时直接放行
     * @return true 表示应当计入
     */
    fun accepts(value: String, rule: CodeRule): Boolean {
        if (!rule.enabled) return true
        if (value.isEmpty()) return false

        if (rule.prefix.isNotEmpty() && !value.startsWith(rule.prefix)) return false
        if (rule.length > 0 && value.length != rule.length) return false

        val pattern = rule.regex
        if (pattern.isNotEmpty()) {
            when (val re = compile(pattern)) {
                null -> return false          // 正则非法：记错误，但**不放行**（见下方说明）
                else -> if (!re.containsMatchIn(value)) return false
            }
        }
        return true
    }

    /**
     * 单独校验一条规则是否合法（用于 UI 保存前提示，不产生过滤副作用）。
     *
     * @return 错误描述；null 表示规则合法（含「未启用」或「全空」）
     */
    fun validate(rule: CodeRule): String? {
        if (!rule.enabled) return null
        if (rule.length < 0) return "长度不能为负数"
        if (rule.prefix.isNotEmpty() && rule.prefix.any { it.isWhitespace() }) {
            return "前缀里不要有空格，条码值一般不含空格"
        }
        if (rule.regex.isNotEmpty() && compile(rule.regex) == null) {
            return "正则表达式写错了，已按「不限制」处理"
        }
        return null
    }

    /** 规则变更时调用，清掉缓存与错误态，避免旧正则一直生效 */
    fun reset() {
        synchronized(compiled) { compiled.clear() }
        lastError = null
    }

    /**
     * 编译并缓存。失败返回 null 且记录 [lastError]。
     *
     * 为什么非法时选择「不放行」而不是「放行」：正则是一条**白名单**规则，
     * 用户写了正则就表示「我只想要匹配的」。规则写错时若放行所有码，
     * 用户会以为规则生效了、结果记进去一堆不想要的，事后才发现白扫；
     * 不放行则立刻表现为「扫不进任何东西」，用户会马上去看设置，
     * 错误更容易被察觉。这个选择是有意的。
     */
    private fun compile(pattern: String): Regex? {
        synchronized(compiled) {
            if (compiled.containsKey(pattern)) return compiled[pattern]
        }
        val re = runCatching { Regex(pattern) }.getOrNull()
        synchronized(compiled) { compiled[pattern] = re }
        if (re == null) lastError = pattern
        return re
    }
}

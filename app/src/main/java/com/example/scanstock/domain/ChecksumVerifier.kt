package com.example.scanstock.domain

/**
 * 条码校验位自校验（纯逻辑，零 Android 依赖，可 JVM 单测）。
 *
 * ## 为什么需要它
 *
 * 用户诉求里的「提高识别**准确率**」在盘点场景下比「识别率」更要紧：
 * 漏识补扫一次就行，**误识会把错误数据直接写进库存**。
 *
 * 而免费方案确实会误识——ML Kit 在密集 1D 码上的误识并非罕见，
 * 典型是一两位数字被解错（1↔7、6↔8、0↔8 这类形近误判）。
 * 好消息是：**EAN / UPC 系列自带校验位**，用一位加权和就能把绝大多数误识挡掉，
 * 代价是纯算术、零依赖、可完全单测。
 *
 * ## 能不能校验，取决于码制本身
 *
 * | 码制 | 校验方式 | 本类是否实现 |
 * |---|---|---|
 * | EAN-13 | mod-10 加权（12 位数据 + 1 位校验） | 已校验 |
 * | EAN-8 | mod-10 加权（7 + 1） | 已校验 |
 * | UPC-A | mod-10 加权（11 + 1） | 已校验 |
 * | UPC-E | mod-10 加权（7 + 1，压缩形式的校验位与展开形式一致） | 已校验 |
 * | Code-128 | mod-103，**校验符在解码时已被消耗** | 不校验，见下 |
 * | Code-39 | mod-43，且校验位是**可选**的 | 不校验，见下 |
 * | QR / Data Matrix | 内建 Reed-Solomon 纠错 | 不校验，库已保证 |
 *
 * ### 为什么 Code-128 不做
 *
 * Code-128 的校验是 `(起始符 + Σ i×符号值) mod 103`，其中**符号值**是编码时的
 * Code A/B/C 子集值，而 ML Kit 只给我们**解码后的文本**——校验符已在校验过程中被吃掉，
 * 子集信息也丢了。想验就必须先「重新编码」，但纯数字串用 Code B 和 Code C 编出来的
 * 符号序列完全不同、校验位也不同，无法反推。
 *
 * 硬做只能「把 B 和 C 都试一遍，有一个对上就算过」——那会把检出能力削掉一大半，
 * 却换来一堆「看起来验过了」的假安全感。**不如不做**：返回 [CheckResult.NOT_APPLICABLE]
 * 明确放行，而不是假装校验过。
 *
 * Code-39 同理：它用的是**可选**校验位，多数实现根本不写，验不了。
 */
object ChecksumVerifier {

    /**
     * 校验结果。
     *
     * 刻意分三态而不是布尔值：`NOT_APPLICABLE`（没法验）与 `VALID`（验过且通过）
     * 在调用方看来都是「放行」，但**语义完全不同**——混成一个 `true` 会让人误以为
     * 「所有进来的码都验过了」，将来排查误识问题时会被这个假象带偏。
     */
    enum class CheckResult {
        /** 校验位算出来和实际一致 */
        VALID,

        /** 校验位对不上：几乎可以断定是解错了，应当丢弃 */
        INVALID,

        /** 该码制没有可校验的校验位，或格式不满足校验前提（如长度不对、含非数字） */
        NOT_APPLICABLE
    }

    /**
     * 需要校验的码制名。取值必须与 [com.example.scanstock.camera.BarcodeFormats.nameOf] 一致。
     *
     * 这里用字符串而非 ML Kit 的 format 常量，是为了让 domain 层保持零 Android / 零 ML Kit 依赖
     * ——这是 `DetectedCode` 定下的架构约定，换了识别库这一层不用改。
     */
    private const val FORMAT_EAN_13 = "EAN-13"
    private const val FORMAT_EAN_8 = "EAN-8"
    private const val FORMAT_UPC_A = "UPC-A"
    private const val FORMAT_UPC_E = "UPC-E"

    /** 各码制的「数据位 + 校验位」总长度 */
    private val EXPECTED_LENGTH: Map<String, Int> = mapOf(
        FORMAT_EAN_13 to 13,
        FORMAT_EAN_8 to 8,
        FORMAT_UPC_A to 12,
        FORMAT_UPC_E to 8
    )

    /**
     * 校验一个码值。
     *
     * @param formatName `DetectedCode.formatName`，例如 `"EAN-13"`
     * @param value      `DetectedCode.value`（ML Kit 的 rawValue 原值）
     */
    fun verify(formatName: String, value: String): CheckResult {
        val expectedLength = EXPECTED_LENGTH[formatName] ?: return CheckResult.NOT_APPLICABLE

        // 长度不对就别硬算了。这不代表码是错的——可能上游改过裁剪逻辑，
        // 或者这个格式的变体（如 EAN-13 前面补了 0），一律交还给调用方自行决定。
        if (value.length != expectedLength) return CheckResult.NOT_APPLICABLE

        // 非数字同样不判错：宁可放过，不可错杀。
        // （EAN/UPC 规范上必须是数字，真出现非数字说明上游有问题，不该在这里静默吞掉。）
        if (!value.all { it in '0'..'9' }) return CheckResult.NOT_APPLICABLE

        val expectedCheckDigit = value.last() - '0'
        val actualCheckDigit = computeMod10CheckDigit(value.substring(0, value.length - 1))
        return if (expectedCheckDigit == actualCheckDigit) {
            CheckResult.VALID
        } else {
            CheckResult.INVALID
        }
    }

    /**
     * 计算 EAN / UPC 系列的 mod-10 校验位。
     *
     * ## 权重规则（四种码制共用一个公式）
     *
     * 标准描述是「**从右往左**数，第 1、3、5… 位权重 3，第 2、4、6… 位权重 1」。
     * 换算成 0-based 的下标 `i`、数据位长度 `n`，从右数是第 `n - i` 位，于是：
     *
     * ```
     * 权重 = if ((n - i) % 2 == 1) 3 else 1
     * ```
     *
     * 这一个式子在四种码制上自洽，逐个数一遍验证：
     *
     * - **EAN-13**（n=12）：最右 i=11 → k=1 → 权重 3 ✓
     * - **EAN-8**（n=7）：最右 i=6 → k=1 → 权重 3 ✓
     * - **UPC-A**（n=11）：最右 i=10 → k=1 → 权重 3 ✓
     * - **UPC-E**（n=7）：最右 i=6 → k=1 → 权重 3 ✓
     *
     * 凭这一点就不必为四种码制各写一份、各测一遍。
     *
     * @param body 不含校验位的数据位，必须全是数字
     * @return 0..9 的校验位
     */
    internal fun computeMod10CheckDigit(body: String): Int {
        var sum = 0
        val n = body.length
        for (i in 0 until n) {
            val digit = body[i] - '0'
            val weight = if ((n - i) % 2 == 1) 3 else 1
            sum += digit * weight
        }
        return (10 - sum % 10) % 10
    }

    /**
     * 该码制是否**具备**可校验的校验位（不判断这一条具体对不对）。
     *
     * 供设置面板展示「已覆盖 / 未覆盖」用，也是 [verify] 返回
     * [CheckResult.NOT_APPLICABLE] 的判据来源，避免两处各写一份白名单。
     */
    fun supportsChecksum(formatName: String): Boolean = EXPECTED_LENGTH.containsKey(formatName)
}

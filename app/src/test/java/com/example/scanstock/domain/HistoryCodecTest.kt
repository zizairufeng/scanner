package com.example.scanstock.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [HistoryCodec] 的行编解码：坏行必须返回 null 而不是抛异常，正常行必须逐字节还原 */
class HistoryCodecTest {

    private fun record(
        value: String = "6901234567892",
        formatName: String = "EAN-13",
        kind: RecordKind = RecordKind.NEW,
        session: String = "mh3k2p1",
        source: RecordSource = RecordSource.CAMERA,
        timeMillis: Long = 1_762_912_912_345L
    ) = ScanRecord(timeMillis, kind, 256, formatName, value, session, source)

    @Test
    fun `普通记录往返一致`() {
        val origin = record()
        val decoded = HistoryCodec.decode(HistoryCodec.encode(origin))
        assertEquals(origin, decoded)
    }

    @Test
    fun `含制表符换行与反斜杠的码值往返一致`() {
        // 二维码里塞换行、Code-128 编进制表符都真实存在；不转义会把一行裂成多行，
        // 整个文件从此读出错位数据（不可逆）
        val nasty = "A\tB\nC\rD\\E"
        val origin = record(value = nasty, formatName = "Code-\t128")
        val encoded = HistoryCodec.encode(origin)
        assertTrue("编码后不能含裸换行：$encoded", !encoded.contains('\n'))
        assertEquals(origin, HistoryCodec.decode(encoded))
    }

    @Test
    fun `版本头与空行不是记录`() {
        assertNull(HistoryCodec.decode(HistoryCodec.HEADER))
        assertNull(HistoryCodec.decode(""))
    }

    @Test
    fun `字段数不对的行返回 null`() {
        assertNull(HistoryCodec.decode("1762912912345\tNEW\t256\tEAN-13"))
    }

    @Test
    fun `时间戳不合法返回 null`() {
        assertNull(HistoryCodec.decode("abc\tNEW\t256\tEAN-13\t690\tmh3k2p1\tCAMERA"))
        assertNull(HistoryCodec.decode("0\tNEW\t256\tEAN-13\t690\tmh3k2p1\tCAMERA"))
    }

    @Test
    fun `不认识的类型返回 null`() {
        assertNull(HistoryCodec.decode("1762912912345\tWHATEVER\t256\tEAN-13\t690\tmh3k2p1\tCAMERA"))
    }

    @Test
    fun `空码值返回 null`() {
        assertNull(HistoryCodec.decode("1762912912345\tNEW\t256\tEAN-13\t\tmh3k2p1\tCAMERA"))
    }

    @Test
    fun `来源不认识时退化为相机而不是丢整行`() {
        // 来源只是附加信息，为了它丢掉一条真实扫描记录不值得
        val decoded = HistoryCodec.decode("1762912912345\tDUP\t256\tEAN-13\t690\tmh3k2p1\tNOPE")
        assertEquals(RecordSource.CAMERA, decoded?.source)
        assertEquals(RecordKind.DUP, decoded?.kind)
    }
}

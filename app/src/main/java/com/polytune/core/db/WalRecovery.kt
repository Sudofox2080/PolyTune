package com.polytune.core.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.util.Log
import java.io.File
import java.io.RandomAccessFile

/**
 * WAL 自检与兜底恢复（只处理「salt 分代」这一种已被实测确认的情形）。
 *
 * 背景：Room 走 WAL 模式，写入先落在 -wal 文件里。SQLite 打开库时会校验 WAL 头 salt 与
 * 逐帧滚动校验和，对不上就认为这份 WAL 是「别的连接留下的、已经作废的一代」，直接忽略。
 * 忽略本身是对的，但数据就留在了 WAL 里没人管——查询恒返回空，界面上就是「还没有下载任务」。
 * 本次真机的现场就是这样：主库 15 页完整但空，两条任务全在 WAL 的第 0–57 帧里，
 * 而第 58 帧起 salt 从 3f855902/2e12d47b 跳成 3f855901/42752225（且这 42 帧的校验和全部对不上），
 * SQLite 因此整份 WAL 作废。
 *
 * 设计原则是可证伪优先于聪明：只有当「帧序列中途 salt 发生分代跳变」时才动手，
 * 其余情况一律原样返回，连主库都不读。改动面越窄，误伤面越接近零。
 *
 * 具体做三件事：
 * 1. 前置门禁：只读 WAL 头与各帧帧头，确认存在 salt 分代跳变，否则立即 return。
 * 2. 严格按 SQLite 的滚动校验和走帧，校验失败立刻停；只在 commit != 0 的帧处认账，
 *    未提交的事务尾巴整段丢弃（绝不把半个事务写进主库）。
 * 3. 只回放「最后一代」——也就是 SQLite 本来会接受的那一代——之外没被接受的帧？
 *    不回放：回放的是「被作废那一代里校验和通过、且落在完整提交点之前」的帧，
 *    这正是我们要捞的数据；由于第一代的提交链在跳变前是完整的，回放结果与原库一致。
 *
 * 收尾交给 SQLite 自己：TRUNCATE checkpoint + integrity_check。
 */
internal object WalRecovery {

    private const val TAG = "WalRecovery"

    /** WAL 头 magic。低位置 1 表示校验和按大端读 32 位字。 */
    private const val WAL_MAGIC_LE = 0x377f0682
    private const val WAL_MAGIC_BE = 0x377f0683
    private const val WAL_HEADER = 32
    private const val FRAME_HEADER = 24

    fun repairIfNeeded(ctx: Context, dbName: String) {
        val db = ctx.getDatabasePath(dbName)
        val wal = File(db.absolutePath + "-wal")
        val shm = File(db.absolutePath + "-shm")
        if (!db.exists() || !wal.exists() || wal.length() <= WAL_HEADER.toLong()) return

        val walLen = wal.length()
        RandomAccessFile(wal, "r").use { raf ->
            // ---- 1. 只读头，先做门禁 ----
            val hdr = ByteArray(WAL_HEADER)
            raf.readFully(hdr)
            val magic = beInt(hdr, 0)
            if (magic != WAL_MAGIC_LE && magic != WAL_MAGIC_BE) return
            val bigEndian = magic == WAL_MAGIC_BE
            val pageSize = beInt(hdr, 8)
            if (pageSize < 512 || pageSize > 65536 || Integer.bitCount(pageSize) != 1) return
            val salt0 = beInt(hdr, 16)
            val salt1 = beInt(hdr, 20)

            val frameLen = FRAME_HEADER.toLong() + pageSize
            val frameCount = ((walLen - WAL_HEADER) / frameLen).toInt()
            if (frameCount <= 0) return

            // 只读每帧 24 字节帧头，判断有没有分代跳变。
            val heads = arrayOfNulls<IntArray>(frameCount) // [pgno, commit, salt0, salt1]
            var generationBreak = -1
            val fh = ByteArray(FRAME_HEADER)
            for (i in 0 until frameCount) {
                raf.seek(WAL_HEADER + i * frameLen)
                raf.readFully(fh)
                val pgno = beInt(fh, 0)
                heads[i] = intArrayOf(pgno, beInt(fh, 4), beInt(fh, 8), beInt(fh, 12))
                if (generationBreak < 0 && (pgno == 0 || heads[i]!![2] != salt0 || heads[i]!![3] != salt1)) {
                    generationBreak = i
                }
            }
            // 门禁：没有分代跳变 → 这是正常 WAL，SQLite 自己能处理，什么都不做。
            // 头帧就断（generationBreak == 0）也说明 WAL 已经整份作废，没有可捞的完整代，同样不动。
            if (generationBreak <= 0) return

            val usable = generationBreak // 第一代帧数
            val mainLen = db.length()
            if (mainLen < 100) return
            val mainPageSize = readPageSize(db)

            // ---- 2. 对第一代按滚动校验和收帧，只在提交点认账 ----
            val walBytes = ByteArray((walLen).toInt())
            raf.seek(0)
            raf.readFully(walBytes)

            var s0 = beInt(hdr, 24)
            var s1 = beInt(hdr, 28)
            // 用文件里存的头部校验和做一次自证：头都不对就不该相信后面的帧。
            val h0 = s0
            val h1 = s1
            val (hs0, hs1) = checksum(walBytes, 0, 0, 24, 0, 0, bigEndian)
            if (hs0 != h0 || hs1 != h1) return

            // pending：本轮事务累积的 (pgno, 帧体偏移)；一旦遇到 commit != 0 就整体转入 apply。
            val pending = ArrayList<IntArray>()
            val apply = ArrayList<IntArray>()
            for (i in 0 until usable) {
                val off = (WAL_HEADER + i * frameLen).toInt() // frameCount 已由文件长度界过，不会溢出 Int
                val head = heads[i]!!
                if (head[2] != salt0 || head[3] != salt1) break
                val acc = checksum(
                    walBytes,
                    s0,
                    s1,
                    FRAME_HEADER,
                    off,
                    pageSize,
                    bigEndian,
                )
                val calc0 = acc[0]
                val calc1 = acc[1]
                val stored0 = beInt(walBytes, off + 16)
                val stored1 = beInt(walBytes, off + 20)
                if (calc0 != stored0 || calc1 != stored1) break // 校验失败：这一帧之后一律不信
                s0 = calc0
                s1 = calc1
                pending.add(intArrayOf(head[0], off + FRAME_HEADER))
                if (head[1] != 0) {
                    // 提交点：本轮事务到此完整，全部转正。
                    apply.addAll(pending)
                    pending.clear()
                }
            }
            // 未提交的尾巴直接丢掉，绝不写进主库。
            pending.clear()
            if (apply.isEmpty()) return

            // ---- 3. 流式比较 + 原地回放 ----
            val patchedPages = HashMap<Int, Int>() // pgno -> 帧体偏移（后面的覆盖前面的）
            for (f in apply) patchedPages[f[0]] = f[1]

            val tmp = File(db.absolutePath + ".recover")
            var changed = false
            RandomAccessFile(tmp, "rw").use { out ->
                out.setLength(0)
                var pageNo = 1
                val buf = ByteArray(mainPageSize)
                raf.seek(0)
                var read = 0L
                while (read < mainLen) {
                    val want = minOf(mainPageSize, (mainLen - read).toInt())
                    raf.seek(read)
                    raf.readFully(buf, 0, want)
                    val frameBody = patchedPages[pageNo]
                    if (pageSize == mainPageSize && frameBody != null && want == pageSize) {
                        val nb = ByteArray(pageSize)
                        System.arraycopy(walBytes, frameBody, nb, 0, pageSize)
                        if (!nb.contentEquals(buf)) changed = true
                        out.write(nb)
                    } else {
                        out.write(buf, 0, want)
                    }
                    read += want
                    pageNo++
                }
                out.fd.sync()
            }

            if (changed) {
                wal.delete()
                shm.delete()
                if (!tmp.renameTo(db)) {
                    tmp.copyTo(db, overwrite = true)
                    tmp.delete()
                }
                Log.w(TAG, "WAL 分代跳变@$generationBreak，已回放 ${apply.size} 帧到主库")
            } else {
                tmp.delete()
                // 内容一致说明主库已经是终态，WAL 只是残留，直接丢弃。
                wal.delete()
                shm.delete()
            }
        }

        // 回到 SQLite 手里再让它自己对账：TRUNCATE checkpoint + 完整性检查。
        // 注意 SQLiteDatabase 与 Cursor 都没有实现 Closeable，不能用 use {}，只能手动关。
        var sdb: SQLiteDatabase? = null
        try {
            sdb = SQLiteDatabase.openDatabase(db.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
            sdb.rawQuery("PRAGMA integrity_check", null).use { c ->
                if (c.moveToFirst()) Log.i(TAG, "integrity_check=${c.getString(0)}")
            }
            sdb.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { c -> c.moveToFirst() }
        } catch (e: SQLiteException) {
            Log.w(TAG, "恢复后自检异常 ${e.message}")
        } finally {
            runCatching { sdb?.close() }
        }
    }

    /**
     * SQLite 的滚动校验和。按 [bigEndian] 读 32 位字，每两个一组：
     * s0 += x + s1; s1 += y + s0。顺序不能反，反了就和 SQLite 对不上。
     *
     * @param headLen 从 [headOff] 起的帧头参与长度（WAL 头是 24，帧头是 8）
     * @param bodyLen 紧跟在帧头之后的页数据长度（WAL 头没有，传 0）
     */
    private fun checksum(
        buf: ByteArray,
        s0In: Int,
        s1In: Int,
        headLen: Int,
        headOff: Int,
        bodyLen: Int,
        bigEndian: Boolean,
    ): IntArray {
        var s0 = s0In
        var s1 = s1In
        var r = accum(buf, headOff, headOff + headLen, s0, s1, bigEndian)
        s0 = r[0]
        s1 = r[1]
        if (bodyLen > 0) {
            val bodyOff = headOff + headLen
            r = accum(buf, bodyOff, bodyOff + bodyLen, s0, s1, bigEndian)
            s0 = r[0]
            s1 = r[1]
        }
        return intArrayOf(s0, s1)
    }

    private fun accum(buf: ByteArray, from: Int, to: Int, s0In: Int, s1In: Int, bigEndian: Boolean): IntArray {
        var s0 = s0In
        var s1 = s1In
        var i = from
        while (i + 8 <= to) {
            val x = word(buf, i, bigEndian)
            val y = word(buf, i + 4, bigEndian)
            s0 += x + s1
            s1 += y + s0
            i += 8
        }
        return intArrayOf(s0, s1)
    }

    private fun word(b: ByteArray, o: Int, bigEndian: Boolean): Int = if (bigEndian) {
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)
    } else {
        ((b[o + 3].toInt() and 0xFF) shl 24) or ((b[o + 2].toInt() and 0xFF) shl 16) or
            ((b[o + 1].toInt() and 0xFF) shl 8) or (b[o].toInt() and 0xFF)
    }

    private fun beInt(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    /** 页大小在 SQLite 头偏移 16（2 字节大端），值 1 表示 65536。 */
    private fun readPageSize(db: File): Int = RandomAccessFile(db, "r").use { raf ->
        val b = ByteArray(2)
        raf.seek(16)
        raf.readFully(b)
        val v = ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
        if (v == 1) 65536 else v
    }
}

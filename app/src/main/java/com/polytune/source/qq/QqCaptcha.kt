package com.polytune.source.qq

/**
 * ptlogin2 的图形验证码挑战。
 *
 * 之所以把图片字节也一起带出来，是因为验证码地址一次性失效：
 * 界面上如果先拿 URL 去 AsyncImage 再下载，用户看到的图和这次会话的
 * cap_cd 可能对不上，填对了也会被服务端判错。字节随挑战一起落下来最稳。
 *
 * [password] 保存的是 sha1(明文).upper() + 手机号 的摘要，不是明文证书；
 * 第二次握手必须用同一个摘要，否则服务端会当成另一个设备的尝试重新要图。
 */
data class CaptchaChallenge(
    val imageUrl: String,
    val capCd: String,
    val verifySession: String,
    val imageBytes: ByteArray,
    val phone: String,
    val password: String,
) {
    // data class 里有 ByteArray 必须手写这两个，否则每次比较都用引用
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CaptchaChallenge) return false
        return imageUrl == other.imageUrl && capCd == other.capCd &&
            verifySession == other.verifySession && phone == other.phone &&
            password == other.password && imageBytes.contentEquals(other.imageBytes)
    }

    override fun hashCode(): Int {
        var r = imageUrl.hashCode()
        r = 31 * r + capCd.hashCode()
        r = 31 * r + verifySession.hashCode()
        r = 31 * r + phone.hashCode()
        r = 31 * r + password.hashCode()
        r = 31 * r + imageBytes.contentHashCode()
        return r
    }
}

/** ptuiCB('code','sub','url','0','msg','nick') 的结构化形式。 */
data class PtuiFields(
    val code: Int,
    val url: String,
    val message: String,
    val nick: String,
)

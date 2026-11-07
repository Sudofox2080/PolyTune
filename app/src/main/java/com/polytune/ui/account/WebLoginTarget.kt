package com.polytune.ui.account

import com.polytune.source.api.Platform

/**
 * 网页登录的落点配置。每个源给一个官方登录页，
 * 登录成功后从 WebView 的 CookieManager 里按 host 把 cookie 抓回来，
 * 再交给对应源的 CookieImport 分支解析成内部凭证。
 *
 * 这里刻意只放「登录页」而不是网页版播放页：
 * 用户名密码走的是源自己的网页表单，验证码、滑块、风控全由源自己在网页里处理，
 * 应用只负责把最终生成的会话 cookie 取回来，不介入任何凭据校验逻辑。
 */
data class WebLoginTarget(
    val platform: Platform,
    val loginUrl: String,
    /** 判定登录成功用的 cookie 名，命中任一即可。 */
    val cookieKeys: List<String>,
    /** 抓取 cookie 时按这些后缀聚合，顺序决定优先取哪个域的。 */
    val cookieHosts: List<String>,
    /** 抓到的 cookie 交给源解析前，界面上给的提示。 */
    val doneHint: String,
) {
    fun looksLoggedIn(cookie: String): Boolean {
        if (cookie.isBlank()) return false
        return cookieKeys.any { key ->
            // 形如 "a=1; qqmusic_key=xxx; b=2" 里精确匹配键名，避免 qm_keyst 命中 qm_keyst_xxx
            cookie.split(';').any { part ->
                val name = part.substringBefore('=', "").trim()
                name == key
            }
        }
    }

    companion object {
        /**
         * 该源有没有可用的网页登录落点。QQ 返回 null：它的网页登录已撤掉，
         * 原因是 y.qq.com 的登录按钮在 WebView 里只会调起 wtloginmqq://（QQ 客户端），
         * 而 ptlogin2 的纯网页通道在缺装客户端时走不通、短信通道又被图形验证码挡住。
         */
        fun ofOrNull(platform: Platform): WebLoginTarget? = when (platform) {
            Platform.QQ -> of(platform)
            else -> of(platform)
        }

        fun of(platform: Platform): WebLoginTarget = when (platform) {
            Platform.QQ -> WebLoginTarget(
                platform = platform,
                /*
                 * QQ 的网页登录已不对外暴露（见 ofOrNull），这里保留一份可用的落点，
                 * 供以后在设备装了 QQ 客户端、或图形验证码有解时重新挂上。
                 * 选 xlogin 而不是 loginpage：后者缺 pt_ptlogin 参数时会把用户
                 * 直接拐到腾讯网首页，连登录框都看不到。
                 */
                loginUrl = "https://xui.ptlogin2.qq.com/cgi-bin/xlogin?appid=716027609" +
                    "&daid=383&style=34&s_url=https%3A%2F%2Fy.qq.com%2Fportal%2Fplayer.html" +
                    "&pt_3rd_aid=100497308&hide_title_bar=1&hide_border=1&self_regurl=&target=top" +
                    "&pt_feedback_link=&ucheck=1",
                cookieKeys = listOf("qqmusic_key", "qm_keyst", "wxuin", "uin", "pt4_token", "p_skey"),
                cookieHosts = listOf("y.qq.com", "qq.com", "ptlogin2.qq.com"),
                doneHint = "在网页里用 QQ 号+密码或短信登录，页面跳到 QQ 音乐并显示头像后点「已登录，读取凭证」。",
            )

            Platform.NETEASE -> WebLoginTarget(
                platform = platform,
                loginUrl = "https://music.163.com/#/login",
                cookieKeys = listOf("MUSIC_U"),
                cookieHosts = listOf("music.163.com", "163.com"),
                doneHint = "在网页里用手机号或邮箱登录，页面出现你的昵称即视为成功。",
            )

            Platform.KUGOU -> WebLoginTarget(
                platform = platform,
                /*
                 * 酷狗的站点 cookie 落在多个子域，www.kugou.com 首页登录后
                 * 票据写在 passport.kugou.com 与 login-user.kugou.com 上，
                 * 所以直接进通行证登录页，跳转后目标域必定带上 KuGoo/token。
                 */
                loginUrl = "https://login.kugou.com/login/index.html",
                // kg_mid / kg_dfid 是设备指纹，任何访客都有，拿它判登录会误报；
                // 只认真正代表账号会话的 KuGoo、token、kg_uid、userid。
                cookieKeys = listOf("KuGoo", "kugoou", "token", "kg_uid", "userid"),
                cookieHosts = listOf("kugou.com", "login-user.kugou.com", "passport.kugou.com"),
                doneHint = "在网页右上角点头像登录酷狗账号，登录成功后回到这里点「已登录，读取凭证」。",
            )
        }
    }
}

# LopMusic

一个使用 Jetpack Compose 与 Material 3 Expressive 风格编写的 Android 本地音乐播放器，把 QQ 音乐、网易云音乐、酷狗音乐聚合成一个曲库。登录自己的账号后，可以直接搜索、试听、播放与下载自己有权访问的内容。

## 功能

- 多源聚合搜索：一次输入，同时返回 QQ 音乐、网易云音乐、酷狗音乐的结果，并按歌曲合并成组，展示不同平台版本。
- 多源播放：当前源没有可用直链时自动按音质档位降级并尝试其它源。
- 逐行歌词：当前行放大加粗，相邻行淡出；点击歌词跳转，支持翻译与罗马音，歌词随播放自动滚动。
- 播放页双页：信息页封面在左、标题与歌手在右；点「查看歌词」或左滑文字进入歌词页，右滑返回；封面左右滑切歌，信息页下滑收起。歌词页纵向滚动独立，上滑不会误退出播放器。
- 播放队列：队列抽屉、上一首/下一首、自动连播、最近播放兜底。
- 快速起播：各音乐源并行解析直链，先拿到可用地址就先播，避免逐个源串行等待。
- 后台播放：基于 Media3 `MediaSessionService`，使用系统媒体通知与媒体控件。
- 在线歌单：登录后显示自己账号创建/收藏的歌单，同时保留三个平台的推荐歌单与歌单详情，可整张加入队列播放。
- 离线歌单：下载完成后出现在本地列表，下载时会把歌词保存为同名 `.lrc.json` 侧车文件，离线播放不再依赖网络。
- 试听判定：以实际播放时长与曲目声明时长对比，不单凭接口的 `freeTrialInfo` 误标整首为试听。
- 下载：Room + WorkManager 断点续传，支持暂停、继续、失败重试与换源重试。
- 多协议：HTTP/HTTPS、HLS、DASH、本地文件、WebDAV、SMB（通过本地代理）。
- Material 3 Expressive：动态取色、形状变形加载动画、banner、转场与弹簧动效。

## 账号说明

QQ 音乐、网易云音乐、酷狗音乐均使用各自的官方登录通道，登录的是**你自己的账号**。应用只读取账号本身有权访问的播放地址，不做会员破解、不绕过付费或版权限制。因此：

- 没有会员的账号，会员曲目只会返回试听片段或没有可用地址。
- 接口与直链由各平台控制，随时可能变化。
- 下载内容仅供个人备份使用，请遵守各平台服务条款与当地法律。

## 构建

环境要求：JDK 21、Android SDK（compileSdk 37）、Gradle 9.3.1。

```bash
git clone <repo-url>
cd LopMusic
# 在 local.properties 写入 sdk.dir
./gradlew :app:assembleDebug
```

Windows：

```powershell
$env:JAVA_HOME="C:\Program Files\Java\jdk-21"
.\gradlew.bat :app:assembleDebug
```

产物位于 `app/build/outputs/apk/debug/app-debug.apk`。

## 结构

```
app/src/main/java/com/polytune/
├── core/        网络、数据库、下载、协议中间件、加密
├── source/      各音乐平台适配（qq / netease / kugou）与聚合器
├── player/      Media3 播放引擎、媒体会话、格式识别
└── ui/          Compose 界面（首页、搜索、歌单、下载、账号、播放、队列）
```

新增一个音乐源只需要实现 `source/api/MusicSource` 接口，并在 `PolyTuneApp` 注册到 `Aggregator`。

## 已知限制

- QQ 音乐非会员账号的多数曲目只返回试听或 `104003`，属于账号权限，不是客户端参数问题。
- 酷狗部分曲目的播放接口返回 `status:2`，会通过跨源兜底或降级处理。
- FFmpeg 扩展解码器未打包，WMA、APE、ALAC、MKA 等系统不支持的格式暂不能播放；MP3、AAC/M4A、FLAC、OGG/Vorbis、Opus、WAV、HLS、DASH 已验证。
- 各平台接口可能随时调整，登录态失效后需要在账号页重新登录。

## License

MIT

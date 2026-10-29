# :reader — 索引阅读器（Android）

独立模块：连接局域网内运行索引服务的电脑，搜索本地索引并阅读正文。不含任何 EhViewer / E-Hentai 代码。

## 服务端

电脑上运行 `FLServer`（见仓库外的 filelocator-web 项目）：

```
java -cp tools/lucene-core-3.0.3.jar;server/out FLServer "E:\index" 8765 web
```

启动后控制台打印 `LAN http://<ip>:8765/`，手机填这个地址。

## 客户端

- 首页：填服务器地址 → 连接（校验 `/api/meta`）。
- 搜索：关键词以空格分词，`-词` 排除；`与/或` 切换叠加方式；可限定 全部/正文/文件名、扩展名、目录；支持相关度/名称/大小/时间排序。
- 阅读：全屏正文，点按左右 30% 翻页、中间唤出工具栏；可调字号、字体、深/浅/纸三主题；进度条与百分比；自动记住每本书的位置。

## 接口

| 接口 | 用途 |
|---|---|
| `GET /api/meta` | 连接校验、索引统计 |
| `GET /api/search?t=..&x=..&op=and\|or&m=..&sort=..&order=..&ext=..&lookin=..` | 搜索 |
| `GET /api/read?path=..&page=..&q=..` | 按块读取正文 |

## 安装

APK 与 EhViewer 是两个独立应用（包名 `moe.tarsin.indexreader` vs `moe.tarsin.ehviewer`），可并存，互不覆盖。
CI 产物名为 `index-reader-apk`，解压得到 `index-reader-0.1.0.apk` 与 `README.txt`。

## 构建

```
./gradlew :reader:assembleDebug
```

需要 Android SDK 37 与 JDK 25。

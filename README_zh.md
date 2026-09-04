# XProxy

中文 | [English](README.md)

XProxy 是一个面向安全测试与流量分析的桌面工具，集成了 HTTP/WS 代理、History 浏览、Target 归档、Fuzzer 请求发送与批量攻击能力。

## 功能概览

- **Proxy**：拦截与转发 HTTP/WS 流量，支持规则化拦截、Match/Replace、上游代理。
- **HTTP History**：查看历史请求/响应，支持按 MIME 与关键字过滤。
- **Target**：对历史流量做站点树聚合与去重，便于快速定位接口。
- **Fuzzer**：单次发送、重放、自动跟随重定向、批量攻击。
- **Codec**：数据编解码工具箱，内置 20+ 种操作：
  - **数据格式**：Base64（标准/URL安全）、URL编码/解码、十六进制编码/解码、HTML编码/解码
  - **哈希算法**：MD5、SHA1、SHA256、SHA512、HMAC
  - **加密**：AES加密/解密（ECB/CBC模式）
  - **字符串**：ROT13、反转、大小写转换、去空格
  - **签名**：JWT payload 解码
- **Kits**：可扩展的插件与脚本生态系统：
  - **Xapp 插件**：基于 Python 的插件系统，支持生命周期管理（on_proxy_http_message、on_before_request、on_after_request）、被动扫描、请求/响应重写、上下文菜单集成。
  - **Xapp 商店**：浏览并安装来自 xapp-store 仓库的社区插件。
  - **Intruder 脚本**：管理和执行 Python 攻击脚本，支持分类、内置模板、持久化状态。
- **Settings**：主题、编码策略、TLS 证书导出与信任状态、响应渲染阈值配置。

## 截图

![主界面](images/01-project.png)

![Target](images/02-target.png)

![Proxy](images/03-proxy_1.png)

![Proxy](images/03-proxy_2.png)

![Fuzzer](images/04-fuzzer_1.png)

![Fuzzer](images/04-fuzzer_2.png)

![Codec](images/05-codec.png)

![Kits](images/06-kits.png)

![Settings](images/07-settings.png)

## 环境要求

- JDK 17+
- macOS（如需打包 DMG）

## 快速开始

### 1) 构建

```bash
./gradlew build
```

### 2) 运行 Fat JAR

```bash
./gradlew fatJar
java -jar build/libs/xproxy.jar
```

## 打包 DMG（macOS）

项目内置了 `build.sh`：

```bash
./build.sh
sudo xattr -cr /Applications/XProxy.app
```

该脚本会：

1. 构建 `fatJar`
2. 用 `jpackage` 先生成 app-image
3. 继续生成 `.dmg`

输出目录：`build/package/dmg`

## 数据与配置位置

- 全局数据库：`~/.xproxy/xproxy.db`
- 默认项目目录：`~/xproxy/projects`

## 技术栈

- Kotlin / Java (JVM 17)
- Swing + RSyntaxTextArea
- Netty + Proxyee
- SQLite

## 贡献者

- [@TheKingOfDuck](https://github.com/TheKingOfDuck)
- [@medi0cr1ty](https://github.com/Phelaine)

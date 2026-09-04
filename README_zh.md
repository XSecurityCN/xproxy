# XProxy

中文 | [English](README.md)

XProxy 是一个面向安全测试与流量分析的桌面工具，集成了 HTTP/WS 代理、History 浏览、Target 归档、Fuzzer 请求发送与批量攻击能力。

## 功能概览

- **Proxy**：拦截与转发 HTTP/WS 流量，支持规则化拦截、Match/Replace、上游代理。
- **HTTP History**：查看历史请求/响应，支持按 MIME 与关键字过滤。
- **Target**：对历史流量做站点树聚合与去重，便于快速定位接口。
- **Fuzzer**：单次发送、重放、自动跟随重定向、批量攻击。
- **Settings**：主题、编码策略、TLS 证书导出与信任状态、响应渲染阈值配置。

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

# X-Gate-AI · 统一大模型网关

[![Java](https://img.shields.io/badge/Java-17-007396)](https://www.oracle.com/java/technologies/downloads/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.4-6DB33F)](https://spring.io/projects/spring-boot)
[![MyBatis-Plus](https://img.shields.io/badge/MyBatis--Plus-3.5.11-0EA5E9)](https://baomidou.com/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue)](LICENSE)

> 面向 **OpenAI 兼容协议**的统一模型网关：把 DeepSeek、通义千问、OpenAI、vLLM 本地推理等零散的上游大模型服务收拢到一个固定 API 地址背后，通过 Web 控制台**动态调度、免重启切换**。

- 对外只暴露一套 **OpenAI 兼容 API**，工具 / 业务系统只需配置一个地址
- 上游的 URL、Key、模型名全部可在控制台动态维护，实时生效
- Java 服务与 Web 控制台**合并为单个可执行 jar**，开箱即用

---

## 目录

- [核心特性](#核心特性)
- [架构概览](#架构概览)
- [技术栈](#技术栈)
- [快速开始](#快速开始)
- [配置说明](#配置说明)
- [API 使用示例](#api-使用示例)
- [Web 控制台](#web-控制台)
- [数据存储](#数据存储)
- [项目结构](#项目结构)
- [License](#license)

---

## 核心特性

- **统一出口**：对外仅暴露 `/v1/chat/completions`、`/v1/embeddings`、`/v1/models`，协议与 OpenAI 完全兼容，OpenAI SDK、Cherry Studio、Dify、各类 Agent 框架可直接接入
- **流式透传**：`chat/completions` 基于 WebFlux 实现 SSE（`text/event-stream`）字节级原样转发，非流式则原样返回完整 JSON
- **通道与多上游**：一个对外模型名（通道）可绑定多个上游服务，按权重分配，默认轮询策略
- **故障转移**：上游调用失败自动切换到可用上游；失败上游进入冷却期，超时时间可配
- **零重启热切换**：新增 / 编辑 / 启停上游、调整通道绑定均在控制台完成，即时生效
- **调用观测**：记录每次调用的 Token（JTokkit 统计）、延迟、HTTP 状态，控制台提供日志查询与用量统计
- **轻量存储**：SQLite 单文件数据库，无外部依赖，首次启动自动建库建表；上游 Key 加密存储
- **自动清理**：调用日志按天滚动保留（默认 30 天），定时任务自动清理

## 架构概览

```
                工具 / 客户端（OpenAI SDK、Cherry Studio、Dify、Agent 框架）
                                    │
                                    ▼
                    对外统一 API（OpenAI 兼容）
              /v1/chat/completions  /v1/embeddings  /v1/models
                                    │
                    ┌───────────────┴───────────────┐
                    ▼                               ▼
          【对外 API + 路由调度】            【Web 控制台】
              轮询 / 故障转移 / 模型名映射       仪表盘 / 通道 / 服务 / 日志 / 用量
                    │                              （静态资源内嵌于 jar）
                    ▼
        ┌────────┼────────┬────────┐
        ▼        ▼        ▼        ▼
   DeepSeek  通义千问   OpenAI   vLLM 本地   ... 任意 OpenAI 兼容服务
```

核心链路：请求进入 → 按对外模型名查找通道 → 按权重轮询选择一个可用上游 → 动态构建客户端并指向上游 `baseUrl / apiKey / modelName` → 转发 → 流式透传 / JSON 返回。

## 技术栈

| 分类 | 选型 | 版本 |
|---|---|---|
| 语言 | Java | 17 |
| 框架 | Spring Boot | 3.4.4 |
| 反应式 | Spring WebFlux（SSE 流式转发） | 3.4.4 |
| 模型接入 | LangChain4j `open-ai` starter | 1.0.0-beta3 |
| 数据访问 | MyBatis-Plus（spring-boot3 starter） | 3.5.11 |
| 数据库 | SQLite（xerial jdbc） | 3.46.1.3 |
| 前端 | 静态页 + Vue 3（vue.global.prod.js，内嵌于 jar） | — |
| 工具库 | HuTool / Fastjson2 / JTokkit（token 统计）/ Lombok | 5.8.47 / 2.0.64 / 1.1.0 / 1.18.46 |

## 快速开始

### 环境要求

- JDK 17+
- Maven 3.6+（仅构建需要，运行只需 jar）

### 构建与运行

```bash
# 1. 打包（产物：target/x-gate-ai-1.0.0.jar）
mvn clean package -DskipTests

# 2. 运行
java -jar target/x-gate-ai-1.0.0.jar
```

启动完成后：

| 访问入口 | 地址 |
|---|---|
| Web 控制台 | <http://localhost:8090/> |
| 对外 API（OpenAI 兼容） | <http://localhost:8090/v1> |
| 数据库文件 | `./x-gate-ai.db`（jar 同级目录，首次启动自动创建） |

> 默认端口为 `8090`，可通过 `--server.port=8080` 或环境变量覆盖。

## 配置说明

配置文件位于 [src/main/resources/application.properties](src/main/resources/application.properties)，核心项：

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `server.port` | `8090` | 服务端口 |
| `spring.datasource.url` | `jdbc:sqlite:./x-gate-ai.db` | SQLite 数据文件位置 |
| `gateway.time-out-of-minutes` | `3` | 上游调用超时时间（分钟），用于耗时较长的生成任务 |
| `gateway.cool-down-seconds` | `30` | 失败上游冷却时间（秒），冷却期内不再分配 |
| `gateway.log-retention-days` | `30` | 调用日志保留天数，按天滚动清理 |
| `gateway.encrypt-key` | `xgate-ai-encrypt-2026` | 上游 api_key 加密密钥，**生产环境务必通过环境变量 `XGATE_ENCRYPT_KEY` 覆盖** |

## API 使用示例

### 对话（非流式）

```bash
curl http://localhost:8090/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "<对外模型名，如控制台配置的模型通道>",
    "messages": [{ "role": "user", "content": "你好，介绍一下你自己" }],
    "stream": false
  }'
```

### 对话（SSE 流式）

```bash
curl -N http://localhost:8090/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "<对外模型名>",
    "messages": [{ "role": "user", "content": "讲个故事" }],
    "stream": true
  }'
```

### 向量化 & 模型列表

```bash
curl http://localhost:8090/v1/embeddings \
  -H "Content-Type: application/json" \
  -d '{ "model": "<对外模型名>", "input": "你好" }'

curl http://localhost:8090/v1/models
```

### OpenAI SDK 接入

只需把 `base_url` 指向网关：

```python
from openai import OpenAI

client = OpenAI(
    base_url="http://localhost:8090/v1",  # 网关地址
    api_key="任意占位"                       # 网关对外未启用鉴权时可任意填
)

resp = client.chat.completions.create(
    model="<对外模型名>",
    messages=[{"role": "user", "content": "你好"}],
)
```

## Web 控制台

浏览器打开 <http://localhost:8090/> 即进入控制台，包含以下页面：

- **仪表盘**：关键指标概览与统计
- **模型通道**：维护对外暴露的模型名（public model）、启停状态、负载策略
- **上游服务**：维护各上游大模型的 `base_url` / `api_key`（加密存储）/ 模型名 / 启停
- **通道绑定**：为通道绑定多个上游并配置权重
- **调用日志**：按时间、模型、状态检索调用明细（Token、延迟、HTTP 状态）
- **用量统计**：Token 用量与调用量统计

## 数据存储

基于 SQLite 单文件（`./x-gate-ai.db`），建表脚本见 [schema.sql](src/main/resources/db/schema.sql)：

| 表 | 说明 |
|---|---|
| `upstream_providers` | 上游大模型服务（名称、base_url、api_key、模型名、启停、备注） |
| `model_channels` | 对外模型通道（对外模型名唯一、启停、负载策略） |
| `channel_upstreams` | 通道与上游的绑定关系（含权重、排序） |
| `call_logs` | 调用日志（api_key、模型、token、延迟、HTTP 状态、时间） |

## 项目结构

```
x-gate-ai
├── doc/
│   └── x-gate-ai-技术方案设计.md        # 技术方案设计文档
├── src/main/
│   ├── java/com/xgateai/
│   │   ├── XGateAiApplication.java      # 启动类
│   │   ├── adminbridge/                 # 控制台管理（上游/通道服务、Key 加密）
│   │   ├── application/                 # 对外 API 控制器、实体、异常处理
│   │   ├── gatewaybridge/               # 网关桥接（路由调度、OpenAI 适配、日志清理）
│   │   └── mapper/                      # MyBatis-Plus Mapper
│   └── resources/
│       ├── application.properties       # 配置文件
│       ├── db/schema.sql                # 建表脚本（幂等）
│       └── static/                      # Web 控制台前端（内嵌发布）
│           ├── index.html               # 仪表盘
│           ├── models.html              # 模型通道
│           ├── services.html            # 上游服务
│           ├── logs.html                # 调用日志
│           ├── usage.html               # 用量统计
│           └── js/ / css/ / lib/        # 前端资源
├── pom.xml
└── LICENSE                              # Apache-2.0
```

## License

[Apache License 2.0](LICENSE)

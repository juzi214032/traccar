# Traccar 服务端架构分析

> 基于 v6.14.5 源码分析(2026-07)。相关源码引用以 `src/main/java/org/traccar/` 为根。

## 项目定位

Traccar 是开源 GPS 追踪服务器,核心职责:

**接收 200+ 种 GPS 设备协议的数据 → 解码为统一的 Position 模型 → 流水线处理 → 持久化 + 事件告警 + 对外转发**,并提供 REST API / WebSocket 给前端。

代码规模:约 1046 个 Java 文件,其中 675 个在 `protocol` 包 —— "协议适配"是项目最大的工程量所在。

## 技术栈

| 层 | 选型 |
|---|---|
| 语言/运行时 | Java 21(发布流水线用 JDK 25 构建),字节码强制兼容 JDK 17 |
| 网络 I/O | Netty 4.2(TCP/UDP/MQTT/HTTP 全部自建 pipeline) |
| DI | Google Guice 7,全部 `@Singleton` |
| Web/API | Jetty 12 EE10 + Jersey 4(经 HK2-Guice bridge 桥接)+ WebSocket |
| 存储 | JDBC(H2/MySQL/MariaDB/PostgreSQL/MSSQL)+ HikariCP + Liquibase 迁移(`schema/changelog-*.xml`) |
| 集成 | Kafka、RabbitMQ、MQTT(HiveMQ)、Redis、Firebase、AWS SNS、OIDC、MCP SDK |
| 表达式/模板 | JEXL3(计算属性)、Velocity + Jxls(报表) |

## 总体形态:模块化单体

单进程模块化单体,非微服务。`Main.run()`(`Main.java:114`)创建 Guice injector(`MainModule` + `DatabaseModule` + `WebModule`),按序启动 4 个 `LifecycleObject`:

```
ScheduleManager(定时任务) → ServerManager(协议服务器群) → WebServer(REST/WS) → BroadcastService(多实例广播)
```

水平扩展靠 `BroadcastService`(multicast/Redis 两种后端)做实例间事件同步,属于"共享数据库 + 广播总线"的多实例模式,而非真正的分布式架构。

## 核心数据管道

```
GPS 设备 ──TCP/UDP──▶ ① 协议层 ──Position──▶ ② 处理链 ──▶ ③ 存储/事件/转发
```

### ① 协议层 —— 插件式"约定优于配置"

- 每个协议 = `XxxProtocol`(注册连接器)+ `XxxProtocolDecoder`(解码)+ 可选 `FrameDecoder`/`Encoder`
- 协议名由类名去掉 `Protocol` 后缀小写得到
- `ServerManager` 反射扫描 `org.traccar.protocol` 包,**只实例化配置了 `<协议名>.port` 的协议** —— 加新协议零注册成本,不配端口零运行时开销
- 每个连接的 Netty pipeline 由 `BasePipelineFactory` 统一组装:

```
传输层帧解码 → IdleStateHandler → OpenChannelHandler → [NetworkForwarderHandler]
→ NetworkMessageHandler → StandardLoggingHandler → [AcknowledgementHandler]
→ 协议解码/编码器 → RemoteAddressHandler → ProcessingHandler → MainEventHandler
```

### ② 处理链 —— 全异步回调驱动(`ProcessingHandler.java`)

全局共享的 `@Sharable` handler,三个关键设计:

1. **按设备串行队列**(`ProcessingHandler.java:86-90`):每个 deviceId 一个队列,同一设备的位置严格按序处理,不同设备并发 —— 保证里程/引擎小时等累计值的正确性
2. **异步回调链**(`ProcessingHandler.java:167-190`):18 个 position handler 不是同步 for 循环,而是 `Callback` 递进式链,回调回到 Netty event loop 执行(`ctx.executor()`)—— geocoder/geolocation 等慢速外部调用不阻塞 I/O 线程
3. **FilterHandler 可短路**:被过滤的位置直接跳到 ACK,不进数据库

处理完 18 级 position 链后,12 个 event handler 分析位置产出 `Event` → `NotificationManager` 发通知,最后 `PostProcessHandler` 收尾。

> 18 级处理链与过滤器详情见 [position-pipeline.md](position-pipeline.md)。

### ③ 存储层

- `Storage` 抽象类 + 两个实现:`DatabaseStorage`(`QueryBuilder` 生成 SQL)和 `MemoryStorage`(`database.memory=true` 时)
- 业务侧热路径大量读 `CacheManager`(`session` 包)避免查库
- 数据库结构由 Liquibase 管理(`schema/changelog-*.xml`)

## API 层

- 24 个 Jersey resource(`api/resource/`),按实体划分:Device / Position / Event / Report / Command / User / Geofence 等
- 认证:session token、OIDC、LDAP、TOTP;`api/security` 基于权限关系表做访问控制
- WebSocket 推送实时位置
- `web.mcp.enable=true` 可开启 **MCP 端点**,把服务器能力暴露给 LLM
- 前端 `traccar-web` 是独立仓库(发布流水线单独 checkout 构建),前后端完全解耦

## 关键包速查

| 包 | 职责 |
|---|---|
| `protocol` | 200+ 协议解码/编码器 |
| `handler` / `handler.events` / `handler.network` | 位置处理链 / 事件检测 / Netty I/O 处理 |
| `model` | 数据模型(Position、Device、Event...) |
| `storage` | 持久化抽象 + QueryBuilder |
| `session` | 连接会话管理 + CacheManager 缓存 |
| `api.resource` / `api.security` | REST 端点 / 认证授权 |
| `forward` | 位置/事件外发(JSON、Kafka、AMQP、MQTT、Redis、Wialon) |
| `broadcast` | 跨实例广播(multicast/Redis) |
| `geocoder` / `geolocation` / `geofence` / `speedlimit` / `mapmatcher` | 地理相关外部服务与计算 |
| `reports` | 报表生成(Jxls + Velocity) |
| `notification` / `notificators` | 通知系统(邮件、SMS、推送等) |
| `schedule` | 定时任务 |

## CI/CD(GitHub Actions)

| 流水线 | 触发 | 功能 |
|---|---|---|
| `gradle.yml`(Build Project) | push / PR 到 master | JDK 21 执行 `./gradlew build`(编译 + 测试 + checkstyle) |
| `release.yml`(Build Release) | 手动触发,输入 version | 构建服务端 + 前端 → 打 Linux x64/ARM(jlink + makeself)、Windows(Inno Setup)、通用 zip → 三平台真实安装测试(探测 8082 端口)→ preview 上传 S3 / 正式版发 GitHub Release + Docker 镜像(alpine/debian/ubuntu × amd64/arm64,推 Docker Hub + ghcr.io) |

## 架构特点小结

**优点:**

- **扩展点清晰**:加协议、加 handler、加通知渠道、加转发目标都是"实现基类 + Guice 绑定"的模式;`MainModule` 按配置决定是否提供实例,未启用的组件在处理链构造时被 `filter(Objects::nonNull)` 自然剔除
- **非阻塞贯穿始终**:从 Netty I/O 到处理链回调,慢速外部服务不拖垮吞吐
- **顺序性保证**:per-device 队列是并发与正确性之间的务实取舍

**需要注意的点:**

- `ProcessingHandler.queues` 是按 deviceId 增长的 `HashMap`,设备量大时依赖清理逻辑
- 单体 + 共享库模式下数据库是扩展瓶颈,重度部署依赖 `CacheManager` 与广播机制
- 累计值(totalDistance / hours)依赖"上一位置"缓存,相关缓存一致性是历史 bug 高发区(见 commit `2710d25d8`)

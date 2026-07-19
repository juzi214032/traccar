# 位置处理链与过滤器详解

> 对应源码:`src/main/java/org/traccar/ProcessingHandler.java` 与 `src/main/java/org/traccar/handler/`。总体架构见 [architecture.md](architecture.md)。

## 处理机制

`ProcessingHandler` 收到解码后的 `Position` 后:

1. 先经 `BufferingManager` 缓冲(可按配置对乱序数据排序)
2. 进入**按设备的串行队列**:同一设备严格按序处理,不同设备并发
3. 依次经过 **18 级 position handler**(异步回调链,慢速外部调用不阻塞 event loop)
4. 未被过滤的位置再经 **12 个 event handler** 产出事件/告警
5. `PostProcessHandler` 收尾 → 记日志 → 发 ACK → 处理该设备队列中的下一条

只有第 8 步 `FilterHandler` 能短路整条链(`callback.processed(true)`),被过滤的点不入库、不出事件。

## 18 级 Position 处理链

| # | Handler | 作用 | 说明 |
|---|---|---|---|
| 1 | `ComputedAttributesHandler.Early` | 计算属性(早期) | 用 JEXL 表达式基于原始数据算出新属性,在过滤/修正**之前**跑,可影响后续处理 |
| 2 | `OutdatedHandler` | 回填过期位置 | 设备只上报状态、无有效定位时(`outdated=true`),从缓存取上一位置的坐标/速度/航向回填;无历史则时间设为 GPS 纪元(1980-01-06) |
| 3 | `TimeHandler` | 时间修正 | ① 修复 GPS 周数回绕(老设备时间倒退 1024 周,按周期补齐);② 按配置用 serverTime/deviceTime 覆盖 fixTime |
| 4 | `GeolocationHandler` | 基站/WiFi 定位 | 无 GPS 信号时,用报文里的基站(LBS)/WiFi 数据调外部服务解析坐标 |
| 5 | `HemisphereHandler` | 半球修正 | 有些设备不报 N/S、E/W 符号;按配置强制纬度/经度正负号 |
| 6 | `MapMatcherHandler` | 地图匹配 | 把漂移的 GPS 点"吸附"到道路网络上(外部服务) |
| 7 | `DistanceHandler` | 里程计算 | 算与上一点的距离(`distance`)并累加总里程(`totalDistance`);可选坐标误差过滤(见下文) |
| 8 | `FilterHandler` | **数据过滤(唯一能短路)** | 详见下节 |
| 9 | `GeofenceHandler` | 围栏标注 | 计算该点落在哪些围栏内(圆/多边形/路线走廊),命中的围栏 ID 列表写入 `position.geofenceIds`;**只标注不出事件** |
| 10 | `GeocoderHandler` | 逆地理编码 | 坐标 → 文字地址,写入 `position.address` |
| 11 | `SpeedLimitHandler` | 道路限速查询 | 查该坐标所在道路的法定限速,写入 `speedLimit`,供超速事件判断 |
| 12 | `MotionHandler` | 运动状态推断 | 设备没报 motion 时,按速度阈值推断 `motion=true/false`,是停留/行程划分的基础 |
| 13 | `ComputedAttributesHandler.Late` | 计算属性(晚期) | 此时里程、围栏、地址、限速都已就绪,表达式可引用这些结果 |
| 14 | `DriverHandler` | 司机关联 | 位置无司机标识(如刷卡 ID)时,取设备关联的司机填入 `driverUniqueId`(需 `useLinkedDriver`) |
| 15 | `CopyAttributesHandler` | 属性继承 | 按配置从上一位置复制本次缺失的属性(如油量、温度只偶尔上报),保持时间序列连续 |
| 16 | `EngineHoursHandler` | 引擎小时累计 | 设备不报 hours 时:前后两点都点火,则把时间差累加到引擎工作总时长 |
| 17 | `PositionForwardingHandler` | 实时转发 | 推给外部系统(HTTP JSON / Kafka / AMQP / MQTT / Redis / Wialon) |
| 18 | `DatabaseHandler` | 持久化 | 位置入库,更新设备最新位置引用 |

**顺序即语义**:修正类(2-6)在前 → 计算类(7、9-16)居中 → 落地类(17-18)在后。里程(7)必须在过滤(8)前算好距离供其判断;围栏(9)在过滤后才不会被漂移点触发误报。

**可选启用**:4、6、10、11 等依赖外部服务的 handler 只在配置开启时才实例化(`MainModule` 返回 null 则从链中剔除)。

**大量依赖 CacheManager 的"上一位置"**:2、7、15、16 都要和前一点对比 —— 这正是按设备串行队列的原因,乱序会算错里程和引擎小时。

## FilterHandler 过滤器详解(`handler/FilterHandler.java`)

所有 `filter.*` 配置键既可全局配置,也可通过设备/分组属性按设备覆盖(`AttributeUtil.lookup` 查找链)。命中任一过滤器即整条丢弃,并记 INFO 日志。

### 漂移检测:`filter.maxSpeed`(核心)

```java
// FilterHandler.java:111-119
double distance = position.getDouble(Position.KEY_DISTANCE);   // 第 7 步 DistanceHandler 算好的
double time = position.getFixTime().getTime() - last.getFixTime().getTime();
return time > 0 && UnitsConverter.knotsFromMps(distance / (time / 1000)) > filterMaxSpeed;
```

**(与上一点距离) ÷ (时间差) = 隐含速度**,超过阈值(节)即判定为物理上不可能的"瞬移"漂移点。注意:

- 判断的不是设备上报的 speed 字段,而是坐标反推的速度 —— 报速为 0 但坐标跳走的漂移点也能抓到
- **无任何豁免,是硬过滤**

### 坏点/质量过滤(广义漂移相关)

| 过滤器 | 配置键 | 判定条件 |
|---|---|---|
| Invalid | `filter.invalid` | GPS 未锁定(`valid=false`)或坐标超界(±90/±180) |
| Zero | `filter.zero` | 坐标恰为 (0, 0)("零点岛") |
| Accuracy | `filter.accuracy` | 定位误差半径(米)超过阈值 |
| Approximate | `filter.approximate` | 被标记为近似定位的点(通常是基站 LBS,公里级精度) |

### 时间合理性过滤

| 过滤器 | 配置键 | 判定条件 |
|---|---|---|
| Future | `filter.future` | fixTime 在未来超 N 秒 |
| Past | `filter.past` | fixTime 早于当前时间 N 秒以上 |
| Outdated | `filter.outdated` | 无有效定位的过期位置 |

### 去冗余过滤(可被豁免)

| 过滤器 | 配置键 | 判定条件 |
|---|---|---|
| Duplicate | `filter.duplicate` | fixTime 与上一点相同且无新增属性 |
| Static | `filter.static` | 速度为 0 的静止点 |
| Distance | `filter.distance` | 与上一点距离小于 N 米 |
| MinPeriod | `filter.minPeriod` | 与上一点间隔小于 N 秒 |
| DailyLimit | `filter.dailyLimit`(+ `filter.dailyLimitInterval`) | 当日入库条数达上限 |

**豁免机制**(仅对 Duplicate/Static/Distance 生效,`FilterHandler.java:197-204`):

- `filter.skipLimit` —— 距上一条**入库**超 N 秒,强制放行一条(保心跳)
- `filter.skipAttributes` —— 指定属性(如点火、告警)与上一点相比发生变化时放行

### 日历过滤

设备绑定 Calendar 时,fixTime 落在日历时间窗之外的点被过滤(如只在工作时段采集)。

### 互补策略:DistanceHandler 的 `coordinates.filter`

`coordinates.minError` / `coordinates.maxError`:距离异常时**不丢弃消息,只把坐标替换成上一点的** —— 属性数据(油量、点火等)仍保留入库。与 `filter.maxSpeed` 的"整条丢弃"互补。

## 12 个事件处理器(过滤后执行)

`MediaEventHandler`、`CommandResultEventHandler`、`OverspeedEventHandler`(超速,可用第 11 步的 speedLimit)、`BehaviorEventHandler`(急加速/急刹)、`FuelEventHandler`(油量骤降)、`MotionEventHandler`(行程开始/结束)、`GeofenceEventHandler`(对比前后 geofenceIds 差集 → 进/出围栏事件)、`ProximityEventHandler`、`AlarmEventHandler`、`IgnitionEventHandler`(点火/熄火)、`MaintenanceEventHandler`(保养到期)、`DriverEventHandler`(换司机)。

事件经 `NotificationManager` 按用户订阅规则分发通知(邮件/SMS/推送等)。

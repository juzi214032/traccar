---
name: traccar-db-query
description: Query the Traccar database directly. Connect to H2/MySQL/PostgreSQL and run SQL queries to inspect devices, positions, events, geofences, and debug issues.
---

# Traccar 数据库查询

直接连接 Traccar 数据库执行 SQL 查询。用于排查设备状态、查看位置历史、分析围栏事件、调试过滤策略等。

## 连接数据库

### 本地开发 (H2)

debug.xml 配置的 H2 数据库路径为 `./target/database`：

```bash
bash .claude/skills/traccar-db-query/query.sh "SELECT * FROM tc_devices"
```

脚本默认连接 `jdbc:h2:./target/database`，用户 `sa`，无密码。

### 生产环境 (MySQL/PostgreSQL)

通过环境变量覆盖连接参数：

```bash
DB_URL='jdbc:mysql://localhost:3306/traccar?useSSL=false&allowPublicKeyRetrieval=true' \
DB_USER=traccar \
DB_PASS=secret \
bash .claude/skills/traccar-db-query/query.sh "SELECT * FROM tc_devices"
```

也可以写入 `.env` 文件（gitignore 保护）：

```
DB_URL=jdbc:mysql://localhost:3306/traccar?useSSL=false&allowPublicKeyRetrieval=true
DB_USER=traccar
DB_PASS=secret
```

### 交互模式

不带参数运行进入交互模式（逐行输入 SQL，`exit` 退出）：

```bash
bash .claude/skills/traccar-db-query/query.sh
```

## 数据表速查

### 核心表

| 表名 | 说明 | 关键字段 |
|------|------|---------|
| `tc_devices` | 设备 | id, name, uniqueid, lastupdate, positionid, groupid, attributes, disabled |
| `tc_positions` | 定位数据 | id, deviceid, fixtime, latitude, longitude, speed, valid, accuracy, geofenceids |
| `tc_events` | 事件 | id, type, servertime, deviceid, positionid, geofenceid |
| `tc_geofences` | 围栏 | id, name, area, calendarid |
| `tc_users` | 用户 | id, name, email, administrator, disabled |
| `tc_groups` | 分组 | id, name, groupid |
| `tc_drivers` | 司机 | id, name, uniqueid |
| `tc_commands` | 指令定义 | id, type, description |
| `tc_notifications` | 通知定义 | id, type, notificators, calendarid |
| `tc_calendars` | 日历 | id, name |
| `tc_statistics` | 统计 | id, capturetime, activeusers, activedevices |

### 关联表

| 表名 | 关联关系 |
|------|---------|
| `tc_user_device` | 用户 ↔ 设备 |
| `tc_device_geofence` | 设备 ↔ 围栏 |
| `tc_user_geofence` | 用户 ↔ 围栏 |
| `tc_device_driver` | 设备 ↔ 司机 |
| `tc_device_maintenance` | 设备 ↔ 维护 |
| `tc_device_notification` | 设备 ↔ 通知 |
| `tc_user_user` | 用户层级（托管） |

### 事件类型 (tc_events.type)

| type | 说明 |
|------|------|
| `deviceOnline` / `deviceOffline` | 设备上下线 |
| `deviceUnknown` / `deviceInactive` / `deviceExcessive` | 设备异常 |
| `geofenceEnter` / `geofenceExit` | 围栏进出 |
| `alarm` (attributes 中含具体 alarm 类型) | 告警 |
| `ignitionOn` / `ignitionOff` | 点火 |
| `deviceOverspeed` | 超速 |
| `deviceMoving` / `deviceStopped` | 运动/静止 |
| `maintenance` | 维护提醒 |
| `commandResult` | 指令结果 |

## 常用查询

### 设备相关

```sql
-- 所有设备（含在线状态）
SELECT id, name, uniqueid, lastupdate,
       CASE WHEN lastupdate > DATEADD('MINUTE', -5, CURRENT_TIMESTAMP) THEN 'ONLINE' ELSE 'OFFLINE' END AS status
  FROM tc_devices
 WHERE disabled = 0
 ORDER BY lastupdate DESC NULLS LAST;

-- 单个设备详情
SELECT * FROM tc_devices WHERE id = 1;

-- 按 uniqueId 查设备
SELECT * FROM tc_devices WHERE uniqueid = 'IMEI号';

-- 某设备最近的定位
SELECT * FROM tc_positions
 WHERE deviceid = 1
 ORDER BY fixtime DESC LIMIT 10;

-- 所有设备最后定位时间（超过 1 天未上报）
SELECT d.id, d.name, d.uniqueid, MAX(p.fixtime) AS last_fix
  FROM tc_devices d
  LEFT JOIN tc_positions p ON p.deviceid = d.id
 WHERE d.disabled = 0
 GROUP BY d.id
HAVING MAX(p.fixtime) < DATEADD('DAY', -1, CURRENT_TIMESTAMP)
    OR MAX(p.fixtime) IS NULL
 ORDER BY last_fix ASC NULLS FIRST;
```

### 围栏与事件

```sql
-- 某设备的围栏进出事件（最近 50 条）
SELECT e.id, e.type, e.servertime, e.geofenceid, g.name AS geofence_name,
       p.latitude, p.longitude
  FROM tc_events e
  LEFT JOIN tc_geofences g ON e.geofenceid = g.id
  LEFT JOIN tc_positions p ON e.positionid = p.id
 WHERE e.deviceid = 1
   AND e.type IN ('geofenceEnter', 'geofenceExit')
 ORDER BY e.servertime DESC LIMIT 50;

-- 某时间段内的围栏事件
SELECT e.*, g.name
  FROM tc_events e
  JOIN tc_geofences g ON e.geofenceid = g.id
 WHERE e.servertime BETWEEN '2026-07-20 00:00:00' AND '2026-07-23 23:59:59'
 ORDER BY e.servertime DESC LIMIT 100;

-- 某设备的围栏配置（设备绑定了哪些围栏）
SELECT g.id, g.name, g.description
  FROM tc_geofences g
  JOIN tc_device_geofence dg ON dg.geofenceid = g.id
 WHERE dg.deviceid = 1;

-- 最近所有围栏进出事件（含设备名）
SELECT e.servertime, d.name AS device, e.type, g.name AS geofence
  FROM tc_events e
  JOIN tc_devices d ON e.deviceid = d.id
  LEFT JOIN tc_geofences g ON e.geofenceid = g.id
 WHERE e.type IN ('geofenceEnter', 'geofenceExit')
 ORDER BY e.servertime DESC LIMIT 50;
```

### 告警

```sql
-- 最近的告警事件
SELECT e.id, e.servertime, e.deviceid, d.name, e.attributes
  FROM tc_events e
  JOIN tc_devices d ON e.deviceid = d.id
 WHERE e.type = 'alarm'
 ORDER BY e.servertime DESC LIMIT 50;

-- 某设备近 7 天告警统计（按类型）
SELECT e.attributes, COUNT(*) AS cnt
  FROM tc_events e
 WHERE e.deviceid = 1
   AND e.type = 'alarm'
   AND e.servertime >= DATEADD('DAY', -7, CURRENT_TIMESTAMP)
 GROUP BY e.attributes
 ORDER BY cnt DESC;
```

### 定位分析

```sql
-- 某设备近 1 小时轨迹
SELECT fixtime, latitude, longitude, speed, valid
  FROM tc_positions
 WHERE deviceid = 1
   AND fixtime >= DATEADD('HOUR', -1, CURRENT_TIMESTAMP)
 ORDER BY fixtime;

-- 某设备 24 小时定位量统计（按小时聚合）
SELECT FORMATDATETIME(fixtime, 'yyyy-MM-dd HH:00') AS hour, COUNT(*) AS cnt,
       AVG(speed) AS avg_speed
  FROM tc_positions
 WHERE deviceid = 1
   AND fixtime >= DATEADD('DAY', -1, CURRENT_TIMESTAMP)
 GROUP BY hour ORDER BY hour;

-- 设备定位频率统计（过去 1 小时，按上报间隔）
SELECT deviceid, COUNT(*) AS positions,
       DATEDIFF('SECOND', MIN(fixtime), MAX(fixtime)) AS time_span_sec
  FROM tc_positions
 WHERE fixtime >= DATEADD('HOUR', -1, CURRENT_TIMESTAMP)
 GROUP BY deviceid
 ORDER BY positions DESC;

-- geofenceIds 不为空（有哪些位置命中了围栏）
SELECT p.id, p.fixtime, d.name, p.geofenceids
  FROM tc_positions p
  JOIN tc_devices d ON p.deviceid = d.id
 WHERE p.geofenceids IS NOT NULL
   AND p.geofenceids <> ''
   AND p.fixtime >= DATEADD('HOUR', -24, CURRENT_TIMESTAMP)
 ORDER BY p.fixtime DESC LIMIT 100;
```

### 数据量与清理

```sql
-- 各表数据量统计
SELECT 'tc_positions' AS tbl, COUNT(*) AS rows FROM tc_positions
UNION ALL SELECT 'tc_events', COUNT(*) FROM tc_events
UNION ALL SELECT 'tc_devices', COUNT(*) FROM tc_devices
UNION ALL SELECT 'tc_geofences', COUNT(*) FROM tc_geofences
UNION ALL SELECT 'tc_users', COUNT(*) FROM tc_users;

-- 定位表按设备的数据量
SELECT deviceid, COUNT(*) AS positions,
       MIN(fixtime) AS first_fix, MAX(fixtime) AS last_fix
  FROM tc_positions
 GROUP BY deviceid ORDER BY positions DESC;

-- 事件表按类型分布
SELECT type, COUNT(*) AS cnt
  FROM tc_events
 GROUP BY type ORDER BY cnt DESC;
```

### 调试过滤策略

```sql
-- 观察锚点过滤：某设备连续位置的距离变化（判断是否触发锚点）
SELECT id, fixtime, latitude, longitude, accuracy, speed,
       geofenceids,
       (SELECT COUNT(*) FROM tc_events e
         WHERE e.deviceid = p.deviceid
           AND e.type IN ('geofenceEnter', 'geofenceExit')
           AND e.servertime BETWEEN DATEADD('MINUTE', -10, p.fixtime) AND DATEADD('MINUTE', 10, p.fixtime))
       AS nearby_events
  FROM tc_positions p
 WHERE deviceid = 1
   AND fixtime >= DATEADD('HOUR', -2, CURRENT_TIMESTAMP)
 ORDER BY fixtime DESC;

-- 精度过滤排查：某设备高精度/低精度定位的分布
SELECT CASE WHEN accuracy > 20 THEN 'LOW(>20)' ELSE 'OK(<=20)' END AS accuracy_group,
       COUNT(*), ROUND(AVG(accuracy), 1) AS avg_acc
  FROM tc_positions
 WHERE deviceid = 1
   AND fixtime >= DATEADD('DAY', -7, CURRENT_TIMESTAMP)
 GROUP BY accuracy > 20;
```

## 多数据库语法差异

| 功能 | H2 | MySQL | PostgreSQL |
|------|----|-------|-----------|
| 当前时间 | `CURRENT_TIMESTAMP` | `NOW()` | `NOW()` |
| 时间加减 | `DATEADD('HOUR', -1, ...)` | `... - INTERVAL 1 HOUR` | `... - INTERVAL '1 hour'` |
| 字符串拼接 | `CONCAT(a, b)` | `CONCAT(a, b)` | `a \|\| b` |
| LIMIT | `LIMIT n` | `LIMIT n` | `LIMIT n` |
| 类型转换 | `CAST(x AS VARCHAR)` | `CAST(x AS CHAR)` | `x::text` |

**⚠ 注意**：上面常用查询中的 SQL 语法偏向 H2。在 MySQL/PostgreSQL 中需调整时间函数。

## 脚本用法

### query.sh

```bash
# 单条查询
bash .claude/skills/traccar-db-query/query.sh "SELECT COUNT(*) FROM tc_positions"

# 从文件读取
bash .claude/skills/traccar-db-query/query.sh -f query.sql

# 交互模式
bash .claude/skills/traccar-db-query/query.sh

# 多行 SQL
bash .claude/skills/traccar-db-query/query.sh "
SELECT d.name, COUNT(*) AS cnt
  FROM tc_positions p
  JOIN tc_devices d ON p.deviceid = d.id
 GROUP BY d.name
 ORDER BY cnt DESC
 LIMIT 10
"
```

需要 Java 21+ 运行时和 Gradle 已下载依赖（`./gradlew build` 后 JDBC 驱动在 Gradle cache 中自动可用）。

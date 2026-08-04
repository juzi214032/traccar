package org.traccar.handler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.traccar.config.Config;
import org.traccar.model.Device;
import org.traccar.model.Geofence;
import org.traccar.model.Position;
import org.traccar.session.HomeAssistantProvider;
import org.traccar.session.cache.CacheManager;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class GeofenceHandlerTest {

    private static final long DEVICE_ID = 1L;
    private static final long GEOFENCE_ID = 100L;

    /** Base location: Beijing */
    private static final double BASE_LAT = 39.9;
    private static final double BASE_LON = 116.4;

    /** ~85m east of base (well beyond anchorMaxDistance=50m) */
    private static final double DRIFT_LON = 116.401;

    /** Interval between generated positions; anchor clusters must span real time to lock. */
    private static final long POSITION_INTERVAL_MS = 30_000;

    private CacheManager cacheManager;
    private Config config;
    private GeofenceHandler handler;
    private HomeAssistantProvider homeAssistant;
    private AtomicReference<Position> lastPositionRef;
    /** Monotonic fix-time cursor, advanced by every generated position. */
    private long clock;

    @BeforeEach
    public void setUp() {
        config = mock(Config.class);
        cacheManager = mock(CacheManager.class);
        when(cacheManager.getConfig()).thenReturn(config);

        Device device = mock(Device.class);
        when(device.getId()).thenReturn(DEVICE_ID);
        when(device.getAttributes()).thenReturn(new HashMap<>());
        when(device.getGroupId()).thenReturn(0L);
        when(cacheManager.getObject(eq(Device.class), anyLong())).thenReturn(device);

        // Default: no geofences matched (safe for Phase 1 cluster building)
        when(cacheManager.getDeviceObjects(anyLong(), eq(Geofence.class)))
                .thenReturn(Set.of());

        lastPositionRef = new AtomicReference<>();
        when(cacheManager.getPosition(DEVICE_ID)).thenAnswer(inv -> lastPositionRef.get());

        // Default: Home Assistant integration disabled so existing tests are unaffected
        homeAssistant = mock(HomeAssistantProvider.class);
        when(homeAssistant.isEnabled()).thenReturn(false);

        clock = 1_600_000_000_000L;

        handler = new GeofenceHandler(cacheManager, homeAssistant);
    }

    // ---- helpers ----

    private Position position(double lat, double lon) {
        Position p = new Position();
        p.setDeviceId(DEVICE_ID);
        p.setFixTime(new Date(clock));
        clock += POSITION_INTERVAL_MS;
        p.setLatitude(lat);
        p.setLongitude(lon);
        p.setValid(true);
        return p;
    }

    private Position positionWithGeofenceIds(double lat, double lon, List<Long> ids) {
        Position p = position(lat, lon);
        p.setGeofenceIds(ids);
        return p;
    }

    /** Position with an explicit fix time, for replaying real sampling intervals. */
    private Position positionAt(double lat, double lon, long fixTimeMs) {
        Position p = position(lat, lon);
        p.setFixTime(new Date(fixTimeMs));
        return p;
    }

    private BasePositionHandler.Callback countingCallback(AtomicBoolean called) {
        return filtered -> called.set(true);
    }

    private void configureAnchor(int radius, int count, int maxDist, int release) {
        when(config.getString("filter.geofenceAnchorRadius")).thenReturn(String.valueOf(radius));
        when(config.getString("filter.geofenceAnchorCount")).thenReturn(String.valueOf(count));
        when(config.getString("filter.geofenceAnchorMaxDistance")).thenReturn(String.valueOf(maxDist));
        when(config.getString("filter.geofenceAnchorReleaseCount")).thenReturn(String.valueOf(release));
    }

    /**
     * Feed {@code count} identical positions at (BASE_LAT, BASE_LON) to build the anchor
     * cluster. After this, the anchor should be locked.
     */
    private void buildAnchor(int count) {
        for (int i = 0; i < count; i++) {
            AtomicBoolean cb = new AtomicBoolean();
            handler.onPosition(position(BASE_LAT, BASE_LON), countingCallback(cb));
            assertTrue(cb.get(), "callback should be called (position continues through pipeline)");
        }
    }

    // ---- tests ----

    @Test
    public void testAnchorEstablishAndFilter() {
        configureAnchor(30, 5, 50, 5);

        // Phase 1: build anchor with 5 identical positions
        buildAnchor(5);

        // Set up a last position with known geofenceIds so we can verify filtering
        Position lastPos = positionWithGeofenceIds(BASE_LAT, BASE_LON, List.of(GEOFENCE_ID));
        lastPositionRef.set(lastPos);

        // Phase 2: inject a drift position far from the anchor
        Position drift = position(BASE_LAT, DRIFT_LON);
        AtomicBoolean cb = new AtomicBoolean();
        handler.onPosition(drift, countingCallback(cb));

        // Drift point should inherit geofenceIds from the lastPosition (filtered), not
        // recalculated via GeofenceUtil.
        assertEquals(List.of(GEOFENCE_ID), drift.getGeofenceIds(),
                "drift point should reuse geofenceIds from lastPosition");
    }

    @Test
    public void testAnchorReleaseAfterSustainedMovement() {
        configureAnchor(30, 5, 50, 5);

        // Phase 1: build anchor
        buildAnchor(5);

        // Inject a last position so filtered positions can reuse geofenceIds
        Position lastPos = positionWithGeofenceIds(BASE_LAT, BASE_LON, List.of(GEOFENCE_ID));
        lastPositionRef.set(lastPos);

        // Phase 2: progressively further positions → release anchor after releaseCount.
        // Distances stay inside the abandon ceiling (anchorMaxDistance × 5 = 250m) so the
        // away-streak path is exercised rather than the outright-abandon path.
        double metersPerLonDegree = 111320 * Math.cos(Math.toRadians(BASE_LAT));
        double[] eastMeters = {60, 90, 120, 150, 180};
        for (int i = 0; i < eastMeters.length; i++) {
            Position p = position(BASE_LAT, BASE_LON + eastMeters[i] / metersPerLonDegree);
            handler.onPosition(p, countingCallback(new AtomicBoolean()));

            if (i < eastMeters.length - 1) {
                // awayStreak still below releaseCount → filtered
                assertEquals(List.of(GEOFENCE_ID), p.getGeofenceIds(),
                        "step " + i + ": should be filtered, reuse lastPosition geofenceIds");
            } else {
                // awayStreak reaches releaseCount → anchor released.
                // The releasing position itself is NOT filtered (skipByAnchor stays false
                // when the release path is taken), so it goes through normal geofence calc.
                assertNotEquals(List.of(GEOFENCE_ID), p.getGeofenceIds(),
                        "step " + i + ": anchor released, should NOT reuse old geofenceIds");
            }
        }

        // After release, the next position should also be normally calculated
        Position afterRelease = position(BASE_LAT, BASE_LON + 210 / metersPerLonDegree);
        handler.onPosition(afterRelease, countingCallback(new AtomicBoolean()));
        assertNotEquals(List.of(GEOFENCE_ID), afterRelease.getGeofenceIds(),
                "post-release: should be normally calculated, not reuse old geofenceIds");
    }

    @Test
    public void testAnchorAwayStreakResetWhenCloseToAnchor() {
        configureAnchor(30, 5, 50, 5);

        // Phase 1: build anchor
        buildAnchor(5);

        Position lastPos = positionWithGeofenceIds(BASE_LAT, BASE_LON, List.of(GEOFENCE_ID));
        lastPositionRef.set(lastPos);

        // First drift point: awayStreak=1, filtered
        Position drift1 = position(BASE_LAT, DRIFT_LON);
        handler.onPosition(drift1, countingCallback(new AtomicBoolean()));
        assertEquals(List.of(GEOFENCE_ID), drift1.getGeofenceIds(),
                "first drift: filtered, awayStreak=1");

        // Back close to anchor: awayStreak reset to 0, NOT filtered
        Position backClose = position(BASE_LAT, BASE_LON);
        lastPositionRef.set(drift1); // update lastPosition
        handler.onPosition(backClose, countingCallback(new AtomicBoolean()));
        assertNotEquals(List.of(GEOFENCE_ID), backClose.getGeofenceIds(),
                "back close to anchor: awayStreak=0, NOT filtered, normal calc");

        // Second drift: awayStreak starts from 1 again (since lastDistanceFromAnchor
        // was reset to ~0 in the previous "close" step)
        Position drift2 = position(BASE_LAT, DRIFT_LON);
        lastPositionRef.set(drift1); // drift1 has geofenceIds=[100] from filtering in step 3
        handler.onPosition(drift2, countingCallback(new AtomicBoolean()));
        assertEquals(List.of(GEOFENCE_ID), drift2.getGeofenceIds(),
                "second drift after reset: awayStreak=1, filtered again");
    }

    @Test
    public void testAnchorClusterResetDuringBuilding() {
        configureAnchor(30, 5, 50, 5);

        // Start building cluster at base
        handler.onPosition(position(BASE_LAT, BASE_LON), countingCallback(new AtomicBoolean()));
        handler.onPosition(position(BASE_LAT, BASE_LON), countingCallback(new AtomicBoolean()));
        handler.onPosition(position(BASE_LAT, BASE_LON), countingCallback(new AtomicBoolean()));
        // clusterCount = 3

        // A point far away resets the cluster
        handler.onPosition(position(BASE_LAT, DRIFT_LON), countingCallback(new AtomicBoolean()));
        // clusterCount should be back to 1, cluster center at DRIFT_LON

        // Feed 4 more identical positions at base (not enough to re-establish anchor)
        // but since cluster was reset to DRIFT_LON, back-to-base is also a reset
        // Actually the point after DRIFT_LON:
        // cluster is at DRIFT_LON (count=1), base is 85m away > 30 → reset again to base, count=1

        // To verify the reset, we need to show that after the reset, the anchor is NOT
        // yet established. Feed a drift point immediately after the reset position — it
        // should NOT be filtered because anchor is not established yet.
        // Strategy: feed another base position (so cluster at base, count=1), then feed
        // a drift. The drift should NOT be filtered.
        handler.onPosition(position(BASE_LAT, BASE_LON), countingCallback(new AtomicBoolean()));
        // clusterCount=2 now (need 5 for anchor)

        // This drift should NOT be filtered because anchor is not established
        Position drift = position(BASE_LAT, DRIFT_LON);
        handler.onPosition(drift, countingCallback(new AtomicBoolean()));
        // No geofenceIds from filtering — goes through normal calculation (empty result)
        assertNull(drift.getGeofenceIds(),
                "drift before anchor established: should NOT be filtered, normal calc with no matching geofence");
    }

    @Test
    public void testAnchorNotActivatedWhenNotConfigured() {
        // No anchor keys configured → anchor logic disabled
        // Mock a geofence that contains the position
        Geofence geofence = mock(Geofence.class);
        when(geofence.getId()).thenReturn(GEOFENCE_ID);
        when(geofence.containsPosition(any(Position.class))).thenReturn(true);
        when(cacheManager.getDeviceObjects(eq(DEVICE_ID), eq(Geofence.class)))
                .thenReturn(Set.of(geofence));

        Position p = position(BASE_LAT, BASE_LON);
        handler.onPosition(p, countingCallback(new AtomicBoolean()));

        // Normal geofence calculation should happen
        assertEquals(List.of(GEOFENCE_ID), p.getGeofenceIds(),
                "without anchor config, normal geofence calculation should work");
    }

    @Test
    public void testAnchorNotActivatedWithPartialConfig() {
        // Only set anchorRadius and anchorCount, missing maxDistance and releaseCount
        when(config.getString("filter.geofenceAnchorRadius")).thenReturn("30");
        when(config.getString("filter.geofenceAnchorCount")).thenReturn("5");
        // maxDistance and releaseCount NOT configured (return null)

        // Mock a geofence that contains the position
        Geofence geofence = mock(Geofence.class);
        when(geofence.getId()).thenReturn(GEOFENCE_ID);
        when(geofence.containsPosition(any(Position.class))).thenReturn(true);
        when(cacheManager.getDeviceObjects(eq(DEVICE_ID), eq(Geofence.class)))
                .thenReturn(Set.of(geofence));

        // Feed many positions → anchor should never activate (partial config)
        for (int i = 0; i < 10; i++) {
            Position p = position(BASE_LAT, BASE_LON);
            handler.onPosition(p, countingCallback(new AtomicBoolean()));
            // All positions should go through normal geofence calculation
            assertEquals(List.of(GEOFENCE_ID), p.getGeofenceIds(),
                    "position " + i + ": with partial config, normal calculation should work");
        }
    }

    @Test
    public void testAnchorOnlyDistanceFilterNotSpeedOrAccuracy() {
        // Verify anchor filter works independently of accuracy/speed filters.
        // Set accuracy filter to a very permissive value and speed to 0 — anchor
        // should still filter drift points.
        when(config.getString("filter.geofenceEventAccuracy")).thenReturn("5"); // strict accuracy
        configureAnchor(30, 5, 50, 5);

        // Build anchor with positions that have default accuracy=0 (passes accuracy filter
        // since 0 <= 5) and default speed=0 (speed filter might trigger if configured).
        // But we don't configure speed filter here — verify anchor works alone.
        buildAnchor(5);

        Position lastPos = positionWithGeofenceIds(BASE_LAT, BASE_LON, List.of(GEOFENCE_ID));
        lastPositionRef.set(lastPos);

        // Drift position with good accuracy (passes accuracy filter) but far from anchor
        Position drift = position(BASE_LAT, DRIFT_LON);
        drift.setAccuracy(1); // <= 5, passes accuracy filter
        drift.setSpeed(10);   // non-zero speed

        handler.onPosition(drift, countingCallback(new AtomicBoolean()));

        // Should still be filtered by anchor despite passing accuracy/speed checks
        assertEquals(List.of(GEOFENCE_ID), drift.getGeofenceIds(),
                "anchor should filter drift even when accuracy and speed are acceptable");
    }

    @Test
    public void testAnchorStateIsPerDevice() {
        configureAnchor(30, 5, 50, 5);

        long device1 = DEVICE_ID;
        long device2 = 2L;

        // Register a second device
        Device device2Mock = mock(Device.class);
        when(device2Mock.getId()).thenReturn(device2);
        when(device2Mock.getAttributes()).thenReturn(new HashMap<>());
        when(device2Mock.getGroupId()).thenReturn(0L);
        when(cacheManager.getObject(eq(Device.class), eq(device2))).thenReturn(device2Mock);

        // Build anchor for device 1
        buildAnchor(5);

        // Device 2: feed only 2 positions at base → anchor NOT established for device 2
        for (int i = 0; i < 2; i++) {
            Position p = position(BASE_LAT, BASE_LON);
            p.setDeviceId(device2);
            handler.onPosition(p, countingCallback(new AtomicBoolean()));
        }

        // Device 1: drift should be filtered (anchor active)
        Position lastPos = positionWithGeofenceIds(BASE_LAT, BASE_LON, List.of(GEOFENCE_ID));
        lastPositionRef.set(lastPos);
        Position drift1 = position(BASE_LAT, DRIFT_LON);
        handler.onPosition(drift1, countingCallback(new AtomicBoolean()));
        assertEquals(List.of(GEOFENCE_ID), drift1.getGeofenceIds(),
                "device 1: anchor active, should filter drift");

        // Device 2: drift should NOT be filtered (anchor not yet established)
        lastPositionRef.set(null); // device 2 has no lastPosition yet
        Position drift2 = position(BASE_LAT, DRIFT_LON);
        drift2.setDeviceId(device2);
        handler.onPosition(drift2, countingCallback(new AtomicBoolean()));
        // No filtering → goes through normal geofence calc → gets null (no matching geofences)
        assertNull(drift2.getGeofenceIds(),
                "device 2: anchor not established, should NOT filter drift");
    }

    @Test
    public void testLowAccuracyDriftDoesNotReleaseAnchor() {
        when(config.getString("filter.geofenceEventAccuracy")).thenReturn("20");
        configureAnchor(30, 5, 50, 5);

        // Build anchor with good-accuracy positions (accuracy=0 passes)
        buildAnchor(5);

        Position lastPos = positionWithGeofenceIds(BASE_LAT, BASE_LON, List.of(GEOFENCE_ID));
        lastPositionRef.set(lastPos);

        // 6 low-accuracy drift points progressively farther from the anchor.
        // Before the fix these would increment awayStreak and release the anchor after 5.
        for (int i = 1; i <= 6; i++) {
            Position p = position(BASE_LAT, BASE_LON + i * 0.001);
            p.setAccuracy(30); // > 20 → skipByAccuracy → anchor state frozen
            handler.onPosition(p, countingCallback(new AtomicBoolean()));
            assertEquals(List.of(GEOFENCE_ID), p.getGeofenceIds(),
                    "low-accuracy drift " + i + ": inherited geofenceIds via accuracy skip");
        }

        // A good-accuracy drift point beyond maxDistance: anchor must still be locked,
        // so it starts a fresh awayStreak (1/5) and gets filtered.
        Position goodDrift = position(BASE_LAT, DRIFT_LON);
        goodDrift.setAccuracy(10);
        handler.onPosition(goodDrift, countingCallback(new AtomicBoolean()));
        assertEquals(List.of(GEOFENCE_ID), goodDrift.getGeofenceIds(),
                "anchor still locked after low-accuracy drift: good-accuracy drift filtered");
    }

    @Test
    public void testLowAccuracyPointsDoNotBuildAnchor() {
        when(config.getString("filter.geofenceEventAccuracy")).thenReturn("20");
        configureAnchor(30, 5, 50, 5);

        // Feed 5 low-accuracy positions at base — they must NOT build an anchor cluster
        for (int i = 0; i < 5; i++) {
            Position p = position(BASE_LAT, BASE_LON);
            p.setAccuracy(30); // > 20 → skipByAccuracy → no cluster building
            handler.onPosition(p, countingCallback(new AtomicBoolean()));
        }

        // A good-accuracy drift point: no anchor established → NOT filtered by anchor,
        // goes through normal geofence calculation (no matching geofences → null)
        Position drift = position(BASE_LAT, DRIFT_LON);
        drift.setAccuracy(10);
        handler.onPosition(drift, countingCallback(new AtomicBoolean()));
        assertNull(drift.getGeofenceIds(),
                "no anchor was built from low-accuracy points: drift not filtered");
    }

    @Test
    public void testConsistentHighSpeedDepartureStillReleasesAnchor() {
        when(config.getString("filter.geofenceSpeedConsistency")).thenReturn("true");
        configureAnchor(30, 5, 50, 5);

        // circle geofence around the anchor so recomputed nearby points stay "inside"
        Geofence circle = new Geofence();
        circle.setId(GEOFENCE_ID);
        circle.setArea("CIRCLE (" + BASE_LAT + " " + BASE_LON + ", 60)");
        when(cacheManager.getDeviceObjects(anyLong(), eq(Geofence.class))).thenReturn(Set.of(circle));

        buildAnchor(5);

        long t0 = clock;
        Position lastPos = positionWithGeofenceIds(BASE_LAT, BASE_LON, List.of(GEOFENCE_ID));
        lastPos.setFixTime(new Date(t0));
        lastPositionRef.set(lastPos);

        // Realistic hard departure: accelerating 8 m/s^2 (below the 10 m/s^2 plausibility
        // limit), reported speed matching displacement, 1s apart. Distance from anchor:
        // 8, 24, 48m (inside anchorMaxDistance=50), then 80, 120, 168, 224, 288m — five
        // increasing points beyond anchorMaxDistance release the anchor at the last step.
        double metersPerLonDegree = 111320 * Math.cos(Math.toRadians(BASE_LAT));
        double[] speedsMps = {8, 16, 24, 32, 40, 48, 56, 64};
        double[] eastMeters = {8, 24, 48, 80, 120, 168, 224, 288};
        for (int i = 0; i < speedsMps.length; i++) {
            Position p = position(BASE_LAT, BASE_LON + eastMeters[i] / metersPerLonDegree);
            p.setFixTime(new Date(t0 + (i + 1) * 1000L));
            p.setSpeed(speedsMps[i] / 0.514444);
            handler.onPosition(p, countingCallback(new AtomicBoolean()));
            if (i < speedsMps.length - 1) {
                assertEquals(List.of(GEOFENCE_ID), p.getGeofenceIds(),
                        "step " + i + ": inside anchor range or filtered while away streak builds");
            } else {
                assertNotEquals(List.of(GEOFENCE_ID), p.getGeofenceIds(),
                        "step " + i + ": consistent movement must release the anchor");
            }
            lastPositionRef.set(p);
        }
    }

    @Test
    public void testFakeSpeedCreepIsFrozenAndInherits() {
        when(config.getString("filter.geofenceSpeedConsistency")).thenReturn("true");
        configureAnchor(30, 5, 50, 5);

        buildAnchor(5);

        long t0 = clock;
        Position lastPos = positionWithGeofenceIds(BASE_LAT, BASE_LON, List.of(GEOFENCE_ID));
        lastPos.setFixTime(new Date(t0));
        lastPositionRef.set(lastPos);

        // Drift creep: claims 80 knots (~41 m/s) but only moves ~5m per second.
        // Implied speed ≈ 5 m/s < 41 * 0.4 → fakeSpeed → frozen + inherit.
        // Cumulative displacement passes anchorMaxDistance but the anchor never releases.
        for (int i = 1; i <= 20; i++) {
            Position p = position(BASE_LAT, BASE_LON + i * 0.00006);
            p.setFixTime(new Date(t0 + i * 1000L));
            p.setSpeed(80);
            handler.onPosition(p, countingCallback(new AtomicBoolean()));
            assertEquals(List.of(GEOFENCE_ID), p.getGeofenceIds(),
                    "creep " + i + ": fake-speed point must inherit geofenceIds");
            lastPositionRef.set(p);
        }
    }

    @Test
    public void testAnchorStateFlipFiltersBoundaryDrift() {
        // 复现 2026-07-31 19:48 (UTC+8) 的边界漂移：设备长时间静止在家围栏内，
        // 一个漂移点落在锚点放行圈内（<50m）却越过围栏边界，方案 C 应拦住它，
        // 继承锚点围栏状态并标记 skipped，使下游防抖计数不推进。
        // 坐标已等量平移脱敏（lat+5, lon-8），相对几何关系与原始数据完全一致。
        when(config.getString("filter.geofenceEventAccuracy")).thenReturn("20");
        configureAnchor(30, 5, 50, 5);

        // 脱敏家围栏多边形（原始坐标等量平移 lat+5, lon-8）
        Geofence home = new Geofence();
        home.setId(GEOFENCE_ID);
        home.setArea("POLYGON ((35.30160967824608 112.20360753223486, "
                + "35.302433018113717 112.20462670203165, "
                + "35.301407428001042 112.20618992433879, "
                + "35.300960448967626 112.2056050577438, "
                + "35.300801270828643 112.20537814409246, "
                + "35.300747572078627 112.20529494942407, "
                + "35.300523446548993 112.20499636543155, "
                + "35.30160967824608 112.20360753223486))");
        when(cacheManager.getDeviceObjects(anyLong(), eq(Geofence.class))).thenReturn(Set.of(home));

        // 脱敏锚点位置：设备停留点（原始 30.301943, 120.205066），在围栏内
        double anchorLat = 35.301943;
        double anchorLon = 112.205066;

        // 建立锚点：5 个静止点（accuracy<20 通过精度过滤，未配速度黑名单所以 speed=0 正常算围栏）
        for (int i = 0; i < 5; i++) {
            Position p = position(anchorLat, anchorLon);
            p.setAccuracy(14);
            handler.onPosition(p, countingCallback(new AtomicBoolean()));
        }

        // 设 lastPosition 带围栏状态 [GEOFENCE_ID]，模拟设备静止时已稳定在围栏内
        Position lastPos = positionWithGeofenceIds(anchorLat, anchorLon, List.of(GEOFENCE_ID));
        lastPositionRef.set(lastPos);

        // 脱敏漂移点（原始 30.302312, 120.204899）：距锚点 ~46.7m（放行圈内），
        // 但落在围栏东北边界外 ~8.5m。speed=0.12 节逃过零速黑名单，accuracy=14.97 通过精度过滤。
        Position drift = position(35.302312, 112.204899);
        drift.setAccuracy(14.97);
        drift.setSpeed(0.12);
        handler.onPosition(drift, countingCallback(new AtomicBoolean()));

        // 方案 C：状态翻转 → 被 skip，继承锚点围栏状态
        assertEquals(List.of(GEOFENCE_ID), drift.getGeofenceIds(),
                "边界漂移点应被方案C拦截，继承锚点围栏状态");
        assertTrue(drift.getBoolean(GeofenceHandler.ATTRIBUTE_GEOFENCE_SKIPPED),
                "漂移点应标记为 skipped，不推进防抖计数");
    }

    @Test
    public void testAnchorBaselineUsesGeometryNotInheritedState() {
        // 复现 2026-08-03 enter 迟到事故里的死锁机制：真实围栏状态在跳过窗口内变过，继承链上
        // 的值已过期；随后设备静止，速度恒为 0 必然命中速度黑名单。锚点就在这样一个"被跳过的帧"
        // 上锁定——若基准取继承值，之后放行圈内每个真实在围栏内的点都会算出 [GEOFENCE_ID] 与
        // 基准不符而被判状态翻转，永久拦截且无法自愈。基准必须取几何计算结果。
        // 坐标取自真实轨迹并等量平移脱敏（lat+5, lon-8），相对几何关系与原始数据一致。
        when(config.getString("filter.geofenceEventAccuracy")).thenReturn("20");
        when(config.getString("filter.geofenceSpeedBlackLte")).thenReturn("0");
        configureAnchor(20, 5, 50, 5);

        Geofence home = new Geofence();
        home.setId(GEOFENCE_ID);
        home.setArea("POLYGON ((35.30160967824608 112.20360753223486, "
                + "35.302433018113717 112.20462670203165, "
                + "35.301407428001042 112.20618992433879, "
                + "35.300960448967626 112.2056050577438, "
                + "35.300801270828643 112.20537814409246, "
                + "35.300747572078627 112.20529494942407, "
                + "35.300523446548993 112.20499636543155, "
                + "35.30160967824608 112.20360753223486))");
        when(cacheManager.getDeviceObjects(anyLong(), eq(Geofence.class))).thenReturn(Set.of(home));

        // 上一个点没有围栏状态：真实状态变化发生在跳过窗口内，继承链上的值始终是空
        lastPositionRef.set(positionWithGeofenceIds(35.301590158235115, 112.204935263318021, null));

        // 静止点：真实轨迹 5 个连续点，第三列是相对起点的秒数（跨时 242 秒、净位移 0.2 米），
        // 均在围栏内、精度合格、速度 0 → 每个点都被速度黑名单跳过，锚点在被跳过的帧上锁定
        double[][] restPoints = {
                {35.301590158235115, 112.204935263318021, 0, 3.9},
                {35.301590463920988, 112.204934962866091, 60, 3.8},
                {35.301590746462466, 112.204934679488375, 121, 3.8},
                {35.301590996965089, 112.204934430484769, 181, 3.7},
                {35.301591222564916, 112.204934205889586, 242, 3.7},
        };
        long base = clock;
        for (double[] point : restPoints) {
            Position p = positionAt(point[0], point[1], base + (long) point[2] * 1000);
            p.setAccuracy(point[3]);
            p.setSpeed(0);
            handler.onPosition(p, countingCallback(new AtomicBoolean()));
            assertTrue(p.getBoolean(GeofenceHandler.ATTRIBUTE_GEOFENCE_SKIPPED),
                    "静止点被速度黑名单跳过，锚点正是在这样的帧上锁定");
            assertNull(p.getGeofenceIds(), "跳过的点只能继承到空的围栏状态");
            lastPositionRef.set(p);
        }

        // 真实走动点：距锚点 12.8m（放行圈内）、真实在围栏内、精度 8.3 合格、速度 1.92 逃过零速黑名单
        Position moving = positionAt(35.301691434059144, 112.204869971132908, base + 300_000);
        moving.setAccuracy(8.3);
        moving.setSpeed(1.92);
        handler.onPosition(moving, countingCallback(new AtomicBoolean()));

        assertNull(moving.getAttributes().get(GeofenceHandler.ATTRIBUTE_GEOFENCE_SKIPPED),
                "锚点基准取几何值后，圈内真实状态不再被误判为翻转");
        assertEquals(List.of(GEOFENCE_ID), moving.getGeofenceIds(),
                "该点应正常算出所在围栏，而不是继承过期的空状态");
    }

    @Test
    public void testStaleAnchorAbandonedWhenFarBeyondCeiling() {
        // 复现"幽灵锚点"：锚点在家锁定后，设备坐地铁跨城移动，沿途低精度点让锚点状态一直冻结，
        // 出站时锚点已在 18 公里外。此时径向距离随步行朝向随机涨落，away streak 反复清零，
        // 锚点永远释放不了。距离硬上限必须在第一个远点就直接丢弃它。
        // 坐标取自真实轨迹并等量平移脱敏（lat+5, lon-8）。
        when(config.getString("filter.geofenceEventAccuracy")).thenReturn("20");
        configureAnchor(20, 5, 50, 5);

        // 家里的静止点：真实轨迹 5 个连续点，第三列是相对起点的秒数
        // （跨时 242 秒、净位移 0.2 米 → 满足锚点最小时长，锚点锁定）
        double[][] restAtHome = {
                {35.301590158235115, 112.204935263318021, 0},
                {35.301590463920988, 112.204934962866091, 60},
                {35.301590746462466, 112.204934679488375, 121},
                {35.301590996965089, 112.204934430484769, 181},
                {35.301591222564916, 112.204934205889586, 242},
        };
        long base = clock;
        for (double[] point : restAtHome) {
            Position p = positionAt(point[0], point[1], base + (long) point[2] * 1000);
            p.setAccuracy(3.9);
            handler.onPosition(p, countingCallback(new AtomicBoolean()));
        }

        Position lastPos = positionWithGeofenceIds(
                restAtHome[0][0], restAtHome[0][1], List.of(GEOFENCE_ID));
        lastPositionRef.set(lastPos);

        // 出地铁：设备出现在 18 公里外的公司，精度合格
        Position farAway = positionAt(35.286344880488770, 112.018028516053320, base + 300_000);
        farAway.setAccuracy(14.4);
        handler.onPosition(farAway, countingCallback(new AtomicBoolean()));

        assertNull(farAway.getAttributes().get(GeofenceHandler.ATTRIBUTE_GEOFENCE_SKIPPED),
                "远超硬上限的点应立即丢弃陈旧锚点，而不是被当作漂移拦截");
        assertNotEquals(List.of(GEOFENCE_ID), farAway.getGeofenceIds(),
                "丢弃锚点后应正常计算围栏，不继承家的围栏状态");
    }

    @Test
    public void testWalkingDoesNotLockAnchorButRestingDoes() {
        // 锚点原本只要求"连续 anchorCount 个点靠得近"，慢速步行在十几秒内就能凑够，于是步行
        // 也会锁定锚点，形成"锁定→走出放行圈→攒 streak→释放→立刻重锁"的振荡，把真实移动的点
        // 当成漂移拦掉。加上聚类窗口最小时长后，静止的判据变成"一段时间没挪动"，步行不再误锁，
        // 真正的静止仍然锁定。坐标取自真实轨迹并等量平移脱敏（lat+5, lon-8）。
        when(config.getString("filter.geofenceEventAccuracy")).thenReturn("20");
        configureAnchor(20, 5, 50, 5);

        // 真实离家步行轨迹：19 个点，跨时 64 秒，净位移 104 米（第三列为相对起点的秒数）
        double[][] walking = {
                {35.301671550819663, 112.204153279741661, 0},
                {35.301722593443593, 112.204127146067592, 4},
                {35.301755068501564, 112.204081371928297, 7},
                {35.301788444988894, 112.204033743399791, 10},
                {35.301835901344617, 112.203988495344547, 13},
                {35.301861406007127, 112.203944701070995, 17},
                {35.301899597427862, 112.203897722856510, 21},
                {35.301935844876098, 112.203863082918119, 25},
                {35.301966307627438, 112.203822067991922, 28},
                {35.301991849997606, 112.203773312093929, 32},
                {35.302028477805280, 112.203737497545077, 35},
                {35.302070722131987, 112.203698686122536, 39},
                {35.302117110091331, 112.203665044510174, 44},
                {35.302160870489672, 112.203622074670491, 47},
                {35.302201387066660, 112.203573758777054, 51},
                {35.302235961785854, 112.203534121243564, 55},
                {35.302283197174120, 112.203503058960379, 58},
                {35.302325813903842, 112.203461003588103, 61},
                {35.302364332381487, 112.203425895406255, 64},
        };
        // 步行途中锚点若被锁定，就会进入"锁定→走出放行圈→攒 streak→拦截"的振荡，
        // 把真实移动的点当成漂移拦掉。断言真实步行轨迹一个点都不该被拦。
        long walkBase = clock;
        lastPositionRef.set(positionWithGeofenceIds(
                walking[0][0], walking[0][1], List.of(GEOFENCE_ID)));
        for (int i = 0; i < walking.length; i++) {
            Position p = positionAt(walking[i][0], walking[i][1],
                    walkBase + (long) walking[i][2] * 1000);
            p.setAccuracy(14.2);
            handler.onPosition(p, countingCallback(new AtomicBoolean()));
            assertNull(p.getAttributes().get(GeofenceHandler.ATTRIBUTE_GEOFENCE_SKIPPED),
                    "步行点 " + i + " 不该被锚点拦截：步行不是漂移");
            lastPositionRef.set(p);
        }

        // 真正静止（跨时 242 秒、净位移 0.2 米）仍然锁定锚点，抗漂移能力保留
        double[][] resting = {
                {35.301590158235115, 112.204935263318021, 0},
                {35.301590463920988, 112.204934962866091, 60},
                {35.301590746462466, 112.204934679488375, 121},
                {35.301590996965089, 112.204934430484769, 181},
                {35.301591222564916, 112.204934205889586, 242},
        };
        long restBase = walkBase + 120_000;
        for (double[] point : resting) {
            Position p = positionAt(point[0], point[1], restBase + (long) point[2] * 1000);
            p.setAccuracy(3.9);
            handler.onPosition(p, countingCallback(new AtomicBoolean()));
        }

        // 锚点已锁定：一个 100 米外的漂移点（仍在硬上限内）应被拦截
        Position drift = positionAt(35.302500, 112.204935, restBase + 300_000);
        drift.setAccuracy(10);
        lastPositionRef.set(positionWithGeofenceIds(
                resting[0][0], resting[0][1], List.of(GEOFENCE_ID)));
        handler.onPosition(drift, countingCallback(new AtomicBoolean()));
        assertEquals(List.of(GEOFENCE_ID), drift.getGeofenceIds(),
                "静止建立的锚点仍应拦截漂移点，抗漂移能力不受影响");
    }

    @Test
    public void testHomeAssistantHomeOverride() {
        // HA 判定在家 → 直接认定家围栏，跳过所有过滤和 GPS 计算，即使当前点在围栏外
        when(homeAssistant.isEnabled()).thenReturn(true);
        when(homeAssistant.getState("sensor.yueyue_iphone"))
                .thenReturn(HomeAssistantProvider.HomeState.HOME);
        when(config.getString("homeassistant.entity")).thenReturn("sensor.yueyue_iphone");
        when(config.getString("homeassistant.homeGeofenceId")).thenReturn("100");
        configureAnchor(30, 5, 50, 5);

        // 一个明显在围栏外的漂移点（无匹配围栏，正常计算会得 null）
        Position drift = position(BASE_LAT, DRIFT_LON);
        drift.setAccuracy(10);
        handler.onPosition(drift, countingCallback(new AtomicBoolean()));

        assertEquals(List.of(GEOFENCE_ID), drift.getGeofenceIds(),
                "HA 在家应直接覆盖为家围栏，不做 GPS 计算");
        assertNull(drift.getAttributes().get(GeofenceHandler.ATTRIBUTE_GEOFENCE_SKIPPED),
                "在家覆盖不打 skipped 标记，作为真实在家观测参与防抖");
    }

    @Test
    public void testHomeAssistantNotHomeFallsThrough() {
        // HA 判定不在家 → 不覆盖，走原有 GPS 计算逻辑
        when(homeAssistant.isEnabled()).thenReturn(true);
        when(homeAssistant.getState("sensor.yueyue_iphone"))
                .thenReturn(HomeAssistantProvider.HomeState.NOT_HOME);
        when(config.getString("homeassistant.entity")).thenReturn("sensor.yueyue_iphone");
        when(config.getString("homeassistant.homeGeofenceId")).thenReturn("100");

        Position p = position(BASE_LAT, DRIFT_LON);
        handler.onPosition(p, countingCallback(new AtomicBoolean()));

        assertNull(p.getGeofenceIds(),
                "不在家不覆盖，正常计算无匹配围栏得 null");
    }
}

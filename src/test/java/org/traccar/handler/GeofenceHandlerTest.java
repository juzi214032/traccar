package org.traccar.handler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.traccar.config.Config;
import org.traccar.model.Device;
import org.traccar.model.Geofence;
import org.traccar.model.Position;
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

    private CacheManager cacheManager;
    private Config config;
    private GeofenceHandler handler;
    private AtomicReference<Position> lastPositionRef;

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

        handler = new GeofenceHandler(cacheManager);
    }

    // ---- helpers ----

    private Position position(double lat, double lon) {
        Position p = new Position();
        p.setDeviceId(DEVICE_ID);
        p.setFixTime(new Date());
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

        // Phase 2: progressively further positions → release anchor after releaseCount
        // Each step increases lon by ~85m, so each step is further from anchor than the last.
        for (int i = 1; i <= 5; i++) {
            double lon = BASE_LON + i * 0.001; // +85m, +170m, +255m, +340m, +425m
            Position p = position(BASE_LAT, lon);
            handler.onPosition(p, countingCallback(new AtomicBoolean()));

            if (i < 5) {
                // i=1..4: still filtered (awayStreak < releaseCount)
                assertEquals(List.of(GEOFENCE_ID), p.getGeofenceIds(),
                        "step " + i + ": should be filtered, reuse lastPosition geofenceIds");
            } else {
                // i=5: awayStreak reaches releaseCount → anchor released.
                // The releasing position itself is NOT filtered (skipByAnchor stays false
                // when the release path is taken), so it goes through normal geofence calc.
                assertNotEquals(List.of(GEOFENCE_ID), p.getGeofenceIds(),
                        "step " + i + ": anchor released, should NOT reuse old geofenceIds");
            }
        }

        // After release, the next position should also be normally calculated
        Position afterRelease = position(BASE_LAT, BASE_LON + 6 * 0.001);
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
}

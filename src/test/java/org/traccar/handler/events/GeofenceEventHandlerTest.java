package org.traccar.handler.events;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.traccar.config.Config;
import org.traccar.handler.GeofenceHandler;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.model.Geofence;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class GeofenceEventHandlerTest {

    private static final long DEVICE_ID = 1L;
    private static final long GEOFENCE_ID = 100L;

    private CacheManager cacheManager;
    private Config config;
    private GeofenceEventHandler handler;
    private List<Event> capturedEvents;

    @BeforeEach
    public void setUp() {
        config = mock(Config.class);
        cacheManager = mock(CacheManager.class);
        when(cacheManager.getConfig()).thenReturn(config);

        Device device = mock(Device.class);
        when(device.getId()).thenReturn(DEVICE_ID);
        when(cacheManager.getObject(Device.class, DEVICE_ID)).thenReturn(device);

        Geofence geofence = mock(Geofence.class);
        when(geofence.getCalendarId()).thenReturn(0L);
        when(cacheManager.getObject(Geofence.class, GEOFENCE_ID)).thenReturn(geofence);

        // isLatest always passes (no cached position → always latest)
        when(cacheManager.getPosition(DEVICE_ID)).thenReturn(null);

        capturedEvents = new ArrayList<>();
        handler = new GeofenceEventHandler(cacheManager);
    }

    private Position position(Date time, List<Long> geofenceIds) {
        Position p = new Position();
        p.setDeviceId(DEVICE_ID);
        p.setFixTime(time);
        p.setGeofenceIds(geofenceIds);
        p.setValid(true);
        return p;
    }

    private BaseEventHandler.Callback callback() {
        return capturedEvents::add;
    }

    @Test
    public void testEnterDebounce() {
        // threshold=2: need > 2 consecutive (i.e., 3) IN points after first transition
        when(config.getString("filter.geofenceEventEnterPositionCountBlackLte")).thenReturn("2");

        long t = System.currentTimeMillis();

        // #1: establish OUT state (empty geofenceIds)
        // DebounceState created: stable={}, pending={}, pendingCount=1
        handler.onPosition(position(new Date(t), List.of()), callback());
        assertEquals(0, capturedEvents.size());

        // #2: transition to IN → reset: pending={100}, pendingCount=1
        handler.onPosition(position(new Date(t + 1000), List.of(GEOFENCE_ID)), callback());
        assertEquals(0, capturedEvents.size(), "1st IN: count=1, no event");

        // #3: second IN → count=2, 2 ≤ 2, no event
        handler.onPosition(position(new Date(t + 2000), List.of(GEOFENCE_ID)), callback());
        assertEquals(0, capturedEvents.size(), "2nd IN: count=2 ≤ 2, no event");

        // #4: third IN → count=3 > 2, enteredIds={100}-{}={100} → fire ENTER
        handler.onPosition(position(new Date(t + 3000), List.of(GEOFENCE_ID)), callback());
        assertEquals(1, capturedEvents.size(), "3rd IN: count=3 > 2, ENTER fires");
        assertEquals(Event.TYPE_GEOFENCE_ENTER, capturedEvents.get(0).getType());
        assertEquals(GEOFENCE_ID, capturedEvents.get(0).getGeofenceId());
    }

    @Test
    public void testExitDebounce() {
        // threshold=1: need > 1 consecutive (i.e., 2) OUT points after first transition
        when(config.getString("filter.geofenceEventExitPositionCountBlackLte")).thenReturn("1");

        long t = System.currentTimeMillis();

        // #1: establish IN state
        // DebounceState created: stable={100}, pending={100}, pendingCount=1
        handler.onPosition(position(new Date(t), List.of(GEOFENCE_ID)), callback());
        assertEquals(0, capturedEvents.size());

        // #2: transition to OUT → reset: pending={}, pendingCount=1
        handler.onPosition(position(new Date(t + 1000), List.of()), callback());
        assertEquals(0, capturedEvents.size(), "1st OUT: count=1, no event");

        // #3: second OUT → count=2 > 1, exitedIds={100}-{}={100} → fire EXIT
        handler.onPosition(position(new Date(t + 2000), List.of()), callback());
        assertEquals(1, capturedEvents.size(), "2nd OUT: count=2 > 1, EXIT fires");
        assertEquals(Event.TYPE_GEOFENCE_EXIT, capturedEvents.get(0).getType());
        assertEquals(GEOFENCE_ID, capturedEvents.get(0).getGeofenceId());
    }

    @Test
    public void testFluctuationFiltered() {
        // Both enter and exit debounce = 2 (need > 2 consecutive = 3 points to confirm)
        when(config.getString("filter.geofenceEventEnterPositionCountBlackLte")).thenReturn("2");
        when(config.getString("filter.geofenceEventExitPositionCountBlackLte")).thenReturn("2");

        long t = System.currentTimeMillis();

        // #1: establish IN state: stable={100}
        handler.onPosition(position(new Date(t), List.of(GEOFENCE_ID)), callback());
        assertEquals(0, capturedEvents.size());

        // #2: stay IN, count=2
        handler.onPosition(position(new Date(t + 1000), List.of(GEOFENCE_ID)), callback());
        assertEquals(0, capturedEvents.size());

        // #3: drift OUT → reset, pending={}, count=1
        handler.onPosition(position(new Date(t + 2000), List.of()), callback());
        assertEquals(0, capturedEvents.size());

        // #4: still OUT → count=2, exitedIds={100}, 2 ≤ 2 → no EXIT
        handler.onPosition(position(new Date(t + 3000), List.of()), callback());
        assertEquals(0, capturedEvents.size());

        // #5: drift back IN → reset, pending={100}, count=1
        handler.onPosition(position(new Date(t + 4000), List.of(GEOFENCE_ID)), callback());
        assertEquals(0, capturedEvents.size());

        // #6: stay IN → count=2, enteredIds={}, 2 ≤ 2 → no event
        handler.onPosition(position(new Date(t + 5000), List.of(GEOFENCE_ID)), callback());
        assertEquals(0, capturedEvents.size());

        // The 2-point OUT fluctuation was filtered — 0 events total
        assertEquals(0, capturedEvents.size(), "2-point fluctuation filtered, 0 events");
    }

    @Test
    public void testNoDebounceConfigured() {
        // No debounce config → original immediate trigger logic
        long t = System.currentTimeMillis();

        // First position: empty geofenceIds
        Position lastPos = position(new Date(t), List.of());
        when(cacheManager.getPosition(DEVICE_ID)).thenReturn(lastPos);
        handler.onPosition(position(new Date(t + 1000), List.of()), callback());
        assertEquals(0, capturedEvents.size());

        // Second position: enters geofence → immediate ENTER (no debounce)
        Position prev = position(new Date(t + 1000), List.of());
        when(cacheManager.getPosition(DEVICE_ID)).thenReturn(prev);
        handler.onPosition(position(new Date(t + 2000), List.of(GEOFENCE_ID)), callback());
        assertEquals(1, capturedEvents.size(), "ENTER fires immediately without debounce");
        assertEquals(Event.TYPE_GEOFENCE_ENTER, capturedEvents.get(0).getType());
    }

    @Test
    public void testZeroThresholdNoDebounce() {
        // threshold=0 means > 0 (i.e. 1 point is enough) → debounce but effectively instant
        when(config.getString("filter.geofenceEventExitPositionCountBlackLte")).thenReturn("0");

        long t = System.currentTimeMillis();

        // #1: establish IN: stable={100}
        handler.onPosition(position(new Date(t), List.of(GEOFENCE_ID)), callback());

        // #2: OUT → reset count=1, 1 > 0 → fire EXIT immediately
        handler.onPosition(position(new Date(t + 1000), List.of()), callback());
        assertEquals(0, capturedEvents.size(), "1st OUT: count=1, no event yet");
    }

    private Position positionWithCoords(Date time, double lat, double lon,
                                        double speedKn, List<Long> geofenceIds) {
        Position p = position(time, geofenceIds);
        p.setLatitude(lat);
        p.setLongitude(lon);
        p.setSpeed(speedKn);
        return p;
    }

    @Test
    public void testExitDelayedConfirmTeleportReturnDiscards() {
        // 复现 7/28 19:30 漂移：高速冲出触发 exit → 8秒后 teleport 跳回 → 丢弃。
        // 坐标等量平移脱敏（lat+5, lon-8），几何关系与原始数据一致。
        when(config.getString("filter.geofenceEventExitPositionCountBlackLte")).thenReturn("1");
        when(config.getString("filter.geofenceExitConfirmWindow")).thenReturn("15");

        Geofence home = new Geofence();
        home.setId(GEOFENCE_ID);
        home.setCalendarId(0L);
        home.setArea("CIRCLE (35.3019 112.2050, 100)");
        when(cacheManager.getObject(Geofence.class, GEOFENCE_ID)).thenReturn(home);

        long t = System.currentTimeMillis();

        // #1: 设备在围栏内，建立 stable={100}
        handler.onPosition(positionWithCoords(
                new Date(t), 35.3019, 112.2050, 0, List.of(GEOFENCE_ID)), callback());

        // #2: 冲出到围栏外（~160m）→ reset, count=1
        handler.onPosition(positionWithCoords(
                new Date(t + 1000), 35.301998, 112.203431, 34, List.of()), callback());
        assertEquals(0, capturedEvents.size(), "1st OUT: reset, no event");

        // #3: 仍在围栏外 → count=2 > 1 → exit 挂起（延迟确认，无事件）
        handler.onPosition(positionWithCoords(
                new Date(t + 2000), 35.301998, 112.203431, 34, List.of()), callback());
        assertEquals(0, capturedEvents.size(), "exit pending, no event yet");

        // #4: 8秒后 teleport 跳回围栏内（位移~181m/8s≈22.6m/s，speed=0，落回围栏内）。
        // 跳回点被 GeofenceHandler skip，继承空 geofenceIds，但坐标仍用于 teleport 判定。
        Position ret = positionWithCoords(
                new Date(t + 10000), 35.301368, 112.205176, 0, List.of());
        ret.set(GeofenceHandler.ATTRIBUTE_GEOFENCE_SKIPPED, true);
        handler.onPosition(ret, callback());
        assertEquals(0, capturedEvents.size(), "teleport return discards exit, 0 events");
    }

    @Test
    public void testExitDelayedConfirmWindowExpires() {
        when(config.getString("filter.geofenceEventExitPositionCountBlackLte")).thenReturn("1");
        when(config.getString("filter.geofenceExitConfirmWindow")).thenReturn("15");

        Geofence home = new Geofence();
        home.setId(GEOFENCE_ID);
        home.setCalendarId(0L);
        home.setArea("CIRCLE (35.3019 112.2050, 100)");
        when(cacheManager.getObject(Geofence.class, GEOFENCE_ID)).thenReturn(home);

        long t = System.currentTimeMillis();

        // #1: 在内，stable={100}
        handler.onPosition(positionWithCoords(
                new Date(t), 35.3019, 112.2050, 0, List.of(GEOFENCE_ID)), callback());

        // #2: 冲出，reset, count=1
        handler.onPosition(positionWithCoords(
                new Date(t + 1000), 35.302, 112.203, 34, List.of()), callback());

        // #3: 仍在外，count=2 > 1 → exit 挂起
        handler.onPosition(positionWithCoords(
                new Date(t + 2000), 35.302, 112.203, 34, List.of()), callback());
        assertEquals(0, capturedEvents.size(), "exit pending");

        // #4: 16秒后（超过 15s 窗口），仍在外 → 确认 exit
        handler.onPosition(positionWithCoords(
                new Date(t + 18000), 35.302, 112.203, 34, List.of()), callback());
        assertEquals(1, capturedEvents.size(), "window expired, exit confirmed");
        assertEquals(Event.TYPE_GEOFENCE_EXIT, capturedEvents.get(0).getType());
        assertEquals(GEOFENCE_ID, capturedEvents.get(0).getGeofenceId());
    }

    @Test
    public void testExitDelayedConfirmOverwrite() {
        // 已有挂起 exit A 时，新 exit B 触发应先确认 A（未被丢弃说明是真实 exit）。
        when(config.getString("filter.geofenceEventExitPositionCountBlackLte")).thenReturn("0");
        when(config.getString("filter.geofenceEventEnterPositionCountBlackLte")).thenReturn("0");
        when(config.getString("filter.geofenceExitConfirmWindow")).thenReturn("30");

        // mock geofence：containsPosition 恒真；所有点同位置（位移 0 → 非 teleport）
        Geofence home = mock(Geofence.class);
        when(home.getCalendarId()).thenReturn(0L);
        when(home.containsPosition(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        when(cacheManager.getObject(Geofence.class, GEOFENCE_ID)).thenReturn(home);

        long t = System.currentTimeMillis();

        // #1: IN, stable={100}
        handler.onPosition(positionWithCoords(
                new Date(t), 35.3019, 112.2050, 0, List.of(GEOFENCE_ID)), callback());
        // #2: OUT, reset, return
        handler.onPosition(positionWithCoords(
                new Date(t + 1000), 35.3019, 112.2050, 0, List.of()), callback());
        // #3: OUT, count=2 > 0 → exit A 挂起 (triggerTime=t+2000), stable={}
        handler.onPosition(positionWithCoords(
                new Date(t + 2000), 35.3019, 112.2050, 0, List.of()), callback());
        assertEquals(0, capturedEvents.size(), "exit A pending");

        // #4: IN, reset, return (A 保留：位移 0 非 teleport)
        handler.onPosition(positionWithCoords(
                new Date(t + 3000), 35.3019, 112.2050, 0, List.of(GEOFENCE_ID)), callback());
        // #5: IN, count=2 > 0 → ENTER, stable={100}
        handler.onPosition(positionWithCoords(
                new Date(t + 4000), 35.3019, 112.2050, 0, List.of(GEOFENCE_ID)), callback());
        assertEquals(1, capturedEvents.size(), "ENTER fired");

        // #6: OUT, reset, return
        handler.onPosition(positionWithCoords(
                new Date(t + 5000), 35.3019, 112.2050, 0, List.of()), callback());
        // #7: OUT, count=2 > 0 → exit B: existing A → 确认 A, 挂起 B
        handler.onPosition(positionWithCoords(
                new Date(t + 6000), 35.3019, 112.2050, 0, List.of()), callback());
        assertEquals(2, capturedEvents.size(), "ENTER + exit A confirmed on overwrite");
        assertEquals(Event.TYPE_GEOFENCE_EXIT, capturedEvents.get(1).getType());
    }
}

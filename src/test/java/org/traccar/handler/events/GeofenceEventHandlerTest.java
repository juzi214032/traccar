package org.traccar.handler.events;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.traccar.config.Config;
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
}

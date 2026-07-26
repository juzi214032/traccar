package org.traccar.handler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.traccar.config.Config;
import org.traccar.handler.events.GeofenceEventHandler;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.model.Geofence;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Replays the real GPS drift incident of 2026-07-26 05:51 UTC (device "玥玥_traccar"):
 * the device sat stationary at home, then the GPS fabricated a physically self-consistent
 * straight-line track due east — constant latitude, reported speed (64 m/s) matching the
 * displacement speed exactly. The speed-consistency check cannot catch such fabrications
 * by design; the anchor released after 5 coherent away points (5 seconds at 1 Hz) and six
 * computed-outside points defeated the debounce, firing a false geofenceExit at 05:51:20.
 * The only kinematic tell is the impossible jump from standstill to 63.7 m/s in one second
 * (64 m/s^2, about 6.5 g), which the acceleration plausibility check now intercepts.
 * Positions and the geofence polygon are taken from the production database with a uniform
 * coordinate offset applied for privacy; the offset preserves all distances and geometry.
 * The test asserts that neither geofenceExit nor geofenceEnter fires.
 */
public class GeofenceFabricatedTrackIncidentTest {

    private static final long DEVICE_ID = 5L;
    private static final long GEOFENCE_ID = 1L;

    private static final String HOME_AREA = "POLYGON ((30.31530967824608 86.48990753223486, "
            + "30.316133018113717 86.49092670203165, 30.315107428001042 86.49248992433879, "
            + "30.314660448967626 86.4919050577438, 30.314501270828643 86.49167814409246, "
            + "30.314447572078627 86.49159494942407, 30.314223446548993 86.49129636543155, "
            + "30.31530967824608 86.48990753223486))";

    /** {offsetSeconds, latitude, longitude, speedKnots, accuracyMeters} from tc_positions 551422-551447. */
    private static final double[][] TRACK = {
            // 03:13 warm-up: last non-skipped moving point inside the geofence, hours
            // before the incident — seeds the debounce state to "inside" as in production
            {-9312, 30.315312440200444, 86.49136107738993, 2.89, 15.3},
            // 05:43-05:47 stationary at home with excellent accuracy, anchor builds here
            {-306, 30.314989121457952, 86.4915411956298, 0, 3.0},
            {-246, 30.31498890339433, 86.49153882080829, 0, 2.9},
            {-186, 30.314988892121114, 86.49153792849022, 0, 2.8},
            {-121, 30.314988376297418, 86.49153918576997, 0, 3.8},
            {-61, 30.31498686062801, 86.4915367222599, 0, 5.0},
            // stationary at home (05:48:31 - 05:51:11)
            {0, 30.314990264327626, 86.4915382153835, 0, 5.4},
            {65, 30.314977735616672, 86.49153009820161, 0, 14.9},
            {125, 30.314953120799593, 86.49152015658909, 0, 24.3},
            {132, 30.314907704906105, 86.49148464092329, 0, 34.9},
            {150, 30.314951186429397, 86.4915116001587, 0, 18.5},
            {160, 30.315003173337224, 86.4915309779233, 0, 16.8},
            // 05:51:12 fabricated track: 0 -> 123.9 knots (63.7 m/s) in one second,
            // then a dead-straight line east at constant latitude, reported speed
            // matching displacement — self-consistent, only the onset jump is impossible
            {161, 30.315016561475243, 86.49142678641425, 123.9, 15.8},
            {163, 30.3150296394474, 86.49222343345257, 124.51, 16.6},
            {164, 30.315029640072378, 86.49290120403068, 124.51, 18.9},
            {165, 30.315029640250714, 86.49354283350518, 124.56, 19.2},
            {166, 30.315029640257418, 86.4942010652404, 124.56, 19.2},
            {167, 30.3150296402619, 86.49485947012406, 81.21, 19.3},
            {168, 30.3150296402621, 86.49551700574705, 81.21, 19.4},
            {169, 30.31502964026457, 86.49617419913979, 70.09, 19.5},
            {170, 30.315029640264242, 86.49683225133353, 62.3, 19.6},
            {171, 30.31502964026591, 86.4974883057532, 52.39, 19.8},
            {173, 30.315029640262445, 86.49815063110026, 52.39, 20.0},
            // 05:51:36 teleport back home, stationary again
            {185, 30.315391357937223, 86.49128447173416, 0, 14.0},
            {185, 30.314965674517513, 86.49154824085741, 0, 20.5},
            {191, 30.314982222957948, 86.49147434262611, 0, 38.2},
            {191, 30.31496262925217, 86.49155112872706, 0, 18.3},
            {257, 30.314952804929568, 86.49154329727691, 0, 10.1},
    };

    private CacheManager cacheManager;
    private GeofenceHandler geofenceHandler;
    private GeofenceEventHandler eventHandler;
    private AtomicReference<Position> lastPositionRef;

    @BeforeEach
    public void setUp() {
        Config config = mock(Config.class);
        cacheManager = mock(CacheManager.class);
        when(cacheManager.getConfig()).thenReturn(config);

        Device device = mock(Device.class);
        when(device.getId()).thenReturn(DEVICE_ID);
        when(device.getAttributes()).thenReturn(new HashMap<>());
        when(device.getGroupId()).thenReturn(0L);
        when(cacheManager.getObject(eq(Device.class), anyLong())).thenReturn(device);

        // production filter configuration from traccar.xml
        when(config.getString("filter.geofenceEventAccuracy")).thenReturn("20");
        when(config.getString("filter.geofenceSpeedBlackLte")).thenReturn("0");
        when(config.getString("filter.geofenceEventEnterPositionCountBlackLte")).thenReturn("2");
        when(config.getString("filter.geofenceEventExitPositionCountBlackLte")).thenReturn("2");
        when(config.getString("filter.geofenceAnchorRadius")).thenReturn("30");
        when(config.getString("filter.geofenceAnchorCount")).thenReturn("5");
        when(config.getString("filter.geofenceAnchorMaxDistance")).thenReturn("50");
        when(config.getString("filter.geofenceAnchorReleaseCount")).thenReturn("5");
        when(config.getString("filter.geofenceSpeedConsistency")).thenReturn("true");

        Geofence home = new Geofence();
        home.setId(GEOFENCE_ID);
        home.setArea(HOME_AREA);
        when(cacheManager.getDeviceObjects(eq(DEVICE_ID), eq(Geofence.class))).thenReturn(Set.of(home));
        when(cacheManager.getObject(eq(Geofence.class), eq(GEOFENCE_ID))).thenReturn(home);

        lastPositionRef = new AtomicReference<>();
        when(cacheManager.getPosition(DEVICE_ID)).thenAnswer(inv -> lastPositionRef.get());

        geofenceHandler = new GeofenceHandler(cacheManager);
        eventHandler = new GeofenceEventHandler(cacheManager);
    }

    @Test
    public void testFabricatedTrackDoesNotFireGeofenceEvents() {
        long baseTime = 1_000_000_000_000L;

        // seed: device has been inside the geofence long before the warm-up point
        Position seed = new Position();
        seed.setDeviceId(DEVICE_ID);
        seed.setFixTime(new Date(baseTime - 9_400_000L));
        seed.setLatitude(30.314990264327626);
        seed.setLongitude(86.4915382153835);
        seed.setValid(true);
        seed.setGeofenceIds(List.of(GEOFENCE_ID));
        lastPositionRef.set(seed);

        List<Event> events = new ArrayList<>();
        List<String> eventLog = new ArrayList<>();

        for (double[] row : TRACK) {
            Position p = new Position();
            p.setDeviceId(DEVICE_ID);
            p.setFixTime(new Date(baseTime + (long) row[0] * 1000));
            p.setLatitude(row[1]);
            p.setLongitude(row[2]);
            p.setSpeed(row[3]);
            p.setAccuracy(row[4]);
            p.setValid(true);

            geofenceHandler.onPosition(p, filtered -> { });
            eventHandler.onPosition(p, event -> {
                events.add(event);
                if (event.getType().equals(Event.TYPE_GEOFENCE_EXIT)
                        || event.getType().equals(Event.TYPE_GEOFENCE_ENTER)) {
                    eventLog.add(event.getType() + " at offset " + (long) row[0] + "s");
                }
            });
            lastPositionRef.set(p);
        }

        assertTrue(eventLog.isEmpty(),
                "fabricated track replay must not fire geofence events, but got: " + eventLog
                        + " (all events: " + events.stream().map(Event::getType).toList() + ")");
    }
}

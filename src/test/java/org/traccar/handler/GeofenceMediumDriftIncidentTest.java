package org.traccar.handler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.traccar.config.Config;
import org.traccar.handler.events.GeofenceEventHandler;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.model.Geofence;
import org.traccar.model.Position;
import org.traccar.session.HomeAssistantProvider;
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
 * Replays the real GPS drift incident of 2026-07-25 14:32 UTC (device "玥玥_traccar"):
 * the device sat stationary at home, then produced a burst of medium-amplitude drift —
 * coordinates jumping 20-30 m every 1-2 seconds with good self-reported accuracy (14-20 m)
 * and plausible walking speeds (2-6 m/s). Implied speeds sat in the 13-14 m/s band, just
 * under the original 15 m/s teleport floor, so the consistency filter missed the poison
 * point and skipped points inherited its wrong result to defeat the debounce, firing a
 * false geofenceExit at 14:32:52 and a false geofenceEnter 5 seconds later.
 * Positions and the geofence polygon are taken from the production database with a uniform
 * coordinate offset applied for privacy; the offset preserves all distances and geometry.
 * The test asserts that neither geofenceExit nor geofenceEnter fires.
 */
public class GeofenceMediumDriftIncidentTest {

    private static final long DEVICE_ID = 5L;
    private static final long GEOFENCE_ID = 1L;

    private static final String HOME_AREA = "POLYGON ((30.31530967824608 86.48990753223486, "
            + "30.316133018113717 86.49092670203165, 30.315107428001042 86.49248992433879, "
            + "30.314660448967626 86.4919050577438, 30.314501270828643 86.49167814409246, "
            + "30.314447572078627 86.49159494942407, 30.314223446548993 86.49129636543155, "
            + "30.31530967824608 86.48990753223486))";

    /** {offsetSeconds, latitude, longitude, speedKnots, accuracyMeters} from tc_positions 548190-548220. */
    private static final double[][] TRACK = {
            // stationary at home (14:28:00 - 14:32:02), anchor builds here
            {0, 30.31556095495433, 86.49106818731447, 0, 14.25},
            {61, 30.31556095495433, 86.49106818731447, 0, 14.25},
            {121, 30.31556095495433, 86.49106818731447, 0, 14.25},
            {182, 30.31556095495433, 86.49106818731447, 0, 14.25},
            {242, 30.31556095495433, 86.49106818731447, 0, 14.25},
            // small wobble near home, still inside
            {259, 30.315601442710676, 86.49099520209045, 0.05, 14.25},
            {267, 30.315502297811257, 86.49108283698503, 0.14, 14.25},
            {271, 30.315488016296502, 86.49103289034335, 1.71, 14.25},
            {279, 30.31553179260894, 86.4910543008966, 2.54, 14.44},
            {280, 30.315601569630445, 86.49107137625113, 3.6, 14.81},
            {281, 30.315575006375653, 86.49116391855871, 1.59, 14.25},
            {282, 30.315641689842785, 86.49120320956752, 4.1, 14.25},
            {284, 30.31574665317128, 86.49127116236865, 6.19, 14.25},
            {286, 30.315791605404637, 86.49128837566947, 5.39, 17.16},
            {287, 30.315742106850873, 86.49127889016364, 3.1, 18.15},
            {288, 30.31563843384102, 86.49127034797725, 0.42, 17.79},
            // 14:32:50-54 the drift burst: 20-30 m jumps, accuracy 15-20, reported 1.5-2.6 m/s;
            // these four points were computed/inherited as outside and fired the false exit
            {290, 30.315843467313797, 86.49141416737463, 5.08, 16.83},
            {292, 30.315891781569505, 86.4914366148169, 4.91, 20.38},
            {293, 30.31567960068836, 86.49126439580259, 2.92, 16.98},
            {294, 30.3155345566948, 86.49115724630485, 3.51, 15.19},
            // 14:32:55+ back inside, false enter fired at 14:32:57
            {295, 30.315417447412006, 86.4910928675387, 6.23, 14.28},
            {297, 30.31581893161164, 86.49130835896766, 5.78, 24.91},
            {298, 30.31537110775633, 86.4910640127072, 6.07, 20.28},
            {299, 30.315287740950264, 86.49103087737365, 7.14, 7.93},
            {301, 30.315225539306095, 86.49099180513139, 8, 8.39},
            {303, 30.31516308539611, 86.4909456034578, 23.65, 13.45},
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

        geofenceHandler = new GeofenceHandler(cacheManager, mock(HomeAssistantProvider.class));
        eventHandler = new GeofenceEventHandler(cacheManager);
    }

    @Test
    public void testMediumDriftDoesNotFireGeofenceEvents() {
        long baseTime = 1_000_000_000_000L;

        // seed: device has been inside the geofence for a long time before the replay
        Position seed = new Position();
        seed.setDeviceId(DEVICE_ID);
        seed.setFixTime(new Date(baseTime - 60_000));
        seed.setLatitude(30.31556095495433);
        seed.setLongitude(86.49106818731447);
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
                "medium drift replay must not fire geofence events, but got: " + eventLog
                        + " (all events: " + events.stream().map(Event::getType).toList() + ")");
    }
}

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
 * Replays the real GPS drift incident of 2026-07-25 06:25-06:36 UTC (device "玥玥_traccar"):
 * the device sat stationary at home, then the GPS produced a fake high-speed track running
 * east out of the geofence, which fired a false geofenceExit at 06:33. Positions and the
 * geofence polygon are taken verbatim from the production database. The production filter
 * configuration is applied. The test asserts that no geofenceExit event fires.
 */
public class GeofenceDriftIncidentTest {

    private static final long DEVICE_ID = 5L;
    private static final long GEOFENCE_ID = 1L;

    private static final String HOME_AREA = "POLYGON ((30.30160967824608 120.20360753223486, "
            + "30.302433018113717 120.20462670203165, 30.301407428001042 120.20618992433879, "
            + "30.300960448967626 120.2056050577438, 30.300801270828643 120.20537814409246, "
            + "30.300747572078627 120.20529494942407, 30.300523446548993 120.20499636543155, "
            + "30.30160967824608 120.20360753223486))";

    /** {offsetSeconds, latitude, longitude, speedKnots, accuracyMeters} from tc_positions. */
    private static final double[][] TRACK = {
            // stationary at home (06:25:04 - 06:30:15), anchor builds here
            {0, 30.30128593890959, 120.20534632772133, 0, 2.0},
            {60, 30.301286094018515, 120.20534717994425, 0, 2.0},
            {125, 30.30128604597184, 120.20534581620367, 0, 2.0},
            {186, 30.30128646287557, 120.20534593882616, 0, 2.0},
            {246, 30.30128677179807, 120.20534495263558, 0, 2.0},
            {311, 30.301286514339512, 120.20534354646796, 0, 2.0},
            // 06:31:16 speed jumps to 90 knots while still at home
            {372, 30.301286833303344, 120.20534360264259, 90.93, 2.0},
            // 06:31:38+ fake high-speed drift running east, low accuracy
            {394, 30.301531925947398, 120.20683929713964, 92.75, 32.4},
            {395, 30.301581094515434, 120.20733208638795, 92.75, 32.4},
            {396, 30.30162211766979, 120.2076589792624, 93.51, 31.2},
            {397, 30.30166585991062, 120.20794403340584, 93.51, 30.2},
            {398, 30.30170055596029, 120.2082005680718, 92.11, 29.4},
            {399, 30.301726144665878, 120.20844087270623, 90.9, 28.8},
            {400, 30.301751384530792, 120.20870893139815, 88.42, 28.5},
            {401, 30.301783157391185, 120.20897635585855, 88.61, 28.3},
            {402, 30.301809824504197, 120.20921946657876, 88.42, 28.2},
            {403, 30.30182853355429, 120.20943410040498, 87.36, 28.0},
            {404, 30.30185563088536, 120.20963342424524, 85.4, 27.8},
            {405, 30.301879127994443, 120.20978742010135, 85.65, 27.6},
            {406, 30.301885753148817, 120.20989794499964, 85.4, 27.2},
            {407, 30.30190198940631, 120.21011860287523, 84.08, 27.3},
            {408, 30.301925850007084, 120.21041871307428, 82.28, 27.7},
            {409, 30.30194840006035, 120.21060650810763, 82.43, 27.8},
            {410, 30.301966832553035, 120.21075698555897, 82.28, 27.7},
            {411, 30.301986549077267, 120.21091180231143, 82.43, 27.7},
            {412, 30.301994916802744, 120.21106501396211, 81.86, 27.7},
            {413, 30.302010410107883, 120.21121137711422, 82.13, 27.7},
            {414, 30.302022331115477, 120.21134495144607, 82.13, 27.7},
            {415, 30.30203673492655, 120.21146993325974, 82.13, 27.6},
            {416, 30.302056518683155, 120.21160835778207, 82.05, 27.7},
            {417, 30.302073415169, 120.2117160394086, 80.72, 27.7},
            {418, 30.30209376715206, 120.21182430714211, 80.72, 27.7},
            {419, 30.30209917730637, 120.21190391602124, 80.12, 27.7},
            {421, 30.302100206243704, 120.2119626328333, 75.38, 27.6},
            {422, 30.302104032498693, 120.21203241432671, 75.38, 27.6},
            {424, 30.30209408116679, 120.21210703026712, 73.28, 27.6},
            {426, 30.302126231194645, 120.21252826482136, 0, 31.6},
            {427, 30.302157432364478, 120.21291739875038, 0, 36.3},
            {428, 30.302188633536467, 120.21330653284309, 0, 43.5},
            {429, 30.302219834712755, 120.21369566709951, 0, 53.0},
            // 06:32:27 chaotic bounce between home and drift track
            {443, 30.301287309621422, 120.20531922562408, 0, 14.2},
            {443, 30.301436088031462, 120.20767279541627, 0, 485.5},
            {443, 30.30150135466964, 120.20506330367967, 80.01, 59.4},
            {444, 30.30153695550953, 120.20548925126673, 80.01, 59.4},
            {448, 30.30139598679696, 120.20634285592757, 80.91, 41.6},
            {449, 30.301430113702125, 120.20677376270054, 80.91, 41.6},
            {450, 30.30145986720659, 120.20705031041416, 80.91, 39.5},
            {452, 30.301486272778252, 120.2073125518642, 80.17, 38.0},
            {452, 30.301471485307438, 120.20724343072509, 79.41, 34.2},
            {453, 30.30149783767519, 120.20766703738323, 79.41, 34.2},
            {454, 30.30152471391098, 120.20789541313434, 78.21, 33.2},
            {455, 30.30156444416766, 120.2081534099445, 80.15, 32.5},
            {456, 30.301586755999313, 120.2083983635878, 79.1, 32.0},
            {457, 30.301617420394578, 120.2086394633863, 79.76, 31.6},
            {458, 30.30162534290883, 120.20884120674567, 79.1, 31.2},
            {459, 30.30164645040506, 120.20903687362548, 77.38, 30.8},
            {460, 30.301662677287336, 120.20923465345084, 77.38, 30.5},
            {461, 30.30166541908108, 120.20931126950867, 77.01, 29.8},
            {462, 30.301676785124233, 120.20949366090271, 75.73, 29.6},
            {463, 30.30169034570585, 120.20966295939917, 75.73, 29.4},
            {464, 30.30170292833865, 120.20982443992122, 76.73, 29.2},
            {465, 30.301711607330105, 120.20999736405122, 76.73, 29.0},
            {466, 30.301723246023958, 120.2101437601824, 76.73, 28.8},
            {468, 30.30174170822661, 120.21007703438418, 77.19, 27.4},
            {469, 30.301761688896118, 120.2103232895721, 77.02, 27.6},
            {470, 30.30177698054649, 120.21055431157501, 77.02, 27.8},
            {471, 30.301806031862778, 120.21084965117883, 77.02, 28.1},
            {473, 30.301842668128266, 120.21110443456523, 77.9, 28.3},
            {476, 30.301436391267444, 120.20617789205023, 82.76, 73.4},
            {477, 30.301477313505416, 120.20661798586296, 82.76, 73.4},
            {478, 30.30151803409344, 120.20706030586385, 0, 74.5},
            {479, 30.30155885550644, 120.20750151304621, 0, 76.3},
            {480, 30.301599676919437, 120.20794272041228, 0, 79.6},
            {481, 30.301640498332436, 120.20838392796203, 0, 84.8},
            {490, 30.302002309583045, 120.21634299062548, 0, 126.3},
            {493, 30.301422381039753, 120.20661473687767, 80.78, 46.2},
            {495, 30.301451703173107, 120.20705166222919, 80.78, 46.2},
            {496, 30.30140880980846, 120.20642712331929, 80.78, 45.4},
            {498, 30.30143785298658, 120.20686554891728, 80.78, 45.4},
            {500, 30.301397470911795, 120.20625589788693, 80.65, 41.6},
            {502, 30.301426314640874, 120.2066931075327, 80.65, 41.6},
            {505, 30.301390641575033, 120.20605907227892, 80.71, 37.2},
            {506, 30.30141878992319, 120.20648950220044, 80.71, 37.2},
            // 06:33:31-38 the tail: accuracy dips to 14-20, these passed the accuracy filter
            {507, 30.301397423456354, 120.20592133759227, 80.71, 14.2},
            {511, 30.301426767659873, 120.20628386280745, 80.55, 18.5},
            {512, 30.30144817702607, 120.20668051882936, 80.55, 18.8},
            {513, 30.301459723494414, 120.2070405760794, 79.76, 19.1},
            {514, 30.301477118761426, 120.20742389550448, 79.12, 19.8},
            {515, 30.301509498296063, 120.20780767466566, 79.12, 20.5},
            {516, 30.30153002126513, 120.20814320189334, 79.14, 21.1},
            {517, 30.301553627832558, 120.20833469310249, 79.35, 21.0},
            {518, 30.301577616254555, 120.20869193464337, 79.35, 21.7},
            {519, 30.301590842031235, 120.20898386960985, 79.35, 22.1},
            {520, 30.301591323340098, 120.20908583684762, 76.87, 22.1},
            {521, 30.301612808589134, 120.20932991293017, 77.89, 22.4},
            {522, 30.30163208149799, 120.20961520966014, 77.89, 22.8},
            {523, 30.301664890770972, 120.2099347601818, 78.55, 23.4},
            {524, 30.30168472741174, 120.21011532483672, 78.67, 23.6},
            {525, 30.301709387623173, 120.21039402185303, 78.56, 24.0},
            {526, 30.301716742039993, 120.21061299183869, 78.56, 24.3},
            {527, 30.301738709625024, 120.2108842789991, 78.56, 24.8},
            {528, 30.301752627973574, 120.21114846218236, 78.55, 25.2},
            {529, 30.301774799486232, 120.21136578238799, 78.9, 25.5},
            {530, 30.30178266364677, 120.21143673333205, 78.62, 25.5},
            {531, 30.301790173809216, 120.21150476911076, 78.62, 25.5},
            {533, 30.301814115004408, 120.2117749409443, 78.4, 25.8},
            {534, 30.301826001011325, 120.21197474745179, 78.59, 26.1},
            {536, 30.301835784536063, 120.21217200906523, 79.11, 26.4},
            {539, 30.301420754765193, 120.20615170175242, 81.08, 83.9},
            {540, 30.301452033208925, 120.20658379550203, 81.08, 83.9},
            {542, 30.30148827604956, 120.20684229316512, 81.83, 78.7},
            {554, 30.302112430553585, 120.21731120777073, 0, 143.4},
            {578, 30.30172373440204, 120.21147452600614, 0, 103.3},
            {638, 30.301720937968735, 120.21143894390379, 0, 103.0},
            // 06:36:24 drift ends, device back at home with good accuracy
            {680, 30.301262625591665, 120.20533426914162, 0, 8.6},
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
    public void testDriftIncidentDoesNotFireGeofenceExit() {
        long baseTime = 1_000_000_000_000L;

        // seed: device has been inside the geofence for a long time before the replay
        Position seed = new Position();
        seed.setDeviceId(DEVICE_ID);
        seed.setFixTime(new Date(baseTime - 60_000));
        seed.setLatitude(30.3012860);
        seed.setLongitude(120.2053460);
        seed.setValid(true);
        seed.setGeofenceIds(List.of(GEOFENCE_ID));
        lastPositionRef.set(seed);

        List<Event> events = new ArrayList<>();
        List<String> exitLog = new ArrayList<>();

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
                if (event.getType().equals(Event.TYPE_GEOFENCE_EXIT)) {
                    exitLog.add("geofenceExit at offset " + (long) row[0] + "s");
                }
            });
            lastPositionRef.set(p);
        }

        assertTrue(exitLog.isEmpty(),
                "drift replay must not fire geofenceExit, but got: " + exitLog
                        + " (all events: " + events.stream().map(Event::getType).toList() + ")");
    }
}

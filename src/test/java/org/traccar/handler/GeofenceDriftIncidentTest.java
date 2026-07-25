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
 * geofence polygon are taken from the production database with a uniform coordinate offset
 * applied for privacy; the offset preserves all distances and geometry. The production filter
 * configuration is applied. The test asserts that no geofenceExit event fires.
 */
public class GeofenceDriftIncidentTest {

    private static final long DEVICE_ID = 5L;
    private static final long GEOFENCE_ID = 1L;

    private static final String HOME_AREA = "POLYGON ((30.31530967824608 86.48990753223486, "
            + "30.316133018113717 86.49092670203165, 30.315107428001042 86.49248992433879, "
            + "30.314660448967626 86.4919050577438, 30.314501270828643 86.49167814409246, "
            + "30.314447572078627 86.49159494942407, 30.314223446548993 86.49129636543155, "
            + "30.31530967824608 86.48990753223486))";

    /** {offsetSeconds, latitude, longitude, speedKnots, accuracyMeters} from tc_positions. */
    private static final double[][] TRACK = {
            // stationary at home (06:25:04 - 06:30:15), anchor builds here
            {0, 30.31498593890959, 86.49164632772133, 0, 2.0},
            {60, 30.314986094018515, 86.49164717994425, 0, 2.0},
            {125, 30.31498604597184, 86.49164581620367, 0, 2.0},
            {186, 30.31498646287557, 86.49164593882616, 0, 2.0},
            {246, 30.31498677179807, 86.49164495263558, 0, 2.0},
            {311, 30.314986514339512, 86.49164354646796, 0, 2.0},
            // 06:31:16 speed jumps to 90 knots while still at home
            {372, 30.314986833303344, 86.49164360264259, 90.93, 2.0},
            // 06:31:38+ fake high-speed drift running east, low accuracy
            {394, 30.315231925947398, 86.49313929713964, 92.75, 32.4},
            {395, 30.315281094515434, 86.49363208638795, 92.75, 32.4},
            {396, 30.31532211766979, 86.4939589792624, 93.51, 31.2},
            {397, 30.31536585991062, 86.49424403340584, 93.51, 30.2},
            {398, 30.31540055596029, 86.4945005680718, 92.11, 29.4},
            {399, 30.315426144665878, 86.49474087270623, 90.9, 28.8},
            {400, 30.315451384530792, 86.49500893139815, 88.42, 28.5},
            {401, 30.315483157391185, 86.49527635585855, 88.61, 28.3},
            {402, 30.315509824504197, 86.49551946657876, 88.42, 28.2},
            {403, 30.31552853355429, 86.49573410040498, 87.36, 28.0},
            {404, 30.31555563088536, 86.49593342424524, 85.4, 27.8},
            {405, 30.315579127994443, 86.49608742010135, 85.65, 27.6},
            {406, 30.315585753148817, 86.49619794499964, 85.4, 27.2},
            {407, 30.31560198940631, 86.49641860287523, 84.08, 27.3},
            {408, 30.315625850007084, 86.49671871307428, 82.28, 27.7},
            {409, 30.31564840006035, 86.49690650810763, 82.43, 27.8},
            {410, 30.315666832553035, 86.49705698555897, 82.28, 27.7},
            {411, 30.315686549077267, 86.49721180231143, 82.43, 27.7},
            {412, 30.315694916802744, 86.49736501396211, 81.86, 27.7},
            {413, 30.315710410107883, 86.49751137711422, 82.13, 27.7},
            {414, 30.315722331115477, 86.49764495144607, 82.13, 27.7},
            {415, 30.31573673492655, 86.49776993325974, 82.13, 27.6},
            {416, 30.315756518683155, 86.49790835778207, 82.05, 27.7},
            {417, 30.315773415169, 86.4980160394086, 80.72, 27.7},
            {418, 30.31579376715206, 86.49812430714211, 80.72, 27.7},
            {419, 30.31579917730637, 86.49820391602124, 80.12, 27.7},
            {421, 30.315800206243704, 86.4982626328333, 75.38, 27.6},
            {422, 30.315804032498693, 86.49833241432671, 75.38, 27.6},
            {424, 30.31579408116679, 86.49840703026712, 73.28, 27.6},
            {426, 30.315826231194645, 86.49882826482136, 0, 31.6},
            {427, 30.315857432364478, 86.49921739875038, 0, 36.3},
            {428, 30.315888633536467, 86.49960653284309, 0, 43.5},
            {429, 30.315919834712755, 86.49999566709951, 0, 53.0},
            // 06:32:27 chaotic bounce between home and drift track
            {443, 30.314987309621422, 86.49161922562408, 0, 14.2},
            {443, 30.315136088031462, 86.49397279541627, 0, 485.5},
            {443, 30.31520135466964, 86.49136330367967, 80.01, 59.4},
            {444, 30.31523695550953, 86.49178925126673, 80.01, 59.4},
            {448, 30.31509598679696, 86.49264285592757, 80.91, 41.6},
            {449, 30.315130113702125, 86.49307376270054, 80.91, 41.6},
            {450, 30.31515986720659, 86.49335031041416, 80.91, 39.5},
            {452, 30.315186272778252, 86.4936125518642, 80.17, 38.0},
            {452, 30.315171485307438, 86.49354343072509, 79.41, 34.2},
            {453, 30.31519783767519, 86.49396703738323, 79.41, 34.2},
            {454, 30.31522471391098, 86.49419541313434, 78.21, 33.2},
            {455, 30.31526444416766, 86.4944534099445, 80.15, 32.5},
            {456, 30.315286755999313, 86.4946983635878, 79.1, 32.0},
            {457, 30.315317420394578, 86.4949394633863, 79.76, 31.6},
            {458, 30.31532534290883, 86.49514120674567, 79.1, 31.2},
            {459, 30.31534645040506, 86.49533687362548, 77.38, 30.8},
            {460, 30.315362677287336, 86.49553465345084, 77.38, 30.5},
            {461, 30.31536541908108, 86.49561126950867, 77.01, 29.8},
            {462, 30.315376785124233, 86.49579366090271, 75.73, 29.6},
            {463, 30.31539034570585, 86.49596295939917, 75.73, 29.4},
            {464, 30.31540292833865, 86.49612443992122, 76.73, 29.2},
            {465, 30.315411607330105, 86.49629736405122, 76.73, 29.0},
            {466, 30.315423246023958, 86.4964437601824, 76.73, 28.8},
            {468, 30.31544170822661, 86.49637703438418, 77.19, 27.4},
            {469, 30.315461688896118, 86.4966232895721, 77.02, 27.6},
            {470, 30.31547698054649, 86.49685431157501, 77.02, 27.8},
            {471, 30.315506031862778, 86.49714965117883, 77.02, 28.1},
            {473, 30.315542668128266, 86.49740443456523, 77.9, 28.3},
            {476, 30.315136391267444, 86.49247789205023, 82.76, 73.4},
            {477, 30.315177313505416, 86.49291798586296, 82.76, 73.4},
            {478, 30.31521803409344, 86.49336030586385, 0, 74.5},
            {479, 30.31525885550644, 86.49380151304621, 0, 76.3},
            {480, 30.315299676919437, 86.49424272041228, 0, 79.6},
            {481, 30.315340498332436, 86.49468392796203, 0, 84.8},
            {490, 30.315702309583045, 86.50264299062548, 0, 126.3},
            {493, 30.315122381039753, 86.49291473687767, 80.78, 46.2},
            {495, 30.315151703173107, 86.49335166222919, 80.78, 46.2},
            {496, 30.31510880980846, 86.49272712331929, 80.78, 45.4},
            {498, 30.31513785298658, 86.49316554891728, 80.78, 45.4},
            {500, 30.315097470911795, 86.49255589788693, 80.65, 41.6},
            {502, 30.315126314640874, 86.4929931075327, 80.65, 41.6},
            {505, 30.315090641575033, 86.49235907227892, 80.71, 37.2},
            {506, 30.31511878992319, 86.49278950220044, 80.71, 37.2},
            // 06:33:31-38 the tail: accuracy dips to 14-20, these passed the accuracy filter
            {507, 30.315097423456354, 86.49222133759227, 80.71, 14.2},
            {511, 30.315126767659873, 86.49258386280745, 80.55, 18.5},
            {512, 30.31514817702607, 86.49298051882936, 80.55, 18.8},
            {513, 30.315159723494414, 86.4933405760794, 79.76, 19.1},
            {514, 30.315177118761426, 86.49372389550448, 79.12, 19.8},
            {515, 30.315209498296063, 86.49410767466566, 79.12, 20.5},
            {516, 30.31523002126513, 86.49444320189334, 79.14, 21.1},
            {517, 30.315253627832558, 86.49463469310249, 79.35, 21.0},
            {518, 30.315277616254555, 86.49499193464337, 79.35, 21.7},
            {519, 30.315290842031235, 86.49528386960985, 79.35, 22.1},
            {520, 30.315291323340098, 86.49538583684762, 76.87, 22.1},
            {521, 30.315312808589134, 86.49562991293017, 77.89, 22.4},
            {522, 30.31533208149799, 86.49591520966014, 77.89, 22.8},
            {523, 30.315364890770972, 86.4962347601818, 78.55, 23.4},
            {524, 30.31538472741174, 86.49641532483672, 78.67, 23.6},
            {525, 30.315409387623173, 86.49669402185303, 78.56, 24.0},
            {526, 30.315416742039993, 86.49691299183869, 78.56, 24.3},
            {527, 30.315438709625024, 86.4971842789991, 78.56, 24.8},
            {528, 30.315452627973574, 86.49744846218236, 78.55, 25.2},
            {529, 30.315474799486232, 86.49766578238799, 78.9, 25.5},
            {530, 30.31548266364677, 86.49773673333205, 78.62, 25.5},
            {531, 30.315490173809216, 86.49780476911076, 78.62, 25.5},
            {533, 30.315514115004408, 86.4980749409443, 78.4, 25.8},
            {534, 30.315526001011325, 86.49827474745179, 78.59, 26.1},
            {536, 30.315535784536063, 86.49847200906523, 79.11, 26.4},
            {539, 30.315120754765193, 86.49245170175242, 81.08, 83.9},
            {540, 30.315152033208925, 86.49288379550203, 81.08, 83.9},
            {542, 30.31518827604956, 86.49314229316512, 81.83, 78.7},
            {554, 30.315812430553585, 86.50361120777073, 0, 143.4},
            {578, 30.31542373440204, 86.49777452600614, 0, 103.3},
            {638, 30.315420937968735, 86.49773894390379, 0, 103.0},
            // 06:36:24 drift ends, device back at home with good accuracy
            {680, 30.314962625591665, 86.49163426914162, 0, 8.6},
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
        seed.setLatitude(30.3149860);
        seed.setLongitude(86.4916460);
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

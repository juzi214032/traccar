/*
 * Copyright 2023 - 2025 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.handler;

import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.config.Keys;
import org.traccar.helper.DistanceCalculator;
import org.traccar.helper.model.AttributeUtil;
import org.traccar.helper.model.GeofenceUtil;
import org.traccar.model.Position;
import org.traccar.session.HomeAssistantProvider;
import org.traccar.session.cache.CacheManager;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class GeofenceHandler extends BasePositionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GeofenceHandler.class);

    /**
     * Position attribute marking that geofence calculation was skipped and the
     * geofence ids were inherited. Downstream debounce must not count such positions.
     */
    public static final String ATTRIBUTE_GEOFENCE_SKIPPED = "geofenceSkipped";

    private static final double KNOTS_TO_MPS = 0.514444;
    /** Reported speed above this (m/s) is subject to the fake-speed check. */
    private static final double FAKE_SPEED_MIN_MPS = 5.0;
    /** Implied speed must reach at least this fraction of the reported speed. */
    private static final double FAKE_SPEED_MIN_RATIO = 0.4;
    /** Implied speed above this (m/s) is subject to the teleport check. */
    private static final double TELEPORT_MIN_MPS = 10.0;
    /** Implied speed must not exceed the reported speed by more than this factor. */
    private static final double TELEPORT_MAX_RATIO = 2.5;
    /** Skip the consistency check when positions are too far apart in time (seconds). */
    private static final double CONSISTENCY_MAX_INTERVAL = 60.0;
    /** Maximum plausible reported-speed increase rate (m/s^2) for the acceleration check. */
    private static final double MAX_ACCELERATION_MPS2 = 10.0;

    private final CacheManager cacheManager;
    private final HomeAssistantProvider homeAssistant;

    /**
     * Per-device anchor state for detecting stationary-to-drifting transitions.
     * Keyed by deviceId.
     */
    private final ConcurrentHashMap<Long, AnchorState> anchorStates = new ConcurrentHashMap<>();

    /**
     * Last credible reported speed per device, used by the acceleration plausibility
     * check. Fabricated drift tracks are often self-consistent (reported speed matches
     * displacement), so the only tell is a kinematically impossible speed jump from the
     * last trusted speed. Keyed by deviceId.
     */
    private final ConcurrentHashMap<Long, TrustedSpeed> trustedSpeeds = new ConcurrentHashMap<>();

    private static class TrustedSpeed {
        private double speedMps;
        private long timeMs;

        TrustedSpeed(double speedMps, long timeMs) {
            this.speedMps = speedMps;
            this.timeMs = timeMs;
        }
    }

    /**
     * Tracks the stationary anchor state for a single device.
     * <p>
     * When a device produces {@code anchorCount} consecutive positions within
     * {@code anchorRadius} meters of each other, an anchor is locked. Once locked,
     * positions farther than {@code anchorMaxDistance} from the anchor are filtered
     * (geofence calculation skipped) unless the device shows sustained movement away
     * from the anchor for {@code anchorReleaseCount} consecutive positions.
     */
    private static class AnchorState {
        /** Running cluster center used during the building phase. */
        private double clusterLat;
        private double clusterLon;
        /** Number of consecutive positions within anchorRadius of the cluster center. */
        private int clusterCount;
        /** Locked anchor coordinates (set when clusterCount reaches anchorCount). */
        private double anchorLat;
        private double anchorLon;
        /** Whether the anchor is currently locked and filtering. */
        private boolean isAnchored;
        /** Consecutive positions that have been progressively farther from the anchor. */
        private int awayStreak;
        /** Distance of the last position from the anchor (for tracking "away" direction). */
        private double lastDistanceFromAnchor;
        /**
         * Geofence ids recorded when the anchor was locked. A position inside the anchor
         * release radius that would flip this state (inside→outside or vice versa) is
         * treated as a boundary drift and filtered.
         */
        private Set<Long> anchorGeofenceIds;

        AnchorState(double lat, double lon) {
            this.clusterLat = lat;
            this.clusterLon = lon;
            this.clusterCount = 1;
        }
    }

    @Inject
    public GeofenceHandler(CacheManager cacheManager, HomeAssistantProvider homeAssistant) {
        this.cacheManager = cacheManager;
        this.homeAssistant = homeAssistant;
    }

    @Override
    public void onPosition(Position position, Callback callback) {

        long deviceId = position.getDeviceId();

        // Home Assistant 覆盖：若设备的在家实体判定"在家"，直接认定为家围栏，跳过所有 GPS 围栏计算，
        // 从根本上消除家附近的 GPS 漂移误报。not_home / unavailable / 未配置则继续走原有逻辑。
        if (homeAssistant.isEnabled()) {
            String entity = AttributeUtil.lookup(cacheManager, Keys.HOMEASSISTANT_ENTITY, deviceId);
            Integer homeGeofenceId = AttributeUtil.lookup(
                    cacheManager, Keys.HOMEASSISTANT_HOME_GEOFENCE_ID, deviceId);
            if (entity != null && homeGeofenceId != null
                    && homeAssistant.getState(entity) == HomeAssistantProvider.HomeState.HOME) {
                position.setGeofenceIds(List.of(homeGeofenceId.longValue()));
                LOGGER.info("device {} home-assistant home override geofence={}", deviceId, homeGeofenceId);
                callback.processed(false);
                return;
            }
        }

        // 获取精度阈值配置，支持全局 CONFIG 和按设备 DEVICE 覆盖
        Integer geofenceEventAccuracy = AttributeUtil.lookup(
                cacheManager, Keys.FILTER_GEOFENCE_EVENT_ACCURACY, deviceId);
        // 获取速度黑名单阈值配置，速度 <= 该值（单位节）时跳过围栏计算
        Double geofenceSpeedBlackLte = AttributeUtil.lookup(
                cacheManager, Keys.FILTER_GEOFENCE_SPEED_BLACK_LTE, deviceId);

        // 精度超标：当前精度值大于阈值时，跳过围栏计算，复用上一次的围栏结果
        boolean skipByAccuracy = geofenceEventAccuracy != null
                && position.getAccuracy() > geofenceEventAccuracy;
        if (skipByAccuracy) {
            LOGGER.info("device {} accuracy filter accuracy={} threshold={}",
                    deviceId, position.getAccuracy(), geofenceEventAccuracy);
        }
        // 速度过低：当前速度小于等于阈值时跳过围栏计算，避免静止/低速设备反复触发围栏进出事件
        boolean skipBySpeed = geofenceSpeedBlackLte != null
                && position.getSpeed() <= geofenceSpeedBlackLte;
        if (skipBySpeed) {
            LOGGER.info("device {} speed filter speed={} threshold={}",
                    deviceId, position.getSpeed(), geofenceSpeedBlackLte);
        }

        // 速度一致性检查：上报速度与位移速度物理不自洽的点视为漂移，不参与围栏计算和锚点状态更新
        boolean skipByConsistency = false;
        Boolean consistencyEnabled = AttributeUtil.lookup(
                cacheManager, Keys.FILTER_GEOFENCE_SPEED_CONSISTENCY, deviceId);
        if (consistencyEnabled != null && consistencyEnabled) {
            Position previous = cacheManager.getPosition(deviceId);
            if (previous != null) {
                double interval = (position.getFixTime().getTime() - previous.getFixTime().getTime()) / 1000.0;
                if (interval > 0 && interval <= CONSISTENCY_MAX_INTERVAL) {
                    double impliedSpeed = DistanceCalculator.distance(
                            position.getLatitude(), position.getLongitude(),
                            previous.getLatitude(), previous.getLongitude()) / interval;
                    double reportedSpeed = position.getSpeed() * KNOTS_TO_MPS;
                    boolean fakeSpeed = reportedSpeed > FAKE_SPEED_MIN_MPS
                            && impliedSpeed < reportedSpeed * FAKE_SPEED_MIN_RATIO;
                    boolean teleport = impliedSpeed > TELEPORT_MIN_MPS
                            && impliedSpeed > reportedSpeed * TELEPORT_MAX_RATIO;
                    if (fakeSpeed || teleport) {
                        skipByConsistency = true;
                        LOGGER.info("device {} speed consistency filter type={} reported={} implied={} interval={}",
                                deviceId, fakeSpeed ? "fakeSpeed" : "teleport",
                                reportedSpeed, impliedSpeed, interval);
                    }
                }
            }
        }

        // 加速度合理性检查：自洽伪造轨迹（上报速度与位移吻合）能骗过一致性检查，
        // 唯一破绽是相对最后可信速度的运动学不可能跳变（如 1 秒内 0→64 m/s）
        boolean skipByAcceleration = false;
        if (consistencyEnabled != null && consistencyEnabled) {
            long fixTimeMs = position.getFixTime().getTime();
            double reportedSpeed = position.getSpeed() * KNOTS_TO_MPS;
            TrustedSpeed trusted = trustedSpeeds.get(deviceId);
            if (trusted != null && !skipByAccuracy && !skipByConsistency) {
                double interval = (fixTimeMs - trusted.timeMs) / 1000.0;
                if (interval > 0 && interval <= CONSISTENCY_MAX_INTERVAL
                        && reportedSpeed > trusted.speedMps + MAX_ACCELERATION_MPS2 * interval) {
                    skipByAcceleration = true;
                    // 被拒绝的点不更新可信速度，但推进时间戳：断言设备此刻仍处于可信速度，
                    // 否则伪造轨迹拖长时间后会因间隔变大而重新变得"合理"
                    trusted.timeMs = fixTimeMs;
                    LOGGER.info("device {} speed acceleration filter reported={} trusted={} interval={}",
                            deviceId, reportedSpeed, trusted.speedMps, interval);
                }
            }
            if (!skipByAcceleration && !skipByAccuracy && !skipByConsistency) {
                trustedSpeeds.put(deviceId, new TrustedSpeed(reportedSpeed, fixTimeMs));
            }
        }

        // 锚点过滤：设备静止时锁定锚点，远离锚点的位置跳过围栏计算
        boolean skipByAnchor = false;
        AnchorState state = null;
        boolean anchorJustLocked = false;
        List<Long> precomputedGeofenceIds = null;
        Integer anchorRadius = AttributeUtil.lookup(
                cacheManager, Keys.FILTER_GEOFENCE_ANCHOR_RADIUS, deviceId);
        Integer anchorCount = AttributeUtil.lookup(
                cacheManager, Keys.FILTER_GEOFENCE_ANCHOR_COUNT, deviceId);
        Integer anchorMaxDist = AttributeUtil.lookup(
                cacheManager, Keys.FILTER_GEOFENCE_ANCHOR_MAX_DISTANCE, deviceId);
        Integer anchorRelease = AttributeUtil.lookup(
                cacheManager, Keys.FILTER_GEOFENCE_ANCHOR_RELEASE_COUNT, deviceId);

        if (anchorRadius != null && anchorCount != null && anchorMaxDist != null && anchorRelease != null) {
            if (skipByAccuracy || skipByConsistency || skipByAcceleration) {
                // 低精度或速度不自洽/不合理的点已被否决，不允许它建立、推进或释放锚点，保持锚点状态冻结
                LOGGER.info("device {} anchor state frozen reason={} accuracy={} threshold={}",
                        deviceId,
                        skipByAccuracy ? "accuracy" : skipByConsistency ? "consistency" : "acceleration",
                        position.getAccuracy(), geofenceEventAccuracy);
            } else {
                double lat = position.getLatitude();
                double lon = position.getLongitude();

                state = anchorStates.computeIfAbsent(deviceId, k -> new AnchorState(lat, lon));

                if (!state.isAnchored) {
                    // Phase 1: building anchor cluster
                    double dist = DistanceCalculator.distance(lat, lon, state.clusterLat, state.clusterLon);
                    if (dist <= anchorRadius) {
                        state.clusterCount++;
                        // running average update
                        state.clusterLat = state.clusterLat + (lat - state.clusterLat) / state.clusterCount;
                        state.clusterLon = state.clusterLon + (lon - state.clusterLon) / state.clusterCount;
                        if (state.clusterCount >= anchorCount) {
                            state.isAnchored = true;
                            anchorJustLocked = true;
                            state.anchorLat = state.clusterLat;
                            state.anchorLon = state.clusterLon;
                            state.awayStreak = 0;
                            state.lastDistanceFromAnchor = 0;
                            LOGGER.info("device {} anchor established lat={} lon={} clusterCount={}",
                                    deviceId, state.anchorLat, state.anchorLon, state.clusterCount);
                        }
                    } else {
                        state.clusterLat = lat;
                        state.clusterLon = lon;
                        state.clusterCount = 1;
                    }
                } else {
                    // Phase 2: anchor active
                    double distFromAnchor = DistanceCalculator.distance(
                            lat, lon, state.anchorLat, state.anchorLon);
                    if (distFromAnchor <= anchorMaxDist) {
                        // 方案 C：放行圈内校验围栏状态一致性。锚点锁定时记录了围栏状态，
                        // 若放行圈内的点会让状态翻转（在内→在外或反之），判定为边界漂移并拦截，
                        // 避免静止设备因 GPS 抖动越过围栏边界而误触发进出事件。
                        if (state.anchorGeofenceIds != null) {
                            precomputedGeofenceIds = GeofenceUtil.getCurrentGeofences(cacheManager, position);
                            if (!new HashSet<>(precomputedGeofenceIds).equals(state.anchorGeofenceIds)) {
                                skipByAnchor = true;
                                LOGGER.info("device {} anchor state-flip lat={} lon={} dist={} anchor={} current={}",
                                        deviceId, lat, lon, distFromAnchor,
                                        state.anchorGeofenceIds, precomputedGeofenceIds);
                            }
                        }
                        if (!skipByAnchor) {
                            if (state.awayStreak > 0) {
                                LOGGER.info("device {} anchor away streak reset distance={}", deviceId, distFromAnchor);
                            }
                            state.awayStreak = 0;
                        }
                    } else {
                        if (distFromAnchor > state.lastDistanceFromAnchor) {
                            state.awayStreak++;
                            LOGGER.info("device {} anchor away streak {} distance={} lastDistance={}",
                                    deviceId, state.awayStreak, distFromAnchor, state.lastDistanceFromAnchor);
                        } else {
                            LOGGER.info("device {} anchor away streak reset distance={}", deviceId, distFromAnchor);
                            state.awayStreak = 0;
                        }
                        if (state.awayStreak >= anchorRelease) {
                            // sustained movement: release anchor
                            LOGGER.info("device {} anchor released awayStreak={}", deviceId, state.awayStreak);
                            state.isAnchored = false;
                            state.anchorGeofenceIds = null;
                            state.clusterLat = lat;
                            state.clusterLon = lon;
                            state.clusterCount = 1;
                        } else {
                            skipByAnchor = true;
                            LOGGER.info("device {} anchor filtered lat={} lon={} distance={} awayStreak={}/{}",
                                    deviceId, lat, lon, distFromAnchor, state.awayStreak, anchorRelease);
                        }
                    }
                    state.lastDistanceFromAnchor = distFromAnchor;
                }
            }
        }

        if (skipByAccuracy || skipBySpeed || skipByConsistency || skipByAcceleration || skipByAnchor) {
            // 不重新计算围栏，继承上一个已知位置的围栏 ID 列表；
            // 打标记让防抖计数忽略该点，避免继承的错误结果推进事件计数
            position.set(ATTRIBUTE_GEOFENCE_SKIPPED, true);
            Position lastPosition = cacheManager.getPosition(position.getDeviceId());
            if (lastPosition != null && lastPosition.getGeofenceIds() != null) {
                position.setGeofenceIds(lastPosition.getGeofenceIds());
            }
            // 锚点刚锁定时，即使本帧被速度黑名单等规则跳过，也用继承到的围栏状态
            // 记录锚点基准，否则方案 C 的状态翻转检查无从比较
            if (anchorJustLocked && state != null) {
                List<Long> inherited = position.getGeofenceIds();
                state.anchorGeofenceIds = inherited != null ? new HashSet<>(inherited) : new HashSet<>();
            }
        } else {
            // 位置数据质量合格，重新计算围栏
            List<Long> geofenceIds = precomputedGeofenceIds != null
                    ? precomputedGeofenceIds
                    : GeofenceUtil.getCurrentGeofences(cacheManager, position);
            if (!geofenceIds.isEmpty()) {
                position.setGeofenceIds(geofenceIds);
            }
            if (anchorJustLocked && state != null) {
                state.anchorGeofenceIds = new HashSet<>(geofenceIds);
            }
        }
        callback.processed(false);
    }

}

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
import org.traccar.session.cache.CacheManager;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class GeofenceHandler extends BasePositionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GeofenceHandler.class);

    private final CacheManager cacheManager;

    /**
     * Per-device anchor state for detecting stationary-to-drifting transitions.
     * Keyed by deviceId.
     */
    private final ConcurrentHashMap<Long, AnchorState> anchorStates = new ConcurrentHashMap<>();

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

        AnchorState(double lat, double lon) {
            this.clusterLat = lat;
            this.clusterLon = lon;
            this.clusterCount = 1;
        }
    }

    @Inject
    public GeofenceHandler(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    @Override
    public void onPosition(Position position, Callback callback) {

        long deviceId = position.getDeviceId();

        // 获取精度阈值配置，支持全局 CONFIG 和按设备 DEVICE 覆盖
        Integer geofenceEventAccuracy = AttributeUtil.lookup(
                cacheManager, Keys.FILTER_GEOFENCE_EVENT_ACCURACY, deviceId);
        // 获取速度黑名单阈值配置，速度 <= 该值（单位节）时跳过围栏计算
        Double geofenceSpeedBlackLte = AttributeUtil.lookup(
                cacheManager, Keys.FILTER_GEOFENCE_SPEED_BLACK_LTE, deviceId);

        // 精度超标：当前精度值大于阈值时，跳过围栏计算，复用上一次的围栏结果
        boolean skipByAccuracy = geofenceEventAccuracy != null
                && position.getAccuracy() > geofenceEventAccuracy;
        // 速度过低：当前速度小于等于阈值时跳过围栏计算，避免静止/低速设备反复触发围栏进出事件
        boolean skipBySpeed = geofenceSpeedBlackLte != null
                && position.getSpeed() <= geofenceSpeedBlackLte;

        // 锚点过滤：设备静止时锁定锚点，远离锚点的位置跳过围栏计算
        boolean skipByAnchor = false;
        Integer anchorRadius = AttributeUtil.lookup(
                cacheManager, Keys.FILTER_GEOFENCE_ANCHOR_RADIUS, deviceId);
        Integer anchorCount = AttributeUtil.lookup(
                cacheManager, Keys.FILTER_GEOFENCE_ANCHOR_COUNT, deviceId);
        Integer anchorMaxDist = AttributeUtil.lookup(
                cacheManager, Keys.FILTER_GEOFENCE_ANCHOR_MAX_DISTANCE, deviceId);
        Integer anchorRelease = AttributeUtil.lookup(
                cacheManager, Keys.FILTER_GEOFENCE_ANCHOR_RELEASE_COUNT, deviceId);

        if (anchorRadius != null && anchorCount != null && anchorMaxDist != null && anchorRelease != null) {
            double lat = position.getLatitude();
            double lon = position.getLongitude();

            AnchorState state = anchorStates.computeIfAbsent(deviceId, k -> new AnchorState(lat, lon));

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
                    if (state.awayStreak > 0) {
                        LOGGER.debug("device {} anchor away streak reset distance={}", deviceId, distFromAnchor);
                    }
                    state.awayStreak = 0;
                } else {
                    if (distFromAnchor > state.lastDistanceFromAnchor) {
                        state.awayStreak++;
                        LOGGER.debug("device {} anchor away streak {} distance={} lastDistance={}",
                                deviceId, state.awayStreak, distFromAnchor, state.lastDistanceFromAnchor);
                    } else {
                        LOGGER.debug("device {} anchor away streak reset distance={}", deviceId, distFromAnchor);
                        state.awayStreak = 0;
                    }
                    if (state.awayStreak >= anchorRelease) {
                        // sustained movement: release anchor
                        LOGGER.info("device {} anchor released awayStreak={}", deviceId, state.awayStreak);
                        state.isAnchored = false;
                        state.clusterLat = lat;
                        state.clusterLon = lon;
                        state.clusterCount = 1;
                    } else {
                        skipByAnchor = true;
                        LOGGER.debug("device {} anchor filtered lat={} lon={} distance={} awayStreak={}/{}",
                                deviceId, lat, lon, distFromAnchor, state.awayStreak, anchorRelease);
                    }
                }
                state.lastDistanceFromAnchor = distFromAnchor;
            }
        }

        if (skipByAccuracy || skipBySpeed || skipByAnchor) {
            // 不重新计算围栏，继承上一个已知位置的围栏 ID 列表
            Position lastPosition = cacheManager.getPosition(position.getDeviceId());
            if (lastPosition != null && lastPosition.getGeofenceIds() != null) {
                position.setGeofenceIds(lastPosition.getGeofenceIds());
            }
        } else {
            // 位置数据质量合格，重新计算围栏
            List<Long> geofenceIds = GeofenceUtil.getCurrentGeofences(cacheManager, position);
            if (!geofenceIds.isEmpty()) {
                position.setGeofenceIds(geofenceIds);
            }
        }
        callback.processed(false);
    }

}

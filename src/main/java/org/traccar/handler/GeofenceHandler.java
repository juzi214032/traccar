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
import org.traccar.config.Keys;
import org.traccar.helper.model.AttributeUtil;
import org.traccar.helper.model.GeofenceUtil;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;

import java.util.List;

public class GeofenceHandler extends BasePositionHandler {

    private final CacheManager cacheManager;

    @Inject
    public GeofenceHandler(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    @Override
    public void onPosition(Position position, Callback callback) {

        // 获取精度阈值配置，支持全局 CONFIG 和按设备 DEVICE 覆盖
        Integer geofenceEventAccuracy = AttributeUtil.lookup(
                cacheManager, Keys.FILTER_GEOFENCE_EVENT_ACCURACY, position.getDeviceId());
        // 获取速度黑名单阈值配置，速度 <= 该值（单位节）时跳过围栏计算
        Double geofenceSpeedBlackLte = AttributeUtil.lookup(
                cacheManager, Keys.FILTER_GEOFENCE_SPEED_BLACK_LTE, position.getDeviceId());

        // 精度超标：当前精度值大于阈值时，跳过围栏计算，复用上一次的围栏结果
        boolean skipByAccuracy = geofenceEventAccuracy != null
                && position.getAccuracy() > geofenceEventAccuracy;
        // 速度过低：当前速度小于等于阈值时跳过围栏计算，避免静止/低速设备反复触发围栏进出事件
        boolean skipBySpeed = geofenceSpeedBlackLte != null
                && position.getSpeed() <= geofenceSpeedBlackLte;

        if (skipByAccuracy || skipBySpeed) {
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

/*
 * Copyright 2016 - 2026 Anton Tananaev (anton@traccar.org)
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
package org.traccar.handler.events;

import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.config.Keys;
import org.traccar.handler.GeofenceHandler;
import org.traccar.helper.DistanceCalculator;
import org.traccar.helper.model.AttributeUtil;
import org.traccar.helper.model.PositionUtil;
import org.traccar.model.Calendar;
import org.traccar.model.Event;
import org.traccar.model.Geofence;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class GeofenceEventHandler extends BaseEventHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GeofenceEventHandler.class);

    private final CacheManager cacheManager;

    private static final double KNOTS_TO_MPS = 0.514444;
    private static final double TELEPORT_MIN_MPS = 10.0;
    private static final double TELEPORT_MAX_RATIO = 2.5;

    /**
     * Per-device debounce state for geofence enter/exit events.
     * Keyed by deviceId. Only populated when debounce is configured.
     */
    private final ConcurrentHashMap<Long, DebounceState> debounceStates = new ConcurrentHashMap<>();

    /**
     * Per-device pending exit events awaiting delayed confirmation, keyed by
     * deviceId then geofenceId. An exit is held here instead of firing immediately
     * when a confirmation window is configured; it is discarded if the device
     * teleports back inside the geofence, or confirmed when the window expires.
     */
    private final ConcurrentHashMap<Long, ConcurrentHashMap<Long, PendingExit>> pendingExits
            = new ConcurrentHashMap<>();

    /**
     * Tracks the geofence debounce state for a single device.
     * <p>
     * {@code stableGeofenceIds} is the last confirmed state — events have been
     * fired for this set. {@code pendingGeofenceIds} is the current candidate
     * and {@code pendingCount} is how many consecutive positions have shared
     * the same pending set. Events fire only when pendingCount exceeds the
     * configured threshold.
     */
    private static class DebounceState {
        private Set<Long> stableGeofenceIds;
        private Set<Long> pendingGeofenceIds;
        private int pendingCount;

        DebounceState(Set<Long> initialIds) {
            this.stableGeofenceIds = new HashSet<>(initialIds);
            this.pendingGeofenceIds = new HashSet<>(initialIds);
            this.pendingCount = 1;
        }
    }

    /**
     * A held geofence exit event awaiting delayed confirmation.
     */
    private static class PendingExit {
        private final long geofenceId;
        private final long triggerTimeMs;
        private final Position triggerPosition;

        PendingExit(long geofenceId, Position triggerPosition) {
            this.geofenceId = geofenceId;
            this.triggerPosition = triggerPosition;
            this.triggerTimeMs = triggerPosition.getFixTime().getTime();
        }
    }

    @Inject
    public GeofenceEventHandler(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    @Override
    public void onPosition(Position position, Callback callback) {
        if (!PositionUtil.isLatest(cacheManager, position)) {
            return;
        }

        long deviceId = position.getDeviceId();

        // Look up debounce thresholds — null means debounce is disabled for that direction
        Integer enterThreshold = AttributeUtil.lookup(
                cacheManager, Keys.FILTER_GEOFENCE_EVENT_ENTER_POSITION_COUNT_BLACK_LTE, deviceId);
        Integer exitThreshold = AttributeUtil.lookup(
                cacheManager, Keys.FILTER_GEOFENCE_EVENT_EXIT_POSITION_COUNT_BLACK_LTE, deviceId);
        Integer confirmWindow = AttributeUtil.lookup(
                cacheManager, Keys.FILTER_GEOFENCE_EXIT_CONFIRM_WINDOW, deviceId);

        Set<Long> currentIds = position.getGeofenceIds() != null
                ? new HashSet<>(position.getGeofenceIds())
                : Collections.emptySet();

        if (enterThreshold != null || exitThreshold != null) {
            // ========== Debounce mode ==========

            // 延迟确认：处理挂起的 exit。必须在 skipped 检查前执行——跳回点可能被
            // GeofenceHandler 的速度一致性检查跳过，但其坐标仍可用于 teleport 判定。
            if (confirmWindow != null && confirmWindow > 0) {
                processPendingExits(deviceId, position, callback, confirmWindow);
            }

            // 被 GeofenceHandler 跳过的点其围栏结果是继承来的，不代表真实观测，
            // 不推进也不重置防抖计数，否则漂移期间被拦截的点会复制错误结果凑够计数
            if (position.getBoolean(GeofenceHandler.ATTRIBUTE_GEOFENCE_SKIPPED)) {
                LOGGER.info("device {} geofence debounce ignored skipped position", deviceId);
                return;
            }

            DebounceState state = debounceStates.computeIfAbsent(deviceId,
                    k -> new DebounceState(currentIds));

            // 1. Update consecutive count for current pending set
            if (currentIds.equals(state.pendingGeofenceIds)) {
                state.pendingCount++;
            } else {
                LOGGER.info("device {} geofence debounce reset currentIds={} previousPending={}",
                        deviceId, currentIds, state.pendingGeofenceIds);
                state.pendingGeofenceIds = currentIds;
                state.pendingCount = 1;
                return; // geofence set changed — reset count, skip event detection this round
            }

            // 2. Geofences that appeared in the pending set (potential ENTER)
            Set<Long> enteredIds = new HashSet<>(state.pendingGeofenceIds);
            enteredIds.removeAll(state.stableGeofenceIds);

            // 3. Geofences that disappeared from the pending set (potential EXIT)
            Set<Long> exitedIds = new HashSet<>(state.stableGeofenceIds);
            exitedIds.removeAll(state.pendingGeofenceIds);

            // 4. ENTER debounce: fire only after pendingCount exceeds the threshold
            if (enterThreshold != null && state.pendingCount > enterThreshold && !enteredIds.isEmpty()) {
                for (long geofenceId : enteredIds) {
                    Geofence geofence = cacheManager.getObject(Geofence.class, geofenceId);
                    if (geofence != null) {
                        long calendarId = geofence.getCalendarId();
                        Calendar calendar = calendarId != 0
                                ? cacheManager.getObject(Calendar.class, calendarId) : null;
                        if (calendar == null || calendar.checkMoment(position.getFixTime())) {
                            Event event = new Event(Event.TYPE_GEOFENCE_ENTER, position);
                            event.setGeofenceId(geofenceId);
                            callback.eventDetected(event);
                            LOGGER.info("device {} geofence enter debounce fired geofenceId={} count={} threshold={}",
                                    deviceId, geofenceId, state.pendingCount, enterThreshold);
                        }
                    }
                }
                state.stableGeofenceIds.addAll(enteredIds);
            }

            // 5. EXIT debounce: fire only after pendingCount exceeds the threshold
            if (exitThreshold != null && state.pendingCount > exitThreshold && !exitedIds.isEmpty()) {
                for (long geofenceId : exitedIds) {
                    Geofence geofence = cacheManager.getObject(Geofence.class, geofenceId);
                    if (geofence != null) {
                        long calendarId = geofence.getCalendarId();
                        Calendar calendar = calendarId != 0
                                ? cacheManager.getObject(Calendar.class, calendarId) : null;
                        if (calendar == null || calendar.checkMoment(position.getFixTime())) {
                            if (confirmWindow != null && confirmWindow > 0) {
                                // 延迟确认：挂起 exit，等待窗口内是否 teleport 跳回
                                ConcurrentHashMap<Long, PendingExit> devicePending =
                                        pendingExits.computeIfAbsent(deviceId,
                                                k -> new ConcurrentHashMap<>());
                                PendingExit existing = devicePending.get(geofenceId);
                                if (existing != null) {
                                    // 旧 pending 未被丢弃说明是真实 exit，先确认再覆盖
                                    Event event = new Event(
                                            Event.TYPE_GEOFENCE_EXIT, existing.triggerPosition);
                                    event.setGeofenceId(geofenceId);
                                    callback.eventDetected(event);
                                    LOGGER.info("device {} exit confirmed (overwritten) geofenceId={}",
                                            deviceId, geofenceId);
                                }
                                devicePending.put(geofenceId, new PendingExit(geofenceId, position));
                                LOGGER.info("device {} exit pending geofenceId={} window={}s",
                                        deviceId, geofenceId, confirmWindow);
                            } else {
                                Event event = new Event(Event.TYPE_GEOFENCE_EXIT, position);
                                event.setGeofenceId(geofenceId);
                                callback.eventDetected(event);
                                LOGGER.info("device {} geofence exit debounce fired geofenceId={} "
                                        + "count={} threshold={}",
                                        deviceId, geofenceId, state.pendingCount, exitThreshold);
                            }
                        }
                    }
                }
                state.stableGeofenceIds.removeAll(exitedIds);
            }
        } else {
            // ========== Original immediate trigger logic (unchanged) ==========

            Set<Long> oldGeofences = new HashSet<>();
            Position lastPosition = cacheManager.getPosition(deviceId);
            if (lastPosition != null && lastPosition.getGeofenceIds() != null) {
                oldGeofences.addAll(lastPosition.getGeofenceIds());
            }

            Set<Long> newGeofences = new HashSet<>();
            if (position.getGeofenceIds() != null) {
                newGeofences.addAll(position.getGeofenceIds());
                newGeofences.removeAll(oldGeofences);
                position.getGeofenceIds().forEach(oldGeofences::remove);
            }

            for (long geofenceId : oldGeofences) {
                Geofence geofence = cacheManager.getObject(Geofence.class, geofenceId);
                if (geofence != null) {
                    long calendarId = geofence.getCalendarId();
                    Calendar calendar = calendarId != 0
                            ? cacheManager.getObject(Calendar.class, calendarId) : null;
                    if (calendar == null || calendar.checkMoment(position.getFixTime())) {
                        Event event = new Event(Event.TYPE_GEOFENCE_EXIT, position);
                        event.setGeofenceId(geofenceId);
                        callback.eventDetected(event);
                    }
                }
            }
            for (long geofenceId : newGeofences) {
                Geofence geofence = cacheManager.getObject(Geofence.class, geofenceId);
                if (geofence != null) {
                    long calendarId = geofence.getCalendarId();
                    Calendar calendar = calendarId != 0
                            ? cacheManager.getObject(Calendar.class, calendarId) : null;
                    if (calendar == null || calendar.checkMoment(position.getFixTime())) {
                        Event event = new Event(Event.TYPE_GEOFENCE_ENTER, position);
                        event.setGeofenceId(geofenceId);
                        callback.eventDetected(event);
                    }
                }
            }
        }
    }

    /**
     * Returns true if the current position represents a teleport return to the geofence
     * the pending exit left — implied speed exceeds teleport thresholds. This signals
     * the exit was GPS drift (high-speed dart-out followed by an instant snap-back).
     */
    private boolean isTeleportReturn(PendingExit pending, Position current) {
        double intervalSec = (current.getFixTime().getTime() - pending.triggerTimeMs) / 1000.0;
        if (intervalSec <= 0) {
            return false;
        }
        double distance = DistanceCalculator.distance(
                pending.triggerPosition.getLatitude(), pending.triggerPosition.getLongitude(),
                current.getLatitude(), current.getLongitude());
        double impliedSpeed = distance / intervalSec;
        double reportedSpeed = current.getSpeed() * KNOTS_TO_MPS;
        return impliedSpeed > TELEPORT_MIN_MPS
                && impliedSpeed > reportedSpeed * TELEPORT_MAX_RATIO;
    }

    /**
     * Resolves pending exits for a device on each incoming position. A pending exit
     * is confirmed (fired) when the confirmation window expires, or discarded when the
     * device teleports back inside the geofence. Runs for skipped positions too, since
     * the snap-back point is often skipped by the consistency filter but its coordinates
     * are still valid for the teleport check.
     */
    private void processPendingExits(
            long deviceId, Position position, Callback callback, int confirmWindow) {
        ConcurrentHashMap<Long, PendingExit> devicePending = pendingExits.get(deviceId);
        if (devicePending == null || devicePending.isEmpty()) {
            return;
        }
        long nowMs = position.getFixTime().getTime();
        for (PendingExit pending : List.copyOf(devicePending.values())) {
            long elapsedSec = (nowMs - pending.triggerTimeMs) / 1000;
            if (elapsedSec > confirmWindow) {
                Geofence geofence = cacheManager.getObject(Geofence.class, pending.geofenceId);
                if (geofence != null) {
                    long calendarId = geofence.getCalendarId();
                    Calendar calendar = calendarId != 0
                            ? cacheManager.getObject(Calendar.class, calendarId) : null;
                    if (calendar == null
                            || calendar.checkMoment(pending.triggerPosition.getFixTime())) {
                        Event event = new Event(
                                Event.TYPE_GEOFENCE_EXIT, pending.triggerPosition);
                        event.setGeofenceId(pending.geofenceId);
                        callback.eventDetected(event);
                        LOGGER.info("device {} exit confirmed (window expired) geofenceId={} "
                                + "elapsed={}s window={}s",
                                deviceId, pending.geofenceId, elapsedSec, confirmWindow);
                    }
                }
                devicePending.remove(pending.geofenceId);
            } else if (isTeleportReturn(pending, position)) {
                Geofence geofence = cacheManager.getObject(Geofence.class, pending.geofenceId);
                if (geofence != null && geofence.containsPosition(position)) {
                    DebounceState state = debounceStates.get(deviceId);
                    if (state != null) {
                        state.stableGeofenceIds.add(pending.geofenceId);
                    }
                    devicePending.remove(pending.geofenceId);
                    LOGGER.info("device {} exit discarded (teleport return) geofenceId={} "
                            + "elapsed={}s", deviceId, pending.geofenceId, elapsedSec);
                }
            }
        }
        if (devicePending.isEmpty()) {
            pendingExits.remove(deviceId);
        }
    }
}

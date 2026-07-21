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
import org.traccar.config.Keys;
import org.traccar.helper.model.AttributeUtil;
import org.traccar.helper.model.PositionUtil;
import org.traccar.model.Calendar;
import org.traccar.model.Event;
import org.traccar.model.Geofence;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class GeofenceEventHandler extends BaseEventHandler {

    private final CacheManager cacheManager;

    /**
     * Per-device debounce state for geofence enter/exit events.
     * Keyed by deviceId. Only populated when debounce is configured.
     */
    private final ConcurrentHashMap<Long, DebounceState> debounceStates = new ConcurrentHashMap<>();

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

        Set<Long> currentIds = position.getGeofenceIds() != null
                ? new HashSet<>(position.getGeofenceIds())
                : Collections.emptySet();

        if (enterThreshold != null || exitThreshold != null) {
            // ========== Debounce mode ==========

            DebounceState state = debounceStates.computeIfAbsent(deviceId,
                    k -> new DebounceState(currentIds));

            // 1. Update consecutive count for current pending set
            if (currentIds.equals(state.pendingGeofenceIds)) {
                state.pendingCount++;
            } else {
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
                            Event event = new Event(Event.TYPE_GEOFENCE_EXIT, position);
                            event.setGeofenceId(geofenceId);
                            callback.eventDetected(event);
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
}

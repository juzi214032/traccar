/*
 * Copyright 2026 Anton Tananaev (anton@traccar.org)
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
package org.traccar.session;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.LifecycleObject;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.model.Device;
import org.traccar.storage.Storage;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Request;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Polls Home Assistant presence entities in the background and caches their state, so that
 * geofence evaluation can be authoritatively overridden when a device is reported at home.
 * Querying runs on a dedicated scheduler thread to avoid blocking the position pipeline.
 */
@Singleton
public class HomeAssistantProvider implements LifecycleObject {

    private static final Logger LOGGER = LoggerFactory.getLogger(HomeAssistantProvider.class);

    public enum HomeState {
        HOME,
        NOT_HOME,
        UNKNOWN
    }

    private final Client client;
    private final Storage storage;
    private final String url;
    private final String token;
    private final int pollInterval;
    private final boolean enabled;

    private final ConcurrentHashMap<String, HomeState> stateCache = new ConcurrentHashMap<>();
    private final Set<String> entities = ConcurrentHashMap.newKeySet();
    private ScheduledExecutorService scheduler;

    @Inject
    public HomeAssistantProvider(Config config, Client client, Storage storage) {
        this.client = client;
        this.storage = storage;
        String configuredUrl = config.getString(Keys.HOMEASSISTANT_URL);
        this.url = configuredUrl != null ? configuredUrl.replaceAll("/+$", "") : null;
        this.token = config.getString(Keys.HOMEASSISTANT_TOKEN);
        this.pollInterval = config.getInteger(Keys.HOMEASSISTANT_POLL_INTERVAL);
        this.enabled = url != null && token != null;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Returns the cached state for the entity and registers it for background polling.
     * Non-blocking: a newly seen entity returns {@code UNKNOWN} until the next poll cycle.
     */
    public HomeState getState(String entity) {
        if (!enabled || entity == null || entity.isBlank()) {
            return HomeState.UNKNOWN;
        }
        if (entities.add(entity)) {
            stateCache.putIfAbsent(entity, HomeState.UNKNOWN);
        }
        return stateCache.getOrDefault(entity, HomeState.UNKNOWN);
    }

    static HomeState mapState(String state) {
        if (state == null || "unavailable".equals(state)) {
            return HomeState.UNKNOWN;
        } else if ("not_home".equals(state)) {
            return HomeState.NOT_HOME;
        } else {
            return HomeState.HOME;
        }
    }

    private HomeState fetch(String entity) {
        try (Response response = client.target(url + "/api/states/" + entity).request()
                .header("Authorization", "Bearer " + token).get()) {
            if (response.getStatus() / 100 != 2) {
                LOGGER.error("Home Assistant entity {} query failed status={}", entity, response.getStatus());
                return HomeState.UNKNOWN;
            }
            JsonObject json = response.readEntity(JsonObject.class);
            String state = json.containsKey("state") && !json.isNull("state")
                    ? json.getString("state") : null;
            HomeState result = mapState(state);
            if (result == HomeState.UNKNOWN) {
                LOGGER.error("Home Assistant entity {} unavailable (state={})", entity, state);
            }
            return result;
        } catch (Exception e) {
            LOGGER.error("Home Assistant entity {} query error: {}", entity, e.getMessage());
            return HomeState.UNKNOWN;
        }
    }

    @Override
    public void start() {
        if (!enabled) {
            LOGGER.info("Home Assistant integration disabled (homeassistant.url/token not set)");
            return;
        }
        try {
            for (Device device : storage.getObjects(Device.class, new Request(new Columns.All()))) {
                Object entity = device.getAttributes().get(Keys.HOMEASSISTANT_ENTITY.getKey());
                if (entity instanceof String entityId && !entityId.isBlank()) {
                    entities.add(entityId);
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Home Assistant startup device scan failed: {}", e.getMessage());
        }
        for (String entity : entities) {
            HomeState state = fetch(entity);
            stateCache.put(entity, state);
            LOGGER.info("Home Assistant startup: entity={} state={}", entity, state);
        }
        if (entities.isEmpty()) {
            LOGGER.info("Home Assistant enabled url={}, no device has homeassistant.entity configured yet", url);
        }
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleWithFixedDelay(this::pollAll, pollInterval, pollInterval, TimeUnit.SECONDS);
    }

    private void pollAll() {
        for (String entity : entities) {
            stateCache.put(entity, fetch(entity));
        }
    }

    @Override
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

}

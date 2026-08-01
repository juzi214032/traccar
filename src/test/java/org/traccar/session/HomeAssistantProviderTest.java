package org.traccar.session;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class HomeAssistantProviderTest {

    @Test
    public void testMapState() {
        assertEquals(HomeAssistantProvider.HomeState.NOT_HOME,
                HomeAssistantProvider.mapState("not_home"));
        assertEquals(HomeAssistantProvider.HomeState.UNKNOWN,
                HomeAssistantProvider.mapState("unavailable"));
        assertEquals(HomeAssistantProvider.HomeState.UNKNOWN,
                HomeAssistantProvider.mapState(null));
        assertEquals(HomeAssistantProvider.HomeState.HOME,
                HomeAssistantProvider.mapState("home"));
        assertEquals(HomeAssistantProvider.HomeState.HOME,
                HomeAssistantProvider.mapState("office"));
        assertEquals(HomeAssistantProvider.HomeState.HOME,
                HomeAssistantProvider.mapState("Away"));
    }
}

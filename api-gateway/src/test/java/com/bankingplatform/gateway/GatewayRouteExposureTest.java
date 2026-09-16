package com.bankingplatform.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the gateway is willing to route.
 *
 * <p>The internal-only boundary rests on the gateway having no route to
 * {@code /internal/**}. Two things could break that silently: someone adding an
 * explicit route, or someone re-enabling the discovery locator, which
 * auto-creates a {@code /{service-id}/**} route for every registered service
 * and would expose {@code /account-service/internal/accounts/{id}/balance}.
 *
 * <p>Asserted against the configuration rather than a running gateway, so the
 * check costs nothing and runs on every build.
 */
@DisplayName("Gateway route exposure")
class GatewayRouteExposureTest {

    @SuppressWarnings("unchecked")
    private Map<String, Object> gatewayConfig() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("application.yml")) {
            assertThat(in).as("gateway application.yml on the test classpath").isNotNull();
            Map<String, Object> root = new Yaml().load(in);
            Map<String, Object> spring = (Map<String, Object>) root.get("spring");
            Map<String, Object> cloud = (Map<String, Object>) spring.get("cloud");
            return (Map<String, Object>) cloud.get("gateway");
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> routedPaths() throws Exception {
        List<Map<String, Object>> routes = (List<Map<String, Object>>) gatewayConfig().get("routes");
        List<String> paths = new ArrayList<>();
        for (Map<String, Object> route : routes) {
            for (Object predicate : (List<Object>) route.get("predicates")) {
                paths.add(String.valueOf(predicate));
            }
        }
        return paths;
    }

    @Test
    @DisplayName("no route exposes an internal path")
    void noInternalRoute() throws Exception {
        assertThat(routedPaths())
                .as("gateway route predicates")
                .isNotEmpty()
                .noneMatch(path -> path.contains("/internal"));
    }

    @Test
    @DisplayName("every route is an /api path, so nothing else is reachable")
    void everyRouteIsApi() throws Exception {
        assertThat(routedPaths()).allMatch(path -> path.contains("Path=/api/"));
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("the discovery locator stays disabled")
    void discoveryLocatorDisabled() throws Exception {
        Map<String, Object> discovery = (Map<String, Object>) gatewayConfig().get("discovery");
        Map<String, Object> locator = (Map<String, Object>) discovery.get("locator");

        // Enabling this would route /{service-id}/** to every service and
        // reopen the internal surface, regardless of the explicit routes above.
        assertThat(locator.get("enabled")).isEqualTo(false);
    }
}

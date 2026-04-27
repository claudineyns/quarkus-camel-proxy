package br.dev.claudiney.proxy.health;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.CamelContext;
import org.apache.camel.ServiceStatus;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Liveness;

@Liveness
@ApplicationScoped
public class CamelRoutesHealthCheck implements HealthCheck {

    @Inject
    CamelContext camelContext;

    @Override
    public HealthCheckResponse call() {
        final long total = camelContext.getRoutes().size();
        final long started = camelContext.getRoutes().stream()
                .filter(r -> ServiceStatus.Started.equals(
                        camelContext.getRouteController().getRouteStatus(r.getId())))
                .count();

        final HealthCheckResponseBuilder builder = HealthCheckResponse.named("camel-routes")
                .withData("total", total)
                .withData("started", started);

        return (total > 0 && started == total)
                ? builder.up().build()
                : builder.down().build();
    }
}

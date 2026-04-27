package br.dev.claudiney.proxy.health;

import io.quarkus.redis.datasource.RedisDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;
import org.eclipse.microprofile.health.Readiness;

@Liveness
@Readiness
@ApplicationScoped
public class RedisHealthCheck implements HealthCheck {

    @Inject
    RedisDataSource redisDataSource;

    @Override
    public HealthCheckResponse call() {
        try {
            redisDataSource.value(String.class).get("__health__");
            return HealthCheckResponse.named("redis").up().build();
        } catch (Exception e) {
            return HealthCheckResponse.named("redis")
                    .down()
                    .withData("error", e.getMessage())
                    .build();
        }
    }
}

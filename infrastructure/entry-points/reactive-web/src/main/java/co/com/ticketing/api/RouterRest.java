package co.com.ticketing.api;

import co.com.ticketing.api.error.ErrorResponseMapper;
import co.com.ticketing.api.filter.AdminApiKeyFilter;
import co.com.ticketing.api.handler.EventHandler;
import co.com.ticketing.api.handler.OrderHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

import static org.springframework.web.reactive.function.server.RouterFunctions.route;

@Configuration
public class RouterRest {

    @Bean
    public RouterFunction<ServerResponse> routerFunction(EventHandler events, OrderHandler orders,
                                                         AdminApiKeyFilter adminApiKey, ErrorResponseMapper errors) {
        RouterFunction<ServerResponse> adminRoutes = route()
                .POST("/events", events::create)
                .POST("/events/{eventId}/complimentary", events::assignComplimentary)
                .filter(adminApiKey)
                .build();

        return route()
                .path("/api/v1", api -> api
                        .GET("/events", events::list)
                        .GET("/events/{eventId}", events::get)
                        .GET("/events/{eventId}/availability", events::availability)
                        .GET("/events/{eventId}/availability/stream", events::availabilityStream)
                        .POST("/orders", orders::place)
                        .GET("/orders/{orderId}", orders::get)
                        .POST("/orders/{orderId}/confirm", orders::confirm)
                        .add(adminRoutes))
                .onError(Throwable.class, errors::toResponse)
                .build();
    }
}

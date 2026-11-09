package com.zentro.security.config;

import com.zentro.security.observability.CorrelationIdFilter;
import com.zentro.security.observability.InternalCorrelationInterceptor;
import jakarta.servlet.DispatcherType;
import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class CorrelationAutoConfiguration {
  @Bean
  FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter() {
    var registration = new FilterRegistrationBean<>(new CorrelationIdFilter());
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
    registration.setAsyncSupported(true);
    registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
    return registration;
  }

  @Bean
  RestClientCustomizer internalCorrelationCustomizer(Environment environment) {
    List<String> destinations = Binder.get(environment)
        .bind("zentro.correlation.internal-base-urls", Bindable.listOf(String.class)).orElse(List.of());
    return builder -> builder.requestInterceptor(new InternalCorrelationInterceptor(destinations));
  }
}

package com.zentro.security.config;

import com.zentro.security.observability.PropagationContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(afterName = "org.springframework.boot.actuate.autoconfigure.tracing.MicrometerTracingAutoConfiguration")
public class PropagationAutoConfiguration {
  @Bean @ConditionalOnMissingBean
  PropagationContext propagationContext(ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
    return new PropagationContext(tracer.getIfAvailable(() -> Tracer.NOOP), propagator.getIfAvailable(() -> Propagator.NOOP));
  }
}

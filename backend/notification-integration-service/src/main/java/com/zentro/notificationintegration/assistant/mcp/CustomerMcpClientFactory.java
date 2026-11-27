package com.zentro.notificationintegration.assistant.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zentro.notificationintegration.config.AssistantProperties;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.springframework.ai.mcp.client.common.autoconfigure.McpClientAutoConfiguration;
import org.springframework.ai.mcp.client.common.autoconfigure.McpToolCallbackAutoConfiguration;
import org.springframework.ai.mcp.client.common.autoconfigure.NamedClientMcpTransport;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.ResolvableType;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Uses Spring AI's official client and callback auto-configurations with a private transport per turn.
 * No customer credentials or clients are stored in singleton state or shared with another context.
 */
@Component
public class CustomerMcpClientFactory {
  private final AssistantProperties properties;
  private final ObjectMapper mapper;
  private final ConfigurableEnvironment environment;
  private final ParcelPricingGrpcClient parcelGrpc;

  public CustomerMcpClientFactory(AssistantProperties properties, ObjectMapper mapper,
      ConfigurableEnvironment environment) {
    this(properties, mapper, environment, (ParcelPricingGrpcClient) null);
  }

  @Autowired
  public CustomerMcpClientFactory(AssistantProperties properties, ObjectMapper mapper,
      ConfigurableEnvironment environment, ObjectProvider<ParcelPricingGrpcClient> parcelGrpcProvider) {
    this(properties, mapper, environment, parcelGrpcProvider.getIfAvailable());
  }

  public CustomerMcpClientFactory(AssistantProperties properties, ObjectMapper mapper,
      ConfigurableEnvironment environment, ParcelPricingGrpcClient parcelGrpc) {
    this.properties = properties;
    this.mapper = mapper;
    this.environment = environment;
    this.parcelGrpc = parcelGrpc;
  }

  public CustomerMcpSession open(Jwt jwt) {
    var context = new AnnotationConfigApplicationContext();
    // Copy configuration only: no parent bean factory, so another customer's beans cannot be inherited.
    context.getEnvironment().merge(environment);
    context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("customer-mcp-session", Map.of(
        "spring.ai.mcp.client.enabled", true,
        "spring.ai.mcp.client.type", "SYNC",
        "spring.ai.mcp.client.initialized", true,
        "spring.ai.mcp.client.toolcallback.enabled", true)));
    var transport = new InProcessMcpTransport(mapper);
    context.registerBean("customerMcpServer", McpSyncServer.class, () -> McpServer.sync(transport)
        .serverInfo("zentro-customer-support", "1.0")
        .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
        .tools(new CustomerSupportTools(parcelGrpc, jwt, mapper).specifications()).build(),
        definition -> definition.setDestroyMethodName("close"));
    var transports = new RootBeanDefinition(List.class);
    transports.setTargetType(ResolvableType.forClassWithGenerics(List.class, NamedClientMcpTransport.class));
    transports.setDependsOn("customerMcpServer");
    transports.setInstanceSupplier(() -> List.of(new NamedClientMcpTransport("embedded-support", transport)));
    context.registerBeanDefinition("customerMcpTransports", transports);
    context.register(McpClientAutoConfiguration.class, McpToolCallbackAutoConfiguration.class);
    try {
      context.refresh();
      return new CustomerMcpSession(context);
    } catch (RuntimeException ex) {
      context.close();
      transport.close();
      throw ex;
    }
  }
}

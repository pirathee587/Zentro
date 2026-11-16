package com.zentro.notificationintegration.assistant.mcp;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.ConfigurableApplicationContext;

/** Owns the Spring-managed client lifecycle for exactly one customer chat invocation. */
public final class CustomerMcpSession implements AutoCloseable {
  private final ConfigurableApplicationContext context;
  private final ToolCallbackProvider callbacks;

  CustomerMcpSession(ConfigurableApplicationContext context) {
    this.context = context;
    this.callbacks = context.getBean(ToolCallbackProvider.class);
  }

  public ToolCallback[] toolCallbacks() { return callbacks.getToolCallbacks(); }

  @Override public void close() { context.close(); }
}

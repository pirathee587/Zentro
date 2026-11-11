package com.zentro.trackingreporting.graphql.config;

import graphql.ExecutionResult;
import graphql.analysis.FieldComplexityCalculator;
import graphql.analysis.MaxQueryComplexityInstrumentation;
import graphql.analysis.MaxQueryDepthInstrumentation;
import graphql.execution.ExecutionContext;
import graphql.execution.instrumentation.ChainedInstrumentation;
import graphql.execution.instrumentation.Instrumentation;
import graphql.execution.instrumentation.InstrumentationContext;
import graphql.execution.instrumentation.InstrumentationState;
import graphql.execution.instrumentation.SimpleInstrumentationContext;
import graphql.execution.instrumentation.parameters.InstrumentationExecuteOperationParameters;
import graphql.language.OperationDefinition;
import java.util.List;
import org.springframework.boot.autoconfigure.graphql.GraphQlSourceBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GraphQlConfig {

  public static final int MAX_DEPTH = 7;
  public static final int MAX_COMPLEXITY = 100;

  @Bean
  public MaxQueryDepthInstrumentation maxQueryDepthInstrumentation() {
    return new SafeMaxQueryDepthInstrumentation(MAX_DEPTH);
  }

  @Bean
  public MaxQueryComplexityInstrumentation maxQueryComplexityInstrumentation() {
    FieldComplexityCalculator calculator = (params, childComplexity) -> {
      int base = 1 + childComplexity;
      // Account for connection size multiplier if specified
      if (params.getArguments().containsKey("size")) {
        Object sizeObj = params.getArguments().get("size");
        if (sizeObj instanceof Number num) {
          int size = num.intValue();
          if (size > 1) {
            base += Math.min(size, 50) - 1;
          }
        }
      }
      return base;
    };
    return new SafeMaxQueryComplexityInstrumentation(MAX_COMPLEXITY, calculator);
  }

  @Bean
  public GraphQlSourceBuilderCustomizer graphQlSourceBuilderCustomizer(
      MaxQueryDepthInstrumentation depthInstrumentation,
      MaxQueryComplexityInstrumentation complexityInstrumentation) {
    return builder -> builder.configureGraphQl(graphQlBuilder -> {
      Instrumentation chained = new ChainedInstrumentation(
          List.of(depthInstrumentation, complexityInstrumentation)
      );
      graphQlBuilder.instrumentation(chained);
    });
  }

  static class SafeMaxQueryDepthInstrumentation extends MaxQueryDepthInstrumentation {
    SafeMaxQueryDepthInstrumentation(int maxDepth) {
      super(maxDepth);
    }

    @Override
    public InstrumentationContext<ExecutionResult> beginExecuteOperation(
        InstrumentationExecuteOperationParameters parameters, InstrumentationState state) {
      ExecutionContext ctx = parameters.getExecutionContext();
      OperationDefinition op = ctx.getOperationDefinition();
      if (op != null && op.getOperation() == OperationDefinition.Operation.MUTATION
          && ctx.getGraphQLSchema().getMutationType() == null) {
        return SimpleInstrumentationContext.noOp();
      }
      return super.beginExecuteOperation(parameters, state);
    }
  }

  static class SafeMaxQueryComplexityInstrumentation extends MaxQueryComplexityInstrumentation {
    SafeMaxQueryComplexityInstrumentation(int maxComplexity, FieldComplexityCalculator calculator) {
      super(maxComplexity, calculator);
    }

    @Override
    public InstrumentationContext<ExecutionResult> beginExecuteOperation(
        InstrumentationExecuteOperationParameters parameters, InstrumentationState state) {
      ExecutionContext ctx = parameters.getExecutionContext();
      OperationDefinition op = ctx.getOperationDefinition();
      if (op != null && op.getOperation() == OperationDefinition.Operation.MUTATION
          && ctx.getGraphQLSchema().getMutationType() == null) {
        return SimpleInstrumentationContext.noOp();
      }
      return super.beginExecuteOperation(parameters, state);
    }
  }
}

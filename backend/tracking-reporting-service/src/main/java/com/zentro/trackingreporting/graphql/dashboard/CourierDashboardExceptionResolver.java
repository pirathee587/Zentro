package com.zentro.trackingreporting.graphql.dashboard;

import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.stereotype.Component;

@Component
@Order(-10)
public class CourierDashboardExceptionResolver extends DataFetcherExceptionResolverAdapter {
  @Override protected GraphQLError resolveToSingleError(Throwable error, DataFetchingEnvironment env) {
    if (!(error instanceof CourierDashboardException failure)) return null;
    return GraphqlErrorBuilder.newError(env).errorType(ErrorType.INTERNAL_ERROR)
        .message(failure.getMessage()).extensions(Map.of("code", failure.code())).build();
  }
}

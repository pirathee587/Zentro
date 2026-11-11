package com.zentro.trackingreporting.graphql.exception;

import com.zentro.trackingreporting.exception.TrackingNotFoundException;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import jakarta.validation.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class GraphQlExceptionResolver extends DataFetcherExceptionResolverAdapter {

  private static final Logger log = LoggerFactory.getLogger(GraphQlExceptionResolver.class);

  @Override
  protected GraphQLError resolveToSingleError(Throwable ex, DataFetchingEnvironment env) {
    Throwable cause = ex;
    while ((cause instanceof java.util.concurrent.CompletionException
        || cause instanceof org.springframework.core.NestedRuntimeException)
        && cause.getCause() != null && cause.getCause() != cause) {
      cause = cause.getCause();
    }

    if (cause instanceof org.springframework.security.core.AuthenticationException ae) {
      return GraphqlErrorBuilder.newError(env)
          .errorType(ErrorType.UNAUTHORIZED)
          .message(ae.getMessage() != null ? ae.getMessage() : "Authentication is required")
          .path(env.getExecutionStepInfo().getPath())
          .build();
    }

    if (cause instanceof AccessDeniedException ade) {
      Authentication auth = SecurityContextHolder.getContext().getAuthentication();
      boolean isUnauthenticated = (auth == null || !auth.isAuthenticated()
          || "anonymousUser".equalsIgnoreCase(auth.getName()));
      ErrorType errorType = isUnauthenticated ? ErrorType.UNAUTHORIZED : ErrorType.FORBIDDEN;
      return GraphqlErrorBuilder.newError(env)
          .errorType(errorType)
          .message(ade.getMessage() != null ? ade.getMessage() : "Access denied")
          .path(env.getExecutionStepInfo().getPath())
          .build();
    }

    if (cause instanceof TrackingNotFoundException tnfe) {
      return GraphqlErrorBuilder.newError(env)
          .errorType(ErrorType.NOT_FOUND)
          .message(tnfe.getMessage())
          .path(env.getExecutionStepInfo().getPath())
          .build();
    }

    if (cause instanceof IllegalArgumentException iae) {
      return GraphqlErrorBuilder.newError(env)
          .errorType(ErrorType.BAD_REQUEST)
          .message(iae.getMessage())
          .path(env.getExecutionStepInfo().getPath())
          .build();
    }

    if (cause instanceof ValidationException ve) {
      return GraphqlErrorBuilder.newError(env)
          .errorType(ErrorType.BAD_REQUEST)
          .message(ve.getMessage())
          .path(env.getExecutionStepInfo().getPath())
          .build();
    }

    log.error("Unhandled GraphQL execution exception at path {}: {}",
        env.getExecutionStepInfo().getPath(), cause.getMessage(), cause);
    return GraphqlErrorBuilder.newError(env)
        .errorType(ErrorType.INTERNAL_ERROR)
        .message("An unexpected error occurred while processing the request.")
        .path(env.getExecutionStepInfo().getPath())
        .build();
  }
}

package com.zentro.identitypartner.user.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** The user does not exist (404). The message never contains the user id or the Keycloak subject. */
public class UserNotFoundException extends ResponseStatusException {
  public static final String MESSAGE = "User not found.";

  public UserNotFoundException() {
    super(HttpStatus.NOT_FOUND, MESSAGE);
  }
}

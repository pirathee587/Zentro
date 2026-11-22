package com.zentro.identitypartner.user.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The signed-in Keycloak identity has no Zentro profile, but its email already belongs to a profile
 * owned by a different Keycloak identity. A second business profile is never created for that email:
 * the person must use their original sign-in (or have the identities reconciled by an administrator).
 * The message only concerns the caller's own email address, so it reveals nothing about other accounts.
 */
public class AccountIdentityConflictException extends ResponseStatusException {
  public static final String CODE = "ACCOUNT_IDENTITY_CONFLICT";
  public static final String MESSAGE =
      "This email address is already registered with a different Zentro sign-in. "
          + "Sign in with the method you originally used, or contact Zentro support to link your sign-in methods.";

  public AccountIdentityConflictException() {
    super(HttpStatus.CONFLICT, MESSAGE);
  }
}

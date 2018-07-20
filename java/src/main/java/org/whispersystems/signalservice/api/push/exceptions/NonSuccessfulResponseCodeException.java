/**
 * Copyright (C) 2014-2016 Open Whisper Systems
 *
 * Licensed according to the LICENSE file in this repository.
 */

package org.whispersystems.signalservice.api.push.exceptions;

import java.io.IOException;

public class NonSuccessfulResponseCodeException extends IOException {

  public static final int UNKNOWN = -1;

  private final int responseCode;

  public NonSuccessfulResponseCodeException() {
    super();
    responseCode = UNKNOWN;
  }

  public NonSuccessfulResponseCodeException(String s) {
    this(s, UNKNOWN);
  }

  public NonSuccessfulResponseCodeException(String message, int responseCode) {
    super("[Code: " + responseCode + "] " + message);
    this.responseCode = responseCode;
  }

  public int getResponseCode() {
    return responseCode;
  }
}

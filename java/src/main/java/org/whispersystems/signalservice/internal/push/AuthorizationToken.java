package org.whispersystems.signalservice.internal.push;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;

import org.apache.http.util.TextUtils;

public class AuthorizationToken {

  @JsonProperty
  private String token;

  @JsonIgnore
  private String username = "";

  @JsonSetter("token")
  public void setToken(String token) {
    this.token = token;

    if (!TextUtils.isEmpty(token)) {
      String[] parts = token.split(":");
      username = parts[0];
    }
  }

  public String getToken() {
    return token;
  }

  public String getUsername() {
    return username;
  }
}

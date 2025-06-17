package me.ksyz.accountmanager.auth;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Optional;

public class Account {
  private String refreshToken;
  private String accessToken;
  private String username;
  private String uuid;
  private long unban;
  private AccountType type;

  public Account(String refreshToken, String accessToken, String username, String uuid) {
    this(refreshToken, accessToken, username, uuid, 0L, AccountType.PREMIUM);
  }

  public Account(String refreshToken, String accessToken, String username, String uuid, long unban) {
    this(refreshToken, accessToken, username, uuid, unban, AccountType.PREMIUM);
  }

  public Account(String refreshToken, String accessToken, String username, String uuid, long unban, AccountType type) {
    this.refreshToken = refreshToken;
    this.accessToken = accessToken;
    this.username = username;
    this.uuid = uuid;
    this.unban = unban;
    this.type = type;
  }

  public Account(String username, String accessToken, String uuid) {
    this("", accessToken, username, uuid, 0L, AccountType.PREMIUM);
  }


  public String getRefreshToken() {
    return refreshToken;
  }

  public String getAccessToken() {
    return accessToken;
  }

  public String getUsername() {
    return username;
  }

  public String getUuid() { // 新增：获取 UUID 的方法
    return uuid;
  }

  public long getUnban() {
    return unban;
  }

  public AccountType getType() {
    return type;
  }

  public void setRefreshToken(String refreshToken) {
    this.refreshToken = refreshToken;
  }

  public void setAccessToken(String accessToken) {
    this.accessToken = accessToken;
  }

  public void setUsername(String username) {
    this.username = username;
  }

  public void setUuid(String uuid) {
    this.uuid = uuid;
  }

  public void setUnban(long unban) {
    this.unban = unban;
  }

  public void setType(AccountType type) {
    this.type = type;
  }

  public JsonObject toJson() {
    JsonObject jsonObject = new JsonObject();
    jsonObject.addProperty("refreshToken", refreshToken);
    jsonObject.addProperty("accessToken", accessToken);
    jsonObject.addProperty("username", username);
    jsonObject.addProperty("uuid", uuid);
    jsonObject.addProperty("unban", unban);
    jsonObject.addProperty("type", type.toString());
    return jsonObject;
  }

  public static Account fromJson(JsonObject jsonObject) {
    return new Account(
            Optional.ofNullable(jsonObject.get("refreshToken")).map(JsonElement::getAsString).orElse(""),
            Optional.ofNullable(jsonObject.get("accessToken")).map(JsonElement::getAsString).orElse(""),
            Optional.ofNullable(jsonObject.get("username")).map(JsonElement::getAsString).orElse(""),
            Optional.ofNullable(jsonObject.get("uuid")).map(JsonElement::getAsString).orElse(""),
            Optional.ofNullable(jsonObject.get("unban")).map(JsonElement::getAsLong).orElse(0L),
            Optional.ofNullable(jsonObject.get("type")).map(JsonElement::getAsString).map(AccountType::valueOf).orElse(AccountType.PREMIUM)
    );
  }

  @Override
  public String toString() {
    return "Account{" +
            "refreshToken='" + refreshToken + '\'' +
            ", accessToken='" + accessToken + '\'' +
            ", username='" + username + '\'' +
            ", uuid='" + uuid + '\'' +
            ", unban=" + unban +
            ", type=" + type +
            '}';
  }
}
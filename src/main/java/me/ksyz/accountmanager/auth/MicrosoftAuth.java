package me.ksyz.accountmanager.auth;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import me.ksyz.accountmanager.utils.SSLUtil;
import net.minecraft.util.Session;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.http.conn.ssl.BrowserCompatHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

// Based on Auth Me (https://github.com/axieum/authme)
public final class MicrosoftAuth {
  // A reusable Apache HTTP request config
  public static final RequestConfig REQUEST_CONFIG = RequestConfig
          .custom()
          .setConnectionRequestTimeout(30_000)
          .setConnectTimeout(30_000)
          .setSocketTimeout(30_000)
          .build();
  // Account Manager
  public static final String CLIENT_ID = "42a60a84-599d-44b2-a7c6-b00cdef1d6a2";
  // 25565 + 10
  public static final int PORT = 25575;

  public static URI getMSAuthLink(String state) {
    try {
      // Build a Microsoft login url
      URIBuilder uriBuilder = new URIBuilder("https://login.live.com/oauth20_authorize.srf")
              .addParameter("client_id", CLIENT_ID)
              .addParameter("response_type", "code")
              .addParameter("redirect_uri", String.format("http://localhost:%d/callback", PORT))
              .addParameter("scope", "XboxLive.signin XboxLive.offline_access")
              .addParameter("state", state)
              .addParameter("prompt", "select_account");
      return uriBuilder.build();
    } catch (Exception e) {
      return null;
    }
  }

  public static CompletableFuture<String> acquireMSAuthCode(String state, Executor executor) {
    return CompletableFuture.supplyAsync(() -> {
      try {
        // Prepare a temporary HTTP server we can listen for the OAuth2 callback on
        HttpServer server = HttpServer.create(
                new InetSocketAddress(PORT), 0
        );

        // Track when a request has been handled
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> authCode = new AtomicReference<>(null),
                errorMsg = new AtomicReference<>(null);

        server.createContext("/callback", exchange -> {
          // Parse the query parameters
          Map<String, String> query = URLEncodedUtils
                  .parse(
                          exchange.getRequestURI().toString().replaceAll("/callback\\?", ""),
                          StandardCharsets.UTF_8
                  )
                  .stream()
                  .collect(Collectors.toMap(NameValuePair::getName, NameValuePair::getValue));

          // Check the returned parameter values
          if (!state.equals(query.get("state"))) {
            // The "state" does not match what we sent
            errorMsg.set(
                    String.format("State mismatch! Expected '%s' but got '%s'.", state, query.get("state"))
            );
          } else if (query.containsKey("code")) {
            // Successfully matched the auth code
            authCode.set(query.get("code"));
          } else if (query.containsKey("error")) {
            // Otherwise, try to find an error description
            errorMsg.set(String.format("%s: %s", query.get("error"), query.get("error_description")));
          }

          // Send a response informing that the browser may now be closed
          InputStream stream = MicrosoftAuth.class.getResourceAsStream("/callback.html");
          byte[] response = stream != null ? IOUtils.toByteArray(stream) : new byte[0];
          exchange.getResponseHeaders().add("Content-Type", "text/html");
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.getResponseBody().close();

          // Let the caller thread know that the request has been handled
          latch.countDown();
        });

        try {
          // Start the HTTP server (http://localhost:25575/callback)
          server.start();

          // Wait for the server to stop and return the auth code
          latch.await();

          // If present, return
          return Optional.ofNullable(authCode.get())
                  .filter(code -> !StringUtils.isBlank(code))
                  // Otherwise, throw an exception with the error description (if present)
                  .orElseThrow(() -> new Exception(
                          Optional.ofNullable(errorMsg.get())
                                  .orElse("There was no auth code or error description present.")
                  ));
        } finally {
          // Always release the server
          server.stop(2);
        }
      } catch (InterruptedException e) {
        throw new CancellationException("Microsoft auth code acquisition was cancelled!");
      } catch (Exception e) {
        throw new CompletionException("Unable to acquire Microsoft auth code!", e);
      }
    }, executor);
  }

  private static CloseableHttpClient createTrustedHttpClient() {
    try {
      javax.net.ssl.SSLSocketFactory socketFactory = SSLUtil.getSSLContext().getSocketFactory();

      SSLConnectionSocketFactory sslsf = new SSLConnectionSocketFactory(
              socketFactory,
              new String[]{"TLSv1.2"},
              null,
              new BrowserCompatHostnameVerifier()
      );

      return HttpClientBuilder.create()
              .setSSLSocketFactory(sslsf)
              .build();
    } catch (Exception e) {
      e.printStackTrace();
      return HttpClients.createDefault();
    }
  }

  public static CompletableFuture<Map<String, String>> acquireMSAccessTokens(String authCode, Executor executor) {
    return CompletableFuture.supplyAsync(() -> {
      try (CloseableHttpClient client = createTrustedHttpClient()) {
        // Build a new HTTP request
        HttpPost request = new HttpPost(URI.create("https://login.live.com/oauth20_token.srf"));
        request.setConfig(REQUEST_CONFIG);
        request.setHeader("Content-Type", "application/x-www-form-urlencoded");
        request.setEntity(new UrlEncodedFormEntity(
                Arrays.asList(
                        new BasicNameValuePair("client_id", CLIENT_ID),
                        new BasicNameValuePair("grant_type", "authorization_code"),
                        new BasicNameValuePair("code", authCode),
                        // We must provide the exact redirect URI that was used to obtain the auth code
                        new BasicNameValuePair(
                                "redirect_uri", String.format("http://localhost:%d/callback", PORT)
                        )
                ),
                "UTF-8"
        ));

        // Send the request on the HTTP client
        HttpResponse res = client.execute(request);

        // Attempt to parse the response body as JSON and extract the access and refresh tokens
        JsonObject json = new JsonParser().parse(EntityUtils.toString(res.getEntity())).getAsJsonObject();
        String accessToken = Optional.ofNullable(json.get("access_token"))
                .map(JsonElement::getAsString)
                .filter(token -> !StringUtils.isBlank(token))
                .orElseThrow(() -> new Exception(json.has("error") ?
                        String.format("%s: %s", json.get("error").getAsString(), json.get("error_description").getAsString()) :
                        "There was no Microsoft access token or error description present."
                ));
        String refreshToken = Optional.ofNullable(json.get("refresh_token"))
                .map(JsonElement::getAsString)
                .filter(token -> !StringUtils.isBlank(token))
                .orElseThrow(() -> new Exception(json.has("error") ?
                        String.format("%s: %s", json.get("error").getAsString(), json.get("error_description").getAsString()) :
                        "There was no Microsoft refresh token or error description present."
                ));

        // Return an immutable mapping of the access and refresh tokens
        Map<String, String> result = new HashMap<>();
        result.put("access_token", accessToken);
        result.put("refresh_token", refreshToken);
        return result;
      } catch (InterruptedException e) {
        throw new CancellationException("Microsoft access tokens acquisition was cancelled!");
      } catch (Exception e) {
        throw new CompletionException("Unable to acquire Microsoft access tokens!", e);
      }
    }, executor);
  }

  public static CompletableFuture<Map<String, String>> refreshMSAccessTokens(String msToken, Executor executor) {
    return CompletableFuture.supplyAsync(() -> {
      try (CloseableHttpClient client = createTrustedHttpClient()) {
        // Build a new HTTP request
        HttpPost request = new HttpPost(URI.create("https://login.live.com/oauth20_token.srf"));
        request.setConfig(REQUEST_CONFIG);
        request.setHeader("Content-Type", "application/x-www-form-urlencoded");
        request.setEntity(new UrlEncodedFormEntity(
                Arrays.asList(
                        new BasicNameValuePair("client_id", CLIENT_ID),
                        new BasicNameValuePair("grant_type", "refresh_token"),
                        new BasicNameValuePair("refresh_token", msToken),
                        // We must provide the exact redirect URI that was used to obtain the auth code
                        new BasicNameValuePair(
                                "redirect_uri", String.format("http://localhost:%d/callback", PORT)
                        )
                ),
                "UTF-8"
        ));

        // Send the request on the HTTP client
        HttpResponse res = client.execute(request);

        // Attempt to parse the response body as JSON and extract the access and refresh tokens
        JsonObject json = new JsonParser().parse(EntityUtils.toString(res.getEntity())).getAsJsonObject();
        String accessToken = Optional.ofNullable(json.get("access_token"))
                .map(JsonElement::getAsString)
                .filter(token -> !StringUtils.isBlank(token))
                .orElseThrow(() -> new Exception(json.has("error") ?
                        String.format("%s: %s", json.get("error").getAsString(), json.get("error_description").getAsString()) :
                        "There was no Microsoft access token or error description present."
                ));
        String refreshToken = Optional.ofNullable(json.get("refresh_token"))
                .map(JsonElement::getAsString)
                .filter(token -> !StringUtils.isBlank(token))
                .orElseThrow(() -> new Exception(json.has("error") ?
                        String.format("%s: %s", json.get("error").getAsString(), json.get("error_description").getAsString()) :
                        "There was no Microsoft refresh token or error description present."
                ));

        // Return an immutable mapping of the access and refresh tokens
        Map<String, String> result = new HashMap<>();
        result.put("access_token", accessToken);
        result.put("refresh_token", refreshToken);
        return result;
      } catch (InterruptedException e) {
        throw new CancellationException("Microsoft access tokens acquisition was cancelled!");
      } catch (Exception e) {
        throw new CompletionException("Unable to acquire Microsoft access tokens!", e);
      }
    }, executor);
  }

  public static CompletableFuture<String> acquireXboxAccessToken(String accessToken, Executor executor) {
    return CompletableFuture.supplyAsync(() -> {
      try (CloseableHttpClient client = createTrustedHttpClient()) {
        // Build a new HTTP request
        HttpPost request = new HttpPost(URI.create("https://user.auth.xboxlive.com/user/authenticate"));
        JsonObject entity = new JsonObject();
        JsonObject properties = new JsonObject();
        properties.addProperty("AuthMethod", "RPS");
        properties.addProperty("SiteName", "user.auth.xboxlive.com");
        properties.addProperty("RpsTicket", String.format("d=%s", accessToken));
        entity.add("Properties", properties);
        entity.addProperty("RelyingParty", "http://auth.xboxlive.com");
        entity.addProperty("TokenType", "JWT");
        request.setConfig(REQUEST_CONFIG);
        request.setHeader("Content-Type", "application/json");
        request.setEntity(new StringEntity(entity.toString()));

        // Send the request on the HTTP client
        HttpResponse res = client.execute(request);

        // Attempt to parse the response body as JSON and extract the access token
        JsonObject json = res.getStatusLine().getStatusCode() == 200
                ? new JsonParser().parse(EntityUtils.toString(res.getEntity())).getAsJsonObject()
                : new JsonObject();
        // If present, return
        return Optional.ofNullable(json.get("Token"))
                .map(JsonElement::getAsString)
                .filter(token -> !StringUtils.isBlank(token))
                // Otherwise, throw an exception with the error description (if present)
                .orElseThrow(() -> new Exception(json.has("XErr") ?
                        String.format("%s: %s", json.get("XErr").getAsString(), json.get("Message").getAsString()) :
                        "There was no access token or error description present."
                ));
      } catch (InterruptedException e) {
        throw new CancellationException("Xbox Live access token acquisition was cancelled!");
      } catch (Exception e) {
        throw new CompletionException("Unable to acquire Xbox Live access token!", e);
      }
    }, executor);
  }

  public static CompletableFuture<Map<String, String>> acquireXboxXstsToken(String accessToken, Executor executor) {
    return CompletableFuture.supplyAsync(() -> {
      try (CloseableHttpClient client = createTrustedHttpClient()) {
        // Build a new HTTP request
        HttpPost request = new HttpPost("https://xsts.auth.xboxlive.com/xsts/authorize");
        JsonObject entity = new JsonObject();
        JsonObject properties = new JsonObject();
        JsonArray userTokens = new JsonArray();
        userTokens.add(new JsonPrimitive(accessToken));
        properties.addProperty("SandboxId", "RETAIL");
        properties.add("UserTokens", userTokens);
        entity.add("Properties", properties);
        entity.addProperty("RelyingParty", "rp://api.minecraftservices.com/");
        entity.addProperty("TokenType", "JWT");
        request.setConfig(REQUEST_CONFIG);
        request.setHeader("Content-Type", "application/json");
        request.setEntity(new StringEntity(entity.toString()));

        // Send the request on the HTTP client
        HttpResponse res = client.execute(request);

        // Attempt to parse the response body as JSON and extract the access token and user hash
        JsonObject json = res.getStatusLine().getStatusCode() == 200
                ? new JsonParser().parse(EntityUtils.toString(res.getEntity())).getAsJsonObject()
                : new JsonObject();
        return Optional.ofNullable(json.get("Token"))
                .map(JsonElement::getAsString)
                .filter(token -> !StringUtils.isBlank(token))
                // If present, extract the user hash and return
                .map(token -> {
                  // Extract the user hash
                  String uhs = json.get("DisplayClaims").getAsJsonObject()
                          .get("xui").getAsJsonArray()
                          .get(0).getAsJsonObject()
                          .get("uhs").getAsString();

                  // Return an immutable mapping of the token and user hash
                  Map<String, String> result = new HashMap<>();
                  result.put("Token", token);
                  result.put("uhs", uhs);
                  return result;
                })
                // Otherwise, throw an exception with the error description (if present)
                .orElseThrow(() -> new Exception(json.has("XErr") ?
                        String.format("%s: %s", json.get("XErr").getAsString(), json.get("Message").getAsString()) :
                        "There was no access token or error description present."
                ));
      } catch (InterruptedException e) {
        throw new CancellationException("Xbox Live XSTS token acquisition was cancelled!");
      } catch (Exception e) {
        throw new CompletionException("Unable to acquire Xbox Live XSTS token!", e);
      }
    }, executor);
  }

  public static CompletableFuture<String> acquireMCAccessToken(String xstsToken, String userHash, Executor executor) {
    return CompletableFuture.supplyAsync(() -> {
      try (CloseableHttpClient client = createTrustedHttpClient()) {
        // Build a new HTTP request
        HttpPost request = new HttpPost(URI.create("https://api.minecraftservices.com/authentication/login_with_xbox"));
        request.setConfig(REQUEST_CONFIG);
        request.setHeader("Content-Type", "application/json");
        request.setEntity(new StringEntity(
                String.format("{\"identityToken\": \"XBL3.0 x=%s;%s\"}", userHash, xstsToken)
        ));

        // Send the request on the HTTP client
        HttpResponse res = client.execute(request);

        // Attempt to parse the response body as JSON and extract the access token
        JsonObject json = new JsonParser().parse(EntityUtils.toString(res.getEntity())).getAsJsonObject();

        // If present, return
        return Optional.ofNullable(json.get("access_token"))
                .map(JsonElement::getAsString)
                .filter(token -> !StringUtils.isBlank(token))
                // Otherwise, throw an exception with the error description (if present)
                .orElseThrow(() -> new Exception(json.has("error") ?
                        String.format("%s: %s", json.get("error").getAsString(), json.get("errorMessage").getAsString()) :
                        "There was no access token or error description present."
                ));
      } catch (InterruptedException e) {
        throw new CancellationException("Minecraft access token acquisition was cancelled!");
      } catch (Exception e) {
        throw new CompletionException("Unable to acquire Minecraft access token!", e);
      }
    }, executor);
  }

  /**
   * Attempts to log in using a Minecraft Access Token and fetches the profile from Mojang API.
   * This is the original login method.
   *
   * @param mcToken The Minecraft Access Token.
   * @param executor The executor to run the asynchronous task on.
   * @return A CompletableFuture that completes with a Session object.
   */
  public static CompletableFuture<Session> login(String mcToken, Executor executor) {
    return CompletableFuture.supplyAsync(() -> {
      try (CloseableHttpClient client = createTrustedHttpClient()) {
        // Build a new HTTP request
        HttpGet request = new HttpGet(URI.create("https://api.minecraftservices.com/minecraft/profile"));
        request.setConfig(REQUEST_CONFIG);
        request.setHeader("Authorization", "Bearer " + mcToken);

        // Send the request on the HTTP client
        HttpResponse res = client.execute(request);

        // Attempt to parse the response body as JSON and extract the profile
        JsonObject json = new JsonParser().parse(EntityUtils.toString(res.getEntity())).getAsJsonObject();

        // Check for errors first
        if (json.has("error")) {
          throw new Exception(String.format("%s: %s", json.get("error").getAsString(), json.get("errorMessage").getAsString()));
        }

        // If present, return
        return Optional.ofNullable(json.get("id"))
                .map(JsonElement::getAsString)
                .filter(uuid -> !StringUtils.isBlank(uuid))
                // If present, build a new session and return
                .map(uuid -> new Session(
                        json.get("name").getAsString(),
                        uuid,
                        mcToken,
                        Session.Type.MOJANG.toString()
                ))
                // Otherwise, throw an exception if UUID is missing for some reason
                .orElseThrow(() -> new Exception("Minecraft profile ID (UUID) was missing from the response."));
      } catch (InterruptedException e) {
        throw new CancellationException("Minecraft profile fetching was cancelled!");
      } catch (Exception e) {
        throw new CompletionException("Unable to fetch Minecraft profile!", e);
      }
    }, executor);
  }

  /**
   * Creates a Minecraft Session directly from a given access token, username, and UUID.
   * This method bypasses the Mojang profile fetching API, useful for pre-known credentials.
   *
   * @param accessToken The Minecraft Access Token.
   * @param username The desired username for the session.
   * @param uuid The desired UUID for the session.
   * @param executor The executor to run the asynchronous task on.
   * @return A CompletableFuture that completes with a Session object.
   */
  public static CompletableFuture<Session> login(String accessToken, String username, String uuid, Executor executor) {
    return CompletableFuture.supplyAsync(() -> {
      // Validate inputs for direct session creation
      if (StringUtils.isBlank(accessToken) || StringUtils.isBlank(username) || StringUtils.isBlank(uuid)) {
        throw new IllegalArgumentException("Access Token, Username, and UUID cannot be empty for direct login.");
      }

      // Create and return the Session directly
      return new Session(
              username,
              uuid, // Ensure this is the raw UUID string, not formatted.
              accessToken,
              Session.Type.MOJANG.toString() // Or Session.Type.LEGACY/MSFT depending on your needs
      );
    }, executor);
  }
}
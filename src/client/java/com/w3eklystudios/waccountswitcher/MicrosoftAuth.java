package com.w3eklystudios.waccountswitcher;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Consumer;

final class MicrosoftAuth {
    private static final String CLIENT_ID = "00000000402b5328";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    private MicrosoftAuth() {}

    record DeviceCode(String userCode, String verificationUri, String deviceCode, int interval, int expiresIn) {}

    static DeviceCode startDeviceCode() throws Exception {
        JsonObject j = form("https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode",
                "client_id", CLIENT_ID, "scope", "XboxLive.signin offline_access");
        return new DeviceCode(j.get("user_code").getAsString(), j.get("verification_uri").getAsString(),
                j.get("device_code").getAsString(), j.has("interval") ? j.get("interval").getAsInt() : 5,
                j.get("expires_in").getAsInt());
    }

    static Account completeDeviceCode(DeviceCode device, Consumer<String> status) throws Exception {
        String refresh = null;
        long end = System.currentTimeMillis() + device.expiresIn * 1000L;
        int interval = device.interval;
        while (System.currentTimeMillis() < end) {
            Thread.sleep(interval * 1000L);
            JsonObject result = formAllowError("https://login.microsoftonline.com/consumers/oauth2/v2.0/token",
                    "grant_type", "urn:ietf:params:oauth:grant-type:device_code", "client_id", CLIENT_ID,
                    "device_code", device.deviceCode);
            if (result.has("access_token")) { refresh = result.get("refresh_token").getAsString(); break; }
            String error = result.has("error") ? result.get("error").getAsString() : "";
            if (error.equals("authorization_pending")) continue;
            if (error.equals("slow_down")) { interval += 5; continue; }
            if (error.equals("authorization_declined") || error.equals("expired_token")) throw new IllegalStateException("Microsoft sign-in " + error.replace('_', ' ') + ".");
            throw new IllegalStateException(result.has("error_description") ? result.get("error_description").getAsString() : "Microsoft sign-in failed.");
        }
        if (refresh == null) throw new IllegalStateException("Microsoft sign-in code expired. Try again.");
        status.accept("Microsoft approved; checking Xbox and Minecraft access…");
        return authenticate(refresh);
    }

    static Account refresh(Account old) throws Exception {
        if (old.refreshToken == null || old.refreshToken.isBlank()) throw new IllegalStateException("This account has no refresh token. Sign in with Microsoft again.");
        JsonObject token = form("https://login.microsoftonline.com/consumers/oauth2/v2.0/token",
                "grant_type", "refresh_token", "client_id", CLIENT_ID, "refresh_token", old.refreshToken,
                "scope", "XboxLive.signin offline_access");
        String refresh = token.has("refresh_token") ? token.get("refresh_token").getAsString() : old.refreshToken;
        return authenticate(refresh, token.get("access_token").getAsString());
    }

    static Account authenticate(String refreshToken) throws Exception {
        JsonObject token = form("https://login.microsoftonline.com/consumers/oauth2/v2.0/token",
                "grant_type", "refresh_token", "client_id", CLIENT_ID, "refresh_token", refreshToken,
                "scope", "XboxLive.signin offline_access");
        return authenticate(token.get("refresh_token").getAsString(), token.get("access_token").getAsString());
    }

    private static Account authenticate(String refreshToken, String microsoftToken) throws Exception {
        JsonObject xbl = postJson("https://user.auth.xboxlive.com/user/authenticate", """
                {"Properties":{"AuthMethod":"RPS","SiteName":"user.auth.xboxlive.com","RpsTicket":"d=%s"},"RelyingParty":"http://auth.xboxlive.com","TokenType":"JWT"}
                """.formatted(escapeJson(microsoftToken)));
        String userHash = xbl.getAsJsonObject("DisplayClaims").getAsJsonArray("xui").get(0).getAsJsonObject().get("uhs").getAsString();
        JsonObject xsts = postJson("https://xsts.auth.xboxlive.com/xsts/authorize", """
                {"Properties":{"SandboxId":"RETAIL","UserTokens":["%s"]},"RelyingParty":"rp://api.minecraftservices.com/","TokenType":"JWT"}
                """.formatted(escapeJson(xbl.get("Token").getAsString())));
        String xstsToken = xsts.get("Token").getAsString();
        String identity = "XBL3.0 x=" + userHash + ";" + xstsToken;
        JsonObject mc = postJson("https://api.minecraftservices.com/authentication/login_with_xbox",
                "{\"identityToken\":\"" + escapeJson(identity) + "\"}");
        String mcToken = mc.get("access_token").getAsString();
        JsonObject profile = getJson("https://api.minecraftservices.com/minecraft/profile", mcToken);
        return new Account(profile.get("name").getAsString(), profile.get("id").getAsString(), mcToken,
                refreshToken, userHash, "microsoft");
    }

    static Account tokenLogin(String mcAccessToken) throws Exception {
        JsonObject profile = getJson("https://api.minecraftservices.com/minecraft/profile", mcAccessToken);
        return new Account(profile.get("name").getAsString(), profile.get("id").getAsString(),
                mcAccessToken, null, null, "token");
    }

    private static JsonObject getJson(String url, String bearer) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + bearer).GET().build();
        return read(request, false);
    }
    private static JsonObject postJson(String url, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return read(request);
    }
    private static JsonObject form(String url, String... fields) throws Exception {
        JsonObject result = formAllowError(url, fields);
        if (result.has("error")) throw new IllegalStateException(result.has("error_description")
                ? result.get("error_description").getAsString() : result.get("error").getAsString());
        return result;
    }
    private static JsonObject formAllowError(String url, String... fields) throws Exception {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < fields.length; i += 2) {
            if (!body.isEmpty()) body.append('&');
            body.append(enc(fields[i])).append('=').append(enc(fields[i + 1]));
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        return read(request, true);
    }
    private static JsonObject read(HttpRequest request) throws Exception {
        return read(request, false);
    }
    private static JsonObject read(HttpRequest request, boolean allowHttpError) throws Exception {
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        JsonObject json;
        try { json = JsonParser.parseString(response.body()).getAsJsonObject(); }
        catch (Exception e) { throw new IllegalStateException("Authentication service returned an unreadable response."); }
        if (!allowHttpError && (response.statusCode() < 200 || response.statusCode() >= 300)) {
            String error = json.has("XErr") ? "Xbox error " + json.get("XErr").getAsString()
                    : json.has("errorMessage") ? json.get("errorMessage").getAsString()
                    : json.has("error_description") ? json.get("error_description").getAsString()
                    : json.has("error") ? json.get("error").getAsString() : "HTTP " + response.statusCode();
            throw new IllegalStateException(error);
        }
        return json;
    }
    private static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static String escapeJson(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\""); }
}

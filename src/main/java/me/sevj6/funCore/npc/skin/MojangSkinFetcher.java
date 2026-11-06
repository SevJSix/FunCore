package me.sevj6.funCore.npc.skin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Looks up signed skins from Mojang's official APIs:
 * <ol>
 *     <li>{@code api.mojang.com} turns a username into a UUID (skipped if a UUID is given).</li>
 *     <li>{@code sessionserver.mojang.com} returns that account's profile with its {@code textures} property
 *     signed by Mojang ({@code ?unsigned=false}).</li>
 * </ol>
 *
 * <p>Lookups run off the main thread. The futures complete on an HTTP client thread, so hop back to the main thread
 * (e.g. with {@code Utils.sync}) before touching the world. Results are cached for a few minutes so repeated
 * commands don't hit Mojang's rate limits.
 */
public final class MojangSkinFetcher {

    private static final String NAME_TO_UUID = "https://api.mojang.com/users/profiles/minecraft/";
    private static final String UUID_TO_PROFILE = "https://sessionserver.mojang.com/session/minecraft/profile/%s?unsigned=false";
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final Pattern UUID_NO_DASHES = Pattern.compile("[0-9a-fA-F]{32}");
    private static final Duration CACHE_TIME = Duration.ofMinutes(10);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final String userAgent;
    private final Map<String, CachedSkin> cache = new ConcurrentHashMap<>();

    private record CachedSkin(Skin skin, long expiresAt) {
    }

    public MojangSkinFetcher(String userAgent) {
        this.userAgent = userAgent;
    }

    /**
     * Fetches the current skin of a Minecraft account.
     *
     * @param nameOrUuid a username, or a UUID with or without dashes
     * @return completes with the skin, or exceptionally with a {@link SkinLookupException} that has a message you
     *         can show to the player
     */
    public CompletableFuture<Skin> fetch(String nameOrUuid) {
        String key = nameOrUuid.toLowerCase(Locale.ROOT);
        CachedSkin cached = cache.get(key);
        if (cached != null && cached.expiresAt() > System.currentTimeMillis()) {
            return CompletableFuture.completedFuture(cached.skin());
        }

        UUID uuid = parseUuid(nameOrUuid);
        CompletableFuture<UUID> idLookup;
        if (uuid != null) {
            idLookup = CompletableFuture.completedFuture(uuid);
        } else if (USERNAME.matcher(nameOrUuid).matches()) {
            idLookup = lookupUuid(nameOrUuid);
        } else {
            return CompletableFuture.failedFuture(new SkinLookupException("'" + nameOrUuid + "' is not a valid username or UUID."));
        }

        return idLookup.thenCompose(this::fetchProfile).whenComplete((skin, error) -> {
            if (skin != null) {
                long expiresAt = System.currentTimeMillis() + CACHE_TIME.toMillis();
                cache.put(key, new CachedSkin(skin, expiresAt));
                cache.put(skin.ownerId().toString(), new CachedSkin(skin, expiresAt));
            }
        });
    }

    private CompletableFuture<UUID> lookupUuid(String username) {
        return get(NAME_TO_UUID + username).thenApply(json -> {
            if (json == null) throw new SkinLookupException("No Minecraft account is named '" + username + "'.");
            UUID id = parseUuid(json.get("id").getAsString());
            if (id == null) throw new SkinLookupException("Mojang returned an invalid UUID for '" + username + "'.");
            return id;
        });
    }

    private CompletableFuture<Skin> fetchProfile(UUID uuid) {
        String undashed = uuid.toString().replace("-", "");
        return get(UUID_TO_PROFILE.formatted(undashed)).thenApply(json -> {
            if (json == null) throw new SkinLookupException("No Minecraft account has the UUID " + uuid + ".");
            String name = json.get("name").getAsString();
            for (JsonElement element : json.getAsJsonArray("properties")) {
                JsonObject property = element.getAsJsonObject();
                if (!"textures".equals(property.get("name").getAsString())) continue;
                JsonElement signature = property.get("signature");
                if (signature == null) throw new SkinLookupException("Mojang didn't sign the skin for " + name + ".");
                return new Skin(name, uuid, property.get("value").getAsString(), signature.getAsString());
            }
            throw new SkinLookupException(name + " has no skin data.");
        });
    }

    /** GETs a URL and parses the JSON body. Completes with {@code null} if the account doesn't exist (HTTP 204/404). */
    private CompletableFuture<@Nullable JsonObject> get(String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", userAgent)
                .header("Accept", "application/json")
                .GET()
                .build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .exceptionally(error -> {
                    throw new SkinLookupException("Couldn't reach Mojang's servers. Try again later.", error);
                })
                .thenApply(response -> switch (response.statusCode()) {
                    case 200 -> parse(response.body());
                    case 204, 404 -> null;
                    case 429 -> throw new SkinLookupException("Mojang is rate limiting requests. Wait a minute and try again.");
                    default -> throw new SkinLookupException("Mojang returned an unexpected response (HTTP " + response.statusCode() + ").");
                });
    }

    private static JsonObject parse(String body) {
        try {
            return JsonParser.parseString(body).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new SkinLookupException("Mojang returned a response that couldn't be read.", e);
        }
    }

    /** Parses a UUID with or without dashes, or returns {@code null} if the text isn't one. */
    public static @Nullable UUID parseUuid(String text) {
        if (UUID_NO_DASHES.matcher(text).matches()) {
            text = text.replaceFirst("(.{8})(.{4})(.{4})(.{4})(.{12})", "$1-$2-$3-$4-$5");
        }
        try {
            return text.length() == 36 ? UUID.fromString(text) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Finds the {@link SkinLookupException} inside a failed future's exception, if there is one. */
    public static String describe(Throwable error) {
        Throwable cause = error;
        while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
        return cause instanceof SkinLookupException ? cause.getMessage() : "Unexpected error: " + cause;
    }
}

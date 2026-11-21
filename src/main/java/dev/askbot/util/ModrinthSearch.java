package dev.askbot.util;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public final class ModrinthSearch {
	public record Result(String title, String slug, String description, long downloads, String pageUrl,
			String downloadUrl) {
	}

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(15))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();
	private static final Gson GSON = new Gson();

	private ModrinthSearch() {
	}

	public static CompletableFuture<List<Result>> search(String query, int limit) {
		return CompletableFuture.supplyAsync(() -> {
			List<Result> out = new ArrayList<>();
			try {
				String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
				String facets = URLEncoder.encode("[[\"project_type:mod\"],[\"versions:26.3\"],[\"categories:fabric\"]]",
						StandardCharsets.UTF_8);
				String url = "https://api.modrinth.com/v2/search?query=" + encoded + "&facets=" + facets
						+ "&limit=" + Math.max(1, Math.min(20, limit));
				HttpRequest req = HttpRequest.newBuilder(URI.create(url))
						.timeout(Duration.ofSeconds(20))
						.header("Accept", "application/json")
						.header("User-Agent", "AskBot/1.0.0 (Minecraft mod)")
						.GET()
						.build();
				HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
				if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
					throw new RuntimeException("Modrinth returned HTTP " + resp.statusCode());
				}
				JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
				JsonArray hits = json.getAsJsonArray("hits");
				if (hits == null) {
					return out;
				}
				for (JsonElement h : hits) {
					if (!h.isJsonObject()) {
						continue;
					}
					JsonObject hit = h.getAsJsonObject();
					String title = str(hit, "title");
					String slug = str(hit, "slug");
					String description = str(hit, "description");
					long downloads = hit.has("downloads") && hit.get("downloads").isJsonPrimitive()
							? hit.get("downloads").getAsLong()
							: 0;
					String pageUrl = "https://modrinth.com/mod/" + slug;
					String downloadUrl = fetchLatestDownload(slug);
					out.add(new Result(title, slug, description, downloads, pageUrl, downloadUrl));
				}
			} catch (Exception e) {
				throw new RuntimeException("Modrinth search failed: " + e.getMessage(), e);
			}
			return out;
		});
	}

	private static String fetchLatestDownload(String slug) {
		try {
			String url = "https://api.modrinth.com/v2/project/" + slug
					+ "/version?loaders=%5B%22fabric%22%5D&game_versions=%5B%2226.3%22%5D&limit=1";
			HttpRequest req = HttpRequest.newBuilder(URI.create(url))
					.timeout(Duration.ofSeconds(20))
					.header("Accept", "application/json")
					.header("User-Agent", "AskBot/1.0.0 (Minecraft mod)")
					.GET()
					.build();
			HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
				return null;
			}
			JsonArray versions = JsonParser.parseString(resp.body()).getAsJsonArray();
			if (versions == null || versions.isEmpty()) {
				return null;
			}
			JsonObject v = versions.get(0).getAsJsonObject();
			JsonArray files = v.getAsJsonArray("files");
			if (files == null || files.isEmpty()) {
				return null;
			}
			// prefer primary
			for (JsonElement f : files) {
				if (f.isJsonObject() && f.getAsJsonObject().has("primary")
						&& f.getAsJsonObject().get("primary").getAsBoolean()) {
					return str(f.getAsJsonObject(), "url");
				}
			}
			return str(files.get(0).getAsJsonObject(), "url");
		} catch (Exception e) {
			return null;
		}
	}

	private static String str(JsonObject o, String key) {
		if (o == null || !o.has(key) || o.get(key).isJsonNull()) {
			return null;
		}
		if (o.get(key).isJsonPrimitive()) {
			return o.get(key).getAsString();
		}
		return null;
	}
}

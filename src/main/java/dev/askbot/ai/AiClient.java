package dev.askbot.ai;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import dev.askbot.AskBotConfig;
import dev.askbot.ProviderConfig;
import dev.askbot.util.TextUtil;

/**
 * Small async client for three request dialects:
 * <ul>
 *     <li>OpenAI-compatible chat completions (OpenRouter, OpenAI, Groq,
 *     Together, DeepSeek, Ollama, LM Studio, vLLM, ...)</li>
 *     <li>Anthropic Messages</li>
 *     <li>Google Gemini generateContent</li>
 * </ul>
 * No Minecraft classes are referenced here, so the networking layer stays
 * testable and can run entirely off the server thread.
 */
public final class AiClient {

	@FunctionalInterface
	public interface CommandRunner {
		java.util.concurrent.CompletableFuture<String> run(String command);
	}
	private static final Gson GSON = new Gson();
	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(15))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();

	private AiClient() {
	}

	public static CompletableFuture<String> complete(AskBotConfig config, ProviderConfig provider,
			List<ChatMessage> history, String userMessage, String systemPrompt) {
		ProviderType type;
		try {
			type = ProviderType.from(provider.type);
		} catch (IllegalArgumentException e) {
			return CompletableFuture.failedFuture(new AiException(e.getMessage()));
		}

		if (provider.baseUrl == null || provider.baseUrl.isBlank()) {
			return CompletableFuture.failedFuture(
					new AiException("Provider '" + provider.id + "' has no baseUrl in config/askbot.json."));
		}
		if (provider.model == null || provider.model.isBlank()) {
			return CompletableFuture.failedFuture(
					new AiException("Provider '" + provider.id + "' has no model in config/askbot.json."));
		}

		String apiKey = TextUtil.expandEnv(provider.apiKey);
		if (provider.requiresApiKey && apiKey.isBlank()) {
			return CompletableFuture.failedFuture(new AiException(
					"Provider '" + provider.id + "' has no API key. Set "
							+ TextUtil.describeKeySource(provider.apiKey)
							+ " or edit config/askbot.json."));
		}

		JsonObject body = buildBody(type, config, provider, history, userMessage, systemPrompt);
		String url = buildUrl(type, provider);

		HttpRequest.Builder requestBuilder;
		try {
			requestBuilder = HttpRequest.newBuilder(URI.create(url))
					.timeout(Duration.ofSeconds(Math.max(5, config.timeoutSeconds)))
					.setHeader("Content-Type", "application/json")
					.setHeader("Accept", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8));
		} catch (IllegalArgumentException e) {
			return CompletableFuture.failedFuture(new AiException("Invalid provider URL '" + url + "': " + e.getMessage()));
		}

		if (!apiKey.isBlank()) {
			try {
				switch (type) {
					case ANTHROPIC -> requestBuilder.setHeader("x-api-key", apiKey);
					case GEMINI -> requestBuilder.setHeader("x-goog-api-key", apiKey);
					default -> requestBuilder.setHeader("Authorization", "Bearer " + apiKey);
				}
			} catch (IllegalArgumentException e) {
				return CompletableFuture.failedFuture(new AiException(
						"The API key for provider '" + provider.id + "' contains invalid characters."));
			}
		}
		if (type == ProviderType.ANTHROPIC) {
			requestBuilder.setHeader("anthropic-version", "2023-06-01");
		}

		if (provider.headers != null) {
			for (Map.Entry<String, String> header : provider.headers.entrySet()) {
				String name = header.getKey();
				String value = TextUtil.expandEnv(header.getValue());
				if (name == null || name.isBlank() || value.isBlank()) {
					continue;
				}
				try {
					requestBuilder.setHeader(name.trim(), value);
				} catch (IllegalArgumentException e) {
					return CompletableFuture.failedFuture(
							new AiException("Invalid HTTP header '" + name + "' for provider '" + provider.id + "'."));
				}
			}
		}

		HttpRequest request;
		try {
			request = requestBuilder.build();
		} catch (IllegalArgumentException e) {
			return CompletableFuture.failedFuture(new AiException("Could not build request: " + e.getMessage()));
		}

		return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
				.thenApply(response -> {
					try {
						return handleResponse(type, response);
					} catch (AiException e) {
						throw new CompletionException(e);
					}
				});
	}

	public static CompletableFuture<List<String>> listModels(AskBotConfig config, ProviderConfig provider) {
		ProviderType type;
		try {
			type = ProviderType.from(provider.type);
		} catch (IllegalArgumentException e) {
			return CompletableFuture.failedFuture(new AiException(e.getMessage()));
		}
		if (provider.baseUrl == null || provider.baseUrl.isBlank()) {
			return CompletableFuture.failedFuture(
					new AiException("Provider '" + provider.id + "' has no baseUrl in config/askbot.json."));
		}
		String apiKey = TextUtil.expandEnv(provider.apiKey);
		if (provider.requiresApiKey && apiKey.isBlank()) {
			return CompletableFuture.failedFuture(new AiException(
					"Provider '" + provider.id + "' has no API key. Set "
							+ TextUtil.describeKeySource(provider.apiKey)
							+ " or edit config/askbot.json."));
		}

		String base = provider.baseUrl;
		String url;
		if (base.endsWith("/models")) {
			url = base;
		} else {
			url = base + "/models";
		}

		HttpRequest.Builder requestBuilder;
		try {
			requestBuilder = HttpRequest.newBuilder(URI.create(url))
					.timeout(Duration.ofSeconds(Math.max(5, config.timeoutSeconds)))
					.setHeader("Accept", "application/json")
					.GET();
		} catch (IllegalArgumentException e) {
			return CompletableFuture.failedFuture(new AiException("Invalid provider URL '" + url + "': " + e.getMessage()));
		}

		if (!apiKey.isBlank()) {
			try {
				switch (type) {
					case ANTHROPIC -> requestBuilder.setHeader("x-api-key", apiKey);
					case GEMINI -> requestBuilder.setHeader("x-goog-api-key", apiKey);
					default -> requestBuilder.setHeader("Authorization", "Bearer " + apiKey);
				}
			} catch (IllegalArgumentException e) {
				return CompletableFuture.failedFuture(new AiException(
						"The API key for provider '" + provider.id + "' contains invalid characters."));
			}
		}
		if (type == ProviderType.ANTHROPIC) {
			requestBuilder.setHeader("anthropic-version", "2023-06-01");
		}

		if (provider.headers != null) {
			for (Map.Entry<String, String> header : provider.headers.entrySet()) {
				String name = header.getKey();
				String value = TextUtil.expandEnv(header.getValue());
				if (name == null || name.isBlank() || value.isBlank()) {
					continue;
				}
				try {
					requestBuilder.setHeader(name.trim(), value);
				} catch (IllegalArgumentException e) {
					return CompletableFuture.failedFuture(
							new AiException("Invalid HTTP header '" + name + "' for provider '" + provider.id + "'."));
				}
			}
		}

		HttpRequest request;
		try {
			request = requestBuilder.build();
		} catch (IllegalArgumentException e) {
			return CompletableFuture.failedFuture(new AiException("Could not build request: " + e.getMessage()));
		}

		return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
				.thenApply(response -> {
					int status = response.statusCode();
					String body = response.body() == null ? "" : response.body();
					if (status < 200 || status >= 300) {
						String detail = extractError(body);
						throw new CompletionException(new AiException("Provider returned HTTP " + status
								+ (detail == null || detail.isBlank() ? "." : ": " + detail)));
					}
					List<String> models = new ArrayList<>();
					try {
						JsonElement parsed = JsonParser.parseString(body);
						if (parsed.isJsonObject()) {
							JsonObject json = parsed.getAsJsonObject();
							JsonArray data = array(json, "data");
							if (data != null) {
								for (JsonElement el : data) {
									if (el.isJsonObject()) {
										String id = string(el.getAsJsonObject(), "id");
										if (id == null) {
											id = string(el.getAsJsonObject(), "name");
										}
										if (id != null && !id.isBlank()) {
											models.add(id);
										}
									}
								}
							}
							JsonArray modelsArr = array(json, "models");
							if (modelsArr != null) {
								for (JsonElement el : modelsArr) {
									if (el.isJsonObject()) {
										String name = string(el.getAsJsonObject(), "name");
										if (name == null) {
											name = string(el.getAsJsonObject(), "id");
										}
										if (name != null && !name.isBlank()) {
											if (name.startsWith("models/")) {
												name = name.substring("models/".length());
											}
											models.add(name);
										}
									}
								}
							}
						}
					} catch (JsonParseException ignored) {
					}
					if (models.isEmpty()) {
						throw new CompletionException(new AiException("Provider returned no models."));
					}
					return models;
				});
	}

	public static final int MAX_TOOL_ITERATIONS = 8;

	public static CompletableFuture<String> completeWithTools(AskBotConfig config, ProviderConfig provider,
			List<ChatMessage> history, String userMessage, String systemPrompt, CommandRunner runner) {
		ProviderType type;
		try {
			type = ProviderType.from(provider.type);
		} catch (IllegalArgumentException e) {
			return CompletableFuture.failedFuture(new AiException(e.getMessage()));
		}
		if (type != ProviderType.OPENAI) {
			return complete(config, provider, history, userMessage, systemPrompt);
		}
		List<JsonObject> messages = new ArrayList<>();
		if (notBlank(systemPrompt)) {
			JsonObject sys = new JsonObject();
			sys.addProperty("role", "system");
			sys.addProperty("content", systemPrompt);
			messages.add(sys);
		}
		for (ChatMessage chatMessage : history) {
			JsonObject m = new JsonObject();
			m.addProperty("role", chatMessage.role());
			m.addProperty("content", chatMessage.content());
			messages.add(m);
		}
		JsonObject user = new JsonObject();
		user.addProperty("role", "user");
		user.addProperty("content", userMessage);
		messages.add(user);

		return runToolLoop(config, provider, messages, runner, 0);
	}

	private static CompletableFuture<String> runToolLoop(AskBotConfig config, ProviderConfig provider,
			List<JsonObject> messages, CommandRunner runner, int iteration) {
		if (iteration >= MAX_TOOL_ITERATIONS) {
			return CompletableFuture.completedFuture("Stopped: too many command steps.");
		}
		JsonObject body = buildOpenAiToolBody(config, provider, messages);
		String url = buildUrl(ProviderType.OPENAI, provider);

		HttpRequest.Builder requestBuilder;
		try {
			requestBuilder = HttpRequest.newBuilder(URI.create(url))
					.timeout(Duration.ofSeconds(Math.max(5, config.timeoutSeconds)))
					.setHeader("Content-Type", "application/json")
					.setHeader("Accept", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8));
		} catch (IllegalArgumentException e) {
			return CompletableFuture.failedFuture(new AiException("Invalid provider URL '" + url + "': " + e.getMessage()));
		}

		String apiKey = TextUtil.expandEnv(provider.apiKey);
		if (!apiKey.isBlank()) {
			try {
				requestBuilder.setHeader("Authorization", "Bearer " + apiKey);
			} catch (IllegalArgumentException e) {
				return CompletableFuture.failedFuture(new AiException(
						"The API key for provider '" + provider.id + "' contains invalid characters."));
			}
		}
		if (provider.headers != null) {
			for (Map.Entry<String, String> header : provider.headers.entrySet()) {
				String name = header.getKey();
				String value = TextUtil.expandEnv(header.getValue());
				if (name == null || name.isBlank() || value.isBlank()) {
					continue;
				}
				try {
					requestBuilder.setHeader(name.trim(), value);
				} catch (IllegalArgumentException e) {
					return CompletableFuture.failedFuture(
							new AiException("Invalid HTTP header '" + name + "' for provider '" + provider.id + "'."));
				}
			}
		}

		HttpRequest request;
		try {
			request = requestBuilder.build();
		} catch (IllegalArgumentException e) {
			return CompletableFuture.failedFuture(new AiException("Could not build request: " + e.getMessage()));
		}

		return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
				.thenCompose(response -> {
					int status = response.statusCode();
					String bodyStr = response.body() == null ? "" : response.body();
					if (status < 200 || status >= 300) {
						String detail = extractError(bodyStr);
						return CompletableFuture.failedFuture(new AiException("Provider returned HTTP " + status
								+ (detail == null || detail.isBlank() ? "." : ": " + detail)));
					}
					JsonObject json;
					try {
						JsonElement parsed = JsonParser.parseString(bodyStr);
						if (!parsed.isJsonObject()) {
							throw new JsonParseException("response is not a JSON object");
						}
						json = parsed.getAsJsonObject();
					} catch (JsonParseException e) {
						return CompletableFuture.failedFuture(new AiException("Provider returned invalid JSON: " + TextUtil.oneLine(bodyStr, 200)));
					}
					if (json.has("error") && !json.get("error").isJsonNull()) {
						String detail = extractError(json);
						return CompletableFuture.failedFuture(new AiException(detail == null ? "The provider returned an error." : detail));
					}
					JsonArray choices = array(json, "choices");
					if (choices == null || choices.isEmpty()) {
						return CompletableFuture.failedFuture(new AiException("Provider response had no choices."));
					}
					JsonObject choice = choices.get(0).getAsJsonObject();
					JsonObject msg = choice.getAsJsonObject("message");
					messages.add(msg.deepCopy());

					JsonArray toolCalls = msg.has("tool_calls") && msg.get("tool_calls").isJsonArray()
							? msg.getAsJsonArray("tool_calls")
							: null;
					if (toolCalls == null || toolCalls.isEmpty()) {
						String content = contentToText(msg.get("content"));
						return CompletableFuture.completedFuture(content == null ? "" : content.trim());
					}

					CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
					for (JsonElement tcEl : toolCalls) {
						JsonObject tc = tcEl.getAsJsonObject();
						String toolId = tc.has("id") ? tc.get("id").getAsString() : "";
						JsonObject fn = tc.getAsJsonObject("function");
						String name = fn.get("name").getAsString();
						String args = fn.has("arguments") ? fn.get("arguments").getAsString() : "{}";
						String command = args;
						try {
							JsonObject a = JsonParser.parseString(args).getAsJsonObject();
							if (a.has("command")) {
								command = a.get("command").getAsString();
							}
						} catch (Exception ignored) {
						}
						final String cmd = command;
						final String tid = toolId;
						final String tname = name;
						chain = chain.thenCompose(v -> runner.run(cmd).handle((out, ex) -> {
							String result = (ex != null) ? ("Error: " + ex.getMessage())
									: (out == null ? "" : out);
							JsonObject toolMsg = new JsonObject();
							toolMsg.addProperty("role", "tool");
							toolMsg.addProperty("tool_call_id", tid);
							toolMsg.addProperty("name", tname);
							toolMsg.addProperty("content", result);
							messages.add(toolMsg);
							return null;
						}));
					}
					return chain.thenCompose(v -> runToolLoop(config, provider, messages, runner, iteration + 1));
				});
	}

	private static JsonObject buildOpenAiToolBody(AskBotConfig config, ProviderConfig provider, List<JsonObject> messages) {
		JsonObject root = new JsonObject();
		root.addProperty("model", provider.model);
		JsonArray msgs = new JsonArray();
		for (JsonObject m : messages) {
			msgs.add(m.deepCopy());
		}
		root.add("messages", msgs);
		root.addProperty("stream", false);

		Integer maxTokens = provider.maxTokens != null ? provider.maxTokens : config.maxTokens;
		String field = provider.maxTokensField == null ? "max_tokens" : provider.maxTokensField;
		if (maxTokens != null && maxTokens > 0 && !field.equalsIgnoreCase("none")) {
			root.addProperty(field, maxTokens);
		}
		Double temperature = provider.temperature != null ? provider.temperature : config.temperature;
		if (temperature != null) {
			root.addProperty("temperature", temperature);
		}

		if (config.allowCommands) {
			JsonArray tools = new JsonArray();
			JsonObject tool = new JsonObject();
			tool.addProperty("type", "function");
			JsonObject fn = new JsonObject();
			fn.addProperty("name", "run_minecraft_command");
			fn.addProperty("description", "Run a Minecraft server command without the leading slash and return its output.");
			JsonObject params = new JsonObject();
			params.addProperty("type", "object");
			JsonObject props = new JsonObject();
			JsonObject cmdProp = new JsonObject();
			cmdProp.addProperty("type", "string");
			cmdProp.addProperty("description", "The command to run, without leading slash.");
			props.add("command", cmdProp);
			params.add("properties", props);
			JsonArray req = new JsonArray();
			req.add("command");
			params.add("required", req);
			fn.add("parameters", params);
			tool.add("function", fn);
			tools.add(tool);
			root.add("tools", tools);
		}

		mergeExtra(root, provider.extraBody);
		return root;
	}

	private static String buildUrl(ProviderType type, ProviderConfig provider) {
		String base = provider.baseUrl;
		return switch (type) {
			case OPENAI -> base.endsWith("/chat/completions") ? base : base + "/chat/completions";
			case ANTHROPIC -> base.endsWith("/messages") ? base : base + "/messages";
			case GEMINI -> {
				String model = provider.model.startsWith("models/")
						? provider.model.substring("models/".length())
						: provider.model;
				yield base.contains(":generateContent")
						? base
						: base + "/models/" + model + ":generateContent";
			}
		};
	}

	private static JsonObject buildBody(ProviderType type, AskBotConfig config, ProviderConfig provider,
			List<ChatMessage> history, String userMessage, String systemPrompt) {
		JsonObject root = new JsonObject();
		Integer maxTokens = provider.maxTokens != null ? provider.maxTokens : config.maxTokens;
		Double temperature = provider.temperature != null ? provider.temperature : config.temperature;

		switch (type) {
			case OPENAI -> {
				root.addProperty("model", provider.model);
				JsonArray messages = new JsonArray();
				if (notBlank(systemPrompt)) {
					messages.add(message("system", systemPrompt));
				}
				for (ChatMessage chatMessage : history) {
					messages.add(message(chatMessage.role(), chatMessage.content()));
				}
				messages.add(message("user", userMessage));
				root.add("messages", messages);
				root.addProperty("stream", false);

				String maxTokensField = provider.maxTokensField == null ? "max_tokens" : provider.maxTokensField;
				if (maxTokens != null && maxTokens > 0 && !maxTokensField.equalsIgnoreCase("none")) {
					root.addProperty(maxTokensField, maxTokens);
				}
				if (temperature != null) {
					root.addProperty("temperature", temperature);
				}
			}
			case ANTHROPIC -> {
				root.addProperty("model", provider.model);
				root.addProperty("max_tokens", maxTokens != null && maxTokens > 0 ? maxTokens : 512);
				if (notBlank(systemPrompt)) {
					root.addProperty("system", systemPrompt);
				}
				if (temperature != null) {
					root.addProperty("temperature", temperature);
				}
				JsonArray messages = new JsonArray();
				for (ChatMessage chatMessage : history) {
					messages.add(message(chatMessage.role(), chatMessage.content()));
				}
				messages.add(message("user", userMessage));
				root.add("messages", messages);
			}
			case GEMINI -> {
				if (notBlank(systemPrompt)) {
					JsonObject systemInstruction = new JsonObject();
					systemInstruction.add("parts", textParts(systemPrompt));
					root.add("systemInstruction", systemInstruction);
				}
				JsonArray contents = new JsonArray();
				for (ChatMessage chatMessage : history) {
					JsonObject content = new JsonObject();
					content.addProperty("role", "assistant".equals(chatMessage.role()) ? "model" : "user");
					content.add("parts", textParts(chatMessage.content()));
					contents.add(content);
				}
				JsonObject userContent = new JsonObject();
				userContent.addProperty("role", "user");
				userContent.add("parts", textParts(userMessage));
				contents.add(userContent);
				root.add("contents", contents);

				JsonObject generationConfig = new JsonObject();
				if (maxTokens != null && maxTokens > 0) {
					generationConfig.addProperty("maxOutputTokens", maxTokens);
				}
				if (temperature != null) {
					generationConfig.addProperty("temperature", temperature);
				}
				if (!generationConfig.isEmpty()) {
					root.add("generationConfig", generationConfig);
				}
			}
		}

		if (config.internetAccess) {
			if (type == ProviderType.GEMINI && !root.has("tools")) {
				JsonArray tools = new JsonArray();
				JsonObject tool = new JsonObject();
				tool.add("google_search", new JsonObject());
				tools.add(tool);
				root.add("tools", tools);
			}
			if (type == ProviderType.OPENAI && provider.baseUrl != null
					&& provider.baseUrl.contains("openrouter") && !root.has("plugins")) {
				JsonArray plugins = new JsonArray();
				JsonObject plugin = new JsonObject();
				plugin.addProperty("id", "web");
				plugins.add(plugin);
				root.add("plugins", plugins);
			}
		}

		mergeExtra(root, provider.extraBody);
		return root;
	}

	private static JsonObject message(String role, String content) {
		JsonObject message = new JsonObject();
		message.addProperty("role", role);
		message.addProperty("content", content == null ? "" : content);
		return message;
	}

	private static JsonArray textParts(String text) {
		JsonArray parts = new JsonArray();
		JsonObject part = new JsonObject();
		part.addProperty("text", text == null ? "" : text);
		parts.add(part);
		return parts;
	}

	/** Deep-merges extraBody into the top level of the request. */
	private static void mergeExtra(JsonObject target, Map<String, Object> extraBody) {
		if (extraBody == null || extraBody.isEmpty()) {
			return;
		}
		JsonElement tree = GSON.toJsonTree(extraBody);
		if (!tree.isJsonObject()) {
			return;
		}
		for (Map.Entry<String, JsonElement> entry : tree.getAsJsonObject().entrySet()) {
			JsonElement existing = target.get(entry.getKey());
			if (existing != null && existing.isJsonObject() && entry.getValue().isJsonObject()) {
				JsonObject merged = existing.getAsJsonObject().deepCopy();
				for (Map.Entry<String, JsonElement> nested : entry.getValue().getAsJsonObject().entrySet()) {
					merged.add(nested.getKey(), nested.getValue());
				}
				target.add(entry.getKey(), merged);
			} else {
				target.add(entry.getKey(), entry.getValue());
			}
		}
	}

	private static String handleResponse(ProviderType type, HttpResponse<String> response) throws AiException {
		String body = response.body() == null ? "" : response.body();
		int status = response.statusCode();
		if (status < 200 || status >= 300) {
			String detail = extractError(body);
			throw new AiException("Provider returned HTTP " + status
					+ (detail == null || detail.isBlank() ? "." : ": " + detail));
		}

		JsonObject json;
		try {
			JsonElement parsed = JsonParser.parseString(body);
			if (!parsed.isJsonObject()) {
				throw new JsonParseException("response is not a JSON object");
			}
			json = parsed.getAsJsonObject();
		} catch (JsonParseException e) {
			throw new AiException("Provider returned invalid JSON: " + TextUtil.oneLine(body, 200));
		}

		if (json.has("error") && !json.get("error").isJsonNull()) {
			String detail = extractError(json);
			throw new AiException(detail == null ? "The provider returned an error." : detail);
		}

		String answer = switch (type) {
			case OPENAI -> parseOpenAi(json);
			case ANTHROPIC -> parseAnthropic(json);
			case GEMINI -> parseGemini(json);
		};
		if (answer == null || answer.isBlank()) {
			throw new AiException("The provider returned an empty response.");
		}
		return answer.trim();
	}

	private static String parseOpenAi(JsonObject json) throws AiException {
		JsonArray choices = array(json, "choices");
		if (choices == null || choices.isEmpty()) {
			throw new AiException("Provider response had no choices.");
		}
		JsonElement first = choices.get(0);
		if (!first.isJsonObject()) {
			throw new AiException("Provider response had an invalid choice.");
		}
		JsonObject choice = first.getAsJsonObject();

		JsonElement message = choice.get("message");
		if (message != null && message.isJsonObject()) {
			JsonObject messageObject = message.getAsJsonObject();
			String content = contentToText(messageObject.get("content"));
			if (notBlank(content)) {
				return content;
			}
			String refusal = string(messageObject, "refusal");
			if (notBlank(refusal)) {
				throw new AiException("The model refused to answer: " + refusal);
			}
		}

		String legacyText = contentToText(choice.get("text"));
		if (notBlank(legacyText)) {
			return legacyText;
		}
		throw new AiException("Provider response did not contain any text.");
	}

	private static String parseAnthropic(JsonObject json) throws AiException {
		JsonArray content = array(json, "content");
		if (content == null || content.isEmpty()) {
			String stopReason = string(json, "stop_reason");
			throw new AiException(stopReason == null
					? "Anthropic response had no content."
					: "Anthropic returned no text (stop_reason: " + stopReason + ").");
		}
		StringBuilder answer = new StringBuilder();
		for (JsonElement block : content) {
			if (block.isJsonObject()) {
				String text = contentToText(block.getAsJsonObject().get("text"));
				if (notBlank(text)) {
					answer.append(text);
				}
			}
		}
		return answer.toString();
	}

	private static String parseGemini(JsonObject json) throws AiException {
		JsonArray candidates = array(json, "candidates");
		if (candidates == null || candidates.isEmpty()) {
			JsonElement feedback = json.get("promptFeedback");
			if (feedback != null && feedback.isJsonObject()) {
				String blockReason = string(feedback.getAsJsonObject(), "blockReason");
				if (notBlank(blockReason)) {
					throw new AiException("Gemini blocked the request (" + blockReason + ").");
				}
			}
			throw new AiException("Gemini response had no candidates.");
		}
		JsonElement first = candidates.get(0);
		if (!first.isJsonObject()) {
			throw new AiException("Gemini response had an invalid candidate.");
		}
		JsonObject candidate = first.getAsJsonObject();
		JsonElement content = candidate.get("content");
		if (content != null && content.isJsonObject()) {
			JsonArray parts = array(content.getAsJsonObject(), "parts");
			if (parts != null) {
				StringBuilder answer = new StringBuilder();
				for (JsonElement part : parts) {
					if (part.isJsonObject()) {
						String text = contentToText(part.getAsJsonObject().get("text"));
						if (notBlank(text)) {
							answer.append(text);
						}
					}
				}
				if (!answer.isEmpty()) {
					return answer.toString();
				}
			}
		}
		String finishReason = string(candidate, "finishReason");
		throw new AiException(finishReason == null
				? "Gemini returned no text."
				: "Gemini returned no text (finishReason: " + finishReason + ").");
	}

	/** Handles both string content and the array-of-parts content some providers use. */
	private static String contentToText(JsonElement element) {
		if (element == null || element.isJsonNull()) {
			return null;
		}
		if (element.isJsonPrimitive()) {
			return element.getAsString();
		}
		if (element.isJsonArray()) {
			StringBuilder text = new StringBuilder();
			for (JsonElement part : element.getAsJsonArray()) {
				String piece = contentToText(part);
				if (piece != null) {
					text.append(piece);
				}
			}
			return text.toString();
		}
		if (element.isJsonObject()) {
			JsonObject object = element.getAsJsonObject();
			String direct = string(object, "text");
			if (direct != null) {
				return direct;
			}
			return contentToText(object.get("content"));
		}
		return null;
	}

	/** Extracts a useful provider error message from a JSON or plain-text body. */
	private static String extractError(String body) {
		if (body == null || body.isBlank()) {
			return null;
		}
		try {
			JsonElement parsed = JsonParser.parseString(body);
			if (parsed.isJsonObject()) {
				return extractError(parsed.getAsJsonObject());
			}
		} catch (JsonParseException ignored) {
			// Plain text or HTML error page.
		}
		return TextUtil.oneLine(body, 300);
	}

	private static String extractError(JsonObject json) {
		JsonElement error = json.get("error");
		if (error != null && !error.isJsonNull()) {
			if (error.isJsonObject()) {
				String message = string(error.getAsJsonObject(), "message");
				if (notBlank(message)) {
					return message;
				}
			} else if (error.isJsonPrimitive()) {
				return error.getAsString();
			}
		}
		String message = string(json, "message");
		return notBlank(message) ? message : null;
	}

	private static JsonArray array(JsonObject object, String name) {
		JsonElement element = object.get(name);
		return element != null && element.isJsonArray() ? element.getAsJsonArray() : null;
	}

	private static String string(JsonObject object, String name) {
		JsonElement element = object.get(name);
		return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
	}

	private static boolean notBlank(String value) {
		return value != null && !value.isBlank();
	}
}

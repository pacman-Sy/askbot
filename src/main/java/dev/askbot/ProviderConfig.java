package dev.askbot;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * One configurable AI endpoint. A provider is deliberately just data so that
 * users can add OpenRouter, OpenAI, Anthropic, Gemini, Ollama, LM Studio,
 * vLLM, Groq, Together, DeepSeek or any other OpenAI-compatible service
 * without touching code.
 */
public final class ProviderConfig {
	public String id = "";
	/**
	 * Request/response dialect: "openai" (OpenAI-compatible), "anthropic" or
	 * "gemini".
	 */
	public String type = "openai";
	public boolean enabled = true;
	/** API root, for example https://openrouter.ai/api/v1 */
	public String baseUrl = "";
	/** Literal key or ${ENVIRONMENT_VARIABLE}. */
	public String apiKey = "";
	public String model = "";
	/** Set to false for keyless local endpoints such as Ollama. */
	public boolean requiresApiKey = true;
	/** Usually "max_tokens"; some OpenAI reasoning models need "max_completion_tokens". */
	public String maxTokensField = "max_tokens";
	/** Per-provider override. Null inherits the global value. */
	public Integer maxTokens;
	/** Per-provider override. Null inherits the global value. */
	public Double temperature;
	/** Extra HTTP headers, for example OpenRouter's HTTP-Referer. */
	public Map<String, String> headers = new LinkedHashMap<>();
	/** Extra JSON merged into the top level of the request body. */
	public Map<String, Object> extraBody = new LinkedHashMap<>();

	public ProviderConfig normalize() {
		id = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
		type = type == null ? "openai" : type.trim().toLowerCase(Locale.ROOT);
		baseUrl = baseUrl == null ? "" : baseUrl.trim();
		while (baseUrl.endsWith("/")) {
			baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
		}
		apiKey = apiKey == null ? "" : apiKey.trim();
		model = model == null ? "" : model.trim();
		maxTokensField = maxTokensField == null ? "max_tokens" : maxTokensField.trim();
		if (maxTokensField.isEmpty()) {
			maxTokensField = "max_tokens";
		}
		headers = headers == null ? new LinkedHashMap<>() : headers;
		extraBody = extraBody == null ? new LinkedHashMap<>() : extraBody;
		return this;
	}
}

package dev.askbot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public final class AskBotConfig {
	public static final Gson GSON = new GsonBuilder()
			.setPrettyPrinting()
			.disableHtmlEscaping()
			.create();

	public static final String DEFAULT_SYSTEM_PROMPT = """
			You are AskBot, a helpful assistant inside a Minecraft Java Edition server.
			Answer in plain text and keep it concise: usually 1-4 sentences and at most about 120 words.
			Do not use Markdown, code fences, tables or emoji, because the answer is displayed in Minecraft chat.
			If you are unsure, say so. Never claim to have performed actions in the game; you can only give advice.
			""".strip();

	public String defaultProvider = "openrouter";
	public String systemPrompt = DEFAULT_SYSTEM_PROMPT;
	public int maxTokens = 512;
	public double temperature = 0.7;
	public int timeoutSeconds = 60;
	public int maxQuestionLength = 1000;
	public int maxResponseCharacters = 4000;
	public int cooldownSeconds = 5;
	public int cooldownBypassPermissionLevel = 2;
	public int maxConcurrentRequests = 4;
	public int permissionLevel = 0;
	public int adminPermissionLevel = 3;
	public boolean broadcast = false;
	public boolean showThinking = true;
	public boolean includePlayerContext = true;
	public boolean includeCoordinates = false;
	public int historyMessages = 6;
	public boolean internetAccess = false;
	public boolean allowCommands = false;
	public String prefix = "[AskBot] ";
	public List<ProviderConfig> providers = defaultProviders();

	public static AskBotConfig defaults() {
		return new AskBotConfig();
	}

	public void normalize() {
		if (defaultProvider == null) {
			defaultProvider = "";
		}
		defaultProvider = defaultProvider.trim();

		if (systemPrompt == null) {
			systemPrompt = DEFAULT_SYSTEM_PROMPT;
		}
		if (prefix == null) {
			prefix = "[AskBot] ";
		}

		maxTokens = clamp(maxTokens, 1, 32768);
		if (!Double.isFinite(temperature)) {
			temperature = 0.7;
		}
		temperature = Math.max(0.0, Math.min(2.0, temperature));
		timeoutSeconds = clamp(timeoutSeconds, 5, 300);
		maxQuestionLength = clamp(maxQuestionLength, 1, 4000);
		maxResponseCharacters = clamp(maxResponseCharacters, 100, 20000);
		cooldownSeconds = clamp(cooldownSeconds, 0, 3600);
		cooldownBypassPermissionLevel = clamp(cooldownBypassPermissionLevel, 0, 4);
		maxConcurrentRequests = clamp(maxConcurrentRequests, 1, 32);
		permissionLevel = clamp(permissionLevel, 0, 4);
		adminPermissionLevel = clamp(adminPermissionLevel, 0, 4);
		historyMessages = clamp(historyMessages, 0, 50);

		if (providers == null) {
			providers = defaultProviders();
		}

		List<ProviderConfig> normalized = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		for (ProviderConfig provider : providers) {
			if (provider == null) {
				continue;
			}
			provider.normalize();
			if (provider.maxTokens != null) {
				provider.maxTokens = clamp(provider.maxTokens, 1, 32768);
			}
			if (provider.temperature != null) {
				if (!Double.isFinite(provider.temperature)) {
					provider.temperature = null;
				} else {
					provider.temperature = Math.max(0.0, Math.min(2.0, provider.temperature));
				}
			}
			if (provider.id.isEmpty() || !seen.add(provider.id)) {
				continue;
			}
			normalized.add(provider);
		}
		providers = normalized;

		if (defaultProvider.isEmpty()) {
			ProviderConfig first = firstEnabled();
			defaultProvider = first == null ? "" : first.id;
		}
	}

	public ProviderConfig findProvider(String id) {
		if (id == null) {
			return null;
		}
		String wanted = id.trim().toLowerCase(Locale.ROOT);
		for (ProviderConfig provider : providers) {
			if (provider.id.equals(wanted)) {
				return provider;
			}
		}
		return null;
	}

	public ProviderConfig firstEnabled() {
		for (ProviderConfig provider : providers) {
			if (provider.enabled) {
				return provider;
			}
		}
		return null;
	}

	public List<String> enabledProviderIds() {
		List<String> ids = new ArrayList<>();
		for (ProviderConfig provider : providers) {
			if (provider.enabled) {
				ids.add(provider.id);
			}
		}
		return ids;
	}

	public void save(Path path) throws IOException {
		Path parent = path.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		String json = GSON.toJson(this) + System.lineSeparator();
		Path temp = path.resolveSibling(path.getFileName() + ".tmp");
		Files.writeString(temp, json, StandardCharsets.UTF_8);
		try {
			Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException atomicMoveFailed) {
			Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	public static List<ProviderConfig> defaultProviders() {
		List<ProviderConfig> list = new ArrayList<>();
		list.add(provider("openrouter", "openai", "https://openrouter.ai/api/v1",
				"${OPENROUTER_API_KEY}", "openrouter/auto", true));
		list.add(provider("openai", "openai", "https://api.openai.com/v1",
				"${OPENAI_API_KEY}", "gpt-4.1-mini", true));
		list.add(provider("anthropic", "anthropic", "https://api.anthropic.com/v1",
				"${ANTHROPIC_API_KEY}", "claude-sonnet-4-5", true));
		list.add(provider("gemini", "gemini", "https://generativelanguage.googleapis.com/v1beta",
				"${GEMINI_API_KEY}", "gemini-2.5-flash", true));

		ProviderConfig ollama = provider("ollama", "openai", "http://localhost:11434/v1",
				"", "llama3.2", true);
		ollama.requiresApiKey = false;
		list.add(ollama);

		ProviderConfig custom = provider("custom", "openai", "https://api.example.com/v1",
				"${CUSTOM_API_KEY}", "your-model", false);
		custom.enabled = false;
		list.add(custom);

		ProviderConfig openrouter = list.get(0);
		openrouter.headers.put("X-Title", "AskBot");

		return list;
	}

	private static ProviderConfig provider(String id, String type, String baseUrl, String apiKey,
			String model, boolean enabled) {
		ProviderConfig provider = new ProviderConfig();
		provider.id = id;
		provider.type = type;
		provider.baseUrl = baseUrl;
		provider.apiKey = apiKey;
		provider.model = model;
		provider.enabled = enabled;
		provider.normalize();
		return provider;
	}
}

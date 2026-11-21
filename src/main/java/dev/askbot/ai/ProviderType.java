package dev.askbot.ai;

import java.util.Locale;

public enum ProviderType {
	OPENAI,
	ANTHROPIC,
	GEMINI;

	public static ProviderType from(String raw) {
		if (raw == null || raw.isBlank()) {
			return OPENAI;
		}
		return switch (raw.trim().toLowerCase(Locale.ROOT)) {
			case "openai", "openai-compatible", "openrouter", "ollama", "lmstudio", "custom" -> OPENAI;
			case "anthropic", "claude" -> ANTHROPIC;
			case "gemini", "google", "google-gemini" -> GEMINI;
			default -> throw new IllegalArgumentException(
					"Unknown provider type '" + raw + "'. Use \"openai\", \"anthropic\" or \"gemini\".");
		};
	}
}

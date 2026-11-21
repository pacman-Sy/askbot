package dev.askbot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.askbot.command.AskBotCommand;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;

public final class AskBot implements ModInitializer {
	public static final String MOD_ID = "askbot";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static volatile AskBotConfig config = AskBotConfig.defaults();
	private static volatile Path configPath;

	@Override
	public void onInitialize() {
		configPath = FabricLoader.getInstance().getConfigDir().resolve("askbot.json");
		String error = reloadConfig();
		if (error != null) {
			LOGGER.warn("Using built-in AskBot defaults because the config could not be loaded: {}", error);
		}

		CommandRegistrationCallback.EVENT.register(
				(dispatcher, registryAccess, environment) -> AskBotCommand.register(dispatcher));

		ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			String reloadError = reloadConfig();
			if (reloadError != null) {
				LOGGER.warn("AskBot config reload failed on server start: {}", reloadError);
			}
			AskBotConfig current = config;
			LOGGER.info("AskBot ready: default provider '{}', {} provider(s) configured.",
					current.defaultProvider, current.providers.size());
		});

		ServerLifecycleEvents.SERVER_STOPPED.register(server -> AskBotCommand.clearAll());
	}

	public static AskBotConfig getConfig() {
		return config;
	}

	public static Path getConfigPath() {
		return configPath;
	}

	/**
	 * Re-reads config/askbot.json. On failure the previous config is kept.
	 *
	 * @return null on success, otherwise a human-readable error
	 */
	public static synchronized String reloadConfig() {
		Path path = configPath;
		if (path == null) {
			return "config path is not initialized";
		}

		try {
			if (!Files.exists(path)) {
				AskBotConfig defaults = AskBotConfig.defaults();
				defaults.normalize();
				try {
					defaults.save(path);
					LOGGER.info("Created default AskBot config at {}", path);
				} catch (IOException e) {
					LOGGER.warn("Could not write default AskBot config to {}: {}", path, e.getMessage());
				}
				config = defaults;
				return null;
			}

			String json = Files.readString(path, StandardCharsets.UTF_8);
			if (json.isBlank()) {
				return "config/askbot.json is empty";
			}
			AskBotConfig loaded = AskBotConfig.GSON.fromJson(json, AskBotConfig.class);
			if (loaded == null) {
				return "config/askbot.json did not contain a JSON object";
			}
			loaded.normalize();
			config = loaded;
			return null;
		} catch (Exception e) {
			LOGGER.error("Failed to load AskBot config from {}", path, e);
			return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
		}
	}

	/** Saves the current config. Returns null on success, otherwise an error. */
	public static synchronized String saveConfig() {
		Path path = configPath;
		if (path == null) {
			return "config path is not initialized";
		}
		try {
			config.save(path);
			return null;
		} catch (IOException e) {
			LOGGER.error("Failed to save AskBot config to {}", path, e);
			return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
		}
	}
}

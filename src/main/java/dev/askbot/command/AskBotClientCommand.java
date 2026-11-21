package dev.askbot.command;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import dev.askbot.AskBot;
import dev.askbot.AskBotConfig;
import dev.askbot.ProviderConfig;
import dev.askbot.ai.AiClient;
import dev.askbot.ai.AiException;
import dev.askbot.ai.ChatMessage;
import dev.askbot.util.ModrinthSearch;
import dev.askbot.util.TextUtil;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public final class AskBotClientCommand {
	private static final UUID LOCAL_ID = new UUID(0L, 1L);
	private static final int MAX_CHAT_CHUNK = 900;

	private static final Map<UUID, Long> COOLDOWNS = new ConcurrentHashMap<>();
	private static final Map<UUID, Deque<ChatMessage>> HISTORIES = new ConcurrentHashMap<>();
	private static final AtomicInteger IN_FLIGHT = new AtomicInteger();

	private AskBotClientCommand() {
	}

	public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		dispatcher.register(ClientCommands.literal("askbot")
				.executes(AskBotClientCommand::help)
				.then(ClientCommands.literal("help")
						.executes(AskBotClientCommand::help))
				.then(ClientCommands.literal("clear")
						.executes(AskBotClientCommand::clear))
				.then(ClientCommands.literal("status")
						.executes(AskBotClientCommand::status))
				.then(ClientCommands.literal("reload")
						.executes(AskBotClientCommand::reload))
				.then(ClientCommands.literal("provider")
						.executes(context -> {
							info(context.getSource(), "Usage: /askbot provider <id>");
							return 0;
						})
						.then(ClientCommands.argument("id", StringArgumentType.word())
								.executes(AskBotClientCommand::provider)))
		.then(ClientCommands.literal("key")
				.executes(context -> {
					info(context.getSource(), "Usage: /askbot key <provider> <key>");
					return 0;
				})
				.then(ClientCommands.argument("provider", StringArgumentType.word())
						.then(ClientCommands.argument("key", StringArgumentType.greedyString())
								.executes(AskBotClientCommand::setKey))))
		.then(ClientCommands.literal("model")
				.executes(context -> {
					info(context.getSource(), "Usage: /askbot model <model>  or  /askbot model <provider> <model>");
					return 0;
				})
				.then(ClientCommands.argument("value", StringArgumentType.greedyString())
						.executes(AskBotClientCommand::setModel)))
		.then(ClientCommands.literal("internet")
				.executes(context -> {
					info(context.getSource(), "Usage: /askbot internet <on|off>");
					return 0;
				})
				.then(ClientCommands.argument("value", StringArgumentType.word())
						.executes(AskBotClientCommand::setInternet)))
		.then(ClientCommands.literal("commands")
				.executes(context -> {
					info(context.getSource(), "Usage: /askbot commands <on|off>");
					return 0;
				})
				.then(ClientCommands.argument("value", StringArgumentType.word())
						.executes(AskBotClientCommand::setCommands)))
		.then(ClientCommands.literal("mod")
				.executes(context -> {
					info(context.getSource(), "Usage: /askbot mod <search query>");
					return 0;
				})
				.then(ClientCommands.argument("query", StringArgumentType.greedyString())
						.executes(AskBotClientCommand::findMod)))
		.then(ClientCommands.literal("models")
				.executes(AskBotClientCommand::models)
				.then(ClientCommands.argument("provider", StringArgumentType.word())
						.executes(AskBotClientCommand::modelsFor)))
				.then(ClientCommands.argument("question", StringArgumentType.greedyString())
						.executes(AskBotClientCommand::ask)));
	}

	private static int ask(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		AskBotConfig config = AskBot.getConfig();
		Minecraft client = source.getClient();
		if (client.player == null) {
			error(source, "You must be in a world to use AskBot.");
			return 0;
		}
		UUID historyId = client.player.getUUID();

		String question = TextUtil.stripQuotes(StringArgumentType.getString(context, "question"));
		if (question.isBlank()) {
			info(source, "Usage: /askbot <question>");
			return 0;
		}
		if (question.length() > config.maxQuestionLength) {
			error(source, "That question is too long (" + question.length() + " characters, maximum is "
					+ config.maxQuestionLength + ").");
			return 0;
		}

		ProviderConfig provider = config.findProvider(config.defaultProvider);
		if (provider == null) {
			error(source, "Provider '" + config.defaultProvider
					+ "' is not configured. Check config/askbot.json.");
			return 0;
		}
		if (!provider.enabled) {
			error(source, "Provider '" + provider.id + "' is disabled in config/askbot.json.");
			return 0;
		}

		String apiKey = TextUtil.expandEnv(provider.apiKey);
		if (provider.requiresApiKey && apiKey.isBlank()) {
			error(source, "Provider '" + provider.id + "' has no API key. Set "
					+ TextUtil.describeKeySource(provider.apiKey) + " or edit config/askbot.json.");
			return 0;
		}

		boolean useCooldown = config.cooldownSeconds > 0;
		long requestStarted = System.currentTimeMillis();
		if (useCooldown) {
			Long last = COOLDOWNS.get(historyId);
			if (last != null) {
				long remainingMs = config.cooldownSeconds * 1000L - (requestStarted - last);
				if (remainingMs > 0) {
					long seconds = (remainingMs + 999L) / 1000L;
					error(source, "Please wait " + seconds + " more second" + (seconds == 1 ? "" : "s")
							+ " before asking again.");
					return 0;
				}
			}
		}

		if (IN_FLIGHT.incrementAndGet() > config.maxConcurrentRequests) {
			IN_FLIGHT.decrementAndGet();
			error(source, "AskBot is handling too many questions right now. Try again in a moment.");
			return 0;
		}
		if (useCooldown) {
			COOLDOWNS.put(historyId, requestStarted);
		}

		String playerName = client.player.getName().getString();
		String systemPrompt = buildSystemPrompt(config, client, playerName);
		List<ChatMessage> history = snapshotHistory(historyId, config.historyMessages);

		if (config.showThinking) {
			source.sendFeedback(Component.literal("[AskBot] Thinking... (" + provider.id + " / "
					+ provider.model + ")").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
		}

		CompletableFuture<String> request;
		try {
			request = AiClient.complete(config, provider, history, question, systemPrompt);
		} catch (RuntimeException e) {
			IN_FLIGHT.decrementAndGet();
			error(source, "Could not start the AI request: " + AiException.describe(e));
			AskBot.LOGGER.warn("AskBot could not build a request (provider={}, model={}): {}",
					provider.id, provider.model, e.toString());
			return 0;
		}

		request.whenComplete((answer, throwable) -> {
			IN_FLIGHT.decrementAndGet();
			Runnable delivery = () -> {
				try {
					if (throwable != null) {
						String message = AiException.describe(throwable);
						error(source, message);
						AskBot.LOGGER.warn("AskBot request failed (provider={}, model={}): {}",
								provider.id, provider.model, message);
						return;
					}
					String text = answer == null ? "" : answer.trim();
					if (text.isEmpty()) {
						error(source, "The AI returned an empty response.");
						return;
					}
					addTurn(historyId, question, TextUtil.truncate(text, config.maxResponseCharacters),
							config.historyMessages);
					deliver(config, source, client, playerName, question, text);
				} catch (RuntimeException e) {
					AskBot.LOGGER.warn("AskBot could not deliver a reply: {}", e.toString());
				}
			};

			try {
				client.execute(delivery);
			} catch (RuntimeException e) {
				AskBot.LOGGER.debug("Client stopped before the AskBot reply could be delivered.");
			}
		});

		return 1;
	}

	private static int clear(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		Minecraft client = source.getClient();
		if (client.player == null) {
			error(source, "You must be in a world to use AskBot.");
			return 0;
		}
		HISTORIES.remove(client.player.getUUID());
		info(source, "Your AskBot conversation history has been cleared.");
		return 1;
	}

	private static int status(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		AskBotConfig config = AskBot.getConfig();
		ProviderConfig provider = config.findProvider(config.defaultProvider);
		info(source, "Status");
		info(source, "Default provider: " + config.defaultProvider
				+ (provider == null ? "" : " | type: " + provider.type + " | model: " + provider.model));
		if (provider != null) {
			info(source, "Enabled: " + provider.enabled + " | requires API key: " + provider.requiresApiKey
					+ " | API key: " + (TextUtil.expandEnv(provider.apiKey).isBlank() ? "not set" : "set"));
			info(source, "Configured providers: " + String.join(", ", config.enabledProviderIds()));
		}
		info(source, "maxTokens=" + config.maxTokens + " | temperature=" + config.temperature
				+ " | timeout=" + config.timeoutSeconds + "s");
		info(source, "cooldown=" + config.cooldownSeconds + "s | history=" + config.historyMessages
				+ " messages | broadcast=" + config.broadcast + " | internet=" + config.internetAccess
				+ " | commands=" + config.allowCommands);
		Minecraft client = source.getClient();
		if (client.player != null) {
			Deque<ChatMessage> history = HISTORIES.get(client.player.getUUID());
			info(source, "Your stored history: " + (history == null ? 0 : history.size()) + " message(s)");
		}
		return 1;
	}

	private static int reload(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		try {
			String error = AskBot.reloadConfig();
			if (error != null) {
				error(source, "Could not reload config/askbot.json: " + error);
				return 0;
			}
			info(source, "Configuration reloaded.");
		} catch (Exception e) {
			error(source, "Could not reload config/askbot.json: " + e.getMessage());
			AskBot.LOGGER.warn("AskBot could not reload config: {}", e.toString());
		}
		return 1;
	}

	private static int setKey(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		AskBotConfig config = AskBot.getConfig();
		String id = StringArgumentType.getString(context, "provider");
		String key = StringArgumentType.getString(context, "key").trim();
		ProviderConfig provider = config.findProvider(id);
		if (provider == null) {
			error(source, "Unknown provider '" + id + "'. Configured: " + String.join(", ", config.enabledProviderIds()));
			return 0;
		}
		provider.apiKey = key;
		provider.normalize();
		try {
			config.save(AskBot.getConfigPath());
		} catch (Exception e) {
			error(source, "Updated key for '" + provider.id + "' but could not save config: " + e.getMessage());
			return 0;
		}
		info(source, "API key updated for provider '" + provider.id + "' (not shown).");
		return 1;
	}

	private static int setModel(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		AskBotConfig config = AskBot.getConfig();
		String value = StringArgumentType.getString(context, "value").trim();
		if (value.isBlank()) {
			info(source, "Usage: /askbot model <model>  or  /askbot model <provider> <model>");
			return 0;
		}
		String[] parts = value.split("\\s+", 2);
		ProviderConfig provider;
		String model;
		if (parts.length == 2 && config.findProvider(parts[0]) != null) {
			provider = config.findProvider(parts[0]);
			model = parts[1].trim();
		} else {
			provider = config.findProvider(config.defaultProvider);
			model = value;
		}
		if (provider == null) {
			error(source, "No provider selected. Use /askbot provider <id> first.");
			return 0;
		}
		if (model.isBlank()) {
			info(source, "Usage: /askbot model <model>  or  /askbot model <provider> <model>");
			return 0;
		}
		provider.model = model;
		provider.normalize();
		try {
			config.save(AskBot.getConfigPath());
		} catch (Exception e) {
			error(source, "Updated model for '" + provider.id + "' but could not save config: " + e.getMessage());
			return 0;
		}
		info(source, "Model set to '" + provider.model + "' for provider '" + provider.id + "'.");
		return 1;
	}

	private static int models(CommandContext<FabricClientCommandSource> context) {
		return listModels(context, null);
	}

	private static int modelsFor(CommandContext<FabricClientCommandSource> context) {
		return listModels(context, StringArgumentType.getString(context, "provider"));
	}

	private static int listModels(CommandContext<FabricClientCommandSource> context, String providerId) {
		FabricClientCommandSource source = context.getSource();
		AskBotConfig config = AskBot.getConfig();
		Minecraft client = source.getClient();
		String id = (providerId == null || providerId.isBlank()) ? config.defaultProvider : providerId.trim();
		ProviderConfig provider = config.findProvider(id);
		if (provider == null) {
			error(source, "Unknown provider '" + id + "'.");
			return 0;
		}
		if (!provider.enabled) {
			error(source, "Provider '" + provider.id + "' is disabled.");
			return 0;
		}
		info(source, "Fetching models for '" + provider.id + "'...");
		AiClient.listModels(config, provider).whenComplete((list, throwable) -> {
			Runnable delivery = () -> {
				if (throwable != null) {
					error(source, AiException.describe(throwable));
					return;
				}
				info(source, list.size() + " model(s) available for '" + provider.id + "':");
				int shown = 0;
				for (String name : list) {
					if (shown >= 40) {
						break;
					}
					info(source, "  " + name);
					shown++;
				}
				if (list.size() > shown) {
					info(source, "  ... and " + (list.size() - shown) + " more");
				}
				info(source, "Use /askbot model " + provider.id + " <model> to choose one.");
			};
			try {
				client.execute(delivery);
			} catch (RuntimeException e) {
				AskBot.LOGGER.debug("Client stopped before model list could be delivered.");
			}
		});
		return 1;
	}

	private static int setInternet(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		AskBotConfig config = AskBot.getConfig();
		String value = StringArgumentType.getString(context, "value").trim().toLowerCase();
		boolean enabled;
		if (value.equals("on") || value.equals("true") || value.equals("yes")) {
			enabled = true;
		} else if (value.equals("off") || value.equals("false") || value.equals("no")) {
			enabled = false;
		} else {
			error(source, "Usage: /askbot internet <on|off>");
			return 0;
		}
		config.internetAccess = enabled;
		try {
			config.save(AskBot.getConfigPath());
		} catch (Exception e) {
			error(source, "Updated setting but could not save config: " + e.getMessage());
			return 0;
		}
		info(source, "Internet access " + (enabled ? "enabled" : "disabled") + ".");
		return 1;
	}

	private static int findMod(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		AskBotConfig config = AskBot.getConfig();
		Minecraft client = source.getClient();
		String query = StringArgumentType.getString(context, "query").trim();
		if (query.isBlank()) {
			info(source, "Usage: /askbot mod <search query>");
			return 0;
		}
		info(source, "Searching Modrinth for \"" + query + "\"...");
		ModrinthSearch.search(query, 6).whenComplete((results, throwable) -> {
			Runnable delivery = () -> {
				if (throwable != null) {
					error(source, "Mod search failed: " + throwable.getMessage());
					return;
				}
				if (results.isEmpty()) {
					info(source, "No mods found for \"" + query + "\".");
					return;
				}
				info(source, results.size() + " mod(s) found:");
				for (ModrinthSearch.Result r : results) {
					info(source, "  " + r.title() + " (" + r.downloads() + " downloads)");
					info(source, "    " + r.pageUrl());
					if (r.downloadUrl() != null) {
						info(source, "    Download: " + r.downloadUrl());
					}
					if (r.description() != null) {
						info(source, "    " + TextUtil.oneLine(r.description(), 120));
					}
				}
				info(source, "Place the jar in your mods/ folder and restart the game.");
			};
			try {
				client.execute(delivery);
			} catch (RuntimeException e) {
				AskBot.LOGGER.debug("Client stopped before mod results could be delivered.");
			}
		});
		return 1;
	}

	private static int setCommands(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		AskBotConfig config = AskBot.getConfig();
		String value = StringArgumentType.getString(context, "value").trim().toLowerCase();
		boolean enabled;
		if (value.equals("on") || value.equals("true") || value.equals("yes")) {
			enabled = true;
		} else if (value.equals("off") || value.equals("false") || value.equals("no")) {
			enabled = false;
		} else {
			error(source, "Usage: /askbot commands <on|off>");
			return 0;
		}
		config.allowCommands = enabled;
		try {
			config.save(AskBot.getConfigPath());
		} catch (Exception e) {
			error(source, "Updated setting but could not save config: " + e.getMessage());
			return 0;
		}
		info(source, "Command execution " + (enabled ? "enabled" : "disabled") + " (server-side only).");
		return 1;
	}

	private static int provider(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		String id = StringArgumentType.getString(context, "id");
		AskBotConfig config = AskBot.getConfig();
		ProviderConfig provider = config.findProvider(id);
		if (provider == null) {
			error(source, "Unknown provider '" + id + "'. Configured: " + String.join(", ", config.enabledProviderIds()));
			return 0;
		}
		if (!provider.enabled) {
			error(source, "Provider '" + id + "' is disabled in config/askbot.json.");
			return 0;
		}
		config.defaultProvider = provider.id;
		try {
			config.save(AskBot.getConfigPath());
		} catch (Exception e) {
			error(source, "Switched to '" + provider.id + "' but could not save config: " + e.getMessage());
			return 0;
		}
		info(source, "Default provider set to '" + provider.id + "'.");
		return 1;
	}

	private static int help(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		info(source, "AskBot commands:");
		info(source, "/askbot <question>");
		info(source, "/askbot clear");
		info(source, "/askbot status");
		info(source, "/askbot provider <id>");
		info(source, "/askbot key <provider> <key>");
		info(source, "/askbot model <model>");
		info(source, "/askbot model <provider> <model>");
		info(source, "/askbot models [provider]");
		info(source, "/askbot internet <on|off>");
		info(source, "/askbot commands <on|off>");
		info(source, "/askbot mod <query>");
		info(source, "/askbot reload");
		info(source, "/askbot help");
		return 1;
	}

	private static void deliver(AskBotConfig config, FabricClientCommandSource source, Minecraft client,
			String playerName, String question, String answer) {
		List<String> lines = TextUtil.chunk(
				TextUtil.truncate(answer, config.maxResponseCharacters), MAX_CHAT_CHUNK);
		if (lines.isEmpty()) {
			error(source, "The AI returned an empty response.");
			return;
		}
		for (String line : lines) {
			source.sendFeedback(answerLine(config.prefix, line));
		}
	}

	private static Component answerLine(String prefix, String text) {
		String safePrefix = prefix == null || prefix.isEmpty() ? "[AskBot] " : prefix;
		return Component.literal(safePrefix).withStyle(ChatFormatting.AQUA)
				.append(Component.literal(text).withStyle(ChatFormatting.WHITE));
	}

	private static String buildSystemPrompt(AskBotConfig config, Minecraft client, String playerName) {
		if (!config.includePlayerContext) {
			return config.systemPrompt;
		}
		StringBuilder prompt = new StringBuilder(config.systemPrompt);
		prompt.append("\n\nContext: player name is ").append(playerName);
		if (client.level != null) {
			prompt.append(", dimension is ").append(client.level.dimension().identifier());
		}
		if (config.includeCoordinates && client.player != null) {
			prompt.append(", coordinates are ")
					.append((int) client.player.getX()).append(", ")
					.append((int) client.player.getY()).append(", ")
					.append((int) client.player.getZ());
		}
		if (config.internetAccess) {
			prompt.append(" You may use internet search to find up-to-date information when it helps.");
		}
		return prompt.toString();
	}

	private static List<ChatMessage> snapshotHistory(UUID id, int maxMessages) {
		Deque<ChatMessage> history = HISTORIES.get(id);
		if (history == null || maxMessages <= 0) {
			return List.of();
		}
		synchronized (history) {
			List<ChatMessage> snapshot = new ArrayList<>(history);
			int from = Math.max(0, snapshot.size() - maxMessages);
			snapshot = new ArrayList<>(snapshot.subList(from, snapshot.size()));
			while (!snapshot.isEmpty() && "assistant".equals(snapshot.get(0).role())) {
				snapshot.remove(0);
			}
			return List.copyOf(snapshot);
		}
	}

	private static void addTurn(UUID id, String question, String answer, int maxMessages) {
		if (maxMessages <= 0) {
			return;
		}
		Deque<ChatMessage> history = HISTORIES.computeIfAbsent(id, ignored -> new ArrayDeque<>());
		synchronized (history) {
			history.addLast(new ChatMessage("user", question));
			history.addLast(new ChatMessage("assistant", answer));
			int maxStored = Math.max(maxMessages + 2, maxMessages);
			while (history.size() > maxStored) {
				history.pollFirst();
			}
			while (!history.isEmpty() && "assistant".equals(history.peekFirst().role())) {
				history.pollFirst();
			}
		}
	}

	private static boolean hasPermission() {
		return true;
	}

	private static void info(FabricClientCommandSource source, String message) {
		source.sendFeedback(Component.literal(message));
	}

	private static void error(FabricClientCommandSource source, String message) {
		source.sendError(Component.literal(message));
	}
}

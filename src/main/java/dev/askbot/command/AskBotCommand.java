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
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import dev.askbot.AskBot;
import dev.askbot.AskBotConfig;
import dev.askbot.ProviderConfig;
import dev.askbot.ai.AiClient;
import dev.askbot.ai.AiException;
import dev.askbot.ai.ChatMessage;
import dev.askbot.util.ModrinthSearch;
import dev.askbot.util.TextUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.Permissions;

public final class AskBotCommand {
	private static final UUID CONSOLE_ID = new UUID(0L, 0L);
	private static final int MAX_CHAT_CHUNK = 900;

	private static final Map<UUID, Long> COOLDOWNS = new ConcurrentHashMap<>();
	private static final Map<UUID, Deque<ChatMessage>> HISTORIES = new ConcurrentHashMap<>();
	private static final AtomicInteger IN_FLIGHT = new AtomicInteger();

	private AskBotCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("askbot")
				.executes(AskBotCommand::help)
				.then(Commands.literal("help")
						.executes(AskBotCommand::help))
				.then(Commands.literal("clear")
						.executes(AskBotCommand::clear))
				.then(Commands.literal("status")
						.executes(AskBotCommand::status))
				.then(Commands.literal("reload")
						.executes(AskBotCommand::reload))
				.then(Commands.literal("provider")
						.executes(context -> {
							info(context.getSource(), "Usage: /askbot provider <id>");
							return 0;
						})
						.then(Commands.argument("id", StringArgumentType.word())
								.suggests(AskBotCommand::suggestProviders)
								.executes(AskBotCommand::provider)))
		.then(Commands.literal("key")
				.executes(context -> {
					info(context.getSource(), "Usage: /askbot key <provider> <key>");
					return 0;
				})
				.then(Commands.argument("provider", StringArgumentType.word())
						.suggests(AskBotCommand::suggestProviders)
						.then(Commands.argument("key", StringArgumentType.greedyString())
								.executes(AskBotCommand::setKey))))
		.then(Commands.literal("model")
				.executes(context -> {
					info(context.getSource(), "Usage: /askbot model <model>  or  /askbot model <provider> <model>");
					return 0;
				})
				.then(Commands.argument("value", StringArgumentType.greedyString())
						.executes(AskBotCommand::setModel)))
		.then(Commands.literal("internet")
				.executes(context -> {
					info(context.getSource(), "Usage: /askbot internet <on|off>");
					return 0;
				})
				.then(Commands.argument("value", StringArgumentType.word())
						.executes(AskBotCommand::setInternet)))
		.then(Commands.literal("commands")
				.executes(context -> {
					info(context.getSource(), "Usage: /askbot commands <on|off>");
					return 0;
				})
				.then(Commands.argument("value", StringArgumentType.word())
						.executes(AskBotCommand::setCommands)))
		.then(Commands.literal("mod")
				.executes(context -> {
					info(context.getSource(), "Usage: /askbot mod <search query>");
					return 0;
				})
				.then(Commands.argument("query", StringArgumentType.greedyString())
						.executes(AskBotCommand::findMod)))
		.then(Commands.literal("models")
				.executes(AskBotCommand::models)
				.then(Commands.argument("provider", StringArgumentType.word())
						.suggests(AskBotCommand::suggestProviders)
						.executes(AskBotCommand::modelsFor)))
				.then(Commands.argument("question", StringArgumentType.greedyString())
						.executes(AskBotCommand::ask)));
	}

	private static int ask(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		AskBotConfig config = AskBot.getConfig();

		if (!hasPermission(source, config.permissionLevel)) {
			error(source, "You do not have permission to use AskBot.");
			return 0;
		}

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

		ServerPlayer player = source.getPlayer();
		UUID historyId = player == null ? CONSOLE_ID : player.getUUID();

		boolean useCooldown = config.cooldownSeconds > 0
				&& !hasPermission(source, config.cooldownBypassPermissionLevel);
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

		String playerName = player == null ? "Server" : player.getName().getString();
		String systemPrompt = buildSystemPrompt(config, player);
		List<ChatMessage> history = snapshotHistory(historyId, config.historyMessages);
		MinecraftServer server = source.getServer();

		if (config.showThinking) {
			source.sendSystemMessage(Component.literal("[AskBot] Thinking... (" + provider.id + " / "
					+ provider.model + ")").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
		}

		CompletableFuture<String> request;
		try {
			if (config.allowCommands && "openai".equals(provider.type)) {
				final MinecraftServer cmdServer = server;
				final ServerPlayer cmdPlayer = player;
				AiClient.CommandRunner runner = command -> {
					CompletableFuture<String> future = new CompletableFuture<>();
					StringBuilder output = new StringBuilder();
					CommandSource capturing = new CommandSource() {
						public void sendSystemMessage(Component component) {
							if (output.length() > 0) {
								output.append('\n');
							}
							output.append(component.getString());
						}

						public boolean acceptsSuccess() {
							return true;
						}

						public boolean acceptsFailure() {
							return true;
						}

						public boolean shouldInformAdmins() {
							return false;
						}
					};
					ServerLevel level = cmdPlayer != null ? cmdPlayer.level() : cmdServer.overworld();
					Vec3 pos = cmdPlayer != null ? cmdPlayer.position() : Vec3.ZERO;
					CommandSourceStack stack = new CommandSourceStack(capturing, pos, Vec2.ZERO, level,
							PermissionSet.ALL_PERMISSIONS, Component.literal("AskBot"), cmdServer);
					Runnable task = () -> {
						try {
							String cmd = command == null ? "" : command.trim();
							if (cmd.startsWith("/")) {
								cmd = cmd.substring(1);
							}
							cmdServer.getCommands().performPrefixedCommand(stack, cmd);
						} catch (Exception e) {
							if (output.length() > 0) {
								output.append('\n');
							}
							output.append("Error: ").append(e.getMessage());
						}
						future.complete(output.toString().trim());
					};
					cmdServer.execute(task);
					return future;
				};
				request = AiClient.completeWithTools(config, provider, history, question, systemPrompt, runner);
			} else {
				request = AiClient.complete(config, provider, history, question, systemPrompt);
			}
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
					addTurn(historyId, question,
							TextUtil.truncate(text, config.maxResponseCharacters), config.historyMessages);
					deliver(config, source, player, playerName, question, text);
				} catch (RuntimeException e) {
					AskBot.LOGGER.warn("AskBot could not deliver a reply: {}", e.toString());
				}
			};

			try {
				server.execute(delivery);
			} catch (RuntimeException e) {
				AskBot.LOGGER.debug("Server stopped before the AskBot reply could be delivered.");
			}
		});

		return 1;
	}

	private static void deliver(AskBotConfig config, CommandSourceStack source, ServerPlayer player,
			String playerName, String question, String answer) {
		MinecraftServer server = source.getServer();
		if (server == null || !server.isRunning()) {
			return;
		}
		if (player != null && (player.hasDisconnected()
				|| server.getPlayerList().getPlayer(player.getUUID()) == null)) {
			return;
		}

		List<String> lines = TextUtil.chunk(
				TextUtil.truncate(answer, config.maxResponseCharacters), MAX_CHAT_CHUNK);
		if (lines.isEmpty()) {
			error(source, "The AI returned an empty response.");
			return;
		}

		if (config.broadcast) {
			server.getPlayerList().broadcastSystemMessage(
					Component.literal(playerName + " asked: " + question)
							.withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC),
					false);
			for (String line : lines) {
				server.getPlayerList().broadcastSystemMessage(answerLine(config.prefix, line), false);
			}
		} else {
			for (String line : lines) {
				source.sendSystemMessage(answerLine(config.prefix, line));
			}
		}
	}

	private static String buildSystemPrompt(AskBotConfig config, ServerPlayer player) {
		String base = config.systemPrompt == null ? "" : config.systemPrompt.trim();
		if (!config.includePlayerContext || player == null) {
			return base;
		}

		StringBuilder prompt = new StringBuilder(base);
		prompt.append("\n\nCurrent player: ").append(player.getName().getString());
		prompt.append(" (dimension: ").append(player.level().dimension().identifier()).append(')');
		if (config.includeCoordinates) {
			prompt.append(", position: ")
					.append(player.blockPosition().getX()).append(", ")
					.append(player.blockPosition().getY()).append(", ")
					.append(player.blockPosition().getZ());
		}
		prompt.append(". Use this context only when it is relevant. Do not claim to see the world.");
		if (config.internetAccess) {
			prompt.append(" You may use internet search to find up-to-date information when it helps.");
		}
		return prompt.toString();
	}

	private static int clear(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		ServerPlayer player = source.getPlayer();
		HISTORIES.remove(player == null ? CONSOLE_ID : player.getUUID());
		info(source, "Your AskBot conversation history has been cleared.");
		return 1;
	}

	private static int status(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		AskBotConfig config = AskBot.getConfig();
		ProviderConfig provider = config.findProvider(config.defaultProvider);

		source.sendSystemMessage(Component.literal("[AskBot] Status")
				.withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
		if (provider == null) {
			info(source, "Default provider: " + config.defaultProvider + " (not configured)");
		} else {
			info(source, "Default provider: " + provider.id + " | type: " + provider.type
					+ " | model: " + provider.model);
			info(source, "Enabled: " + provider.enabled + " | requires API key: " + provider.requiresApiKey
					+ " | API key: " + (TextUtil.expandEnv(provider.apiKey).isBlank() ? "not set" : "set"));
		}
		info(source, "Configured providers: " + String.join(", ", config.enabledProviderIds()));
		info(source, "maxTokens=" + config.maxTokens + " | temperature=" + config.temperature
				+ " | timeout=" + config.timeoutSeconds + "s");
		info(source, "cooldown=" + config.cooldownSeconds + "s | history=" + config.historyMessages
				+ " messages | broadcast=" + config.broadcast + " | internet=" + config.internetAccess
				+ " | commands=" + config.allowCommands);

		UUID historyId = source.getPlayer() == null ? CONSOLE_ID : source.getPlayer().getUUID();
		int stored = 0;
		Deque<ChatMessage> history = HISTORIES.get(historyId);
		if (history != null) {
			synchronized (history) {
				stored = history.size();
			}
		}
		info(source, "Your stored history: " + stored + " message(s)");
		return 1;
	}

	private static int reload(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		if (!hasPermission(source, AskBot.getConfig().adminPermissionLevel)) {
			error(source, "You do not have permission to reload the AskBot config.");
			return 0;
		}
		String error = AskBot.reloadConfig();
		if (error == null) {
			source.sendSystemMessage(Component.literal("[AskBot] Configuration reloaded.")
					.withStyle(ChatFormatting.GREEN));
			return 1;
		}
		error(source, "Could not reload config: " + error);
		return 0;
	}

	private static int setKey(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		AskBotConfig config = AskBot.getConfig();
		if (!hasPermission(source, config.adminPermissionLevel)) {
			error(source, "You do not have permission to change API keys.");
			return 0;
		}
		String id = StringArgumentType.getString(context, "provider");
		String key = StringArgumentType.getString(context, "key").trim();
		ProviderConfig provider = config.findProvider(id);
		if (provider == null) {
			error(source, "Unknown provider '" + id + "'. Configured: "
					+ String.join(", ", config.enabledProviderIds()));
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

	private static int setModel(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		AskBotConfig config = AskBot.getConfig();
		if (!hasPermission(source, config.adminPermissionLevel)) {
			error(source, "You do not have permission to change models.");
			return 0;
		}
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

	private static int models(CommandContext<CommandSourceStack> context) {
		return listModels(context, null);
	}

	private static int modelsFor(CommandContext<CommandSourceStack> context) {
		return listModels(context, StringArgumentType.getString(context, "provider"));
	}

	private static int listModels(CommandContext<CommandSourceStack> context, String providerId) {
		CommandSourceStack source = context.getSource();
		AskBotConfig config = AskBot.getConfig();
		if (!hasPermission(source, config.permissionLevel)) {
			error(source, "You do not have permission to use AskBot.");
			return 0;
		}
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
		MinecraftServer server = source.getServer();
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
				server.execute(delivery);
			} catch (RuntimeException e) {
				AskBot.LOGGER.debug("Server stopped before model list could be delivered.");
			}
		});
		return 1;
	}

	private static int setInternet(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		AskBotConfig config = AskBot.getConfig();
		if (!hasPermission(source, config.adminPermissionLevel)) {
			error(source, "You do not have permission to change this setting.");
			return 0;
		}
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

	private static int findMod(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		AskBotConfig config = AskBot.getConfig();
		if (!hasPermission(source, config.permissionLevel)) {
			error(source, "You do not have permission to use AskBot.");
			return 0;
		}
		String query = StringArgumentType.getString(context, "query").trim();
		if (query.isBlank()) {
			info(source, "Usage: /askbot mod <search query>");
			return 0;
		}
		info(source, "Searching Modrinth for \"" + query + "\"...");
		MinecraftServer server = source.getServer();
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
				info(source, "Place the jar in your mods/ folder and restart the game/server.");
			};
			try {
				server.execute(delivery);
			} catch (RuntimeException e) {
				AskBot.LOGGER.debug("Server stopped before mod results could be delivered.");
			}
		});
		return 1;
	}

	private static int setCommands(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		AskBotConfig config = AskBot.getConfig();
		if (!hasPermission(source, config.adminPermissionLevel)) {
			error(source, "You do not have permission to change this setting.");
			return 0;
		}
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
		info(source, "Command execution " + (enabled ? "enabled" : "disabled") + ".");
		return 1;
	}

	private static int provider(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		AskBotConfig config = AskBot.getConfig();
		if (!hasPermission(source, config.adminPermissionLevel)) {
			error(source, "You do not have permission to change the AskBot provider.");
			return 0;
		}

		String id = StringArgumentType.getString(context, "id");
		ProviderConfig provider = config.findProvider(id);
		if (provider == null || !provider.enabled) {
			error(source, "Unknown or disabled provider '" + id + "'. Available: "
					+ String.join(", ", config.enabledProviderIds()));
			return 0;
		}

		config.defaultProvider = provider.id;
		String saveError = AskBot.saveConfig();
		if (saveError != null) {
			source.sendSystemMessage(Component.literal("[AskBot] Default provider set to '" + provider.id
					+ "' for this session, but saving the config failed: " + saveError)
					.withStyle(ChatFormatting.YELLOW));
			return 1;
		}
		source.sendSystemMessage(Component.literal("[AskBot] Default provider set to '" + provider.id + "'.")
				.withStyle(ChatFormatting.GREEN));
		return 1;
	}

	private static int help(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		source.sendSystemMessage(Component.literal("[AskBot] Commands")
				.withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
		info(source, "/askbot <question> - ask the AI");
		info(source, "/askbot clear - clear your conversation history");
		info(source, "/askbot status - show provider and config status");
		info(source, "/askbot provider <id> - switch the default provider (admin)");
		info(source, "/askbot key <provider> <key> - set an API key in-game (admin)");
		info(source, "/askbot model <model> - set the model for the default provider (admin)");
		info(source, "/askbot model <provider> <model> - set a provider model (admin)");
		info(source, "/askbot models [provider] - list available models");
		info(source, "/askbot internet <on|off> - enable AI web search (admin)");
		info(source, "/askbot commands <on|off> - allow AI to run commands (admin)");
		info(source, "/askbot mod <query> - search Modrinth for mods");
		info(source, "/askbot reload - reload config/askbot.json (admin)");
		return 1;
	}

	private static CompletableFuture<Suggestions> suggestProviders(CommandContext<CommandSourceStack> context,
			SuggestionsBuilder builder) {
		for (String id : AskBot.getConfig().enabledProviderIds()) {
			builder.suggest(id);
		}
		return builder.buildFuture();
	}

	private static boolean hasPermission(CommandSourceStack source, int level) {
		if (level <= 0) {
			return true;
		}
		Permission permission = switch (Math.min(level, 4)) {
			case 1 -> Permissions.COMMANDS_MODERATOR;
			case 2 -> Permissions.COMMANDS_GAMEMASTER;
			case 3 -> Permissions.COMMANDS_ADMIN;
			default -> Permissions.COMMANDS_OWNER;
		};
		return source.permissions().hasPermission(permission);
	}

	private static List<ChatMessage> snapshotHistory(UUID id, int maxMessages) {
		if (maxMessages <= 0) {
			return List.of();
		}
		Deque<ChatMessage> history = HISTORIES.get(id);
		if (history == null) {
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
		Deque<ChatMessage> history = HISTORIES.computeIfAbsent(id, key -> new ArrayDeque<>());
		synchronized (history) {
			history.addLast(new ChatMessage("user", question));
			history.addLast(new ChatMessage("assistant", answer));
			while (history.size() > maxMessages) {
				history.removeFirst();
			}
		}
	}

	public static void clearAll() {
		HISTORIES.clear();
		COOLDOWNS.clear();
	}

	private static Component answerLine(String prefix, String text) {
		String safePrefix = prefix == null || prefix.isEmpty() ? "[AskBot] " : prefix;
		return Component.literal(safePrefix).withStyle(ChatFormatting.AQUA)
				.append(Component.literal(text).withStyle(ChatFormatting.WHITE));
	}

	private static void error(CommandSourceStack source, String message) {
		source.sendSystemMessage(Component.literal("[AskBot] " + message).withStyle(ChatFormatting.RED));
	}

	private static void info(CommandSourceStack source, String message) {
		source.sendSystemMessage(Component.literal("[AskBot] " + message).withStyle(ChatFormatting.GRAY));
	}
}

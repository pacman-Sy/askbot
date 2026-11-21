package dev.askbot.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TextUtil {
	private static final Pattern ENV_PATTERN = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

	private TextUtil() {
	}

	/** Expands ${NAME} using environment variables. Missing variables become empty. */
	public static String expandEnv(String value) {
		if (value == null || value.isEmpty()) {
			return "";
		}
		Matcher matcher = ENV_PATTERN.matcher(value);
		StringBuilder out = new StringBuilder();
		while (matcher.find()) {
			String replacement = System.getenv(matcher.group(1));
			matcher.appendReplacement(out, Matcher.quoteReplacement(replacement == null ? "" : replacement));
		}
		matcher.appendTail(out);
		return out.toString();
	}

	/** Human-readable description of where a configured key comes from. */
	public static String describeKeySource(String rawValue) {
		Matcher matcher = ENV_PATTERN.matcher(rawValue == null ? "" : rawValue);
		if (matcher.find()) {
			return "the " + matcher.group(1) + " environment variable";
		}
		return "an API key";
	}

	public static String stripQuotes(String value) {
		if (value == null) {
			return "";
		}
		String trimmed = value.trim();
		if (trimmed.length() >= 2) {
			char first = trimmed.charAt(0);
			char last = trimmed.charAt(trimmed.length() - 1);
			if ((first == '"' || first == '\'') && first == last) {
				return trimmed.substring(1, trimmed.length() - 1).trim();
			}
		}
		return trimmed;
	}

	/** Truncates a string, appending an ellipsis when content was removed. */
	public static String truncate(String value, int maxLength) {
		if (value == null) {
			return "";
		}
		if (maxLength <= 0 || value.length() <= maxLength) {
			return value;
		}
		return value.substring(0, Math.max(0, maxLength - 1)).stripTrailing() + "\u2026";
	}

	/** Collapses whitespace so an error body can be logged/shown on one line. */
	public static String oneLine(String value, int maxLength) {
		if (value == null) {
			return "";
		}
		return truncate(value.replaceAll("\\s+", " ").trim(), maxLength);
	}

	/**
	 * Splits an AI answer into chat-sized chunks. Newlines become separate
	 * messages and long lines are wrapped at word boundaries where possible.
	 */
	public static List<String> chunk(String value, int maxLength) {
		List<String> chunks = new ArrayList<>();
		if (value == null || maxLength <= 0) {
			return chunks;
		}
		String normalized = value.replace("\r\n", "\n").replace('\r', '\n');
		for (String rawLine : normalized.split("\n", -1)) {
			String line = rawLine.strip();
			if (line.isEmpty()) {
				continue;
			}
			while (line.length() > maxLength) {
				int cut = line.lastIndexOf(' ', maxLength);
				if (cut < maxLength / 2) {
					cut = maxLength;
				}
				String piece = line.substring(0, cut).strip();
				if (!piece.isEmpty()) {
					chunks.add(piece);
				}
				line = line.substring(cut).strip();
			}
			if (!line.isEmpty()) {
				chunks.add(line);
			}
		}
		return chunks;
	}
}

package dev.askbot.ai;

import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import javax.net.ssl.SSLException;

/** An error whose message is safe and useful to show to a player in chat. */
public class AiException extends Exception {
	public AiException(String message) {
		super(message);
	}

	public AiException(String message, Throwable cause) {
		super(message, cause);
	}

	public static String describe(Throwable throwable) {
		Throwable cause = throwable;
		while ((cause instanceof CompletionException || cause instanceof ExecutionException)
				&& cause.getCause() != null) {
			cause = cause.getCause();
		}
		if (cause instanceof AiException aiException) {
			return aiException.getMessage();
		}
		if (cause instanceof HttpTimeoutException) {
			return "The request timed out. Try again or raise timeoutSeconds in config/askbot.json.";
		}
		if (cause instanceof ConnectException) {
			return "Could not connect to the AI provider. Check its baseUrl and your internet connection.";
		}
		if (cause instanceof UnknownHostException) {
			return "Unknown host '" + cause.getMessage() + "'. Check the provider baseUrl.";
		}
		if (cause instanceof SSLException) {
			return "TLS error while contacting the provider: " + cause.getMessage();
		}
		if (cause instanceof IOException) {
			return "Network error: " + cause.getMessage();
		}
		String message = cause == null ? null : cause.getMessage();
		return message == null || message.isBlank() ? "Unexpected error: " + throwable.getClass().getSimpleName() : message;
	}
}

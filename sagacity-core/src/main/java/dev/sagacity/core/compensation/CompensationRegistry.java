package dev.sagacity.core.compensation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import dev.sagacity.core.Reversibility;

/** Maps tool names to their declared compensation. */
public final class CompensationRegistry {

	/**
	 * @param handler nullable for IRREVERSIBLE tools, which have no undo by definition
	 */
	public record Registration(String toolName, Reversibility reversibility, CompensationHandler handler) {
	}

	private final Map<String, Registration> registrations = new ConcurrentHashMap<>();

	public void register(Registration registration) {
		this.registrations.put(registration.toolName(), registration);
	}

	/** @return the registration, or null if the tool declared no compensation */
	public Registration find(String toolName) {
		return this.registrations.get(toolName);
	}

}

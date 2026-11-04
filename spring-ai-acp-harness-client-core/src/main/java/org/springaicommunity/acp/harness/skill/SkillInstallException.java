package org.springaicommunity.acp.harness.skill;

/** A configured skill could not be installed, so the agent is not started without it. */
public class SkillInstallException extends RuntimeException {

	public SkillInstallException(String message) {
		super(message);
	}

	public SkillInstallException(String message, Throwable cause) {
		super(message, cause);
	}

}

package org.springaicommunity.acp.client;

/** Something went wrong talking to the agent. */
public class AcpHarnessClientException extends RuntimeException {

	public AcpHarnessClientException(String message) {
		super(message);
	}

	public AcpHarnessClientException(String message, Throwable cause) {
		super(message, cause);
	}

}

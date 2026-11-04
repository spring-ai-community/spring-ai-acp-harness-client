package org.springaicommunity.acp.harness.client;

/**
 * The agent is fine, but no connection can take another session right now. Worth trying
 * again later, unlike a turn that failed.
 */
public class AcpHarnessCapacityException extends AcpHarnessClientException {

	public AcpHarnessCapacityException(String message) {
		super(message);
	}

}

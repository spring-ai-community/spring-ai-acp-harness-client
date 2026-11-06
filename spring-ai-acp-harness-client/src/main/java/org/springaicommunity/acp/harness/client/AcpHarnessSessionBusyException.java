package org.springaicommunity.acp.harness.client;

/**
 * The named session is still running an earlier turn, and the caller chose not to wait
 * for it, or waited as long as it was willing to. The agent is fine: the caller sent
 * overlapping turns to one conversation.
 *
 * @see AcpHarnessClient.PromptSpec#waitIfBusy(java.time.Duration)
 */
public class AcpHarnessSessionBusyException extends AcpHarnessClientException {

	public AcpHarnessSessionBusyException(String message) {
		super(message);
	}

}

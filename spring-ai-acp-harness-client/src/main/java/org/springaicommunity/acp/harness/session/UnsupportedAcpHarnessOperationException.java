package org.springaicommunity.acp.harness.session;

import org.springaicommunity.acp.harness.client.AcpHarnessClientException;

/**
 * An operation this library offers that the agent in front of it does not implement.
 *
 * <p>
 * The counterpart to {@code UnsupportedAcpHarnessOptionException}, one level up: that one
 * is about a <em>value</em> an agent will not take, this one about a <em>method</em> it
 * does not have. ACP makes both possible and neither avoidable — {@code session/list},
 * {@code session/delete} and {@code session/resume} are each gated on a capability the
 * agent advertises — so an abstraction over several agents has to have a way of saying
 * "not here", and saying it by name.
 */
public class UnsupportedAcpHarnessOperationException extends AcpHarnessClientException {

	private final transient AcpHarnessSessions.Operation operation;

	public UnsupportedAcpHarnessOperationException(String runtimeId, AcpHarnessSessions.Operation operation) {
		super("Runtime '" + runtimeId + "' does not support " + operation.method()
				+ "; the agent did not advertise it in its capabilities");
		this.operation = operation;
	}

	public AcpHarnessSessions.Operation operation() {
		return operation;
	}

}

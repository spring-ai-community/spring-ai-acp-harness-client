package org.springaicommunity.acp.harness.permission;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.agentclientprotocol.sdk.spec.AcpSchema;

import static org.assertj.core.api.Assertions.assertThat;

class PermissionPolicyTests {

	private static final AcpSchema.PermissionOption ALLOW = new AcpSchema.PermissionOption("a", "Allow",
			AcpSchema.PermissionOptionKind.ALLOW_ONCE);

	private static final AcpSchema.PermissionOption REJECT = new AcpSchema.PermissionOption("r", "Reject",
			AcpSchema.PermissionOptionKind.REJECT_ONCE);

	private static final List<AcpSchema.PermissionOption> BOTH = List.of(ALLOW, REJECT);

	@Test
	void denyRejectsEvenWhenTheToolLooksHarmless() {
		assertThat(PermissionPolicy.deny().decide("developer__read", BOTH)).isEqualTo(REJECT);
	}

	@Test
	void autoApproveAllows() {
		assertThat(PermissionPolicy.autoApprove().decide("anything", BOTH)).isEqualTo(ALLOW);
	}

	@Test
	void allowlistAdmitsOnlyItsOwnTools() {
		PermissionPolicy policy = PermissionPolicy.allowlist(Set.of("developer__text_editor"));

		assertThat(policy.decide("developer__text_editor", BOTH)).isEqualTo(ALLOW);
		assertThat(policy.decide("developer__shell", BOTH)).isEqualTo(REJECT);
	}

	@Test
	void allowlistMatchesRegardlessOfCase() {
		PermissionPolicy policy = PermissionPolicy.allowlist(Set.of("Developer__Text_Editor"));

		assertThat(policy.decide("developer__text_editor", BOTH)).isEqualTo(ALLOW);
	}

	@Test
	void allowlistRejectsWhenTheAgentWouldNotNameTheTool() {
		PermissionPolicy policy = PermissionPolicy.allowlist(Set.of("developer__text_editor"));

		assertThat(policy.decide(null, BOTH)).isEqualTo(REJECT);
	}

	@Test
	void fallsBackToTheAlwaysVariantWhenOnlyItIsOffered() {
		AcpSchema.PermissionOption rejectAlways = new AcpSchema.PermissionOption("ra", "Always reject",
				AcpSchema.PermissionOptionKind.REJECT_ALWAYS);

		assertThat(PermissionPolicy.deny().decide("t", List.of(ALLOW, rejectAlways))).isEqualTo(rejectAlways);
	}

	@Test
	void returnsNullWhenTheAgentOffersNothingToChoose() {
		assertThat(PermissionPolicy.deny().decide("t", List.of())).isNull();
	}

	@Test
	void theWholeRequestReachesARuleAsJustTheToolAndOptions() {
		assertThat(PermissionPolicy.autoApprove().decide(request("Run ls"), "shell")).isEqualTo(ALLOW);
	}

	@Test
	void askShowsThePersonTheAgentsDescriptionOfTheCall() {
		AtomicReference<PermissionQuestion> asked = new AtomicReference<>();
		PermissionPolicy policy = PermissionPolicy.ask(question -> {
			asked.set(question);
			return REJECT;
		});

		assertThat(policy.decide(request("Run `rm -rf build`"), "developer__shell")).isEqualTo(REJECT);
		assertThat(asked.get().describe()).isEqualTo("Run `rm -rf build`");
		assertThat(asked.get().toolName()).isEqualTo("developer__shell");
		assertThat(asked.get().kind()).isEqualTo(AcpSchema.ToolKind.EXECUTE);
		assertThat(asked.get().options()).containsExactly(ALLOW, REJECT);
	}

	@Test
	void askFallsBackToTheToolNameWhenTheAgentGaveNoTitle() {
		AtomicReference<PermissionQuestion> asked = new AtomicReference<>();
		PermissionPolicy.ask(question -> {
			asked.set(question);
			return null;
		}).decide("developer__shell", BOTH);

		assertThat(asked.get().describe()).isEqualTo("developer__shell");
	}

	@Test
	void askCancelsWhenThePersonDeclinesToChoose() {
		assertThat(PermissionPolicy.ask(question -> null).decide(request("x"), null)).isNull();
	}

	@Test
	void askNeverSendsBackAnOptionTheAgentDidNotOffer() {
		AcpSchema.PermissionOption invented = new AcpSchema.PermissionOption("z", "Invented",
				AcpSchema.PermissionOptionKind.ALLOW_ALWAYS);

		assertThat(PermissionPolicy.ask(question -> invented).decide(request("x"), null)).isNull();
	}

	private static AcpSchema.RequestPermissionRequest request(String title) {
		AcpSchema.ToolCallUpdate call = new AcpSchema.ToolCallUpdate("call-1", title, null, AcpSchema.ToolKind.EXECUTE,
				AcpSchema.ToolCallStatus.PENDING, null, null, null, null, null);
		return new AcpSchema.RequestPermissionRequest("session-1", call, BOTH);
	}

}

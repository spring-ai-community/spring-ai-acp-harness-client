package org.springaicommunity.acp.harness.permission;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.agentclientprotocol.sdk.spec.AcpSchema;

import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ACP's rule for a cancelled turn — every pending {@code session/request_permission} is
 * answered {@code cancelled} — held against a decision that may be a person who has not
 * answered yet, or who answers too late.
 */
class PendingPermissionsTests {

	private static final AcpSchema.PermissionOption ALLOW = new AcpSchema.PermissionOption("allow", "Allow",
			AcpSchema.PermissionOptionKind.ALLOW_ONCE);

	private final PendingPermissions permissions = new PendingPermissions();

	@Test
	void aDecisionIsTheAnswer() {
		assertThat(outcomeOf(permissions.decide("s", () -> ALLOW)))
			.isEqualTo(new AcpSchema.PermissionSelected("allow"));
		assertThat(outcomeOf(permissions.decide("s", () -> null))).isInstanceOf(AcpSchema.PermissionCancelled.class);
	}

	@Test
	void withdrawingAnswersCancelledAtOnceAndInterruptsWhoeverIsDeciding() throws InterruptedException {
		Person person = new Person(ALLOW);
		Mono<AcpSchema.RequestPermissionResponse> question = permissions.decide("s", person).cache();
		question.subscribe();
		person.awaitAsked();

		permissions.withdraw("s");

		assertThat(outcomeOf(question)).isInstanceOf(AcpSchema.PermissionCancelled.class);
		assertThat(person.interrupted.await(5, TimeUnit.SECONDS)).isTrue();
	}

	@Test
	void anAnswerThatComesAfterTheWithdrawalIsNotSent() throws InterruptedException {
		// A prompt that ignores the interrupt and answers anyway: the "allow" must not
		// reach the agent for a turn that is already over.
		CountDownLatch asked = new CountDownLatch(1);
		CountDownLatch answer = new CountDownLatch(1);
		Mono<AcpSchema.RequestPermissionResponse> question = permissions.decide("s", () -> {
			asked.countDown();
			awaitUninterruptibly(answer);
			return ALLOW;
		}).cache();
		question.subscribe();
		assertThat(asked.await(5, TimeUnit.SECONDS)).isTrue();

		permissions.withdraw("s");
		answer.countDown();

		assertThat(outcomeOf(question)).isInstanceOf(AcpSchema.PermissionCancelled.class);
	}

	@Test
	void aRequestThatArrivesWhileWithdrawnIsNotAskedUntilReopened() {
		AtomicInteger asked = new AtomicInteger();
		permissions.withdraw("s");

		assertThat(outcomeOf(permissions.decide("s", () -> {
			asked.incrementAndGet();
			return ALLOW;
		}))).isInstanceOf(AcpSchema.PermissionCancelled.class);
		assertThat(asked).hasValue(0);

		permissions.reopen("s");

		assertThat(outcomeOf(permissions.decide("s", () -> ALLOW)))
			.isEqualTo(new AcpSchema.PermissionSelected("allow"));
	}

	@Test
	void withdrawingOneSessionLeavesAnothersQuestionAlone() throws InterruptedException {
		Person person = new Person(ALLOW);
		Mono<AcpSchema.RequestPermissionResponse> question = permissions.decide("other", person).cache();
		question.subscribe();
		person.awaitAsked();

		permissions.withdraw("s");
		person.answer();

		assertThat(outcomeOf(question)).isEqualTo(new AcpSchema.PermissionSelected("allow"));
	}

	@Test
	void anInterruptMeantForTheQuestionDoesNotOutliveIt() throws InterruptedException {
		Person person = new Person(ALLOW);
		AtomicBoolean interruptedAfterwards = new AtomicBoolean(true);
		Mono<AcpSchema.RequestPermissionResponse> question = permissions.decide("s", person).cache();
		question.subscribe();
		person.awaitAsked();
		permissions.withdraw("s");
		outcomeOf(question);

		// The next decision runs on a boundedElastic thread too, maybe the same one.
		outcomeOf(permissions.decide("t", () -> {
			interruptedAfterwards.set(Thread.currentThread().isInterrupted());
			return ALLOW;
		}));

		assertThat(interruptedAfterwards).isFalse();
	}

	private static AcpSchema.RequestPermissionOutcome outcomeOf(Mono<AcpSchema.RequestPermissionResponse> response) {
		AcpSchema.RequestPermissionResponse answered = response.block(Duration.ofSeconds(5));
		assertThat(answered).isNotNull();
		return answered.outcome();
	}

	private static void awaitUninterruptibly(CountDownLatch latch) {
		boolean interrupted = false;
		while (true) {
			try {
				latch.await();
				break;
			}
			catch (InterruptedException ex) {
				interrupted = true;
			}
		}
		if (interrupted) {
			Thread.currentThread().interrupt();
		}
	}

	/** Someone at a keyboard, who answers when told to or gives up when interrupted. */
	private static final class Person implements java.util.function.Supplier<AcpSchema.PermissionOption> {

		private final AcpSchema.PermissionOption choice;

		private final CountDownLatch asked = new CountDownLatch(1);

		private final CountDownLatch answered = new CountDownLatch(1);

		private final CountDownLatch interrupted = new CountDownLatch(1);

		Person(AcpSchema.PermissionOption choice) {
			this.choice = choice;
		}

		@Override
		public AcpSchema.PermissionOption get() {
			asked.countDown();
			try {
				answered.await();
				return choice;
			}
			catch (InterruptedException ex) {
				interrupted.countDown();
				return null;
			}
		}

		void awaitAsked() throws InterruptedException {
			assertThat(asked.await(5, TimeUnit.SECONDS)).isTrue();
		}

		void answer() {
			answered.countDown();
		}

	}

}

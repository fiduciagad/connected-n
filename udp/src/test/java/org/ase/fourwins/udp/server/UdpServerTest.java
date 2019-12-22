package org.ase.fourwins.udp.server;

import static java.time.Duration.ofSeconds;
import static java.util.Arrays.asList;
import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.joining;
import static org.ase.fourwins.udp.server.UdpServer.MAX_CLIENT_NAME_LENGTH;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.junit.Assert.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.ase.fourwins.board.Board.GameState;
import org.ase.fourwins.tournament.Tournament;
import org.ase.fourwins.udp.udphelper.UdpCommunicator;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.verification.VerificationMode;

import lombok.Getter;

public class UdpServerTest {

	private static final Duration TIMEOUT = ofSeconds(10);

	private static class BaseClient {

		@Getter
		protected final String name;
		protected final UdpCommunicator communicator;

		public BaseClient(String name, String remoteHost, int remotePort) throws IOException {
			this.name = name;
			this.communicator = new UdpCommunicator(remoteHost, remotePort);
		}

		void send(String message) throws IOException {
			this.communicator.getMessageSender().send(message);
		}

		void trySend(String message) {
			try {
				send(message);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

	}

	static class DummyClient extends BaseClient {

		@Getter
		private final List<String> received = new CopyOnWriteArrayList<String>();

		public DummyClient(String name, String remoteHost, int remotePort) throws IOException {
			super(name, remoteHost, remotePort);
			this.communicator.addMessageListener(received -> messageReceived(received));
			runInBackground(() -> {
				try {
					this.communicator.listenForMessages();
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			});
			register();
		}

		protected void messageReceived(String received) {
			DummyClient.this.received.add(received);
		}

		protected void register() throws IOException {
			send("REGISTER;" + name);
		}

		protected void unregister() throws IOException {
			send("UNREGISTER");
		}

		void assertReceived(String... messages) throws InterruptedException {
			await().until(() -> getReceived(), is(asList(messages)));
		}

	}

	private final int serverPort = freePort();

	private final Tournament tournament = mock(Tournament.class);

	private UdpServer udpServerInBackground() {
		UdpServer udpServer = new UdpServer(serverPort, tournament);
		runInBackground(() -> udpServer.startServer());
		return udpServer;
	}

	private static void runInBackground(Runnable runnable) {
		new Thread(runnable).start();
	}

	@BeforeEach
	public void setup() {
		udpServerInBackground();
	}

	@AfterEach
	public void tearDown() {
//		sut.shutdown();
	}

	@Test
	void clientCanConnectToServer() throws IOException, InterruptedException {
		infiniteSeason(tournament);
		assertTimeoutPreemptively(TIMEOUT, () -> {
			DummyClient client1 = new DummyClient("1", "localhost", serverPort);
			client1.assertReceived("Welcome 1");
			verify(tournament, times(0)).playSeason(anyCollection(), anyGameStateConsumer());
		});
	}

	@Test
	void canHandleEmptyCorruptedRegisterMessage() throws IOException {
		infiniteSeason(tournament);
		assertTimeoutPreemptively(TIMEOUT, () -> {
			new DummyClient("1", "localhost", serverPort) {
				protected void register() throws IOException {
					send("REGISTER;");
				}
			};
			verify(tournament, timesWithTimeout(0)).playSeason(anyCollection(), anyGameStateConsumer());
		});
	}

	@Test
	void acceptLongName() throws IOException, InterruptedException {
		infiniteSeason(tournament);
		assertTimeoutPreemptively(TIMEOUT, () -> {
			String longName = nameOfLength(MAX_CLIENT_NAME_LENGTH);
			DummyClient client = new DummyClient(longName, "localhost", serverPort);
			client.assertReceived("Welcome " + longName);
			verify(tournament, timesWithTimeout(0)).playSeason(anyCollection(), anyGameStateConsumer());
		});
	}

	@Test
	void denyTooLongName() throws IOException, InterruptedException {
		infiniteSeason(tournament);
		assertTimeoutPreemptively(TIMEOUT, () -> {
			String longName = nameOfLength(MAX_CLIENT_NAME_LENGTH + 1);
			DummyClient client = new DummyClient(longName, "localhost", serverPort);
			client.assertReceived("NAME_TOO_LONG");
			verify(tournament, timesWithTimeout(0)).playSeason(anyCollection(), anyGameStateConsumer());
		});
	}

	@Test
	void denyEmptyName() throws IOException, InterruptedException {
		infiniteSeason(tournament);
		assertTimeoutPreemptively(TIMEOUT, () -> {
			String emptyName = "";
			DummyClient client = new DummyClient(emptyName, "localhost", serverPort);
			client.assertReceived("NO_NAME_GIVEN");
			verify(tournament, timesWithTimeout(0)).playSeason(anyCollection(), anyGameStateConsumer());
		});
	}

	@Test
	void afterSecondClientConnectsTheTournamentIsStarted() throws IOException, InterruptedException {
		infiniteSeason(tournament);
		assertTimeoutPreemptively(TIMEOUT, () -> {
			DummyClient client1 = new DummyClient("1", "localhost", serverPort);
			DummyClient client2 = new DummyClient("2", "localhost", serverPort);

			assertWelcomed(client1);
			assertWelcomed(client2);
			verify(tournament, timesWithTimeout(1)).playSeason(anyCollection(), anyGameStateConsumer());
		});
	}

	@Test
	void seasonWillOnlyBeStartedIfTwoOreMorePlayersAreRegistered() throws IOException, InterruptedException {
		infiniteSeason(tournament);
		assertTimeoutPreemptively(TIMEOUT, () -> {
			new DummyClient("1", "localhost", serverPort);
			new DummyClient("2", "localhost", serverPort);

			verify(tournament, timesWithTimeout(1)).playSeason(anyCollection(), anyGameStateConsumer());

			// while season is running others can register
			DummyClient client3 = new DummyClient("3", "localhost", serverPort);
			client3.assertReceived("Welcome 3");
		});
	}

	@Test
	void canUnregister() throws IOException, InterruptedException {
		infiniteSeason(tournament);
		assertTimeoutPreemptively(TIMEOUT, () -> {
			DummyClient client = new DummyClient("1", "localhost", serverPort);
			client.unregister();
			client.assertReceived("Welcome 1", "UNREGISTERED");
		});
	}

	@Test
	void whenDeregisteringNoNextSeasonIsStarted() throws IOException, InterruptedException {
		AtomicInteger seasonsStarted = new AtomicInteger(0);
		doAnswer(s -> {
			seasonsStarted.incrementAndGet();
			MILLISECONDS.sleep(25);
			return Stream.empty();
		}).when(tournament).playSeason(anyCollection(), anyGameStateConsumer());
		assertTimeoutPreemptively(TIMEOUT, () -> {
			new PlayingClient("1", "localhost", serverPort, -1);
			DummyClient client2 = new PlayingClient("2", "localhost", serverPort, -1);

			verify(tournament, timesWithTimeout(1)).playSeason(anyCollection(), anyGameStateConsumer());

			// while season is running others can register
			DummyClient client3 = new PlayingClient("3", "localhost", serverPort, -1);
			assertWelcomed(client3);

			// TODO signal to Mockito answer to delay until...

			client3.unregister();
			client2.unregister();

			assertWelcomed(client3);
			assertWelcomed(client2);

			await().until(() -> client3.getReceived(), hasItems("Welcome 3", "UNREGISTERED"));
			await().until(() -> client2.getReceived(), hasItems("Welcome 2", "UNREGISTERED"));

			int seasonsStartedBeforeUnregister = seasonsStarted.get();

			// TODO ...here

			// TODO eliminate wait
			SECONDS.sleep(3);
			assertThat(seasonsStarted.get(), is(seasonsStartedBeforeUnregister));
		});
	}

//	TODO joining with long runner
//	TODO test when returning a wrong UUID the next message must be working

	@Test
	void aReRegisterdClientIsNotANewPlayer() throws IOException {
		infiniteSeason(tournament);
		assertTimeoutPreemptively(TIMEOUT, () -> {
			String nameToReuse = "1";
			DummyClient client = new DummyClient(nameToReuse, "localhost", serverPort);
			client.assertReceived("Welcome " + nameToReuse);
			DummyClient newClientWithSameTokenFromSameIP = new DummyClient(nameToReuse, "localhost", serverPort);
			newClientWithSameTokenFromSameIP.assertReceived("Welcome " + nameToReuse);

			verify(tournament, timesWithTimeout(0)).playSeason(anyCollection(), anyGameStateConsumer());
			client.unregister();
			newClientWithSameTokenFromSameIP.unregister();
		});
	}

	private void assertWelcomed(DummyClient client) throws InterruptedException {
		await().until(() -> client.getReceived(), hasItem("Welcome " + client.getName()));
	}

	private void infiniteSeason(Tournament mock) {
		doAnswer(s -> {
			while (true) {
				DAYS.sleep(Long.MAX_VALUE);
			}
		}).when(tournament).playSeason(anyCollection(), anyGameStateConsumer());
	}

	private String nameOfLength(int len) {
		return IntStream.range(0, len).mapToObj(i -> "X").collect(joining());
	}

	private VerificationMode timesWithTimeout(int times) {
		return timeout(SECONDS.toMillis(5)).times(times);
	}

	private Consumer<GameState> anyGameStateConsumer() {
		return anyConsumer(GameState.class);
	}

	@SuppressWarnings("unchecked")
	private <T> Consumer<T> anyConsumer(Class<T> clazz) {
		return any(Consumer.class);
	}

	private static int freePort() {
		try {
			ServerSocket socket = new ServerSocket(0);
			socket.close();
			return socket.getLocalPort();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

}

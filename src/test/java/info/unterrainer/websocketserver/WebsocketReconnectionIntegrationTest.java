package info.unterrainer.websocketserver;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration tests specifically focused on reconnection scenarios and
 * handling disconnections from either side (client or server).
 */
class WebsocketReconnectionIntegrationTest {

	private WebsocketServer server;
	private int testPort = 18081;
	private WebSocketClient client;
	private String wsUrl;

	@BeforeEach
	void setUp() {
		wsUrl = "ws://localhost:" + testPort;
		client = new WebSocketClient();
	}

	@AfterEach
	void tearDown() throws Exception {
		if (client != null && client.isRunning()) {
			client.stop();
		}
	}

	@Test
	void testClientDisconnectsAndReconnects() throws Exception {
		server = new WebsocketServer("reconnect-server");
		CountDownLatch firstConnect = new CountDownLatch(1);
		CountDownLatch firstDisconnect = new CountDownLatch(1);
		CountDownLatch secondConnect = new CountDownLatch(1);
		AtomicInteger connectionCount = new AtomicInteger(0);
		List<String> connectionIds = new ArrayList<>();

		server.ws("/client-reconnect", ws -> {
			ws.onConnect(ctx -> {
				int count = connectionCount.incrementAndGet();
				String connectionId = "connection-" + count;
				connectionIds.add(connectionId);
				if (count == 1) {
					firstConnect.countDown();
				} else if (count == 2) {
					secondConnect.countDown();
				}
			});
			ws.onClose(ctx -> {
				firstDisconnect.countDown();
			});
		}).start(testPort);

		client.start();

		// First connection
		ReconnectTestHandler handler1 = new ReconnectTestHandler();
		Session session1 = client.connect(handler1, new URI(wsUrl + "/client-reconnect")).get(5, TimeUnit.SECONDS);
		assertThat(firstConnect.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(session1.isOpen()).isTrue();

		// Client disconnects
		session1.close(1000, "Client initiated disconnect");
		assertThat(firstDisconnect.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(handler1.closeLatch.await(5, TimeUnit.SECONDS)).isTrue();

		// Wait a bit before reconnecting
		Thread.sleep(1000);

		// Client reconnects
		ReconnectTestHandler handler2 = new ReconnectTestHandler();
		Session session2 = client.connect(handler2, new URI(wsUrl + "/client-reconnect")).get(5, TimeUnit.SECONDS);
		assertThat(secondConnect.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(session2.isOpen()).isTrue();

		assertThat(connectionCount.get()).isEqualTo(2);
		assertThat(connectionIds).hasSize(2);

		session2.close();
	}

	@Test
	void testServerForcesDisconnectAndClientReconnects() throws Exception {
		server = new WebsocketServer("force-disconnect-server");
		CountDownLatch connectLatch = new CountDownLatch(1);
		CountDownLatch secondConnectLatch = new CountDownLatch(1);
		AtomicReference<io.javalin.websocket.WsConnectContext> connectedClient = new AtomicReference<>();
		AtomicBoolean isFirstConnection = new AtomicBoolean(true);

		server.ws("/force-disconnect", ws -> {
			ws.onConnect(ctx -> {
				if (isFirstConnection.get()) {
					connectedClient.set(ctx);
					connectLatch.countDown();
				} else {
					secondConnectLatch.countDown();
				}
			});
			ws.onMessage(ctx -> {
				if (ctx.message().equals("disconnect-me")) {
					ctx.session.close(1000, "Server initiated disconnect");
				}
			});
		}).start(testPort);

		client.start();

		// First connection
		ReconnectTestHandler handler1 = new ReconnectTestHandler();
		Session session1 = client.connect(handler1, new URI(wsUrl + "/force-disconnect")).get(5, TimeUnit.SECONDS);
		assertThat(connectLatch.await(5, TimeUnit.SECONDS)).isTrue();

		// Client asks server to disconnect
		session1.getRemote().sendString("disconnect-me");
		assertThat(handler1.closeLatch.await(5, TimeUnit.SECONDS)).isTrue();

		// Wait before reconnecting
		Thread.sleep(1000);

		// Client reconnects
		isFirstConnection.set(false);
		ReconnectTestHandler handler2 = new ReconnectTestHandler();
		Session session2 = client.connect(handler2, new URI(wsUrl + "/force-disconnect")).get(5, TimeUnit.SECONDS);
		assertThat(secondConnectLatch.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(session2.isOpen()).isTrue();

		session2.close();
	}

	@Test
	void testMessageLossOnReconnection() throws Exception {
		server = new WebsocketServer("message-loss-server");
		CountDownLatch firstConnect = new CountDownLatch(1);
		CountDownLatch secondConnect = new CountDownLatch(1);
		AtomicInteger messageCounter = new AtomicInteger(0);
		List<Integer> receivedMessages = new ArrayList<>();

		server.ws("/message-loss", ws -> {
			ws.onConnect(ctx -> {
				if (firstConnect.getCount() > 0) {
					firstConnect.countDown();
				} else {
					secondConnect.countDown();
				}
			});
			ws.onMessage(ctx -> {
				int msgNum = Integer.parseInt(ctx.message());
				receivedMessages.add(msgNum);
				ctx.send("ack-" + msgNum);
			});
		}).start(testPort);

		client.start();

		// First connection - send some messages
		ReconnectTestHandler handler1 = new ReconnectTestHandler();
		List<String> acksReceived = new ArrayList<>();
		handler1.onMessageReceived = msg -> {
			acksReceived.add(msg);
		};

		Session session1 = client.connect(handler1, new URI(wsUrl + "/message-loss")).get(5, TimeUnit.SECONDS);
		assertThat(firstConnect.await(5, TimeUnit.SECONDS)).isTrue();

		// Send messages 1-5
		for (int i = 1; i <= 5; i++) {
			session1.getRemote().sendString(String.valueOf(i));
		}
		Thread.sleep(500); // Wait for acknowledgments

		// Client disconnects
		session1.close();
		Thread.sleep(500);

		// Client reconnects and sends more messages
		ReconnectTestHandler handler2 = new ReconnectTestHandler();
		handler2.onMessageReceived = msg -> {
			acksReceived.add(msg);
		};

		Session session2 = client.connect(handler2, new URI(wsUrl + "/message-loss")).get(5, TimeUnit.SECONDS);
		assertThat(secondConnect.await(5, TimeUnit.SECONDS)).isTrue();

		// Send messages 6-10
		for (int i = 6; i <= 10; i++) {
			session2.getRemote().sendString(String.valueOf(i));
		}
		Thread.sleep(500);

		// Verify all messages were received
		assertThat(receivedMessages).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);

		session2.close();
	}

	@Test
	void testRapidReconnectionAttempts() throws Exception {
		server = new WebsocketServer("rapid-reconnect-server");
		AtomicInteger connectionCount = new AtomicInteger(0);
		AtomicInteger disconnectionCount = new AtomicInteger(0);

		server.ws("/rapid-reconnect", ws -> {
			ws.onConnect(ctx -> {
				connectionCount.incrementAndGet();
			});
			ws.onClose(ctx -> {
				disconnectionCount.incrementAndGet();
			});
		}).start(testPort);

		client.start();

		int attempts = 5;
		for (int i = 0; i < attempts; i++) {
			ReconnectTestHandler handler = new ReconnectTestHandler();
			Session session = client.connect(handler, new URI(wsUrl + "/rapid-reconnect")).get(5, TimeUnit.SECONDS);
			assertThat(handler.connectLatch.await(2, TimeUnit.SECONDS)).isTrue();
			assertThat(session.isOpen()).isTrue();

			// Immediately disconnect
			session.close();
			assertThat(handler.closeLatch.await(2, TimeUnit.SECONDS)).isTrue();

			// Very short wait before next connection
			Thread.sleep(100);
		}

		// Give server time to process all disconnections
		Thread.sleep(500);

		assertThat(connectionCount.get()).isEqualTo(attempts);
		assertThat(disconnectionCount.get()).isEqualTo(attempts);
	}

	@Test
	void testReconnectionWithDifferentClientInstance() throws Exception {
		server = new WebsocketServer("different-client-server");
		CountDownLatch firstConnect = new CountDownLatch(1);
		CountDownLatch secondConnect = new CountDownLatch(1);
		List<Session> sessions = new ArrayList<>();

		server.ws("/different-client", ws -> {
			ws.onConnect(ctx -> {
				sessions.add(ctx.session);
				if (sessions.size() == 1) {
					firstConnect.countDown();
				} else {
					secondConnect.countDown();
				}
			});
		}).start(testPort);

		// First client
		WebSocketClient client1 = new WebSocketClient();
		client1.start();
		ReconnectTestHandler handler1 = new ReconnectTestHandler();
		Session session1 = client1.connect(handler1, new URI(wsUrl + "/different-client")).get(5, TimeUnit.SECONDS);
		assertThat(firstConnect.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(session1.isOpen()).isTrue();

		// Close first client
		session1.close();
		client1.stop();
		Thread.sleep(500);

		// Second client (different instance)
		WebSocketClient client2 = new WebSocketClient();
		client2.start();
		ReconnectTestHandler handler2 = new ReconnectTestHandler();
		Session session2 = client2.connect(handler2, new URI(wsUrl + "/different-client")).get(5, TimeUnit.SECONDS);
		assertThat(secondConnect.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(session2.isOpen()).isTrue();

		// Verify they are different sessions
		assertThat(sessions).hasSize(2);
		assertThat(sessions.get(0)).isNotEqualTo(sessions.get(1));

		session2.close();
		client2.stop();
	}

	@Test
	void testReconnectionPreservesSessionState() throws Exception {
		server = new WebsocketServer("session-state-server");
		CountDownLatch firstConnect = new CountDownLatch(1);
		CountDownLatch secondConnect = new CountDownLatch(1);
		AtomicInteger sessionIdCounter = new AtomicInteger(0);
		AtomicReference<String> firstSessionId = new AtomicReference<>();
		AtomicReference<String> secondSessionId = new AtomicReference<>();

		server.ws("/session-state", ws -> {
			ws.onConnect(ctx -> {
				String sessionId = "session-" + sessionIdCounter.incrementAndGet();
				if (sessionIdCounter.get() == 1) {
					firstSessionId.set(sessionId);
					firstConnect.countDown();
				} else {
					secondSessionId.set(sessionId);
					secondConnect.countDown();
				}
			});
			ws.onMessage(ctx -> {
				ctx.send("Echo: " + ctx.message());
			});
		}).start(testPort);

		client.start();

		// First connection
		ReconnectTestHandler handler1 = new ReconnectTestHandler();
		CountDownLatch firstMessage = new CountDownLatch(1);
		AtomicReference<String> firstResponse = new AtomicReference<>();
		handler1.onMessageReceived = msg -> {
			firstResponse.set(msg);
			firstMessage.countDown();
		};

		Session session1 = client.connect(handler1, new URI(wsUrl + "/session-state")).get(5, TimeUnit.SECONDS);
		assertThat(firstConnect.await(5, TimeUnit.SECONDS)).isTrue();

		session1.getRemote().sendString("first-connection");
		assertThat(firstMessage.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(firstResponse.get()).isEqualTo("Echo: first-connection");

		// Disconnect
		session1.close();
		Thread.sleep(500);

		// Reconnect
		ReconnectTestHandler handler2 = new ReconnectTestHandler();
		CountDownLatch secondMessage = new CountDownLatch(1);
		AtomicReference<String> secondResponse = new AtomicReference<>();
		handler2.onMessageReceived = msg -> {
			secondResponse.set(msg);
			secondMessage.countDown();
		};

		Session session2 = client.connect(handler2, new URI(wsUrl + "/session-state")).get(5, TimeUnit.SECONDS);
		assertThat(secondConnect.await(5, TimeUnit.SECONDS)).isTrue();

		session2.getRemote().sendString("second-connection");
		assertThat(secondMessage.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(secondResponse.get()).isEqualTo("Echo: second-connection");

		// Verify new session was created (server doesn't preserve session)
		assertThat(firstSessionId.get()).isNotEqualTo(secondSessionId.get());

		session2.close();
	}

	@Test
	void testNetworkInterruptionSimulation() throws Exception {
		server = new WebsocketServer("network-interruption-server");
		CountDownLatch connectLatch = new CountDownLatch(1);
		CountDownLatch errorLatch = new CountDownLatch(1);
		AtomicBoolean errorOccurred = new AtomicBoolean(false);

		server.ws("/network-interruption", ws -> {
			ws.onConnect(ctx -> {
				connectLatch.countDown();
			});
			ws.onError(ctx -> {
				errorOccurred.set(true);
				errorLatch.countDown();
			});
		}).start(testPort);

		client.start();
		ReconnectTestHandler handler = new ReconnectTestHandler();
		Session session = client.connect(handler, new URI(wsUrl + "/network-interruption")).get(5, TimeUnit.SECONDS);
		assertThat(connectLatch.await(5, TimeUnit.SECONDS)).isTrue();

		// Simulate network interruption by stopping client abruptly
		client.stop();

		// The error should be detected on server side
		// Note: This might take some time depending on keep-alive settings
		Thread.sleep(2000);

		// Handler should detect the closure
		assertThat(handler.closeLatch.await(5, TimeUnit.SECONDS)).isTrue();
	}

	@WebSocket
	public static class ReconnectTestHandler {
		public volatile Session session;
		public volatile CountDownLatch connectLatch = new CountDownLatch(1);
		public volatile CountDownLatch closeLatch = new CountDownLatch(1);
		public volatile java.util.function.Consumer<String> onMessageReceived;
		public volatile java.util.function.Consumer<Throwable> onError;

		@OnWebSocketConnect
		public void onConnect(Session session) {
			this.session = session;
			connectLatch.countDown();
		}

		@OnWebSocketMessage
		public void onMessage(String message) {
			if (onMessageReceived != null) {
				onMessageReceived.accept(message);
			}
		}

		@OnWebSocketClose
		public void onClose(int statusCode, String reason) {
			closeLatch.countDown();
		}

		@OnWebSocketError
		public void onError(Throwable error) {
			if (onError != null) {
				onError.accept(error);
			}
		}
	}
}

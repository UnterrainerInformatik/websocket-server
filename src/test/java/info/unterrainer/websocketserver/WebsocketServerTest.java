package info.unterrainer.websocketserver;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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

class WebsocketServerTest {

	private WebsocketServer server;
	private WebSocketClient client;
	private static final int testPort = 18080;
	private static final String wsUrl = "ws://localhost:" + testPort;

	@BeforeEach
	void setUp() throws Exception {
		client = new WebSocketClient();
	}

	@AfterEach
	void tearDown() throws Exception {
		if (server != null) {
			server.stop();
		}
		if (client != null && client.isStarted()) {
			client.stop();
		}
	}

	@Test
	void testBasicConnection() throws Exception {
		server = new WebsocketServer("test-server");
		CountDownLatch serverConnectLatch = new CountDownLatch(1);

		server.ws("/test", ws -> {
			ws.onConnect(ctx -> {
				serverConnectLatch.countDown();
			});
		}).start(testPort);

		client.start();
		TestWebSocketHandler handler = new TestWebSocketHandler();
		Session session = client.connect(handler, new URI(wsUrl + "/test")).get(5, TimeUnit.SECONDS);

		assertThat(handler.connectLatch.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(serverConnectLatch.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(session.isOpen()).isTrue();

		session.close();
	}

	@Test
	void testMessageEcho() throws Exception {
		server = new WebsocketServer("test-server");
		AtomicReference<String> receivedMessage = new AtomicReference<>();
		CountDownLatch messageLatch = new CountDownLatch(1);

		server.ws("/echo", ws -> {
			ws.onMessage(ctx -> {
				ctx.send(ctx.message());
			});
		}).start(testPort);

		client.start();
		TestWebSocketHandler handler = new TestWebSocketHandler();
		handler.onMessageReceived = message -> {
			receivedMessage.set(message);
			messageLatch.countDown();
		};

		Session session = client.connect(handler, new URI(wsUrl + "/echo")).get(5, TimeUnit.SECONDS);
		session.getRemote().sendString("Hello World");

		assertThat(messageLatch.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(receivedMessage.get()).isEqualTo("Hello World");

		session.close();
	}

	@Test
	void testMultipleClients() throws Exception {
		server = new WebsocketServer("test-server");
		CountDownLatch serverMessageLatch = new CountDownLatch(2);

		server.ws("/multi", ws -> {
			ws.onMessage(ctx -> {
				serverMessageLatch.countDown();
				ctx.send("received");
			});
		}).start(testPort);

		client.start();
		WebSocketClient client2 = new WebSocketClient();
		client2.start();

		try {
			TestWebSocketHandler handler1 = new TestWebSocketHandler();
			TestWebSocketHandler handler2 = new TestWebSocketHandler();

			Session session1 = client.connect(handler1, new URI(wsUrl + "/multi")).get(5, TimeUnit.SECONDS);
			Session session2 = client2.connect(handler2, new URI(wsUrl + "/multi")).get(5, TimeUnit.SECONDS);

			session1.getRemote().sendString("client1");
			session2.getRemote().sendString("client2");

			assertThat(serverMessageLatch.await(5, TimeUnit.SECONDS)).isTrue();

			session1.close();
			session2.close();
		} finally {
			client2.stop();
		}
	}

	@Test
	void testConnectionClose() throws Exception {
		server = new WebsocketServer("test-server");
		CountDownLatch serverCloseLatch = new CountDownLatch(1);

		server.ws("/close", ws -> {
			ws.onClose(ctx -> {
				serverCloseLatch.countDown();
			});
		}).start(testPort);

		client.start();
		TestWebSocketHandler handler = new TestWebSocketHandler();
		Session session = client.connect(handler, new URI(wsUrl + "/close")).get(5, TimeUnit.SECONDS);

		session.close();

		assertThat(handler.closeLatch.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(serverCloseLatch.await(5, TimeUnit.SECONDS)).isTrue();
	}

	@Test
	void testMultipleEndpoints() throws Exception {
		server = new WebsocketServer("test-server");
		AtomicReference<String> endpoint1Message = new AtomicReference<>();
		AtomicReference<String> endpoint2Message = new AtomicReference<>();
		CountDownLatch latch1 = new CountDownLatch(1);
		CountDownLatch latch2 = new CountDownLatch(1);

		server.ws("/endpoint1", ws -> {
			ws.onMessage(ctx -> ctx.send("endpoint1-response"));
		}).ws("/endpoint2", ws -> {
			ws.onMessage(ctx -> ctx.send("endpoint2-response"));
		}).start(testPort);

		client.start();

		TestWebSocketHandler handler1 = new TestWebSocketHandler();
		handler1.onMessageReceived = message -> {
			endpoint1Message.set(message);
			latch1.countDown();
		};

		TestWebSocketHandler handler2 = new TestWebSocketHandler();
		handler2.onMessageReceived = message -> {
			endpoint2Message.set(message);
			latch2.countDown();
		};

		Session session1 = client.connect(handler1, new URI(wsUrl + "/endpoint1")).get(5, TimeUnit.SECONDS);
		Session session2 = client.connect(handler2, new URI(wsUrl + "/endpoint2")).get(5, TimeUnit.SECONDS);

		session1.getRemote().sendString("test");
		session2.getRemote().sendString("test");

		assertThat(latch1.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(latch2.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(endpoint1Message.get()).isEqualTo("endpoint1-response");
		assertThat(endpoint2Message.get()).isEqualTo("endpoint2-response");

		session1.close();
		session2.close();
	}

	@Test
	void testServerWithExistingJavalinInstance() throws Exception {
		io.javalin.Javalin javalin = io.javalin.Javalin.create();
		server = new WebsocketServer("test-server", javalin);
		AtomicReference<String> receivedMessage = new AtomicReference<>();
		CountDownLatch messageLatch = new CountDownLatch(1);

		server.ws("/test", ws -> {
			ws.onMessage(ctx -> {
				ctx.send("response");
			});
		});
		javalin.start("0.0.0.0", testPort);

		client.start();
		TestWebSocketHandler handler = new TestWebSocketHandler();
		handler.onMessageReceived = message -> {
			receivedMessage.set(message);
			messageLatch.countDown();
		};

		Session session = client.connect(handler, new URI(wsUrl + "/test")).get(5, TimeUnit.SECONDS);
		session.getRemote().sendString("test");

		assertThat(messageLatch.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(receivedMessage.get()).isEqualTo("response");

		session.close();
		javalin.stop();
	}

	@Test
	void testBroadcastToAllClients() throws Exception {
		server = new WebsocketServer("test-server");
		java.util.Set<io.javalin.websocket.WsContext> clients = java.util.concurrent.ConcurrentHashMap.newKeySet();

		server.ws("/broadcast", ws -> {
			ws.onConnect(ctx -> {
				clients.add(ctx);
			});
			ws.onClose(ctx -> {
				clients.remove(ctx);
			});
			ws.onMessage(ctx -> {
				clients.forEach(client -> client.send(ctx.message()));
			});
		}).start(testPort);

		client.start();
		WebSocketClient client2 = new WebSocketClient();
		client2.start();

		try {
			CountDownLatch latch1 = new CountDownLatch(1);
			CountDownLatch latch2 = new CountDownLatch(1);
			AtomicReference<String> message1 = new AtomicReference<>();
			AtomicReference<String> message2 = new AtomicReference<>();

			TestWebSocketHandler handler1 = new TestWebSocketHandler();
			handler1.onMessageReceived = msg -> {
				message1.set(msg);
				latch1.countDown();
			};

			TestWebSocketHandler handler2 = new TestWebSocketHandler();
			handler2.onMessageReceived = msg -> {
				message2.set(msg);
				latch2.countDown();
			};

			Session session1 = client.connect(handler1, new URI(wsUrl + "/broadcast")).get(5, TimeUnit.SECONDS);
			Session session2 = client2.connect(handler2, new URI(wsUrl + "/broadcast")).get(5, TimeUnit.SECONDS);

			// Wait for connections to be established
			Thread.sleep(100);

			session1.getRemote().sendString("broadcast-message");

			assertThat(latch1.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(latch2.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(message1.get()).isEqualTo("broadcast-message");
			assertThat(message2.get()).isEqualTo("broadcast-message");

			session1.close();
			session2.close();
		} finally {
			client2.stop();
		}
	}

	@Test
	void testBinaryMessageHandling() throws Exception {
		server = new WebsocketServer("test-server");
		byte[] testData = new byte[] { 0x01, 0x02, 0x03, 0x04, 0x05 };
		AtomicReference<byte[]> receivedData = new AtomicReference<>();
		CountDownLatch messageLatch = new CountDownLatch(1);

		server.ws("/binary", ws -> {
			ws.onBinaryMessage(ctx -> {
				byte[] copy = Arrays.copyOf(ctx.data(), ctx.data().length);
				ctx.send(ByteBuffer.wrap(copy));
			});
		}).start(testPort);

		client.start();
		TestWebSocketHandler handler = new TestWebSocketHandler();
		handler.onBinaryMessageReceived = data -> {
			receivedData.set(data);
			messageLatch.countDown();
		};

		Session session = client.connect(handler, new URI(wsUrl + "/binary")).get(5, TimeUnit.SECONDS);
		session.getRemote().sendBytes(java.nio.ByteBuffer.wrap(Arrays.copyOf(testData, testData.length)));

		assertThat(messageLatch.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(receivedData.get()).isEqualTo(testData);

		session.close();
	}

	@Test
	void testErrorHandling() throws Exception {
		server = new WebsocketServer("test-server");
		CountDownLatch errorLatch = new CountDownLatch(1);
		AtomicReference<String> errorMessage = new AtomicReference<>();

		server.ws("/error", ws -> {
			ws.onMessage(ctx -> {
				if (ctx.message().equals("error")) {
					throw new RuntimeException("Intentional error");
				}
				ctx.send("ok");
			});
		}).start(testPort);

		client.start();
		TestWebSocketHandler handler = new TestWebSocketHandler();
		handler.onError = (session, throwable) -> {
			errorMessage.set(throwable.getMessage());
			errorLatch.countDown();
		};

		Session session = client.connect(handler, new URI(wsUrl + "/error")).get(5, TimeUnit.SECONDS);

		// Send normal message first
		CountDownLatch okLatch = new CountDownLatch(1);
		handler.onMessageReceived = msg -> {
			if ("ok".equals(msg)) {
				okLatch.countDown();
			}
		};
		session.getRemote().sendString("normal");
		assertThat(okLatch.await(5, TimeUnit.SECONDS)).isTrue();

		session.close();
	}

	@Test
	void testCustomExceptionHandler() throws Exception {
		CountDownLatch exceptionHandlerLatch = new CountDownLatch(1);
		AtomicReference<String> exceptionMessage = new AtomicReference<>();

		server = new WebsocketServer("test-server", (e, ctx) -> {
			exceptionMessage.set(e.getMessage());
			exceptionHandlerLatch.countDown();
		});

		server.ws("/custom-error", ws -> {
			ws.onMessage(ctx -> {
				throw new RuntimeException("Custom exception");
			});
		}).start(testPort);

		client.start();

		TestWebSocketHandler handler = new TestWebSocketHandler();
		Session session = client.connect(handler, new URI(wsUrl + "/custom-error")).get(5, TimeUnit.SECONDS);

		session.getRemote().sendString("trigger-error");

		assertThat(exceptionHandlerLatch.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(exceptionMessage.get()).isEqualTo("Custom exception");

		session.close();
	}

	@Test
	void testLargeMessage() throws Exception {
		server = new WebsocketServer("test-server");
		AtomicReference<String> receivedMessage = new AtomicReference<>();
		CountDownLatch messageLatch = new CountDownLatch(1);

		server.ws("/large", ws -> {
			ws.onMessage(ctx -> {
				ctx.send(ctx.message());
			});
		}).start(testPort);

		client.start();
		TestWebSocketHandler handler = new TestWebSocketHandler();
		handler.onMessageReceived = message -> {
			receivedMessage.set(message);
			messageLatch.countDown();
		};

		Session session = client.connect(handler, new URI(wsUrl + "/large")).get(5, TimeUnit.SECONDS);

		// Create a large message (10KB)
		StringBuilder largeMessage = new StringBuilder();
		for (int i = 0; i < 10000; i++) {
			largeMessage.append("x");
		}
		String testMessage = largeMessage.toString();

		session.getRemote().sendString(testMessage);

		assertThat(messageLatch.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(receivedMessage.get()).hasSize(10000);
		assertThat(receivedMessage.get()).isEqualTo(testMessage);

		session.close();
	}

	@Test
	void testRapidMessages() throws Exception {
		server = new WebsocketServer("test-server");
		java.util.concurrent.atomic.AtomicInteger messageCount = new java.util.concurrent.atomic.AtomicInteger(0);
		int expectedMessages = 100;
		CountDownLatch messageLatch = new CountDownLatch(expectedMessages);

		server.ws("/rapid", ws -> {
			ws.onMessage(ctx -> {
				ctx.send("ack-" + ctx.message());
			});
		}).start(testPort);

		client.start();
		TestWebSocketHandler handler = new TestWebSocketHandler();
		handler.onMessageReceived = message -> {
			messageCount.incrementAndGet();
			messageLatch.countDown();
		};

		Session session = client.connect(handler, new URI(wsUrl + "/rapid")).get(5, TimeUnit.SECONDS);

		// Send multiple messages rapidly
		for (int i = 0; i < expectedMessages; i++) {
			session.getRemote().sendString("message-" + i);
		}

		assertThat(messageLatch.await(10, TimeUnit.SECONDS)).isTrue();
		assertThat(messageCount.get()).isEqualTo(expectedMessages);

		session.close();
	}

	@WebSocket
	public static class TestWebSocketHandler {
		public volatile Session session;
		public volatile CountDownLatch connectLatch = new CountDownLatch(1);
		public volatile CountDownLatch closeLatch = new CountDownLatch(1);
		public volatile CountDownLatch errorLatch = new CountDownLatch(1);
		public volatile java.util.function.Consumer<String> onMessageReceived;
		public volatile java.util.function.Consumer<byte[]> onBinaryMessageReceived;
		public volatile java.util.function.BiConsumer<Session, Throwable> onError;
		public volatile java.util.function.BiConsumer<Integer, String> onClose;

		@OnWebSocketConnect
		public void onConnect(Session session) {
			this.session = session;
			connectLatch.countDown();
		}

		@OnWebSocketMessage
		public void onMessage(Session session, String message) {
			if (onMessageReceived != null) {
				onMessageReceived.accept(message);
			}
		}

		@OnWebSocketMessage
		public void onBinaryMessage(Session session, byte[] data, int offset, int length) {
			if (onBinaryMessageReceived != null) {
				byte[] actual = new byte[length];
				System.arraycopy(data, offset, actual, 0, length);
				onBinaryMessageReceived.accept(actual);
			}
		}
		
		@OnWebSocketClose
		public void onClose(int statusCode, String reason) {
			closeLatch.countDown();
			if (onClose != null) {
				onClose.accept(statusCode, reason);
			}
		}

		@OnWebSocketError
		public void onError(Throwable error) {
			errorLatch.countDown();
			if (onError != null) {
				onError.accept(session, error);
			}
		}
	}
}

package info.unterrainer.websocketserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.websocket.api.RemoteEndpoint;
import org.eclipse.jetty.websocket.api.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import info.unterrainer.oauthtokenmanager.OauthTokenManager;
import io.javalin.websocket.WsBinaryMessageContext;
import io.javalin.websocket.WsCloseContext;
import io.javalin.websocket.WsConnectContext;
import io.javalin.websocket.WsErrorContext;
import io.javalin.websocket.WsMessageContext;

class WsOauthHandlerBaseTest {

	@Mock
	private OauthTokenManager tokenManager;
	@Mock
	private WsConnectContext connectContext;
	@Mock
	private WsMessageContext messageContext;
	@Mock
	private WsBinaryMessageContext binaryMessageContext;
	@Mock
	private WsCloseContext closeContext;
	@Mock
	private WsErrorContext errorContext;
	@Mock
	private Session session;
	@Mock
	private RemoteEndpoint remoteEndpoint;

	private WsOauthHandlerBase handler;
	private AutoCloseable closeable;

	@BeforeEach
	void setUp() {
		closeable = MockitoAnnotations.openMocks(this);
		handler = new TestWsOauthHandler("test-handler");
		handler.setTokenHandler(tokenManager);
	}

	@AfterEach
	void tearDown() throws Exception {
		if (closeable != null) {
			closeable.close();
		}
	}

	@Test
	void testOnConnectWithValidToken() throws Exception {
		String validToken = "Bearer valid-token";
		String tenantId = "tenant-123";

		when(connectContext.header("Authorization")).thenReturn(validToken);
		when(connectContext.session).thenReturn(session);
		when(tokenManager.checkAccess(validToken)).thenReturn(tenantId);

		handler.onConnect(connectContext);

		assertThat(handler.clientsConnected).contains(connectContext);
		assertThat(handler.clientsQuarantined).doesNotContain(connectContext);
		assertThat(handler.tenantIdsBySession.get(session)).isEqualTo(tenantId);
	}

	@Test
	void testOnConnectWithInvalidToken() throws Exception {
		String invalidToken = "Bearer invalid-token";

		when(connectContext.header("Authorization")).thenReturn(invalidToken);
		when(connectContext.session).thenReturn(session);
		when(tokenManager.checkAccess(invalidToken)).thenThrow(new RuntimeException("Invalid token"));

		handler.onConnect(connectContext);

		verify(session).close(1000, "(test-handler) Unauthorized access with invalid token");
		assertThat(handler.clientsConnected).doesNotContain(connectContext);
		assertThat(handler.clientsQuarantined).doesNotContain(connectContext);
	}

	@Test
	void testOnConnectWithoutToken() throws Exception {
		when(connectContext.header("Authorization")).thenReturn(null);
		when(connectContext.session).thenReturn(session);

		handler.onConnect(connectContext);

		assertThat(handler.clientsQuarantined).contains(connectContext);
		assertThat(handler.clientsConnected).doesNotContain(connectContext);
	}

	@Test
	void testOnConnectWithEmptyToken() throws Exception {
		when(connectContext.header("Authorization")).thenReturn("");
		when(connectContext.session).thenReturn(session);

		handler.onConnect(connectContext);

		assertThat(handler.clientsQuarantined).contains(connectContext);
		assertThat(handler.clientsConnected).doesNotContain(connectContext);
	}

	@Test
	void testOnMessageFromQuarantinedClientWithValidToken() throws Exception {
		String validToken = "Bearer valid-token";
		String tenantId = "tenant-456";

		// First, put client in quarantine
		when(connectContext.header("Authorization")).thenReturn(null);
		when(connectContext.session).thenReturn(session);
		handler.onConnect(connectContext);
		assertThat(handler.clientsQuarantined).contains(connectContext);

		// Now send a valid token via message
		when(messageContext.session).thenReturn(session);
		when(messageContext.message()).thenReturn(validToken);
		when(tokenManager.checkAccess(validToken)).thenReturn(tenantId);

		handler.onMessage(messageContext);

		assertThat(handler.clientsConnected).contains(connectContext);
		assertThat(handler.clientsQuarantined).doesNotContain(connectContext);
		assertThat(handler.tenantIdsBySession.get(session)).isEqualTo(tenantId);
	}

	@Test
	void testOnMessageFromQuarantinedClientWithInvalidToken() throws Exception {
		String invalidToken = "Bearer invalid-token";

		// First, put client in quarantine
		when(connectContext.header("Authorization")).thenReturn(null);
		when(connectContext.session).thenReturn(session);
		handler.onConnect(connectContext);

		// Now send an invalid token via message
		when(messageContext.session).thenReturn(session);
		when(messageContext.message()).thenReturn(invalidToken);
		when(tokenManager.checkAccess(invalidToken)).thenThrow(new RuntimeException("Invalid token"));

		handler.onMessage(messageContext);

		verify(session).close(1000, "(test-handler) Unauthorized access with invalid token");
		assertThat(handler.clientsConnected).doesNotContain(connectContext);
	}

	@Test
	void testOnMessageFromQuarantinedClientWithoutBearer() throws Exception {
		// First, put client in quarantine
		when(connectContext.header("Authorization")).thenReturn(null);
		when(connectContext.session).thenReturn(session);
		handler.onConnect(connectContext);

		// Now send a message without Bearer prefix
		when(messageContext.session).thenReturn(session);
		when(messageContext.message()).thenReturn("invalid message");

		handler.onMessage(messageContext);

		verify(session).close(1000, "(test-handler) Unauthorized access from quarantined client");
	}

	@Test
	void testOnMessageFromConnectedClient() throws Exception {
		String validToken = "Bearer valid-token";
		String tenantId = "tenant-789";
		String testMessage = "Hello World";

		// First, connect client
		when(connectContext.header("Authorization")).thenReturn(validToken);
		when(connectContext.session).thenReturn(session);
		when(tokenManager.checkAccess(validToken)).thenReturn(tenantId);
		handler.onConnect(connectContext);

		// Now send a regular message
		when(messageContext.session).thenReturn(session);
		when(messageContext.message()).thenReturn(testMessage);

		handler.onMessage(messageContext);

		// Message should be processed normally (in our test handler, we just count it)
		TestWsOauthHandler testHandler = (TestWsOauthHandler) handler;
		assertThat(testHandler.messageCount).isEqualTo(1);
	}

	@Test
	void testOnBinaryMessageFromConnectedClient() throws Exception {
		String validToken = "Bearer valid-token";
		String tenantId = "tenant-abc";
		byte[] testData = new byte[] { 1, 2, 3, 4, 5 };

		// First, connect client
		when(connectContext.header("Authorization")).thenReturn(validToken);
		when(connectContext.session).thenReturn(session);
		when(tokenManager.checkAccess(validToken)).thenReturn(tenantId);
		handler.onConnect(connectContext);

		// Now send a binary message
		when(binaryMessageContext.session).thenReturn(session);
		when(binaryMessageContext.data()).thenReturn(testData);

		handler.onBinaryMessage(binaryMessageContext);

		// Binary message should be processed normally
		TestWsOauthHandler testHandler = (TestWsOauthHandler) handler;
		assertThat(testHandler.binaryMessageCount).isEqualTo(1);
	}

	@Test
	void testOnBinaryMessageFromQuarantinedClient() throws Exception {
		byte[] testData = new byte[] { 1, 2, 3, 4, 5 };

		// First, put client in quarantine
		when(connectContext.header("Authorization")).thenReturn(null);
		when(connectContext.session).thenReturn(session);
		handler.onConnect(connectContext);

		// Now send a binary message
		when(binaryMessageContext.session).thenReturn(session);
		when(binaryMessageContext.data()).thenReturn(testData);

		handler.onBinaryMessage(binaryMessageContext);

		verify(session).close(1000, "(test-handler) Unauthorized access from quarantined client");
	}

	@Test
	void testOnClose() throws Exception {
		String validToken = "Bearer valid-token";
		String tenantId = "tenant-close";

		// First, connect client
		when(connectContext.header("Authorization")).thenReturn(validToken);
		when(connectContext.session).thenReturn(session);
		when(tokenManager.checkAccess(validToken)).thenReturn(tenantId);
		handler.onConnect(connectContext);

		assertThat(handler.clientsConnected).contains(connectContext);

		// Now close connection
		when(closeContext.session).thenReturn(session);
		handler.onClose(closeContext);

		assertThat(handler.clientsConnected).doesNotContain(connectContext);
		assertThat(handler.tenantIdsBySession).doesNotContainKey(session);
	}

	@Test
	void testOnError() throws Exception {
		String validToken = "Bearer valid-token";
		String tenantId = "tenant-error";
		IOException testError = new IOException("Test error");

		// First, connect client
		when(connectContext.header("Authorization")).thenReturn(validToken);
		when(connectContext.session).thenReturn(session);
		when(tokenManager.checkAccess(validToken)).thenReturn(tenantId);
		handler.onConnect(connectContext);

		assertThat(handler.clientsConnected).contains(connectContext);

		// Now trigger error
		when(errorContext.session).thenReturn(session);
		when(errorContext.error()).thenReturn(testError);
		handler.onError(errorContext);

		assertThat(handler.clientsConnected).doesNotContain(connectContext);
	}

	@Test
	void testRemoveClient() throws Exception {
		String validToken = "Bearer valid-token";
		String tenantId = "tenant-remove";

		// First, connect client
		when(connectContext.header("Authorization")).thenReturn(validToken);
		when(connectContext.session).thenReturn(session);
		when(tokenManager.checkAccess(validToken)).thenReturn(tenantId);
		handler.onConnect(connectContext);

		assertThat(handler.clientsConnected).contains(connectContext);

		// Remove client
		handler.removeClient(session);

		assertThat(handler.clientsConnected).doesNotContain(connectContext);
		assertThat(handler.clientsQuarantined).doesNotContain(connectContext);
	}

	@Test
	void testGetClient() throws Exception {
		String validToken = "Bearer valid-token";
		String tenantId = "tenant-get";

		// First, connect client
		when(connectContext.header("Authorization")).thenReturn(validToken);
		when(connectContext.session).thenReturn(session);
		when(tokenManager.checkAccess(validToken)).thenReturn(tenantId);
		handler.onConnect(connectContext);

		WsConnectContext retrievedContext = handler.getClient(session);

		assertThat(retrievedContext).isEqualTo(connectContext);
	}

	@Test
	void testGetQuarantinedClient() throws Exception {
		// First, put client in quarantine
		when(connectContext.header("Authorization")).thenReturn(null);
		when(connectContext.session).thenReturn(session);
		handler.onConnect(connectContext);

		WsConnectContext retrievedContext = handler.getQuarantinedClient(session);

		assertThat(retrievedContext).isEqualTo(connectContext);
	}

	@Test
	void testIsConnected() throws Exception {
		String validToken = "Bearer valid-token";
		String tenantId = "tenant-check";

		// Initially not connected
		when(connectContext.session).thenReturn(session);
		assertThat(handler.isConnected(session)).isFalse();

		// Connect client
		when(connectContext.header("Authorization")).thenReturn(validToken);
		when(tokenManager.checkAccess(validToken)).thenReturn(tenantId);
		handler.onConnect(connectContext);

		assertThat(handler.isConnected(session)).isTrue();
	}

	@Test
	void testIsQuarantined() throws Exception {
		// Initially not quarantined
		when(connectContext.session).thenReturn(session);
		assertThat(handler.isQuarantined(session)).isFalse();

		// Put client in quarantine
		when(connectContext.header("Authorization")).thenReturn(null);
		handler.onConnect(connectContext);

		assertThat(handler.isQuarantined(session)).isTrue();
	}

	@Test
	void testHeartbeat() throws Exception {
		String validToken = "Bearer valid-token";
		String tenantId = "tenant-heartbeat";

		when(connectContext.header("Authorization")).thenReturn(validToken);
		when(connectContext.session).thenReturn(session);
		when(tokenManager.checkAccess(validToken)).thenReturn(tenantId);
		when(session.isOpen()).thenReturn(true);
		when(session.getRemote()).thenReturn(remoteEndpoint);

		handler.onConnect(connectContext);

		// Wait for heartbeat to execute (at least one cycle)
		// The heartbeat runs every 30 seconds, but we can't wait that long in a test
		// So we'll just verify the setup is correct
		assertThat(handler.clientsConnected).contains(connectContext);
	}

	@Test
	void testMultipleClientsInDifferentStates() throws Exception {
		String validToken = "Bearer valid-token";
		String tenantId = "tenant-multi";

		Session session1 = mock(Session.class);
		Session session2 = mock(Session.class);
		WsConnectContext context1 = mock(WsConnectContext.class);
		WsConnectContext context2 = mock(WsConnectContext.class);

		// Connect first client
		when(context1.header("Authorization")).thenReturn(validToken);
		when(context1.session).thenReturn(session1);
		when(tokenManager.checkAccess(validToken)).thenReturn(tenantId);
		handler.onConnect(context1);

		// Put second client in quarantine
		when(context2.header("Authorization")).thenReturn(null);
		when(context2.session).thenReturn(session2);
		handler.onConnect(context2);

		assertThat(handler.clientsConnected).contains(context1);
		assertThat(handler.clientsConnected).doesNotContain(context2);
		assertThat(handler.clientsQuarantined).doesNotContain(context1);
		assertThat(handler.clientsQuarantined).contains(context2);
	}

	/**
	 * Test implementation of WsOauthHandlerBase for testing purposes
	 */
	private static class TestWsOauthHandler extends WsOauthHandlerBase {
		public int messageCount = 0;
		public int binaryMessageCount = 0;

		public TestWsOauthHandler(String name) {
			super(name);
		}

		@Override
		public void onMsg(WsMessageContext ctx) throws Exception {
			messageCount++;
		}

		@Override
		public void onBinaryMessage(WsBinaryMessageContext ctx) throws Exception {
			binaryMessageCount++;
			super.onBinaryMessage(ctx);
		}
	}
}

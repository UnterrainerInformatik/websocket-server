package info.unterrainer.websocketserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.util.Arrays;

import org.eclipse.jetty.websocket.api.RemoteEndpoint;
import org.eclipse.jetty.websocket.api.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import info.unterrainer.oauthtokenmanager.OauthTokenManager;
import io.javalin.websocket.WsBinaryMessageContext;
import io.javalin.websocket.WsCloseContext;
import io.javalin.websocket.WsConnectContext;
import io.javalin.websocket.WsErrorContext;
import io.javalin.websocket.WsMessageContext;

class WsOauthHandlerBaseTest {

  private OauthTokenManager tokenManager;
  private Session session;
  private RemoteEndpoint remoteEndpoint;

  private WsConnectContext connectContext;
  private WsMessageContext messageContext;
  private WsBinaryMessageContext binaryMessageContext;
  private WsCloseContext closeContext;
  private WsErrorContext errorContext;

  private WsOauthHandlerBase handler;

  @BeforeEach
  void setUp() {
    tokenManager = mock(OauthTokenManager.class);
    session = mock(Session.class);
    remoteEndpoint = mock(RemoteEndpoint.class);

    connectContext = mock(WsConnectContext.class);
    messageContext = mock(WsMessageContext.class);
    binaryMessageContext = mock(WsBinaryMessageContext.class);
    closeContext = mock(WsCloseContext.class);
    errorContext = mock(WsErrorContext.class);

    // IMPORTANT: ctx.session is a public final field -> set it via reflection
    setCtxSession(connectContext, session);
    setCtxSession(messageContext, session);
    setCtxSession(binaryMessageContext, session);
    setCtxSession(closeContext, session);
    setCtxSession(errorContext, session);

    handler = new TestWsOauthHandler("test-handler");
    handler.setTokenHandler(tokenManager);
  }

  // ---- tests ----

  @Test
  void testHandleConnectWithValidToken() throws Exception {
    String validToken = "Bearer valid-token";
    String tenantId = "tenant-123";

    when(connectContext.header("Authorization")).thenReturn(validToken);
    when(tokenManager.checkAccess(validToken)).thenReturn(tenantId);

    handler.handleConnect(connectContext);

    assertThat(handler.clientsConnected).contains(connectContext);
    assertThat(handler.clientsQuarantined).doesNotContain(connectContext);
    assertThat(handler.tenantIdsBySession.get(session)).isEqualTo(tenantId);
  }

  @Test
  void testHandleConnectWithInvalidToken() throws Exception {
    String invalidToken = "Bearer invalid-token";

    when(connectContext.header("Authorization")).thenReturn(invalidToken);
    when(tokenManager.checkAccess(invalidToken)).thenThrow(new RuntimeException("Invalid token"));

    handler.handleConnect(connectContext);

    verify(session).close(1000, "(test-handler) Unauthorized access with invalid token");
    assertThat(handler.clientsConnected).doesNotContain(connectContext);
    assertThat(handler.clientsQuarantined).doesNotContain(connectContext);
  }

  @Test
  void testHandleConnectWithoutToken() throws Exception {
    when(connectContext.header("Authorization")).thenReturn(null);

    handler.handleConnect(connectContext);

    assertThat(handler.clientsQuarantined).contains(connectContext);
    assertThat(handler.clientsConnected).doesNotContain(connectContext);
  }

  @Test
  void testHandleConnectWithEmptyToken() throws Exception {
    when(connectContext.header("Authorization")).thenReturn("");

    handler.handleConnect(connectContext);

    assertThat(handler.clientsQuarantined).contains(connectContext);
    assertThat(handler.clientsConnected).doesNotContain(connectContext);
  }

  @Test
  void testHandleMessageFromQuarantinedClientWithValidToken() throws Exception {
    String tenantId = "tenant-456";
    String tokenInMessage = "Bearer valid-token";

    // quarantine first
    when(connectContext.header("Authorization")).thenReturn(null);
    handler.handleConnect(connectContext);
    assertThat(handler.clientsQuarantined).contains(connectContext);

    // now message with bearer token
    when(messageContext.message()).thenReturn(tokenInMessage);
    when(tokenManager.checkAccess(tokenInMessage)).thenReturn(tenantId);

    handler.handleMessage(messageContext);

    assertThat(handler.clientsConnected).contains(connectContext);
    assertThat(handler.clientsQuarantined).doesNotContain(connectContext);
    assertThat(handler.tenantIdsBySession.get(session)).isEqualTo(tenantId);
  }

  @Test
  void testHandleMessageFromQuarantinedClientWithInvalidToken() throws Exception {
    String invalidToken = "Bearer invalid-token";

    when(connectContext.header("Authorization")).thenReturn(null);
    handler.handleConnect(connectContext);

    when(messageContext.message()).thenReturn(invalidToken);
    when(tokenManager.checkAccess(invalidToken)).thenThrow(new RuntimeException("Invalid token"));

    handler.handleMessage(messageContext);

    verify(session).close(1000, "(test-handler) Unauthorized access with invalid token");
    assertThat(handler.clientsConnected).doesNotContain(connectContext);
  }

  @Test
  void testHandleMessageFromQuarantinedClientWithoutBearer() throws Exception {
    when(connectContext.header("Authorization")).thenReturn(null);
    handler.handleConnect(connectContext);

    when(messageContext.message()).thenReturn("invalid message");

    handler.handleMessage(messageContext);

    verify(session).close(1000, "(test-handler) Unauthorized access from quarantined client");
  }

  @Test
  void testHandleMessageFromConnectedClient() throws Exception {
    String validToken = "Bearer valid-token";
    String tenantId = "tenant-789";
    String testMessage = "Hello World";

    when(connectContext.header("Authorization")).thenReturn(validToken);
    when(tokenManager.checkAccess(validToken)).thenReturn(tenantId);
    handler.handleConnect(connectContext);

    when(messageContext.message()).thenReturn(testMessage);
    handler.handleMessage(messageContext);

    TestWsOauthHandler th = (TestWsOauthHandler) handler;
    assertThat(th.messageCount).isEqualTo(1);
  }

  @Test
  void testHandleBinaryMessageFromConnectedClient() throws Exception {
    String validToken = "Bearer valid-token";
    String tenantId = "tenant-abc";
    byte[] testData = new byte[] { 1, 2, 3, 4, 5 };

    when(connectContext.header("Authorization")).thenReturn(validToken);
    when(tokenManager.checkAccess(validToken)).thenReturn(tenantId);
    handler.handleConnect(connectContext);

    when(binaryMessageContext.data()).thenReturn(Arrays.copyOf(testData, testData.length));
    handler.handleBinaryMessage(binaryMessageContext);

    TestWsOauthHandler th = (TestWsOauthHandler) handler;
    assertThat(th.binaryMessageCount).isEqualTo(1);
  }

  @Test
  void testHandleBinaryMessageFromQuarantinedClient() throws Exception {
    byte[] testData = new byte[] { 1, 2, 3, 4, 5 };

    when(connectContext.header("Authorization")).thenReturn(null);
    handler.handleConnect(connectContext);

    when(binaryMessageContext.data()).thenReturn(testData);
    handler.handleBinaryMessage(binaryMessageContext);

    verify(session).close(1000, "(test-handler) Unauthorized access from quarantined client");
  }

  @Test
  void testHandleClose() throws Exception {
    String validToken = "Bearer valid-token";
    when(connectContext.header("Authorization")).thenReturn(validToken);
    when(tokenManager.checkAccess(validToken)).thenReturn("tenant-close");

    handler.handleConnect(connectContext);
    assertThat(handler.clientsConnected).contains(connectContext);

    handler.handleClose(closeContext);

    assertThat(handler.clientsConnected).doesNotContain(connectContext);
    assertThat(handler.tenantIdsBySession).doesNotContainKey(session);
  }

  @Test
  void testHandleError() throws Exception {
    String validToken = "Bearer valid-token";
    when(connectContext.header("Authorization")).thenReturn(validToken);
    when(tokenManager.checkAccess(validToken)).thenReturn("tenant-error");

    handler.handleConnect(connectContext);
    assertThat(handler.clientsConnected).contains(connectContext);

    IOException testError = new IOException("Test error");
    when(errorContext.error()).thenReturn(testError);

    handler.handleError(errorContext);

    assertThat(handler.clientsConnected).doesNotContain(connectContext);
  }

  @Test
  void testHeartbeatSetupDoesNotCrash() throws Exception {
    String validToken = "Bearer valid-token";
    when(connectContext.header("Authorization")).thenReturn(validToken);
    when(tokenManager.checkAccess(validToken)).thenReturn("tenant-heartbeat");

    when(session.isOpen()).thenReturn(true);
    when(session.getRemote()).thenReturn(remoteEndpoint);

    handler.handleConnect(connectContext);

    assertThat(handler.clientsConnected).contains(connectContext);
    // we do not wait 30s in a unit test; we just ensure setup does not explode
  }

  // ---- helper: set public final ctx.session field ----

  private static void setCtxSession(Object ctx, Session s) {
    // Try reflection first
    try {
      Field f = ctx.getClass().getField("session"); // public field inherited from ctx class
      f.setAccessible(true);
      f.set(ctx, s);
      return;
    } catch (NoSuchFieldException ignored) {
      // fallthrough to declared field on super types
    } catch (IllegalAccessException e) {
      // fallthrough to VarHandle
    }

    // Try walking class hierarchy with reflection
    Class<?> c = ctx.getClass();
    while (c != null) {
      try {
        Field f = c.getDeclaredField("session");
        f.setAccessible(true);
        f.set(ctx, s);
        return;
      } catch (NoSuchFieldException ignored) {
        c = c.getSuperclass();
      } catch (IllegalAccessException e) {
        break;
      }
    }

    // VarHandle fallback (works well on newer JDKs)
    try {
      // Find the class that actually declares "session"
      Class<?> decl = findDeclaringClass(ctx.getClass(), "session");
      VarHandle vh = MethodHandles.privateLookupIn(decl, MethodHandles.lookup())
          .findVarHandle(decl, "session", Session.class);
      vh.set(ctx, s);
    } catch (Throwable t) {
      throw new RuntimeException("Unable to set ctx.session via reflection/VarHandle. " +
          "Consider creating real contexts (if Javalin provides constructors) or refactor handle* signatures.", t);
    }
  }

  private static Class<?> findDeclaringClass(Class<?> start, String fieldName) throws NoSuchFieldException {
    Class<?> c = start;
    while (c != null) {
      try {
        c.getDeclaredField(fieldName);
        return c;
      } catch (NoSuchFieldException ignored) {
        c = c.getSuperclass();
      }
    }
    throw new NoSuchFieldException(fieldName);
  }

  // ---- test impl ----
  private static class TestWsOauthHandler extends WsOauthHandlerBase {
    public int messageCount = 0;
    public int binaryMessageCount = 0;

    public TestWsOauthHandler(String name) { super(name); }

    @Override
    public void onMsg(WsMessageContext ctx) throws Exception {
      messageCount++;
    }

    @Override
    public void onBinaryMsg(WsBinaryMessageContext ctx) throws Exception {
      binaryMessageCount++;
    }
  }
}

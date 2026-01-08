package info.unterrainer.websocketserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import io.javalin.websocket.WsBinaryMessageContext;
import io.javalin.websocket.WsCloseContext;
import io.javalin.websocket.WsConnectContext;
import io.javalin.websocket.WsErrorContext;
import io.javalin.websocket.WsMessageContext;

class WsHandlerBaseTest {

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

	private TestWsHandler handler;
	
	@BeforeEach
	void setUp() {
		handler = new TestWsHandler();
	}

	@Test
	void testOnConnect() throws Exception {
		handler.onConnect(connectContext);
		assertThat(handler.connectCalled).isTrue();
	}

	@Test
	void testOnMsg() throws Exception {
		String message = "test message";
		when(messageContext.message()).thenReturn(message);

		handler.onMsg(messageContext);

		assertThat(handler.msgCalled).isTrue();
		assertThat(handler.lastMessage).isEqualTo(message);
	}

	@Test
	void testOnBinaryMessage() throws Exception {
		byte[] data = new byte[] { 1, 2, 3 };
		when(binaryMessageContext.data()).thenReturn(data);

		handler.onBinaryMsg(binaryMessageContext);

		assertThat(handler.binaryMessageCalled).isTrue();
		assertThat(handler.lastBinaryData).isEqualTo(data);
	}

	@Test
	void testOnClose() throws Exception {
		handler.onClose(closeContext);
		assertThat(handler.closeCalled).isTrue();
	}

	@Test
	void testOnError() throws Exception {
		RuntimeException error = new RuntimeException("test error");
		when(errorContext.error()).thenReturn(error);

		handler.onError(errorContext);

		assertThat(handler.errorCalled).isTrue();
		assertThat(handler.lastError).isEqualTo(error);
	}

	@Test
	void testHandlerLifecycle() throws Exception {
		// Simulate complete lifecycle
		handler.onConnect(connectContext);
		assertThat(handler.connectCalled).isTrue();

		String message = "lifecycle test";
		when(messageContext.message()).thenReturn(message);
		handler.onMsg(messageContext);
		assertThat(handler.msgCalled).isTrue();

		byte[] data = new byte[] { 4, 5, 6 };
		when(binaryMessageContext.data()).thenReturn(data);
		handler.onBinaryMsg(binaryMessageContext);
		assertThat(handler.binaryMessageCalled).isTrue();

		handler.onClose(closeContext);
		assertThat(handler.closeCalled).isTrue();
	}

	@Test
	void testMultipleMessages() throws Exception {
		handler.onConnect(connectContext);

		// Send multiple messages
		for (int i = 0; i < 5; i++) {
			WsMessageContext ctx = mock(WsMessageContext.class);
			when(ctx.message()).thenReturn("message-" + i);
			handler.onMsg(ctx);
		}

		assertThat(handler.messageCount).isEqualTo(5);
		assertThat(handler.lastMessage).isEqualTo("message-4");
	}

	/**
	 * Test implementation of WsHandlerBase
	 */
	private static class TestWsHandler extends WsHandlerBase {
		public boolean connectCalled = false;
		public boolean msgCalled = false;
		public boolean binaryMessageCalled = false;
		public boolean closeCalled = false;
		public boolean errorCalled = false;
		public String lastMessage = null;
		public byte[] lastBinaryData = null;
		public Throwable lastError = null;
		public int messageCount = 0;

		@Override
		public void onConnect(WsConnectContext ctx) throws Exception {
			connectCalled = true;
		}

		@Override
		public void onMsg(WsMessageContext ctx) throws Exception {
			msgCalled = true;
			lastMessage = ctx.message();
			messageCount++;
		}

		@Override
		public void onBinaryMsg(WsBinaryMessageContext ctx) throws Exception {
			binaryMessageCalled = true;
			lastBinaryData = ctx.data();
		}

		@Override
		public void onClose(WsCloseContext ctx) throws Exception {
			closeCalled = true;
		}

		@Override
		public void onError(WsErrorContext ctx) throws Exception {
			errorCalled = true;
			lastError = ctx.error();
		}
	}
}

package info.unterrainer.websocketserver;

import io.javalin.websocket.WsConnectContext;
import io.javalin.websocket.WsMessageContext;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class AiComm extends WsOauthHandlerBase {

	@Override
	public void onMsg(WsMessageContext ctx) throws Exception {
		super.onMsg(ctx);

		// Broadcast to all connected WS clients.
		for (WsConnectContext client : clientsConnected) {
			if (client.session.isOpen()) {
				client.send("Echo from server: [" + ctx.message() + "]");
			}
		}
	}
}

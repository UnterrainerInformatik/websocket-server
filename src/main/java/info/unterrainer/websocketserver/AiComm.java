package info.unterrainer.websocketserver;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.javalin.websocket.WsConnectContext;
import io.javalin.websocket.WsMessageContext;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class AiComm extends WsOauthHandlerBase {

	private Set<WsConnectContext> connectedWsClients = ConcurrentHashMap.newKeySet();

	@Override
	public void onConnect(WsConnectContext ctx) throws Exception {
		super.onConnect(ctx);
		connectedWsClients.add(ctx);
		ctx.send("Welcome to our websocket-server!");
	}

	@Override
	public void onMessage(WsMessageContext ctx) throws Exception {
		super.onMessage(ctx);

		// Broadcast to all connected WS clients.
		for (WsConnectContext client : connectedWsClients) {
			if (client.session.isOpen()) {
				client.send("Echo from server: [" + ctx.message() + "]");
			}
		}
	}
}

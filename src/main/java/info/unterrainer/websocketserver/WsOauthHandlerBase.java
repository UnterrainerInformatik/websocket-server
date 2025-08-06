package info.unterrainer.websocketserver;

import java.io.EOFException;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jetty.websocket.api.Session;

import info.unterrainer.oauthtokenmanager.OauthTokenManager;
import io.javalin.websocket.WsCloseContext;
import io.javalin.websocket.WsConnectContext;
import io.javalin.websocket.WsErrorContext;
import io.javalin.websocket.WsMessageContext;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class WsOauthHandlerBase extends WsHandlerBase {

	private OauthTokenManager tokenHandler;
	private Set<WsConnectContext> clientsConnected = ConcurrentHashMap.newKeySet();
	private Set<WsConnectContext> clientsQuarantined = ConcurrentHashMap.newKeySet();

	void setTokenHandler(OauthTokenManager tokenHandler) {
		this.tokenHandler = tokenHandler;
	}

	public void removeClient(Session session) {
		log.debug("Removing client: [{}]", session.getRemoteAddress());
		clientsConnected.removeIf(client -> client.session.equals(session));
		clientsQuarantined.removeIf(client -> client.session.equals(session));
	}

	public WsConnectContext getClient(Session session) {
		log.debug("Getting client: [{}]", session.getRemoteAddress());
		return clientsConnected.stream().filter(client -> client.session.equals(session)).findFirst().orElse(null);
	}

	public WsConnectContext getQuarantinedClient(Session session) {
		log.debug("Getting quarantined client: [{}]", session.getRemoteAddress());
		return clientsQuarantined.stream().filter(client -> client.session.equals(session)).findFirst().orElse(null);
	}

	public boolean isQuarantined(Session session) {
		log.debug("Checking if client is quarantined: [{}]", session.getRemoteAddress());
		return clientsQuarantined.stream().anyMatch(client -> client.session.equals(session));
	}

	public boolean isConnected(Session session) {
		log.debug("Checking if client is connected: [{}]", session.getRemoteAddress());
		return clientsConnected.stream().anyMatch(client -> client.session.equals(session));
	}

	@Override
	public void onConnect(WsConnectContext ctx) throws Exception {
		log.debug("New client tries to connect: [{}]", ctx.session.getRemoteAddress());
		String token = ctx.header("Authorization");
		if (token == null || token.isEmpty()) {
			log.warn("No token provided for client: [{}]\nSending connection into quarantine.",
					ctx.session.getRemoteAddress());
			clientsQuarantined.add(ctx);
			return;
		}
		log.debug("New client token: [{}]", token);
		try {
			tokenHandler.checkAccess(token);
			clientsConnected.add(ctx);
		} catch (Exception e) {
			log.debug("Token validation failed for client [{}]. Disconnecting.", ctx.session.getRemoteAddress(), e);
			ctx.session.close();
			return;
		}
	}

	@Override
	public void onMessage(WsMessageContext ctx) throws Exception {
		log.debug("Received from [{}]: [{}]", ctx.session.getRemoteAddress(), ctx.message());
		if (isQuarantined(ctx.session)) {
			log.warn("Client [{}] is quarantined, checking message for standard authorization-bearer-token.",
					ctx.session.getRemoteAddress());
			if (ctx.message() == null || !ctx.message().startsWith("Bearer ")) {
				log.warn("Invalid message from quarantined client [{}]. Disconnecting.",
						ctx.session.getRemoteAddress());
				removeClient(ctx.session);
				ctx.session.close();
				return;
			}
			try {
				tokenHandler.checkAccess(ctx.message());
				WsConnectContext client = getQuarantinedClient(ctx.session);
				clientsQuarantined.removeIf(c -> c.session.equals(ctx.session));
				clientsConnected.add(client);
			} catch (Exception e) {
				ctx.session.close();
				log.debug("Token validation failed for client [{}]. Disconnecting.", ctx.session.getRemoteAddress(), e);
				return;
			}
		}
	}

	@Override
	public void onClose(WsCloseContext ctx) throws Exception {
		log.debug("Disconnected client: [{}]", ctx.session.getRemoteAddress());
		removeClient(ctx.session);
	}

	@Override
	public void onError(WsErrorContext ctx) throws Exception {
		Throwable t = ctx.error();
		if (t instanceof EOFException || t instanceof IOException) {
			log.debug("Client disconnected [{}].", ctx.session.getRemoteAddress());
		} else {
			log.error("Unexpected error on [{}].", ctx.session.getRemoteAddress(), t);
		}
		removeClient(ctx.session);
	}
}

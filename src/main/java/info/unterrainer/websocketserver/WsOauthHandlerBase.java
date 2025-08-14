package info.unterrainer.websocketserver;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.websocket.api.Session;

import info.unterrainer.commons.jreutils.ShutdownHook;
import info.unterrainer.oauthtokenmanager.OauthTokenManager;
import io.javalin.websocket.WsBinaryMessageContext;
import io.javalin.websocket.WsCloseContext;
import io.javalin.websocket.WsConnectContext;
import io.javalin.websocket.WsErrorContext;
import io.javalin.websocket.WsMessageContext;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class WsOauthHandlerBase extends WsHandlerBase {

	protected String name;
	protected OauthTokenManager tokenHandler;
	protected Set<WsConnectContext> clientsConnected = ConcurrentHashMap.newKeySet();
	protected Set<WsConnectContext> clientsQuarantined = ConcurrentHashMap.newKeySet();
	protected HashMap<Session, String> tenantIdsBySession = new HashMap<>();

	protected ScheduledExecutorService hb = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "ws-heartbeat");
		t.setDaemon(true);
		return t;
	});

	public WsOauthHandlerBase(String name) {
		super();
		this.name = name;
		ShutdownHook.register(() -> {
			hb.close();
			hb = null;
		});

		hb.scheduleAtFixedRate(() -> {
			for (WsConnectContext c : clientsConnected) {
				Session s = c.session;
				if (s.isOpen()) {
					try {
						s.getRemote().sendPing(ByteBuffer.allocate(1));
					} catch (Exception e) {
						try {
							s.close(1000, "(" + name + ") heartbeat failed");
						} catch (Exception ignore) {
						}
					}
				}
			}
		}, 30, 30, TimeUnit.SECONDS);
	}

	void setTokenHandler(OauthTokenManager tokenHandler) {
		this.tokenHandler = tokenHandler;
	}

	public void removeClient(Session session) {
		log.debug("(" + name + ") Removing client: [{}]", session.getRemoteAddress());
		clientsConnected.removeIf(client -> client.session.equals(session));
		clientsQuarantined.removeIf(client -> client.session.equals(session));
	}

	public WsConnectContext getClient(Session session) {
		log.debug("(" + name + ") Getting client: [{}]", session.getRemoteAddress());
		return clientsConnected.stream().filter(client -> client.session.equals(session)).findFirst().orElse(null);
	}

	public WsConnectContext getQuarantinedClient(Session session) {
		log.debug("(" + name + ") Getting quarantined client: [{}]", session.getRemoteAddress());
		return clientsQuarantined.stream().filter(client -> client.session.equals(session)).findFirst().orElse(null);
	}

	public boolean isQuarantined(Session session) {
		log.debug("(" + name + ") Checking if client is quarantined: [{}]", session.getRemoteAddress());
		return clientsQuarantined.stream().anyMatch(client -> client.session.equals(session));
	}

	public boolean isConnected(Session session) {
		log.debug("(" + name + ") Checking if client is connected: [{}]", session.getRemoteAddress());
		return clientsConnected.stream().anyMatch(client -> client.session.equals(session));
	}

	@Override
	public void onConnect(WsConnectContext ctx) throws Exception {
		log.debug("(" + name + ") New client tries to connect: [{}]", ctx.session.getRemoteAddress());
		String token = ctx.header("Authorization");
		if (token == null || token.isEmpty()) {
			log.warn("(" + name + ") No token provided for client: [{}]\nSending connection into quarantine.",
					ctx.session.getRemoteAddress());
			clientsQuarantined.add(ctx);
			return;
		}
		log.debug("(" + name + ") New client token: [{}]", token);
		try {
			String tenantId = tokenHandler.checkAccess(token);
			tenantIdsBySession.put(ctx.session, tenantId);
			clientsConnected.add(ctx);
		} catch (Exception e) {
			log.debug("(" + name + ") Token validation failed for client [{}]. Disconnecting.",
					ctx.session.getRemoteAddress(), e);
			ctx.session.close(1000, "(" + name + ") Unauthorized access with invalid token");
			return;
		}
	}

	@Override
	public void onMsg(WsMessageContext ctx) throws Exception {
	}

	public final void onMessage(WsMessageContext ctx) throws Exception {
		log.debug("(" + name + ") Received from [{}]: [{}]", ctx.session.getRemoteAddress(), ctx.message());
		if (isQuarantined(ctx.session)) {
			log.warn(
					"(" + name
							+ ") Client [{}] is quarantined, checking message for standard authorization-bearer-token.",
					ctx.session.getRemoteAddress());
			if (ctx.message() == null || !ctx.message().startsWith("Bearer ")) {
				log.warn("(" + name + ") Invalid message from quarantined client [{}]. Disconnecting.",
						ctx.session.getRemoteAddress());
				removeClient(ctx.session);
				ctx.session.close(1000, "(" + name + ") Unauthorized access from quarantined client");
				return;
			}
			try {
				String tenantId = tokenHandler.checkAccess(ctx.message());
				tenantIdsBySession.put(ctx.session, tenantId);
				WsConnectContext client = getQuarantinedClient(ctx.session);
				log.debug("(" + name + ") Client [{}] passed token validation. Moving from quarantine to connected.",
						ctx.session.getRemoteAddress());
				clientsQuarantined.removeIf(c -> c.session.equals(ctx.session));
				clientsConnected.add(client);
				return;
			} catch (Exception e) {
				log.debug("(" + name + ") Token validation failed for client [{}]. Disconnecting.",
						ctx.session.getRemoteAddress(), e);
				ctx.session.close(1000, "(" + name + ") Unauthorized access with invalid token");
				return;
			}
		}
		onMsg(ctx);
	}

	@Override
	public void onBinaryMessage(WsBinaryMessageContext ctx) throws Exception {
		log.debug("(" + name + ") Received binary message from [{}]: [{}] bytes", ctx.session.getRemoteAddress(),
				ctx.data().length);
		if (isQuarantined(ctx.session)) {
			log.warn("(" + name + ") Invalid Message from quarantined client [{}]. Disconnecting.",
					ctx.session.getRemoteAddress());
			removeClient(ctx.session);
			ctx.session.close(1000, "(" + name + ") Unauthorized access from quarantined client");
			return;
		}
	}

	@Override
	public void onClose(WsCloseContext ctx) throws Exception {
		log.debug("(" + name + ") Disconnected client: [{}]", ctx.session.getRemoteAddress());
		removeClient(ctx.session);
	}

	@Override
	public void onError(WsErrorContext ctx) throws Exception {
		Throwable t = ctx.error();
		if (t instanceof EOFException || t instanceof IOException) {
			log.debug("(" + name + ") Client disconnected [{}].", ctx.session.getRemoteAddress());
		} else {
			log.error("(" + name + ") Unexpected error on [{}].", ctx.session.getRemoteAddress(), t);
		}
		removeClient(ctx.session);
	}
}

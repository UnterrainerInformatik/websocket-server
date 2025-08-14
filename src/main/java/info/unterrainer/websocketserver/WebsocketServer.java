package info.unterrainer.websocketserver;

import java.util.HashSet;
import java.util.function.Consumer;

import info.unterrainer.oauthtokenmanager.OauthTokenManager;
import io.javalin.Javalin;
import io.javalin.websocket.WsExceptionHandler;
import io.javalin.websocket.WsHandler;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class WebsocketServer {

	private String name;
	private String keycloakHost;
	private String realm;
	private OauthTokenManager tokenManager;

	private Javalin wss;
	private boolean isOauthEnabled = false;

	public WebsocketServer() {
		this("", (Javalin) null);
	}

	public WebsocketServer(String name) {
		this(name, (Javalin) null);
	}

	public WebsocketServer(Javalin server) {
		this("", server);
	}

	public WebsocketServer(String name, Javalin server) {
		this(name, server, null);
	}

	public WebsocketServer(WsExceptionHandler<Exception> exceptionHandler) {
		this("", (Javalin) null, exceptionHandler);
	}

	public WebsocketServer(String name, WsExceptionHandler<Exception> exceptionHandler) {
		this(name, (Javalin) null, exceptionHandler);
	}

	public WebsocketServer(Javalin server, WsExceptionHandler<Exception> exceptionHandler) {
		this("", server, exceptionHandler);
	}

	public WebsocketServer(String name, Javalin server, WsExceptionHandler<Exception> exceptionHandler) {
		this.name = name;
		try {
			wss = server;
			if (wss == null)
				wss = Javalin.create();

			if (exceptionHandler != null)
				wss.wsException(Exception.class, exceptionHandler);

			wss.wsException(Exception.class, (e, ctx) -> {
				log.error("(" + name + ") Uncaught websocket-exception in Websocket-Server: {}", e);
			});
		} catch (Exception e) {
			log.error("(" + name + ") Error initializing Websocket-Server.", e);
		}
	}

	public WebsocketServer(String keycloakHost, String keycloakRealm) {
		this("", null, keycloakHost, keycloakRealm);
	}

	public WebsocketServer(String name, String keycloakHost, String keycloakRealm) {
		this(name, null, keycloakHost, keycloakRealm);
	}

	public WebsocketServer(Javalin server, String keycloakHost, String keycloakRealm) {
		this("", server, keycloakHost, keycloakRealm, null);
	}

	public WebsocketServer(String name, Javalin server, String keycloakHost, String keycloakRealm) {
		this(name, server, keycloakHost, keycloakRealm, null);
	}

	public WebsocketServer(String keycloakHost, String keycloakRealm, WsExceptionHandler<Exception> exceptionHandler) {
		this("", null, keycloakHost, keycloakRealm, exceptionHandler);
	}

	public WebsocketServer(String name, String keycloakHost, String keycloakRealm,
			WsExceptionHandler<Exception> exceptionHandler) {
		this(name, null, keycloakHost, keycloakRealm, exceptionHandler);
	}

	public WebsocketServer(Javalin server, String keycloakHost, String keycloakRealm,
			WsExceptionHandler<Exception> exceptionHandler) {
		this("", server, keycloakHost, keycloakRealm, exceptionHandler);
	}

	public WebsocketServer(String name, Javalin server, String keycloakHost, String keycloakRealm,
			WsExceptionHandler<Exception> exceptionHandler) {
		this(name, server, exceptionHandler);
		if (keycloakHost == null || keycloakHost.isEmpty()) {
			throw new IllegalArgumentException("(" + name + ") Keycloak host must not be null or empty.");
		}
		if (keycloakRealm == null || keycloakRealm.isEmpty()) {
			throw new IllegalArgumentException("(" + name + ") Keycloak realm must not be null or empty.");
		}
		this.keycloakHost = keycloakHost;
		this.realm = keycloakRealm;

		try {
			tokenManager = new OauthTokenManager(this.keycloakHost, this.realm);
			tokenManager.initPublicKey();
			isOauthEnabled = true;
		} catch (Exception e) {
			log.error("(" + name + ") Error initializing OauthTokenManager.", e);
			return;
		}
	}

	/**
	 * Starts the Websocket server on the specified port. Don't start this, if you
	 * used this class as a decorator for an existing Javalin instance. Call the
	 * other start method instead.
	 *
	 * @param port the port to listen to
	 * @return
	 */
	public WebsocketServer start(int port) {
		wss.start("0.0.0.0", port);
		log.debug("(" + name + ") Websocket server started on port: {}", port);
		return this;
	}

	public WebsocketServer ws(String path, Consumer<WsHandler> ws) {
		wss.ws(path, ws, new HashSet<>());
		return this;
	}

	public WebsocketServer wsOauth(String path, WsOauthHandlerBase handler) {
		if (!isOauthEnabled) {
			throw new IllegalStateException(
					"(" + name + ") Websocket server is not configured for OAuth2/JWT support.");
		}

		handler.setTokenHandler(tokenManager);
		wss.ws(path, ws -> {
			ws.onConnect(handler::onConnect);
			ws.onMessage(handler::onMessage);
			ws.onBinaryMessage(handler::onBinaryMessage);
			ws.onClose(handler::onClose);
			ws.onError(handler::onError);
		});
		return this;
	}
}

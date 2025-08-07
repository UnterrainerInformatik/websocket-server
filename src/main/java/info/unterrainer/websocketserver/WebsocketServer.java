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

	private String keycloakHost;
	private String realm;
	private OauthTokenManager tokenManager;

	private Javalin wss;
	private boolean isOauthEnabled = false;

	public WebsocketServer() {
		this(null);
	}

	public WebsocketServer(WsExceptionHandler<Exception> exceptionHandler) {
		try {
			wss = Javalin.create();
			wss.exception(Exception.class, (e, ctx) -> {
				log.error("Uncaught exception in Websocket-Server: {}", e);
			});
			if (exceptionHandler != null)
				wss.wsException(Exception.class, exceptionHandler);
			wss.wsException(Exception.class, (e, ctx) -> {
				log.error("Uncaught websocket-exception in Websocket-Server: {}", e);
			});
		} catch (Exception e) {
			log.error("Error initializing Websocket-Server.", e);
		}
	}

	public WebsocketServer(String keycloakHost, String keycloakRealm) {
		this(keycloakHost, keycloakRealm, null);
	}

	public WebsocketServer(String keycloakHost, String keycloakRealm, WsExceptionHandler<Exception> exceptionHandler) {
		this(exceptionHandler);
		if (keycloakHost == null || keycloakHost.isEmpty()) {
			throw new IllegalArgumentException("Keycloak host must not be null or empty.");
		}
		if (keycloakRealm == null || keycloakRealm.isEmpty()) {
			throw new IllegalArgumentException("Keycloak realm must not be null or empty.");
		}
		this.keycloakHost = keycloakHost;
		this.realm = keycloakRealm;

		try {
			tokenManager = new OauthTokenManager(this.keycloakHost, this.realm);
			tokenManager.initPublicKey();
			isOauthEnabled = true;
		} catch (Exception e) {
			log.error("Error initializing OauthTokenManager.", e);
			return;
		}
	}

	public WebsocketServer start(int port) {
		wss.start("0.0.0.0", port);
		log.debug("Websocket server started on port: {}", port);
		return this;
	}

	public WebsocketServer ws(String path, Consumer<WsHandler> ws) {
		wss.ws(path, ws, new HashSet<>());
		return this;
	}

	public WebsocketServer wsOauth(String path, WsOauthHandlerBase handler) {
		if (!isOauthEnabled) {
			throw new IllegalStateException("Websocket server is not configured for OAuth2/JWT support.");
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

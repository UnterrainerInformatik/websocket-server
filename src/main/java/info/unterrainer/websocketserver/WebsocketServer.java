package info.unterrainer.websocketserver;

import java.util.HashSet;
import java.util.function.Consumer;

import info.unterrainer.oauthtokenmanager.OauthTokenManager;
import io.javalin.Javalin;
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
		wss = Javalin.create();
	}

	public WebsocketServer(String keycloakHost, String keycloakRealm) {
		this.keycloakHost = keycloakHost;
		this.realm = keycloakRealm;

		try {
			tokenManager = new OauthTokenManager(this.keycloakHost, this.realm);
			tokenManager.initPublicKey();
			wss = Javalin.create();
			isOauthEnabled = true;
		} catch (Exception e) {
			// Exceptions will terminate a request later on, but should not terminate the
			// main-thread here.
		}
	}

	public WebsocketServer start(int port) {
		wss.start(port);
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
			ws.onClose(handler::onClose);
			ws.onError(handler::onError);
		});
		return this;
	}
}

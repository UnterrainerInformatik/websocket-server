package info.unterrainer.websocketserver;

import java.io.EOFException;
import java.io.IOException;

import info.unterrainer.oauthtokenmanager.OauthTokenManager;
import io.javalin.websocket.WsCloseContext;
import io.javalin.websocket.WsConnectContext;
import io.javalin.websocket.WsErrorContext;
import io.javalin.websocket.WsMessageContext;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class WsOauthHandlerBase extends WsHandlerBase {

	private OauthTokenManager tokenHandler;

	void setTokenHandler(OauthTokenManager tokenHandler) {
		this.tokenHandler = tokenHandler;
	}

	@Override
	public void onConnect(WsConnectContext ctx) throws Exception {
		log.debug("New client tries to connect: [{}]", ctx.session.getRemoteAddress());
		String token = ctx.header("Authorization");
		log.debug("New client token: [{}]", token);
		try {
			tokenHandler.checkAccess(token);
		} catch (Exception e) {
			ctx.session.close();
			return;
		}
	}

	@Override
	public void onMessage(WsMessageContext ctx) throws Exception {
		log.debug("Received from [{}]: [{}]", ctx.session.getRemoteAddress(), ctx.message());
	}

	@Override
	public void onClose(WsCloseContext ctx) throws Exception {
		log.debug("Disconnected client: [{}]", ctx.session.getRemoteAddress());
	}

	@Override
	public void onError(WsErrorContext ctx) throws Exception {
		Throwable t = ctx.error();
		if (t instanceof EOFException || t instanceof IOException) {
			log.debug("Client disconnected [{}].", ctx.session.getRemoteAddress());
		} else {
			log.error("Unexpected error on [{}].", ctx.session.getRemoteAddress(), t);
		}
	}
}

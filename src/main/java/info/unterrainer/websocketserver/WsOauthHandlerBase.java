package info.unterrainer.websocketserver;

import io.javalin.websocket.WsCloseContext;
import io.javalin.websocket.WsConnectContext;
import io.javalin.websocket.WsErrorContext;
import io.javalin.websocket.WsMessageContext;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class WsOauthHandlerBase extends WsHandlerBase {

	private JwtTokenHandler tokenHandler;

	void setTokenHandler(JwtTokenHandler tokenHandler) {
		this.tokenHandler = tokenHandler;
	}

	@Override
	public void onConnect(WsConnectContext ctx) throws Exception {
		log.debug("New client tries to connect: [{}]", ctx.session.getRemoteAddress());
		String token = ctx.queryParam("token");
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
		log.error("Error on [{}]: [{}]", ctx.session.getRemoteAddress(), ctx.error());
	}
}

package info.unterrainer.websocketserver;

import io.javalin.websocket.WsCloseContext;
import io.javalin.websocket.WsConnectContext;
import io.javalin.websocket.WsErrorContext;
import io.javalin.websocket.WsMessageContext;

public abstract class WsHandlerBase {

	public abstract void onConnect(WsConnectContext ctx) throws Exception;

	public abstract void onMessage(WsMessageContext ctx) throws Exception;

	public abstract void onClose(WsCloseContext ctx) throws Exception;

	public abstract void onError(WsErrorContext ctx) throws Exception;
}

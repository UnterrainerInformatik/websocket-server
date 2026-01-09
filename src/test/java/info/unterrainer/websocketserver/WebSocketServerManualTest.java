package info.unterrainer.websocketserver;

public class WebSocketServerManualTest {

	public static void main(String[] args) {
		WebsocketServer server = new WebsocketServer("https://keycloak.lan.elite-zettl.at", "Cms");
		AiComm aiComm;

		aiComm = new AiComm("ai-comm-test");

		server.wsOauth("/jwt", aiComm);
		server.ws("/ws", ws -> {
			ws.onConnect(ctx -> ctx.send("Welcome to our websocket-server!"));
			ws.onMessage(ctx -> ctx.send("Echo from server: [" + ctx.message() + "]"));
		});
		server.start(7070);
	}
}

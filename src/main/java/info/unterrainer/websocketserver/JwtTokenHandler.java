package info.unterrainer.websocketserver;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

import org.keycloak.TokenVerifier;
import org.keycloak.common.VerificationException;
import org.keycloak.representations.AccessToken;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import info.unterrainer.commons.httpserver.exceptions.ForbiddenException;
import info.unterrainer.commons.httpserver.exceptions.UnauthorizedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class JwtTokenHandler {

	private final String host;
	private final String realm;

	private String authUrl;
	private PublicKey publicKey = null;

	public void initPublicKey() {
		String correctedHost = host;
		String correctedRealm = realm;

		if (publicKey != null)
			return;
		if (!correctedHost.endsWith("/"))
			correctedHost += "/";
		if (!correctedRealm.startsWith("/"))
			correctedRealm = "/" + correctedRealm;

		authUrl = correctedHost + "realms" + correctedRealm + "/protocol/openid-connect/certs";
		try {
			log.info("Getting public key from: [{}]", authUrl);
			publicKey = fetchPublicKey(authUrl);
		} catch (Exception e) {
			log.error("There was an error fetching the PublicKey from the openIdConnect-server [{}].", authUrl);
			throw new IllegalStateException(e);
		}
	}

	private PublicKey fetchPublicKey(String jwksUrl) throws Exception {
		ObjectMapper objectMapper = new ObjectMapper();
		HttpClient client = HttpClient.newHttpClient();
		HttpRequest request = HttpRequest.newBuilder().uri(URI.create(jwksUrl)).GET().build();

		HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

		if (response.statusCode() >= 300) {
			throw new IOException("Failed to fetch JWKS: HTTP " + response.statusCode());
		}

		JsonNode jwks = objectMapper.readTree(response.body());
		// Just take the first key for now.
		JsonNode key = jwks.get("keys").get(0);

		String modulusBase64 = key.get("n").asText();
		String exponentBase64 = key.get("e").asText();

		byte[] modulusBytes = Base64.getUrlDecoder().decode(modulusBase64);
		byte[] exponentBytes = Base64.getUrlDecoder().decode(exponentBase64);

		BigInteger modulus = new BigInteger(1, modulusBytes);
		BigInteger exponent = new BigInteger(1, exponentBytes);

		RSAPublicKeySpec spec = new RSAPublicKeySpec(modulus, exponent);
		KeyFactory factory = KeyFactory.getInstance("RSA");
		return factory.generatePublic(spec);
	}

	public void checkAccess(String authorizationHeader) {
		try {
			TokenVerifier<AccessToken> tokenVerifier = persistUserInfoInContext(authorizationHeader);
			if (tokenVerifier == null)
				throw new UnauthorizedException();

			initPublicKey();
			tokenVerifier.publicKey(publicKey);
			try {
				tokenVerifier.verifySignature();
			} catch (VerificationException e) {
				throw new UnauthorizedException(
						"Error verifying token from user with publicKey obtained from keycloak.", e);
			}

			try {
				tokenVerifier.verify();
				throw new ForbiddenException();
			} catch (VerificationException e) {
				throw new ForbiddenException();
			}
		} catch (Exception e) {
			log.error("Error checking token.", e);
			throw e;
		}
	}

	private TokenVerifier<AccessToken> persistUserInfoInContext(String authorizationHeader) {
		if (authorizationHeader == null || authorizationHeader.isBlank())
			return null;

		try {
			TokenVerifier<AccessToken> tokenVerifier = TokenVerifier.create(authorizationHeader, AccessToken.class);
			AccessToken token = tokenVerifier.getToken();
			if (!token.isActive()) {
				log.warn("Token is inactive.");
				return null;
			}
			// Disabled to enable getting token from side-channels like 'localhost'.
			/*
			 * if (!token.getIssuer().equalsIgnoreCase(authUrl)) {
			 * setTokenRejectionReason(ctx, "Token has wrong real-url."); return null; }
			 */
			return tokenVerifier;

		} catch (VerificationException e) {
			log.warn("Token was checked and deemed invalid.", e);
			return null;
		}
	}
}

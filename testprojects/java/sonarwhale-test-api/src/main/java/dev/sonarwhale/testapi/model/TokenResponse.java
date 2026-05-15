package dev.sonarwhale.testapi.model;
public class TokenResponse {
    public final String accessToken;
    public final String refreshToken;
    public final String tokenType;
    public final int expiresIn;

    public TokenResponse(String accessToken, String refreshToken, String tokenType, int expiresIn) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.tokenType = tokenType;
        this.expiresIn = expiresIn;
    }

    public TokenResponse(String accessToken, String refreshToken) {
        this(accessToken, refreshToken, "bearer", 3600);
    }
}

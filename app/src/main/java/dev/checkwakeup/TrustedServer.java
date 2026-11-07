package dev.checkwakeup;

/** Values generated at build time, never a private key or password. */
final class TrustedServer {
    static final String URL = GeneratedServerConfig.URL;
    static final String KID = GeneratedServerConfig.KID;
    static final String PUBLIC_KEY = GeneratedServerConfig.PUBLIC_KEY;
    private TrustedServer() {}
}

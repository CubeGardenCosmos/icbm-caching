package cn.cubegarden.icbm.caching.control.tls;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;

/**
 * mTLS 密钥材料工厂（JSSE + PKCS12；草稿 phase2-control-protocol §3 传输安全取舍：
 * 证书 CN 绑定角色由 {@code PeerRegistry} 校验，TLS 之外不重复加密）。
 */
public final class TlsContextFactory {

    private TlsContextFactory() {
    }

    public static KeyManagerFactory keyManagers(Path keyStore, String password) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keyStore)) {
            ks.load(in, password.toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, password.toCharArray());
        return kmf;
    }

    public static TrustManagerFactory trustManagers(Path trustStore, String password) throws Exception {
        KeyStore ts = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(trustStore)) {
            ts.load(in, password.toCharArray());
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ts);
        return tmf;
    }

    /** 客户端 SSLContext（JSSE 客户端用；信任库必须包含服务端 CA）。 */
    public static javax.net.ssl.SSLContext clientContext(
            Path keyStore, String keyStorePassword,
            Path trustStore, String trustStorePassword) throws Exception {
        javax.net.ssl.SSLContext ctx = javax.net.ssl.SSLContext.getInstance("TLS");
        ctx.init(keyManagers(keyStore, keyStorePassword).getKeyManagers(),
                trustManagers(trustStore, trustStorePassword).getTrustManagers(), null);
        return ctx;
    }
}

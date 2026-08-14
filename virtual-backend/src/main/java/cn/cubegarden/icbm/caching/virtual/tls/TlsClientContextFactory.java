package cn.cubegarden.icbm.caching.virtual.tls;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * mTLS 客户端密钥材料（JSSE + PKCS12；与 control-service 的 TlsContextFactory 同构，
 * 属工具代码而非格式，两侧各自持有以避免应用组件互相依赖）。
 */
public final class TlsClientContextFactory {

    private TlsClientContextFactory() {
    }

    public static SSLContext create(Path keyStore, String keyStorePassword,
                                    Path trustStore, String trustStorePassword) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keyStore)) {
            ks.load(in, keyStorePassword.toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, keyStorePassword.toCharArray());

        KeyStore ts = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(trustStore)) {
            ts.load(in, trustStorePassword.toCharArray());
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ts);

        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        return ctx;
    }
}

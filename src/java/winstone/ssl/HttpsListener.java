/*
 * Copyright 2003-2006 Rick Knowles <winstone-devel at lists sourceforge net>
 * Distributed under the terms of either:
 * - the common development and distribution license (CDDL), v1.0; or
 * - the GNU Lesser General Public License, v2.1 or later
 */
package winstone.ssl;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.server.ssl.SslSelectChannelConnector;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import sun.security.util.DerInputStream;
import sun.security.util.DerValue;
import sun.security.x509.CertAndKeyGen;
import sun.security.x509.X500Name;
import winstone.HttpListener;
import winstone.Logger;
import winstone.WinstoneException;
import winstone.WinstoneResourceBundle;
import winstone.auth.BasicAuthenticationHandler;
import winstone.cmdline.Option;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.RSAPrivateKeySpec;
import java.text.MessageFormat;
import java.util.Enumeration;
import java.util.Map;

/**
 * Implements the main listener daemon thread. This is the class that gets
 * launched by the command line, and owns the server socket, etc.
 * 
 * @author <a href="mailto:rick_knowles@hotmail.com">Rick Knowles</a>
 * @version $Id: HttpsListener.java,v 1.10 2007/06/13 15:27:35 rickknowles Exp $
 */
public class HttpsListener extends HttpListener {
    private static final WinstoneResourceBundle SSL_RESOURCES = new WinstoneResourceBundle("winstone.ssl.LocalStrings");
    private final KeyStore keystore;
    private final char[] password;
    private final String keyManagerType;
    /**
     * If true, request the client certificate ala "SSLVerifyClient require" Apache directive.
     * If false, which is the default, don't do so.
     * Technically speaking, there's the equivalent of "SSLVerifyClient optional", but IE doesn't
     * recognize it and it always prompt the certificate chooser dialog box, so in practice
     * it's useless.
     * <p>
     * See http://hudson.361315.n4.nabble.com/winstone-container-and-ssl-td383501.html for this failure mode in IE.
     */
    private boolean performClientAuth;

    /**
     * Constructor
     */
    public HttpsListener(Map args) throws IOException {
        super(args);

        this.listenPort = Option.HTTPS_PORT.get(args);
        this.listenAddress = Option.HTTPS_LISTEN_ADDRESS.get(args);
        this.doHostnameLookups = Option.HTTPS_DO_HOSTNAME_LOOKUPS.get(args);
        this.keepAliveTimeout = Option.HTTPS_KEEP_ALIVE_TIMEOUT.get(args);

        if (listenPort<0) {
            // not running HTTPS listener
            keystore = null;
            password = null;
            keyManagerType = null;
        } else {
            try {
                performClientAuth = Option.HTTPS_VERIFY_CLIENT.get(args);
                File opensslCert = Option.HTTPS_CERTIFICATE.get(args);
                File opensslKey =  Option.HTTPS_PRIVATE_KEY.get(args);
                File keyStore =    Option.HTTPS_KEY_STORE.get(args);
                String pwd =         Option.HTTPS_KEY_STORE_PASSWORD.get(args);

                if ((opensslCert!=null ^ opensslKey!=null))
                    throw new WinstoneException(MessageFormat.format("--{0} and --{1} need to be used together", Option.HTTPS_CERTIFICATE, Option.HTTPS_PRIVATE_KEY));
                if (keyStore!=null && opensslKey!=null)
                    throw new WinstoneException(MessageFormat.format("--{0} and --{1} are mutually exclusive", Option.HTTPS_KEY_STORE, Option.HTTPS_PRIVATE_KEY));

                if (keyStore!=null) {
                    // load from Java style JKS
                    if (!keyStore.exists() || !keyStore.isFile())
                        throw new WinstoneException(SSL_RESOURCES.getString(
                                "HttpsListener.KeyStoreNotFound", keyStore.getPath()));

                    this.password = pwd!=null ? pwd.toCharArray() : null;

                    keystore = KeyStore.getInstance("JKS");
                    keystore.load(new FileInputStream(keyStore), this.password);
                } else if (opensslCert!=null) {
                    // load from openssl style key files
                    CertificateFactory cf = CertificateFactory.getInstance("X509");
                    Certificate cert = cf.generateCertificate(new FileInputStream(opensslCert));
                    PrivateKey key = readPEMRSAPrivateKey(new FileReader(opensslKey));

                    this.password = "changeit".toCharArray();
                    keystore = KeyStore.getInstance("JKS");
                    keystore.load(null);
                    keystore.setKeyEntry("hudson", key, password, new Certificate[]{cert});
                } else {
                    // use self-signed certificate
                    this.password = "changeit".toCharArray();
                    System.out.println("Using one-time self-signed certificate");

                    CertAndKeyGen ckg = new CertAndKeyGen("RSA", "SHA1WithRSA", null);
                    ckg.generate(1024);
                    PrivateKey privKey = ckg.getPrivateKey();

                    X500Name xn = new X500Name("Test site", "Unknown", "Unknown", "Unknown");
                    X509Certificate cert = ckg.getSelfCertificate(xn, 3650L * 24 * 60 * 60);

                    keystore = KeyStore.getInstance("JKS");
                    keystore.load(null);
                    keystore.setKeyEntry("hudson", privKey, password, new Certificate[]{cert});
                }
            } catch (GeneralSecurityException e) {
                throw (IOException)new IOException("Failed to handle keys").initCause(e);
            }

            this.keyManagerType = Option.HTTPS_KEY_MANAGER_TYPE.get(args);
        }
    }

    private static PrivateKey readPEMRSAPrivateKey(Reader reader) throws IOException, GeneralSecurityException {
        // TODO: should have more robust format error handling
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            BufferedReader r = new BufferedReader(reader);
            String line;
            boolean in = false;
            while ((line=r.readLine())!=null) {
                if (line.startsWith("-----")) {
                    in = !in;
                    continue;
                }
                if (in) {
                    char[] inBytes = line.toCharArray();
                    byte[] outBytes = new byte[(inBytes.length*3)/4];
                    int length = BasicAuthenticationHandler.decodeBase64(inBytes, outBytes, 0, inBytes.length, 0);
                    baos.write(outBytes,0,length);
                }
            }
        } finally {
            reader.close();
        }


        DerInputStream dis = new DerInputStream(baos.toByteArray());
        DerValue[] seq = dis.getSequence(0);

        // int v = seq[0].getInteger();
        BigInteger mod = seq[1].getBigInteger();
        // pubExpo
        BigInteger privExpo = seq[3].getBigInteger();
        // p1, p2, exp1, exp2, crtCoef

        KeyFactory kf = KeyFactory.getInstance("RSA");
        return kf.generatePrivate (new RSAPrivateKeySpec(mod,privExpo));
    }

    @Override
    protected SelectChannelConnector createConnector(Server server) {
        return new SslSelectChannelConnector(getSSLContext());
    }

    /**
     * Used to get the base ssl context in which to create the server socket.
     * This is basically just so we can have a custom location for key stores.
     */
    public SslContextFactory getSSLContext() {
        try {
            // Check the key manager factory
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(this.keyManagerType);

            kmf.init(keystore, password);
            Logger.log(Logger.FULL_DEBUG, SSL_RESOURCES,
                    "HttpsListener.KeyCount", keystore.size() + "");
            for (Enumeration e = keystore.aliases(); e.hasMoreElements();) {
                String alias = (String) e.nextElement();
                Logger.log(Logger.FULL_DEBUG, SSL_RESOURCES,
                        "HttpsListener.KeyFound", alias,
                        keystore.getCertificate(alias) + "");
            }

            SSLContext context = SSLContext.getInstance("SSL");
            context.init(kmf.getKeyManagers(), null, null);

            SslContextFactory ssl = new SslContextFactory();
            ssl.setSslContext(context);
            ssl.setNeedClientAuth(performClientAuth);
            return ssl;
        } catch (Throwable err) {
            throw new WinstoneException(SSL_RESOURCES
                    .getString("HttpsListener.ErrorGettingContext"), err);
        }
    }
}

package edu.uri.dfcsc.occp;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidParameterSpecException;
import java.security.spec.RSAKeyGenParameterSpec;
import java.util.Calendar;
import java.util.Date;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.crypto.spec.DHParameterSpec;

import org.apache.commons.net.util.Base64;
import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.DERInteger;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.cert.CertIOException;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.X509KeyUsage;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * High level interface to create an x509 trust chain
 * 
 * @author Kevin Bryan
 */
public class CA {
    private static Logger logger = Logger.getLogger(CA.class.getName());
    private X509Certificate caCrt;
    private PrivateKey caKey;
    private final String CA_DN = "CN=OCCP Challenge CA";

    /**
     * Certificate version of KeyPair
     */
    public static class CertPair {
        /**
         * @param cert X509Certificate
         * @param key PrivateKey
         */
        public CertPair(X509Certificate cert, PrivateKey key) {
            this.cert = cert;
            this.key = key;
        }

        X509Certificate cert;
        PrivateKey key;
    }

    /**
     * Create a self-signed X.509 Certificate
     * 
     * @return CertPair object
     */
    public CertPair generateCA() {
        try {
            // Do the math
            Security.addProvider(new BouncyCastleProvider());
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA", "BC");
            RSAKeyGenParameterSpec rsasp = new RSAKeyGenParameterSpec(1024, BigInteger.valueOf(0x10001));
            kpg.initialize(rsasp);
            KeyPair keyPair = kpg.generateKeyPair();
            caKey = keyPair.getPrivate();
            // Give the key a name (cert)
            X500Name caName = new X500Name(CA_DN);
            // Time limit the cert
            Date now = new Date();
            Calendar cal = Calendar.getInstance();
            cal.setTime(now);
            cal.add(Calendar.DAY_OF_MONTH, -2);
            now = cal.getTime();
            cal.add(Calendar.DAY_OF_MONTH, 92);
            Date expire = cal.getTime();

            // Give basic information
            JcaX509v3CertificateBuilder gen = new JcaX509v3CertificateBuilder(caName, BigInteger.ONE, now, expire,
                    caName, keyPair.getPublic());

            // Mark this cert as a CA
            gen.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));

            // Allow this cert to sign others
            gen.addExtension(Extension.keyUsage, true,
                    new X509KeyUsage(X509KeyUsage.keyCertSign | X509KeyUsage.cRLSign));

            JcaX509ExtensionUtils extUtil = new JcaX509ExtensionUtils();
            // for CA, subject == authority
            gen.addExtension(Extension.subjectKeyIdentifier, false,
                    extUtil.createSubjectKeyIdentifier(keyPair.getPublic()));
            gen.addExtension(Extension.authorityKeyIdentifier, false,
                    extUtil.createAuthorityKeyIdentifier(keyPair.getPublic()));

            final String BC = BouncyCastleProvider.PROVIDER_NAME;

            // Use CA private key to sign (i.e., self-sign)
            ContentSigner signer = new JcaContentSignerBuilder("SHA1WithRSA").setProvider(BC).build(caKey);
            // Build gives "Holder" object instead of cert directly
            X509CertificateHolder hold = gen.build(signer);
            // Store CA cert so we can sign the rest of the certs
            caCrt = (new JcaX509CertificateConverter()).setProvider(BC).getCertificate(hold);

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Could not generate CA", e);
            return null;
        }
        return new CertPair(caCrt, caKey);
    }

    /**
     * Generate a Certificate signed by this CA with the given name, and
     * optionally mark it for server side use
     * 
     * @param certDN Distinguished Name for Certificate
     * @param isServer If true, add serverAuth to ExtKeyUsage
     * @return CertPair containing the certificate and private key
     * @throws NoSuchProviderException
     * @throws NoSuchAlgorithmException
     * @throws InvalidAlgorithmParameterException
     * @throws CertIOException
     * @throws OperatorCreationException
     * @throws CertificateException
     */
    public CertPair generateCert(String certDN, boolean isServer)
            throws NoSuchAlgorithmException, NoSuchProviderException, InvalidAlgorithmParameterException,
            CertIOException, OperatorCreationException, CertificateException {
        // Do the math
        Security.addProvider(new BouncyCastleProvider());
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA", "BC");
        RSAKeyGenParameterSpec rsasp = new RSAKeyGenParameterSpec(1024, BigInteger.valueOf(0x10001));
        kpg.initialize(rsasp);
        KeyPair keyPair = kpg.generateKeyPair();
        // Generate the issuer name
        X500Name caName = new X500Name(CA_DN);
        // Give the key a name (cert)
        X500Name srvName = new X500Name(certDN);
        // Time limit the cert
        Date now = new Date();
        Calendar cal = Calendar.getInstance();
        cal.setTime(now);
        cal.add(Calendar.DAY_OF_MONTH, -2);
        now = cal.getTime();
        cal.add(Calendar.DAY_OF_MONTH, 92);
        Date expire = cal.getTime();

        // Give basic information
        JcaX509v3CertificateBuilder gen = new JcaX509v3CertificateBuilder(caName, BigInteger.valueOf(2), now, expire,
                srvName, keyPair.getPublic());

        // Mark this cert as a non-CA
        gen.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));

        // Allow this cert to create signatures and encrypt data
        gen.addExtension(Extension.keyUsage, true, new X509KeyUsage(X509KeyUsage.digitalSignature
                | X509KeyUsage.keyEncipherment));

        JcaX509ExtensionUtils extUtil = new JcaX509ExtensionUtils();
        // Add Subject, Authority data
        gen.addExtension(Extension.subjectKeyIdentifier, false, extUtil.createSubjectKeyIdentifier(keyPair.getPublic()));
        gen.addExtension(Extension.authorityKeyIdentifier, false, extUtil.createAuthorityKeyIdentifier(caCrt));
        if (isServer) {
            gen.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
        }

        final String BC = BouncyCastleProvider.PROVIDER_NAME;

        // Sign with CA's private key
        ContentSigner signer = new JcaContentSignerBuilder("SHA1WithRSA").setProvider(BC).build(caKey);
        // Build gives "Holder" object instead of cert directly
        X509CertificateHolder hold = gen.build(signer);
        X509Certificate cert = (new JcaX509CertificateConverter()).setProvider(BC).getCertificate(hold);
        return new CertPair(cert, keyPair.getPrivate());
    }

    /**
     * Write a Certificate to a Stream
     * 
     * @param cert Certificate to write
     * @param file Stream to write certificate to
     * @throws CertificateEncodingException
     * @throws UnsupportedEncodingException
     */
    public void writeCert(X509Certificate cert, PrintStream file)
            throws CertificateEncodingException, UnsupportedEncodingException {
        final String begin_cert = "-----BEGIN CERTIFICATE-----";
        final String end_cert = "-----END CERTIFICATE-----";

        Base64 encoder = new Base64(76, new byte[] { '\n' });
        file.println(begin_cert);
        file.print(encoder.encodeToString(cert.getEncoded()));
        file.println(end_cert);

    }

    /**
     * Write a Private Key to a Stream
     * 
     * @param key Private Key to write
     * @param file Stream to write the Key to
     * @throws FileNotFoundException
     * @throws CertificateEncodingException
     * @throws UnsupportedEncodingException
     */
    public void writeKey(PrivateKey key, PrintStream file)
            throws FileNotFoundException, CertificateEncodingException, UnsupportedEncodingException {
        final String begin_key = "-----BEGIN RSA PRIVATE KEY-----";
        final String end_key = "-----END RSA PRIVATE KEY-----";

        Base64 encoder = new Base64(76, new byte[] { '\n' });
        file.println(begin_key);
        file.print(encoder.encodeToString(key.getEncoded()));
        file.println(end_key);

    }

    private void writeDH(DHParameterSpec dhParam, PrintStream file) throws IOException {
        final String begin_key = "-----BEGIN DH PARAMETERS-----";
        final String end_key = "-----END DH PARAMETERS-----";

        Base64 encoder = new Base64(76, new byte[] { '\n' });
        file.println(begin_key);
        // No support for dhParam.getEncoded() like for keys, so manually build
        // structure
        ASN1EncodableVector seq = new ASN1EncodableVector();
        seq.add(new DERInteger(dhParam.getP()));
        seq.add(new DERInteger(dhParam.getG()));
        byte[] derEncoded = new DERSequence(seq).getEncoded();
        file.print(encoder.encodeToString(derEncoded));
        file.println(end_key);

    }

    /**
     * Generate DH Parameters and write them to a Stream
     * 
     * @param dhpem Stream to write the generated DHParameters to
     * @return Whether or not it was successful
     */
    public boolean genDHParams(PrintStream dhpem) {
        boolean failure = false;
        try {
            AlgorithmParameterGenerator paramGen = AlgorithmParameterGenerator.getInstance("DH");
            paramGen.init(512);
            AlgorithmParameters params = paramGen.generateParameters();
            DHParameterSpec dhSpec = params.getParameterSpec(DHParameterSpec.class);
            writeDH(dhSpec, dhpem);
        } catch (NoSuchAlgorithmException | InvalidParameterSpecException e) {
            logger.log(Level.SEVERE, "Check your JRE/JCE install", e);
            failure = true;
        } catch (UnsupportedEncodingException e) {
            logger.log(Level.SEVERE, "Check your JRE install", e);
            failure = true;
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Error writing DH Params", e);
            failure = true;
        }
        return !failure;
    }
};

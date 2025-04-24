package io.benwiegand.atvremote.receiver.auth.ssl;

import android.content.Context;
import android.util.Log;

import org.bouncycastle.asn1.x509.X509Name;
import org.bouncycastle.x509.X509V3CertificateGenerator;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.file.Path;
import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.SignatureException;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.time.Instant;
import java.util.Date;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

public class KeystoreManager {
    private static final String TAG = KeystoreManager.class.getSimpleName();

    // try to preserve forward-compatibility if possible
    private static final String KEYSTORE_TYPE = "BKS";
    private static final String KEYSTORE_TYPE_FALLBACK = KeyStore.getDefaultType();

    // before you scream, the password serves no purpose in this case. it's not part of the threat model
    private static final char[] KEYSTORE_PASSWORD = "hunter2".toCharArray();

    private static final String KEYPAIR_ALGORITHM = "RSA";
    private static final String SIGNING_ALGORITHM = "SHA256WithRSAEncryption";
    private static final int KEY_SIZE = 4096;

    private static final String SSL_SERVER_KEY_ALIAS = "atvr_server_key";
    public static final String CERTIFICATE_COMMON_NAME = "Bob";    // bob is a pretty common name

    private final File keystoreFile;
    private KeyStore keystore = null;
    private boolean modified = false;

    public KeystoreManager(Context context) {
        Path sslPath = context.getFilesDir().toPath().resolve("ssl");
        File sslDir = sslPath.toFile();
        keystoreFile = sslPath.resolve("keystore.jks").toFile();

        if (!(sslDir.isDirectory() || sslDir.mkdirs()))
            throw new RuntimeException("cannot make ssl directory");
    }

    public KeyManager[] getKeyManagers() throws CorruptedKeystoreException {
        try {
            KeyManagerFactory kmf = KeyManagerFactory.getInstance("X509");
            kmf.init(keystore, KEYSTORE_PASSWORD);

            return kmf.getKeyManagers();
        } catch (NoSuchAlgorithmException e) {
            throw new UnsupportedOperationException("keypair algorithm not supported", e);
        } catch (UnrecoverableKeyException e) {
            throw new CorruptedKeystoreException("keystore password rejected", e);
        } catch (KeyStoreException e) {
            throw new RuntimeException("failed to make a key manager", e);
        }
    }

    public TrustManager[] getTrustManagers() {
        try {
            TrustManagerFactory tmf = TrustManagerFactory.getInstance("X509");
            tmf.init(keystore);

            return tmf.getTrustManagers();
        } catch (NoSuchAlgorithmException e) {
            throw new UnsupportedOperationException("keypair algorithm not supported", e);
        } catch (KeyStoreException e) {
            throw new RuntimeException("failed to make a key manager", e);
        }
    }
    private InputStream getKeystoreInputStream() throws IOException {
        try {
            return new FileInputStream(keystoreFile);
        } catch (FileNotFoundException e) {
            // apparently this can throw even if the file exists (if there's another error)
            if (keystoreFile.isFile()) throw new IOException("keystore file open failed", e);

            Log.d(TAG, "keystore file not found");
            return null;
        }
    }

    private KeyStore getKeystoreInstance() {
        try {
            return KeyStore.getInstance(KEYSTORE_TYPE);
        } catch (KeyStoreException e) {
            if (KEYSTORE_TYPE.equals(KEYSTORE_TYPE_FALLBACK)) {
                Log.wtf(TAG, "failed to create keystore", e);
                throw new UnsupportedOperationException("Cannot create keystore of type " + KEYSTORE_TYPE);
            }

            Log.d(TAG, "failed to create keystore instance for " + KEYSTORE_TYPE + ", falling back to " + KEYSTORE_TYPE_FALLBACK, e);
            try {
                return KeyStore.getInstance(KEYSTORE_TYPE_FALLBACK);
            } catch (KeyStoreException ex) {
                Log.wtf(TAG, "couldn't create fallback keystore type either");
                throw new UnsupportedOperationException("Cannot create keystore of type " + KEYSTORE_TYPE + " or " + KEYSTORE_TYPE_FALLBACK, ex);
            }
        }

    }

    public void loadKeystore() throws CorruptedKeystoreException, IOException {
        KeyStore ks = getKeystoreInstance();

        try (InputStream is = getKeystoreInputStream()) {
            if (is == null)
                Log.i(TAG, "no keystore file, an empty one will be created");

            ks.load(is, KEYSTORE_PASSWORD);

            // reset modified state (empty keystore creation always counts)
            modified = is == null;

        } catch (IOException e) {               // io error, corrupted, or bad password
            if (e.getCause() instanceof UnrecoverableKeyException) {
                Log.wtf(TAG, "Keystore.load() reports that the keystore password is invalid. It's probably corrupted", e);
                throw new CorruptedKeystoreException("keystore password not working, likely corrupted", e);
            }

            // the keystore could be corrupted, or there's an io error
            throw e;

        } catch (CertificateException e) {      // a certificate couldn't be loaded
            throw new CorruptedKeystoreException("unable to load keystore due to a corrupted entry", e);
        } catch (NoSuchAlgorithmException e) {  // no algorithm to check integrity (unsupported?)
            throw new UnsupportedOperationException("unable to load keystore because there's no matching integrity checking algorithm", e);
        }

        keystore = ks;
    }

    public boolean deleteKeystore() {
        if (keystore != null) throw new IllegalStateException("keystore already loaded, refusing to delete");

        return !keystoreFile.isFile() || keystoreFile.delete();
    }

    private KeyPair generateKeypair() {
        try {
            Log.d(TAG, "generating a " + KEY_SIZE + "-bit " + KEYPAIR_ALGORITHM + " key");
            KeyPairGenerator keygen = KeyPairGenerator.getInstance(KEYPAIR_ALGORITHM);
            keygen.initialize(KEY_SIZE);
            return keygen.genKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new UnsupportedOperationException("no sufficient keypair algorithm found", e);
        }
    }

    private Certificate signKeypair(KeyPair keypair) {
        try {
            Log.d(TAG, "self-signing keypair with X509");
            X509V3CertificateGenerator certgen = new X509V3CertificateGenerator();
            X509Name commonName = new X509Name("CN=" + CERTIFICATE_COMMON_NAME);
            certgen.setIssuerDN(commonName);
            certgen.setSubjectDN(commonName);
            certgen.setPublicKey(keypair.getPublic());
            certgen.setSerialNumber(BigInteger.valueOf(42069));
            certgen.setNotBefore(Date.from(Instant.ofEpochSecond(0)));              // the date might be incorrectly set on first boot
            certgen.setNotAfter(Date.from(Instant.ofEpochSecond(99999999999L)));    // should hold us off until the far-off year of 5138
            certgen.setSignatureAlgorithm(SIGNING_ALGORITHM);
            return certgen.generate(keypair.getPrivate());
        } catch (CertificateEncodingException e) {
            throw new RuntimeException("failed to encode certificate", e);
        } catch (NoSuchAlgorithmException e) {
            throw new UnsupportedOperationException("no suitable signing algorithm found", e);
        } catch (SignatureException e) {
            throw new RuntimeException("failed to sign keypair", e);
        } catch (InvalidKeyException e) {
            throw new UnsupportedOperationException("bouncycastle rejected keys", e);
        }
    }

    public void initSSL() {
        if (keystore == null) throw new IllegalStateException("keystore must be loaded first");

        try {
            if (keystore.containsAlias(SSL_SERVER_KEY_ALIAS)) return;   // for now assume any existing key is sufficient

            KeyPair keypair = generateKeypair();
            Certificate cert = signKeypair(keypair);

            keystore.setKeyEntry(SSL_SERVER_KEY_ALIAS, keypair.getPrivate(), KEYSTORE_PASSWORD, new Certificate[] {cert});
        } catch (KeyStoreException e) {
            throw new RuntimeException("failed to store self-signed keypair", e);
        }

    }

    public Certificate getSSLCertificate() {
        if (keystore == null) throw new IllegalStateException("keystore must be loaded first");

        try {
            return keystore.getCertificate(SSL_SERVER_KEY_ALIAS);
        } catch (KeyStoreException e) {
            throw new IllegalStateException("keystore not initialized?", e);
        }

    }

    public void saveKeystore() throws IOException, CorruptedKeystoreException {
        if (keystore == null) throw new IllegalStateException("no currently loaded keystore to save");
        if (!modified) {
            Log.d(TAG, "not saving keystore because it wasn't modified");
            return;
        }

        if (keystoreFile.isFile()) Log.v(TAG, "overwriting existing keystore at: " + keystoreFile);
        else Log.v(TAG, "saving keystore as: " + keystoreFile);

        try (FileOutputStream os = new FileOutputStream(keystoreFile)) {

            keystore.store(os, KEYSTORE_PASSWORD);
            modified = false;

        } catch (FileNotFoundException e) {
            Log.e(TAG, "cannot open keystore file for writing", e);
            throw e;
        } catch (IOException e) {
            Log.e(TAG, "failed to write keystore", e);
            throw e;
        } catch (CertificateException e) {
            Log.wtf(TAG, "failed to store a certificate within the keystore", e);
            throw new CorruptedKeystoreException("failed to store certificate", e);
        } catch (KeyStoreException e) {
            Log.wtf(TAG, "keystore was never loaded", e);
            throw new IllegalStateException("keystore was never loaded?", e);
        } catch (NoSuchAlgorithmException e) {
            Log.e(TAG, "algorithm for verifying keystore integrity not supported", e);
            throw new UnsupportedOperationException("unable to save keystore because there's no matching integrity checking algorithm", e);
        }
    }

}

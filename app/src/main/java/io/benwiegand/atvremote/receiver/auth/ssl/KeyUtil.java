package io.benwiegand.atvremote.receiver.auth.ssl;

import android.util.Log;

import org.bouncycastle.jce.provider.JDKMessageDigest;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;

public class KeyUtil {
    private static final String TAG = KeyUtil.class.getSimpleName();

    private static MessageDigest getSha256Digest() {
        try {
            return MessageDigest.getInstance("SHA256");
        } catch (NoSuchAlgorithmException e) {
            Log.w(TAG, "no JDK support for SHA256");
            return new JDKMessageDigest.SHA256();   // if I'm gonna be forced to have bouncycastle I might as well use it
        }
    }

    public static byte[] calculateCertificateFingerprint(Certificate cert) throws CorruptedKeystoreException {
        try {
            return getSha256Digest().digest(cert.getEncoded());
        } catch (CertificateEncodingException e) {
            throw new CorruptedKeystoreException("certificate encoding is invalid", e);
        }
    }

    public static SecureRandom getSecureRandom() {
        try {
            return SecureRandom.getInstanceStrong();
        } catch (NoSuchAlgorithmException e) {
            Log.w(TAG, "can't create strong secure random!", e);
        }

        return new SecureRandom();
    }

}

import com.android.apksig.ApkSigner;

import java.io.File;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Collections;

/** Signs an APK with an APK Signature Scheme v2 signature using Google's apksig library. */
public class Sign {
    public static void main(String[] a) throws Exception {
        String keystore = a[0], password = a[1], alias = a[2];
        File in = new File(a[3]), out = new File(a[4]);
        int minSdk = Integer.parseInt(a[5]);

        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream f = new FileInputStream(keystore)) {
            ks.load(f, password.toCharArray());
        }
        PrivateKey key = (PrivateKey) ks.getKey(alias, password.toCharArray());
        X509Certificate cert = (X509Certificate) ks.getCertificate(alias);

        ApkSigner.SignerConfig signer = new ApkSigner.SignerConfig.Builder(
                "GLYPHSTO", key, Collections.singletonList(cert)).build();
        new ApkSigner.Builder(Collections.singletonList(signer))
                .setInputApk(in)
                .setOutputApk(out)
                .setMinSdkVersion(minSdk)
                .setV1SigningEnabled(false) // v1 is only for Android 7 and older; minSdk is 33
                .setV2SigningEnabled(true)
                .build()
                .sign();
        System.out.println("signed " + out);
    }
}

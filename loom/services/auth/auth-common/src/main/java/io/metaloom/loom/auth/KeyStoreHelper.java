package io.metaloom.loom.auth;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.util.Arrays;
import java.util.Objects;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

import org.apache.commons.io.FileExistsException;

public class KeyStoreHelper {

	/**
	 * Returns the keystore type to use. PKCS12 is used unconditionally because
	 * JCEKS depends on JCE provider JAR verification which is not implemented
	 * in GraalVM Substrate VM (JarVerifier.verifyJars is intentionally
	 * unimplemented). PKCS12 supports SecretKeyEntry with HMAC keys since
	 * Java 9 and is the JDK default, so it is functionally equivalent for
	 * Loom's use both on the JVM and as a native image.
	 */
	public static String keystoreType() {
		return "pkcs12";
	}

	/**
	 * Create a keystore for the given path and store various keys in it which are needed for JWT.
	 * 
	 * @param keystorePath
	 * @param keystorePassword
	 * @throws NoSuchAlgorithmException
	 *             Thrown if the HmacSHA256 algorithm could not be found
	 * @throws KeyStoreException
	 * @throws IOException
	 * @throws CertificateException
	 */
	public static void gen(String keystorePath, String keystorePassword)
		throws NoSuchAlgorithmException, KeyStoreException, CertificateException, IOException {
		Objects.requireNonNull(keystorePassword, "The keystore password must be specified.");
		File keystoreFile = new File(keystorePath);
		if (keystoreFile.exists()) {
			throw new FileExistsException(keystoreFile);
		} else {
			if (keystoreFile.getParentFile() != null) {
				keystoreFile.getParentFile().mkdirs();
			}
			keystoreFile.createNewFile();
		}

		KeyStore keystore = KeyStore.getInstance(keystoreType());
		keystore.load(null, null);
		for (String type : Arrays.asList("SHA256", "SHA384", "SHA512")) {
			KeyGenerator keygen = KeyGenerator.getInstance("Hmac" + type);
			SecretKey key = keygen.generateKey();
			String entryKey = type.replace("SHA", "HS");
			keystore.setKeyEntry(entryKey, key, keystorePassword.toCharArray(), null);
		}

		try (FileOutputStream fos = new FileOutputStream(keystoreFile)) {
			keystore.store(fos, keystorePassword.toCharArray());
			fos.flush();
		}
	}
}

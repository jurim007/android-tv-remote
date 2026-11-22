import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

class SignUpdateIntegrity {
    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException("Usage: SignUpdateIntegrity.java APK PACKAGE SIGNING_STORE PASSWORD_FILE");
        }
        var apk = Path.of(args[0]);
        var filename = Pattern.compile("TVControl-v([0-9]+\\.[0-9]+\\.[0-9]+)\\.apk")
            .matcher(apk.getFileName().toString());
        if (!filename.matches()) throw new IllegalArgumentException("Expected TVControl-vMAJOR.MINOR.PATCH.apk");
        var password = Files.readString(Path.of(args[3])).strip().toCharArray();
        PrivateKey key;
        try {
            var store = KeyStore.getInstance(Path.of(args[2]).toFile(), password);
            var aliases = store.aliases();
            String keyAlias = null;
            while (aliases.hasMoreElements()) {
                var alias = aliases.nextElement();
                if (!store.isKeyEntry(alias)) continue;
                if (keyAlias != null) throw new IllegalArgumentException("Expected one signing key");
                keyAlias = alias;
            }
            if (keyAlias == null) throw new IllegalArgumentException("No signing key found");
            key = (PrivateKey) store.getKey(keyAlias, password);
        } finally {
            Arrays.fill(password, '\0');
        }

        var digest = MessageDigest.getInstance("SHA-256");
        long size;
        try (var input = new DigestInputStream(Files.newInputStream(apk), digest)) {
            size = input.transferTo(java.io.OutputStream.nullOutputStream());
        }
        var checksum = HexFormat.of().formatHex(digest.digest());
        var body = "TVControl update v1\n" + args[1] + "\n" + filename.group(1) + "\n"
            + size + "\n" + checksum + "\n";
        var signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(key);
        signer.update(body.getBytes(StandardCharsets.UTF_8));
        var signature = Base64.getEncoder().encodeToString(signer.sign());
        Files.writeString(apk.resolveSibling(apk.getFileName() + ".integrity"), checksum + "\n" + signature + "\n");
    }
}

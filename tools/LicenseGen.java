import java.security.*;
import java.security.spec.*;
import java.util.Base64;

/**
 * Генератор/подписыватель лицензионных ключей премиума с ПРИВЯЗКОЙ К УСТРОЙСТВУ
 * (EC P-256 / SHA256withECDSA). Ключ действует только на устройстве с заданным deviceId
 * (Android ID). Работает на JBR/JDK 11+ без установки Python.
 *
 *   java LicenseGen.java gen                                  # создать пару ключей
 *   java LicenseGen.java sign <PRIV_B64> <deviceId> [note]    # выдать ключ для устройства
 *   java LicenseGen.java verify <PUB_B64> <deviceId> <key>    # самопроверка
 *
 * payload = "PREMIUM:" + deviceId + ":" + time36 [+ ":" + note]
 * key     = base64url(payload) + "." + base64url(signatureDER)
 */
public class LicenseGen {
    public static void main(String[] a) throws Exception {
        if (a.length == 0) { System.out.println("usage: gen | sign <privB64> <deviceId> [note] | verify <pubB64> <deviceId> <key>"); return; }
        Base64.Encoder u = Base64.getUrlEncoder().withoutPadding();
        Base64.Decoder ud = Base64.getUrlDecoder();
        switch (a[0]) {
            case "gen": {
                KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
                kpg.initialize(new ECGenParameterSpec("secp256r1"));
                KeyPair kp = kpg.generateKeyPair();
                System.out.println("PUBLIC_B64=" + Base64.getEncoder().encodeToString(kp.getPublic().getEncoded()));
                System.out.println("PRIVATE_B64=" + Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded()));
                break;
            }
            case "sign": {
                PrivateKey pk = KeyFactory.getInstance("EC")
                        .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(a[1])));
                String deviceId = a[2];
                String note = a.length > 3 ? a[3] : "";
                String payload = "PREMIUM:" + deviceId + ":" + Long.toString(System.currentTimeMillis() / 1000, 36)
                        + (note.isEmpty() ? "" : ":" + note);
                Signature s = Signature.getInstance("SHA256withECDSA");
                s.initSign(pk);
                s.update(payload.getBytes("UTF-8"));
                System.out.println("KEY=" + u.encodeToString(payload.getBytes("UTF-8")) + "." + u.encodeToString(s.sign()));
                break;
            }
            case "verify": {
                PublicKey pub = KeyFactory.getInstance("EC")
                        .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(a[1])));
                String deviceId = a[2];
                String[] parts = a[3].split("\\.");
                byte[] payload = ud.decode(parts[0]);
                byte[] sig = ud.decode(parts[1]);
                Signature s = Signature.getInstance("SHA256withECDSA");
                s.initVerify(pub);
                s.update(payload);
                String p = new String(payload, "UTF-8");
                boolean ok = s.verify(sig) && p.startsWith("PREMIUM:" + deviceId + ":");
                System.out.println("VALID=" + ok + " payload=" + p);
                break;
            }
            default: System.out.println("unknown: " + a[0]);
        }
    }
}

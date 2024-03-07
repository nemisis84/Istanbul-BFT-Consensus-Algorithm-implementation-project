package pt.ulisboa.tecnico.hdsledger.utilities;

import java.io.FileInputStream;
import java.io.IOException;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

public class ProcessConfig {
    public ProcessConfig() {}

    private boolean isLeader;

    private String hostname;

    private String id;

    private int port;

    public boolean isLeader() {
        return isLeader;
    }

    public void setleader(boolean isLeader) {
        this.isLeader = isLeader;
    }

    public int getPort() {
        return port;
    }

    public String getId() {
        return id;
    }

    public String getHostname() {
        return hostname;
    }

    public PrivateKey getPrivateKey() {
        try {
            String keyPath = "../Utilities/keys/key" + getId() + ".priv";
            byte[] encoded = readKey(keyPath);
            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(encoded);
            KeyFactory keyFac = KeyFactory.getInstance("RSA");
            return keyFac.generatePrivate(spec);
        } catch (Exception e) {
            return null;
        }
    }

    public PublicKey getPublicKey() {
        return getNodePubKey(getId());
    }

    public PublicKey getNodePubKey(String id) {
        try {
            String keyPath = "../Utilities/keys/key" + id + ".pub";
            byte[] encoded = readKey(keyPath);
            X509EncodedKeySpec spec = new X509EncodedKeySpec(encoded);
            KeyFactory keyFac = KeyFactory.getInstance("RSA");
            return keyFac.generatePublic(spec);
        } catch (Exception e) {
            return null;
        }
    }


    // Read key bytes from file
    private byte[] readKey(String keyPath) throws IOException {
        FileInputStream fis = new FileInputStream(keyPath);
        byte[] encoded = new byte[fis.available()];
        fis.read(encoded);
        fis.close();

        return encoded;
    }
}

package pt.ulisboa.tecnico.hdsledger.utilities;

import java.io.FileOutputStream;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;


public class generate_keys {
// Generates pair of public and private keys and stores them
public static void main(String[] args) throws Exception {
    // String KEYS_PATH = "HDSLedger/Utlities/keys/keyclient2";
    String KEYS_PATH = "keyclient3";
    String privateKeyPath = KEYS_PATH + ".priv";
    String publicKeyPath = KEYS_PATH + ".pub";

    System.out.println("Generating RSA key ...");
    KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
    keyGen.initialize(1024);
    KeyPair keys = keyGen.generateKeyPair();

    PrivateKey privKey = keys.getPrivate();
    byte[] privKeyEncoded = privKey.getEncoded();

    PublicKey pubKey = keys.getPublic();
    byte[] pubKeyEncoded = pubKey.getEncoded();

    System.out.println("Writing Private key to '" + privateKeyPath + "' ...");
    FileOutputStream privFos = new FileOutputStream(privateKeyPath);
    privFos.write(privKeyEncoded);
    privFos.close();
    System.out.println("Writing Pubic key to '" + publicKeyPath + "' ...");
    FileOutputStream pubFos = new FileOutputStream(publicKeyPath);
    pubFos.write(pubKeyEncoded);
    pubFos.close();
}

}
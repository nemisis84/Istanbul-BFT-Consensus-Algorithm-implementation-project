package pt.ulisboa.tecnico.hdsledger.utilities;

import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SignatureException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;

public class Authenticate {
    // Method to sign a message
    public static byte[] signMessage(PrivateKey privateKey, String message) throws NoSuchAlgorithmException, InvalidKeyException, SignatureException {
        Signature signature = Signature.getInstance("SHA256withRSA");

        signature.initSign(privateKey);
        // Update the signature object with the message
        signature.update(message.getBytes());

        return signature.sign();
    }

    // Method to verify a message
    public static boolean verifyMessage(PublicKey publicKey, String message, byte[] signature) throws NoSuchAlgorithmException, InvalidKeyException, SignatureException {
        Signature signature_verify = Signature.getInstance("SHA256withRSA");

        signature_verify.initVerify(publicKey);
        // Update the signature_verifynature object with the message
        signature_verify.update(message.getBytes());

        return signature_verify.verify(signature);
    }
}
package pt.ulisboa.tecnico.hdsledger.communication;


public class ClientData {
    
    private String value;

    private int requestID;

    private String clientID;

    private byte[] signature;

    public String getValue() {
        return value;
    }

    public int getRequestID() {
        return requestID;
    }

    public String getClientID() {
        return clientID;
    }

    public byte[] getSignature() {
        return signature;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public void setRequestID(int messageID) {
        this.requestID = messageID;
    }

    public void setClientID(String clientID) {
        this.clientID = clientID;
    }

    public void setSignature(byte[] signature) {
        this.signature = signature;
    }

    
}

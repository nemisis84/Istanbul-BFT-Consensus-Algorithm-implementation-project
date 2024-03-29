package pt.ulisboa.tecnico.hdsledger.communication;

import com.google.gson.Gson;


public class BalanceMessage extends Message {

    private float balance;
    private int requestID;
    private String clientID;
    private String requestedClient;

    public BalanceMessage(String senderID, Type type) {
        super(senderID, type);
    }

    public void setBalance(float balance) {
        this.balance = balance;
    }

    public void setClientId(String clientID) {
        this.clientID = clientID;
    }

    public void setRequestId(Integer requestID) {
        this.requestID = requestID;
    }

    public float getBalance() {
        return balance;
    }

    public Integer getRequestID() {
        return requestID;
    }

    public String getClientID() {
        return clientID;
    }

    public String toJson() {
        return new Gson().toJson(this);
    }

    public String getRequestedClient() {
        return requestedClient;
    }

    public void setRequestedClient(String requestedClient) {
        this.requestedClient = requestedClient;
    }

}

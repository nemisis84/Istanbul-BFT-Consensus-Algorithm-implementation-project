package pt.ulisboa.tecnico.hdsledger.communication;

public class ClientMessage extends Message {

    private ClientData clientData;
    
    public ClientMessage(String senderId, Type type) {
        super(senderId, type);
    }

    public ClientData getClientData() {
        return clientData;
    }

    public void setClientData(ClientData clientData) {
        this.clientData = clientData;
    }
}

package pt.ulisboa.tecnico.hdsledger.client.services;

import java.security.PrivateKey;
import java.util.concurrent.atomic.AtomicInteger;

import pt.ulisboa.tecnico.hdsledger.communication.ClientData;
import pt.ulisboa.tecnico.hdsledger.communication.ClientMessage;
import pt.ulisboa.tecnico.hdsledger.communication.Message;
import pt.ulisboa.tecnico.hdsledger.utilities.Authenticate;
import pt.ulisboa.tecnico.hdsledger.utilities.ProcessConfig;

public class ClientMessageBuilder {

    // Client request instance
    private final AtomicInteger clientRequest = new AtomicInteger(0);
    // Client key private 
    private PrivateKey privateKey;

    public ClientMessageBuilder(ProcessConfig processConfig){
        this.privateKey = processConfig.getPrivateKey();
    }

    public ClientMessage buildMessage(String payload, String senderID){
        
        ClientMessage clientMessage = new ClientMessage(senderID, Message.Type.APPEND);
        
        ClientData clientData = new ClientData();
        clientData.setClientID(senderID);
        clientData.setRequestID(this.clientRequest.getAndIncrement());
        
        try{
            byte[] signature = Authenticate.signMessage(this.privateKey, payload);
            clientData.setSignature(signature);
        } catch (Exception e) {
            System.out.println("Error signing value");
        }

        clientData.setValue(payload);

        clientMessage.setClientData(clientData);

        return clientMessage;
    }
}

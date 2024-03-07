package pt.ulisboa.tecnico.hdsledger.communication;

public class ClientMessage extends Message {
    
    String value;
    
    public ClientMessage(String senderId, Type type) {
        super(senderId, type);
    }

    public void setValue(String value){
        this.value = value;
    }

    public String getValue(){
        return this.value;
    }
}

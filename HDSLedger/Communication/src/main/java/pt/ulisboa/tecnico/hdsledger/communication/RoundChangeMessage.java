package pt.ulisboa.tecnico.hdsledger.communication;

import com.google.gson.Gson;

public class RoundChangeMessage extends Message {

    // Consensus instance
    private int consensusInstance;
    // Round
    private int round;

    private int prepared_round;

    private String prepared_value;
    
    public RoundChangeMessage(String senderId, Type type, int consensusInstance, int round, int prepared_round, String prepared_value) {
        super(senderId, type);
        this.consensusInstance = consensusInstance;
        this.round = round;
        this.prepared_round = prepared_round;
        this.prepared_value = prepared_value;
    }

    public int getConsensusInstance() {
        return consensusInstance;
    }

    public void setConsensusInstance(int consensusInstance) {
        this.consensusInstance = consensusInstance;
    }

    public int getRound() {
        return round;
    }

    public void setRound(int round) {
        this.round = round;
    }

    public int getPreparedRound() {
        return prepared_round;
    }

    public void setPreparedRound(int prepared_round) {
        this.prepared_round = prepared_round;
    }

    // Getter and setter for prepared_value
    public String getPreparedValue() {
        return prepared_value;
    }

    public void setPreparedValue(String prepared_value) {
        this.prepared_value = prepared_value;
    }

    public String toJson() {
        return new Gson().toJson(this);
    }

}

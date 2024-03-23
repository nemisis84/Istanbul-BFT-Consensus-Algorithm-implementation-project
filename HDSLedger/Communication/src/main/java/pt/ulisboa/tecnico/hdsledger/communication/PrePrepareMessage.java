package pt.ulisboa.tecnico.hdsledger.communication;

import com.google.gson.Gson;

import pt.ulisboa.tecnico.hdsledger.service.blockchain.Block;

public class PrePrepareMessage {
    
    // Value
    private Block block;

    public PrePrepareMessage(Block block) {
        this.block = block;
    }

    public Block getBlock() {
        return this.block;
    }

    public String toJson() {
        return new Gson().toJson(this);
    }
}   

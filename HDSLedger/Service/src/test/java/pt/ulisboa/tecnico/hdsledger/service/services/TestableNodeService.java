package pt.ulisboa.tecnico.hdsledger.service.services;

import pt.ulisboa.tecnico.hdsledger.communication.Link;
import pt.ulisboa.tecnico.hdsledger.utilities.ProcessConfig;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import pt.ulisboa.tecnico.hdsledger.communication.ClientData;
import pt.ulisboa.tecnico.hdsledger.service.blockchain.Block;
import pt.ulisboa.tecnico.hdsledger.service.blockchain.Blockchain;
import pt.ulisboa.tecnico.hdsledger.communication.CommitMessage;
import pt.ulisboa.tecnico.hdsledger.communication.ConsensusMessage;
import pt.ulisboa.tecnico.hdsledger.communication.builder.ConsensusMessageBuilder;
import pt.ulisboa.tecnico.hdsledger.service.models.InstanceInfo;
import pt.ulisboa.tecnico.hdsledger.communication.Message;
import pt.ulisboa.tecnico.hdsledger.communication.PrepareMessage;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import com.google.gson.Gson;

public class TestableNodeService extends NodeService {

    public TestableNodeService(Link link, ProcessConfig nodeConfig, ProcessConfig leaderConfig,
            ProcessConfig[] nodesConfig) {
        // Add clients configs to the link, so node can send messages to clients
        super(link, nodeConfig, leaderConfig, nodesConfig);
    }

    @Override
    public void startConsensusWithTimer() {
        System.out.println("Timer started");
    }

    // Shut down currently running services
    public void shutdown() {
        System.out.println("Shutting down NodeService...");

        // Shutdown Link or network components
        if (link != null) {
            link.shutdown(); // Ensure this method properly closes sockets and cleans up resources.
        }
        System.out.println("NodeService shutdown completed.");
    }

    public String addToTransactionQueueAndCreateBlock(ClientData clientData) {
        // Add to transaction queue
        super.addToTransactionQueue(clientData);

        // Create block from transactions in the queue
        Block block = super.blockCreator();

        // Store the block with a corresponding hash as key
        try {
            String blockHash = Blockchain.calculateHash(block);
            super.blockNumberToBlockMapping.put(block.getBLOCK_ID(), block);
            setupInstanceInfoForBlock(blockHash, 1);
            return blockHash;
        } catch (NoSuchAlgorithmException | IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    public void setupInstanceInfoForBlock(String blockHash, int targetRound) {
        int currentInstance = super.getConsensusInstance().get();
        InstanceInfo instanceInfo = new InstanceInfo(blockHash);
        instanceInfo.setCurrentRound(targetRound); // Assuming the initial round starts at 1
        super.getInstanceInfo().put(currentInstance, instanceInfo);
    }

    public void sendCommitMessages(String blockHash, int number) {
        int consensusInstance = super.getConsensusInstance().get();
        int round = 1;

        CommitMessage commitMessage = new CommitMessage(blockHash);

        for (int i = 0; i < number; i++) {
            ConsensusMessage consensusMessage = new ConsensusMessageBuilder(String.valueOf(i + 1), Message.Type.COMMIT)
                    .setConsensusInstance(consensusInstance)
                    .setRound(round)
                    .setMessage(commitMessage.toJson())
                    .build();
            super.uponCommit(consensusMessage);
        }
    }

    public void sendPrepareMessages(String blockHash, int numberOfMessages) {
        int consensusInstance = super.getConsensusInstance().get();
        int round = 1;

        // Create a PrepareMessage object containing the blockHash
        PrepareMessage prepareMessage = new PrepareMessage(blockHash);
        String prepareMessageJson = new Gson().toJson(prepareMessage);

        for (int i = 0; i < numberOfMessages; i++) {
            ConsensusMessage consensusMessage = new ConsensusMessageBuilder(String.valueOf(i + 1), Message.Type.PREPARE)
                    .setConsensusInstance(consensusInstance)
                    .setRound(round)
                    .setMessage(prepareMessageJson) // Pass the serialized PrepareMessage JSON here
                    .build();
            super.uponPrepare(consensusMessage);
        }
    }

    public int getQuorum() {
        int f = Math.floorDiv(nodesConfig.length - 1, 3);
        return Math.floorDiv(nodesConfig.length + f, 2) + 1;
    }

}
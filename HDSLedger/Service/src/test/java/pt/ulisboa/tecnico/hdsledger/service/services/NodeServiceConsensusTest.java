package pt.ulisboa.tecnico.hdsledger.service.services;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.mockito.Spy;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doNothing;

import java.util.Map;

import com.google.gson.Gson;

import pt.ulisboa.tecnico.hdsledger.communication.Link;
import pt.ulisboa.tecnico.hdsledger.communication.Message;
import pt.ulisboa.tecnico.hdsledger.communication.ClientData;
import pt.ulisboa.tecnico.hdsledger.communication.ClientMessage;
import pt.ulisboa.tecnico.hdsledger.communication.CommitMessage;
import pt.ulisboa.tecnico.hdsledger.communication.PrepareMessage;
import pt.ulisboa.tecnico.hdsledger.communication.ConsensusMessage;
import pt.ulisboa.tecnico.hdsledger.communication.PrePrepareMessage;
import pt.ulisboa.tecnico.hdsledger.communication.RoundChangeMessage;
import pt.ulisboa.tecnico.hdsledger.communication.builder.ConsensusMessageBuilder;

import pt.ulisboa.tecnico.hdsledger.service.blockchain.Block;
import pt.ulisboa.tecnico.hdsledger.service.models.InstanceInfo;
import pt.ulisboa.tecnico.hdsledger.service.blockchain.Blockchain;

public class NodeServiceConsensusTest extends NodeServiceBaseTest {

    // Test that a replayed message is not accepted for quorum
    @Test
    private void testRejectReplayedMessage() {
        System.out.println("Reject replayed message");

        ClientData clientData = super.setupClientData("20 client2 1");
        String blockHash = nodeService.addToTransactionQueueAndCreateBlock(clientData);

        // Assuming setupInstanceInfoForBlock has already been called inside
        // addToTransactionQueueAndCreateBlock
        nodeService.sendPrepareMessages(blockHash, nodeService.getQuorum());

        // Verify that the commit messages were sent
        verify(super.linkSpy, times(nodeService.getQuorum())).send(anyString(),
                argThat(argument -> argument instanceof ConsensusMessage
                        && ((ConsensusMessage) argument).getType() == Message.Type.COMMIT));

        // Reset the link spy to clear the interactions
        Mockito.reset(super.linkSpy);

        // Send the same prepare messages again
        nodeService.sendPrepareMessages(blockHash, nodeService.getQuorum());

        // Verify that no commit messages were sent
        verify(super.linkSpy, times(0)).send(anyString(), argThat(argument -> argument instanceof ConsensusMessage
                && ((ConsensusMessage) argument).getType() == Message.Type.COMMIT));
    }

    // Test a byzantine leader sending conflicting pre-prepare messages
    // Should result in a round change
    // Will generate some socket exceptions, ignore them
    @Test
    public void testConflictingLeaderPrePrepareMessages() {
        System.out.println("Testing Byzantine leader sending conflicting pre-prepare messages...");

        super.setupAllNodes();
        super.setupClient();

        // Get leader node
        TestableNodeService leader = allNodes.get(super.leaderId);

        // Simulate Byzantine leader sending conflicting pre-prepare messages
        int consensusInstance = nodeService.getConsensusInstance().get();
        int round = 2;

        // Create a PrepareMessage object. Assume leader is Byzantine and does not have
        // the correct block hash
        PrePrepareMessage prePrepareMessage = new PrePrepareMessage("incorrectBlockHash");
        String preprepareMessageJson = new Gson().toJson(prePrepareMessage);

        for (Map.Entry<String, TestableNodeService> entry : allNodes.entrySet()) {
            TestableNodeService node = entry.getValue();
            node.listen();
            if (node.getConfig().getId().equals(super.leaderId)) {
                continue;
            }
            node.startConsensus();
        }
        // Send the conflicting pre-prepare message to all nodes
        for (Map.Entry<String, TestableNodeService> entry : allNodes.entrySet()) {
            TestableNodeService node = entry.getValue();

            ConsensusMessage consensusMessage = new ConsensusMessageBuilder(super.leaderId, Message.Type.PRE_PREPARE)
                    .setConsensusInstance(consensusInstance)
                    .setRound(round)
                    .setMessage(preprepareMessageJson) // Pass the serialized PrepareMessage JSON here
                    .build();
            node.uponPrePrepare(consensusMessage);
        }

        // Wait 7 seconds for round change
        try {
            System.out.println("Waiting for round change...");
            Thread.sleep(7000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        // Verify that round change is performed
        verify(super.linkSpy, times(nodeConfigs.length)).send(anyString(),
                argThat(argument -> argument instanceof RoundChangeMessage
                        && ((RoundChangeMessage) argument).getType() == Message.Type.ROUND_CHANGE));
        assertEquals(false, nodeService.isLeader(super.leaderId));
        assertEquals(true, nodeService.isLeader("2"));

    }

    // Test that updateLeader is done correctly
    @Test
    public void testUpdateLeader() {
        System.out.println("Update leader test...");
        String newLeaderId = "2";

        // Prevent the real send method from being called on the spy
        doNothing().when(super.linkSpy).send(anyString(), any(ConsensusMessage.class));

        // Increment the current round to simulate a round change
        int initialConsensusInstance = nodeService.getConsensusInstance().get();

        nodeService.setupInstanceInfoForBlock("testString", 1);

        // Retrieve the updated instance info
        InstanceInfo instance = nodeService.getInstanceInfo().get(initialConsensusInstance);

        int targetRound = instance.getCurrentRound() + 1;
        instance.setCurrentRound(targetRound);

        // Act
        nodeService.updateLeader();

        assertEquals(true, nodeService.isLeader(newLeaderId));
    }

    // Test that commit is sent if there is a quorum of prepare messages
    @Test
    public void testCommitOnPrepareQuorum() {
        System.out.println("Commit on valid quorum...");
        // Prevent the real send method from being called on the spy
        doNothing().when(super.linkSpy).send(anyString(), any(ConsensusMessage.class));

        ClientData clientData = super.setupClientData("20 client2 1");
        String blockHash = nodeService.addToTransactionQueueAndCreateBlock(clientData);
        // nodeService.startConsensus();
        nodeService.sendPrepareMessages(blockHash, nodeService.getQuorum());

        int quorum = nodeService.getQuorum();

        // Check that the commit message was sent
        verify(super.linkSpy, times(quorum)).send(anyString(),
                argThat(argument -> argument instanceof ConsensusMessage &&
                        ((ConsensusMessage) argument).getType() == Message.Type.COMMIT));

    }

    // Test that a byzantine node sends invalid data
    // Should not commit, but should not crash
    @Test
    public void testInvalidData() {
        System.out.println("Byzantine node invalid data test");

        // Prepare unsigned client data
        ClientData unsignedClientData = new ClientData();
        unsignedClientData.setRequestID(1); // Example request ID
        unsignedClientData.setValue("invalid data"); // Transaction value
        unsignedClientData.setClientID("client1"); // Client ID

        ClientMessage falseClientMessage = new ClientMessage(unsignedClientData.getClientID(), Message.Type.TRANSFER);
        falseClientMessage.setClientData(unsignedClientData);

        String blockHash = nodeService.addToTransactionQueueAndCreateBlock(unsignedClientData);

        // Assuming setupInstanceInfoForBlock has already been called inside
        // addToTransactionQueueAndCreateBlock
        nodeService.sendCommitMessages(blockHash, nodeService.getQuorum());

        // Verify no commit messages were sent due to the unsigned client data
        verify(super.linkSpy, times(0)).send(anyString(), argThat(argument -> argument instanceof ConsensusMessage
                && ((ConsensusMessage) argument).getType() == Message.Type.COMMIT));
    }

}

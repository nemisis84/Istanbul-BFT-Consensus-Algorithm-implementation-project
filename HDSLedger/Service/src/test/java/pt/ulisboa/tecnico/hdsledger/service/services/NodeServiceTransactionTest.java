package pt.ulisboa.tecnico.hdsledger.service.services;

import java.util.Map;

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
import static org.mockito.Mockito.doNothing;
import static org.mockito.ArgumentMatchers.*;

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

public class NodeServiceTransactionTest extends NodeServiceBaseTest {

    // Test that block is appended to blockchain if valid commit quorum
    @Test
    public void testCommitAddsTransactionToBlock() {
        System.out.println("Add transaction to block on commit...");
        int ledgerLengthBefore = nodeService.getBlockchain().getLength();

        ClientData clientData = super.setupClientData("20 client2 1");
        String blockHash = nodeService.addToTransactionQueueAndCreateBlock(clientData);
        // Prevent the real send method from being called on the spy
        doNothing().when(super.linkSpy).send(anyString(), any(ConsensusMessage.class));

        // Assuming setupInstanceInfoForBlock has already been called inside
        // addToTransactionQueueAndCreateBlock
        nodeService.sendCommitMessages(blockHash, nodeService.getQuorum());

        // Check that the block was added to the blockchain
        Block latestBlock = nodeService.getBlockchain().getLatestBlock();
        assertEquals(ledgerLengthBefore + 1, nodeService.getBlockchain().getLength());
        assertEquals(1, latestBlock.getTransactions().size());
        assertTrue(latestBlock.getTransactions().contains(clientData));
    }

    // Test that block is not appended to blockchain if invalid commit quorum
    @Test
    public void testNoCommitNoTransactionToBlock() {
        System.out.println("No transaction to block on no commit...");
        int ledgerLengthBefore = nodeService.getBlockchain().getLength();

        ClientData clientData = super.setupClientData("20 client2 1");
        String blockHash = nodeService.addToTransactionQueueAndCreateBlock(clientData);

        // Assuming setupInstanceInfoForBlock has already been called inside
        // addToTransactionQueueAndCreateBlock
        nodeService.sendCommitMessages(blockHash, nodeService.getQuorum() - 1);

        // Check that the block was not added to the blockchain
        assertEquals(ledgerLengthBefore, nodeService.getBlockchain().getLength());
    }

    // Test client balances after a successful transaction
    // Balance should be updated correctly for both nodes
    @Test
    public void testClientBalances() {
        System.out.println("Client balances test");
        // Prevent the real send method from being called on the spy
        doNothing().when(super.linkSpy).send(anyString(), any(ConsensusMessage.class));

        // Set up client balances
        nodeService.initialiseClientBalances(super.clientConfigs);

        // Set up client data
        ClientData clientData = super.setupClientData("20 client2 1");
        String blockHash = nodeService.addToTransactionQueueAndCreateBlock(clientData);

        // Assuming setupInstanceInfoForBlock has already been called inside
        // addToTransactionQueueAndCreateBlock
        nodeService.sendCommitMessages(blockHash, nodeService.getQuorum());

        // Check that the balances were updated correctly
        assertEquals(80f, nodeService.clientBalances.getOrDefault("client1", 0.0f));
        Double result = 120 - (20 * 0.1);
        Float receiverBalance = result.floatValue();
        assertEquals(receiverBalance, nodeService.clientBalances.getOrDefault("client2", 0.0f));
    }

    // Test client trying to send more money than they have
    // Should not update balances
    @Test
    public void testClientOverspend() {
        System.out.println("Client balances overspend test");

        // Set up client balances
        nodeService.initialiseClientBalances(super.clientConfigs);

        // Set up client data
        ClientData clientData = super.setupClientData("200 client2 1");

        String blockHash = nodeService.addToTransactionQueueAndCreateBlock(clientData);

        // Assuming setupInstanceInfoForBlock has already been called inside
        // addToTransactionQueueAndCreateBlock
        nodeService.sendCommitMessages(blockHash, nodeService.getQuorum());

        // Check that the balances were not updated
        assertEquals(100f, nodeService.clientBalances.getOrDefault("client1", 0.0f));
        assertEquals(100f, nodeService.clientBalances.getOrDefault("client2", 0.0f));
    }

    // Test that a client can't send money to non-existent clients
    @Test
    public void testClientBalancesNonExistentClient() {
        System.out.println("Client balances non-existent client test");

        // Set up client balances
        nodeService.initialiseClientBalances(super.clientConfigs);

        // Set up client data
        ClientData clientData = super.setupClientData("20 nonexistent 1");
        String blockHash = nodeService.addToTransactionQueueAndCreateBlock(clientData);

        // Assuming setupInstanceInfoForBlock has already been called inside
        // addToTransactionQueueAndCreateBlock
        nodeService.sendCommitMessages(blockHash, nodeService.getQuorum());

        // Check that the balances were not updated
        assertEquals(100f, nodeService.clientBalances.getOrDefault("client1", 0.0f));
    }

    // Test that a client can't send negative money
    @Test
    public void testClientBalancesNegativeMoney() {
        System.out.println("Client balances negative money test");

        // Set up client balances
        nodeService.initialiseClientBalances(super.clientConfigs);

        // Set up client data
        ClientData clientData = super.setupClientData("-20 client2 1");
        String blockHash = nodeService.addToTransactionQueueAndCreateBlock(clientData);

        // Assuming setupInstanceInfoForBlock has already been called inside
        // addToTransactionQueueAndCreateBlock
        nodeService.sendCommitMessages(blockHash, nodeService.getQuorum());

        // Check that the balances were not updated
        assertEquals(100f, nodeService.clientBalances.getOrDefault("client1", 0.0f));
        assertEquals(100f, nodeService.clientBalances.getOrDefault("client2", 0.0f));
    }

    // Test that double spending doesn't work
    @Test
    public void testDoubleSpendingRejected() {
        System.out.println("Double spending rejected test");

        // Set up client balances
        nodeService.initialiseClientBalances(super.clientConfigs);

        // Set up client data
        ClientData clientData1 = super.setupClientData("20 client2 1"); // amount, dest, reqID
        ClientData clientData2 = super.setupClientData("20 client3 1");

        ClientMessage clientMessage1 = new ClientMessage(clientData1.getClientID(), Message.Type.TRANSFER);
        clientMessage1.setClientData(clientData1);
        ClientMessage clientMessage2 = new ClientMessage(clientData2.getClientID(), Message.Type.TRANSFER);
        clientMessage2.setClientData(clientData2);

        String blockHash1 = nodeService.addToTransactionQueueAndCreateBlock(clientData1);
        String blockHash2 = nodeService.addToTransactionQueueAndCreateBlock(clientData2);

        // Assuming setupInstanceInfoForBlock has already been called inside
        // addToTransactionQueueAndCreateBlock
        nodeService.sendCommitMessages(blockHash1, nodeService.getQuorum());
        nodeService.sendCommitMessages(blockHash2, nodeService.getQuorum());

        // Check that only the first transaction was successful
        assertEquals(80f, nodeService.clientBalances.getOrDefault("client1", 0.0f));
        Double result = 120 - (20 * 0.1);
        Float receiverBalance = result.floatValue();
        assertEquals(receiverBalance, nodeService.clientBalances.getOrDefault("client2", 0.0f));
        assertEquals(100f, nodeService.clientBalances.getOrDefault("client3", 0.0f));
    }

    // Test that client data that is not signed is not accepted for quorum
    // Illustrates byzantine nodes sending invalid data
    @Test
    void testRejectUnsignedClientData() {
        System.out.println("Reject unsigned client data");

        // Prepare unsigned client data
        ClientData unsignedClientData = new ClientData();
        unsignedClientData.setRequestID(1); // Example request ID
        unsignedClientData.setValue("20 client2 1"); // Transaction value
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

    // Test that block is not appended if the hash is invalid
    @Test
    public void testNoBlockIfInvalidHash() {
        System.out.println("No block if invalid hash");

        ClientData clientData = super.setupClientData("20 client2 1");
        String blockHash = nodeService.addToTransactionQueueAndCreateBlock(clientData);

        // Assuming setupInstanceInfoForBlock has already been called inside
        // addToTransactionQueueAndCreateBlock
        nodeService.sendCommitMessages("invalidHash", nodeService.getQuorum());

        // Check that the block was not added to the blockchain
        assertEquals(1, nodeService.getBlockchain().getLength());
    }

    // Test a "complete" run of the system, from handleTransfer to block added
    @Test
    public void testCompleteRun() {
        System.out.println("Testing complete run of the system...");

        super.setupAllNodes();
        super.setupClient();
        nodeService.initialiseClientBalances(super.clientConfigs);

        // Set up clientservice

        ClientData clientData = super.setupClientData("20 client2 1");
        ClientMessage transferMessage = new ClientMessage(clientData.getClientID(), Message.Type.TRANSFER);
        transferMessage.setClientData(clientData);

        // Simulate client broadcasting transfer message
        for (Map.Entry<String, TestableNodeService> entry : allNodes.entrySet()) {
            TestableNodeService node = entry.getValue();
            node.initialiseClientBalances(super.clientConfigs);
            node.handleTransfer(transferMessage);
            node.startConsensus();
            node.listen();
        }

        // Wait 7 seconds for block to be added to blockchain
        try {
            System.out.println("Waiting for consensus to complete...");
            Thread.sleep(6000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            e.printStackTrace();
        }

        // Verify that the block was added to the blockchain
        Block latestBlock = nodeService.getBlockchain().getLatestBlock();
        assertTrue(latestBlock.getTransactions().contains(clientData),
                "Transaction was not committed to the blockchain");
        assertEquals(80f, nodeService.clientBalances.get("client1"), "Client1 balance not updated correctly");
        Double result = 120 - (20 * 0.1);
        Float receiverBalance = result.floatValue();
        assertEquals(receiverBalance, nodeService.clientBalances.get("client2"),
                "Client2 balance not updated correctly");
    }

}

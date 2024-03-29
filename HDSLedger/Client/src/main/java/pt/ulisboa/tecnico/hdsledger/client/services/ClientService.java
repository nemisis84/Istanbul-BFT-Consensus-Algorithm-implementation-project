package pt.ulisboa.tecnico.hdsledger.client.services;

import java.io.IOException;
import java.net.InetAddress;
import java.text.MessageFormat;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.HashMap;

import pt.ulisboa.tecnico.hdsledger.communication.ClientData;
import pt.ulisboa.tecnico.hdsledger.communication.ClientMessage;
import pt.ulisboa.tecnico.hdsledger.communication.BalanceMessage;
import pt.ulisboa.tecnico.hdsledger.communication.Link;
import pt.ulisboa.tecnico.hdsledger.communication.Message;
import pt.ulisboa.tecnico.hdsledger.utilities.CustomLogger;
import pt.ulisboa.tecnico.hdsledger.utilities.ProcessConfig;

public class ClientService implements UDPServiceClient {

    private static final CustomLogger LOGGER = new CustomLogger(ClientService.class.getName());
    // Nodes configurations
    private final ProcessConfig[] nodesConfig;
    // Current node is leader
    private final ProcessConfig config;
    // Link to communicate with nodes
    private final Link link;

    private Timer timer;

    private int timeout;

    private int allowedFaults;

    // < requestID, confirmationMessage count>
    private Map<Integer, Integer> transferRequestTracker = new ConcurrentHashMap<>();
    // Keep track of balance responses to request IDs
    private Map<Integer, Map<Float, Integer>> balanceRequestTracker = new HashMap<>();

    private Map<String, Map<Float, Integer>> reciverConfirmationTracker = new ConcurrentHashMap<>();

    public ClientService(Link link, ProcessConfig config,
            ProcessConfig leaderConfig, ProcessConfig[] nodesConfig) {

        this.link = link;
        this.config = config;
        this.nodesConfig = nodesConfig;
        this.allowedFaults = numberOfFaults(nodesConfig.length);
        this.timeout = 5000;
    }

    public void clientTransfer(ClientMessage transferMessage) {
        // Create a message
        this.transferRequestTracker.put(transferMessage.getClientData().getRequestID(), 0);
        link.broadcast(transferMessage);
    }

    public void checkBalance(ClientMessage balanceRequest) {
        // Create message with balance request
        String userKey = balanceRequest.getClientData().getValue();
        this.balanceRequestTracker.put(balanceRequest.getClientData().getRequestID(), new ConcurrentHashMap<>());
        link.broadcast(balanceRequest);
    }

    public void handleBalanceResponse(BalanceMessage balanceResponse) {
        // Check if the balance response is for this client
        float balance = balanceResponse.getBalance();
        String clientID = balanceResponse.getClientID();
        int requestID = balanceResponse.getRequestID();
        String requestedClient = balanceResponse.getRequestedClient();

        if (!clientID.equals(this.config.getId())) {
            return;
        }
        // Update the balance
        if (!balanceRequestTracker.containsKey(requestID)) {
            return;
        }
        Map<Float, Integer> balances = balanceRequestTracker.get(requestID);

        // Get count, increment, and replace
        int newCount = balances.getOrDefault(balance, 0) + 1;
        balances.put(balance, newCount);

        // Check if we have quorum for value
        if (newCount == 2 * this.allowedFaults + 1) { // 2f+1
            LOGGER.log(Level.INFO, MessageFormat.format(
                    "{0} - Recieved {1} valid confirmations on balance check, balance verified.",
                    config.getId(), newCount, balanceResponse.getMessageId()));
            if (requestedClient.equals(this.config.getId())) {
                System.out.println("Your balance is: " + balance);
            } else {
                System.out.println("Client " + requestedClient + " balance is: " + balance);
            }
            balanceRequestTracker.remove(requestID); // To not process redundant value responses
        }
    }

    public ProcessConfig getConfig() {
        return this.config;
    }

    public static int numberOfFaults(int N) {
        return (N - 1) / 3;
    }

    private void handleConfirmationMessage(ClientMessage clientMessage) {
        ClientData clientData = clientMessage.getClientData();
        String clientID = clientData.getClientID();
        int requestID = clientData.getRequestID();

        if (!clientID.equals(this.config.getId())) {
            return;
        }
        // Check if request id is in transferRequestTracker map.
        // Increment value with one
        if (transferRequestTracker.containsKey(requestID)) {
            int count = transferRequestTracker.getOrDefault(requestID, 0) + 1;
            transferRequestTracker.put(requestID, count);
            if (count == this.allowedFaults + 1) {
                LOGGER.log(Level.INFO, MessageFormat.format(
                        "{0} - Recieved {1} valid confirmations on transaction. Transaction appended to blockchain.",
                        config.getId(), count, clientMessage.getMessageId()));
            }
        }
    }

    private void handleRecieverConfirmationMessage(BalanceMessage recieverConfirmation){
        float amount = recieverConfirmation.getBalance();
        String clientID = recieverConfirmation.getClientID();
        int requestID = recieverConfirmation.getRequestID();
        String transactionID = clientID + "_" + requestID;
        String recivingClient = recieverConfirmation.getRequestedClient();

        if (!recivingClient.equals(this.config.getId())) {
            return;
        }
        // Update the balance
        if (!reciverConfirmationTracker.containsKey(transactionID)) {
            reciverConfirmationTracker.put(transactionID, new ConcurrentHashMap<>());
        }

        Map<Float, Integer> transactionConfirmations = reciverConfirmationTracker.get(transactionID);

        // Get count, increment, and replace
        int newCount = transactionConfirmations.getOrDefault(amount, 0) + 1;
        transactionConfirmations.put(amount, newCount);

        // Check if we have quorum for value
        if (newCount == 2 * this.allowedFaults + 1) { // 2f+1
            LOGGER.log(Level.INFO, MessageFormat.format(
                    "{0} - Recieved {1} valid confirmations on a transaction. {2} recieved from {3}",
                    config.getId(), newCount, amount, clientID));
            balanceRequestTracker.remove(transactionID); // To not process redundant value responses
        }

    }


    // Shut down currently running services
    public void shutdown() {
        System.out.println("Shutting down ClientService...");

        // Shutdown Link or network components
        if (link != null) {
            link.shutdown(); // Ensure this method properly closes sockets and cleans up resources.
        }
        System.out.println("ClientService shutdown completed.");
    }

    @Override
    public void listen() {
        try {
            // Thread to listen on every request
            new Thread(() -> {
                try {
                    System.out.println(
                            "Listening on " + InetAddress.getLocalHost().getHostAddress() + ":" + config.getPort());
                    // remove
                    while (true) {
                        Message message = link.receive();
                        // non verified messages
                        if (message == null)
                            return;
                        // Separate thread to handle each message
                        new Thread(() -> {

                            switch (message.getType()) {

                                case ACK ->
                                    LOGGER.log(Level.INFO, MessageFormat.format("{0} - Received ACK message from {1}",
                                            config.getId(), message.getSenderId()));

                                case IGNORE ->
                                    LOGGER.log(Level.INFO,
                                            MessageFormat.format("{0} - Received IGNORE message from {1}",
                                                    config.getId(), message.getSenderId()));

                                case CLIENT_CONFIRMATION -> {

                                    LOGGER.log(Level.INFO,
                                            MessageFormat.format(
                                                    "{0} - Received CLIENT_CONFIRMATION message from {1}",
                                                    config.getId(), message.getSenderId()));

                                    ClientMessage confirmationMessage = (ClientMessage) message;
                                    handleConfirmationMessage(confirmationMessage);
                                }

                                case BALANCE_RESPONSE -> {
                                    LOGGER.log(Level.INFO,
                                            MessageFormat.format("{0} - Received BALANCE response from {1}",
                                                    config.getId(), message.getSenderId()));
                                    // Verify that message is instance of BalanceMessage
                                    if (!(message instanceof BalanceMessage)) {
                                        LOGGER.log(Level.INFO,
                                                MessageFormat.format("{0} - Received invalid balance response from {1}",
                                                        config.getId(), message.getSenderId()));
                                        return;
                                    }
                                    BalanceMessage balanceMessage = (BalanceMessage) message;
                                    handleBalanceResponse(balanceMessage);
                                }

                                case CLIENT_RECIEVER_CONFIRMATION -> {

                                    LOGGER.log(Level.INFO,
                                            MessageFormat.format("{0} - Received CLIENT_RECIEVER_CONFIRMATION response from {1}",
                                                    config.getId(), message.getSenderId()));    

                                    BalanceMessage recieverConfirmation = (BalanceMessage) message;
                                    handleRecieverConfirmationMessage(recieverConfirmation);

                                }

                                default ->
                                    LOGGER.log(Level.INFO,
                                            MessageFormat.format("{0} - Received unknown message from {1}",
                                                    config.getId(), message.getSenderId()));

                            }

                        }).start();
                    }
                } catch (IOException | ClassNotFoundException e) {
                    e.printStackTrace();
                }
            }).start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}

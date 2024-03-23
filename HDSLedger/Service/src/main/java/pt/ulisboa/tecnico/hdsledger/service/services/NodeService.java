package pt.ulisboa.tecnico.hdsledger.service.services;

import java.io.IOException;
import java.net.InetAddress;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SignatureException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import pt.ulisboa.tecnico.hdsledger.communication.ClientData;
import pt.ulisboa.tecnico.hdsledger.communication.ClientMessage;
import pt.ulisboa.tecnico.hdsledger.communication.CommitMessage;
import pt.ulisboa.tecnico.hdsledger.communication.ConsensusMessage;
import pt.ulisboa.tecnico.hdsledger.communication.BalanceMessage;
import pt.ulisboa.tecnico.hdsledger.communication.Link;
import pt.ulisboa.tecnico.hdsledger.communication.Message;
import pt.ulisboa.tecnico.hdsledger.communication.PrePrepareMessage;
import pt.ulisboa.tecnico.hdsledger.communication.PrepareMessage;
import pt.ulisboa.tecnico.hdsledger.communication.RoundChangeMessage;
import pt.ulisboa.tecnico.hdsledger.communication.builder.ConsensusMessageBuilder;
import pt.ulisboa.tecnico.hdsledger.service.models.InstanceInfo;
import pt.ulisboa.tecnico.hdsledger.service.models.MessageBucket;
import pt.ulisboa.tecnico.hdsledger.utilities.Authenticate;
import pt.ulisboa.tecnico.hdsledger.utilities.CustomLogger;
import pt.ulisboa.tecnico.hdsledger.utilities.ProcessConfig;

public class NodeService implements UDPService {

    private static final CustomLogger LOGGER = new CustomLogger(NodeService.class.getName());
    // Nodes configurations
    private final ProcessConfig[] nodesConfig;

    // Current node is leader
    private final ProcessConfig config;
    // Leader configuration
    private ProcessConfig leaderConfig;

    // Link to communicate with nodes
    private final Link link;

    private Timer timer;

    private int timeout;

    // Consensus instance -> Round -> List of prepare messages
    private final MessageBucket prepareMessages;
    // Consensus instance -> Round -> List of commit messages
    private final MessageBucket commitMessages;

    // private final ByzantineBucket roundChangeMessages;
    private final HashSet<RoundChangeMessage> roundChangeMessages;

    // Store if already received pre-prepare for a given <consensus, round>
    private final Map<Integer, Map<Integer, Boolean>> receivedPrePrepare = new ConcurrentHashMap<>();
    // Consensus instance information per consensus instance
    private final Map<Integer, InstanceInfo> instanceInfo = new ConcurrentHashMap<>();
    // Current consensus instance
    private final AtomicInteger consensusInstance = new AtomicInteger(1);
    // Last decided consensus instance
    private final AtomicInteger lastDecidedConsensusInstance = new AtomicInteger(1);

    // Ledger (for now, just a list of strings)
    private ArrayList<String> ledger = new ArrayList<String>();
    // Current queue of client requests
    private Queue<ClientData> clientRequestQueue;
    // Map of consensus instances to client data, used for consensus messages
    private Map<Integer, ClientData> consensusToDataMapping = new ConcurrentHashMap<>();
    // Map of clientID strings to client balances, used for balanceRequests
    private Map<String, Float> clientBalances = new ConcurrentHashMap<>();

    // if the rules have been done already
    private boolean rule1 = false;

    private boolean rule2 = false;

    private int quorum;

    public NodeService(Link link, ProcessConfig config,
            ProcessConfig leaderConfig, ProcessConfig[] nodesConfig) {

        this.link = link;
        this.config = config;
        this.leaderConfig = leaderConfig;
        this.nodesConfig = nodesConfig;

        this.prepareMessages = new MessageBucket(nodesConfig.length);
        this.commitMessages = new MessageBucket(nodesConfig.length);
        this.roundChangeMessages = new HashSet<RoundChangeMessage>();

        this.timeout = 5000;

        int f = Math.floorDiv(nodesConfig.length - 1, 3);
        this.quorum = Math.floorDiv(nodesConfig.length + f, 2) + 1;

        this.clientRequestQueue = new LinkedList<>();
    }

    public void sendTestMessage(String nodeId, Message message) {
        link.send(nodeId, message);
    }

    public void initialiseClientBalances(ProcessConfig[] clientConfigs) {
        for (ProcessConfig clientConfig : clientConfigs) {
            clientBalances.put(clientConfig.getId(), clientConfig.getStartBalance());
        }
        System.out.println("Starting balances: " + clientBalances); // Print at start for demo simplicity
    }

    public void sendClientMessage(Message message) {
        link.broadcast(message);
    }

    public ProcessConfig getConfig() {
        return this.config;
    }

    public AtomicInteger getConsensusInstance() {
        return this.consensusInstance;
    }

    public int getLastDecidedConsensusInstance() {
        return this.lastDecidedConsensusInstance.get();
    }

    public Map<Integer, InstanceInfo> getInstanceInfo() {
        return this.instanceInfo;
    }

    public ArrayList<String> getLedger() {
        return this.ledger;
    }

    public boolean isLeader(String id) {
        return this.leaderConfig.getId().equals(id);
    }

    public ConsensusMessage createConsensusMessage(ClientData clientData, int instance, int round) {
        PrePrepareMessage prePrepareMessage = new PrePrepareMessage(clientData);

        ConsensusMessage consensusMessage = new ConsensusMessageBuilder(config.getId(), Message.Type.PRE_PREPARE)
                .setConsensusInstance(instance)
                .setRound(round)
                .setMessage(prePrepareMessage.toJson())
                .build();

        return consensusMessage;
    }

    public void updateLeader() {

        int localConsensusInstance = this.consensusInstance.get();
        InstanceInfo instance = this.instanceInfo.get(localConsensusInstance);
        for (ProcessConfig node : nodesConfig) {
            if ((Integer.parseInt(node.getId()) - 1) == (instance.getCurrentRound() - 1) % nodesConfig.length) {
                node.setleader(true);
                this.leaderConfig = node;
            } else {
                node.setleader(false);
            }
        }
    }

    public ConsensusMessage getPrepareToSend(int round, String value) {
        int localConsensusInstance = this.consensusInstance.get();

        if (round != -1) {
            for (ConsensusMessage consensusMessage : prepareMessages.getMessages(localConsensusInstance, round)
                    .values()) {
                PrepareMessage prepareMessage = consensusMessage.deserializePrepareMessage();
                String messageValue = prepareMessage.getClientData().getValue();
                if (value.equals(messageValue)) {
                    return consensusMessage;
                }
            }
        }

        return null;
    }

    public void sendRoundChangeMessage(int round) {
        int localConsensusInstance = this.consensusInstance.get();
        InstanceInfo instance = this.instanceInfo.get(localConsensusInstance);

        RoundChangeMessage message = new RoundChangeMessage(config.getId(), Message.Type.ROUND_CHANGE,
                localConsensusInstance, round, instance.getPreparedRound(), instance.getPreparedData());

        Map<String, ConsensusMessage> prep = this.prepareMessages.getMessages(localConsensusInstance, round);
        Collection<ConsensusMessage> prepareMessages;
        if (prep == null)
            prepareMessages = null;
        else
            prepareMessages = prep.values();

        RoundChangeMessage highestPrepared = this.highestPrepared(roundChangeMessages, round);

        if (highestPrepared != null && prepareMessages != null) {
            // Get prepare messages with the round and value that we will use
            HashSet<ConsensusMessage> messagesToSend = prepareMessages.stream()
                    .filter(entry -> entry.getRound() == highestPrepared.getPreparedRound())
                    .filter(entry -> entry.deserializePrepareMessage().getClientData().getValue() == highestPrepared
                            .getClientData().getValue())
                    .collect(Collectors.toCollection(HashSet::new));

            for (ConsensusMessage m : messagesToSend) {
                message.addPrepareMessage(m);
            }
        }

        this.link.broadcast(message);
    }

    public void startChangeRound() {
        int localConsensusInstance = this.consensusInstance.get();
        InstanceInfo instance = this.instanceInfo.get(localConsensusInstance);

        instance.setCurrentRound(instance.getCurrentRound() + 1);

        sendRoundChangeMessage(instance.getCurrentRound());

        startTimer();

        updateLeader();
    }

    public void uponRoundChange(RoundChangeMessage message) {
        int localConsensusInstance = this.consensusInstance.get();
        InstanceInfo instance = this.instanceInfo.get(localConsensusInstance);

        if (instance == null) {
            return;
        }

        LOGGER.log(Level.INFO,
                MessageFormat.format(
                        "{0} - Received ROUND_CHANGE message from {1} Consensus Instance {2}, Round {3}",
                        config.getId(), message.getSenderId(), message.getConsensusInstance(), message.getRound()));

        if (message.getClientData() != null && !verifyClientData(message.getClientData())) {
            System.out.println("Message is not valid");
            return;
        }

        roundChangeMessages.add(message);

        // Received quorum of ROUND_CHANGE. Line 11-16
        int numMessages = (int) roundChangeMessages.stream()
                .filter(entry -> entry.getConsensusInstance() == localConsensusInstance)
                .filter(entry -> entry.getRound() == instance.getCurrentRound()).count();

        if (numMessages >= this.quorum && this.config.isLeader() && !this.rule1
                && this.justifyRoundChange(this.roundChangeMessages)) {

            this.rule1 = true;

            ClientData value;

            RoundChangeMessage highestPrepared = this.highestPrepared(roundChangeMessages, instance.getCurrentRound());

            if (highestPrepared.getPreparedRound() == -1) {

                value = this.consensusToDataMapping.get(localConsensusInstance);

            } else {
                value = highestPrepared.getClientData();
            }

            // Leader broadcasts PRE-PREPARE message

            LOGGER.log(Level.INFO,
                    MessageFormat.format("{0} - Node is leader, sending PRE-PREPARE message", config.getId()));

            this.link.broadcast(this.createConsensusMessage(value, localConsensusInstance, instance.getCurrentRound()));

            startTimer();
        }

        numMessages = (int) roundChangeMessages.stream()
                .filter(entry -> entry.getConsensusInstance() == localConsensusInstance)
                .filter(entry -> entry.getRound() > instance.getCurrentRound()).count();
        // Received f+1 round_change. Line 5-10
        if (numMessages > (Math.floorDiv(nodesConfig.length - 1, 3) + 1) && !this.rule2) {
            this.rule2 = true;

            int newRound = roundChangeMessages.stream()
                    .filter(entry -> entry.getConsensusInstance() == localConsensusInstance)
                    .filter(entry -> entry.getRound() > instance.getCurrentRound())
                    .mapToInt(entry -> entry.getRound()).min().orElseThrow();

            instance.setCurrentRound(newRound);

            updateLeader();

            startTimer();

            sendRoundChangeMessage(newRound);
        }
    }

    //
    public boolean justifyPrePrepare(ConsensusMessage message) {
        int localConsensusInstance = this.consensusInstance.get();
        InstanceInfo instance = this.instanceInfo.get(localConsensusInstance);

        if (message.getRound() == 1 && instance.getCurrentRound() == 1) {
            return true;
        }

        return checkRoundChangeMessages(message.deserializePrePrepareMessage().getClientData());
    }

    // Auxiliar to the justifies, checks round change messages with correct value
    public boolean checkRoundChangeMessages(ClientData value) {
        int localConsensusInstance = this.consensusInstance.get();
        InstanceInfo instance = this.instanceInfo.get(localConsensusInstance);

        Stream<RoundChangeMessage> messages = roundChangeMessages.stream()
                .filter(entry -> entry.getConsensusInstance() == localConsensusInstance)
                .filter(entry -> entry.getRound() == instance.getCurrentRound());

        // Quorum with no prepared round
        if (messages.filter(entry -> entry.getPreparedRound() == -1).count() >= this.quorum) {
            return true;
        }

        // Highest prepared message for this round
        RoundChangeMessage hp = highestPrepared(roundChangeMessages, instance.getCurrentRound());

        // possible attack - byzantine process sends very big prepared round

        ClientData v;

        // For justify round change
        if (value == null) {
            v = hp.getClientData();
        }

        // For justify pre-prepare
        else {
            v = value;
        }

        // For each round change message, find the prepare messages that justify it
        int num_messages = 0;
        for (RoundChangeMessage m : roundChangeMessages) {
            if (m.getConsensusInstance() != localConsensusInstance || m.getRound() != instance.getCurrentRound())
                continue;

            // Get prepare messages with prepared round and value equal to highest prepared
            int n = (int) m.getPrepareMessages().stream()
                    .filter(entry -> entry.getConsensusInstance() == localConsensusInstance)
                    .filter(entry -> entry.getRound() == hp.getPreparedRound())
                    .filter(entry -> (entry.deserializePrepareMessage().getClientData().getValue().equals(v.getValue())
                            && verifyClientData(entry.deserializePrepareMessage().getClientData())))
                    .count();

            if (n >= this.quorum) {
                num_messages++;
            }
        }

        if (num_messages >= this.quorum)
            return true;

        return false;
    }

    // Find quorum of justified round change messages
    public boolean justifyRoundChange(HashSet<RoundChangeMessage> roundChangeMessages) {
        return checkRoundChangeMessages(null);
    }

    public RoundChangeMessage highestPrepared(Collection<RoundChangeMessage> roundChangeMessages, int round) {
        int localConsensusInstance = this.consensusInstance.get();

        RoundChangeMessage highestPrepared = roundChangeMessages.stream()
                .filter(entry -> entry.getConsensusInstance() == localConsensusInstance)
                .filter(entry -> entry.getRound() == round)
                .max(Comparator.comparingInt(entry -> entry.getPreparedRound())).orElse(null);

        return highestPrepared;
    }

    public void cancelTimer() {
        if (timer != null)
            timer.cancel();
    }

    private void startTimer() {
        cancelTimer();
        TimerTask task = new TimerTask() {
            @Override
            public void run() {
                System.out.println("Task timed out!");
                startChangeRound();
            }
        };

        timer = new Timer(true); // Daemon thread
        timer.schedule(task, timeout); // 1000 milliseconds = 1 second
    }

    /*
     * Start an instance of consensus for a value
     * Only the current leader will start a consensus instance
     * the remaining nodes only update values.
     *
     * @param inputValue Value to value agreed upon
     */
    public void startConsensus(ClientData transferData) {
        // Set initial consensus values
        int localConsensusInstance = getConsensusInstance().incrementAndGet();
        InstanceInfo existingConsensus = this.instanceInfo.put(localConsensusInstance,
                new InstanceInfo(transferData));
        this.consensusToDataMapping.put(localConsensusInstance, transferData);
        // If startConsensus was already called for a given round
        if (existingConsensus != null) {
            LOGGER.log(Level.INFO, MessageFormat.format("{0} - Node already started consensus for instance {1}",
                    config.getId(), localConsensusInstance));
            return;
        }

        // Only start a consensus instance if the last one was decided
        // We need to be sure that the previous value has been decided
        while (lastDecidedConsensusInstance.get() < localConsensusInstance - 1) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        this.rule1 = false;
        this.rule2 = false;

        // Return if not leader
        if (!this.config.isLeader()) {
            LOGGER.log(Level.INFO,
                    MessageFormat.format("{0} - Node is not leader, waiting for PRE-PREPARE message", config.getId()));
            startTimer();
            return;
        }

        if (!authorizeClient(transferData)) {
            return;
        }

        InstanceInfo instance = this.instanceInfo.get(localConsensusInstance);
        LOGGER.log(Level.INFO,
                MessageFormat.format("{0} - Node is leader, sending PRE-PREPARE message", config.getId()));
        this.link.broadcast(
                this.createConsensusMessage(transferData, localConsensusInstance, instance.getCurrentRound()));
        startTimer();

    }

    public boolean authorizeClient(ClientData transactionData) {
        // Verify message signature, checks that source is the client
        if (!verifyClientData(transactionData)) {
            System.out.println("Message is not valid");
            return false;
        }

        String transferContent = transactionData.getValue();
        String amount = transferContent.split(" ")[0];
        String destination = transferContent.split(" ")[1];
        String source = transactionData.getClientID();

        // Check if the client has enough balance to send the amount
        if (clientBalances.getOrDefault(source, 0.0f) < Float.parseFloat(amount)) {
            System.out.println("Client does not have enough balance to send the amount");
            return false;
        }
        // Check that destination is a valid client, meaning is in the clientBalances
        if (!clientBalances.containsKey(destination)) {
            System.out.println("Destination is not a valid client");
            return false;
        }
        return true;
    }

    /*
     * Handle pre prepare messages and if the message
     * came from leader and is justified them broadcast prepare
     *
     * @param message Message to be handled
     */
    public void uponPrePrepare(ConsensusMessage message) {
        int localConsensusInstance = this.consensusInstance.get();
        InstanceInfo instance = this.instanceInfo.get(localConsensusInstance);

        if (instance == null) {
            System.out.println("No instance initialized");
            return;
        }

        int consensusInstance = message.getConsensusInstance();
        int round = message.getRound();
        String senderId = message.getSenderId();
        int senderMessageId = message.getMessageId();

        PrePrepareMessage prePrepareMessage = message.deserializePrePrepareMessage();
        this.consensusToDataMapping.put(consensusInstance, prePrepareMessage.getClientData());

        ClientData clientData = prePrepareMessage.getClientData();

        if (!authorizeClient(clientData)) {
            return;
        }

        LOGGER.log(Level.INFO,
                MessageFormat.format(
                        "{0} - Received PRE-PREPARE message from {1} Consensus Instance {2}, Round {3}",
                        config.getId(), senderId, consensusInstance, round));

        // Verify if pre-prepare was sent by leader
        if (!isLeader(senderId)) {
            System.out.println("PrePrepared message not sent by leader");
            return;
        }

        // Verify if message is justified
        if (!justifyPrePrepare(message)) {
            System.out.println("PrePrepare message not justified");
            return;
        }

        // Set instance value
        this.instanceInfo.putIfAbsent(consensusInstance, new InstanceInfo(clientData));

        // Within an instance of the algorithm, each upon rule is triggered at most once
        // for any round r
        receivedPrePrepare.putIfAbsent(consensusInstance, new ConcurrentHashMap<>());
        if (receivedPrePrepare.get(consensusInstance).put(round, true) != null) {
            LOGGER.log(Level.INFO,
                    MessageFormat.format(
                            "{0} - Already received PRE-PREPARE message for Consensus Instance {1}, Round {2}, "
                                    + "replying again to make sure it reaches the initial sender",
                            config.getId(), consensusInstance, round));
        }

        PrepareMessage prepareMessage = new PrepareMessage(prePrepareMessage.getClientData());

        ConsensusMessage consensusMessage = new ConsensusMessageBuilder(config.getId(), Message.Type.PREPARE)
                .setConsensusInstance(consensusInstance)
                .setRound(round)
                .setMessage(prepareMessage.toJson())
                .setReplyTo(senderId)
                .setReplyToMessageId(senderMessageId)
                .build();

        this.link.broadcast(consensusMessage);
    }

    /*
     * Handle prepare messages and if there is a valid quorum broadcast commit
     *
     * @param message Message to be handled
     */
    public synchronized void uponPrepare(ConsensusMessage message) {
        int localConsensusInstance = this.consensusInstance.get();
        InstanceInfo instance = this.instanceInfo.get(localConsensusInstance);

        if (instance == null) {
            System.out.println("No instance exists");
            return;
        }

        int consensusInstance = message.getConsensusInstance();
        int round = message.getRound();
        String senderId = message.getSenderId();

        PrepareMessage prepareMessage = message.deserializePrepareMessage();

        ClientData clientData = prepareMessage.getClientData();

        LOGGER.log(Level.INFO,
                MessageFormat.format(
                        "{0} - Received PREPARE message from {1}: Consensus Instance {2}, Round {3}",
                        config.getId(), senderId, consensusInstance, round));

        if (!verifyClientData(clientData)) {
            System.out.println("Message is not valid");
            return;
        }

        // Doesn't add duplicate messages
        prepareMessages.addMessage(message);

        // Set instance values
        this.instanceInfo.putIfAbsent(consensusInstance, new InstanceInfo(clientData));
        instance = this.instanceInfo.get(consensusInstance);

        // Within an instance of the algorithm, each upon rule is triggered at most once
        // for any round r
        // Late prepare (consensus already ended for other nodes) only reply to him (as
        // an ACK)
        if (instance.getPreparedRound() >= round) {
            LOGGER.log(Level.INFO,
                    MessageFormat.format(
                            "{0} - Already received PREPARE message for Consensus Instance {1}, Round {2}, "
                                    + "replying again to make sure it reaches the initial sender",
                            config.getId(), consensusInstance, round));

            ConsensusMessage m = new ConsensusMessageBuilder(config.getId(), Message.Type.COMMIT)
                    .setConsensusInstance(consensusInstance)
                    .setRound(round)
                    .setReplyTo(senderId)
                    .setReplyToMessageId(message.getMessageId())
                    .setMessage(instance.getCommitMessage().toJson())
                    .build();

            link.send(senderId, m);
            return;
        }

        // Find value with valid quorum
        Optional<String> preparedValue = prepareMessages.hasValidPrepareQuorum(config.getId(), consensusInstance,
                round);

        if (preparedValue.isPresent() && instance.getPreparedRound() < round) {

            Optional<ConsensusMessage> prepMessage = this.prepareMessages
                    .getMessages(consensusInstance, round).values().stream().filter(entry -> entry
                            .deserializePrepareMessage().getClientData().getValue().equals(preparedValue.get()))
                    .findAny();

            if (!prepMessage.isPresent()) {
                System.out.println("Error getting client data");
                return;
            }

            ClientData preparedData = prepMessage.get().deserializePrepareMessage().getClientData();

            instance.setPreparedData(preparedData);
            instance.setPreparedRound(round);

            // Must reply to prepare message senders
            Collection<ConsensusMessage> sendersMessage = prepareMessages.getMessages(consensusInstance, round)
                    .values();
            CommitMessage c = new CommitMessage(clientData);
            instance.setCommitMessage(c);

            sendersMessage.forEach(senderMessage -> {
                ConsensusMessage m = new ConsensusMessageBuilder(config.getId(), Message.Type.COMMIT)
                        .setConsensusInstance(consensusInstance)
                        .setRound(round)
                        .setReplyTo(senderMessage.getSenderId())
                        .setReplyToMessageId(senderMessage.getMessageId())
                        .setMessage(c.toJson())
                        .build();

                link.send(senderMessage.getSenderId(), m);
            });
        }
    }

    /*
     * Handle commit messages and decide if there is a valid quorum
     *
     * @param message Message to be handled
     */
    public synchronized void uponCommit(ConsensusMessage message) {
        int localConsensusInstance = this.consensusInstance.get();
        InstanceInfo instance = this.instanceInfo.get(localConsensusInstance);

        if (instance == null) {
            return;
        }

        int consensusInstance = message.getConsensusInstance();
        int round = message.getRound();

        CommitMessage commitMessage = message.deserializeCommitMessage();
        ClientData clientData = commitMessage.getClientData();

        LOGGER.log(Level.INFO,
                MessageFormat.format("{0} - Received COMMIT message from {1}: Consensus Instance {2}, Round {3}",
                        config.getId(), message.getSenderId(), consensusInstance, round));

        if (!verifyClientData(message.deserializeCommitMessage().getClientData())) {
            System.out.println("Message is not valid");
            return;
        }

        commitMessages.addMessage(message);

        instance = this.instanceInfo.get(consensusInstance);

        if (instance == null) {

            // Should never happen because only receives commit as a response to a prepare
            // message
            MessageFormat.format(
                    "{0} - CRITICAL: Received COMMIT message from {1}: Consensus Instance {2}, Round {3} BUT NO INSTANCE INFO",
                    config.getId(), message.getSenderId(), consensusInstance, round);
            return;
        }

        // Within an instance of the algorithm, each upon rule is triggered at most once
        // for any round r
        if (instance.getCommittedRound() >= round) {

            LOGGER.log(Level.INFO,
                    MessageFormat.format(
                            "{0} - Already received COMMIT message for Consensus Instance {1}, Round {2}, ignoring",
                            config.getId(), consensusInstance, round));
            return;
        }

        Optional<String> commitValue = commitMessages.hasValidCommitQuorum(config.getId(),
                consensusInstance, round);

        if (commitValue.isPresent() && instance.getCommittedRound() < round) {
            System.out.println("Value to commit: " + commitValue.get());
            Optional<ConsensusMessage> prepMessage = this.commitMessages.getMessages(consensusInstance, round).values()
                    .stream()
                    .filter(entry -> entry.deserializeCommitMessage().getClientData().getValue()
                            .equals(commitValue.get()))
                    .findAny();

            if (!prepMessage.isPresent()) {
                System.out.println("Error getting client data");
                return;
            }

            ClientData commitedData = prepMessage.get().deserializeCommitMessage().getClientData();
            cancelTimer();
            instance = this.instanceInfo.get(consensusInstance);
            instance.setCommittedRound(round);

            // Check if this client request is up next
            while (!clientRequestQueue.isEmpty()) {
                System.out.println("Checking if client request is up next");// !
                // Peek at the next client request without removing it from the queue
                ClientData nextClientRequest = clientRequestQueue.peek();

                // Check if the next client request matches the current client request
                if (nextClientRequest.getClientID().equals(clientData.getClientID())
                        && nextClientRequest.getRequestID() == clientData.getRequestID()) {
                    // This client request is up next, break out of the loop
                    clientRequestQueue.poll();
                    break;
                }

                // Wait for 0.5 seconds before checking again
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }

            // Append value to the ledger (must be synchronized to be thread-safe)
            synchronized (ledger) {
                String value = commitedData.getValue();

                // Increment size of ledger to accommodate current instance
                ledger.ensureCapacity(consensusInstance);
                while (ledger.size() < consensusInstance - 1) {
                    ledger.add("");
                }

                ledger.add(consensusInstance - 1, value);

                LOGGER.log(Level.INFO,
                        MessageFormat.format(
                                "{0} - Current Ledger: {1}",
                                config.getId(), String.join("", ledger)));
            }

            lastDecidedConsensusInstance.getAndIncrement();

            LOGGER.log(Level.INFO,
                    MessageFormat.format(
                            "{0} - Decided on Consensus Instance {1}, Round {2}, Successful? {3}",
                            config.getId(), consensusInstance, round, true));

            ClientMessage confirmationMessage = new ClientMessage(config.getId(), Message.Type.CLIENT_CONFIRMATION);
            confirmationMessage.setClientData(clientData);
            link.send(clientData.getClientID(), confirmationMessage);
            // TODO could add message for receiver to notify that received money. Would need
            // to get quorum for this.

            // Update sender and receiver balances
            String[] transferContent = commitedData.getValue().split(" ");
            String amount = transferContent[0];
            String destination = transferContent[1];

            // Update sender balance
            float senderBalance = clientBalances.getOrDefault(clientData.getClientID(), 0.0f);
            clientBalances.put(clientData.getClientID(), senderBalance - Float.parseFloat(amount));
            // Update receiver balance
            float receiverBalance = clientBalances.getOrDefault(destination, 0.0f);
            clientBalances.put(destination, receiverBalance + Float.parseFloat(amount));
        }
    }

    public boolean verifyClientData(ClientData clientData) {
        byte[] signature = clientData.getSignature();
        String value = clientData.getValue();

        // Check if the signature is null and immediately return false if so
        if (signature == null) {
            System.out.println("Message has no signature and will be ignored.");
            return false;
        }

        try { // Verifies that payload is same as signature. Fails if value wrong, or clientID
              // wrong
            if (Authenticate.verifyMessage(config.getNodePubKey(clientData.getClientID()), value, signature)) {
                return true;
            }
        } catch (InvalidKeyException | NoSuchAlgorithmException | SignatureException e) {
            e.printStackTrace();
            return false;
        }
        System.out.println("Value does not match signature");
        return false;
    }

    public void handleTransfer(ClientMessage transferMessage) {
        ClientData transferData = transferMessage.getClientData();
        if (!this.verifyClientData(transferData)) {
            return;
        }
        clientRequestQueue.offer(transferData);

        startConsensus(transferData);
    }

    public void handleBalanceRequest(ClientMessage message) {
        ClientData clientData = message.getClientData();
        if (!this.verifyClientData(clientData)) {
            return;
        }

        clientRequestQueue.offer(clientData);
        // Get the local balance of the id that the client is requesting
        String balanceUser = clientData.getValue();
        float balance = clientBalances.getOrDefault(balanceUser, 0.0f);

        // Check if this client request is up next
        while (!clientRequestQueue.isEmpty()) {
            // Peek at the next client request without removing it from the queue
            ClientData nextClientRequest = clientRequestQueue.peek();

            // Check if the next client request matches the current client request
            if (nextClientRequest.getClientID().equals(clientData.getClientID())
                    && nextClientRequest.getRequestID() == clientData.getRequestID()) {
                // This client request is up next, break out of the loop
                clientRequestQueue.poll();
                break;
            }

            // Wait for 0.5 seconds before checking again
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        // Send the balance back to the client
        BalanceMessage balanceMessage = new BalanceMessage(balance, clientData.getRequestID(),
                clientData.getClientID(), config.getId(), Message.Type.BALANCE_RESPONSE);
        balanceMessage.setRequestedClient(balanceUser);

        link.send(clientData.getClientID(), balanceMessage);
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

                                case TRANSFER ->
                                    handleTransfer((ClientMessage) message);

                                case BALANCE ->
                                    handleBalanceRequest((ClientMessage) message);

                                case PRE_PREPARE ->
                                    uponPrePrepare((ConsensusMessage) message);

                                case PREPARE ->
                                    uponPrepare((ConsensusMessage) message);

                                case COMMIT ->
                                    uponCommit((ConsensusMessage) message);

                                case ROUND_CHANGE ->
                                    uponRoundChange((RoundChangeMessage) message);

                                case ACK ->
                                    LOGGER.log(Level.INFO, MessageFormat.format("{0} - Received ACK message from {1}",
                                            config.getId(), message.getSenderId()));

                                case IGNORE ->
                                    LOGGER.log(Level.INFO,
                                            MessageFormat.format("{0} - Received IGNORE message from {1}",
                                                    config.getId(), message.getSenderId()));

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

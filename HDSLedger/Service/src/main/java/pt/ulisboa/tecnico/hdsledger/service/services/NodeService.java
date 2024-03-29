package pt.ulisboa.tecnico.hdsledger.service.services;

import java.io.IOException;
import java.net.InetAddress;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SignatureException;
import java.text.MessageFormat;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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
import pt.ulisboa.tecnico.hdsledger.service.blockchain.Block;
import pt.ulisboa.tecnico.hdsledger.service.blockchain.Blockchain;
import pt.ulisboa.tecnico.hdsledger.service.models.InstanceInfo;
import pt.ulisboa.tecnico.hdsledger.service.models.MessageBucket;
import pt.ulisboa.tecnico.hdsledger.utilities.Authenticate;
import pt.ulisboa.tecnico.hdsledger.utilities.CustomLogger;
import pt.ulisboa.tecnico.hdsledger.utilities.ProcessConfig;

public class NodeService implements UDPService {

    private static final CustomLogger LOGGER = new CustomLogger(NodeService.class.getName());
    // Nodes configurations
    protected final ProcessConfig[] nodesConfig;

    // Current node is leader
    private final ProcessConfig config;
    // Leader configuration
    private ProcessConfig leaderConfig;

    // Link to communicate with nodes
    protected final Link link;

    private Timer timer;

    private int timeout;

    // Consensus instance -> Round -> List of prepare messages
    protected final MessageBucket prepareMessages;
    // Consensus instance -> Round -> List of commit messages
    private final MessageBucket commitMessages;

    // private final ByzantineBucket roundChangeMessages;
    private final HashSet<RoundChangeMessage> roundChangeMessages;

    private Map<Integer, String> commitedValues;

    // Store if already received pre-prepare for a given <consensus, round>
    private final Map<Integer, Map<Integer, Boolean>> receivedPrePrepare = new ConcurrentHashMap<>();
    // Consensus instance information per consensus instance
    private final Map<Integer, InstanceInfo> instanceInfo = new ConcurrentHashMap<>();
    // Current consensus instance
    private final AtomicInteger consensusInstance = new AtomicInteger(1);
    // Last decided consensus instance
    private final AtomicInteger lastDecidedConsensusInstance = new AtomicInteger(1);

    // Current queue of client requests
    private Queue<ClientData> clientRequestQueue;
    // Map of consensus instances to client data, used for consensus messages
    private Map<Integer, String> consensusToDataMapping = new ConcurrentHashMap<>();
    // Map of clientID strings to client balances, used for balanceRequests
    protected Map<String, Float> clientBalances = new ConcurrentHashMap<>();
    // Map of used request IDs to client IDs, used for checking if a request ID has
    // already been used
    private Map<String, Integer> lastUsedRequestIDs = new ConcurrentHashMap<>();

    private Map<String, Float> nodeBalances = new ConcurrentHashMap<>();

    // if the rules have been done already
    private boolean rule1 = false;

    private boolean rule2 = false;

    private int quorum;

    protected int initialDelayInSeconds = 15; // Wait 15 seconds before the first consensus
    protected int intervalInSeconds = 15; // Consensus every 15 seconds

    // Blockchain and transaction related fields
    private Blockchain blockchain;

    private ConcurrentLinkedQueue<ClientData> transactionQueue = new ConcurrentLinkedQueue<>();

    protected final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    private Map<String, Float> currencyUsedInBlock = new ConcurrentHashMap<>();

    private final AtomicInteger blockNumber = new AtomicInteger(1);

    private int nextBlock;

    protected Map<Integer, Block> blockNumberToBlockMapping = new ConcurrentHashMap<>();

    public NodeService(Link link, ProcessConfig config,
            ProcessConfig leaderConfig, ProcessConfig[] nodesConfig) {

        this.link = link;
        this.config = config;
        this.leaderConfig = leaderConfig;
        this.nodesConfig = nodesConfig;

        this.prepareMessages = new MessageBucket(nodesConfig.length);
        this.commitMessages = new MessageBucket(nodesConfig.length);
        this.roundChangeMessages = new HashSet<RoundChangeMessage>();

        this.commitedValues = new HashMap<Integer, String>();

        this.timeout = 5000;

        int f = Math.floorDiv(nodesConfig.length - 1, 3);
        this.quorum = Math.floorDiv(nodesConfig.length + f, 2) + 1;

        this.clientRequestQueue = new LinkedList<>();

        this.blockchain = new Blockchain();
        this.nextBlock = 1;

        this.startConsensusWithTimer();
    }

    public void sendTestMessage(String nodeId, Message message) {
        link.send(nodeId, message);
    }

    protected void addToTransactionQueue(ClientData transaction) {
        transactionQueue.offer(transaction);
    }

    public Blockchain getBlockchain() {
        return this.blockchain;
    }

    public void initialiseClientBalances(ProcessConfig[] clientConfigs) {
        for (ProcessConfig nodeConfig : nodesConfig) {
            nodeBalances.put(nodeConfig.getId(), (float) 0);
        }
        System.out.println("Starting node balances: " + nodeBalances); // Print at start for demo simplicity

        for (ProcessConfig clientConfig : clientConfigs) {
            clientBalances.put(clientConfig.getId(), clientConfig.getStartBalance());
        }
        System.out.println("Starting client balances: " + clientBalances); // Print at start for demo simplicity
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

    public boolean isLeader(String id) {
        return this.leaderConfig.getId().equals(id);
    }

    public ConsensusMessage createConsensusMessage(String blockHash, int instance, int round) {
        PrePrepareMessage prePrepareMessage = new PrePrepareMessage(blockHash);

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

    public void sendRoundChangeMessage(int round) {
        int localConsensusInstance = this.consensusInstance.get();
        InstanceInfo instance = this.instanceInfo.get(localConsensusInstance);

        RoundChangeMessage message = new RoundChangeMessage(config.getId(), Message.Type.ROUND_CHANGE,
                localConsensusInstance, round, instance.getPreparedRound(), instance.getPreparedValue());

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
                    .filter(entry -> entry.deserializePrepareMessage().getValue() == highestPrepared
                            .getPreparedValue())
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


        roundChangeMessages.add(message);

        // Received quorum of ROUND_CHANGE. Line 11-16
        int numMessages = (int) roundChangeMessages.stream()
                .filter(entry -> entry.getConsensusInstance() == localConsensusInstance)
                .filter(entry -> entry.getRound() == instance.getCurrentRound()).count();

        if (numMessages >= this.quorum && this.config.isLeader() && !this.rule1
                && this.justifyRoundChange(this.roundChangeMessages)) {

            this.rule1 = true;

            String blockHash;

            RoundChangeMessage highestPrepared = this.highestPrepared(roundChangeMessages, instance.getCurrentRound());

            if (highestPrepared.getPreparedRound() == -1) {

                blockHash = this.consensusToDataMapping.get(localConsensusInstance);

            } else {
                blockHash = highestPrepared.getPreparedValue();
            }

            // Leader broadcasts PRE-PREPARE message

            LOGGER.log(Level.INFO,
                    MessageFormat.format("{0} - Node is leader, sending PRE-PREPARE message", config.getId()));

            this.link.broadcast(
                    this.createConsensusMessage(blockHash, localConsensusInstance, instance.getCurrentRound()));

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

        // Send commit message if already commited the value
        String val = commitedValues.get(message.getConsensusInstance());
        if (val != null) {
            CommitMessage c = new CommitMessage(val);

            ConsensusMessage m = new ConsensusMessageBuilder(config.getId(), Message.Type.COMMIT)
                    .setConsensusInstance(message.getConsensusInstance())
                    .setRound(message.getRound())
                    .setReplyTo(message.getSenderId())
                    .setReplyToMessageId(message.getMessageId())
                    .setMessage(c.toJson())
                    .build();

            link.send(message.getSenderId(), m);
        }
    }

    //
    public boolean justifyPrePrepare(ConsensusMessage message) {
        int localConsensusInstance = this.consensusInstance.get();
        InstanceInfo instance = this.instanceInfo.get(localConsensusInstance);

        if (message.getRound() == 1 && instance.getCurrentRound() == 1) {
            return true;
        }

        return checkRoundChangeMessages(message.deserializePrePrepareMessage().getValue());
    }

    // Auxiliar to the justifies, checks round change messages with correct value
    public boolean checkRoundChangeMessages(String blockHash) {
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

        String v;

        // For justify round change
        if (blockHash == null) {
            v = hp.getPreparedValue();
        }

        // For justify pre-prepare
        else {
            v = blockHash;
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
                    .filter(entry -> (entry.deserializePrepareMessage().getValue().equals(v)))
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
    public void startConsensus() {
        System.out.println("Starting consensus");

        // Create new block
        Block block = this.blockCreator();
        
        // Wait until it's this block's turn
        while (block.getBLOCK_ID() > this.nextBlock){
            LOGGER.log(Level.INFO,
                MessageFormat.format("{0} - Block {1} is not up next, wait for synchronization. Next block is {1}",
                    config.getId(), block.getBLOCK_ID(), this.nextBlock)); 
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
        
        }

        synchronized (block) {
            Iterator<ClientData> iterator = block.getTransactions().iterator();
            while (iterator.hasNext()) {
                ClientData transaction = iterator.next();
                if (!authorizeClientTransaction(transaction)) {
                    iterator.remove();
                }
            }
        }

        // Needs to be cleared before next block.
        this.currencyUsedInBlock.clear();

        this.blockNumberToBlockMapping.put(block.getBLOCK_ID(), block);

        String currentBlockHash;
        try {
            currentBlockHash = Blockchain.calculateHash(block);
        } catch (NoSuchAlgorithmException | IOException e) {
            e.printStackTrace();
            return;
        }

        // Set initial consensus values

        int localConsensusInstance = getConsensusInstance().incrementAndGet();
        InstanceInfo existingConsensus = this.instanceInfo.put(localConsensusInstance,
                new InstanceInfo(currentBlockHash));
        this.consensusToDataMapping.put(localConsensusInstance, currentBlockHash);
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

        InstanceInfo instance = this.instanceInfo.get(localConsensusInstance);
        LOGGER.log(Level.INFO,
                MessageFormat.format("{0} - Node is leader, sending PRE-PREPARE message", config.getId()));
        this.link.broadcast(
                this.createConsensusMessage(currentBlockHash, localConsensusInstance, instance.getCurrentRound()));
        startTimer();

    }

    public boolean authorizeClientTransaction(ClientData transactionData) {
        // Verify message signature, checks that source is the client
        if (!verifyClientData(transactionData)) {
            System.out.println("Transaction is not valid");
            return false;
        }
        String clientId = transactionData.getClientID();
        String[] transferContent = transactionData.getValue().split(" ");
        Float amount = Float.parseFloat(transferContent[0]);
        String destination = transferContent[1];
        String requestID = transferContent[2];
        String source = transactionData.getClientID();

        if (clientBalances.getOrDefault(source, 0.0f) < amount) {
            System.out.println("Client does not have enough balance to send the amount");
            return false;
        }

        // Check that the transaction together with other transactions lead to
        // overspending.
        if (this.currencyUsedInBlock.containsKey(clientId)
                && amount + this.currencyUsedInBlock.get(clientId) > this.clientBalances.get(clientId)) {
            System.out.println(
                    "Client does not have enough balance to send the amount and other transaction(s) in this block");
            return false;
        }

        // Check that destination is a valid client, meaning is in the clientBalances
        if (!clientBalances.containsKey(destination)) {
            System.out.println("Destination is not a valid client");
            return false;
        }

        if (!(Integer.parseInt(requestID) > lastUsedRequestIDs.getOrDefault(source, 0))) { // if no registered
                                                                                           // requestIds, this is first
                                                                                           // request for user
            // ? Should we handle differently than ignore? Doesn't handle our of order
            // requests
            System.out.println("Request ID already used for this client, ignoring");
            return false;
        }

        this.currencyUsedInBlock.put(clientId, this.currencyUsedInBlock.getOrDefault(clientId, (float) 0.0) + amount);

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
        this.consensusToDataMapping.put(consensusInstance, prePrepareMessage.getValue());

        String blockHash = prePrepareMessage.getValue();

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
        this.instanceInfo.putIfAbsent(consensusInstance, new InstanceInfo(blockHash));

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

        PrepareMessage prepareMessage = new PrepareMessage(prePrepareMessage.getValue());

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

        String blockHash = prepareMessage.getValue();

        LOGGER.log(Level.INFO,
                MessageFormat.format(
                        "{0} - Received PREPARE message from {1}: Consensus Instance {2}, Round {3}",
                        config.getId(), senderId, consensusInstance, round));

        // Doesn't add duplicate messages
        prepareMessages.addMessage(message);

        // Set instance values
        this.instanceInfo.putIfAbsent(consensusInstance, new InstanceInfo(blockHash));
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
                            .deserializePrepareMessage().getValue().equals(preparedValue.get()))
                    .findAny();

            if (!prepMessage.isPresent()) {
                System.out.println("Error getting client data");
                return;
            }

            String preparedBlockHash = prepMessage.get().deserializePrepareMessage().getValue();

            instance.setPreparedValue(preparedBlockHash);
            instance.setPreparedRound(round);

            // Must reply to prepare message senders
            Collection<ConsensusMessage> sendersMessage = prepareMessages.getMessages(consensusInstance, round)
                    .values();
            CommitMessage c = new CommitMessage(blockHash);
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
        String blockHash = commitMessage.getValue();

        LOGGER.log(Level.INFO,
                MessageFormat.format("{0} - Received COMMIT message from {1}: Consensus Instance {2}, Round {3}",
                        config.getId(), message.getSenderId(), consensusInstance, round));

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
            Optional<ConsensusMessage> prepMessage = this.commitMessages.getMessages(consensusInstance, round).values()
                    .stream()
                    .filter(entry -> entry.deserializeCommitMessage().getValue()
                            .equals(commitValue.get()))
                    .findAny();

            if (!prepMessage.isPresent()) {
                System.out.println("Error getting client data");
                return;
            }

            String commitedBlockHash = prepMessage.get().deserializeCommitMessage().getValue();

            cancelTimer();

            instance = this.instanceInfo.get(consensusInstance);
            instance.setCommittedRound(round);

            // Check if block excist locally. Otherwise, wait for it
            // while (!blockNumberToBlockMapping.containsKey(this.nextBlock)){
            //     System.out.println("Block is not up next, wait for synchronization");
            //     System.out.println(this.nextBlock);
            //     try {
            //         Thread.sleep(500);
            //     } catch (InterruptedException e) {
            //         e.printStackTrace();
            //     }
            // }

            Block blockToBeExecuted = this.blockNumberToBlockMapping.get(this.nextBlock);
            synchronized (this.blockchain) {
                String localBlockHash;
                try {
                    localBlockHash = Blockchain.calculateHash(blockToBeExecuted);
                    if (commitedBlockHash.equals(localBlockHash)) {
                        this.blockchain.addBlock(blockToBeExecuted);
                    }
                    this.commitedValues.put(localConsensusInstance, localBlockHash);
                } catch (NoSuchAlgorithmException | IOException e) {
                    e.printStackTrace();
                    System.out.println("Something wrong happened while appending block");
                    return;
                }
            }

            lastDecidedConsensusInstance.getAndIncrement();

            LOGGER.log(Level.INFO,
                    MessageFormat.format(
                            "{0} - Decided on Consensus Instance {1}, Round {2}, Successful? {3}",
                            config.getId(), consensusInstance, round, true));

            blockToBeExecuted.forEachTransaction(transaction -> {
                executeTransaction(transaction);
            });

            // Remove to avoid unneccessary overhead
            this.blockNumberToBlockMapping.remove(nextBlock);

            nextBlock = nextBlock + 1;
        }
    }

    public void executeTransaction(ClientData transaction) {

        // Update sender and receiver balances
        String[] transferContent;
        String amount;
        String destination;
        String requestId;
        try {
            transferContent = transaction.getValue().split(" ");
            amount = transferContent[0];
            destination = transferContent[1];
            requestId = transferContent[2];
        } catch (Exception e) {
            System.out.println("Transaction data on wrong format");
            return;
        }

        if (!clientBalances.containsKey(destination)) {
            System.out.println("Client does not exist");
            return;
        }
        if (clientBalances.get(transaction.getClientID()) < Float.parseFloat(amount)) {
            System.out.println("Client does not have enough balance to send the amount");
            return;
        }
        if (Float.parseFloat(amount) < 0) {
            System.out.println("Amount is negative");
            return;
        }

        Float amountToTransfer = Float.parseFloat(amount);
        Double feeAmount = Float.parseFloat(amount) * 0.1;
        Float fee = feeAmount.floatValue();

        // Update sender balance
        float senderBalance = clientBalances.getOrDefault(transaction.getClientID(), 0.0f);
        clientBalances.put(transaction.getClientID(), senderBalance - amountToTransfer);

        // Update receiver balance
        float receiverBalance = clientBalances.getOrDefault(destination, 0.0f);
        clientBalances.put(destination, receiverBalance + amountToTransfer - fee);
        // Upate last used request ID
        lastUsedRequestIDs.put(transaction.getClientID(), Integer.parseInt(requestId));

        ClientMessage confirmationMessage = new ClientMessage(config.getId(), Message.Type.CLIENT_CONFIRMATION);
        confirmationMessage.setClientData(transaction);
        link.send(transaction.getClientID(), confirmationMessage);
        
        // Inform the reciever
        BalanceMessage recieverConfirmation = new BalanceMessage(config.getId(), Message.Type.CLIENT_RECIEVER_CONFIRMATION);
        recieverConfirmation.setRequestedClient(destination);

        recieverConfirmation.setClientId(transaction.getClientID());
        recieverConfirmation.setRequestId(transaction.getRequestID());
        recieverConfirmation.setBalance(amountToTransfer);

        link.send(destination, recieverConfirmation);

        // Pay the leader
        float leaderBalance = nodeBalances.getOrDefault(this.leaderConfig.getId(), 0.0f);
        nodeBalances.put(this.leaderConfig.getId(), leaderBalance + fee);

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
        this.transactionQueue.offer(transferData);
    }

    protected Block blockCreator() {

        Block block = new Block(this.blockNumber.getAndIncrement());

        String prevHash;

        try {
            prevHash = Blockchain.calculateHash(blockchain.getLatestBlock());
            block.setPrevHash(prevHash);
        } catch (NoSuchAlgorithmException | IOException e) {
            e.printStackTrace();
        }

        // Put all waiting transactions into block or till it's full
        while (this.transactionQueue.peek() != null || !block.isFull()) {
            block.addTransaction(this.transactionQueue.poll());
        }

        return block;

    }

    public void handleBalanceRequest(ClientMessage message) {
        ClientData clientData = message.getClientData();
        if (!this.verifyClientData(clientData)) {
            return;
        }

        clientRequestQueue.offer(clientData);

        // Get the local balance of the id that the client is requesting
        String balanceUser = clientData.getValue().split(" ")[0];
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
        BalanceMessage balanceMessage = new BalanceMessage(config.getId(), Message.Type.BALANCE_RESPONSE);
        balanceMessage.setClientId(clientData.getClientID());
        balanceMessage.setBalance(balance);
        balanceMessage.setRequestedClient(balanceUser);
        balanceMessage.setRequestId(clientData.getRequestID());

        link.send(clientData.getClientID(), balanceMessage);
    }

    public void startConsensusWithTimer() {
        System.out.println("Consensus timer expired. Start consensus");
        scheduler.scheduleWithFixedDelay(() -> startConsensus(), initialDelayInSeconds, intervalInSeconds,
                TimeUnit.SECONDS);
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
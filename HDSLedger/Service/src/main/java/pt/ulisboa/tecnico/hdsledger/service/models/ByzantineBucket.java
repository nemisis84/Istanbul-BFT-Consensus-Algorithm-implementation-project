package pt.ulisboa.tecnico.hdsledger.service.models;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import pt.ulisboa.tecnico.hdsledger.communication.CommitMessage;
import pt.ulisboa.tecnico.hdsledger.communication.RoundChangeMessage;
import pt.ulisboa.tecnico.hdsledger.utilities.CustomLogger;

public class ByzantineBucket {
    private static final CustomLogger LOGGER = new CustomLogger(MessageBucket.class.getName());
    // Number of faults to trigger round change
    private final int numberOfFaults;

    private final int f;

    // Instance -> Round -> Sender ID -> Consensus message
    private final Map<Integer, Map<Integer, Map<String, RoundChangeMessage>>> bucket = new ConcurrentHashMap<>();

    public ByzantineBucket(int nodeCount) {
        this.f = Math.floorDiv(nodeCount - 1, 3);
        this.numberOfFaults = Math.floorDiv(nodeCount + f, 2) + 1;
    }

    public void addMessage(RoundChangeMessage message) {
        int consensusInstance = message.getConsensusInstance();
        int round = message.getRound();

        bucket.putIfAbsent(consensusInstance, new ConcurrentHashMap<>());
        bucket.get(consensusInstance).putIfAbsent(round, new ConcurrentHashMap<>());
        bucket.get(consensusInstance).get(round).put(message.getSenderId(), message);
    }

    // Have more than f+1 ROUND_CHANGE messages
    public long hasEnoughMessages(int instance, int round) {
        long numMessages = bucket.get(instance).entrySet().stream()
            .filter(entry -> entry.getKey() > round)
            .count();

        return numMessages;
    }
    
    private void triggerRoundChange(){
        // TODO: Find spesific sequest and count it. Trigger if too many instances of faults are recieved or triggered.
        }    

    

    // public Optional<String> hasValidPrepareQuorum(String nodeId, int instance, int round) {
    //     // Create mapping of value to frequency
    //     HashMap<String, Integer> frequency = new HashMap<>();
    //     bucket.get(instance).get(round).values().forEach((message) -> {
    //         RoundChangeMessage prepareMessage = message.deserializeMessage();
    //         String value = prepareMessage.getValue();
    //         frequency.put(value, frequency.getOrDefault(value, 0) + 1);
    //     });

    //     // Only one value (if any, thus the optional) will have a frequency
    //     // greater than or equal to the quorum size
    //     return frequency.entrySet().stream().filter((Map.Entry<String, Integer> entry) -> {
    //         return entry.getValue() >= numberOfFaults;
    //     }).map((Map.Entry<String, Integer> entry) -> {
    //         return entry.getKey();
    //     }).findFirst();
    // }
    // public Optional<String> hasValidCommitQuorum(String nodeId, int instance, int round) {
    //     // Create mapping of value to frequency
    //     HashMap<String, Integer> frequency = new HashMap<>();
    //     bucket.get(instance).get(round).values().forEach((message) -> {
    //         CommitMessage commitMessage = message.deserializeCommitMessage();
    //         String value = commitMessage.getValue();
    //         frequency.put(value, frequency.getOrDefault(value, 0) + 1);
    //     });

    //     // Only one value (if any, thus the optional) will have a frequency
    //     // greater than or equal to the quorum size
    //     return frequency.entrySet().stream().filter((Map.Entry<String, Integer> entry) -> {
    //         return entry.getValue() >= numberOfFaults;
    //     }).map((Map.Entry<String, Integer> entry) -> {
    //         return entry.getKey();
    //     }).findFirst();
    // }

    // public Map<String, ConsensusMessage> getMessages(int instance, int round) {
    //     return bucket.get(instance).get(round);
    // }

}


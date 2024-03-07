import pt.ulisboa.tecnico.hdsledger.service.Node;

import org.mockito.Mockito;
import org.mockito.Mock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.mockito.InjectMocks;
import static org.mockito.Mockito.times;
import org.mockito.MockitoAnnotations;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.*;
import java.util.Arrays;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.mockito.Mockito.atLeastOnce;

import pt.ulisboa.tecnico.hdsledger.communication.Link;
import pt.ulisboa.tecnico.hdsledger.utilities.ProcessConfig;
import pt.ulisboa.tecnico.hdsledger.utilities.ProcessConfigBuilder;
import pt.ulisboa.tecnico.hdsledger.communication.ConsensusMessage;
import pt.ulisboa.tecnico.hdsledger.communication.Message;
import pt.ulisboa.tecnico.hdsledger.service.services.NodeService;
import pt.ulisboa.tecnico.hdsledger.service.models.InstanceInfo;
import pt.ulisboa.tecnico.hdsledger.communication.PrePrepareMessage;

@ExtendWith(MockitoExtension.class)
public class NodeServiceTest {

    private static NodeService leaderNodeService;
    private static NodeService nodeService;

    private static String nodesConfigPath = "src/test/resources/test_config.json";
    private static String clientsConfigPath = "src/test/resources/client_test_config.json";
    private static String nodeId = "2";
    private static String leaderId = "1";
    private static String clientId = "client1";

    // Creating mock objects
    @Mock
    private Link mockNodeLink;

    @Mock
    private Link mockLeaderLink;

    @Mock
    private ProcessConfig mockProcessConfig;

    @Mock
    private ConsensusMessage mockMessage;

    @Mock
    private PrePrepareMessage mockPrePrepareMessage;

    @BeforeEach
    public void setUp() {
        leaderNodeService = nodeSetup(leaderId, mockLeaderLink);
        nodeService = nodeSetup(nodeId, mockNodeLink);
    }

    public NodeService nodeSetup(String id, Link link) {
        ProcessConfig[] nodeConfigs = new ProcessConfigBuilder().fromFile(nodesConfigPath);
        ProcessConfig[] clientConfigs = new ProcessConfigBuilder().fromFile(clientsConfigPath);
        ProcessConfig leaderConfig = Arrays.stream(nodeConfigs)
                .filter(ProcessConfig::isLeader)
                .findAny()
                .get();
        ProcessConfig nodeConfig = Arrays.stream(nodeConfigs)
                .filter(c -> c.getId().equals(id))
                .findAny()
                .get();

        // Add clients configs to the link, so node can send messages to clients
        link.addClient(clientConfigs);

        // Services that implement listen from UDPService
        return new NodeService(link, nodeConfig, leaderConfig,
                nodeConfigs);
    }

    @BeforeEach
    void initMocks() {
        MockitoAnnotations.initMocks(this);
    }

    // Test that the uponPrePrepare method sends a PREPARE message upon receiving a
    // PRE-PREPARE message from the leader
    @Test
    public void test_uponPrePrepare_leader() {
        // Mock that sender is leader
        when(mockMessage.getSenderId()).thenReturn(leaderId);
        when(mockMessage.deserializePrePrepareMessage()).thenReturn(mockPrePrepareMessage);
        when(mockPrePrepareMessage.getValue()).thenReturn("val");
        // Call method
        leaderNodeService.uponPrePrepare(mockMessage);

        // Verify that uponPrePrepare broadcasts a message
        verify(mockLeaderLink, times(1)).broadcast(argThat(argument -> {
            // Check if the broadcasted message is a PREPARE message
            if (argument instanceof ConsensusMessage) {
                ConsensusMessage consensusMessage = (ConsensusMessage) argument;
                return consensusMessage.getType() == Message.Type.PREPARE;
            }
            return false;
        }));
    }

    // Test that the uponPrePrepare method does not send a PREPARE message upon
    // receiving PRE-PREPARE message from a non-leader
    @Test
    public void test_uponPrePrepare_nonLeader() {
        // Mock that sender is not leader
        when(mockMessage.getSenderId()).thenReturn(nodeId);
        when(mockMessage.deserializePrePrepareMessage()).thenReturn(mockPrePrepareMessage);
        when(mockPrePrepareMessage.getValue()).thenReturn("val");

        // Call method
        nodeService.uponPrePrepare(mockMessage);

        // Verify that uponPrePrepare does not broadcast any message
        verify(mockNodeLink, times(0)).broadcast(any());
    }

    // Test that startConsensus initiates a new
    // consensus instance, updates the relevant internal data structures, and
    // broadcasts the necessary messages when the node is the leader.
    @Test
    public void test_handleClientRequest_leader() {
        when(mockMessage.getSenderId()).thenReturn(clientId);
        when(mockMessage.getValue()).thenReturn("val");

        // Call method
        leaderNodeService.handleClientRequest(mockMessage);

        // Verify that startConsensus broadcasts a PRE-PREPARE message
        verify(mockLeaderLink, times(1)).broadcast(argThat(argument -> {
            // Check if the broadcasted message is a PRE-PREPARE message
            if (argument instanceof ConsensusMessage) {
                ConsensusMessage prePrepareMessage = (ConsensusMessage) argument;
                return prePrepareMessage.getType() == Message.Type.PRE_PREPARE;
            }
            return false;
        }));
    }

    // Test that handleClientRequest does not start a new consensus instance when
    // the node is not the leader.
    @Test
    public void test_handleClientRequest_notLeader() {
        // Call method
        nodeService.handleClientRequest(mockMessage);

        // Verify that handleClientRequest does not start a new consensus instance
        verify(mockNodeLink, times(0)).broadcast(any());
    }

}
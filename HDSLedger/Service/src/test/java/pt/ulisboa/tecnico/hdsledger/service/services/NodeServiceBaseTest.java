package pt.ulisboa.tecnico.hdsledger.service.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtendWith;
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

import java.io.FileInputStream;
import java.io.IOException;

import java.util.Map;
import java.util.Arrays;
import java.util.HashMap;

import java.security.PrivateKey;
import java.security.KeyFactory;
import java.security.spec.PKCS8EncodedKeySpec;

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

import pt.ulisboa.tecnico.hdsledger.utilities.Authenticate;
import pt.ulisboa.tecnico.hdsledger.utilities.ProcessConfig;
import pt.ulisboa.tecnico.hdsledger.utilities.ProcessConfigBuilder;

import pt.ulisboa.tecnico.hdsledger.service.blockchain.Block;
import pt.ulisboa.tecnico.hdsledger.service.models.InstanceInfo;
import pt.ulisboa.tecnico.hdsledger.service.blockchain.Blockchain;

import pt.ulisboa.tecnico.hdsledger.client.services.ClientService;
import pt.ulisboa.tecnico.hdsledger.client.models.ClientMessageBuilder;

@ExtendWith(MockitoExtension.class)
public abstract class NodeServiceBaseTest {

    protected Map<String, TestableNodeService> allNodes = new HashMap<>();
    protected static String nodesConfigPath = "src/test/resources/test_config.json";
    protected static String clientsConfigPath = "src/test/resources/client_test_config.json";
    protected static ProcessConfig[] nodeConfigs;
    protected static ProcessConfig[] clientConfigs;
    protected static ProcessConfig leaderConfig;

    protected static String leaderId = "1";
    protected static String testNodeId = "2";
    protected static String clientId = "client1";
    protected static PrivateKey clientKey;
    protected static Link linkSpy;

    protected TestableNodeService nodeService;
    private ClientService clientService = null;

    @BeforeAll
    public static void setUpAll() throws Exception {
        getClientPrivateKey();
    }

    @BeforeEach
    void setUp() throws Exception {
        nodeService = testNodeSetup(testNodeId);
        allNodes.put(testNodeId, nodeService);
        MockitoAnnotations.initMocks(this);
    }

    @AfterEach
    void tearDown() {
        if (allNodes != null) {
            for (TestableNodeService node : allNodes.values()) {
                System.out.println("Shutting down node " + node.getConfig().getId());
                node.shutdown();
            }
            allNodes.clear();
        }
        if (clientService != null) {
            clientService.shutdown();
            clientService = null;
        }
        Mockito.reset(linkSpy);
    }

    public static void getClientPrivateKey() throws IOException {
        try {
            String keyPath = "../Utilities/keys/key" + clientId + ".priv";
            FileInputStream fis = new FileInputStream(keyPath);
            byte[] encoded = new byte[fis.available()];
            fis.read(encoded);
            fis.close();

            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(encoded);
            KeyFactory keyFac = KeyFactory.getInstance("RSA");
            clientKey = keyFac.generatePrivate(spec);
        } catch (Exception e) {
            System.out.println("Error reading client private key");
        }
    }

    public TestableNodeService testNodeSetup(String id) {
        nodeConfigs = new ProcessConfigBuilder().fromFile(nodesConfigPath);
        clientConfigs = new ProcessConfigBuilder().fromFile(clientsConfigPath);
        leaderConfig = Arrays.stream(nodeConfigs)
                .filter(ProcessConfig::isLeader)
                .findAny()
                .get();
        ProcessConfig nodeConfig = Arrays.stream(nodeConfigs)
                .filter(c -> c.getId().equals(id))
                .findAny()
                .get();

        linkSpy = Mockito.spy(new Link(nodeConfig, nodeConfig.getPort(), nodeConfigs, ConsensusMessage.class));

        // Add clients configs to the link, so node can send messages to clients
        linkSpy.addClient(clientConfigs);

        return new TestableNodeService(linkSpy, nodeConfig, leaderConfig,
                nodeConfigs);
    }

    public void setupAllNodes() {
        for (ProcessConfig nodeConfig : nodeConfigs) {
            if (nodeConfig.getId().equals(testNodeId)) {
                continue;
            }
            TestableNodeService node = nodeSetup(nodeConfig.getId());
            allNodes.put(nodeConfig.getId(), node);
        }
    }

    public ClientData setupClientData(String value) {
        // Set up client data
        ClientData clientData = new ClientData();
        clientData.setRequestID(1); // arbitrary number
        clientData.setValue(value);
        clientData.setClientID("client1");

        try {
            byte[] signature = Authenticate.signMessage(clientKey, clientData.getValue());
            clientData.setSignature(signature);
        } catch (Exception e) {
            System.out.println("Error signing value");
        }
        return clientData;
    }

    // Function to set up all nodes in the network, for complete testing
    public TestableNodeService nodeSetup(String id) {
        nodeConfigs = new ProcessConfigBuilder().fromFile(nodesConfigPath);
        clientConfigs = new ProcessConfigBuilder().fromFile(clientsConfigPath);
        ProcessConfig leaderConfig = Arrays.stream(nodeConfigs)
                .filter(ProcessConfig::isLeader)
                .findAny()
                .get();
        ProcessConfig nodeConfig = Arrays.stream(nodeConfigs)
                .filter(c -> c.getId().equals(id))
                .findAny()
                .get();

        Link link = new Link(nodeConfig, nodeConfig.getPort(), nodeConfigs, ConsensusMessage.class);

        // Add clients configs to the link, so node can send messages to clients
        link.addClient(clientConfigs);

        return new TestableNodeService(link, nodeConfig, leaderConfig,
                nodeConfigs);

    }

    public void setupClient() {
        // Set up client service
        ProcessConfig clientConfig = Arrays.stream(clientConfigs)
                .filter(c -> c.getId().equals(clientId))
                .findAny()
                .get();
        Link link = new Link(clientConfig, clientConfig.getPort(), nodeConfigs, ConsensusMessage.class);

        clientService = new ClientService(link, clientConfig, leaderConfig, nodeConfigs);
        ClientMessageBuilder clientMessageBuilder = new ClientMessageBuilder(clientConfig);
        // Start a thread to listen for messages from nodes
        // Listen for incoming messages from nodes
        clientService.listen();

    }

}
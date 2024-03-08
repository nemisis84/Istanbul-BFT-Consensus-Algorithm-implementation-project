package pt.ulisboa.tecnico.hdsledger.service;

import pt.ulisboa.tecnico.hdsledger.communication.ClientMessage;
import pt.ulisboa.tecnico.hdsledger.communication.ConsensusMessage;
import pt.ulisboa.tecnico.hdsledger.communication.Link;
import pt.ulisboa.tecnico.hdsledger.communication.Message;
import pt.ulisboa.tecnico.hdsledger.service.services.NodeService;
import pt.ulisboa.tecnico.hdsledger.utilities.CustomLogger;
import pt.ulisboa.tecnico.hdsledger.utilities.ProcessConfig;
import pt.ulisboa.tecnico.hdsledger.utilities.ProcessConfigBuilder;

import java.text.MessageFormat;
import java.util.Arrays;
import java.util.logging.Level;

public class Node {
    private static final CustomLogger LOGGER = new CustomLogger(Node.class.getName());

    private static String nodesConfigPath = "src/main/resources/";
    private static String clientsConfigPath = "src/main/resources/client_config.json";
    private static String id;

    private Link linkToNodes;
    private NodeService nodeService;

    public Node(String id, String nodesConfigPath, String clientsConfigPath) {
        try {
            // Create process configs from config files
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

            LOGGER.log(Level.INFO, MessageFormat.format("{0} - Running at {1}:{2}; is leader: {3}",
                    nodeConfig.getId(), nodeConfig.getHostname(), nodeConfig.getPort(),
                    nodeConfig.isLeader()));

            // Abstraction to send and receive messages
            this.linkToNodes = new Link(nodeConfig, nodeConfig.getPort(), nodeConfigs,
                    ConsensusMessage.class);
            // Add clients configs to the link, so node can send messages to clients
            this.linkToNodes.addClient(clientConfigs);

            // Services that implement listen from UDPService
            this.nodeService = new NodeService(linkToNodes, nodeConfig, leaderConfig,
                    nodeConfigs);
            if (nodeConfig.isLeader())
                return;
            
            
            nodeService.listen();

            ClientMessage message = new ClientMessage(nodeConfig.getId(), Message.Type.APPEND);
            ((ClientMessage) message).setValue("456");
            if (nodeConfig.getId().equals("3")) {
                // for (int i = 0; i < 4; i++) {
                Thread.sleep(500);
                // String node = String.valueOf(i);
                // nodeService.sendTestMessage(node, message);
                // }

            }
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        // Command line arguments from puppet_master
        id = args[0];
        nodesConfigPath += args[1];

        Node node = new Node(id, nodesConfigPath, clientsConfigPath);
        node.start();
    }

    public void start() {
        nodeService.listen();
    }

    public void sendTestMessage(String recipientId, Message message) {
        nodeService.sendTestMessage(recipientId, message);
    }
}


    
    
        
    
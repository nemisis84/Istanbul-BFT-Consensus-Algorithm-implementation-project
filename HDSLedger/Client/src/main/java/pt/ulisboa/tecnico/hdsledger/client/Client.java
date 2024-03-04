package pt.ulisboa.tecnico.hdsledger.client;

import pt.ulisboa.tecnico.hdsledger.communication.Link;
import pt.ulisboa.tecnico.hdsledger.communication.Message;
import pt.ulisboa.tecnico.hdsledger.communication.ConsensusMessage;
import pt.ulisboa.tecnico.hdsledger.utilities.CustomLogger;
import pt.ulisboa.tecnico.hdsledger.utilities.ProcessConfigBuilder;
import pt.ulisboa.tecnico.hdsledger.utilities.CustomLogger;
import pt.ulisboa.tecnico.hdsledger.utilities.ProcessConfig;

import java.text.MessageFormat;
import java.util.logging.Level;
import java.util.Arrays;
import java.util.Scanner;
import java.io.InputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.net.DatagramSocket;
import java.net.DatagramPacket;
import java.net.UnknownHostException;
import java.net.SocketException;
import java.net.InetAddress;

// Client-side application logic. Needs:
// TODO - To be able to send messages to the nodes (APPEND)
// TODO - To be able to receive messages from the nodes
// TODO - To be able to sign its own messages and authenticate the messages it receives
// TODO - Expect ACKs from the nodes
// TODO - To take command line arguments from a user and use it to send messages to the nodes

// ! CURRENT TASK: Set up a way for the client to send/receive messages to the nodes

public class Client {
    private static final CustomLogger LOGGER = new CustomLogger("Client");

    private static final String CLIENTCONFIGPATH = "src/main/resources/client_config.json";
    private static final String NODECONFIGPATH = "src/main/resources/regular_config.json";

    private static final int CLIENT_PORT = 3010; // For incoming datagrams
    private static final String HOST = "localhost"; // Replace with the actual service host

    private static final String id = "client1"; // ! adjust this to make sense once functioning

    public static void main(String[] args) {
        try {
            Scanner scanner = new Scanner(System.in);

            InputStream resourceStream = Client.class.getClassLoader().getResourceAsStream(NODECONFIGPATH);

            // Creating a link to the service
            ProcessConfig[] nodeConfigs = new ProcessConfigBuilder().fromFile(NODECONFIGPATH);
            ProcessConfig leaderConfig = Arrays.stream(nodeConfigs).filter(ProcessConfig::isLeader).findAny().get();
            ProcessConfig[] clientConfigs = new ProcessConfigBuilder().fromFile(CLIENTCONFIGPATH);
            ProcessConfig clientConfig = Arrays.stream(clientConfigs).filter(c -> c.getId().equals(id)).findAny().get();

            Link link = new Link(clientConfig, CLIENT_PORT, nodeConfigs, ConsensusMessage.class);

            // Continuous loop to read user commands
            while (true) {
                System.out.print("Enter command (e.g., append): ");
                String userCommand = scanner.nextLine().trim().toLowerCase();

                switch (userCommand.split(" ")[0]) {
                    case "append":
                        // Extract the payload from the user input
                        String payload = userCommand.substring("append".length()).trim();
                        Message message = new Message(id, Message.Type.APPEND);
                        message.setValue(payload);
                        link.broadcast(message);

                        System.out.println("Sent append message to the service");
                        break;
                    case "quit":
                        // Handle other commands as needed
                        quitHandler();
                        break;
                    default:
                        System.out.println("Unknown command. Try again.");
                        break;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void quitHandler() {
        // Perform any cleanup or shutdown tasks before exiting
        System.out.println("Exiting the client application.");
        System.exit(0);
    }
}

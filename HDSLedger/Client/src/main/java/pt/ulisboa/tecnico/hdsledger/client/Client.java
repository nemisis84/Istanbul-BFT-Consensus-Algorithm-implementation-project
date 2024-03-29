package pt.ulisboa.tecnico.hdsledger.client;

import pt.ulisboa.tecnico.hdsledger.communication.Link;
import pt.ulisboa.tecnico.hdsledger.client.models.ClientMessageBuilder;
import pt.ulisboa.tecnico.hdsledger.client.services.ClientService;
import pt.ulisboa.tecnico.hdsledger.communication.ClientMessage;
import pt.ulisboa.tecnico.hdsledger.communication.ConsensusMessage;
import pt.ulisboa.tecnico.hdsledger.utilities.CustomLogger;
import pt.ulisboa.tecnico.hdsledger.utilities.ProcessConfigBuilder;
import pt.ulisboa.tecnico.hdsledger.utilities.ProcessConfig;
import pt.ulisboa.tecnico.hdsledger.communication.Message;

import java.text.MessageFormat;
import java.util.logging.Level;
import java.util.Arrays;
import java.util.Scanner;
import java.io.InputStream;
import java.io.IOException;

public class Client {
    private static final CustomLogger LOGGER = new CustomLogger("Client");

    private static final String CLIENTCONFIGPATH = "src/main/resources/client_config.json";
    private static final String NODECONFIGPATH = "src/main/resources/regular_config.json";

    public static void main(String[] args) throws IOException, ClassNotFoundException {
        try {
            String id = args[0];
            Scanner scanner = new Scanner(System.in);

            // Set up the link to the nodes
            InputStream resourceStream = Client.class.getClassLoader().getResourceAsStream(NODECONFIGPATH);

            // Creating a link to the service
            ProcessConfig[] nodeConfigs = new ProcessConfigBuilder().fromFile(NODECONFIGPATH);
            ProcessConfig leaderConfig = Arrays.stream(nodeConfigs).filter(ProcessConfig::isLeader).findAny().get();
            ProcessConfig[] clientConfigs = new ProcessConfigBuilder().fromFile(CLIENTCONFIGPATH);
            ProcessConfig clientConfig = Arrays.stream(clientConfigs).filter(c -> c.getId().equals(id)).findAny().get();
            Float startBalance = clientConfig.getStartBalance();

            LOGGER.log(Level.INFO, MessageFormat.format("{0} - Running at {1}:{2}; is leader: {3}",
                    clientConfig.getId(), clientConfig.getHostname(), clientConfig.getPort(),
                    clientConfig.isLeader()));

            Link link = new Link(clientConfig, clientConfig.getPort(), nodeConfigs,
                    ConsensusMessage.class);

            link.addClient(clientConfigs);

            // Create a clientService for sending messages to nodes
            ClientService clientService = new ClientService(link, clientConfig, leaderConfig, nodeConfigs);
            // Create a ClientMessageBuilder for creating the messages
            ClientMessageBuilder clientMessageBuilder = new ClientMessageBuilder(clientConfig);
            // Start a thread to listen for messages from nodes
            new Thread(() -> {
                // Listen for incoming messages from nodes
                clientService.listen();
            }).start();

            // Continuous loop to read user commands
            System.out.println("Commands: \n"
                    + "\ttransfer <amount> <destination> - Transfer amount to client with key destinationKey\n"
                    + "\tbalance - Check your current balance\n"
                    + "\tcheck <user> - Check balance of client with key userKey\n"
                    + "\tquit - Exit the client application\n");
            while (true) {
                System.out.print("Enter command: ");
                String userCommand = scanner.nextLine().trim().toLowerCase();
                switch (userCommand.split(" ")[0]) {
                    case "transfer":
                        // Extract the payload from the user input
                        String transferInput = userCommand.substring("transfer".length()).trim(); // String amount, dst
                        String[] parts = transferInput.split(" ");
                        if (parts.length != 2) {
                            System.out.println(
                                    "Invalid transfer command. Correct use: transfer <Int amount> <PublicKey destination>");
                            break;
                        }
                        float amount = Float.parseFloat(parts[0]); // Throw exception if not float
                        if (amount <= 0) {
                            System.out.println("Amount must be a positive number.");
                            break;
                        }
                        String destinationKey = parts[1]; // Consider adding a check for valid public key
                        ClientMessage transferMessage = clientMessageBuilder.buildMessage(transferInput,
                                clientConfig.getId(), Message.Type.TRANSFER);
                        // Send the message to nodes
                        clientService.clientTransfer(transferMessage);
                        break;
                    case "balance":
                        ClientMessage selfBalanceRequest = clientMessageBuilder.buildMessage(clientConfig.getId(),
                                clientConfig.getId(),
                                Message.Type.BALANCE);
                        clientService.checkBalance(selfBalanceRequest);

                        break;
                    case "check":
                        System.out.println("Checking balance...");
                        String userId = userCommand.substring("check".length()).trim(); // Uses client id
                        ClientMessage balanceRequest = clientMessageBuilder.buildMessage(userId,
                                clientConfig.getId(), Message.Type.BALANCE); // payload = dest public key
                        clientService.checkBalance(balanceRequest);
                        break;
                    case "quit":
                        quitHandler();
                        break;
                    case "help":
                        System.out.println("Commands: \n"
                                + "\ttransfer <amount> <destination> - Transfer amount to client with key destinationKey\n"
                                + "\tbalance - Check your current balance\n"
                                + "\tcheck <user> - Check balance of client with key userKey\n"
                                + "\tquit - Exit the client application\n");
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
        System.out.println("Exiting the client application.");
        System.exit(0);
    }
}

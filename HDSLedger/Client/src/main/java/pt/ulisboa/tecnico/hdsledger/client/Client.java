package pt.ulisboa.tecnico.hdsledger.client;

import pt.ulisboa.tecnico.hdsledger.utilities.CustomLogger;

import java.text.MessageFormat;
import java.util.logging.Level;

// Client-side application logic. Needs:
// TODO - To be able to send messages to the nodes (APPEND)
// TODO - To be able to receive messages from the nodes
// TODO - To be able to sign its own messages and authenticate the messages it receives
// TODO - Expect ACKs from the nodes
// TODO - To take command line arguments from a user and use it to send messages to the nodes

public class Client {
    private static final CustomLogger LOGGER = new CustomLogger("Client");
    private static String clientConfigPath = "src/main/resources/";

    public static void main(String[] args) {
        try {
            // Command line arguments
            String id = args[0];
            clientConfigPath += args[1];

            // To verify that the client is running
            String logMessage = "Message";
            LOGGER.log(Level.INFO, logMessage);

            // Setup link to send and receive messages

            // Set up method for sending messages to the nodes
            // - Sign the message

            // Set up listening for messages from the nodes

            // Set up handling of messages from the nodes
            // - Check authentication

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

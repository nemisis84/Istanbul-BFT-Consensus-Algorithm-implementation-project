package pt.ulisboa.tecnico.hdsledger.client;

import pt.ulisboa.tecnico.hdsledger.communication.Message;
// import pt.ulisboa.tecnico.hdsledger.utils.CustomLogger;

import java.text.MessageFormat;
import java.util.logging.Level;

import java.util.Scanner;

// Client-side application logic. Needs:
// TODO - To be able to send messages to the nodes (APPEND)
// TODO - To be able to receive messages from the nodes
// TODO - To be able to sign its own messages and authenticate the messages it receives
// TODO - Expect ACKs from the nodes
// TODO - To take command line arguments from a user and use it to send messages to the nodes

// ! CURRENT TASK: Set up a way for the client to send/receive messages to the nodes

public class Client {
    // private static final CustomLogger LOGGER = new CustomLogger("Client");

    private static String clientConfigPath = "src/main/resources/";

    private static String id = "Client";

    public static void main(String[] args) {
        try {
            Scanner scanner = new Scanner(System.in);

            // Continuous loop to read user commands
            while (true) {
                System.out.print("Enter command (e.g., append): ");
                String userCommand = scanner.nextLine().trim().toLowerCase();

                switch (userCommand) {
                    case "append":
                        sendAppendMessage();
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

    public static void sendAppendMessage() {
        // Create an APPEND message
        Message appendMessage = new Message(id, Message.Type.APPEND);
        appendMessage.setValue("456");

        // ! Add signing of the message

        // Send the APPEND message to the nodes
        sendMessage(appendMessage);
    }

    public static void sendMessage(Message message) {
        // Send message to node service

    }

    public static void receiveMessage(Message message) {
        // ! Receive the message from the nodes
        // Authenticate the message
    }
}

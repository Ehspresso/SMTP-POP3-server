package net;
import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

import mailbox.MailWriter;
import mailbox.Mailbox;

public class MySMTPServer extends Thread {

    private final Socket socket;
    private final BufferedReader socketIn;
    private final PrintWriter socketOut;
    String sender;
    List<Mailbox> to = new ArrayList<Mailbox>();

    /**
     * Initializes an object responsible for a connection to an individual client.
     *
     * @param socket The socket associated to the accepted connection.
     * @throws IOException If there is an error attempting to retrieve the socket's information.
     */
    public MySMTPServer(Socket socket) throws IOException {
        this.socket = socket;
        this.socketIn = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        this.socketOut = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);
    }

    /**
     * Handles the communication with an individual client. Must send the initial welcome message, and then repeatedly
     * read requests, process the individual operation, and return a response, according to the SMTP protocol. Empty
     * request lines should be ignored. Only returns if the connection is terminated or if the QUIT command is issued.
     * Must close the socket connection before returning.
     */
    @Override
    public void run() {
        try (this.socket) {

            socketOut.write("220 "+ getHostName()+ " Service ready\r\n");
            socketOut.flush();

            int state = 0;
    
            do {
                String[] cmd;
                cmd = socketIn.readLine().split(" ");
                if(isValidCommand(cmd[0])) {
                    switch (cmd[0].toUpperCase()) {
                        case "HELO":
                            socketOut.write("250 " + getHostName() + "\r\n");
                            socketOut.flush();
                            state = 1;
                            break;
                        case "EHLO":
                            socketOut.write("250 " + getHostName() + "\r\n");
                            socketOut.flush();
                            state = 1;
                            break;
                        case "RSET":
                            state = 1;
                            to.clear();
                            socketOut.write("250 OK\r\n");
                            socketOut.flush();
                            break;
                        case "VRFY":
                            if(cmd.length > 1) {
                                if(Mailbox.isValidUser(cmd[1])) {
                                    socketOut.write("250 OK\r\n");
                                    socketOut.flush();
                                    break;
                                } else {
                                    socketOut.write("550 Invalid User\r\n");
                                    socketOut.flush();
                                    break;
                                }
                            }
                            socketOut.write("501 Invalid argument\r\n");
                            socketOut.flush();
                            break;
                        case "NOOP":
                            socketOut.write("250 OK\r\n");
                            socketOut.flush();
                            break;
                        case "QUIT":
                            socketOut.write("221 OK\r\n");
                            socketOut.flush();
                            socketOut.close();
                            socketIn.close();
                            socket.close();
                            break;
                        case "MAIL":
                            if(state == 1) {
                                if(cmd.length != 2) {
                                    socketOut.write("500 Invalid command\r\n");
                                    socketOut.flush();
                                    break;
                                }
                                else if(!cmd[1].startsWith("FROM:<")) {
                                    socketOut.write("501 Invalid argument\r\n");
                                    socketOut.flush();
                                    break;
                                }
                                else if(!cmd[1].endsWith(">")){
                                    socketOut.write("501 Invalid argument\r\n");
                                    socketOut.flush();
                                    break;
                                }
                                sender = cmd[1].substring(6, cmd[1].length() - 1);
                                System.out.println(sender);
                                socketOut.write("250 OK\r\n");
                                socketOut.flush();
                                state = 2;
                                break;
                            }
                            socketOut.write("503 Bad sequence of commands\r\n");
                            socketOut.flush();
                            break;
                        case "RCPT":
                            if(state > 1) {
                                if(cmd.length != 2) {
                                    socketOut.write("500 Invalid command\r\n");
                                    socketOut.flush();
                                    break;
                                }
                                else if(!cmd[1].startsWith("TO:<")) {
                                    socketOut.write("500 Invalid command\r\n");
                                    socketOut.flush();
                                    break;
                                }
                                else if(!cmd[1].endsWith(">")) {
                                    socketOut.write("501 Invalid argument\r\n");
                                    socketOut.flush();
                                    break;
                                }
                                String username = cmd[1].substring(4, cmd[1].length() - 1);
                                if(!Mailbox.isValidUser(username)) {
                                    socketOut.write("550 User not found\r\n");
                                    socketOut.flush();
                                    break;
                                }
                                Mailbox recip = new Mailbox(username);
                                to.add(recip);
                                socketOut.write("250 OK\r\n");
                                socketOut.flush();
                                state = 3;
                                break;
                            }
                            else {
                                socketOut.write("503 Bad sequence of commands\r\n");
                                socketOut.flush();
                                break;
                            }
                        case "DATA":
                            if(state == 3) {
                                socketOut.write("354 Start mail input; end with <CRLF>.<CRLF>\r\n");
                                socketOut.flush();

                                MailWriter writer = new MailWriter(to);
                                String data = socketIn.readLine();
                                while(!data.equals(".")) {
                                    writer.write(data + "\n");
                                    data = socketIn.readLine();
                                }
                                writer.flush();
                                writer.close();
                                socketOut.write("250 OK\r\n");
                                socketOut.flush();
                                state = 1;
                                to.clear();
                                break;
                            }
                            socketOut.write("503 Bad sequence of commands\r\n");
                            socketOut.flush();
                            break;
                        default:
                            socketOut.write("502 Command no implemented\r\n");
                            socketOut.flush();
                    }
                }
                else {
                    socketOut.write("500 Invalid Command\r\n");
                    socketOut.flush();
                }
            } while(true);
        }
        catch (IOException e) {
            System.err.println("Error in client's connection handling.");
            e.printStackTrace();
        }
    }

    /**
     * Retrieves the name of the current host. Used in the response of commands like HELO and EHLO.
     * @return A string corresponding to the name of the current host.
     */
    private static String getHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            try (BufferedReader reader = Runtime.getRuntime().exec(new String[] {"hostname"}).inputReader()) {
                return reader.readLine();
            } catch (IOException ex) {
                return "unknown_host";
            }
        }
    }

    private static boolean isValidCommand(String cmd) {

        boolean isValid = false;

        switch(cmd.toUpperCase()) {
            case "HELO":
                isValid = true;
                break;
            case "EHLO":
                isValid = true;
                break;
            case "MAIL":
                isValid = true;
                break;
            case "RCPT":
                isValid = true;
                break;
            case "DATA":
                isValid = true;
                break;
            case "RSET":
                isValid = true;
                break;
            case "VRFY":
                isValid = true;
                break;
            case "NOOP":
                isValid = true;
                break;
            case "QUIT":
                isValid = true;
                break;
        }
        return isValid;
    }
    
    /**
     * Main process for the SMTP server. Handles the argument parsing and creates a listening server socket. Repeatedly
     * accepts new connections from individual clients, creating a new server instance that handles communication with
     * that client in a separate thread.
     *
     * @param args The command-line arguments.
     * @throws IOException In case of an exception creating the server socket or accepting new connections.
     */
    public static void main(String[] args) throws IOException {

        if (args.length != 1) {
            throw new RuntimeException("This application must be executed with exactly one argument, the listening port.");
        }

        try (ServerSocket serverSocket = new ServerSocket(Integer.parseInt(args[0]))) {
            serverSocket.setReuseAddress(true);
            System.out.println("Waiting for connections on port " + serverSocket.getLocalPort() + "...");
            //noinspection InfiniteLoopStatement
            while (true) {
                Socket socket = serverSocket.accept();
                System.out.println("Accepted a connection from " + socket.getRemoteSocketAddress());
                try {
                    MySMTPServer handler = new MySMTPServer(socket);
                    handler.start();
                } catch (IOException e) {
                    System.err.println("Error setting up an individual client's handler.");
                    e.printStackTrace();
                }
            }
        }
    }
}
package ca.yorku.eecs3214.mail.net;

import ca.yorku.eecs3214.mail.mailbox.MailMessage;
import ca.yorku.eecs3214.mail.mailbox.Mailbox;
import ca.yorku.eecs3214.mail.mailbox.Mailbox.MailboxNotAuthenticatedException;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class MyPOPServer extends Thread {

    private final Socket socket;
    private final BufferedReader socketIn;
    private final PrintWriter socketOut;

    // TODO Additional properties, if needed
    String user;
    Mailbox mb;
    List<Integer> msgIndex = new ArrayList<Integer>();
    List<Integer> deletedmsg = new ArrayList<Integer>();

    /**
     * Initializes an object responsible for a connection to an individual client.
     *
     * @param socket The socket associated to the accepted connection.
     * @throws IOException If there is an error attempting to retrieve the socket's information.
     */
    public MyPOPServer(Socket socket) throws IOException {
        this.socket = socket;
        this.socketIn = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        this.socketOut = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);
    }

    /**
     * Handles the communication with an individual client. Must send the initial welcome message, and then repeatedly
     * read requests, process the individual operation, and return a response, according to the POP3 protocol. Empty
     * request lines should be ignored. Only returns if the connection is terminated or if the QUIT command is issued.
     * Must close the socket connection before returning.
     */
    @Override
    public void run() {
        try (this.socket) {

            socketOut.write("+OK POP3 server ready <" + socket.getLocalAddress() + ">\r\n");
            socketOut.flush();

            int state = 0; // 0 = User auth state, 1 = transaction state.
            boolean auth = false;

            while(true) {
                String[] cmd = socketIn.readLine().split(" ");
                    switch(cmd[0].toUpperCase()) {
                        case "USER":
                            if(state != 0 || cmd.length != 2) {
                                socketOut.write("-ERR NONO\r\n");
                                socketOut.flush();
                                break;
                            }
                            if(Mailbox.isValidUser(cmd[1])) {
                                auth = true;
                                user = cmd[1];
                                socketOut.write("+OK Hi bossman\r\n");
                                socketOut.flush();
                                break;
                            }
                            else {
                                socketOut.write("-ERR I don't know you\r\n");
                                socketOut.flush();
                                auth = false;
                                break;
                            }
                        case "PASS":
                            if(cmd.length == 1) {
                                socketOut.write("-ERR Where's the password?\r\n");
                                socketOut.flush();
                                break;
                            }
                            if(state == 0 && auth) {
                                mb = new Mailbox(user);
                                String pass = getPass(cmd);
                                try{
                                    mb.loadMessages(pass);
                                    int i = 1;
                                    for(MailMessage m : mb) {
                                        msgIndex.add(i);
                                        i++;
                                    }
                                    socketOut.write("+OK finally, your in\r\n");
                                    socketOut.flush();
                                    state = 1;
                                    break;
                                }
                                catch(MailboxNotAuthenticatedException e) {
                                    socketOut.write("-ERR Are you trying to break in?\r\n");
                                    socketOut.flush();
                                    break;
                                }
                            }
                            else {
                                socketOut.write("-ERR Who are you?\r\n");
                                socketOut.flush();
                                break;
                            }
                        case "STAT":
                            if(state != 1) {
                                socketOut.write("-ERR NONO BAD\r\n");
                                socketOut.flush();
                                break;
                            }
                            socketOut.write("+OK " + mb.size(false) + 
                            " " + mb.getTotalUndeletedFileSize(false) + "\r\n");
                            socketOut.flush();
                            break;
                        case "LIST":
                            if(state != 1) {
                                socketOut.write("-ERR NONO\r\n");
                                socketOut.flush();
                                break;
                            }
                            if(cmd.length > 1) { // Has argument.
                                if(Integer.parseInt(cmd[1]) < 1 || Integer.parseInt(cmd[1]) > mb.size(true) || mb.getMailMessage(Integer.parseInt(cmd[1])).isDeleted()) {
                                    socketOut.write("-ERR No such message\r\n");
                                    socketOut.flush();
                                    break;
                                }
                                else {
                                    socketOut.write("+OK " + 
                                    cmd[1] + " " + 
                                    (int)mb.getMailMessage(Integer.parseInt(cmd[1])).getFileSize() + "\r\n");
                                    socketOut.flush();
                                    break;
                                }
                            }
                            else if(cmd.length == 1) {
                                socketOut.write("+OK " + mb.size(false) + " messages\r\n");
                                socketOut.flush();
                                if(mb.size(false) == 0) {
                                    socketOut.write(".\r\n");
                                    socketOut.flush();
                                    break;
                                }
                                else {
                                    int i = 0;
                                    for(MailMessage m : mb) {
                                        if(!m.isDeleted()) {
                                            socketOut.write(msgIndex.get(i)+ " " + m.getFileSize() + "\r\n");
                                            socketOut.flush();
                                            i+=1;
                                        }
                                    }
                                    socketOut.write(".\r\n");
                                    socketOut.flush();
                                    break;
                                }
                            }
                            else {
                                socketOut.write("-ERR Too many arguments\r\n");
                                socketOut.flush();
                                break;
                            }
                        case "RETR":
                            if(state != 1) {
                                socketOut.write("-ERR Bad sequence of commands\r\n");
                                socketOut.flush();
                                break;
                            }
                            else if(cmd.length == 1 || Integer.parseInt(cmd[1]) < 1 || Integer.parseInt(cmd[1]) > mb.size(false)) {
                                socketOut.write("-ERR No such message\r\n");
                                socketOut.flush();
                                break;
                            }
                            else if(mb.getMailMessage(Integer.parseInt(cmd[1])).isDeleted()) {
                                socketOut.write("-ERR No such message\r\n");
                                socketOut.flush();
                                break;
                            }
                            else if(cmd.length > 2) {
                                socketOut.write("-ERR Choose one message\r\n");
                                socketOut.flush();
                                break;
                            }
                            else {
                                String message = new String(Files.readAllBytes(Paths.get(mb.getMailMessage(Integer.parseInt(cmd[1])).getFile().toString())));
                                
                                socketOut.write("+OK " + 
                                    cmd[1] + " " + 
                                    (int)mb.getMailMessage(Integer.parseInt(cmd[1])).getFileSize() + "\r\n");
                                socketOut.flush();

                                socketOut.write(message + ".\r\n");
                                socketOut.flush();
                                break;
                            }
                        case "DELE":
                            if(state != 1) {
                                socketOut.write("-ERR Bad sequence of commands\r\n");
                                socketOut.flush();
                                break;
                            }
                            if(cmd.length < 2) { 
                                socketOut.write("-ERR No index given\r\n");
                                socketOut.flush();
                                break;
                            }
                            else if(cmd.length == 2) {
                                if(Integer.parseInt(cmd[1]) < 1 || Integer.parseInt(cmd[1]) > mb.size(true)) {
                                    socketOut.write("-ERR Bad index\r\n");
                                    socketOut.flush();
                                    break;
                                }
                                else if(mb.getMailMessage(Integer.parseInt(cmd[1])).isDeleted()) {
                                    socketOut.write("-ERR Message already deleted\r\n");
                                    socketOut.flush();
                                    break;
                                }
                                else {
                                    mb.getMailMessage(Integer.parseInt(cmd[1])).tagForDeletion();
                                    deletedmsg.add(msgIndex.remove(msgIndex.indexOf(Integer.parseInt(cmd[1]))));
                                    //msgIndex.remove(msgIndex.indexOf(Integer.parseInt(cmd[1])));
                                    socketOut.write("+OK Deleted\r\n");
                                    socketOut.flush();
                                    break;
                                }
                            }
                            else {
                                socketOut.write("-ERR Choose one index\r\n");
                                socketOut.flush();
                                break;
                            }
                        case "RSET":
                            if(state != 1) {
                                socketOut.write("-ERR Bad sequence of commands\r\n");
                                socketOut.flush();
                                break;
                            }
                            for(MailMessage m : mb) {
                                m.undelete();
                            }
                            msgIndex.addAll(deletedmsg);
                            Collections.sort(msgIndex);
                            socketOut.write("+OK Messages undeleted\r\n");
                            socketOut.flush();
                            break;
                        case "NOOP":
                                socketOut.write("+OK YAYA\r\n");
                                socketOut.flush();
                                break;
                        case "QUIT":
                            if(mb != null) {
                                mb.deleteMessagesTaggedForDeletion();   
                            }   
                            socketOut.write("+OK See you next time\r\n");
                            socketOut.flush();
                            socketOut.close();
                            socketIn.close();
                            socket.close();
                            break;
                    }
            }

        } catch (IOException e) {
            System.err.println("Error in client's connection handling.");
            e.printStackTrace();
        }
    }

    private String getPass(String[] split) {

        String pass = "";
        for(int i = 1; i < split.length-1; i++) {
            pass += split[i] + " ";
        }
        pass += split[split.length-1];

        return pass;
    }

    /**
     * Main process for the POP3 server. Handles the argument parsing and creates a listening server socket. Repeatedly
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
            while (true) {
                Socket socket = serverSocket.accept();
                System.out.println("Accepted a connection from " + socket.getRemoteSocketAddress());
                try {
                    MyPOPServer handler = new MyPOPServer(socket);
                    handler.start();
                } catch (IOException e) {
                    System.err.println("Error setting up an individual client's handler.");
                    e.printStackTrace();
                }
            }
        }
    }
}

// You can use this file as a starting point for your dictionary client
// The file contains the code for command line parsing and it also
// illustrates how to read and partially parse the input typed by the user. 
// Although your main class has to be in this file, there is no requirement that you
// use this template or hav all or your classes in this file.

import java.lang.System;
import java.io.*;
import java.util.Arrays;
import java.util.List;
import java.io.BufferedReader;
import java.net.*;

//
// This is an implementation of a simplified version of a command
// line dictionary client. The only argument the program takes is
// -d which turns on debugging output. 
//


public class CSdict {
    static final int MAX_LEN = 255;
    static Boolean debugOn = false;
    static String setDict = "*";
    
    private static final int PERMITTED_ARGUMENT_COUNT = 1;
    private static String command;
    private static String[] arguments;
    private static String outputString;
    private static String noDef = "****No definition found****";
    private static String noMatch = "****No matches found****";
    private static String noWord = "****No matching word(s) found****";
    private static String invalidCommand = "900 Invalid command.";
    private static String incorrectNumArg = "901 Incorrect number of arguments.";
    private static String invalidArgument = "902 Invalid argument.";
    private static String notExpected = "903 Supplied command not expected at this time.";
    private static String connectionError = "925 Control connection I/O error, closing control connection.";
    private static String connectionFailed = "920 Control connection to xxx on port yyy failed to open.";
    private static String tooManyCmdLnOpt = "996 Too many command line options - Only -d is allowed.";
    private static String invalidCmdLnOpt = "997 Invalid command line option - Only -d is allowed.";
    private static String inputError = "998 Input error while reading commands, terminating";
    private static String invalidDatabase = "999 Processing error. Invalid database, use SET DICTIONARY to set a valid dictionary.";
    private static String noDict = "999 Processing error. No databases present.";
    private static String readTimeOut = "999 Processing error. Timed out while waiting for a response.";
    private static BufferedReader in = null;
    private static PrintWriter out = null;
    private static String sentout;
    private static Socket dicSocket = null;
    private static Boolean isConnected = false;
    static final String[] validCommands = {
            "open",
            "dict",
            "set",
            "define",
            "match",
            "prefixmatch",
            "close",
            "quit"
    };
    
    public static void main(String [] args) {
        byte cmdString[];

	// Verify command line arguments

        if (args.length == PERMITTED_ARGUMENT_COUNT) {
            debugOn = args[0].equals("-d");
            if (!debugOn) {
                System.out.println(invalidCmdLnOpt);
                return;
            }
        } else if (args.length > PERMITTED_ARGUMENT_COUNT) {
            System.out.println(tooManyCmdLnOpt);
            return;
        }



	// Example code to read command line input and extract arguments.
	
        try {
            while (true){
                cmdString  = new byte[MAX_LEN];
                System.out.print("csdict> ");

                System.in.read(cmdString);
                // Convert the command string to ASII
                String inputString = new String(cmdString, "ASCII");
                // Split the string into words
                String[] inputs = inputString.trim().split("( |\t)+");
                // Set the command
                command = inputs[0].toLowerCase().trim();
                // Remainder of the inputs is the arguments.
                arguments = Arrays.copyOfRange(inputs, 1, inputs.length);

                switch (command) {
                    case "open":
                        if (validArgLength(2)) {
                            executeOpen();
                        } else {
                            System.out.println(incorrectNumArg);
                        }
                        break;
                    case "dict":
                        if (validArgLength(0)) {
                            if (isConnected) {
                                executeDict();
                            } else {
                                System.out.println(notExpected);
                            }
                        } else {
                            System.out.println(incorrectNumArg);
                        }
                        break;
                    case "set":
                        if (isConnected) {
                            if (validArgLength(1)) {
                                setDict = arguments[0];
                            } else {
                                System.out.println(incorrectNumArg);
                            }
                        } else {
                            System.out.println(notExpected);
                        }
                        break;
                    case "define":
                        if (validArgLength(1)) {
                            if (isConnected) {
                                executeDefine();
                            } else {
                                System.out.println(notExpected);
                            }
                        } else {
                            System.out.println(incorrectNumArg);
                        }
                        break;
                    case "match":
                        if (validArgLength(1) ) {
                            if (isConnected) {
                                executeMatch("EXACT");
                            } else {
                                System.out.println(notExpected);
                            }
                        } else {
                            System.out.println(incorrectNumArg);
                        }
                        break;
                    case "prefixmatch":
                        if (validArgLength(1) ) {
                            if (isConnected) {
                                executeMatch("PREFIX");
                            } else {
                                System.out.println(notExpected);
                            }
                        } else {
                            System.out.println(incorrectNumArg);
                        }
                        break;
                    case "close":
                        if (validArgLength(0)) {
                            if (isConnected) {
                                executeClose();
                            } else {
                                System.out.println(notExpected);
                            }
                        } else {
                            System.out.println(incorrectNumArg);
                        }
                        break;
                    case "quit":
                        if (validArgLength(0)) {
                            if (isConnected) {
                                executeClose();
                            }
                            System.exit(1);
                        } else {
                            System.out.println(incorrectNumArg);
                        }
                        break;
                    default:
                        System.out.println(invalidCommand);
                }
            }
        } catch (IOException exception) {
            System.err.println(inputError);
            System.exit(-1);
        }
    }


    private static void executeOpen() {
        if (arguments.length != 2) {
            System.out.println(incorrectNumArg);
            return;
        }
        String host = arguments[0];
        String port = arguments[1];
        try {
            int portInt = Integer.parseInt(port);
            dicSocket = new Socket();
            SocketAddress socketAddress = new InetSocketAddress(host, portInt);
            dicSocket.connect(socketAddress, 3000);
            dicSocket.setSoTimeout(3000);
            isConnected = true;

            out = new PrintWriter(dicSocket.getOutputStream(), true);
            in = new BufferedReader(new InputStreamReader(dicSocket.getInputStream()));
            setDict = "*";
            outputString = in.readLine();

            if (debugOn) outputString = "<-- " + outputString;
            System.out.println(outputString);
        } catch (SocketTimeoutException e) {
            String exception = e.toString();
            if (exception.contains("Read timed out")) {
                System.out.println(readTimeOut);
            } else if (exception.contains("Connect timed out")) {
                System.out.println(connectionFailed.replace("xxx", host).replace("yyy", port));
            }
        } catch (IOException e) {
            System.out.println(connectionError);
        } catch (NumberFormatException e) {
            System.out.println(invalidArgument);
        }
    }
    private static void executeDict() {
        if (arguments.length != 0) {
            System.out.println(incorrectNumArg);
            return;
        }
        try {
            out.println("SHOW DB" + "\r\n");
            String[] outputStringArray = verifyOutputString();
            String status = outputStringArray[0];
            String message = outputStringArray[1];

            if(checkStatusCodeAndPrintMessage(status, message)) return;
            System.out.println(message);
        } catch (IOException e) {
            System.out.println(connectionError);
        }
    }

    private static void executeDefine() {
        try {
            sentout = "DEFINE " + setDict + " " + arguments[0] +"\r\n";
            if (debugOn) System.out.println("> "+ sentout.trim());
            out.println(sentout);

            String[] outputStringArray = verifyOutputString();
            String status = outputStringArray[0];
            String message = outputStringArray[1];
            if (status.equals("552")) {
                if (!message.equals("")) System.out.println(message);
                System.out.println(noDef);
                match(arguments[0], ".");
                outputStringArray = verifyOutputString();
                status = outputStringArray[0];
                message = outputStringArray[1];
            }
            if (checkStatusCodeAndPrintMessage(status, message, false)) return;
            System.out.println(message);
        } catch (IOException e) {
            System.out.println(connectionError);
        }
    }

    public static void match (String matchString , String stragery ) throws IOException {
        String sentout = "MATCH " + setDict + " " + stragery + " " + matchString +"\r\n";
        out.println(sentout);
    }

    private static void executeMatch(String matchType) {
        try {
            if (debugOn) System.out.println("> "+ "MATCH " + setDict + " " + matchType + " " + arguments[0] );
            match(arguments[0],matchType);
            String[] outputStringArray = verifyOutputString();
            String status = outputStringArray[0];
            String message = outputStringArray[1];
            if(checkStatusCodeAndPrintMessage(status, message)) return;
            System.out.println(message);
        } catch (IOException e) {
            System.out.println(connectionError);
        }
    }

    private static void executeClose() {
        try {
            sentout = "QUIT\r\n";
            out.println(sentout);
            if (debugOn) System.out.println("> QUIT");
            String[] outputStringArray = verifyOutputString();
            String status = outputStringArray[0];
            String message = outputStringArray[1];
            if (checkStatusCodeAndPrintMessage(status, message, false)) return;
            System.out.println(message);
            out.println("QUIT");
            in.close();
            out.close();
            dicSocket.close();
            isConnected = false;
            setDict = "*";
            in = null;
            out = null;
        } catch (IOException e) {
            System.out.println(connectionError);
        }
    }

    public static String[] verifyOutputString() throws IOException {
        String outputStringArray[] = new String[2];
        String outputString = "";
        String currentLine;
        String status;
        while (true) {
            if (in.ready()) {
                currentLine = in.readLine();
                status = currentLine.trim().split(" ")[0];
                if (outputStringArray[0] == null) outputStringArray[0] = status;
                if (status.matches("^\\d\\d\\d")) {
                    // check lines start with 3-digit statuses
                    if (status.matches("^(552|551|550|421|420|221)")
                            || ((status.equals("250")) && currentLine.trim().split(" ")[1].equals("ok"))) {
                        // terminal statuses
                        if (debugOn) outputString = outputString + "<-- " + currentLine;
                        break;
                    } else if (status.equals("151")) {
                        // status for each dictionary match in "Define"
                        String[] dictionaryNameArray = currentLine.split(" ", 3);
                        // get and add dictionary name
                        currentLine = currentLine + "\n@ " + dictionaryNameArray[2];
                    }
                    if (debugOn) {
                        currentLine = "<-- " + currentLine;
                    } else {
                        if (!status.equals("151")) continue;
                        // print only the dictionary name with no status code
                        currentLine = currentLine.split("\n", 2)[1];
                    }
                }
                outputString += currentLine + "\n";
            }
        }
        outputStringArray[1] = outputString.trim();
        return outputStringArray;
    }

    public static boolean checkStatusCodeAndPrintMessage(String status, String debugMessage, boolean isMatch) {
        if (status.matches("^(501|550|552|554)")) {
            if (!debugMessage.equals("")) System.out.println(debugMessage);
            String statusMessage = "";
            switch (status) {
                case "501":
                    statusMessage = incorrectNumArg;
                    break;
                case "550":
                    statusMessage = invalidDatabase;
                    break;
                case "552":
                    statusMessage = isMatch ? noWord : noMatch;
                    break;
                case "554":
                    statusMessage = noDict;
                    break;
            }
            System.out.println(statusMessage);
            return true;
        }
        return false;
    }

    public static boolean checkStatusCodeAndPrintMessage(String status, String debugMessage){
        return checkStatusCodeAndPrintMessage(status,debugMessage,true);
    }

    public static boolean validArgLength (int argLength) {
        return arguments.length == argLength;
    }
}
    
    

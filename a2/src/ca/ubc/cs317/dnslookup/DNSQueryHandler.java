package ca.ubc.cs317.dnslookup;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.io.*;

public class DNSQueryHandler {

    private static final int DEFAULT_DNS_PORT = 53;
    private static DatagramSocket socket;
    private static boolean verboseTracing = false;

    private static final Random random = new Random();

    /**
     * Sets up the socket and set the timeout to 5 seconds
     *
     * @throws SocketException if the socket could not be opened, or if there was an
     *                         error with the underlying protocol
     */
    public static void openSocket() throws SocketException {
        socket = new DatagramSocket();
        socket.setSoTimeout(5000);
    }

    /**
     * Closes the socket
     */
    public static void closeSocket() {
        socket.close();
    }

    /**
     * Set verboseTracing to tracing
     */
    public static void setVerboseTracing(boolean tracing) {
        verboseTracing = tracing;
    }

    /**
     * Builds the query, sends it to the server, and returns the response.
     *
     * @param message Byte array used to store the query to DNS servers.
     * @param server  The IP address of the server to which the query is being sent.
     * @param node    Host and record type to be used for search.
     * @return A DNSServerResponse Object containing the response buffer and the transaction ID.
     * @throws IOException if an IO Exception occurs
     */
    public static DNSServerResponse buildAndSendQuery(byte[] message, InetAddress server,
                                                      DNSNode node) throws IOException {
        int attempt = 2;
        // TODO (PART 1): Implement this
        //Prepare output stream to input
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        DataOutputStream dataOutputStream = new DataOutputStream(byteArrayOutputStream);

        //Give and write a transaction ID
        short ID = (short) random.nextInt(Short.MAX_VALUE);
        dataOutputStream.writeShort(ID);
        while (true){
            try {
                //Input rest parts of the header
                dataOutputStream.writeShort(0x0000);
                dataOutputStream.writeShort(0x0001); //QDCOUNT
                dataOutputStream.writeShort(0x0000); //ANCOUNT
                dataOutputStream.writeShort(0x0000); //NSCOUNT
                dataOutputStream.writeShort(0x0000); //ARCOUNT

                String[] hostnames = node.getHostName().split("\\.");
                for (int i = 0; i < hostnames.length; i++) {
                    byte[] domainName = hostnames[i].getBytes();
                    dataOutputStream.writeByte(domainName.length);
                    dataOutputStream.write(domainName);
                }

                dataOutputStream.writeByte(0x00); //End of question
                dataOutputStream.writeShort(node.getType().getCode()); //Write type
                dataOutputStream.writeShort(0x0001); //Write QClass
                message = byteArrayOutputStream.toByteArray();
//                for (byte b : message)
//                    System.out.printf("%X ", b);


                DatagramPacket datagramPacket = new DatagramPacket(message, message.length, server, DEFAULT_DNS_PORT);
                if (verboseTracing)
                    System.out.println("\n\nQuery ID     " + ID + " " + node.getHostName() + "  "
                        + node.getType() + " --> " + server.getHostAddress());
                socket.send(datagramPacket);

                //Response buffer
                byte[] buffer = new byte[512];
                DatagramPacket receivePacket = new DatagramPacket(buffer, buffer.length);
                socket.setSoTimeout(500);
                socket.receive(receivePacket);
                byte[] data = receivePacket.getData();
    //            for (byte by : data)
    //                System.out.println(by);
                ByteBuffer byteBuffer = ByteBuffer.allocate(data.length).put(data);
                return new DNSServerResponse(byteBuffer, ID);

            } catch (SocketTimeoutException e) {
                attempt --;
                if (attempt == 0) {
                    return new DNSServerResponse(ByteBuffer.allocate(512),ID);
                }
            }
        }
    }

    /**
     * Decodes the DNS server response and caches it.
     *
     * @param transactionID  Transaction ID of the current communication with the DNS server
     * @param responseBuffer DNS server's response
     * @param cache          To store the decoded server's response
     * @return A set of resource records corresponding to the name servers of the response.
     */
    public static Set<ResourceRecord> decodeAndCacheResponse(int transactionID, ByteBuffer responseBuffer, DNSCache cache) {
            responseBuffer.clear();
//            while (responseBuffer.hasRemaining()) {
//                System.out.print(String.format("%X", responseBuffer.get()) + " ");
//            }
//            System.out.println("\n over");
            responseBuffer.clear();
            Set<ResourceRecord> rscResult = new HashSet<>();
            Map<Integer, String> compressionMap = new HashMap<>();
        try {
            //check if the ID is correct
            int answerID = responseBuffer.getShort();
            if (answerID != transactionID) return rscResult;
            byte AAByte = responseBuffer.get();
//            System.out.printf("\n%s%X\n", "AAByte: ", AAByte);
            boolean isAuthoritative = (AAByte & 0x04) != 0;
            if (verboseTracing) {
                System.out.println("Response ID: " + answerID + " Authoritative = " + isAuthoritative);
            }

            //Return code
            byte RCodeByte = responseBuffer.get();
            int RCode = RCodeByte & 0X0F;

            //We can skip QDCount since in this assignment there's always 1 question
            int currentPosition = responseBuffer.position();
            responseBuffer.position(currentPosition + 2);

            //2bytes ANCount
            short ANCount = responseBuffer.getShort();

            //2bytes NSCount
            short NSCount = responseBuffer.getShort();

            //2bytes ARCount
            short ARCount = responseBuffer.getShort();

            //Skip the QName and store their offset and name in map
            decodeNameAndConstructMap(compressionMap,responseBuffer);

            //skip the QType and QCLASS
            responseBuffer.getInt();

            if (verboseTracing) {
                System.out.println("  Answers (" + ANCount + ")");
            }
            decodeResourceRecord(ANCount, responseBuffer, compressionMap,rscResult, cache);

            if (verboseTracing) {
                System.out.println("  Nameservers  (" + NSCount + ")");
            }
            decodeResourceRecord(NSCount, responseBuffer,compressionMap,rscResult, cache);

            if (verboseTracing){
                System.out.println("  Additional Information (" + ARCount + ")");
            }
            decodeResourceRecord(ARCount,responseBuffer,compressionMap,rscResult,cache);


        } catch (IOException e) {
            System.out.println(e.getMessage());
        }
        return rscResult;
    }


    public static String decodeNameAndConstructMap (Map<Integer, String> compressionMap, ByteBuffer responseBuffer) {
        int len;
        List<String> domainNames = new ArrayList<>();
        List<Integer> pointerPositions = new ArrayList<>();
        while(true) {
            pointerPositions.add(responseBuffer.position());
            len = responseBuffer.get();
            if ((len & 0x00C0) == 0X00C0) {
                //still need to get the last 6 bit from the previous byte for the pointer address (e.g. 0xC1 0b11000001)
                responseBuffer.position(responseBuffer.position()-1);
                //compare the last 22 bit of the integer (pointer address)
                int nextPosition = responseBuffer.getShort()&0X3fff;
                String domainName = compressionMap.get(nextPosition);
                domainNames.add(domainName);
                break;
            }
            if (len == 0) {
                break;
            }
            byte[] storeValue = new byte[len];
            responseBuffer.get(storeValue, 0, len);
            domainNames.add(new String(storeValue, StandardCharsets.UTF_8));
        }
        insertRecords(compressionMap, domainNames, pointerPositions);
        return String.join(".", domainNames);
    }

    private static void insertRecords(Map<Integer, String> compressionMap, List<String> domainNames, List<Integer> pointerPositions) {
        for (int i = 0; i < domainNames.size(); i++) {
            String domainName = domainNames.get(i);
            for (int j = i + 1; j < domainNames.size(); j++) {
                domainName = domainName + "." + domainNames.get(j);
            }
            compressionMap.put(pointerPositions.get(i), domainName);
        }
    }

    private static void decodeResourceRecord (int count, ByteBuffer responseBuffer,Map<Integer, String> compressionMap,Set<ResourceRecord> rscResult, DNSCache cache) throws IOException{
        while (count-- > 0){
            // GET NAME
            String currentName = decodeNameAndConstructMap(compressionMap,responseBuffer);
            // GET TYPE
            RecordType type = RecordType.getByCode(responseBuffer.getShort());
            // GET CLASS
            int RRClass = responseBuffer.getShort();
            // GET TTL
            long TTL = responseBuffer.getInt() & 0x00000000ffffffffL;
            // GET LENGTH
            int dataLength = responseBuffer.getShort();

            ResourceRecord currentRecord = null;
            switch (type){
                case A:
                    currentRecord = new ResourceRecord(currentName,type,TTL,Inet4Address.getByName(decodeAddress(responseBuffer,type)));
                    break;
                case AAAA:
                    currentRecord = new ResourceRecord(currentName,type,TTL,Inet6Address.getByName(decodeAddress(responseBuffer,type)));
                    break;
                case CNAME:
                case NS:
                    currentRecord = new ResourceRecord(currentName,type,TTL,decodeNameAndConstructMap(compressionMap,responseBuffer));
                    if (type == RecordType.NS) rscResult.add(currentRecord);
                    break;
                case SOA:
                    int position = responseBuffer.position();
                    currentRecord = new ResourceRecord(currentName,type,TTL,decodeNameAndConstructMap(compressionMap,responseBuffer));
                    responseBuffer.position(dataLength+position);
                    break;
                case MX:
                    responseBuffer.getShort();
                    currentRecord = new ResourceRecord(currentName,type,TTL,decodeNameAndConstructMap(compressionMap, responseBuffer));
                    break;
                case OTHER:
                    currentRecord = new ResourceRecord(currentName,type,TTL,decodeOther(responseBuffer,dataLength&0XFF));

            }
            cache.addResult(currentRecord);
            verbosePrintResourceRecord(currentRecord,type.getCode());
        }
    }

    private static String decodeAddress(ByteBuffer responseBuffer,RecordType type){
        List<String> address = new ArrayList<>();
        int length = 0;
        switch (type){
            case A:
                length = 4;
                for (int i = 0 ; i<length; i++){
                    address.add(String.valueOf(responseBuffer.get()&0xFF));
                }
                return String.join(".",address);
            case AAAA:
                length = 8;
                for (int i = 0 ; i<length; i++){
                    address.add(Integer.toHexString(responseBuffer.getShort()&0xFFFF));
                }
                return String.join(":",address);
        }
        return "";
    }


    public static String decodeOther(ByteBuffer responseBuffer, int length) {
        if(responseBuffer.position() + length > 511){
            responseBuffer.position(511);
        }else{
            responseBuffer.position(length);
        }
        return "unknown type";
    }


    /**
     * Formats and prints record details (for when trace is on)
     *
     * @param record The record to be printed
     * @param rtype  The type of the record to be printed
     */
    private static void verbosePrintResourceRecord(ResourceRecord record, int rtype) {
        if (verboseTracing)
            System.out.format("       %-30s %-10d %-4s %s\n", record.getHostName(),
                    record.getTTL(),
                    record.getType() == RecordType.OTHER ? rtype : record.getType(),
                    record.getTextResult());
    }
}


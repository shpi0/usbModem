import jssc.*;
import org.ajwcc.pduUtils.gsm3040.Pdu;
import org.ajwcc.pduUtils.gsm3040.PduParser;
import org.ajwcc.pduUtils.gsm3040.PduUtils;
import org.ajwcc.pduUtils.gsm3040.ie.ConcatInformationElement;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

public class Main {

    private static final String CONFIG_FILE = "./app.properties";

    private static SerialPort serialPort;
    private static boolean configureMode = true;
    private static boolean deletingMessages = false;
    private static boolean modemFound = false;
    private static List<String> answers = new ArrayList<>();

    private static Set<SmsMessage> messages = new TreeSet<>();
    private static List<String> readyMessages = new ArrayList<>();

    public static void main(String[] args) {
        new Main().start();
    }

    private void start() {
        System.out.println("Available ports: ");
        String[] ports = SerialPortList.getPortNames();
        Stream.of(ports).forEach(System.out::println);
        if (Files.exists(Paths.get(CONFIG_FILE))) {
            modemFound = true;
            Properties properties = new Properties();
            try {
                properties.load(new FileInputStream(CONFIG_FILE));
            } catch (IOException e) {
                e.printStackTrace();
            }
            System.out.println("Configured port: ");
            System.out.println(properties.getProperty("com_port"));
            serialPort = new SerialPort(properties.getProperty("com_port"));
        } else {
            System.out.println("No port configured in properties file, trying to find device.");
            for (String port :ports) {
                serialPort = new SerialPort(port);
                System.out.print("Search device on " + port + " ... ");
                try {
                    serialPort.openPort();
                    serialPort.setParams(SerialPort.BAUDRATE_9600,
                            SerialPort.DATABITS_8,
                            SerialPort.STOPBITS_1,
                            SerialPort.PARITY_NONE);
                    serialPort.setFlowControlMode(SerialPort.FLOWCONTROL_RTSCTS_IN |
                            SerialPort.FLOWCONTROL_RTSCTS_OUT);
                    serialPort.addEventListener(new PortReader(), SerialPort.MASK_RXCHAR);
                    serialPort.writeString("ATI\r\n");
                    Thread.sleep(2_000);
                    if (modemFound) {
                        System.out.println("Found a modem!");
                        serialPort.closePort();
                        Thread.sleep(5_000);
                        break;
                    } else {
                        System.out.println("No device on this port!");
                    }
                } catch (SerialPortException | InterruptedException ex) {
                    ex.printStackTrace();
                }
            }
            if (!modemFound) {
                System.out.println("Can't find modem!");
                return;
            }
        }
        try {
            serialPort.openPort();
            serialPort.setParams(SerialPort.BAUDRATE_9600,
                    SerialPort.DATABITS_8,
                    SerialPort.STOPBITS_1,
                    SerialPort.PARITY_NONE);
            serialPort.setFlowControlMode(SerialPort.FLOWCONTROL_RTSCTS_IN |
                    SerialPort.FLOWCONTROL_RTSCTS_OUT);
            serialPort.addEventListener(new PortReader(), SerialPort.MASK_RXCHAR);

            System.out.println("Configuring modem");

            System.out.print("AT+CMGF=0 ... ");
            serialPort.writeString("AT+CMGF=0\r\n");
            Thread.sleep(2_000);

            System.out.print("AT+CPMS=\"MT\" ... ");
            serialPort.writeString("AT+CPMS=\"MT\"\r\n");
            Thread.sleep(2_000);

            configureMode = false;

            System.out.print("Checking all messages on SIM with command AT+CMGL=4 ... ");
            serialPort.writeString("AT+CMGL=4\r\n");
            Thread.sleep(15_000);
            processReadyMessages();
            readyMessages.forEach(System.out::println);
            readyMessages.clear();
            answers.clear();

            while (true) {
                System.out.print("Checking new messages on SIM with command AT+CMGL=0 ... ");
                serialPort.writeString("AT+CMGL=0\r\n");
                Thread.sleep(30_000);
                processReadyMessages();
                readyMessages.forEach(m -> {
                    if (m.contains("BankTochka:")) {
                        System.out.println("Code: " + m.split(" ")[4]);
                    }
                });
                readyMessages.clear();
                answers.clear();
                deletingMessages = true;
                serialPort.writeString("AT+CMGD=1,3\r\n");
                Thread.sleep(30_000);
            }

        }
        catch (SerialPortException | InterruptedException ex) {
            ex.printStackTrace();
        }
    }

    private void processReadyMessages() {
        int last = -1;
        String msgData = "";
        String address = "";
        for (SmsMessage message :messages) {
            if (message.getParts() == -1) {
                readyMessages.add(message.getAddress() + ": " + message.getData());
                last = -1;
            } else {
                if (last == -1) {
                    last = message.getId();
                }
                if (last != message.getId()) {
                    readyMessages.add(message.getAddress() + ": " + msgData);
                    msgData = "";
                }
                last = message.getId();
                msgData += message.getData();
            }
            address = message.getAddress();
        }
        if (last != -1) {
            readyMessages.add(address + ": " + msgData);
        }
        messages.clear();
    }

    private static class SmsMessage implements Comparable {
        private int id;
        private int part;
        private int parts;
        private String data;
        private String address;

        public int getId() {
            return id;
        }

        public int getPart() {
            return part;
        }

        public int getParts() {
            return parts;
        }

        public String getData() {
            return data;
        }

        public String getAddress() {
            return address;
        }

        public SmsMessage(int id, int part, int parts, String data, String address) {
            this.id = id;
            this.part = part;
            this.parts = parts;
            this.data = data;
            this.address = address;
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, part, parts, data);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj.getClass() != this.getClass()) {
                return false;
            }
            SmsMessage o = ((SmsMessage) obj);
            return this.id == o.getId() && this.part == o.getPart() && this.parts == o.getParts() && this.data.equals(o.getData());
        }

        @Override
        public int compareTo(Object o) {
            SmsMessage m = (SmsMessage)o;
            return this.id - m.getId() == 0 ? (
                    this.part - m.getPart() == 0 ? this.data.compareTo(m.getData()) : this.part - m.getPart()
                    ) : this.id - m.getId();
        }
    }

    private static class PortReader implements SerialPortEventListener {

        public void serialEvent(SerialPortEvent event) {
            if(event.isRXCHAR() && event.getEventValue() > 0){
                try {
                    String data = serialPort.readString(event.getEventValue());
                    if (deletingMessages) {
                        deletingMessages = false;
                        return;
                    }
                    if (configureMode) {
                        if (!modemFound) {
                            if (data.contains("IMEI:")) {
                                modemFound = true;
                                return;
                            }
                        }
                        if (data.contains("OK")) {
                            System.out.println("OK");
                        } else {
                            System.out.println("PROBLEM:");
                            System.out.println(data);
                        }
                    } else {
                        answers.addAll(Arrays.asList(data.split("\n")));
                        if (data.contains("OK")) {
                            boolean nextSmsData = false;
                            String smsdata = "";
                            int i = 0;
                            for (String s :answers) {
                                if (nextSmsData && !s.contains("+CMGL:") && !s.isEmpty() && !s.contains("OK")) {
                                    smsdata += s.trim();
                                } else {
                                    if (!smsdata.isEmpty()) {
                                        i++;
                                        Pdu pdu = new PduParser().parsePdu(smsdata);
                                        byte[] bytes = pdu.getUserDataAsBytes();
                                        String decodedMessage = null;
                                        int dataCodingScheme = pdu.getDataCodingScheme();
                                        if (dataCodingScheme == PduUtils.DCS_ENCODING_7BIT) {
                                            decodedMessage = PduUtils.decode7bitEncoding(null, bytes);
                                        } else if (dataCodingScheme == PduUtils.DCS_ENCODING_8BIT) {
                                            decodedMessage = PduUtils.decode8bitEncoding(null, bytes);
                                        } else if (dataCodingScheme == PduUtils.DCS_ENCODING_UCS2) {
                                            decodedMessage = PduUtils.decodeUcs2Encoding(null, bytes);
                                        }
                                        if (decodedMessage != null) {
                                            if (pdu.isConcatMessage()) {
                                                ConcatInformationElement e = pdu.getConcatInfo();
                                                messages.add(new SmsMessage(e.getMpRefNo(), e.getMpSeqNo(), e.getMpMaxNo(), decodedMessage, pdu.getAddress()));
                                            } else {
                                                messages.add(new SmsMessage(-1, -1, -1, decodedMessage, pdu.getAddress()));
                                            }
                                        }
                                        smsdata = "";
                                    }
                                    nextSmsData = false;
                                }
                                if (!s.isEmpty() && !s.contains("OK")) {
                                    if (s.contains("+CMGL:")) {
                                        nextSmsData = true;
                                    }
                                }
                            }
                            System.out.println("Found " + i + " messages parts");
                        }
                    }
                } catch (SerialPortException ex) {
                    ex.printStackTrace();
                }
            }
        }
    }

}


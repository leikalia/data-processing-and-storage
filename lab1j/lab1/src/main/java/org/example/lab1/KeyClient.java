package org.example.lab1;

import java.io.File;
import java.io.FileOutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;

public class KeyClient {

    public static void main(String[] args) throws Exception {
        String host = "127.0.0.1";
        int port = 5555;
        String name = null;
        int delaySec = 0;
        boolean exitAfterSend = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host" -> host = args[++i];
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--name" -> name = args[++i];
                case "--delay" -> delaySec = Integer.parseInt(args[++i]);
                case "--exit-after-send" -> exitAfterSend = true;
                default -> {}
            }
        }
        if (name == null) {
            System.out.println("Usage: KeyClient --host 127.0.0.1 --port 5555 --name alice [--delay 3] [--exit-after-send]");
            return;
        }

        try (SocketChannel ch = SocketChannel.open()) {
            ch.connect(new InetSocketAddress(host, port));
            byte[] req = (name + "\0").getBytes(StandardCharsets.US_ASCII);
            ch.write(ByteBuffer.wrap(req));

            if (delaySec > 0) Thread.sleep(delaySec * 1000L);
            if (exitAfterSend) return;

            ByteBuffer i4 = ByteBuffer.allocate(4);
            readFully(ch, i4);
            int keyLen = i4.flip().getInt();

            byte[] key = readBytes(ch, keyLen);
            i4.clear();
            readFully(ch, i4);
            int crtLen = i4.flip().getInt();
            byte[] crt = readBytes(ch, crtLen);

            try (FileOutputStream f1 = new FileOutputStream(new File(name + ".key"))) { f1.write(key); }
            try (FileOutputStream f2 = new FileOutputStream(new File(name + ".crt"))) { f2.write(crt); }

            System.out.println("Saved: " + name + ".key and " + name + ".crt");
        }
    }

    private static byte[] readBytes(SocketChannel ch, int len) throws Exception {
        ByteBuffer buf = ByteBuffer.allocate(len);
        readFully(ch, buf);
        return buf.array();
    }

    private static void readFully(SocketChannel ch, ByteBuffer buf) throws Exception {
        while (buf.hasRemaining()) {
            int n = ch.read(buf);
            if (n == -1) throw new RuntimeException("Connection closed");
        }
    }
}

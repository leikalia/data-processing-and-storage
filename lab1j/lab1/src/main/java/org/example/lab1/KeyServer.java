package org.example.lab1;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class KeyServer {

    public static void main(String[] args) throws Exception {
        Config c = Config.parse(args);
        if (c == null) {
            System.out.println("""
                    Usage:
                      java KeyServer --port 5555 --threads 4 --issuer "CN=MyCA" --ca-key ca-key.pem [--bind 0.0.0.0] [--key-bits 8192]
                    """);
            return;
        }
        new KeyServer(c).start();
    }

    record Config(int port, int threads, String issuer, String bind, PrivateKey caKey, int keyBits) {
        static Config parse(String[] args) throws Exception {
            int port = 0;
            int threads = Math.max(1, Runtime.getRuntime().availableProcessors());
            String issuer = null, caPath = null, bind = "0.0.0.0";
            int keyBits = 8192;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--port" -> port = Integer.parseInt(args[++i]);
                    case "--threads" -> threads = Integer.parseInt(args[++i]);
                    case "--issuer" -> issuer = args[++i];
                    case "--ca-key" -> caPath = args[++i];
                    case "--bind" -> bind = args[++i];
                    case "--key-bits" -> keyBits = Integer.parseInt(args[++i]);
                    default -> { }
                }
            }
            if (port <= 0 || issuer == null || caPath == null) return null;
            return new Config(port, threads, issuer, bind, readPrivateKey(caPath), keyBits);
        }
    }

    private final Config cfg;
    private final ExecutorService genPool;
    private final BlockingQueue<SendTask> sendQueue = new LinkedBlockingQueue<>();
    private final Map<String, CompletableFuture<KeyMaterial>> cache = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(true);

    KeyServer(Config cfg) {
        this.cfg = cfg;
        this.genPool = Executors.newFixedThreadPool(cfg.threads, r -> {
            Thread t = new Thread(r, "gen-" + System.nanoTime());
            t.setDaemon(true);
            return t;
        });
    }

    public void start() throws Exception {
        Thread sender = new Thread(this::senderLoop, "sender");
        sender.setDaemon(true);
        sender.start();
        runSelector();
    }

    private void runSelector() throws Exception {
        try (Selector selector = Selector.open();
             ServerSocketChannel server = ServerSocketChannel.open()) {

            server.configureBlocking(false);
            server.bind(new InetSocketAddress(cfg.bind, cfg.port));
            server.register(selector, SelectionKey.OP_ACCEPT);
            System.out.println("KeyServer listening on " + cfg.bind + ":" + cfg.port +
                    " threads=" + cfg.threads + " issuer=" + cfg.issuer + " keyBits=" + cfg.keyBits);

            while (running.get()) {
                selector.select();
                var it = selector.selectedKeys().iterator();
                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    it.remove();
                    try {
                        if (key.isAcceptable()) {
                            SocketChannel ch = server.accept();
                            if (ch != null) {
                                ch.configureBlocking(false);
                                ch.register(selector, SelectionKey.OP_READ, new ConnState());
                            }
                        } else if (key.isReadable()) {
                            onRead(key);
                        }
                    } catch (CancelledKeyException ignored) { }
                }
            }
        }
    }

    private void onRead(SelectionKey key) throws IOException {
        SocketChannel ch = (SocketChannel) key.channel();
        ConnState st = (ConnState) key.attachment();
        int n = ch.read(st.in);
        if (n == -1) { ch.close(); return; }
        st.in.flip();
        while (st.in.hasRemaining()) {
            byte b = st.in.get();
            if (b == 0) {
                String name = st.sb.toString();
                // один future на имя — исключаем дубли
                CompletableFuture<KeyMaterial> fut = cache.computeIfAbsent(name, nm -> {
                    System.out.println("-> start generation: " + nm);
                    long t0 = System.currentTimeMillis();
                    return CompletableFuture.supplyAsync(() -> generateFor(nm), genPool)
                            .whenComplete((km, ex) -> {
                                if (ex != null) {
                                    cache.remove(nm);
                                    System.err.println("!! generation failed for " + nm + ": " + ex);
                                } else {
                                    System.out.println("<- ready: " + nm + " in " + (System.currentTimeMillis() - t0) + " ms");
                                }
                            });
                });

                // отправка результата/закрытие соединения при ошибке
                fut.handle((km, ex) -> {
                    try {
                        if (ex != null || km == null) {
                            ch.close(); // не держим клиента бесконечно
                        } else {
                            sendQueue.add(new SendTask(ch, km));
                        }
                    } catch (IOException ignored) { }
                    return null;
                });

                st.reset();
            } else {
                st.sb.append((char) b);
                if (st.sb.length() > 8_192) { ch.close(); return; }
            }
        }
        st.in.clear();
    }

    private void senderLoop() {
        while (running.get()) {
            try {
                SendTask t = sendQueue.take();
                SocketChannel ch = t.ch();
                KeyMaterial km = t.km();
                byte[] key = km.privateKeyPem();
                byte[] crt = km.certificatePem();
                ByteBuffer out = ByteBuffer.allocate(8 + key.length + crt.length);
                out.putInt(key.length).put(key).putInt(crt.length).put(crt).flip();
                while (out.hasRemaining()) ch.write(out);
                ch.close();
            } catch (Exception ignored) { }
        }
    }

    private KeyMaterial generateFor(String name) {
        System.out.println(Thread.currentThread());
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(cfg.keyBits);
            KeyPair kp = kpg.generateKeyPair();

            Instant now = Instant.now();
            X500Name issuer = new X500Name(cfg.issuer);
            X500Name subject = new X500Name("CN=" + name);

            SubjectPublicKeyInfo pubInfo = SubjectPublicKeyInfo.getInstance(kp.getPublic().getEncoded());
            X509v3CertificateBuilder b = new X509v3CertificateBuilder(
                    issuer,
                    new BigInteger(64, new SecureRandom()),
                    Date.from(now.minus(1, ChronoUnit.DAYS)),
                    Date.from(now.plus(3650, ChronoUnit.DAYS)),
                    subject,
                    pubInfo
            );
            ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(cfg.caKey);
            X509CertificateHolder holder = b.build(signer);

            byte[] keyPem = toPem("PRIVATE KEY", kp.getPrivate().getEncoded());
            byte[] crtPem = toPem("CERTIFICATE", holder.getEncoded());
            return new KeyMaterial(keyPem, crtPem);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] toPem(String type, byte[] der) {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
        String pem = "-----BEGIN " + type + "-----\n" + base64 + "\n-----END " + type + "-----\n";
        return pem.getBytes(StandardCharsets.US_ASCII);
    }

    private static PrivateKey readPrivateKey(String path) throws Exception {
        try (Reader r = new FileReader(path); PEMParser pp = new PEMParser(r)) {
            Object obj = pp.readObject();
            JcaPEMKeyConverter conv = new JcaPEMKeyConverter();
            if (obj instanceof PEMKeyPair kp) return conv.getKeyPair(kp).getPrivate();
            if (obj instanceof org.bouncycastle.asn1.pkcs.PrivateKeyInfo info) return conv.getPrivateKey(info);
        } catch (IOException ignored) { }
        byte[] bytes = Files.readAllBytes(new File(path).toPath());
        String s = new String(bytes, StandardCharsets.US_ASCII);
        if (s.contains("BEGIN")) {
            s = s.replaceAll("-----[^-]+-----", "").replaceAll("\\s+", "");
            bytes = Base64.getDecoder().decode(s);
        }
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return kf.generatePrivate(new PKCS8EncodedKeySpec(bytes));
    }


    private record KeyMaterial(byte[] privateKeyPem, byte[] certificatePem) { }
    private record SendTask(SocketChannel ch, KeyMaterial km) { }

    private static final class ConnState {
        final ByteBuffer in = ByteBuffer.allocate(1024);
        final StringBuilder sb = new StringBuilder(256);
        void reset() { sb.setLength(0); }
    }
}


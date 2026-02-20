import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

public class ConcurrentList {

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "custom";
        int workers = args.length > 1 ? Integer.parseInt(args[1]) : 2;
        long delayMs = args.length > 2 ? Long.parseLong(args[2]) : 300;

        String inPath = null;
        String outPath = null;
        boolean finalOnly = false;

        for (String a : args) {
            if (a.startsWith("--in="))  inPath  = a.substring("--in=".length());
            if (a.startsWith("--out=")) outPath = a.substring("--out=".length());
            if (a.equals("--final"))     finalOnly = true;
        }

        InputStream inStream = (inPath == null) ? System.in : new FileInputStream(inPath);
        OutputStream outStream = (outPath == null) ? System.out : new FileOutputStream(outPath, false);

        BufferedReader br = new BufferedReader(new InputStreamReader(inStream, StandardCharsets.UTF_8));
        PrintWriter out = new PrintWriter(new OutputStreamWriter(outStream, StandardCharsets.UTF_8), true);

        if (!finalOnly) {
            out.printf("Mode=%s, workers=%d, delayMs=%d%n", mode, workers, delayMs);
        }

        StepCounter steps = new StepCounter();

        try {
            if ("array".equalsIgnoreCase(mode)) {
                ArrayMode.run(workers, delayMs, steps, br, out, finalOnly);
            } else {
                CustomMode.run(workers, delayMs, steps, br, out, finalOnly);
            }
        } finally {
            out.flush();
            if (outPath != null) out.close();
            if (inPath != null) br.close();
        }
    }

    static class StepCounter {
        final AtomicLong attempts = new AtomicLong();
        final AtomicLong swaps = new AtomicLong();
    }

    static List<String> split80(String s) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < s.length(); i += 80) {
            parts.add(s.substring(i, Math.min(i + 80, s.length())));
        }
        return parts;
    }

    static void measureAndPrint(String mode, int workers, long delayMs, StepCounter steps, int seconds, PrintWriter out) throws InterruptedException {
        long a1 = steps.attempts.get();
        long s1 = steps.swaps.get();
        long t0 = System.nanoTime();
        TimeUnit.SECONDS.sleep(Math.max(1, seconds));
        long a2 = steps.attempts.get();
        long s2 = steps.swaps.get();
        long t1 = System.nanoTime();
        double dtSec = (t1 - t0) / 1_000_000_000.0;
        long dA = a2 - a1;
        long dS = s2 - s1;
        double theo = workers * (dtSec * 1000.0) / (2.0 * Math.max(1L, delayMs));
        double rate = dA / Math.max(1e-9, dtSec);
        out.println("--- MEASURE (" + mode + ") ---");
        out.printf("interval=%.2fs, workers=%d, delayMs=%d%n", dtSec, workers, delayMs);
        out.printf("attempts: dA=%d (%.2f /s), theory≈%.1f%n", dA, rate, theo);
        out.printf("swaps   : dS=%d%n", dS);
        out.println("--- END MEASURE ---");
        out.flush();
    }


    static boolean isSorted(List<String> snap) {
        for (int i = 0; i + 1 < snap.size(); i++) {
            if (snap.get(i).compareTo(snap.get(i + 1)) > 0) return false;
        }
        return true;
    }

    interface SnapSupplier { List<String> get(); }

    static List<String> waitUntilSorted(SnapSupplier supplier, StepCounter steps, long delayMs) throws InterruptedException {
        long pollMs = Math.max(10, delayMs > 0 ? Math.min(200, delayMs / 3) : 20);
        long lastSwaps = steps.swaps.get();
        int stableTicks = 0;
        while (true) {
            TimeUnit.MILLISECONDS.sleep(pollMs);
            List<String> snap = supplier.get();
            boolean sorted = isSorted(snap);
            long curSwaps = steps.swaps.get();
            boolean swapsStable = (curSwaps == lastSwaps);
            if (sorted && swapsStable) {
                stableTicks++;
                if (stableTicks >= 3) return snap; // три подряд подтверждения
            } else {
                stableTicks = 0;
            }
            lastSwaps = curSwaps;
        }
    }


    static class LockList implements Iterable<String> {
        static class Node {
            String v;
            Node next;
            final ReentrantLock lock = new ReentrantLock();
            Node(String v) { this.v = v; }
        }
        private final Node head = new Node(null);

        public void addFirst(String v) {
            Node n = new Node(v);
            head.lock.lock();
            try {
                n.next = head.next;
                head.next = n;
            } finally {
                head.lock.unlock();
            }
        }

        @Override public Iterator<String> iterator() {
            List<String> snapshot = new ArrayList<>();
            Node cur;
            head.lock.lock();
            try { cur = head.next; } finally { head.lock.unlock(); }
            while (cur != null) {
                cur.lock.lock();
                Node next;
                try {
                    snapshot.add(cur.v);
                    next = cur.next;
                } finally {
                    cur.lock.unlock();
                }
                cur = next;
            }
            return snapshot.iterator();
        }

        Node bubbleStep(Node prev, StepCounter counter, long delayMs) throws InterruptedException {
            prev.lock.lock();
            Node a;
            try { a = prev.next; } finally {}
            if (a == null) { prev.lock.unlock(); return prev; }
            a.lock.lock();
            Node b;
            try { b = a.next; } finally {}
            if (b == null) {
                a.lock.unlock();
                prev.lock.unlock();
                return a;
            }
            b.lock.lock();
            try {
                TimeUnit.MILLISECONDS.sleep(delayMs);
                counter.attempts.incrementAndGet();
                if (a.v.compareTo(b.v) > 0) {
                    Node next = b.next;
                    prev.next = b;
                    b.next = a;
                    a.next = next;
                    counter.swaps.incrementAndGet();
                    return b;
                } else {
                    return a;
                }
            } finally {
                b.lock.unlock();
                a.lock.unlock();
                prev.lock.unlock();
                TimeUnit.MILLISECONDS.sleep(delayMs);
            }
        }

        void bubblePass(StepCounter counter, long delayMs) throws InterruptedException {
            Node prev = head;
            while (true) {
                Node nextPrev = bubbleStep(prev, counter, delayMs);
                if (nextPrev == prev) return;
                prev = nextPrev;
                if (prev != null) {
                    prev.lock.lock();
                    try { if (prev.next == null) return; } finally { prev.lock.unlock(); }
                }
            }
        }
    }

    static class CustomMode {
        static void run(int workers, long delayMs, StepCounter steps,
                        BufferedReader br, PrintWriter out, boolean finalOnly) throws Exception {
            LockList list = new LockList();

            List<Thread> sorters = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                Thread t = new Thread(() -> {
                    try {
                        while (true) {
                            list.bubblePass(steps, delayMs);
                        }
                    } catch (InterruptedException ignored) { }
                }, "Sorter-Custom-" + i);
                t.setDaemon(true);
                t.start();
                sorters.add(t);
            }

            String line;
            while ((line = br.readLine()) != null) {
                if (finalOnly) {
                    if (!line.isEmpty() && !line.startsWith(":measure")) {
                        for (String chunk : split80(line)) {
                            list.addFirst(chunk);
                        }
                    }
                    continue;
                }

                if (line.startsWith(":measure")) {
                    String[] p = line.split("\\s+");
                    int sec = (p.length >= 2) ? Integer.parseInt(p[1]) : 5;
                    out.println("Measuring for " + sec + "s...");
                    out.flush();
                    measureAndPrint("custom", workers, delayMs, steps, sec, out);
                } else if (line.isEmpty()) {
                    out.println("--- LIST (custom) attempts=" + steps.attempts.get() +
                            " swaps=" + steps.swaps.get() + " ---");
                    for (String s : list) out.println(s);
                    out.println("--- END ---");
                    out.flush();
                } else {
                    for (String chunk : split80(line)) {
                        list.addFirst(chunk);
                    }
                }
            }

            if (finalOnly) {
                List<String> finalSnap = waitUntilSorted(
                        () -> { List<String> s = new ArrayList<>(); for (String x : list) s.add(x); return s; },
                        steps, delayMs);
                for (String s : finalSnap) out.println(s);
                out.flush();
            }
        }
    }


    static class ArrayMode {
        static void run(int workers, long delayMs, StepCounter steps,
                        BufferedReader br, PrintWriter out, boolean finalOnly) throws Exception {
            List<String> list = Collections.synchronizedList(new ArrayList<>());

            List<Thread> sorters = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                Thread t = new Thread(() -> {
                    try {
                        while (true) {
                            bubblePass(list, steps, delayMs);
                        }
                    } catch (InterruptedException ignored) { }
                }, "Sorter-Array-" + i);
                t.setDaemon(true);
                t.start();
                sorters.add(t);
            }

            String line;
            while ((line = br.readLine()) != null) {
                if (finalOnly) {
                    if (!line.isEmpty() && !line.startsWith(":measure")) {
                        for (String chunk : split80(line)) {
                            synchronized (list) { list.add(0, chunk); }
                        }
                    }
                    continue;
                }

                if (line.startsWith(":measure")) {
                    String[] p = line.split("\\s+");
                    int sec = (p.length >= 2) ? Integer.parseInt(p[1]) : 5;
                    out.println("Measuring for " + sec + "s...");
                    out.flush();
                    measureAndPrint("array", workers, delayMs, steps, sec, out);
                } else if (line.isEmpty()) {
                    List<String> snapshot;
                    synchronized (list) { snapshot = new ArrayList<>(list); }
                    out.println("--- LIST (array) attempts=" + steps.attempts.get() +
                            " swaps=" + steps.swaps.get() + " ---");
                    for (String s : snapshot) out.println(s);
                    out.println("--- END ---");
                    out.flush();
                } else {
                    for (String chunk : split80(line)) {
                        synchronized (list) { list.add(0, chunk); }
                    }
                }
            }

            if (finalOnly) {
                List<String> finalSnap = waitUntilSorted(
                        () -> { synchronized (list) { return new ArrayList<>(list); } },
                        steps, delayMs);
                for (String s : finalSnap) out.println(s);
                out.flush();
            }
        }

        static void bubblePass(List<String> list, StepCounter counter, long delayMs) throws InterruptedException {
            int n;
            synchronized (list) { n = list.size(); }
            if (n < 2) { TimeUnit.MILLISECONDS.sleep(delayMs); return; }
            for (int i = 0; i < n - 1; i++) {
                int j = i;
                TimeUnit.MILLISECONDS.sleep(delayMs);
                counter.attempts.incrementAndGet();
                boolean swapped = false;
                synchronized (list) {
                    if (j + 1 < list.size()) {
                        String a = list.get(j);
                        String b = list.get(j + 1);
                        if (a.compareTo(b) > 0) {
                            list.set(j, b);
                            list.set(j + 1, a);
                            swapped = true;
                        }
                    }
                }
                if (swapped) counter.swaps.incrementAndGet();
                TimeUnit.MILLISECONDS.sleep(delayMs);
            }
        }
    }
}

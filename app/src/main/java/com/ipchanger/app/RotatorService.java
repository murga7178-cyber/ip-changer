package com.ipchanger.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * फ़ोन के अंदर 127.0.0.1:8899 पर एक लोकल HTTP-proxy चलाता है। यह आगे चुने हुए देश की
 * एक चालू free proxy से जुड़ता है और हर कुछ मिनट में उसे अपने आप बदल देता है।
 * Drony / Postern / FreeProxy जैसे VPN-वाले apps में इस पते को डालने से चुने हुए apps
 * का IP बदलता रहता है।
 */
public class RotatorService extends Service {

    public static final int PORT = 8899;
    private static final String CHANNEL_ID = "ipchanger";
    private static final String LIST_BASE =
            "https://cdn.jsdelivr.net/gh/proxyscrape/free-proxy-list@main/proxies/countries/";

    public static volatile boolean running = false;
    public static volatile String status = "रुका हुआ";
    public static volatile String upstream = null;
    public static final List<String> history =
            Collections.synchronizedList(new LinkedList<String>());

    private volatile int gen = 0;
    private volatile int failures = 0;
    private volatile boolean forceRotate = false;
    private volatile boolean bindFailed = false;
    private ServerSocket server;
    private ExecutorService pool;
    private final Set<Socket> active =
            Collections.newSetFromMap(new ConcurrentHashMap<Socket, Boolean>());

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        final String cc = intent.getStringExtra("cc") == null ? "US" : intent.getStringExtra("cc");
        final int sec = intent.getIntExtra("sec", 180);
        startForeground(1, buildNotification());

        final int g = ++gen;
        running = true;
        failures = 0;
        forceRotate = false;
        if (pool == null || pool.isShutdown()) pool = Executors.newCachedThreadPool();

        new Thread(new Runnable() {
            @Override
            public void run() {
                rotateLoop(cc, sec, g);
            }
        }).start();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        gen++;
        upstream = null;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        closeActive();
        if (pool != null) pool.shutdownNow();
        if (!bindFailed) status = "रुका हुआ।";
        super.onDestroy();
    }

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "IP Changer", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(ch);
        }
        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setContentTitle("IP Changer चालू");
        b.setContentText("लोकल प्रॉक्सी 127.0.0.1:" + PORT);
        b.setSmallIcon(android.R.drawable.stat_sys_upload);
        b.setOngoing(true);
        return b.build();
    }

    private boolean alive(int g) {
        return running && g == gen;
    }

    // ------------------------------------------------------------ बदलने का loop

    private void rotateLoop(String cc, int sec, int g) {
        bindFailed = false;
        startServer();
        if (bindFailed) {
            running = false;
            stopSelf();
            return;
        }
        while (alive(g)) {
            status = "प्रॉक्सी ढूँढ रहा है (" + cc + ")...\nइसमें आधा मिनट तक लग सकता है।";
            String[] found = findWorkingProxy(cc, g);
            if (!alive(g)) break;
            if (found == null) {
                waitSeconds(g, 10, "कोई चालू प्रॉक्सी नहीं मिली, दोबारा कोशिश", false);
                continue;
            }
            upstream = found[0];
            failures = 0;
            forceRotate = false;
            closeActive(); // चल रहे connections बंद, ताकि apps नए IP से दोबारा जुड़ें
            addHistory(found[1] + "  (" + found[0] + ")");
            waitSeconds(g, sec, "चालू ✅  देश: " + cc
                    + "\nIP: " + found[1]
                    + "\nप्रॉक्सी: " + found[0]
                    + "\nलोकल पता: 127.0.0.1:" + PORT
                    + "\nअगला बदलाव", true);
        }
    }

    private void waitSeconds(int g, int sec, String label, boolean breakOnForce) {
        for (int i = sec; i > 0 && alive(g); i--) {
            if (breakOnForce && forceRotate) {
                status = "यह प्रॉक्सी चल नहीं रही, नई ढूँढ रहा है...";
                return;
            }
            status = label + ": " + (i / 60) + ":" + String.format(Locale.US, "%02d", i % 60);
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void addHistory(String s) {
        String t = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        history.add(0, t + " → " + s);
        while (history.size() > 8) history.remove(history.size() - 1);
    }

    private void closeActive() {
        for (Socket s : active) closeQuiet(s);
        active.clear();
    }

    private void noteFailure() {
        failures = failures + 1;
        if (failures >= 5) forceRotate = true;
    }

    // ------------------------------------------------------------ लोकल proxy server

    private void startServer() {
        if (server != null && !server.isClosed()) return;
        try {
            ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 50);
            server = ss;
        } catch (IOException e) {
            server = null;
            bindFailed = true;
            status = "पोर्ट " + PORT + " नहीं खुल पाया: " + e.getMessage();
            return;
        }
        final ServerSocket ss2 = server;
        final ExecutorService p = pool;
        new Thread(new Runnable() {
            @Override
            public void run() {
                while (!ss2.isClosed()) {
                    try {
                        final Socket c = ss2.accept();
                        p.execute(new Runnable() {
                            @Override
                            public void run() {
                                handle(c);
                            }
                        });
                    } catch (IOException e) {
                        break;
                    } catch (RejectedExecutionException e) {
                        break;
                    }
                }
            }
        }).start();
    }

    private void handle(Socket client) {
        Socket up = null;
        boolean upstreamPhase = false;
        try {
            active.add(client);
            client.setTcpNoDelay(true);
            final BufferedInputStream cin = new BufferedInputStream(client.getInputStream(), 8192);
            OutputStream cout = client.getOutputStream();

            client.setSoTimeout(15000);
            byte[] head = readHead(cin);
            client.setSoTimeout(0);
            if (head == null) return;

            String hs = new String(head, "ISO-8859-1");
            int eol = hs.indexOf("\r\n");
            String[] parts = (eol > 0 ? hs.substring(0, eol) : hs).split(" ");
            if (parts.length < 2) return;

            String hp = upstream;
            if (hp == null) {
                cout.write("HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\n\r\n"
                        .getBytes("ISO-8859-1"));
                cout.flush();
                return;
            }
            String[] u = hp.split(":");

            upstreamPhase = true;
            up = new Socket();
            up.connect(new InetSocketAddress(u[0], Integer.parseInt(u[1])), 8000);
            up.setTcpNoDelay(true);
            active.add(up);
            BufferedInputStream uin = new BufferedInputStream(up.getInputStream(), 8192);
            final OutputStream uout = up.getOutputStream();

            if (parts[0].equalsIgnoreCase("CONNECT")) {
                String target = parts[1];
                uout.write(("CONNECT " + target + " HTTP/1.1\r\nHost: " + target + "\r\n\r\n")
                        .getBytes("ISO-8859-1"));
                uout.flush();
                up.setSoTimeout(10000);
                byte[] resp = readHead(uin);
                up.setSoTimeout(0);
                if (resp == null
                        || !new String(resp, "ISO-8859-1").matches("(?s)^HTTP/1\\.[01] 200.*")) {
                    noteFailure();
                    cout.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n"
                            .getBytes("ISO-8859-1"));
                    cout.flush();
                    return;
                }
                cout.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes("ISO-8859-1"));
                cout.flush();
            } else {
                uout.write(head);
                uout.flush();
            }
            upstreamPhase = false;
            failures = 0;

            final Socket upF = up;
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    pipe(cin, uout);
                    try {
                        upF.shutdownOutput();
                    } catch (Exception ignored) {
                    }
                }
            });
            t.start();
            pipe(uin, cout);
        } catch (Exception e) {
            if (upstreamPhase) noteFailure();
        } finally {
            closeQuiet(client);
            closeQuiet(up);
            active.remove(client);
            if (up != null) active.remove(up);
        }
    }

    private static byte[] readHead(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int state = 0;
        int b;
        while ((b = in.read()) != -1) {
            bos.write(b);
            if ((state == 0 || state == 2) && b == '\r') {
                state++;
            } else if ((state == 1 || state == 3) && b == '\n') {
                state++;
                if (state == 4) return bos.toByteArray();
            } else {
                state = (b == '\r') ? 1 : 0;
            }
            if (bos.size() > 32768) return null;
        }
        return null;
    }

    private static void pipe(InputStream in, OutputStream out) {
        byte[] buf = new byte[16384];
        int n;
        try {
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (IOException ignored) {
        }
    }

    private static void closeQuiet(Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------ चालू proxy ढूँढना

    private List<String> fetchList(String cc) {
        Set<String> set = new LinkedHashSet<>();
        for (String kind : new String[]{"https", "http"}) {
            try {
                String txt = httpGet(LIST_BASE + cc.toLowerCase(Locale.US) + "/" + kind + "/data.txt",
                        Proxy.NO_PROXY, 15000);
                if (txt == null) continue;
                for (String line : txt.split("\\r?\\n")) {
                    line = line.trim().replaceFirst("^[a-zA-Z0-9]+://", "");
                    if (line.matches("^\\d{1,3}(\\.\\d{1,3}){3}:\\d{2,5}$")) set.add(line);
                }
            } catch (Exception ignored) {
            }
        }
        return new ArrayList<>(set);
    }

    private String[] findWorkingProxy(String cc, int g) {
        List<String> list = fetchList(cc);
        if (list.isEmpty()) return null;
        Collections.shuffle(list);
        if (list.size() > 80) list = new ArrayList<>(list.subList(0, 80));
        ExecutorService testPool = Executors.newFixedThreadPool(16);
        try {
            CompletionService<String[]> cs = new ExecutorCompletionService<>(testPool);
            for (final String p : list) {
                cs.submit(new Callable<String[]>() {
                    @Override
                    public String[] call() {
                        return testProxy(p);
                    }
                });
            }
            for (int i = 0; i < list.size() && alive(g); i++) {
                String[] r = cs.take().get();
                if (r != null) return r;
            }
        } catch (Exception ignored) {
        } finally {
            testPool.shutdownNow();
        }
        return null;
    }

    private String[] testProxy(String hostPort) {
        try {
            String[] parts = hostPort.split(":");
            Proxy px = new Proxy(Proxy.Type.HTTP,
                    new InetSocketAddress(parts[0], Integer.parseInt(parts[1])));
            String body = httpGet("https://api.ipify.org?format=json", px, 7000);
            if (body != null) {
                Matcher m = Pattern.compile("\"ip\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
                if (m.find()) return new String[]{hostPort, m.group(1)};
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String httpGet(String u, Proxy proxy, int timeoutMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection(proxy);
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setRequestProperty("User-Agent", "Mozilla/5.0");
        try {
            if (c.getResponseCode() != 200) return null;
            BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            br.close();
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }
}

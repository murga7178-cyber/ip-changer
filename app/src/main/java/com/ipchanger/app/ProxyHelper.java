package YOUR.PACKAGE.NAME; // <-- इसे अपने app की MainActivity.java की पहली लाइन वाले package से बदलें

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;

import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * अपने किसी भी WebView app में जोड़ने के लिए: चुने हुए देश की चालू proxy ढूँढता है,
 * उसे app के सारे WebView पर लगाता है, और हर 5/10 मिनट में बदलता रहता है।
 */
public class ProxyHelper {

    /** जो चाहिए वही method override करें, बाकी खाली रहेंगे। सब UI thread पर चलते हैं। */
    public static class Listener {
        public void onStatus(String text) {}
        public void onProxyApplied(String ip, String hostPort) {}
        public void onProxyCleared() {}
    }

    private static final String[] COUNTRY_NAMES = {
            "USA", "UK", "India", "Canada", "Germany",
            "France", "Australia", "Netherlands", "Singapore", "Japan", "Brazil"
    };
    private static final String[] COUNTRY_CODES = {
            "US", "GB", "IN", "CA", "DE", "FR", "AU", "NL", "SG", "JP", "BR"
    };
    private static final String[] INTERVAL_NAMES = {"5 मिनट", "10 मिनट", "1 मिनट (टेस्ट)"};
    private static final int[] INTERVAL_SECS = {300, 600, 60};

    private static final String LIST_BASE =
            "https://cdn.jsdelivr.net/gh/proxyscrape/free-proxy-list@main/proxies/countries/";

    private final Activity act;
    private final Listener listener;
    private volatile boolean running = false;
    private volatile int gen = 0;
    private int selCountry = 0;
    private int selInterval = 0;

    public ProxyHelper(Activity activity, Listener l) {
        this.act = activity;
        this.listener = (l != null) ? l : new Listener();
    }

    public boolean isSupported() {
        return WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE);
    }

    /** देश चुनने और समय चुनने वाली छोटी window खोलता है (शुरू / बंद करें / रद्द)। */
    public void showDialog() {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * act.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);

        final Spinner countrySp = new Spinner(act);
        countrySp.setAdapter(new ArrayAdapter<String>(act,
                android.R.layout.simple_spinner_dropdown_item, COUNTRY_NAMES));
        countrySp.setSelection(selCountry);
        final Spinner intervalSp = new Spinner(act);
        intervalSp.setAdapter(new ArrayAdapter<String>(act,
                android.R.layout.simple_spinner_dropdown_item, INTERVAL_NAMES));
        intervalSp.setSelection(selInterval);
        box.addView(countrySp);
        box.addView(intervalSp);

        new AlertDialog.Builder(act)
                .setTitle("IP बदलें (प्रॉक्सी)")
                .setView(box)
                .setPositiveButton("शुरू", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        selCountry = countrySp.getSelectedItemPosition();
                        selInterval = intervalSp.getSelectedItemPosition();
                        start(COUNTRY_CODES[selCountry], INTERVAL_SECS[selInterval]);
                    }
                })
                .setNeutralButton("बंद करें", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        stop();
                    }
                })
                .setNegativeButton("रद्द", null)
                .show();
    }

    /** देश का code (US, GB, IN...) और बदलने का समय सेकंड में (300 = 5 मिनट)। */
    public void start(final String cc, final int sec) {
        if (!isSupported()) {
            status("इस फ़ोन का WebView प्रॉक्सी सपोर्ट नहीं करता। 'Android System WebView' अपडेट करें।");
            return;
        }
        final int g = ++gen;
        running = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                while (alive(g)) {
                    status("प्रॉक्सी ढूँढ रहा है (" + cc + ")...");
                    String[] found = findWorkingProxy(cc, g);
                    if (!alive(g)) break;
                    if (found == null) {
                        waitSeconds(g, 10, "कोई चालू प्रॉक्सी नहीं मिली, दोबारा कोशिश");
                        continue;
                    }
                    applyProxy(found[0], found[1]);
                    waitSeconds(g, sec, "चालू देश: " + cc + " | IP: " + found[1] + " | अगला बदलाव");
                }
            }
        }).start();
    }

    /** proxy बंद करके सीधा इंटरनेट (असली IP) वापस लाता है। */
    public void stop() {
        running = false;
        gen++;
        if (isSupported()) {
            ProxyController.getInstance().clearProxyOverride(Runnable::run, new Runnable() {
                @Override
                public void run() {
                    act.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            listener.onProxyCleared();
                        }
                    });
                }
            });
        }
        status("बंद। सीधा इंटरनेट चल रहा है।");
    }

    // ---------------------------------------------------------------- अंदर का काम

    private boolean alive(int g) {
        return running && g == gen;
    }

    private void status(final String s) {
        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                listener.onStatus(s);
            }
        });
    }

    private void waitSeconds(int g, int sec, String label) {
        for (int i = sec; i > 0 && alive(g); i--) {
            status(label + ": " + (i / 60) + ":" + String.format(Locale.US, "%02d", i % 60));
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void applyProxy(final String hostPort, final String ip) {
        // Unity के विज्ञापन सीधे (बिना proxy) चलें, ताकि वे बंद न हों
        ProxyConfig cfg = new ProxyConfig.Builder()
                .addProxyRule(hostPort)
                .addBypassRule("*.unity3d.com")
                .addBypassRule("*.unity.com")
                .build();
        ProxyController.getInstance().setProxyOverride(cfg, Runnable::run, new Runnable() {
            @Override
            public void run() {
                act.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        listener.onProxyApplied(ip, hostPort);
                    }
                });
            }
        });
    }

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
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            CompletionService<String[]> cs = new ExecutorCompletionService<>(pool);
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
            pool.shutdownNow();
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

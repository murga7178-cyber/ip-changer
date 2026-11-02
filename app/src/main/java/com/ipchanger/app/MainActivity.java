package com.ipchanger.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
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
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private static final String[] COUNTRY_NAMES = {
            "🇺🇸 USA", "🇬🇧 UK", "🇮🇳 India", "🇨🇦 Canada", "🇩🇪 Germany",
            "🇫🇷 France", "🇦🇺 Australia", "🇳🇱 Netherlands", "🇸🇬 Singapore",
            "🇯🇵 Japan", "🇧🇷 Brazil"
    };
    private static final String[] COUNTRY_CODES = {
            "US", "GB", "IN", "CA", "DE", "FR", "AU", "NL", "SG", "JP", "BR"
    };
    private static final String[] INTERVAL_NAMES = {"5 मिनट", "10 मिनट", "1 मिनट (टेस्ट)"};
    private static final int[] INTERVAL_SECS = {300, 600, 60};

    private static final String LIST_BASE =
            "https://cdn.jsdelivr.net/gh/proxyscrape/free-proxy-list@main/proxies/countries/";

    private volatile boolean running = false;
    private volatile int gen = 0;

    private WebView web;
    private TextView statusView;
    private TextView historyView;
    private Spinner countrySp;
    private Spinner intervalSp;
    private EditText urlBox;
    private final LinkedList<String> history = new LinkedList<>();
    private LinearLayout chipRow;
    private final List<String[]> sites = new ArrayList<>();

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#F4F4F4"));
        root.setPadding(dp(10), dp(10), dp(10), dp(6));

        // Row 1: country + interval
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        countrySp = new Spinner(this);
        countrySp.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, COUNTRY_NAMES));
        intervalSp = new Spinner(this);
        intervalSp.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, INTERVAL_NAMES));
        row1.addView(countrySp, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row1.addView(intervalSp, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(row1);

        // Row 2: start / stop
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        Button startBtn = new Button(this);
        startBtn.setText("▶ शुरू करें");
        startBtn.setTextColor(Color.WHITE);
        startBtn.setBackgroundColor(Color.parseColor("#2563EB"));
        Button stopBtn = new Button(this);
        stopBtn.setText("⏹ बंद करें");
        stopBtn.setTextColor(Color.WHITE);
        stopBtn.setBackgroundColor(Color.parseColor("#DC2626"));
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp1.setMargins(0, 0, dp(4), 0);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp2.setMargins(dp(4), 0, 0, 0);
        row2.addView(startBtn, lp1);
        row2.addView(stopBtn, lp2);
        root.addView(row2);

        // Status
        statusView = new TextView(this);
        statusView.setText("रुका हुआ। देश और समय चुनकर 'शुरू करें' दबाएँ।");
        statusView.setTextColor(Color.parseColor("#00FF66"));
        statusView.setBackgroundColor(Color.parseColor("#111111"));
        statusView.setPadding(dp(10), dp(8), dp(10), dp(8));
        statusView.setTextSize(13);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sp.setMargins(0, dp(6), 0, dp(4));
        root.addView(statusView, sp);

        historyView = new TextView(this);
        historyView.setTextSize(11);
        historyView.setTextColor(Color.parseColor("#555555"));
        root.addView(historyView);

        // URL row
        LinearLayout row3 = new LinearLayout(this);
        row3.setOrientation(LinearLayout.HORIZONTAL);
        urlBox = new EditText(this);
        urlBox.setSingleLine(true);
        urlBox.setText("https://api.ipify.org");
        urlBox.setTextSize(14);
        Button goBtn = new Button(this);
        goBtn.setText("Go");
        row3.addView(urlBox, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row3.addView(goBtn, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(row3);

        // Websites row: [+] [saved sites...]
        LinearLayout row4 = new LinearLayout(this);
        row4.setOrientation(LinearLayout.HORIZONTAL);
        Button plusBtn = new Button(this);
        plusBtn.setText("+");
        plusBtn.setTextSize(20);
        plusBtn.setTextColor(Color.WHITE);
        plusBtn.setBackgroundColor(Color.parseColor("#16A34A"));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        hs.addView(chipRow);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(56), LinearLayout.LayoutParams.WRAP_CONTENT);
        plp.setMargins(0, 0, dp(6), 0);
        row4.addView(plusBtn, plp);
        row4.addView(hs, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(row4);

        // WebView
        web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient());
        root.addView(web, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        plusBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAddDialog();
            }
        });
        loadSites();
        refreshChips();

        startBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startClicked();
            }
        });
        stopBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopClicked();
            }
        });
        goBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String u = urlBox.getText().toString().trim();
                if (u.isEmpty()) return;
                if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://" + u;
                web.loadUrl(u);
            }
        });

        web.loadUrl("https://api.ipify.org");
    }

    private void loadSites() {
        sites.clear();
        SharedPreferences sp = getSharedPreferences("ipchanger", MODE_PRIVATE);
        String raw = sp.getString("sites", null);
        if (raw == null) {
            sites.add(new String[]{"IP चेक", "https://api.ipify.org"});
            saveSites();
            return;
        }
        for (String line : raw.split("\n")) {
            String[] p = line.split("\t");
            if (p.length == 2) sites.add(p);
        }
    }

    private void saveSites() {
        StringBuilder sb = new StringBuilder();
        for (String[] s : sites) sb.append(s[0]).append('\t').append(s[1]).append('\n');
        getSharedPreferences("ipchanger", MODE_PRIVATE).edit().putString("sites", sb.toString()).apply();
    }

    private void refreshChips() {
        chipRow.removeAllViews();
        for (final String[] s : sites) {
            Button b = new Button(this);
            b.setText(s[0]);
            b.setAllCaps(false);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openSite(s[1]);
                }
            });
            b.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    confirmDelete(s);
                    return true;
                }
            });
            chipRow.addView(b);
        }
    }

    private void openSite(String u) {
        urlBox.setText(u);
        web.loadUrl(u);
    }

    private void showAddDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(10), dp(20), 0);
        final EditText nameEt = new EditText(this);
        nameEt.setHint("नाम (जैसे Facebook)");
        nameEt.setSingleLine(true);
        final EditText urlEt = new EditText(this);
        urlEt.setHint("वेबसाइट (जैसे m.facebook.com)");
        urlEt.setSingleLine(true);
        box.addView(nameEt);
        box.addView(urlEt);
        new AlertDialog.Builder(this)
                .setTitle("वेबसाइट जोड़ें")
                .setView(box)
                .setPositiveButton("जोड़ें", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        String name = nameEt.getText().toString().replace("\t", " ").replace("\n", " ").trim();
                        String u = urlEt.getText().toString().replace("\t", "").replace("\n", "").trim();
                        if (u.isEmpty()) return;
                        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://" + u;
                        if (name.isEmpty()) name = u.replaceFirst("^https?://", "");
                        sites.add(new String[]{name, u});
                        saveSites();
                        refreshChips();
                        openSite(u);
                    }
                })
                .setNegativeButton("रद्द", null)
                .show();
    }

    private void confirmDelete(final String[] s) {
        new AlertDialog.Builder(this)
                .setMessage("'" + s[0] + "' हटाएँ?")
                .setPositiveButton("हटाएँ", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        sites.remove(s);
                        saveSites();
                        refreshChips();
                    }
                })
                .setNegativeButton("रद्द", null)
                .show();
    }

    private void startClicked() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            setStatus("इस फ़ोन का WebView प्रॉक्सी सपोर्ट नहीं करता।\n'Android System WebView' को Play Store से अपडेट करें।");
            return;
        }
        String cc = COUNTRY_CODES[countrySp.getSelectedItemPosition()];
        int sec = INTERVAL_SECS[intervalSp.getSelectedItemPosition()];
        startLoop(cc, sec);
    }

    private void stopClicked() {
        running = false;
        gen++;
        if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            ProxyController.getInstance().clearProxyOverride(Runnable::run, new Runnable() {
                @Override
                public void run() {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            web.reload();
                        }
                    });
                }
            });
        }
        setStatus("बंद। अब ऐप के अंदर सीधा इंटरनेट (असली IP) चल रहा है।");
    }

    private boolean alive(int g) {
        return running && g == gen;
    }

    private void startLoop(final String cc, final int sec) {
        final int g = ++gen;
        running = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                while (alive(g)) {
                    setStatus("प्रॉक्सी ढूँढ रहा है (" + cc + ")...\nइसमें आधा मिनट तक लग सकता है।");
                    String[] found = findWorkingProxy(cc, g);
                    if (!alive(g)) break;
                    if (found == null) {
                        waitSeconds(g, 10, "कोई चालू प्रॉक्सी नहीं मिली।\nदोबारा कोशिश");
                        continue;
                    }
                    applyProxy(found[0]);
                    addHistory(found[1] + "  (" + found[0] + ")");
                    waitSeconds(g, sec, "चालू ✅  देश: " + cc
                            + "\nIP: " + found[1]
                            + "\nप्रॉक्सी: " + found[0]
                            + "\nअगला बदलाव");
                }
            }
        }).start();
    }

    private void waitSeconds(int g, int sec, String label) {
        for (int i = sec; i > 0 && alive(g); i--) {
            setStatus(label + ": " + (i / 60) + ":" + String.format(Locale.US, "%02d", i % 60));
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void applyProxy(String hostPort) {
        ProxyConfig cfg = new ProxyConfig.Builder().addProxyRule(hostPort).build();
        ProxyController.getInstance().setProxyOverride(cfg, Runnable::run, new Runnable() {
            @Override
            public void run() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        web.reload();
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
                cs.submit(new java.util.concurrent.Callable<String[]>() {
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

    private void setStatus(final String s) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                statusView.setText(s);
            }
        });
    }

    private void addHistory(final String s) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                String t = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
                history.addFirst(t + " → " + s);
                while (history.size() > 5) history.removeLast();
                StringBuilder sb = new StringBuilder("पिछले IP:\n");
                for (String h : history) sb.append(h).append('\n');
                historyView.setText(sb.toString());
            }
        });
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        running = false;
        gen++;
        super.onDestroy();
    }
}

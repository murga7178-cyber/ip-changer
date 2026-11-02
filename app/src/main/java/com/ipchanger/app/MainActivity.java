package com.ipchanger.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final String[] COUNTRY_NAMES = {
            "🇺🇸 USA", "🇬🇧 UK", "🇮🇳 India", "🇨🇦 Canada", "🇩🇪 Germany",
            "🇫🇷 France", "🇦🇺 Australia", "🇳🇱 Netherlands", "🇸🇬 Singapore",
            "🇯🇵 Japan", "🇧🇷 Brazil"
    };
    private static final String[] COUNTRY_CODES = {
            "US", "GB", "IN", "CA", "DE", "FR", "AU", "NL", "SG", "JP", "BR"
    };
    private static final String[] INTERVAL_NAMES = {
            "2 मिनट", "3 मिनट", "5 मिनट", "10 मिनट", "1 मिनट (टेस्ट)"
    };
    private static final int[] INTERVAL_SECS = {120, 180, 300, 600, 60};

    private static final String LOCAL_ADDR = "127.0.0.1:" + RotatorService.PORT;

    private TextView statusView;
    private TextView historyView;
    private Spinner countrySp;
    private Spinner intervalSp;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refreshUi();
            handler.postDelayed(this, 1000);
        }
    };

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.parseColor("#F4F4F4"));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(14), dp(14), dp(20));
        sv.addView(root);

        TextView title = new TextView(this);
        title.setText("🌍 Auto IP Changer");
        title.setTextSize(20);
        title.setTextColor(Color.parseColor("#111111"));
        root.addView(title);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        countrySp = new Spinner(this);
        countrySp.setAdapter(new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_dropdown_item, COUNTRY_NAMES));
        intervalSp = new Spinner(this);
        intervalSp.setAdapter(new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_dropdown_item, INTERVAL_NAMES));
        intervalSp.setSelection(1);
        row1.addView(countrySp, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row1.addView(intervalSp, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(row1);

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

        statusView = new TextView(this);
        statusView.setText(RotatorService.status);
        statusView.setTextColor(Color.parseColor("#00FF66"));
        statusView.setBackgroundColor(Color.parseColor("#111111"));
        statusView.setPadding(dp(10), dp(8), dp(10), dp(8));
        statusView.setTextSize(13);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sp.setMargins(0, dp(8), 0, dp(8));
        root.addView(statusView, sp);

        LinearLayout row3 = new LinearLayout(this);
        row3.setOrientation(LinearLayout.HORIZONTAL);
        TextView addr = new TextView(this);
        addr.setText("लोकल प्रॉक्सी पता:\n" + LOCAL_ADDR);
        addr.setTextColor(Color.parseColor("#111111"));
        Button copyBtn = new Button(this);
        copyBtn.setText("कॉपी करें");
        row3.addView(addr, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row3.addView(copyBtn, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(row3);

        historyView = new TextView(this);
        historyView.setTextSize(11);
        historyView.setTextColor(Color.parseColor("#555555"));
        historyView.setPadding(0, dp(8), 0, dp(8));
        root.addView(historyView);

        TextView help = new TextView(this);
        help.setTextSize(13);
        help.setTextColor(Color.parseColor("#333333"));
        help.setText("कैसे इस्तेमाल करें:\n\n"
                + "1. ऊपर देश और समय चुनकर 'शुरू करें' दबाएँ। status में चालू IP दिखेगा।\n\n"
                + "2. FreeProxy (या Drony / Postern) ऐप खोलें और एक नई proxy profile बनाएँ: "
                + "प्रकार HTTP (CONNECT), host 127.0.0.1, port " + RotatorService.PORT + "।\n\n"
                + "3. उस ऐप में per-app routing से वे apps चुनें जिनका IP बदलना है, और Connect दबाएँ।\n\n"
                + "4. IP हर चुने हुए समय पर अपने आप बदलता रहेगा। बदलते समय चल रहे connections "
                + "एक पल के लिए टूटते हैं और apps नए IP से दोबारा जुड़ते हैं।\n\n"
                + "5. बैटरी सेटिंग में इस ऐप को 'Unrestricted' कर दें, वरना फ़ोन इसे बंद कर सकता है।\n\n"
                + "6. बंद करने के लिए यहाँ 'बंद करें' और VPN ऐप में Disconnect दबाएँ।");
        root.addView(help);

        setContentView(sv);

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
        copyBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("proxy", LOCAL_ADDR));
                Toast.makeText(MainActivity.this, "कॉपी हो गया: " + LOCAL_ADDR, Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(ticker);
        super.onPause();
    }

    private void startClicked() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }
        Intent i = new Intent(this, RotatorService.class);
        i.putExtra("cc", COUNTRY_CODES[countrySp.getSelectedItemPosition()]);
        i.putExtra("sec", INTERVAL_SECS[intervalSp.getSelectedItemPosition()]);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(i);
        } else {
            startService(i);
        }
    }

    private void stopClicked() {
        stopService(new Intent(this, RotatorService.class));
    }

    private void refreshUi() {
        statusView.setText(RotatorService.status);
        List<String> h;
        synchronized (RotatorService.history) {
            h = new ArrayList<>(RotatorService.history);
        }
        StringBuilder sb = new StringBuilder();
        if (!h.isEmpty()) sb.append("पिछले IP:\n");
        for (String x : h) sb.append(x).append('\n');
        historyView.setText(sb.toString());
    }
}

package com.radxa.monitor;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

public class MainActivity extends Activity {

    private static final String PREF = "radxa_pref";
    private static final String MARKER = "radxa-cubie-a7a";
    private static final String SERVICE_TYPE = "_http._tcp.";

    private WebView web;
    private TextView status;
    private TextView device;
    private TextView sshOut;
    private EditText sshUser;
    private EditText sshPass;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private String ip = "192.168.10.165";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SharedPreferences sp = getSharedPreferences(PREF, MODE_PRIVATE);
        ip = sp.getString("ip", "192.168.10.165");

        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (12 * d);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(0xFF0F1420);

        TextView title = new TextView(this);
        title.setText("\uD83D\uDFE2 Radxa Monitor");
        title.setTextColor(0xFFE6ECF5);
        title.setTextSize(18);
        title.setPadding(0, 0, 0, pad / 2);
        root.addView(title);

        device = new TextView(this);
        device.setText("正在通过 mDNS 发现设备…");
        device.setTextColor(0xFF60A5FA);
        device.setTextSize(12);
        device.setPadding(0, 0, 0, pad / 2);
        root.addView(device);

        status = new TextView(this);
        status.setText("等待设备…");
        status.setTextColor(0xFF8B98B0);
        status.setTextSize(13);
        status.setPadding(0, 0, 0, pad / 2);
        root.addView(status);

        web = new WebView(this);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient());
        web.setBackgroundColor(Color.parseColor("#0F1420"));
        root.addView(web, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(mkBtn("↻ 刷新", new View.OnClickListener() {
            public void onClick(View v) { loadPage(); }
        }));
        row.addView(mkBtn("📡 扫描", new View.OnClickListener() {
            public void onClick(View v) { autoDiscover(true); }
        }));
        row.addView(mkBtn("📲 导入Clash", new View.OnClickListener() {
            public void onClick(View v) { importClash(); }
        }));
        row.addView(mkBtn("📋 复制订阅", new View.OnClickListener() {
            public void onClick(View v) { copySub(); }
        }));
        root.addView(row);

        LinearLayout sshRow = new LinearLayout(this);
        sshRow.setOrientation(LinearLayout.HORIZONTAL);
        sshUser = mkEdit(sp.getString("sshUser", "radxa"), false);
        sshPass = mkEdit(sp.getString("sshPass", "radxa"), true);
        sshRow.addView(sshUser, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        sshRow.addView(sshPass, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        sshRow.addView(mkBtn("🔌 SSH快检", new View.OnClickListener() {
            public void onClick(View v) { sshCheck(); }
        }));
        root.addView(sshRow);

        sshOut = new TextView(this);
        sshOut.setText("");
        sshOut.setTextColor(0xFF4ADE80);
        sshOut.setTextSize(11);
        sshOut.setTypeface(android.graphics.Typeface.MONOSPACE);
        sshOut.setVisibility(View.GONE);
        root.addView(sshOut);

        setContentView(root);
        autoDiscover(false);
    }

    private Button mkBtn(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(12);
        b.setOnClickListener(l);
        return b;
    }

    private EditText mkEdit(String text, boolean password) {
        EditText e = new EditText(this);
        e.setText(text);
        e.setTextSize(12);
        e.setTextColor(0xFFE6ECF5);
        e.setHint(password ? "SSH密码" : "SSH用户");
        e.setHintTextColor(0xFF5B6780);
        e.setInputType(password
                ? (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD)
                : InputType.TYPE_CLASS_TEXT);
        e.setSingleLine(true);
        return e;
    }

    // ---------- discovery: mDNS -> saved IP -> TCP scan ----------

    private void autoDiscover(final boolean forceRescan) {
        ui.post(new Runnable() { public void run() {
            device.setText(forceRescan ? "重新发现中 (mDNS→扫描)…" : "mDNS 发现中…");
            status.setText("等待设备…");
            status.setTextColor(0xFF8B98B0);
        }});
        new Thread(new Runnable() { public void run() {
            final String viaNsd = nsdDiscover(4500);
            if (viaNsd != null) { found(viaNsd, "mDNS"); return; }
            if (!forceRescan && probe(ip)) { found(ip, "缓存IP"); return; }
            final String viaScan = scanSubnet();
            if (viaScan != null) { found(viaScan, "TCP扫描"); return; }
            ui.post(new Runnable() { public void run() {
                device.setText("未发现设备，使用 " + ip);
                status.setText("局域网内未找到 radxa 状态页\n请确认手机与板子同一 WiFi");
                status.setTextColor(0xFFF87171);
            }});
            loadPage();
        }}).start();
    }

    private String nsdDiscover(long timeoutMs) {
        final NsdManager nsd = (NsdManager) getSystemService(Context.NSD_SERVICE);
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> hit = new AtomicReference<String>(null);
        final AtomicReference<NsdManager.DiscoveryListener> dlRef =
                new AtomicReference<NsdManager.DiscoveryListener>(null);
        NsdManager.DiscoveryListener dl = new NsdManager.DiscoveryListener() {
            public void onDiscoveryStarted(String t) { }
            public void onDiscoveryStopped(String t) { }
            public void onStartDiscoveryFailed(String t, int e) { latch.countDown(); }
            public void onStopDiscoveryFailed(String t, int e) { }
            public void onServiceLost(NsdServiceInfo s) { }
            public void onServiceFound(final NsdServiceInfo si) {
                if (!SERVICE_TYPE.equals(si.getServiceType())) return;
                nsd.resolveService(si, new NsdManager.ResolveListener() {
                    public void onResolveFailed(NsdServiceInfo i, int e) { }
                    public void onServiceResolved(NsdServiceInfo info) {
                        if (hit.get() != null) return;
                        String name = info.getServiceName();
                        String app = "";
                        Map<String, byte[]> attrs = info.getAttributes();
                        if (attrs != null && attrs.get("app") != null) {
                            app = new String(attrs.get("app"));
                        }
                        if (!app.equals("radxa-monitor")
                                && (name == null || !name.toLowerCase().contains("radxa"))) {
                            return;
                        }
                        if (info.getHost() == null) return;
                        String addr = info.getHost().getHostAddress();
                        if (addr == null) return;
                        if (addr.contains(":")) { hit.compareAndSet(null, addr); return; }
                        hit.set(addr);
                        try {
                            NsdManager.DiscoveryListener cur = dlRef.get();
                            if (cur != null) nsd.stopServiceDiscovery(cur);
                        } catch (Exception ignore) { }
                        latch.countDown();
                    }
                });
            }
        };
        dlRef.set(dl);
        try { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, dl); }
        catch (Exception e) { return null; }
        try { latch.await(timeoutMs, TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { /* fallthrough */ }
        try {
            NsdManager.DiscoveryListener cur = dlRef.get();
            if (cur != null) nsd.stopServiceDiscovery(cur);
        } catch (Exception ignore) { }
        String r = hit.get();
        if (r != null && r.contains(":")) {
            // resolved IPv6 first; give the board's IPv4 a moment via probe map
            return r;
        }
        return r;
    }

    private void found(final String hit, final String via) {
        ip = hit;
        getSharedPreferences(PREF, MODE_PRIVATE).edit()
                .putString("ip", ip).apply();
        ui.post(new Runnable() { public void run() {
            device.setText("设备: " + ip + "  (发现方式: " + via + ")");
            loadPage();
        }});
        final String banner = sshBanner(ip);
        ui.post(new Runnable() { public void run() {
            if (banner != null) {
                device.setText(device.getText() + "  ·  SSH ✓ " + banner);
            } else {
                device.setText(device.getText() + "  ·  SSH ✗ 22端口不可达");
            }
        }});
    }

    private String sshBanner(String host) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, 22), 3000);
            s.setSoTimeout(3000);
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), "UTF-8"));
            String line = r.readLine();
            if (line != null && line.startsWith("SSH-")) {
                return line.substring(4).trim();
            }
            return line;
        } catch (Exception e) {
            return null;
        } finally {
            try { s.close(); } catch (Exception ignore) { }
        }
    }

    // ---------- SSH quick check (JSch password auth) ----------

    private void sshCheck() {
        final String host = ip;
        final String user = sshUser.getText().toString().trim();
        final String pass = sshPass.getText().toString();
        getSharedPreferences(PREF, MODE_PRIVATE).edit()
                .putString("sshUser", user).putString("sshPass", pass).apply();
        if (user.length() == 0 || pass.length() == 0) {
            Toast.makeText(this, "请填写 SSH 用户名/密码", Toast.LENGTH_SHORT).show();
            return;
        }
        ui.post(new Runnable() { public void run() {
            sshOut.setVisibility(View.VISIBLE);
            sshOut.setTextColor(0xFF8B98B0);
            sshOut.setText("SSH 连接 " + user + "@" + host + " …");
        }});
        new Thread(new Runnable() { public void run() {
            String result;
            try {
                result = runSsh(host, user, pass,
                        "hostname; echo ---; uptime; echo ---;"
                      + "systemctl is-active mihomo nginx avahi-daemon; echo ---;"
                      + "free -m | head -2; echo ---; df -h / | tail -1; echo ---;"
                      + "curl -x http://127.0.0.1:7890 -sS --max-time 8"
                      + " https://www.cloudflare.com/cdn-cgi/trace | grep -E '^(ip|loc|colo)='");
                showSsh(result, 0xFF4ADE80);
            } catch (final Exception e) {
                showSsh("SSH 失败: " + e.getMessage(), 0xFFF87171);
            }
        }}).start();
    }

    private void showSsh(final String text, final int color) {
        ui.post(new Runnable() { public void run() {
            sshOut.setVisibility(View.VISIBLE);
            sshOut.setTextColor(color);
            sshOut.setText(text);
        }});
    }

    private String runSsh(String host, String user, String pass, String cmd) throws Exception {
        JSch jsch = new JSch();
        Session session = jsch.getSession(user, host, 22);
        session.setPassword(pass);
        session.setConfig("StrictHostKeyChecking", "no");
        session.setConfig("PreferredAuthentications", "password,keyboard-interactive");
        session.connect(6000);
        ChannelExec channel = (ChannelExec) session.openChannel("exec");
        channel.setCommand(cmd);
        channel.setInputStream(null);
        InputStream in = channel.getInputStream();
        InputStream err = channel.getErrStream();
        channel.connect(8000);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        long deadline = System.currentTimeMillis() + 20000;
        while ((System.currentTimeMillis() < deadline) && (n = in.read(buf)) > 0) {
            bo.write(buf, 0, n);
        }
        while (err.available() > 0) bo.write(err.read());
        in.close();
        channel.disconnect();
        session.disconnect();
        return bo.toString("UTF-8").trim();
    }

    // ---------- web page / actions ----------

    private void loadPage() {
        ui.post(new Runnable() { public void run() {
            web.loadUrl("http://" + urlHost(ip) + "/");
            fetchStatus();
        }});
    }

    private String urlHost(String h) {
        return h.contains(":") ? "[" + h + "]" : h;
    }

    private boolean probe(String host) {
        try {
            String s = httpGet("http://" + urlHost(host) + "/api/status.json", 1500);
            return s.contains(MARKER);
        } catch (Exception e) {
            return false;
        }
    }

    private String scanSubnet() {
        final AtomicReference<String> found = new AtomicReference<String>(null);
        final AtomicBoolean stop = new AtomicBoolean(false);
        Thread[] ts = new Thread[254];
        for (int i = 1; i <= 254; i++) {
            final String host = "192.168.10." + i;
            Thread t = new Thread(new Runnable() { public void run() {
                if (stop.get()) return;
                if (probe(host) && found.compareAndSet(null, host)) stop.set(true);
            }});
            ts[i - 1] = t;
            t.start();
        }
        for (int i = 0; i < 254; i++) {
            try { ts[i].join(3000); } catch (InterruptedException e) { break; }
        }
        return found.get();
    }

    private void fetchStatus() {
        final String u = "http://" + urlHost(ip) + "/api/status.json?_=" + System.currentTimeMillis();
        new Thread(new Runnable() { public void run() {
            try {
                JSONObject o = new JSONObject(httpGet(u, 4000));
                final String line = "mihomo: " + o.optString("service")
                        + "   出口: " + o.optString("exit_loc") + "/" + o.optString("exit_colo")
                        + " (" + o.optString("exit_ip") + ")"
                        + "\nTTFB " + o.optInt("ttfb_ms") + " ms    ↓ "
                        + o.optDouble("down_mbps") + " Mbps    ↑ "
                        + o.optDouble("up_mbps") + " Mbps"
                        + "\n采集于 " + o.optString("updated");
                ui.post(new Runnable() { public void run() {
                    status.setText(line);
                    status.setTextColor(0xFF4ADE80);
                }});
            } catch (final Exception e) {
                ui.post(new Runnable() { public void run() {
                    status.setText("状态获取失败: " + e.getMessage());
                    status.setTextColor(0xFFF87171);
                }});
            }
        }}).start();
    }

    private void importClash() {
        try {
            String sub = "http://" + urlHost(ip) + "/sub/clash.yaml";
            String url = "clash://install-config?url=" + URLEncoder.encode(sub, "UTF-8");
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, "未找到可处理 clash:// 的应用", Toast.LENGTH_SHORT).show();
        }
    }

    private void copySub() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("sub",
                    "http://" + urlHost(ip) + "/sub/clash.yaml"));
            Toast.makeText(this, "订阅链接已复制", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "复制失败", Toast.LENGTH_SHORT).show();
        }
    }

    private String httpGet(String u, int timeoutMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setRequestProperty("Connection", "close");
        InputStream in = c.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return new String(bo.toByteArray(), "UTF-8");
    }
}

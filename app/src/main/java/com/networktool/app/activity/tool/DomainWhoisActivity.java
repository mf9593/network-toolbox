package com.networktool.app.activity.tool;

import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import com.networktool.app.R;
import com.networktool.app.util.AppErrorLogger;
import com.networktool.app.util.ThemeManager;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 域名查询工具Activity
 *
 * 功能说明：
 * - WHOIS信息查询（TCP 43端口）
 * - SSL证书信息查询（TLS握手）
 * - 支持.com/.cn等常见后缀
 * - 输出JSON格式查询结果
 */
public class DomainWhoisActivity extends AppCompatActivity {

    private static final String TAG = "DomainWhoisActivity";

    private MaterialToolbar toolbar;
    private MaterialCardView resultCard;
    private EditText domainEditText;
    private MaterialButton queryButton;
    private TextView resultTextView;

    private ExecutorService executorService;
    private Handler mainHandler;

    private static final int CONNECT_TIMEOUT = 10000;
    private static final int READ_TIMEOUT = 10000;

    // TLD -> WHOIS服务器映射
    private static final String[][] WHOIS_SERVERS = {
            {"com", "whois.verisign-grs.com"},
            {"net", "whois.verisign-grs.com"},
            {"org", "whois.pir.org"},
            {"cn", "whois.cnnic.cn"},
            {"info", "whois.afilias.net"},
            {"biz", "whois.neulevel.biz"},
            {"io", "whois.nic.io"},
            {"me", "whois.nic.me"},
            {"co", "whois.nic.co"},
            {"cc", "whois.nic.cc"},
            {"tv", "whois.nic.tv"},
            {"name", "whois.nic.name"},
            {"pro", "whois.nic.pro"},
            {"mobi", "whois.nic.mobi"},
            {"asia", "whois.nic.asia"},
            {"tel", "whois.nic.tel"},
            {"top", "whois.nic.top"},
            {"xyz", "whois.nic.xyz"},
            {"club", "whois.nic.club"},
            {"vip", "whois.nic.vip"},
            {"shop", "whois.nic.shop"},
            {"site", "whois.nic.site"},
            {"online", "whois.nic.online"},
            {"tech", "whois.nic.tech"},
            {"space", "whois.nic.space"},
            {"press", "whois.nic.press"},
            {"website", "whois.nic.website"},
            {"link", "whois.nic.link"},
            {"info", "whois.afilias.net"},
            {"int", "whois.iana.org"},
            {"edu", "whois.educause.edu"},
            {"gov", "whois.dotgov.gov"},
            {"mil", "whois.nic.mil"},
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_domain_whois);

        setupStatusBar();
        initViews();
        setupToolbar();
        setupListeners();

        executorService = Executors.newCachedThreadPool();
        mainHandler = new Handler(Looper.getMainLooper());
    }

    private void initViews() {
        toolbar = findViewById(R.id.toolbar);
        resultCard = findViewById(R.id.resultCard);
        domainEditText = findViewById(R.id.domainEditText);
        queryButton = findViewById(R.id.queryButton);
        resultTextView = findViewById(R.id.resultTextView);
    }

    private void setupToolbar() {
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("域名查询");
        }

        int themeColor = ThemeManager.getThemeColor();
        int contrastColor = ThemeManager.getContrastColor(themeColor);
        toolbar.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        toolbar.setTitleTextColor(ThemeManager.getOnSurfaceColor(this));
        toolbar.setNavigationIconTint(ThemeManager.getOnSurfaceColor(this));
    }

    private void setupStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(ThemeManager.getSurfaceBaseColor(this));
        }
    }

    private void setupListeners() {
        queryButton.setOnClickListener(v -> queryDomainInfo());
        applyThemeColors();
    }

    private void applyThemeColors() {
        int themeColor = ThemeManager.getThemeColor();
        ThemeManager.applyPrimaryButton(queryButton);
        ThemeManager.setupResultCard(resultCard, this);
    }

    private void queryDomainInfo() {
        String domain = domainEditText.getText().toString().trim();
        if (TextUtils.isEmpty(domain)) {
            Toast.makeText(this, "请输入域名", Toast.LENGTH_SHORT).show();
            return;
        }

        domain = domain.replace("http://", "").replace("https://", "");
        domain = domain.replace("/", "").replace("www.", "");

        if (!isValidDomain(domain)) {
            Toast.makeText(this, "请输入有效的域名", Toast.LENGTH_SHORT).show();
            return;
        }

        resultTextView.setText("正在查询域名信息...\n域名: " + domain);
        queryButton.setEnabled(false);

        final String targetDomain = domain;
        executorService.execute(() -> {
            try {
                JSONObject result = new JSONObject();
                result.put("domain", targetDomain);

                // 1. WHOIS查询
                WhoisClient whoisClient = new WhoisClient();
                JSONObject whoisResult = whoisClient.query(targetDomain);
                result.put("whois", whoisResult);

                // 2. SSL证书查询
                SslCertClient sslClient = new SslCertClient();
                JSONObject sslResult = sslClient.getCertInfo(targetDomain);
                result.put("ssl_cert", sslResult);

                String displayText = formatDisplayResult(result);
                mainHandler.post(() -> {
                    resultTextView.setText(displayText);
                    queryButton.setEnabled(true);
                });
            } catch (Exception e) {
                AppErrorLogger.logError(TAG, "查询失败: " + e.getMessage(), e);
                mainHandler.post(() -> {
                    resultTextView.setText("查询失败: " + e.getMessage());
                    queryButton.setEnabled(true);
                });
            }
        });
    }

    private boolean isValidDomain(String domain) {
        if (TextUtils.isEmpty(domain)) return false;
        return domain.matches("^[a-zA-Z0-9]([a-zA-Z0-9\\-]*[a-zA-Z0-9])?(\\.[a-zA-Z0-9]([a-zA-Z0-9\\-]*[a-zA-Z0-9])?)*\\.[a-zA-Z]{2,}$");
    }

    // ==================== WHOIS客户端 ====================

    private static class WhoisClient {

        public JSONObject query(String domain) throws Exception {
            JSONObject result = new JSONObject();
            String tld = domain.substring(domain.lastIndexOf('.') + 1);

            result.put("tld", "." + tld);

            // 第一步：查询初始WHOIS服务器
            String initialServer = getWhoisServer(tld);
            if (initialServer == null) {
                initialServer = "whois.iana.org";
            }

            result.put("initial_server", initialServer);

            String whoisText = queryWhoisServer(initialServer, domain, tld);

            // 第二步：检查是否有递归WHOIS服务器
            String referredServer = extractWhoisServer(whoisText);
            if (referredServer != null && !referredServer.equalsIgnoreCase(initialServer)) {
                result.put("referral_server", referredServer);
                String referredText = queryWhoisServer(referredServer, domain, tld);
                // 如果递归结果更丰富，使用递归结果
                if (referredText.length() > whoisText.length()) {
                    whoisText = referredText;
                }
            }

            // 解析关键信息（原始WHOIS文本包含大量换行/转义，不放入JSON）
            JSONObject parsed = parseWhoisInfo(whoisText, tld);
            result.put("parsed", parsed);

            return result;
        }

        private String queryWhoisServer(String server, String domain, String tld) throws Exception {
            Socket socket = null;
            try {
                socket = new Socket(server, 43);
                socket.setSoTimeout(READ_TIMEOUT);

                // .cn域名使用GBK编码
                Charset charset = "cn".equalsIgnoreCase(tld)
                        ? Charset.forName("GBK")
                        : StandardCharsets.UTF_8;

                Writer writer = new OutputStreamWriter(socket.getOutputStream(), charset);
                writer.write(domain + "\r\n");
                writer.flush();

                // 读取响应
                StringBuilder sb = new StringBuilder();
                byte[] buffer = new byte[4096];
                int read;
                ByteArrayOutputStream baos = new ByteArrayOutputStream();

                try {
                    java.io.InputStream input = socket.getInputStream();
                    while ((read = input.read(buffer)) != -1) {
                        baos.write(buffer, 0, read);
                    }
                } catch (SocketTimeoutException e) {
                    // 读取超时，部分数据已获取
                }

                sb.append(new String(baos.toByteArray(), charset));
                return sb.toString();

            } finally {
                if (socket != null) {
                    try { socket.close(); } catch (Exception ignored) {}
                }
            }
        }

        private String getWhoisServer(String tld) {
            for (String[] entry : WHOIS_SERVERS) {
                if (entry[0].equalsIgnoreCase(tld)) {
                    return entry[1];
                }
            }
            return null;
        }

        private String extractWhoisServer(String whoisText) {
            Pattern pattern = Pattern.compile("(?i)Whois\\s*Server:\\s*(\\S+)");
            Matcher matcher = pattern.matcher(whoisText);
            if (matcher.find()) {
                String server = matcher.group(1).trim();
                if (!server.isEmpty()) {
                    return server;
                }
            }
            return null;
        }

        private JSONObject parseWhoisInfo(String text, String tld) throws Exception {
            text = text.replace("\r", "").trim();
            JSONObject parsed = new JSONObject();

            if (TextUtils.isEmpty(text)) {
                parsed.put("status", "no_data");
                return parsed;
            }

            // 通用正则模式
            String[] patterns = {
                    "Registrar:\\s*(.+)",
                    "Registry Registrant ID:\\s*(.+)",
                    "Registrant Name:\\s*(.+)",
                    "Registrant Organization:\\s*(.+)",
                    "Registrant Email:\\s*(.+)",
                    "Admin Email:\\s*(.+)",
                    "Tech Email:\\s*(.+)",
                    "Name Server:\\s*(.+)",
                    "Creation Date:\\s*(.+)",
                    "Registry Expiry Date:\\s*(.+)",
                    "Expiration Date:\\s*(.+)",
                    "DNSSEC:\\s*(.+)",
                    "Domain Status:\\s*(.+)",
            };

            // .cn 域名特殊模式
            String[] cnPatterns = {
                    "注册商:\\s*(.+)",
                    "域名状态:\\s*(.+)",
                    "注册时间:\\s*(.+)",
                    "过期时间:\\s*(.+)",
                    "DNS服务器:\\s*(.+)",
            };

            if ("cn".equalsIgnoreCase(tld)) {
                for (String p : cnPatterns) {
                    Pattern pattern = Pattern.compile(p);
                    Matcher matcher = pattern.matcher(text);
                    if (matcher.find()) {
                        String value = matcher.group(1).trim();
                        String key = "cn_" + p.replaceAll("[\\\\:\\s\\(\\)]", "_");
                        parsed.put(key, value);
                    }
                }
            }

            for (String p : patterns) {
                Pattern pattern = Pattern.compile("(?i)" + p);
                Matcher matcher = pattern.matcher(text);
                List<String> values = new ArrayList<>();
                while (matcher.find()) {
                    values.add(matcher.group(1).trim());
                }
                if (!values.isEmpty()) {
                    String key = p.replaceAll("[\\\\:\\s\\(\\)]", "_").toLowerCase();
                    if (values.size() == 1) {
                        parsed.put(key, values.get(0));
                    } else {
                        parsed.put(key, new JSONArray(values));
                    }
                }
            }

            // 判断域名是否注册
            if (text.contains("No Data Found") || text.contains("No match for")
                    || text.contains("NOT FOUND") || text.contains("Domain not found")
                    || text.contains("no entries found") || text.contains("No entries found")) {
                parsed.put("status", "not_registered");
                parsed.put("message", "该域名尚未注册或已过期");
            } else {
                parsed.put("status", "registered");
            }

            // 将原始WHOIS文本放入结果（去除转义反斜杠）
            String rawText = text.replaceAll("\\\\/", "/");
            parsed.put("raw_whois_text", rawText);

            return parsed;
        }
    }

    // ==================== 结果格式化 ====================

    private String formatDisplayResult(JSONObject result) {
        try {
            StringBuilder sb = new StringBuilder();
            String domain = result.optString("domain", "");
            sb.append("域名: ").append(domain).append("\n\n");

            JSONObject whois = result.optJSONObject("whois");
            if (whois != null) {
                sb.append("── WHOIS 信息 ──\n");
                JSONObject parsed = whois.optJSONObject("parsed");
                if (parsed != null) {
                    java.util.Iterator<String> keys = parsed.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        if ("raw_whois_text".equals(key)) continue;
                        if ("status".equals(key)) continue;
                        Object val = parsed.get(key);
                        String label = key.replace("_", " ");
                        String displayVal = sanitizeValue(val.toString());
                        sb.append(label).append(": ").append(displayVal).append("\n");
                    }
                    String raw = parsed.optString("raw_whois_text", "");
                    if (!raw.isEmpty()) {
                        sb.append("\n── 原始WHOIS数据 ──\n");
                        sb.append(raw).append("\n");
                    }
                } else {
                    sb.append("WHOIS: ").append(whois.toString()).append("\n");
                }
            }

            JSONObject ssl = result.optJSONObject("ssl_cert");
            if (ssl != null) {
                sb.append("\n── SSL 证书信息 ──\n");
                sb.append("连接状态: ").append(ssl.optBoolean("connected", false) ? "成功" : "失败").append("\n");
                if (ssl.optBoolean("connected", false)) {
                    sb.append("协议: ").append(ssl.optString("protocol", "")).append("\n");
                    sb.append("加密套件: ").append(ssl.optString("cipher_suite", "")).append("\n");

                    JSONArray certs = ssl.optJSONArray("certificates");
                    if (certs != null && certs.length() > 0) {
                        JSONObject serverCert = certs.optJSONObject(0);
                        if (serverCert != null) {
                            sb.append("证书主题: ").append(serverCert.optString("subject", "")).append("\n");
                            sb.append("证书签发者: ").append(serverCert.optString("issuer", "")).append("\n");
                            sb.append("有效起始: ").append(serverCert.optString("valid_from", "")).append("\n");
                            sb.append("有效截止: ").append(serverCert.optString("valid_to", "")).append("\n");
                            long days = serverCert.optLong("days_remaining", 0);
                            sb.append("剩余天数: ").append(days).append("天\n");
                        }
                    }
                } else {
                    sb.append("错误: ").append(ssl.optString("error", "")).append("\n");
                }
            }

            return sb.toString();
        } catch (Exception e) {
            try { return result.toString(2); } catch (Exception ignored) { return "格式化失败"; }
        }
    }

    private static String sanitizeValue(String val) {
        if (val == null) return "";
        return val.replace("\r\n", " ").replace("\n", " ").replace("\r", " ")
                  .replaceAll("\\\\/", "/").trim();
    }

    // ==================== SSL证书客户端 ====================

    private static class SslCertClient {

        public JSONObject getCertInfo(String domain) throws Exception {
            JSONObject result = new JSONObject();

            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            SSLSocket socket = null;

            try {
                socket = (SSLSocket) factory.createSocket(domain, 443);
                socket.setSoTimeout(READ_TIMEOUT);
                socket.startHandshake();

                result.put("connected", true);
                result.put("host", domain);
                result.put("port", 443);

                // 获取证书链
                java.security.cert.Certificate[] certs = socket.getSession().getPeerCertificates();
                result.put("cert_chain_length", certs.length);

                JSONArray certArray = new JSONArray();
                for (int i = 0; i < certs.length; i++) {
                    if (certs[i] instanceof X509Certificate) {
                        certArray.put(parseX509Certificate((X509Certificate) certs[i], i));
                    }
                }
                result.put("certificates", certArray);

                // SSL会话信息
                result.put("protocol", socket.getSession().getProtocol());
                result.put("cipher_suite", socket.getSession().getCipherSuite());

            } catch (java.net.ConnectException e) {
                result.put("connected", false);
                result.put("error", "连接失败: " + e.getMessage());
            } catch (java.net.SocketTimeoutException e) {
                result.put("connected", false);
                result.put("error", "连接超时");
            } catch (javax.net.ssl.SSLHandshakeException e) {
                result.put("connected", false);
                result.put("error", "SSL握手失败: " + e.getMessage());
            } catch (Exception e) {
                result.put("connected", false);
                result.put("error", "查询失败: " + e.getMessage());
            } finally {
                if (socket != null) {
                    try { socket.close(); } catch (Exception ignored) {}
                }
            }

            return result;
        }

        private JSONObject parseX509Certificate(X509Certificate cert, int index) throws Exception {
            JSONObject info = new JSONObject();
            info.put("index", index);
            info.put("type", index == 0 ? "server_cert" : "intermediate_cert");

            info.put("subject", cert.getSubjectX500Principal().getName());
            info.put("issuer", cert.getIssuerX500Principal().getName());
            info.put("serial_number", cert.getSerialNumber().toString());

            info.put("version", cert.getVersion());
            info.put("signature_algorithm", cert.getSigAlgName());

            info.put("valid_from", cert.getNotBefore().toString());
            info.put("valid_to", cert.getNotAfter().toString());

            // 计算剩余天数
            long diff = cert.getNotAfter().getTime() - System.currentTimeMillis();
            long daysLeft = diff / (1000 * 60 * 60 * 24);
            info.put("days_remaining", daysLeft);
            info.put("is_expired", diff <= 0);

            // 提取SAN（主题备用名称）
            try {
                java.util.Collection<List<?>> sanList = cert.getSubjectAlternativeNames();
                if (sanList != null && !sanList.isEmpty()) {
                    JSONArray sanArray = new JSONArray();
                    for (List<?> san : sanList) {
                        if (san.size() >= 2) {
                            JSONObject sanEntry = new JSONObject();
                            int type = (Integer) san.get(0);
                            String value = (String) san.get(1);
                            String typeName;
                            switch (type) {
                                case 0: typeName = "other"; break;
                                case 1: typeName = "email"; break;
                                case 2: typeName = "DNS"; break;
                                case 3: typeName = "x400"; break;
                                case 4: typeName = "directory"; break;
                                case 5: typeName = "edi"; break;
                                case 6: typeName = "URI"; break;
                                case 7: typeName = "IP"; break;
                                case 8: typeName = "registeredID"; break;
                                default: typeName = "unknown"; break;
                            }
                            sanEntry.put("type", typeName);
                            sanEntry.put("value", value);
                            sanArray.put(sanEntry);
                        }
                    }
                    info.put("subject_alt_names", sanArray);
                }
            } catch (Exception e) {
                // SAN解析失败，跳过
            }

            // 提取基本约束
            try {
                boolean ca = cert.getBasicConstraints() >= 0;
                info.put("is_ca", ca);
            } catch (Exception e) {
                info.put("is_ca", false);
            }

            // 公钥信息
            try {
                java.security.PublicKey pubKey = cert.getPublicKey();
                info.put("public_key_algorithm", pubKey.getAlgorithm());
                info.put("public_key_format", pubKey.getFormat());
            } catch (Exception e) {
                // 忽略
            }

            return info;
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executorService != null) {
            executorService.shutdownNow();
        }
    }
}

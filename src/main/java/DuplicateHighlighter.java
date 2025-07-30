
import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.HighlightColor;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.params.HttpParameterType;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.proxy.ProxyHttpRequestResponse;
import burp.api.montoya.proxy.http.InterceptedRequest;
import burp.api.montoya.proxy.http.ProxyRequestHandler;
import burp.api.montoya.proxy.http.ProxyRequestReceivedAction;
import burp.api.montoya.proxy.http.ProxyRequestToBeSentAction;

import javax.swing.*;
import java.awt.event.ItemListener;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DuplicateHighlighter – Proxy‑history only (Montoya API 2025.6)
 *  - 追加比較：Cookie 名/値・Header 名/値
 *  - 灰色ハイライト無効トグル
 *  - 履歴リセット／全行再ハイライト
 */
public class DuplicateHighlighter implements BurpExtension, ProxyRequestHandler {

    /* ───────── 1. 設定 ───────── */
    private static class Config {
        /* 静的ファイル */
        volatile boolean staticImages   = true;
        volatile boolean staticScripts  = true;
        volatile boolean staticFontsMed = true;

        /* パラメータ比較 */
        volatile boolean useGetNames    = true;
        volatile boolean useGetValues   = true;
        volatile boolean usePostNames   = true;
        volatile boolean usePostValues  = false;
        volatile boolean useJsonKeys    = true;

        /* Cookie / Header 比較 */
        volatile boolean useCookieNames  = false;
        volatile boolean useCookieValues = false;
        volatile boolean useHeaderNames  = false;
        volatile boolean useHeaderValues = false;

        /* 灰色を付けない */
        volatile boolean disableGray    = false;
    }
    private final Config cfg = new Config();

    /* ───────── 2. 状態 ───────── */
    private final Set<String> seen = ConcurrentHashMap.newKeySet();
    private MontoyaApi api;

    /* ───────── 3. 初期化 ───────── */
    @Override
    public void initialize(MontoyaApi api) {
        this.api = api;
        api.extension().setName("Duplicate Highlighter");
        api.proxy().registerRequestHandler(this);
        api.userInterface().registerSuiteTab("Duplicate HL", buildSettingsPane());
    }

    /* ───────── 4. Proxy hooks ───────── */
    @Override
    public ProxyRequestToBeSentAction handleRequestToBeSent(InterceptedRequest ir) {
        return ProxyRequestToBeSentAction.continueWith(ir);
    }

    @Override
    public ProxyRequestReceivedAction handleRequestReceived(InterceptedRequest ir) {
        HttpRequest req = ir;

        HighlightColor col = decideColor(req);
        if (col != null)  ir.annotations().setHighlightColor(col);
        else               ir.annotations().setHighlightColor(HighlightColor.NONE);

        return ProxyRequestReceivedAction.continueWith(req);
    }

    /* ───────── 5. 色決定 ───────── */
    private HighlightColor decideColor(HttpRequest req) {
        String path = req.pathWithoutQuery().toLowerCase(Locale.ROOT);

        boolean isStatic =
                cfg.staticImages   && endsWith(path, ".png",".jpg",".jpeg",".gif",".svg",".ico") ||
                cfg.staticScripts  && endsWith(path, ".js",".mjs",".css",".map")                 ||
                cfg.staticFontsMed && endsWith(path, ".woff",".woff2",".ttf",".eot",
                                               ".mp4",".webm",".ogg",".mp3");

        if (isStatic)
            return cfg.disableGray ? null : HighlightColor.GRAY;

        String key = buildKey(req);
        boolean first = seen.add(key);
        if (first) return HighlightColor.CYAN;
        return cfg.disableGray ? null : HighlightColor.GRAY;
    }

    private boolean endsWith(String p, String... exts) {
        for (String e : exts) if (p.endsWith(e)) return true;
        return false;
    }

    /* ───────── 6. キー生成 ───────── */
    private String buildKey(HttpRequest r) {

        /* 1. プロトコル / ホスト / ポート */
        HttpService svc = r.httpService();
        String proto = svc.secure() ? "https" : "http";
        String hostPart = proto + "://" + svc.host().toLowerCase(Locale.ROOT) + ":" + svc.port();

        /* 2. パス (クエリ除外) */
        String path = r.pathWithoutQuery();

        List<String> parts = new ArrayList<>();

        /* GET パラメータ */
        r.parameters().stream()
                .filter(p -> p.type() == HttpParameterType.URL)
                .forEach(p -> {
                    if (cfg.useGetNames)  parts.add("GN:" + p.name());
                    if (cfg.useGetValues) parts.add("GV:" + p.name() + "=" + p.value());
                });

        /* POST パラメータ */
        r.parameters().stream()
                .filter(p -> p.type() == HttpParameterType.BODY)
                .forEach(p -> {
                    if (cfg.usePostNames)  parts.add("PN:" + p.name());
                    if (cfg.usePostValues) parts.add("PV:" + p.name() + "=" + p.value());
                });

        /* Cookie パラメータ (montoya は Cookie もパラメータ型) */
        r.parameters().stream()
                .filter(p -> p.type() == HttpParameterType.COOKIE)
                .forEach(p -> {
                    if (cfg.useCookieNames)  parts.add("CN:" + p.name());
                    if (cfg.useCookieValues) parts.add("CV:" + p.name() + "=" + p.value());
                });

        /* JSON キー */
        String ct = r.headerValue("Content-Type");
        if (cfg.useJsonKeys && ct != null && ct.contains("application/json")) {
            String body = new String(r.body().getBytes(), StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("(?<=\\{|,)\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*:").matcher(body);
            while (m.find()) parts.add("J:" + m.group(1));
        }

        /* Header 名/値 */
        if (cfg.useHeaderNames || cfg.useHeaderValues) {
            for (HttpHeader h : r.headers()) {
                String nameLower = h.name().toLowerCase(Locale.ROOT);
                if (nameLower.equals("cookie")) continue;         // Cookie は別途処理済み
                if (cfg.useHeaderNames)  parts.add("HN:" + nameLower);
                if (cfg.useHeaderValues) parts.add("HV:" + nameLower + "=" + h.value());
            }
        }

        Collections.sort(parts);
        return r.method() + " " + hostPart + path + " " + String.join("&", parts);
    }

    /* ───────── 7. GUI パネル ───────── */
    private JComponent buildSettingsPane() {

        /* 静的ファイル */
        JCheckBox cbImg  = new JCheckBox("画像を GRAY",           cfg.staticImages);
        JCheckBox cbScr  = new JCheckBox("JS/CSS を GRAY",        cfg.staticScripts);
        JCheckBox cbFont = new JCheckBox("フォント/メディアを GRAY", cfg.staticFontsMed);

        /* パラメータ比較 */
        JCheckBox cbGN = new JCheckBox("GET 名",   cfg.useGetNames);
        JCheckBox cbGV = new JCheckBox("GET 値",   cfg.useGetValues);
        JCheckBox cbPN = new JCheckBox("POST 名",  cfg.usePostNames);
        JCheckBox cbPV = new JCheckBox("POST 値",  cfg.usePostValues);
        JCheckBox cbJK = new JCheckBox("JSON キー", cfg.useJsonKeys);

        /* Cookie / Header */
        JCheckBox cbCN = new JCheckBox("Cookie 名",  cfg.useCookieNames);
        JCheckBox cbCV = new JCheckBox("Cookie 値",  cfg.useCookieValues);
        JCheckBox cbHN = new JCheckBox("Header 名",  cfg.useHeaderNames);
        JCheckBox cbHV = new JCheckBox("Header 値",  cfg.useHeaderValues);

        /* 灰色無効 */
        JCheckBox cbNoGray = new JCheckBox("灰色ハイライト無効", cfg.disableGray);

        JButton reset   = new JButton("履歴リセット");
        JButton recolor = new JButton("すべて再ハイライト");

        ItemListener il = e -> {
            cfg.staticImages   = cbImg.isSelected();
            cfg.staticScripts  = cbScr.isSelected();
            cfg.staticFontsMed = cbFont.isSelected();

            cfg.useGetNames    = cbGN.isSelected();
            cfg.useGetValues   = cbGV.isSelected();
            cfg.usePostNames   = cbPN.isSelected();
            cfg.usePostValues  = cbPV.isSelected();
            cfg.useJsonKeys    = cbJK.isSelected();

            cfg.useCookieNames  = cbCN.isSelected();
            cfg.useCookieValues = cbCV.isSelected();
            cfg.useHeaderNames  = cbHN.isSelected();
            cfg.useHeaderValues = cbHV.isSelected();

            cfg.disableGray    = cbNoGray.isSelected();

            seen.clear();
        };
        List.of(cbImg,cbScr,cbFont,cbGN,cbGV,cbPN,cbPV,cbJK,
                cbCN,cbCV,cbHN,cbHV,cbNoGray).forEach(cb -> cb.addItemListener(il));

        reset.addActionListener(e -> {
            seen.clear();
            JOptionPane.showMessageDialog(null, "ヒストリセットをクリアしました。",
                    "Duplicate HL", JOptionPane.INFORMATION_MESSAGE);
        });

        recolor.addActionListener(e -> {
            int changed = recolorAll();
            JOptionPane.showMessageDialog(null,
                    "再ハイライト完了: " + changed + " 件更新。",
                    "Duplicate HL", JOptionPane.INFORMATION_MESSAGE);
        });

        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createEmptyBorder(8,8,8,8));

        p.add(new JLabel("■ 静的ファイル"));
        p.add(cbImg); p.add(cbScr); p.add(cbFont);
        p.add(Box.createVerticalStrut(6));

        p.add(new JLabel("■ パラメータ比較"));
        p.add(cbGN); p.add(cbGV); p.add(cbPN); p.add(cbPV); p.add(cbJK);
        p.add(Box.createVerticalStrut(6));

        p.add(new JLabel("■ Cookie / Header 比較"));
        p.add(cbCN); p.add(cbCV); p.add(cbHN); p.add(cbHV);
        p.add(Box.createVerticalStrut(6));

        p.add(cbNoGray);
        p.add(Box.createVerticalStrut(6));

        p.add(reset);
        p.add(Box.createVerticalStrut(4));
        p.add(recolor);

        return new JScrollPane(p);
    }

    /* ───────── 8. 再ハイライト ───────── */
    private int recolorAll() {
        seen.clear();
        int count = 0;
        for (ProxyHttpRequestResponse h : api.proxy().history()) {
            HighlightColor c = decideColor(h.request());
            if (c != null) h.annotations().setHighlightColor(c);
            else           h.annotations().setHighlightColor(HighlightColor.NONE);
            count++;
        }
        return count;
    }
}

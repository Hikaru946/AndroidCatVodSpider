package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.utils.Util;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 低调影视 (ddys.app) —— WordPress 定制主题（非苹果CMS！），学习用示例。
 * 本文件的看点有两个，都是前三个站没遇到过的：
 *  1. ext 模式（同 XiaoHeimi）
 *  2. 站点有"密码门"防护：爬虫必须先完成一次握手拿到会话 Cookie，之后才能访问内容
 *
 * 站点结构（2026-10 实测）：
 *   文章页(=详情+播放)  https://ddys.app/{slug}/
 *   分类页              https://ddys.app/category/{movie|drama|anime|airing}[/{子分类}]/
 *   翻页               .../page/{n}/（WordPress 固定链接风格）
 *   搜索               https://ddys.app/?s={关键词}
 *   播放数据            文章页内 <script class="ddys-playlist-data" type="application/json">
 *            seasons[].tracks[] 里每集自带各线路直链 nodeSources，mp4 直链，无需模拟播放器
 *
 * 密码门协议（插件 ddys-protect）：
 *   GET 任意页 → 门页面含隐藏字段(started/started_sig/captcha_token/nonce...)
 *   GET /wp-json/ddys-protect/v1/gatecha/challenge → ALTCHA 工作量证明挑战
 *   本地穷举 number 使 SHA-256(salt+number)==challenge（实测几十万次 <1s）
 *   POST base64(ALTCHA载荷) + 密码 + 全部隐藏字段 → 302 + Set-Cookie
 *   之后所有请求带 Cookie 即为已登录会话
 */
public class Ddys extends Spider {

    // ext 模式增强版：支持两种写法
    //   "ext": "https://ddys.app"                        —— 只覆盖域名（走自动握手，可能被点选验证码挡住）
    //   "ext": "https://ddys.app|Cookie字符串"            —— 域名 + 手动注入会话 Cookie（推荐，见下）
    // Cookie 获取方法：电脑浏览器过一次门 → F12 → Network → 任选一个 ddys.app 请求 →
    //   Request Headers 里复制整行 Cookie 的值 → 粘到 ext 第二段。Cookie 失效后重取一次即可
    private String siteUrl = "https://ddys.app";

    // 门密码：门页面公示"低调影视的拼音缩写"。若站点改密码需同步改这里
    private static final String GATE_PASSWORD = "ddys";

    private volatile String cookie = "";

    @Override
    public void init(Context context, String extend) {
        if (extend == null || !extend.startsWith("http")) return;
        String[] parts = extend.split("\\|", 2);
        siteUrl = parts[0].replaceAll("/+$", "");
        if (parts.length > 1 && !parts[1].trim().isEmpty()) cookie = parts[1].trim();
    }

    private Map<String, String> getHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", Util.CHROME);
        headers.put("Referer", siteUrl + "/");
        if (!cookie.isEmpty()) headers.put("Cookie", cookie);
        return headers;
    }

    /* ==================== 带过门重试的请求 ==================== */

    // 所有内容请求都走这里：发现被门拦住就先握手再重取一次
    private String fetch(String path) throws Exception {
        String url = path.startsWith("http") ? path : siteUrl + path;
        for (int attempt = 0; attempt < 2; attempt++) {
            String body = httpGet(url);
            if (!body.contains("ddys-protect-panel")) return body;
            // 注入的 Cookie 已失效时清掉，走一次自动握手（握手若被点选验证码挡住则给出指引）
            cookie = "";
            passGate();
        }
        throw new Exception("ddys 过门失败：站点要求先完成'请依次点击'的点选验证，爬虫无法自动完成。"
                + "请在电脑浏览器里过一次门，然后按 ext 格式把 Cookie 填入配置：https://ddys.app|你的Cookie");
    }

    private String httpGet(String url) throws Exception {
        Response resp = OkHttp.newCall(new Request.Builder().url(url).headers(okhttp3.Headers.of(getHeaders())).build());
        return resp.body() == null ? "" : resp.body().string();
    }

    /* ==================== 密码门握手 ==================== */

    private synchronized void passGate() throws Exception {
        OkHttpClient noRedirect = new OkHttpClient.Builder()
                .followRedirects(false).followSslRedirects(false)
                .connectTimeout(30, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build();

        // 1) 取门页面上的全部隐藏字段（nonce/时间戳签名等，均为一次性）
        String gate = httpGet(siteUrl + "/");
        Map<String, String> fields = new LinkedHashMap<>();
        Matcher m = Pattern.compile("<input[^>]+type=\"hidden\"[^>]+name=\"([^\"]+)\"[^>]+value=\"([^\"]*)\"").matcher(gate);
        while (m.find()) fields.put(m.group(1), m.group(2));

        // 2) 取 ALTCHA 挑战并解工作量证明：SHA-256(salt + number) == challenge
        JsonObject chal = JsonParser.parseString(httpGet(siteUrl + "/wp-json/ddys-protect/v1/gatecha/challenge")).getAsJsonObject();
        String salt = chal.get("salt").getAsString();
        String target = chal.get("challenge").getAsString();
        int max = chal.has("maxNumber") ? chal.get("maxNumber").getAsInt() : 1_000_000;
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        long t0 = System.currentTimeMillis();
        int number = -1;
        for (int i = 0; i < max; i++) {
            byte[] d = md.digest((salt + i).getBytes());
            StringBuilder hex = new StringBuilder();
            for (byte b : d) hex.append(String.format("%02x", b));
            if (hex.toString().equals(target)) { number = i; break; }
        }
        if (number < 0) throw new Exception("ddys 过门失败：ALTCHA 穷举未解出");
        long took = System.currentTimeMillis() - t0;

        // 3) 组 ALTCHA 载荷（base64 JSON），连密码一起提交
        JsonObject payloadJson = new JsonObject();
        payloadJson.addProperty("algorithm", chal.get("algorithm").getAsString());
        payloadJson.addProperty("challenge", target);
        payloadJson.addProperty("number", number);
        payloadJson.addProperty("salt", salt);
        payloadJson.addProperty("signature", chal.get("signature").getAsString());
        payloadJson.addProperty("took", (int) took);
        String altcha = android.util.Base64.encodeToString(payloadJson.toString().getBytes(), android.util.Base64.NO_WRAP);

        FormBody.Builder form = new FormBody.Builder();
        for (Map.Entry<String, String> e : fields.entrySet()) form.add(e.getKey(), e.getValue());
        form.add("ddys_protect_password", GATE_PASSWORD);
        form.add("ddys_protect_altcha_gate", altcha);

        Response resp = noRedirect.newCall(new Request.Builder().url(siteUrl + "/")
                .headers(okhttp3.Headers.of(getHeaders())).post(form.build()).build()).execute();
        StringBuilder sb = new StringBuilder();
        for (String sc : resp.headers("Set-Cookie")) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(sc.split(";")[0]);
        }
        String body = resp.body() == null ? "" : resp.body().string();

        // 4) 成功判定：拿到新会话 Cookie
        if (sb.length() > 0) {
            cookie = sb.toString();
            return;
        }
        // 失败：把门页面上的警告翻出来给人看（如"尝试次数过多"/"验证码错误"）
        Matcher warn = Pattern.compile("ddys-protect-warning[^>]*>(.{0,400}?)</div>", body.contains("ddys-protect-panel") ? Pattern.DOTALL : 0).matcher(body);
        String msg = warn.find() ? warn.group(1).replaceAll("<[^>]+>|\\s+", " ").trim() : "未知原因（未返回会话Cookie）";
        if (msg.contains("验证码错误")) msg += "【原因：站点要求先完成点选式进入验证，纯HTTP无法通过。请改用 Cookie 注入：ext 填 https://ddys.app|你的Cookie】";
        throw new Exception("ddys 过门失败: " + msg);
    }

    /* ==================== 首页 ==================== */

    @Override
    public String homeContent(boolean filter) throws Exception {
        Document doc = Jsoup.parse(fetch("/"));
        // 分类从导航解析（WordPress category 路径直接当作 tid 传递）
        List<Class> classes = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Element a : doc.select("a[href*=/category/]")) {
            String path = a.attr("href").replace(siteUrl, "").replaceAll("/+$", "");
            String name = a.text().trim();
            if (path.isEmpty() || name.isEmpty() || !seen.add(path)) continue;
            classes.add(new Class(path, name));
        }
        List<Vod> list = parsePostList(doc);
        if (list.size() > 30) list = list.subList(0, 30);
        return Result.string(classes, list);
    }

    @Override
    public String homeVideoContent() throws Exception {
        Document doc = Jsoup.parse(fetch("/"));
        List<Vod> list = parsePostList(doc);
        return list.isEmpty() ? "" : Result.string(list.get(0));
    }

    /* ==================== 分类列表 ==================== */

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        // tid 形如 "/category/movie"（自带前导斜杠），直接拼翻页段
        String path = tid + (pg == null || pg.isEmpty() || "1".equals(pg) ? "/" : "/page/" + pg + "/");
        Document doc = Jsoup.parse(fetch(path));
        List<Vod> list = parsePostList(doc);
        // WordPress 不总在页面上给总页数：能解析到就用，解析不到就顺着翻（翻到空页自然结束）
        int page = parseInt(pg, 1), count = page;
        for (Element a : doc.select("a[href*=/page/]")) {
            Matcher m = Pattern.compile("/page/(\\d+)/").matcher(a.attr("href"));
            if (m.find()) count = Math.max(count, Integer.parseInt(m.group(1)));
        }
        return Result.get().vod(list).page(page, count, Math.max(1, list.size()), count * Math.max(1, list.size())).string();
    }

    /* ==================== 详情（WordPress：文章页即详情+播放） ==================== */

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String id = ids.get(0); // 完整文章 URL，如 https://ddys.app/interstellar/
        Document doc = Jsoup.parse(fetch(id));

        Vod vod = new Vod();
        vod.setVodId(id);
        Element h1 = doc.selectFirst("h1.entry-title, h1.post-box-title, h1");
        vod.setVodName(h1 == null ? "" : h1.text().trim());
        // 封面：卡片区的背景图样式
        Element box = doc.selectFirst("article.post-box .post-box-image, .post-box-image");
        if (box != null) {
            Matcher m = Pattern.compile("url\\(([^)]+)\\)").matcher(box.attr("style"));
            if (m.find()) vod.setVodPic(m.group(1).replace("\"", ""));
        }
        // 简介：正文第一个段落
        Element desc = doc.selectFirst(".entry-content p");
        if (desc != null) vod.setVodContent(desc.text().trim());

        // 播放数据全在 <script class="ddys-playlist-data"> 的 JSON 里
        Element data = doc.selectFirst("script.ddys-playlist-data");
        if (data == null) return Result.string(vod);
        JsonObject d = JsonParser.parseString(data.html()).getAsJsonObject();

        // 线路 = nodes（跳过 VIP 未开放线路），每条线路下的选集来自 seasons[].tracks[]
        // 每集各线路的地址在 track.nodeSources{节点id: 直链}，兜底用 track.src
        List<String> fromNames = new ArrayList<>(), playUrls = new ArrayList<>();
        for (JsonElement nodeEl : d.getAsJsonArray("nodes")) {
            JsonObject node = nodeEl.getAsJsonObject();
            String nodeId = node.get("id").getAsString();
            if (node.has("access") && "vip".equals(node.get("access").getAsString())) continue;
            List<String> eps = new ArrayList<>();
            for (JsonElement seasonEl : d.getAsJsonArray("seasons")) {
                JsonObject season = seasonEl.getAsJsonObject();
                String seasonTitle = season.has("title") ? season.get("title").getAsString() : "";
                boolean multi = d.getAsJsonArray("seasons").size() > 1;
                for (JsonElement trackEl : season.getAsJsonArray("tracks")) {
                    JsonObject track = trackEl.getAsJsonObject();
                    JsonObject sources = track.has("nodeSources") && track.get("nodeSources").isJsonObject()
                            ? track.getAsJsonObject("nodeSources") : null;
                    String url = sources != null && sources.has(nodeId)
                            ? sources.get(nodeId).getAsString() : (track.has("src") ? track.get("src").getAsString() : "");
                    if (url.isEmpty() || !url.startsWith("http")) continue;
                    String name = track.has("title") && !track.get("title").getAsString().isEmpty()
                            ? track.get("title").getAsString() : "第" + (track.has("episode") ? track.get("episode").getAsInt() : eps.size() + 1) + "集";
                    if (multi && !seasonTitle.isEmpty()) name = seasonTitle + "·" + name;
                    eps.add(name + "$" + url);
                }
            }
            if (!eps.isEmpty()) {
                fromNames.add(node.get("label").getAsString());
                playUrls.add(TextUtils.join("#", eps));
            }
        }
        if (!fromNames.isEmpty()) {
            vod.setVodPlayFrom(TextUtils.join("$$$", fromNames));
            vod.setVodPlayUrl(TextUtils.join("$$$", playUrls));
        }
        return Result.string(vod);
    }

    /* ==================== 搜索 ==================== */

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        Document doc = Jsoup.parse(fetch("/?s=" + URLEncoder.encode(key)));
        return Result.string(parsePostList(doc));
    }

    /* ==================== 播放 ==================== */

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        // detailContent 给出的就是 mp4/m3u8 直链，直接播放（parse 默认 0）
        return Result.get().url(id).header(getHeaders()).string();
    }

    /* ==================== 工具方法 ==================== */

    // WordPress 文章卡片：article.post-box（链接在 data-href，封面在背景图样式里）
    private List<Vod> parsePostList(Document doc) {
        List<Vod> list = new ArrayList<>();
        for (Element box : doc.select("article.post-box")) {
            String id = box.attr("data-href");
            Element title = box.selectFirst(".post-box-title a");
            if (id.isEmpty() || title == null || title.text().trim().isEmpty()) continue;
            String pic = "";
            Element img = box.selectFirst(".post-box-image");
            if (img != null) {
                Matcher m = Pattern.compile("url\\(([^)]+)\\)").matcher(img.attr("style"));
                if (m.find()) pic = m.group(1).replace("\"", "");
            }
            String remark = text(box, ".post-box-meta");
            list.add(new Vod(id, title.text().trim(), pic, remark));
        }
        return list;
    }

    private String text(Element root, String css) {
        Element e = root.selectFirst(css);
        return e == null ? "" : e.text().trim();
    }

    private int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}

package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Filter;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.AESEncryption;
import com.github.catvod.utils.ProxyVideo;
import com.github.catvod.utils.Util;

import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.math.BigInteger;
import java.net.URI;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 厂长资源（4kcz.com）—— 由 drpy 规则式 JS 爬虫（厂长资源.js）转写为 Java，学习用示例。
 * 本文件的看点：JS→Java 的"翻译"过程，注释里标了每段对应的 JS 原文。
 *
 * 站点结构（2026-10 实测，WordPress + 厂长定制主题）：
 *   分类   /movie_bt/page/1 或 /movie_bt_series/dyy/page/1（class_url 混合两种格式）
 *          筛选段 /movie_bt_tags/xxx（类型）与 /movie_bt_series/xxx（分类系列）拼在页码前
 *   详情   /movie/{vid}.html
 *          标题 div.dytext.fl>div>h1；封面 div.dyxingq>div>div.dyimg.fl>img
 *          信息 .moviedteail_list li（位置固定：1类型 2地区 3年份 6导演 8主演）
 *          多线路 .mi_paly_box span（线路名）+ 多个 div.paly_list_btn（各线路选集）
 *   播放   /v_play/{base64}.html，方案有三（对应 JS 的 lazy 三分支）：
 *          a) iframe 且 src 含 Cloud → 取 var url 十六进制反转+删7字符 → 直链
 *          b) 页面含 decrypted → AES(CBC) 解密内联脚本 → 直链
 *          c) 其余 → parse=1 交给 App 嗅探
 *   搜索   /page/{pg}?s={关键词}（WordPress 原生搜索，Cookie 需带 esc_search_captcha=1）
 *
 * ext 模式（同 Ddys）：站点有雷池 WAF，必要时 Cookie 注入
 *   "ext": "https://www.czzymovie.com|Cookie整串"
 * 也可以填当前可用域名 https://www.4kcz.com（站点公告的备用域名见其首页弹窗，发布页 www.cz01.vip）
 */
public class CZY extends Spider {

    // JS 原文的 host；可被 ext 覆盖为 https://www.4kcz.com 等当前可用域名
    private String siteUrl = "https://www.czzymovie.com";
    private volatile String cookie = "";

    // JS headers 里的 MOBILE_UA（iPhone UA 是这套站 WAF 实测放行的请求特征）
    private static final String MOBILE_UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 16_3 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/100.0.4896.77 Mobile/15E148 Safari/604.1";

    // JS class_name / class_url 的 24 个分类（原文照抄，仅修正末尾"纪录片"的拼写）
    private static final String[] CAT_NAMES = {"全部", "豆瓣电影Top250", "高分影视", "最新电影", "热映中", "站长推荐", "电影", "电视剧", "动画", "国产剧", "日剧", "韩剧", "美剧", "海外剧", "俄罗斯电影", "加拿大电影", "华语电影", "印度电影", "日本电影", "欧美电影", "法国电影", "英国电影", "韩国电影", "纪录片"};
    private static final String[] CAT_URLS = {"movie_bt", "dbtop250", "gaofenyingshi", "zuixindianying", "reyingzhong", "/movie_bt_series/zhanchangtuijian", "/movie_bt_series/dyy", "/movie_bt_series/dianshiju", "/movie_bt_series/dohua", "/movie_bt_series/guochanju", "/movie_bt_series/rj", "/movie_bt_series/hj", "/movie_bt_series/mj", "/movie_bt_series/hwj", "/movie_bt_series/eluosidianying", "/movie_bt_series/jianadadianying", "/movie_bt_series/huayudianying", "/movie_bt_series/yindudianying", "/movie_bt_series/ribendianying", "/movie_bt_series/meiguodianying", "/movie_bt_series/faguodianying", "/movie_bt_series/yingguodianying", "/movie_bt_series/hanguodianying", "/movie_bt_tags/jlpp"};

    @Override
    public void init(Context context, String extend) {
        if (extend == null || !extend.startsWith("http")) return;
        String[] parts = extend.split("\\|", 2);
        siteUrl = parts[0].replaceAll("/+$", "");
        if (parts.length > 1 && !parts[1].trim().isEmpty()) cookie = parts[1].trim();
    }

    // JS headers 的 Java 版：MOBILE_UA + 搜索验证 Cookie + 可选注入的会话 Cookie
    private Map<String, String> getHeader() {
        Map<String, String> header = new LinkedHashMap<>();
        header.put("User-Agent", MOBILE_UA);
        header.put("Cookie", "esc_search_captcha=1" + (cookie.isEmpty() ? "" : "; " + cookie));
        URI uri = URI.create(siteUrl);
        header.put("Host", uri.getHost());
        header.put("Referer", siteUrl + "/");
        return header;
    }

    // 播放页 iframe 专用头（照搬原作者 ChangZhang.java 的 getIframeHeader）
    private Map<String, String> getIframeHeader(String url) {
        Map<String, String> header = new LinkedHashMap<>();
        header.put("User-Agent", Util.CHROME);
        URI uri = URI.create(url);
        header.put("Host", uri.getHost());
        header.put("Sec-Fetch-Dest", "iframe");
        header.put("Sec-Fetch-Mode", "navigate");
        header.put("Referer", siteUrl + "/");
        return header;
    }

    /* ==================== 带拦截检测的请求 ==================== */

    private String fetch(String url) throws Exception {
        String body = OkHttp.string(url, getHeader());
        // 雷池 WAF / 人机验证页特征
        if (body.contains("safeline") || body.contains("正在进行人机识别") || body.contains("Just a moment")) {
            throw new Exception("厂长资源被 WAF 拦截：请用电脑浏览器过一次验证，ext 填 https://www.czzymovie.com|你的Cookie");
        }
        return body;
    }

    /* ==================== 首页 ==================== */

    @Override
    public String homeContent(boolean filter) throws Exception {
        Document doc = Jsoup.parse(OkHttp.string(siteUrl));
        List<Class> classes = new ArrayList<>();
        for (int i = 0; i < CAT_NAMES.length; i++) classes.add(new Class(CAT_URLS[i], CAT_NAMES[i]));
        List<Vod> list = new ArrayList<>();
        parseCards(doc, list);   // 首页推荐区用同一套卡片选择器
        if (list.size() > 30) list = list.subList(0, 30);
        return Result.string(classes, list, filterMap());
    }

    @Override
    public String homeVideoContent() throws Exception {
        Document doc = Jsoup.parse(OkHttp.string(siteUrl));
        List<Vod> list = new ArrayList<>();
        parseCards(doc, list);
        return list.isEmpty() ? "" : Result.string(list.get(0));
    }

    /* ==================== 分类列表 ==================== */

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        // JS filter_url: {{fl.cateId}}{{fl.class}}{{fl.area}}/page/fypage
        // tid 就是 class_url 的值（部分带 / 前缀），筛选值也以 / 开头，直接顺序拼接
        StringBuilder path = new StringBuilder(tid.startsWith("/") ? tid : "/" + tid);
        path.append(value(extend, "class")).append(value(extend, "area"));
        path.append("/page/").append(pg == null || pg.isEmpty() ? "1" : pg);
        // 注意：首页/分类/搜索用裸请求（原作者实测通过的模式——带 iPhone UA 会触发站点移动模板导致解析为空）
        Document doc = Jsoup.parse(OkHttp.string(path.toString()));
        List<Vod> list = new ArrayList<>();
        parseCards(doc, list);
        return Result.get().vod(list).page(parseInt(pg, 1), parseInt(pg, 1) + 500, 25, 99999).string();
    }

    /* ==================== 详情 ==================== */

    @Override
    public String detailContent(List<String> ids) throws Exception {
        Document doc = Jsoup.parse(fetch(ids.get(0)));

        Vod vod = new Vod();
        vod.setVodId(ids.get(0));
        vod.setVodName(text(doc, "div.dytext.fl > div > h1"));
        Element pic = doc.selectFirst("div.dyxingq > div > div.dyimg.fl > img");
        if (pic != null) vod.setVodPic(pic.attr("src"));
        // 信息条位置固定（实测）：1类型 2地区 3年份 6导演 8主演
        vod.setTypeName(text(doc, ".moviedteail_list > li:nth-child(1) a"));
        vod.setVodArea(text(doc, ".moviedteail_list > li:nth-child(2) a"));
        vod.setVodYear(text(doc, ".moviedteail_list > li:nth-child(3) a"));
        vod.setVodDirector(text(doc, ".moviedteail_list > li:nth-child(6) a"));
        vod.setVodActor(text(doc, ".moviedteail_list > li:nth-child(8)"));
        vod.setVodRemarks(text(doc, ".moviedteail_list > li:nth-child(5)"));
        vod.setVodContent(text(doc, ".yp_context"));

        // 多线路：.mi_paly_box span 是线路名，div.paly_list_btn 按顺序对应各线路的选集
        List<Element> tabs = doc.select(".mi_paly_box span");
        List<Element> boxes = doc.select("div.paly_list_btn");
        List<String> fromNames = new ArrayList<>(), playUrls = new ArrayList<>();
        for (int i = 0; i < boxes.size(); i++) {
            List<String> eps = new ArrayList<>();
            for (Element a : boxes.get(i).select("a")) {
                eps.add(a.text().trim() + "$" + a.attr("href"));
            }
            if (eps.isEmpty()) continue;
            String name = i < tabs.size() && !tabs.get(i).text().trim().isEmpty() ? tabs.get(i).text().trim() : "线路" + (i + 1);
            fromNames.add(name);
            playUrls.add(TextUtils.join("#", eps));
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
        return search(key, "1");
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) throws Exception {
        return search(key, pg == null || pg.isEmpty() ? "1" : pg);
    }

    // JS searchUrl: /page/fypage?s=**（WordPress 原生搜索；搜索用裸请求，与原作者实测模式一致）
    private String search(String key, String pg) throws Exception {
        Document doc = Jsoup.parse(OkHttp.string(siteUrl + "/page/" + pg + "?s=" + URLEncoder.encode(key)));
        List<Vod> list = new ArrayList<>();
        Element box = doc.selectFirst(".search_list");
        if (box != null) parseCards(box, list);
        return Result.string(list);
    }

    /* ==================== 播放（JS lazy 三分支的 Java 版） ==================== */

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        Document doc = Jsoup.parse(fetch(siteUrl + id));
        Element iframe = doc.selectFirst("iframe");

        // 分支 a：有 iframe（部分源在 iframe 内再做一次 Cloud 解码）
        if (iframe != null) {
            String src = iframe.attr("src");
            String inner = OkHttp.string(src, getIframeHeader(src));
            Matcher m = Pattern.compile("result_v2\\s*=(.*?);").matcher(inner);
            if (m.find()) {
                // hex 反转 + 去除中间 7 个字符（与 JS 的 reverse + decodeStr 等价）
                JSONObject json = new JSONObject(m.group(1));
                String encoded = json.getString("data");
                String real = new String(new BigInteger(new StringBuilder(encoded).reverse().toString(), 16).toByteArray());
                String url = decodeStr(real);
                // 视频源需要特定 Referer，走代理头方案（同原作者实现）
                return Result.get().url(ProxyVideo.buildCommonProxyUrl(url, getVideoHeader(url))).string();
            }
            // iframe 内无 result_v2 → 该播放器自解析，交给 App 嗅探
            return Result.get().url(src).parse().header(getHeader()).string();
        }

        // 分支 b：页面含 decrypted（AES 加密的内联脚本）
        if (doc.html().contains("decrypted")) {
            for (Element script : doc.select("script")) {
                String scriptText = script.html();
                if (!scriptText.contains("wp_nonce")) continue;
                Matcher m = Pattern.compile("var(.*?)=\"(.*?)\"").matcher(scriptText);
                if (m.find()) {
                    String result = dncry(m.group(2));
                    Matcher mu = Pattern.compile("url:.*?['\"](.*?)['\"]").matcher(result);
                    if (mu.find()) {
                        return Result.get().url(mu.group(1).replace("\"", "").replace("url:", "").trim()).string();
                    }
                }
            }
        }

        // 分支 c：交给 App 网页嗅探
        return Result.get().url(siteUrl + id).parse().header(getHeader()).string();
    }

    /* ==================== 工具方法 ==================== */

    // JS filter 块的两组筛选值（分类=系列路径，类型=标签路径），v 直接拼进分类 URL
    private LinkedHashMap<String, List<Filter>> filterMap() {
        List<Filter> group = new ArrayList<>();
        List<Filter.Value> area = new ArrayList<>();
        area.add(new Filter.Value("全部", ""));
        for (String[] p : new String[][]{{"站长推荐", "/movie_bt_series/zhanchangtuijian"}, {"电影", "/movie_bt_series/dyy"}, {"电视剧", "/movie_bt_series/dianshiju"}, {"动画", "/movie_bt_series/dohua"}, {"国产剧", "/movie_bt_series/guochanju"}, {"美剧", "/movie_bt_series/mj"}, {"日剧", "/movie_bt_series/rj"}, {"韩剧", "/movie_bt_series/hj"}, {"海外剧（其他）", "/movie_bt_series/hwj"}, {"华语电影", "/movie_bt_series/huayudianying"}, {"欧美电影", "/movie_bt_series/meiguodianying"}, {"日本电影", "/movie_bt_series/ribendianying"}, {"韩国电影", "/movie_bt_series/hanguodianying"}, {"英国电影", "/movie_bt_series/yingguodianying"}, {"法国电影", "/movie_bt_series/faguodianying"}, {"印度电影", "/movie_bt_series/yindudianying"}, {"俄罗斯电影", "/movie_bt_series/eluosidianying"}, {"加拿大电影", "/movie_bt_series/jianadadianying"}, {"会员专区", "/movie_bt_series/huiyuanzhuanqu"}}) {
            area.add(new Filter.Value(p[0], p[1]));
        }
        group.add(new Filter("area", "分类", area));
        List<Filter.Value> cls = new ArrayList<>();
        cls.add(new Filter.Value("全部", ""));
        for (String[] p : new String[][]{{"传记", "/movie_bt_tags/chuanji"}, {"儿童", "/movie_bt_tags/etet"}, {"冒险", "/movie_bt_tags/maoxian"}, {"剧情", "/movie_bt_tags/juqing"}, {"动作", "/movie_bt_tags/dozuo"}, {"动漫", "/movie_bt_tags/doman"}, {"动画", "/movie_bt_tags/dhh"}, {"历史", "/movie_bt_tags/lishi"}, {"古装", "/movie_bt_tags/guzhuang"}, {"同性", "/movie_bt_tags/tongxing"}, {"喜剧", "/movie_bt_tags/xiju"}, {"奇幻", "/movie_bt_tags/qihuan"}, {"家庭", "/movie_bt_tags/jiating"}, {"恐怖", "/movie_bt_tags/kubu"}, {"悬疑", "/movie_bt_tags/xuanyi"}, {"情色", "/movie_bt_tags/qingse"}, {"惊悚", "/movie_bt_tags/kingsong"}, {"战争", "/movie_bt_tags/zhanzhen"}, {"歌舞", "/movie_bt_tags/gw"}, {"武侠", "/movie_bt_tags/wuxia"}, {"灾难", "/movie_bt_tags/zainan"}, {"爱情", "/movie_bt_tags/aiqing"}, {"犯罪", "/movie_bt_tags/fanzui"}, {"短片", "/movie_bt_tags/dp"}, {"科幻", "/movie_bt_tags/kh"}, {"纪录片", "/movie_bt_tags/jlpp"}, {"西部", "/movie_bt_tags/xb"}, {"运动", "/movie_bt_tags/yd"}, {"音乐", "/movie_bt_tags/yy"}}) {
            cls.add(new Filter.Value(p[0], p[1]));
        }
        group.add(new Filter("class", "类型", cls));

        LinkedHashMap<String, List<Filter>> filters = new LinkedHashMap<>();
        for (String u : CAT_URLS) filters.put(u, group);
        return filters;
    }

    // JS 一级/搜索 卡片：.bt_img 容器 + .dytit 标题 + img.lazy data-original 封面 + .jidi 备注
    // 参数用 Element（Jsoup 里 Document 是 Element 的子类，首页/搜索页都能直接传入）
    private void parseCards(Element root, List<Vod> list) {
        for (Element div : root.select(".bt_img.mi_ne_kd > ul > li")) {
            String id = div.select(".dytit > a").attr("href");
            String name = div.select(".dytit > a").text();
            if (id.isEmpty() || name.isEmpty()) continue;
            String pic = div.select("img").attr("data-original");
            if (pic.isEmpty()) pic = div.select("img").attr("src");
            String remark = div.select(".jidi").text();
            if (remark.isEmpty()) remark = div.select(".hdinfo > span").text();
            list.add(new Vod(id, name, pic, remark));
        }
    }

    // JS lazy 分支 a 的解码：去掉中间 7 个字符
    private String decodeStr(String s) {
        int cut = (s.length() - 7) / 2;
        return s.substring(0, cut) + s.substring(cut + 7);
    }

    // JS lazy 分支 b 的 AES 解密（key/iv 来自原作者对站点的逆向）
    private String dncry(String data) throws Exception {
        return AESEncryption.decrypt(data, "336460fdcb76a597", "1234567890983456", AESEncryption.CBC_PKCS_7_PADDING);
    }

    // 视频源专用请求头（跨域播放场景）
    private Map<String, String> getVideoHeader(String url) {
        Map<String, String> header = new LinkedHashMap<>();
        header.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36");
        header.put("Referer", siteUrl + "/");
        return header;
    }

    private String text(Element root, String css) {
        Element e = root.selectFirst(css);
        return e == null ? "" : e.text().trim();
    }

    private String value(Map<String, String> extend, String key) {
        return extend == null || extend.get(key) == null ? "" : extend.get(key);
    }

    private int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}

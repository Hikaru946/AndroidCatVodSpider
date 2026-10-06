package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;
import android.util.Base64;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Filter;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Util;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LIBVIO (www.libvio.to) —— 苹果CMS(stui模板)影视站爬虫，学习用示例。
 *
 * 站点结构（2026-10 实测）：
 *   分类导航      /type/{id}.html                     id: 1电影 2剧集 4番剧 15日韩 16欧美
 *   列表页       /show/{tid}-{area}-{by}-空-{lang}-空-空-空-{page}-空-空-{year}.html（12段'-'拼接）
 *   详情页       /detail/{vid}.html
 *   播放页       /w/{vid}-{sid}-{nid}.html           sid=线路 nid=该线路内第几集
 *   搜索         /search/{关键词}-------------.html
 *   播放器配置    播放页内联脚本 player_aaaa={...}，encrypt=1/2 为 urlencode/base64 编码，
 *               部分线路 url 是站方自定义加密串，由其网页播放器在线解析（爬虫返回 parse=1 交给App嗅探）
 */
public class Libvio extends Spider {

    // ext 模式：域名是实例变量而非常量。TVBox 配置里站点加 "ext": "https://新域名" 即可
    // 覆盖默认域名，站点换域名时只改配置、不用重新打包 jar
    private String siteUrl = "https://www.libvio.to";

    @Override
    public void init(Context context, String extend) {
        if (extend != null && extend.startsWith("http")) siteUrl = extend.replaceAll("/+$", "");
    }

    // 列表页 12 段 URL 中会用到的段下标（从 0 数）
    private static final int SEG_AREA = 1, SEG_BY = 2, SEG_LANG = 4, SEG_PAGE = 8, SEG_YEAR = 11;

    // 播放页内联脚本中的播放器配置（扁平 JSON，无嵌套花括号）
    private static final Pattern regexPlayer = Pattern.compile("player_aaaa\\s*=\\s*(\\{[^}]+\\})");
    private static final Pattern regexJsonField(String field) {
        return Pattern.compile("\"" + field + "\":\"?(.*?)\"?[,}]");
    }

    private Map<String, String> getHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", Util.CHROME);
        headers.put("Referer", siteUrl + "/");
        return headers;
    }

    /* ==================== 首页 ==================== */

    @Override
    public String homeContent(boolean filter) {
        List<Class> classes = new ArrayList<>();
        List<Vod> list = new ArrayList<>();
        Document doc = Jsoup.parse(OkHttp.string(siteUrl, getHeaders()));
        // 从导航栏解析分类（桌面/移动两套菜单会重复，用 LinkedHashSet 按 id 去重）
        Set<String> seen = new LinkedHashSet<>();
        for (Element a : doc.select("a[href^=/type/]")) {
            String id = a.attr("href").replaceAll("\\D", "");
            String name = a.text().trim();
            if (id.isEmpty() || name.isEmpty() || !seen.add(id)) continue;
            classes.add(new Class(id, name));
        }
        // 首页第一个板块作为推荐列表
        list.addAll(parseVodList(doc));
        return Result.string(classes, list, filterMap());
    }

    @Override
    public String homeVideoContent() {
        Document doc = Jsoup.parse(OkHttp.string(siteUrl, getHeaders()));
        List<Vod> list = parseVodList(doc);
        return list.isEmpty() ? "" : Result.string(list.get(0));
    }

    /* ==================== 分类列表 ==================== */

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        // 按站点规律拼 12 段 URL；未选筛选项的段留空字符串
        String[] seg = new String[12];
        Arrays.fill(seg, "");
        seg[0] = tid;
        seg[SEG_AREA] = URLEncoder.encode(value(extend, "area"));
        seg[SEG_BY] = value(extend, "by").isEmpty() ? "time" : value(extend, "by");
        seg[SEG_LANG] = URLEncoder.encode(value(extend, "lang"));
        seg[SEG_PAGE] = pg == null || pg.isEmpty() ? "1" : pg;
        seg[SEG_YEAR] = value(extend, "year");
        String target = siteUrl + "/show/" + TextUtils.join("-", seg) + ".html";

        Document doc = Jsoup.parse(OkHttp.string(target, getHeaders()));
        List<Vod> list = parseVodList(doc);

        // 总页数取自分页条"尾页"链接里的页码段，取不到就当只有一页
        int page = parseInt(pg, 1), count = page;
        Element last = doc.selectFirst("a:contains(尾页)");
        if (last != null) {
            String href = last.attr("href").replace(".html", "");
            String[] parts = href.substring(href.lastIndexOf('/') + 1).split("-");
            if (parts.length > SEG_PAGE) count = parseInt(parts[SEG_PAGE], page);
        }
        return Result.get().vod(list).page(page, count, 12, count * 12).string();
    }

    /* ==================== 详情 ==================== */

    @Override
    public String detailContent(List<String> ids) {
        String id = ids.get(0); // 形如 /detail/6023.html
        Document doc = Jsoup.parse(OkHttp.string(siteUrl + id, getHeaders()));

        Vod vod = new Vod();
        vod.setVodId(id);
        vod.setVodName(text(doc, "h1.title"));
        // 封面：懒加载图在 data-original 属性里
        Element pic = doc.selectFirst("#js-poster-img, .stui-content__thumb .lazyload, img.lazyload");
        if (pic != null) vod.setVodPic(pic.hasAttr("data-original") ? pic.attr("data-original") : pic.attr("src"));
        // 信息条顺序：类型, 地区, 年份, 上映日期, 集数, 更新时间, 主演：…, 导演：…
        List<String> meta = doc.select(".vod-meta span.meta-item").eachText();
        if (meta.size() > 0) vod.setTypeName(meta.get(0));
        if (meta.size() > 1) vod.setVodArea(meta.get(1));
        if (meta.size() > 2) vod.setVodYear(meta.get(2));
        for (String m : meta) {
            if (m.startsWith("主演：")) vod.setVodActor(m.substring(3));
            else if (m.startsWith("导演：")) vod.setVodDirector(m.substring(3));
        }
        // 简介：完整版藏在隐藏的 .detail-content，展开前的短版在 .detail-sketch
        String content = text(doc, ".vod-desc .detail-content");
        if (content.isEmpty()) content = text(doc, ".vod-desc .detail-sketch");
        vod.setVodContent(content);

        // 选集：详情页只给入口链接，完整线路列表在播放页 ul.stui-play__list
        // 每条线路再请求一次它的第一集页面，解析 ul.stui-content__playlist 得到该线路全部集数
        Map<String, List<String>> routes = new LinkedHashMap<>();
        String entry = path(firstPlayLink(doc));      // /w/{vid}-{sid}-{nid}.html
        if (!entry.isEmpty()) {
            String vid = entry.split("/")[2].split("-")[0];
            Document play = Jsoup.parse(OkHttp.string(siteUrl + entry, getHeaders()));
            // 线路去重按 sid，同时记下链接文本（网盘线路用它当集名）
            Map<String, String> sids = new LinkedHashMap<>();
            for (Element a : play.select("ul.stui-play__list a[href*=/w/]")) {
                String href = path(a.attr("href"));
                String sid = href.split("/")[2].split("-")[1];
                if (!sids.containsKey(sid)) sids.put(sid, a.text().trim());
            }
            int no = 1;
            for (Map.Entry<String, String> s : sids.entrySet()) {
                List<String> episodes = new ArrayList<>();
                String routePage = "/w/" + vid + "-" + s.getKey() + "-1.html";
                Document route = Jsoup.parse(OkHttp.string(siteUrl + routePage, getHeaders()));
                for (Element a : route.select("ul.stui-content__playlist a[href*=/w/]")) {
                    episodes.add(a.text().trim() + "$" + path(a.attr("href")));
                }
                // 网盘类线路没有分集列表，整条线路就是一集"全集"
                if (episodes.isEmpty()) episodes.add(s.getValue() + "$" + routePage);
                routes.put("线路" + no++, episodes);
            }
        }
        if (!routes.isEmpty()) {
            vod.setVodPlayFrom(TextUtils.join("$$$", routes.keySet()));
            List<String> all = new ArrayList<>();
            for (List<String> eps : routes.values()) all.add(TextUtils.join("#", eps));
            vod.setVodPlayUrl(TextUtils.join("$$$", all));
        }
        return Result.string(vod);
    }

    /* ==================== 搜索 ==================== */

    @Override
    public String searchContent(String key, boolean quick) {
        String target = siteUrl + "/search/" + URLEncoder.encode(key).replace("+", "%20") + "-------------.html";
        Document doc = Jsoup.parse(OkHttp.string(target, getHeaders()));
        return Result.string(parseVodList(doc));
    }

    /* ==================== 播放 ==================== */

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        // id 是播放页链接 /w/{vid}-{sid}-{nid}.html
        String html = OkHttp.string(siteUrl + id, getHeaders());
        Matcher m = regexPlayer.matcher(html);
        String url = "";
        String encrypt = "0";
        if (m.find()) {
            String json = m.group(1);
            url = findField(json, "url");
            encrypt = findField(json, "encrypt").replaceAll("[^0-9]", "");
        }
        // 苹果CMS标准编码：encrypt=1 urlencode，encrypt=2 base64(urlencode)，与站方 player.js 一致
        if ("1".equals(encrypt)) url = unescape(url);
        else if ("2".equals(encrypt)) url = unescape(new String(Base64.decode(url, Base64.DEFAULT)));
        url = url.replace("\\/", "/");
        // 网盘分享链接（夸克等）直接交给App的网盘模块处理
        if (url.startsWith("http") && url.contains("pan.")) {
            return Result.get().url(url).header(getHeaders()).string();
        }
        // 其余线路的地址是站方自定义加密串，交给播放页的网页播放器解析，App 嗅探真实视频流
        return Result.get().url(siteUrl + id).parse().header(getHeaders()).string();
    }

    /* ==================== 工具方法 ==================== */

    // 列表页/搜索页通用的视频卡片：div.stui-vodlist__box
    private List<Vod> parseVodList(Document doc) {
        List<Vod> list = new ArrayList<>();
        for (Element box : doc.select("div.stui-vodlist__box")) {
            Element a = box.selectFirst("a.stui-vodlist__thumb");
            if (a == null) continue;
            String id = a.attr("href");                       // /detail/xxxx.html
            String name = a.attr("title");
            String pic = a.attr("data-original");
            String remark = box.select("span.pic-text").text(); // 备注如"第10集/已完结"
            if (name.isEmpty()) continue;
            list.add(new Vod(id, name, pic, remark));
        }
        return list;
    }

    // 分类筛选组（取自列表页筛选条实测值），Value(n, v) 的 v 直接填进 12 段 URL 对应的段
    // 结构：{ 分类id: [排序, 地区, 语言, 年份] }
    private LinkedHashMap<String, List<Filter>> filterMap() {
        List<Filter.Value> by = new ArrayList<>();
        by.add(new Filter.Value("全部", ""));
        by.add(new Filter.Value("时间", "time"));
        by.add(new Filter.Value("人气", "hits"));
        by.add(new Filter.Value("评分", "score"));

        List<Filter.Value> area = new ArrayList<>();
        area.add(new Filter.Value("全部", ""));
        for (String v : new String[]{"中国大陆", "中国香港", "中国台湾", "美国", "法国", "英国", "日本", "韩国", "德国", "泰国", "印度", "意大利", "西班牙", "加拿大", "其他"}) {
            area.add(new Filter.Value(v, v));
        }

        List<Filter.Value> lang = new ArrayList<>();
        lang.add(new Filter.Value("全部", ""));
        for (String v : new String[]{"国语", "英语", "粤语", "闽南语", "韩语", "日语", "法语", "德语", "其它"}) {
            lang.add(new Filter.Value(v, v));
        }

        List<Filter.Value> year = new ArrayList<>();
        year.add(new Filter.Value("全部", ""));
        for (int y = 2026; y >= 2015; y--) year.add(new Filter.Value(String.valueOf(y), String.valueOf(y)));

        List<Filter> group = Arrays.asList(
                new Filter("by", "排序", by),
                new Filter("area", "地区", area),
                new Filter("lang", "语言", lang),
                new Filter("year", "年份", year));
        LinkedHashMap<String, List<Filter>> filters = new LinkedHashMap<>();
        for (String tid : new String[]{"1", "2", "4", "15", "16"}) filters.put(tid, group);
        return filters;
    }

    private String firstPlayLink(Document doc) {
        Element a = doc.selectFirst(".stui-content__playlist a[href*=/w/]");
        if (a == null) a = doc.selectFirst("a[href*=/w/]");
        return a == null ? "" : a.attr("href");
    }

    // 站内链接有时是完整 URL 有时是路径，统一归一化成 /xxx 路径
    private String path(String href) {
        if (href.startsWith("http")) href = href.replaceFirst("^https?://[^/]+", "");
        return href;
    }

    private String findField(String json, String field) {
        Matcher m = regexJsonField(field).matcher(json);
        return m.find() ? m.group(1) : "";
    }

    // JS unescape：处理 %uXXXX 与 %XX
    private String unescape(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); ) {
            char c = s.charAt(i);
            if (c == '%' && i + 6 <= s.length() && s.charAt(i + 1) == 'u') {
                try {
                    out.append((char) Integer.parseInt(s.substring(i + 2, i + 6), 16));
                    i += 6;
                    continue;
                } catch (NumberFormatException ignored) {
                }
            }
            if (c == '%' && i + 3 <= s.length()) {
                try {
                    out.append((char) Integer.parseInt(s.substring(i + 1, i + 3), 16));
                    i += 3;
                    continue;
                } catch (NumberFormatException ignored) {
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private String text(Document doc, String css) {
        Element e = doc.selectFirst(css);
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

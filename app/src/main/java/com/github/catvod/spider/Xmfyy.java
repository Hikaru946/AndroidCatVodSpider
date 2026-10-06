package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;

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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 小蜜蜂影院 (www.xmfyy.com) —— 自建模板（底层苹果CMS数据），学习用示例。
 * 与 Libvio.java 对照阅读：套路相同（五个方法 + Jsoup 选择器），细节不同。
 *
 * 站点结构（2026-10 实测）：
 *   首页         /                        卡片 div.vod-card（封面 img + p.vod-title + p.vod-subtitle）
 *   列表页       /filter?channel=&type=&area=&year=&sort=&page=
 *                channel: 1电视剧 2电影 3动漫 4综艺 5短剧（type 数字按频道独立编号）
 *                sort: hot/time/score；总页数藏在 div#pageData 的 data-totalpages 属性里
 *   详情页       /detail/{vid}.html       元信息在 .detail-meta-grid 的 label/value 对，
 *                封面与简介直接取 og:image / og:description 两行 meta 标签
 *   播放页       /vodplay/{vid}-{线路}-{集}.html
 *                页面不含真实地址！前端 JS 调接口：GET /api/play-url?vodId=&playFrom=&index=
 *                返回 {"code":200,"mode":"native","url":"https://.../index.m3u8"}
 *   搜索         /search?keyword={词}&page={页}
 */
public class Xmfyy extends Spider {

    // ext 模式：域名是实例变量而非常量。配置里 "ext": "https://新域名" 可覆盖默认值，换域名零重打包
    private String siteUrl = "https://www.xmfyy.com";

    // 播放页链接的三段结构：/vodplay/{vid}-{from}-{index}.html
    private static final Pattern regexPlay = Pattern.compile("/vodplay/(\\d+)-([a-zA-Z0-9]+)-(\\d+)\\.html");

    // 线路 slug → 显示名（实测自播放页的线路切换按钮文字）
    private static final Map<String, String> FROM_NAMES = new LinkedHashMap<>();

    static {
        FROM_NAMES.put("qiyi", "爱奇艺线路");
        FROM_NAMES.put("youku", "优酷官方线路");
        FROM_NAMES.put("co", "自建线路");
        FROM_NAMES.put("bfzym3u8", "普通线路二");
        FROM_NAMES.put("jsm3u8", "普通线路一");
    }

    // 各频道的"类型"筛选值："名称:ID" 逗号分隔（实测自每个频道筛选条，ID 按频道独立编号）
    private static final String[] TYPES_BY_CHANNEL = {
            "剧情:7,古装:9,战争:10,谍战:11,爱情:12,罪案:13,悬疑:14,家庭:15,军旅:16,喜剧:17,都市:18,武侠:19,言情:20,偶像:21,青春:22,农村:23,穿越:24,奇幻:25,历史:26,年代:27,科幻:28,生活:29",
            "动作:43,喜剧:44,爱情:45,科幻:46,恐怖:47,剧情:48,战争:49,犯罪:50,惊悚:51,冒险:52,悬疑:53,动画:54,武侠:55,古装:56,历史:57,传记:58,纪录片:59",
            "热血:60,恋爱:61,校园:62,搞笑:63,机甲:64,神魔:65,竞技:66,冒险:67,治愈:68,百合:69,萝莉:70,后宫:71,励志:72,泡面番:73,国产动漫:74,日本动漫:75,欧美动漫:76",
            "选秀:77,情感:78,访谈:79,播报:80,旅游:81,音乐:82,美食:83,纪实:84,曲艺:85,游戏:86,亲子:87,职场:88,脱口秀:89,真人秀:90,晚会:91",
            ""
    };

    @Override
    public void init(Context context, String extend) {
        // 配置里 ext 填了新域名就覆盖默认值；末尾多余斜杠去掉，防止拼出 // 路径
        if (extend != null && extend.startsWith("http")) siteUrl = extend.replaceAll("/+$", "");
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
        // 频道固定 5 个，id 与 /filter?channel= 一一对应
        String[] names = {"电视剧", "电影", "动漫", "综艺", "短剧"};
        List<Class> classes = new ArrayList<>();
        for (int i = 0; i < names.length; i++) classes.add(new Class(String.valueOf(i + 1), names[i]));

        Document doc = Jsoup.parse(OkHttp.string(siteUrl, getHeaders()));
        // 首页第一个板块（精选推荐）作为推荐列表
        List<Vod> list = new ArrayList<>();
        Element first = doc.selectFirst("div.vod-section");
        if (first != null) list.addAll(parseVodCards(first));
        return Result.string(classes, list, filterMap());
    }

    @Override
    public String homeVideoContent() {
        Document doc = Jsoup.parse(OkHttp.string(siteUrl, getHeaders()));
        Element first = doc.selectFirst("div.vod-section");
        List<Vod> list = first == null ? new ArrayList<>() : parseVodCards(first);
        return list.isEmpty() ? "" : Result.string(list.get(0));
    }

    /* ==================== 分类列表 ==================== */

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        // 站点自身的链接就是全参数风格，空选项留空即可
        String sort = value(extend, "sort");
        String target = siteUrl + "/filter?channel=" + tid
                + "&type=" + value(extend, "type")
                + "&area=" + URLEncoder.encode(value(extend, "area"))
                + "&year=" + value(extend, "year")
                + "&sort=" + (sort.isEmpty() ? "hot" : sort)
                + "&page=" + (pg == null || pg.isEmpty() ? "1" : pg);
        Document doc = Jsoup.parse(OkHttp.string(target, getHeaders()));
        List<Vod> list = parseVodCards(doc);

        // 总页数直接读隐藏元素 <div id="pageData" data-totalpages="1658">
        int page = parseInt(pg, 1), count = page;
        Element data = doc.selectFirst("#pageData");
        if (data != null) count = parseInt(data.attr("data-totalpages"), page);
        return Result.get().vod(list).page(page, count, 24, count * 24).string();
    }

    /* ==================== 详情 ==================== */

    @Override
    public String detailContent(List<String> ids) {
        String id = ids.get(0); // /detail/638.html
        Document doc = Jsoup.parse(OkHttp.string(siteUrl + id, getHeaders()));

        Vod vod = new Vod();
        vod.setVodId(id);
        vod.setVodName(text(doc, "h1.detail-title"));
        // 封面与简介：站方已放在 og:* meta 标签里，是最稳定的取法
        vod.setVodPic(attr(doc, "meta[property=og:image]", "content"));
        vod.setVodContent(attr(doc, "meta[property=og:description]", "content"));
        // 标签行（如"古装爱情,复仇爽剧,内地剧"）当作分类
        List<String> tags = doc.select(".detail-tags .tag-pill").eachText();
        if (!tags.isEmpty()) vod.setTypeName(TextUtils.join(",", tags));
        // 元信息表：label/value 对，按中文标签分发到 Vod 字段
        for (Element item : doc.select(".detail-meta-grid .meta-item")) {
            String label = text(item, ".meta-label");
            String val = text(item, ".meta-value");
            if (val.isEmpty()) continue;
            switch (label) {
                case "主演：":
                    vod.setVodActor(val);
                    break;
                case "导演：":
                    vod.setVodDirector(val);
                    break;
                case "地区：":
                    vod.setVodArea(val);
                    break;
                case "年份：":
                    vod.setVodYear(val);
                    break;
                case "备注：":
                    vod.setVodRemarks(val);
                    break;
            }
        }

        // 选集：详情页列出全部线路的分集链接，按线路 slug 分组、按集数排序
        // 立即播放按钮与第一集重复，用 TreeMap 按 index 存放天然去重
        Map<String, TreeMap<Integer, String>> routes = new LinkedHashMap<>();
        for (Element a : doc.select("a[href*=/vodplay/]")) {
            Matcher m = regexPlay.matcher(a.attr("href"));
            if (!m.find()) continue;
            String from = m.group(2);
            TreeMap<Integer, String> eps = routes.get(from);
            if (eps == null) routes.put(from, eps = new TreeMap<>());
            eps.put(Integer.parseInt(m.group(3)), a.text().trim() + "$" + m.group(0));
        }
        if (!routes.isEmpty()) {
            List<String> fromNames = new ArrayList<>(), playUrls = new ArrayList<>();
            for (Map.Entry<String, TreeMap<Integer, String>> r : routes.entrySet()) {
                String name = FROM_NAMES.containsKey(r.getKey()) ? FROM_NAMES.get(r.getKey()) : r.getKey();
                fromNames.add(name);
                List<String> eps = new ArrayList<>();
                for (String ep : r.getValue().values()) eps.add(ep);
                playUrls.add(TextUtils.join("#", eps));
            }
            vod.setVodPlayFrom(TextUtils.join("$$$", fromNames));
            vod.setVodPlayUrl(TextUtils.join("$$$", playUrls));
        }
        return Result.string(vod);
    }

    /* ==================== 搜索 ==================== */

    @Override
    public String searchContent(String key, boolean quick) {
        return search(key, "1");
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) {
        return search(key, pg == null || pg.isEmpty() ? "1" : pg);
    }

    private String search(String key, String pg) {
        String target = siteUrl + "/search?keyword=" + URLEncoder.encode(key) + "&page=" + pg;
        Document doc = Jsoup.parse(OkHttp.string(target, getHeaders()));
        // 搜索结果卡片与列表页不同：横向条目 .search-item
        List<Vod> list = new ArrayList<>();
        for (Element item : doc.select(".search-item")) {
            String href = attr(item, "a.search-item-title", "href");
            String name = text(item, "a.search-item-title");
            if (href.isEmpty() || name.isEmpty()) continue;
            String pic = attr(item, "a.search-item-poster img", "src");
            String remark = text(item, ".search-item-type");
            list.add(new Vod(href, name, pic, remark));
        }
        return Result.string(list);
    }

    /* ==================== 播放 ==================== */

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        // id = /vodplay/{vid}-{from}-{index}.html，页面本身不含地址：
        // 站方前端也是调 /api/play-url 异步拿真实 m3u8，爬虫直接调同一个接口
        Matcher m = regexPlay.matcher(id);
        if (!m.find()) return "";
        String api = siteUrl + "/api/play-url?vodId=" + m.group(1)
                + "&playFrom=" + m.group(2) + "&index=" + m.group(3);
        String body = OkHttp.string(api, getHeaders());
        String url = findField(body, "url").replace("\\/", "/");
        String code = findField(body, "code").replaceAll("[^0-9]", "");
        // code=200 且拿到直链 → 交给播放器直接播（parse 默认 0）
        if ("200".equals(code) && url.startsWith("http")) {
            return Result.get().url(url).header(getHeaders()).string();
        }
        // 兜底：把播放页丢给 App 的网页嗅探（对应站点返回 mode=iframe 的线路）
        return Result.get().url(siteUrl + id).parse().header(getHeaders()).string();
    }

    /* ==================== 工具方法 ==================== */

    // 首页/列表页通用的封面卡片：a[href=/detail/...] > div.vod-card
    // 从外层锚点向内找卡片（不用 Element.closest，工程里 Jsoup 1.15.3 没有该方法）
    private List<Vod> parseVodCards(Element root) {
        List<Vod> list = new ArrayList<>();
        for (Element a : root.select("a[href^=/detail/]")) {
            Element card = a.selectFirst("div.vod-card");
            if (card == null) continue;
            String name = text(card, "p.vod-title");
            if (name.isEmpty()) continue;
            String pic = attr(card, "img", "src");
            String remark = text(card, "p.vod-subtitle"); // 此站的副标题是演员名
            list.add(new Vod(a.attr("href"), name, pic, remark));
        }
        return list;
    }

    // 每个频道的筛选组：排序 / 类型 / 地区 / 年份
    private LinkedHashMap<String, List<Filter>> filterMap() {
        LinkedHashMap<String, List<Filter>> filters = new LinkedHashMap<>();
        String[] names = {"电视剧", "电影", "动漫", "综艺", "短剧"};
        for (int ch = 1; ch <= 5; ch++) {
            List<Filter> group = new ArrayList<>();
            // 排序
            List<Filter.Value> sorts = new ArrayList<>();
            sorts.add(new Filter.Value("热度", "hot"));
            sorts.add(new Filter.Value("时间", "time"));
            sorts.add(new Filter.Value("评分", "score"));
            group.add(new Filter("sort", "排序", sorts));
            // 类型（短剧频道没有）
            String raw = TYPES_BY_CHANNEL[ch - 1];
            if (!raw.isEmpty()) {
                List<Filter.Value> types = new ArrayList<>();
                types.add(new Filter.Value("全部", ""));
                for (String pair : raw.split(",")) {
                    int cut = pair.lastIndexOf(':');
                    types.add(new Filter.Value(pair.substring(0, cut), pair.substring(cut + 1)));
                }
                group.add(new Filter("type", "类型", types));
            }
            // 地区
            List<Filter.Value> areas = new ArrayList<>();
            areas.add(new Filter.Value("全部", ""));
            for (String v : new String[]{"大陆", "香港", "台湾", "韩国", "日本", "美国", "泰国", "法国", "英国", "德国", "印度", "其他"}) {
                areas.add(new Filter.Value(v, v));
            }
            group.add(new Filter("area", "地区", areas));
            // 年份
            List<Filter.Value> years = new ArrayList<>();
            years.add(new Filter.Value("全部", ""));
            for (int y = 2026; y >= 2018; y--) years.add(new Filter.Value(String.valueOf(y), String.valueOf(y)));
            group.add(new Filter("year", "年份", years));
            filters.put(String.valueOf(ch), group);
        }
        return filters;
    }

    private String findField(String json, String field) {
        Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*\"?([^\",}]+)").matcher(json);
        return m.find() ? m.group(1) : "";
    }

    private String text(Element root, String css) {
        Element e = root.selectFirst(css);
        return e == null ? "" : e.text().trim();
    }

    private String attr(Element root, String css, String key) {
        Element e = root.selectFirst(css);
        return e == null ? "" : e.attr(key);
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

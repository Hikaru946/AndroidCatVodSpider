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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 小黑米影视 (xiaoheimi.cc) —— 苹果CMS V10 + myui 模板，学习用示例。
 * 本文件的看点是 ext 模式：站点换域名时只需在 TVBox 配置里改 ext，不用重新打包。
 *
 * 站点结构（2026-10 实测）：
 *   分类列表  /index.php/vod/show/{area/地区}/{year/年份}/{by/排序}/id/{tid}/page/{页}.html
 *            （键值对路径段，可任意组合省略；与 libvio 的 12 段横杠、xmfyy 的查询参数并列第三种风格）
 *   详情页    /index.php/vod/detail/id/{vid}.html
 *   播放页    /index.php/vod/play/id/{vid}/sid/{线路}/{nid}/{集}.html
 *            内联脚本 player_aaaa，encrypt=0 即明文 m3u8 直链
 *   搜索      /index.php/vod/search/wd/{关键词}.html
 *
 * ext 用法：TVBox 配置里给站点加 "ext": "https://新域名"，init() 会用它覆盖默认 siteUrl
 */
public class XiaoHeimi extends Spider {

    // ext 模式的关键：不是 static final 常量，而是可以被 init() 覆盖的实例变量
    private String siteUrl = "https://xiaoheimi.cc";

    // 播放页内联脚本中的播放器配置（苹果CMS标准）
    private static final Pattern regexPlayer = Pattern.compile("player_aaaa\\s*=\\s*(\\{[^}]+\\})");

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
        Document doc = Jsoup.parse(OkHttp.string(siteUrl, getHeaders()));
        // 分类从导航栏解析（/vod/type/id/{n}.html），桌面/移动两套菜单按 id 去重
        List<Class> classes = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Element a : doc.select("a[href*=/vod/type/id/]")) {
            String id = a.attr("href").replaceAll("\\D", "");
            String name = a.text().trim();
            if (id.isEmpty() || name.isEmpty() || !seen.add(id)) continue;
            classes.add(new Class(id, name));
        }
        // 首页第一个版块作为推荐列表
        List<Vod> list = new ArrayList<>();
        Element first = doc.selectFirst("ul.myui-vodlist");
        if (first != null) list.addAll(parseVodList(first));
        return Result.string(classes, list, filterMap(classes));
    }

    @Override
    public String homeVideoContent() {
        Document doc = Jsoup.parse(OkHttp.string(siteUrl, getHeaders()));
        Element first = doc.selectFirst("ul.myui-vodlist");
        List<Vod> list = first == null ? new ArrayList<>() : parseVodList(first);
        return list.isEmpty() ? "" : Result.string(list.get(0));
    }

    /* ==================== 分类列表 ==================== */

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        // maccms V10 路由是"键/值"路径段，顺序无关，空的段直接省略
        StringBuilder target = new StringBuilder(siteUrl).append("/index.php/vod/show");
        String area = value(extend, "area");
        String year = value(extend, "year");
        String by = value(extend, "by");
        if (!area.isEmpty()) target.append("/area/").append(URLEncoder.encode(area));
        if (!year.isEmpty()) target.append("/year/").append(year);
        target.append("/by/").append(by.isEmpty() ? "time" : by);
        target.append("/id/").append(tid);
        target.append("/page/").append(pg == null || pg.isEmpty() ? "1" : pg).append(".html");

        Document doc = Jsoup.parse(OkHttp.string(target.toString(), getHeaders()));
        // 只解析第一个主列表：页面底部的"猜你喜欢"推荐区也是同样的卡片，整页解析会混入
        Element listEl = doc.selectFirst("ul.myui-vodlist");
        List<Vod> list = listEl == null ? new ArrayList<>() : parseVodList(listEl);

        // 总页数取分页条里出现的最大页码
        int page = parseInt(pg, 1), count = page;
        for (Element a : doc.select("a[href*=/page/]")) {
            Matcher m = Pattern.compile("/page/(\\d+)\\.html").matcher(a.attr("href"));
            if (m.find()) count = Math.max(count, Integer.parseInt(m.group(1)));
        }
        return Result.get().vod(list).page(page, count, Math.max(1, list.size()), count * Math.max(1, list.size())).string();
    }

    /* ==================== 详情 ==================== */

    @Override
    public String detailContent(List<String> ids) {
        String id = ids.get(0); // /index.php/vod/detail/id/{vid}.html
        Document doc = Jsoup.parse(OkHttp.string(siteUrl + id, getHeaders()));

        Vod vod = new Vod();
        vod.setVodId(id);
        vod.setVodName(text(doc, ".myui-content__detail h1.title"));
        // 封面在海报区：a.myui-vodlist__thumb 内层的 img 才带 data-original（此站没有 og: meta 可用）
        Element pic = doc.selectFirst(".myui-content__thumb a.myui-vodlist__thumb img");
        if (pic != null) vod.setVodPic(pic.hasAttr("data-original") ? pic.attr("data-original") : pic.attr("src"));
        // 信息区是若干 p.data 段落；注意"分类/地区/年份"共用一个段落，a 按序对应三者
        for (Element p : doc.select(".myui-content__detail p.data")) {
            String label = text(p, "span.text-muted");
            if (label.startsWith("分类")) {
                List<String> vals = p.select("a").eachText();
                if (vals.size() > 0) vod.setTypeName(vals.get(0));
                if (vals.size() > 1) vod.setVodArea(vals.get(1));
                if (vals.size() > 2) vod.setVodYear(vals.get(2));
            } else if (label.startsWith("更新")) {
                vod.setVodRemarks(text(p, "span.text-red"));
            } else if (label.startsWith("主演")) {
                vod.setVodActor(TextUtils.join(",", p.select("a").eachText()));
            } else if (label.startsWith("导演")) {
                vod.setVodDirector(TextUtils.join(",", p.select("a").eachText()));
            }
        }
        // 简介：#desc 里 .data 是完整版（默认隐藏），.sketch 是折叠短版
        String content = text(doc, "#desc .data");
        if (content.isEmpty()) content = text(doc, "#desc .sketch");
        vod.setVodContent(content);

        // 选集：tab 头 a[href=#playlistN] 是线路名，对应面板 div#playlistN 里是全集
        // 面板里每条链接自带 sid，直接从链接里取，与 tab 顺序一一对应
        List<String> fromNames = new ArrayList<>(), playUrls = new ArrayList<>();
        for (Element tab : doc.select("a[href^=#playlist]")) {
            String panelId = tab.attr("href").substring(1);
            Element panel = doc.selectFirst("div#" + panelId);
            if (panel == null) continue;
            List<String> eps = new ArrayList<>();
            for (Element a : panel.select("a[href*=/vod/play/]")) {
                eps.add(a.text().trim() + "$" + a.attr("href"));
            }
            if (eps.isEmpty()) continue;
            fromNames.add(tab.text().trim());
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
    public String searchContent(String key, boolean quick) {
        return search(key, "1");
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) {
        return search(key, pg == null || pg.isEmpty() ? "1" : pg);
    }

    private String search(String key, String pg) {
        String target = siteUrl + "/index.php/vod/search/wd/" + URLEncoder.encode(key) + ".html";
        if (!"1".equals(pg)) target = siteUrl + "/index.php/vod/search/wd/" + URLEncoder.encode(key) + "/page/" + pg + ".html";
        Document doc = Jsoup.parse(OkHttp.string(target, getHeaders()));
        // 搜索结果用的是 ul#searchList（media 布局），但卡片仍是 a.myui-vodlist__thumb
        Element listEl = doc.selectFirst("#searchList");
        return Result.string(listEl == null ? new ArrayList<>() : parseVodList(listEl));
    }

    /* ==================== 播放 ==================== */

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        // id = /index.php/vod/play/id/{vid}/sid/{sid}/nid/{nid}.html
        String html = OkHttp.string(siteUrl + id, getHeaders());
        Matcher m = regexPlayer.matcher(html);
        String url = "";
        String encrypt = "0";
        if (m.find()) {
            String json = m.group(1);
            url = findField(json, "url");
            encrypt = findField(json, "encrypt").replaceAll("[^0-9]", "");
        }
        // 苹果CMS标准编码：1 = urlencode，2 = base64(urlencode)，0 = 明文（本站实测为 0）
        if ("1".equals(encrypt)) url = unescape(url);
        else if ("2".equals(encrypt)) url = unescape(new String(Base64.decode(url, Base64.DEFAULT)));
        url = url.replace("\\/", "/");
        if (url.startsWith("http")) {
            return Result.get().url(url).header(getHeaders()).string();
        }
        // 兜底：交给 App 网页嗅探
        return Result.get().url(siteUrl + id).parse().header(getHeaders()).string();
    }

    /* ==================== 工具方法 ==================== */

    // 首页/列表/搜索通用的卡片：a.myui-vodlist__thumb（名称在 title 属性、封面在 data-original）
    private List<Vod> parseVodList(Element root) {
        List<Vod> list = new ArrayList<>();
        for (Element a : root.select("a.myui-vodlist__thumb")) {
            String id = a.attr("href");
            String name = a.attr("title").trim();
            if (!id.contains("/vod/detail/id/") || name.isEmpty()) continue;
            String pic = a.hasAttr("data-original") ? a.attr("data-original") : a.attr("src");
            String remark = a.select("span.pic-text").text();
            list.add(new Vod(id, name, pic, remark));
        }
        return list;
    }

    // 每个分类共用一套筛选：地区 / 年份 / 排序（值取自站点筛选条实测）
    private LinkedHashMap<String, List<Filter>> filterMap(List<Class> classes) {
        List<Filter> group = new ArrayList<>();
        List<Filter.Value> by = new ArrayList<>();
        by.add(new Filter.Value("时间", "time"));
        by.add(new Filter.Value("人气", "hits"));
        by.add(new Filter.Value("评分", "score"));
        group.add(new Filter("by", "排序", by));
        List<Filter.Value> areas = new ArrayList<>();
        areas.add(new Filter.Value("全部", ""));
        for (String v : new String[]{"中国大陆", "中国香港", "中国台湾", "美国", "英国", "法国", "德国", "日本", "韩国", "加拿大", "其他"}) {
            areas.add(new Filter.Value(v, v));
        }
        group.add(new Filter("area", "地区", areas));
        List<Filter.Value> years = new ArrayList<>();
        years.add(new Filter.Value("全部", ""));
        for (int y = 2026; y >= 2020; y--) years.add(new Filter.Value(String.valueOf(y), String.valueOf(y)));
        group.add(new Filter("year", "年份", years));

        LinkedHashMap<String, List<Filter>> filters = new LinkedHashMap<>();
        for (Class c : classes) filters.put(c.getTypeId(), group);
        return filters;
    }

    private String findField(String json, String field) {
        Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*\"?([^\",}]+)").matcher(json);
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

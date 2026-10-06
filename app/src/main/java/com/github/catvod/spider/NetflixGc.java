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
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 奈飞工厂 (www.netflixgc.com) —— 苹果CMS V10 + dsn2 自定义主题（DSL 主题家族），学习用示例。
 * 本文件的看点：列表页不是服务端渲染，而是前端调专用 JSON 接口——爬虫直接调同一个接口。
 *
 * 站点结构（2026-10 实测）：
 *   列表数据   POST /index.php/ds_api/vod
 *             参数(type/area/class/lang/letter/level/state/time/version/weekday/year/by/page)
 *             返回 {code:1, page, pagecount, limit, total, list:[{vod_id, vod_name, vod_pic, vod_remarks, ...}]}
 *   分类导航   /vodtype/{id}.html（列表页路径 /vodshow/{id}-{筛选}.html，仅作跳转，数据走接口）
 *   详情页     /voddetail/{vid}.html
 *             标题 h3.slide-info-title；信息在 li > em.cor4 标签对里；封面 .detail-pic img[data-src]
 *             线路 .anthology-tab a（名称）+ .anthology-list-box（各线路选集）
 *   播放页     /vodplay/{vid}-{sid}-{nid}.html
 *             内联 player_aaaa 为嵌套 JSON（含 vod_data），encrypt=2：base64 解码后是 urlencode 的直链
 *   搜索       /vodsearch/{关键词}-------------.html（服务端渲染，.detail-pic + .slide-info-title）
 */
public class NetflixGc extends Spider {

    // ext 模式：站点无验证，换域名只改配置（站点自身公告区列了多个备用域名，如 netflixgc.tv/.org/.net）
    private String siteUrl = "https://www.netflixgc.com";

    @Override
    public void init(Context context, String extend) {
        if (extend != null && extend.startsWith("http")) siteUrl = extend.replaceAll("/+$", "");
    }

    private Map<String, String> getHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", Util.CHROME);
        headers.put("Referer", siteUrl + "/");
        return headers;
    }

    /* ==================== 数据接口 ==================== */

    // 主题自带的列表接口：POST 表单返回 JSON，分类/筛选/翻页全走这里
    private JsonObject dsApi(String type, String by, String area, String year, String pg) throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("type", type);
        params.put("area", area);
        params.put("class", "");
        params.put("lang", "");
        params.put("letter", "");
        params.put("level", "0");
        params.put("state", "");
        params.put("time", "");
        params.put("version", "");
        params.put("weekday", "");
        params.put("year", year);
        params.put("by", by.isEmpty() ? "time" : by);
        params.put("page", pg == null || pg.isEmpty() ? "1" : pg);
        String body = OkHttp.post(siteUrl + "/index.php/ds_api/vod", params, getHeaders()).getBody();
        return JsonParser.parseString(body).getAsJsonObject();
    }

    // 接口 list 数组 → Vod 列表（字段是接口现成的，不用解析 HTML）
    private List<Vod> parseList(JsonObject d) {
        List<Vod> list = new ArrayList<>();
        JsonArray arr = d.has("list") && d.get("list").isJsonArray() ? d.getAsJsonArray("list") : new JsonArray();
        for (JsonElement e : arr) {
            JsonObject it = e.getAsJsonObject();
            String id = it.has("vod_id") ? "/voddetail/" + it.get("vod_id").getAsString() + ".html" : it.has("url") ? it.get("url").getAsString() : "";
            String name = it.has("vod_name") ? it.get("vod_name").getAsString() : "";
            if (id.isEmpty() || name.isEmpty()) continue;
            String pic = it.has("vod_pic") && !it.get("vod_pic").isJsonNull() ? it.get("vod_pic").getAsString() : "";
            String remark = it.has("vod_remarks") && !it.get("vod_remarks").isJsonNull() ? it.get("vod_remarks").getAsString() : "";
            list.add(new Vod(id, name, pic, remark));
        }
        return list;
    }

    /* ==================== 首页 ==================== */

    @Override
    public String homeContent(boolean filter) throws Exception {
        // 首页 HTML 只用来解析分类导航；影片列表走数据接口
        Document doc = Jsoup.parse(OkHttp.string(siteUrl, getHeaders()));
        List<Class> classes = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Element a : doc.select("a[href*=/vodtype/], a[href*=/vodshow/]")) {
            Matcher m = Pattern.compile("/vod(?:type|show)/(\\d+)").matcher(a.attr("href"));
            if (!m.find()) continue;
            String id = m.group(1);
            String name = a.text().trim();
            if (id.isEmpty() || name.isEmpty() || !seen.add(id)) continue;
            classes.add(new Class(id, name));
        }
        List<Vod> list = parseList(dsApi("", "", "", "", "1"));
        if (list.size() > 30) list = list.subList(0, 30);
        return Result.string(classes, list, filterMap(classes));
    }

    @Override
    public String homeVideoContent() throws Exception {
        List<Vod> list = parseList(dsApi("", "", "", "", "1"));
        return list.isEmpty() ? "" : Result.string(list.get(0));
    }

    /* ==================== 分类列表 ==================== */

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        JsonObject d = dsApi(tid, value(extend, "by"), value(extend, "area"), value(extend, "year"), pg);
        List<Vod> list = parseList(d);
        // 接口直接给了 page/pagecount/limit/total，不用解析分页条
        int page = d.has("page") ? d.get("page").getAsInt() : parseInt(pg, 1);
        int count = d.has("pagecount") ? d.get("pagecount").getAsInt() : page;
        int limit = d.has("limit") && !d.get("limit").isJsonNull() ? d.get("limit").getAsInt() : Math.max(1, list.size());
        int total = d.has("total") ? d.get("total").getAsInt() : count * limit;
        return Result.get().vod(list).page(page, count, limit, total).string();
    }

    /* ==================== 详情 ==================== */

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String id = ids.get(0); // /voddetail/{vid}.html
        Document doc = Jsoup.parse(OkHttp.string(siteUrl + id, getHeaders()));

        Vod vod = new Vod();
        vod.setVodId(id);
        vod.setVodName(text(doc, "h3.slide-info-title"));
        Element pic = doc.selectFirst(".detail-pic img");
        if (pic == null) pic = doc.selectFirst("img[data-src]");
        if (pic != null) vod.setVodPic(pic.hasAttr("data-src") ? pic.attr("data-src") : pic.attr("src"));
        // 信息区：li > em.cor4 标签对；主演/导演的值是 a 列表，年份/地区的值直接跟在标签后
        for (Element li : doc.select("li:has(em.cor4)")) {
            String label = text(li, "em");
            if (label.contains("主演")) {
                vod.setVodActor(TextUtils.join(",", li.select("a").eachText()));
            } else if (label.contains("导演")) {
                vod.setVodDirector(TextUtils.join(",", li.select("a").eachText()));
            } else if (label.contains("年份")) {
                vod.setVodYear(li.ownText().trim());
            } else if (label.contains("地区")) {
                vod.setVodArea(li.ownText().trim());
            } else if (label.contains("类型")) {
                vod.setTypeName(li.ownText().trim());
            }
        }
        // 简介：整页 meta description 就是纯剧情简介
        Element meta = doc.selectFirst("meta[name=description]");
        if (meta != null) vod.setVodContent(meta.attr("content").trim());

        // 选集：.anthology-tab a 是线路名（按顺序），.anthology-list-box 是各线路的集数列表
        List<Element> tabs = doc.select(".anthology-tab a");
        List<Element> boxes = doc.select("div.anthology-list-box");
        List<String> fromNames = new ArrayList<>(), playUrls = new ArrayList<>();
        for (int i = 0; i < boxes.size(); i++) {
            List<String> eps = new ArrayList<>();
            for (Element a : boxes.get(i).select("ul.anthology-list-play a[href*=/vodplay/]")) {
                eps.add(a.text().trim() + "$" + a.attr("href"));
            }
            if (eps.isEmpty()) continue;
            String name = i < tabs.size() ? tabs.get(i).ownText().replace("\u00a0", "").trim() : "";
            fromNames.add(name.isEmpty() ? "线路" + (i + 1) : name);
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
        // 搜索页是服务端渲染的（与列表页不同），直接解析 HTML 卡片
        String target = siteUrl + "/vodsearch/" + URLEncoder.encode(key) + "-------------.html";
        Document doc = Jsoup.parse(OkHttp.string(target, getHeaders()));
        List<Vod> list = new ArrayList<>();
        for (Element a : doc.select(".search-list a[href*=/voddetail/]")) {
            String id = a.attr("href");
            String name = text(a, "h3.slide-info-title");
            if (!id.contains("/voddetail/") || name.isEmpty()) continue;
            Element img = a.selectFirst("img");
            String pic = img == null ? "" : (img.hasAttr("data-src") ? img.attr("data-src") : img.attr("src"));
            list.add(new Vod(id, name, pic, text(a, ".public-prt")));
        }
        return Result.string(list);
    }

    /* ==================== 播放 ==================== */

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        // id = /vodplay/{vid}-{sid}-{nid}.html
        String html = OkHttp.string(siteUrl + id, getHeaders());
        JsonObject player = extractPlayer(html);
        String url = player.has("url") ? player.get("url").getAsString() : "";
        String encrypt = player.has("encrypt") ? player.get("encrypt").getAsString() : "0";
        // 苹果CMS标准编码：1 = urlencode，2 = base64(urlencode)（本站实测为 2）
        if ("1".equals(encrypt)) url = unescape(url);
        else if ("2".equals(encrypt)) url = unescape(new String(android.util.Base64.decode(url, android.util.Base64.DEFAULT)));
        url = url.replace("\\/", "/");
        if (url.startsWith("http")) {
            return Result.get().url(url).header(getHeaders()).string();
        }
        return Result.get().url(siteUrl + id).parse().header(getHeaders()).string();
    }

    // player_aaaa 是嵌套 JSON（含 vod_data），普通正则会截断，必须按括号配对提取
    private JsonObject extractPlayer(String html) throws Exception {
        int key = html.indexOf("player_aaaa");
        if (key < 0) throw new Exception("播放页未找到 player_aaaa");
        int start = html.indexOf('{', key);
        boolean inStr = false;
        int depth = 0;
        for (int i = start; i < html.length(); i++) {
            char c = html.charAt(i);
            if (inStr) {
                if (c == '\\') i++;
                else if (c == '"') inStr = false;
            } else if (c == '"') {
                inStr = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                if (--depth == 0) return JsonParser.parseString(html.substring(start, i + 1)).getAsJsonObject();
            }
        }
        throw new Exception("player_aaaa 括号不配对");
    }

    /* ==================== 工具方法 ==================== */

    // 分类筛选：排序 / 地区 / 年份（ds_api 支持苹果CMS标准筛选参数）
    private LinkedHashMap<String, List<Filter>> filterMap(List<Class> classes) {
        List<Filter> group = new ArrayList<>();
        List<Filter.Value> by = new ArrayList<>();
        by.add(new Filter.Value("时间", "time"));
        by.add(new Filter.Value("人气", "hits"));
        by.add(new Filter.Value("评分", "score"));
        group.add(new Filter("by", "排序", by));
        List<Filter.Value> areas = new ArrayList<>();
        areas.add(new Filter.Value("全部", ""));
        for (String v : new String[]{"中国大陆", "中国香港", "中国台湾", "美国", "英国", "法国", "德国", "日本", "韩国", "泰国", "印度", "其他"}) {
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

    // JS unescape：处理 %uXXXX 与 %XX（encrypt=2 解码的第二步）
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

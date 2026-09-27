package com.heartpilot.module.agent.service.impl;

import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.heartpilot.common.exception.ApiException;
import com.heartpilot.module.agent.entity.AgentTask;
import com.heartpilot.module.agent.service.AgentTaskInputService;
import com.heartpilot.module.user.entity.RelationshipProfile;
import com.heartpilot.module.user.repository.ProfileRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 规范化任务输入，并拼装面向用户的候选计划预览。
 * Normalizes task input and composes the user-facing plan preview.
 *
 * 职责：
 * - 地区校验与解析（省/市 → 完整 searchRegion）、城市下拉选项（调高德行政区接口）
 * - 预算规范化（去尾零、非负校验）、问题列表合并/去重/从修改说明中抽问题
 * - 幂等键校验、parameters JSON 的读写、关系档案偏好拼装进检索上下文
 */
@Service
public class AgentTaskInputServiceImpl implements AgentTaskInputService {
    /** 内置已知城市列表，用于在用户未选城市时从目标文本里粗匹配城市 */
    private static final List<String> KNOWN_CITIES =
            List.of(
                    "北京", "上海", "天津", "重庆", "广州", "深圳", "南宁", "柳州", "桂林", "成都", "杭州", "南京", "武汉",
                    "长沙", "西安", "郑州", "济南", "青岛", "大连", "沈阳", "长春", "哈尔滨", "昆明", "贵阳", "海口", "三亚",
                    "福州", "厦门", "南昌", "合肥", "苏州", "无锡", "宁波", "温州", "石家庄", "太原", "兰州", "西宁", "银川",
                    "乌鲁木齐", "呼和浩特", "拉萨", "香港", "澳门");

    /** 关系档案 Repository，读取用户偏好与关系边界，注入检索上下文 */
    private final ProfileRepository profiles;
    /** parameters JSON 读写工具 */
    private final ObjectMapper json;
    /** 高德 Web 服务 Key，用于城市下拉与地点检索 */
    private final String amapKey;
    /** 合法省级行政区列表，校验用户选择 */
    private static final List<String> PROVINCES =
            List.of(
                    "北京市",
                    "天津市",
                    "上海市",
                    "重庆市",
                    "河北省",
                    "山西省",
                    "辽宁省",
                    "吉林省",
                    "黑龙江省",
                    "江苏省",
                    "浙江省",
                    "安徽省",
                    "福建省",
                    "江西省",
                    "山东省",
                    "河南省",
                    "湖北省",
                    "湖南省",
                    "广东省",
                    "海南省",
                    "四川省",
                    "贵州省",
                    "云南省",
                    "陕西省",
                    "甘肃省",
                    "青海省",
                    "台湾省",
                    "内蒙古自治区",
                    "广西壮族自治区",
                    "西藏自治区",
                    "宁夏回族自治区",
                    "新疆维吾尔自治区",
                    "香港特别行政区",
                    "澳门特别行政区");

    public AgentTaskInputServiceImpl(
            ProfileRepository profiles,
            ObjectMapper json,
            @Value("${AMAP_MAPS_API_KEY:}") String amapKey) {
        this.profiles = profiles;
        this.json = json;
        this.amapKey = amapKey;
    }

    /**
     * 拼装用户确认阶段看到的候选计划预览（Markdown）。
     * 包含目标、地点范围与预算、累计修改要求、待解决问题、分类检索结果与下一步提示；
     * 检索结果过长时截断到 5000 字符。
     */
    @Override
    public String buildPreview(
            AgentTask task,
            String city,
            String budget,
            String places,
            List<String> questions,
            List<String> revisions) {
        return """
                ## 当前候选计划

                ### 目标
                %s

                ### 地点范围
                - 城市：%s
                - 预算上限：%s
                %s

                ### 需要逐项解决的问题
                %s

                ### 按问题分类的检索结果
                %s

                ### 下一步
                请核对上面的分类检索结果是否覆盖你的问题。确认后，我会只围绕这些目标生成完整行动报告；
                不相关的类别不会进入最终报告，未查到可靠资料的部分也不会用其他类别强行代替。
                """
                .formatted(
                        task.getObjective(),
                        city,
                        budgetLabel(budget),
                        revisions.isEmpty() ? "" : "- 累计修改要求：" + String.join("；", revisions),
                        questions.isEmpty()
                                ? "- 暂无单独问题，可在下方继续补充"
                                : "- " + String.join("\n- ", questions),
                        shorten(places, 5_000));
    }

    /**
     * 汇总最终阶段送给大模型的完整需求文本。
     * 优先级从高到低：当前有效参数（地点/预算）> 初始目标 > 待解决问题 > 历次修改 >
     * 关系档案里的偏好与边界，用"｜"连接。
     */
    @Override
    public String combinedRequirements(AgentTask task, Map<String, Object> parameters) {
        List<String> parts = new ArrayList<>();
        String city = resolveCity(parameters, task.getObjective());
        String budget = parameterText(parameters.get("budget"), "");
        // 当前有效参数为最高优先级，覆盖历史文字里的地点与金额
        parts.add("当前有效参数（最高优先级）：地点=" + city + "，预算=" + (budget.isBlank() ? "未限定" : budget + "元"));
        parts.add("初始目标（仅保留非冲突意图，地点和金额以当前有效参数为准）：" + task.getObjective());
        List<String> questions = asStringList(parameters.get("questions"));
        if (!questions.isEmpty()) parts.add("需要解决的问题：" + String.join("；", questions));
        List<String> revisions = asStringList(parameters.get("revisions"));
        if (!revisions.isEmpty()) parts.add("历次补充要求：" + String.join("；", revisions));
        RelationshipProfile profile = profiles.findByUserId(task.getUserId()).orElse(null);
        if (profile != null) {
            if (profile.getPreferences() != null && !profile.getPreferences().isBlank()) {
                parts.add("关系档案偏好：" + profile.getPreferences().trim());
            }
            if (profile.getBoundaries() != null && !profile.getBoundaries().isBlank()) {
                parts.add("必须遵守的关系边界：" + profile.getBoundaries().trim());
            }
        }
        return String.join("｜", parts);
    }

    /** External place searches use only the user's explicit question list. */
    @Override
    public String searchRequirements(Map<String, Object> parameters) {
        return String.join("\n", asStringList(parameters.get("questions")));
    }

    /**
     * 根据选中的省份返回下辖市列表（前端联动下拉）。
     * 直辖市/特别行政区直接返回自身；其余省份实时调用高德行政区查询接口取地级城市。
     * 未配置 Key 或接口异常时抛出业务异常。
     */
    @Override
    public List<String> cityOptions(String province) {
        String normalized = province == null ? "" : province.trim();
        if (!PROVINCES.contains(normalized)) throw ApiException.badRequest("请选择有效的省级行政区");
        // 直辖市/特别行政区本身就是市级，无需再查
        if (normalized.endsWith("市") || normalized.endsWith("特别行政区")) return List.of(normalized);
        if (amapKey.isBlank()) throw ApiException.badRequest("未配置高德地图密钥，无法加载城市列表");
        try {
            // 调用高德行政区接口，subdistrict=1 取一级子级（地级市）
            String response =
                    HttpUtil.get(
                            "https://restapi.amap.com/v3/config/district",
                            Map.of(
                                    "key",
                                    amapKey,
                                    "keywords",
                                    normalized,
                                    "subdistrict",
                                    "1",
                                    "extensions",
                                    "base"));
            var districts = JSONUtil.parseObj(response).getJSONArray("districts");
            if (districts == null || districts.isEmpty()) throw new IllegalStateException();
            var children = districts.getJSONObject(0).getJSONArray("districts");
            if (children == null) throw new IllegalStateException();
            List<String> cities = new ArrayList<>();
            for (int index = 0; index < children.size(); index++) {
                String name = children.getJSONObject(index).getStr("name", "").trim();
                if (!name.isBlank() && !cities.contains(name)) cities.add(name);
            }
            if (cities.isEmpty()) throw new IllegalStateException();
            return cities;
        } catch (Exception ignored) {
            throw ApiException.badRequest("城市列表加载失败，请稍后重试");
        }
    }

    @Override
    public List<String> asStringList(Object raw) {
        if (raw == null) return new ArrayList<>();
        List<?> values = raw instanceof List<?> list ? list : List.of(raw);
        List<String> result = new ArrayList<>();
        for (Object value : values) {
            String text = String.valueOf(value).trim();
            if (!text.isBlank() && !"null".equalsIgnoreCase(text) && !result.contains(text)) {
                result.add(text);
            }
        }
        return result;
    }

    @Override
    public List<String> mergeQuestions(List<String> existing, List<String> incoming) {
        List<String> merged = new ArrayList<>(existing);
        if (incoming != null) {
            for (String question : incoming) {
                String normalized = question == null ? "" : question.trim();
                if (!normalized.isBlank() && !merged.contains(normalized)) merged.add(normalized);
            }
        }
        return merged;
    }

    /**
     * 把修改说明里"像问题"的句子抽出来，追加到问题列表。
     * 按换行/分号切句，去掉列表前缀（-、•、1.、、），再用结尾问号或疑问词
     * （什么/哪里/是否/怎么…）判断是否为问题；"停车+免费/哪里"也算问题。
     */
    @Override
    public List<String> extractQuestions(String note) {
        if (note == null || note.isBlank()) return List.of();
        List<String> extracted = new ArrayList<>();
        for (String part : note.split("[\\r\\n；;]+")) {
            String value = part.trim().replaceFirst("^[\\-•\\d.、\\s]+", "");
            boolean questionLike =
                    value.endsWith("?")
                            || value.endsWith("？")
                            || value.matches(".*(什么|哪里|哪家|哪个|是否|怎么|如何|能否|可不可以|可以.*吗).*?")
                            || (value.contains("停车") && value.matches(".*(免费|哪里|哪儿|哪家|什么).*"));
            if (questionLike && !value.isBlank() && !extracted.contains(value)) {
                extracted.add(value);
            }
        }
        return extracted;
    }

    @Override
    public String searchCategories(String searchResult) {
        if (searchResult == null || searchResult.isBlank()) return "按问题动态提取";
        for (String line : searchResult.split("\\R")) {
            if (line.startsWith("动态检索类别：")) return line.substring("动态检索类别：".length()).trim();
        }
        return "按问题动态提取";
    }

    @Override
    public String parameterText(Object value, String fallback) {
        if (value == null) return fallback;
        String text = String.valueOf(value).trim();
        if (text.isBlank() || "null".equalsIgnoreCase(text)) return fallback;
        try {
            return normalizeBudget(new BigDecimal(text)).toPlainString();
        } catch (NumberFormatException ignored) {
            return text;
        }
    }

    @Override
    public String budgetLabel(String budget) {
        return "未限定".equals(budget) || budget.isBlank() ? "未限定" : budget + " 元";
    }

    @Override
    public void normalizeStoredBudget(Map<String, Object> parameters) {
        Object raw = parameters.get("budget");
        if (raw == null || String.valueOf(raw).isBlank()) {
            parameters.remove("budget");
            return;
        }
        try {
            BigDecimal budget = new BigDecimal(String.valueOf(raw).trim());
            if (budget.signum() < 0) throw ApiException.badRequest("预算不能小于 0");
            parameters.put("budget", normalizeBudget(budget));
        } catch (NumberFormatException ignored) {
            throw ApiException.badRequest("预算格式不正确");
        }
    }

    @Override
    public BigDecimal normalizeBudget(BigDecimal budget) {
        return new BigDecimal(budget.stripTrailingZeros().toPlainString());
    }

    @Override
    public Map<String, Object> readParameters(AgentTask task) {
        try {
            return new LinkedHashMap<>(
                    json.readValue(task.getParametersJson(), new TypeReference<>() {}));
        } catch (Exception ignored) {
            return new LinkedHashMap<>();
        }
    }

    @Override
    public String writeParameters(Map<String, Object> parameters) {
        try {
            return json.writeValueAsString(parameters);
        } catch (Exception ignored) {
            return "{}";
        }
    }

    /**
     * 解析实际用于检索的城市：优先用已解析好的 searchRegion，其次用 city 字段，
     * 都缺失时再从目标文本里粗匹配已知城市。
     */
    @Override
    public String resolveCity(Map<String, Object> parameters, String text) {
        String searchRegion = String.valueOf(parameters.getOrDefault("searchRegion", "")).trim();
        if (!searchRegion.isBlank() && !"null".equalsIgnoreCase(searchRegion)) return searchRegion;
        String city = String.valueOf(parameters.getOrDefault("city", "")).trim();
        if (!city.isBlank() && !"null".equalsIgnoreCase(city)) return city;
        return findKnownCity(text);
    }

    /**
     * 校验并解析用户选择的省/市，返回完整的 searchRegion。
     * 校验：省必须在合法列表内；市必须是中文且以"市/自治州/地区/盟/特别行政区"结尾。
     * 直辖市返回市名本身，否则拼接为"省+市"。
     */
    @Override
    public String validateAndResolveRegion(Map<String, Object> parameters) {
        String province = String.valueOf(parameters.getOrDefault("province", "")).trim();
        String city = String.valueOf(parameters.getOrDefault("city", "")).trim();
        if (!PROVINCES.contains(province)) throw ApiException.badRequest("请选择有效的省级行政区");
        // 城市名必须是完整行政地名，防止用户输入模糊词导致高德 citylimit 失效
        if (!city.matches("[\\p{IsHan}·]{2,20}(?:市|自治州|地区|盟|特别行政区)")) {
            throw ApiException.badRequest("请输入完整城市名称，例如：上海市、南宁市");
        }
        parameters.put("province", province);
        parameters.put("city", city);
        return province.equals(city) ? city : province + city;
    }

    @Override
    public String findKnownCity(String text) {
        if (text == null) return "";
        return KNOWN_CITIES.stream().filter(text::contains).findFirst().orElse("");
    }

    /**
     * 规范化并校验幂等键。
     * 去空格；最长 96 字符；只允许字母数字及 . _ : - ；非法直接 400。
     * null/空串返回 null，表示本次不启用幂等。
     */
    @Override
    public String normalizeIdempotencyKey(String key) {
        if (key == null || key.isBlank()) return null;
        String normalized = key.trim();
        if (normalized.length() > 96) throw ApiException.badRequest("Idempotency-Key 最长为 96 个字符");
        if (!normalized.matches("[A-Za-z0-9._:-]+")) {
            throw ApiException.badRequest("Idempotency-Key 只能包含字母、数字、点、下划线、冒号和短横线");
        }
        return normalized;
    }

    private String shorten(String value, int length) {
        if (value == null) return "";
        return value.substring(0, Math.min(length, value.length()));
    }
}

package io.github.fnyoat.tipismine.hook;

import java.util.Map;

/**
 * 轻量表达式求值器——纯逻辑、无 Android 依赖，可在 SystemUI（hook 侧）和模块 App（UI 预览侧）共用。
 *
 * <p>文本中形如 {@code ${...}} 的片段会被求值替换，支持嵌套与组合。语法：
 * <ul>
 *   <li><b>字面量</b>：{@code "字符串"} / {@code '字符串'} / {@code 123} / {@code 1.5} /
 *       {@code null} / {@code true} / {@code false}</li>
 *   <li><b>变量</b>：{@code ${battery}}、{@code ${charging}}、{@code ${wifi}}、{@code ${bt}}、
 *       {@code ${time}}、{@code ${date}}、{@code ${owner}}、{@code ${vpn}}（未知变量 → 空串）</li>
 *   <li><b>函数</b>：{@code ${get(url)}}、{@code ${post(url, body)}}、{@code ${get(url, ttl)}}、
 *       {@code ${post(url, body, ttl)}}，参数可为表达式或裸文本
 *       （裸文本如 {@code https://x} 或引号包裹均可）。ttl 为可选毫秒数，>0 时覆盖缓存周期。</li>
 *   <li><b>比较</b>：{@code ==} {@code !=} {@code >} {@code >=} {@code <} {@code <=}，返回
 *       {@code "true"} / {@code "false"}；{@code null} 可作操作数，空串与 {@code null} 视为相等</li>
 *   <li><b>逻辑</b>：{@code &&} {@code ||} {@code !}，返回 {@code "true"} / {@code "false"}</li>
 *   <li><b>数值 / 拼接</b>：{@code +}——两侧都可解析为数字时求和，否则字符串拼接</li>
 *   <li><b>三元</b>：{@code cond ? "a" : "b"}，可嵌套任意层（{@code a ? x : b ? y : z}）</li>
 *   <li><b>括号</b>：{@code ( ... )} 任意组合</li>
 * </ul>
 *
 * <p>示例：
 * <pre>
 *   ${owner == null ? "无归属" : owner}
 *   ${(battery >= 80) && charging ? "电量充足" : "电量不足"}
 *   ${battery > 50 ? "高" : battery > 20 ? "中" : "低"}
 *   ${get("http://x?a=" + battery)}
 * </pre>
 *
 * <p>${owner} / ${vpn} 由调用方在每次求值时注入（见 {@link PromptRewriter}），
 * 当前情况无对应槽位实参时求值为空串；比较时与字面 {@code null} 相等。
 *
 * <p>求值失败（语法错误 / 未闭合）时保留原文 {@code ${...}} 不替换。
 */
public final class ExpressionEngine {

    private ExpressionEngine() {
    }

    /**
     * 对整段文本求值：找出所有 {@code ${...}} 并替换。
     *
     * @param text 含表达式的模板文本
     * @param ctx  变量 / 函数提供者
     * @return 求值后的纯文本
     */
    public static String evaluate(String text, ExpressionContext ctx) {
        return evaluate(text, ctx, null);
    }

    /**
     * 对整段文本求值，并允许覆写指定变量（如调用方动态提供的 owner / vpn 实参）。
     *
     * <p>覆写值优先于 {@link ExpressionContext#getVariable}；值可为 {@code null}
     * （表示"该变量当前无值"，求值时表现为空串，参与比较时与字面 {@code null} 相等）。
     *
     * @param text      含表达式的模板文本
     * @param ctx       变量 / 函数提供者
     * @param overrides 覆写变量映射（可为 null）
     * @return 求值后的纯文本
     */
    public static String evaluate(String text, ExpressionContext ctx, Map<String, String> overrides) {
        if (text == null || text.isEmpty()) return text;
        StringBuilder sb = new StringBuilder();
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == '$' && i + 1 < n && text.charAt(i + 1) == '{') {
                int end = matchClose(text, i + 2);
                if (end < 0) {
                    sb.append(c); // ${ 未闭合：按普通字符处理
                    i++;
                    continue;
                }
                String body = text.substring(i + 2, end).trim();
                sb.append(evalSafely(body, ctx, overrides));
                i = end + 1;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    /** 找到与 {@code $+'{'} 对应的 {@code '}'} 下标（跳过引号内内容）；未闭合返回 -1。 */
    private static int matchClose(String s, int start) {
        char quote = 0;
        for (int j = start; j < s.length(); j++) {
            char c = s.charAt(j);
            if (quote != 0) {
                if (c == '\\') {
                    j++;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '}') {
                return j;
            }
        }
        return -1;
    }

    /** 求值单个表达式体；任何异常（语法错误等）都保留原文 {@code ${...}}，绝不中断整段。 */
    private static String evalSafely(String body, ExpressionContext ctx, Map<String, String> overrides) {
        try {
            String r = new Expr(body, ctx, overrides).parse();
            return r == null ? "" : r;
        } catch (Throwable t) {
            return "${" + body + "}";
        }
    }

    /* ------------------------------------------------------------------ */
    /* 递归下降解析器                                                       */
    /* ------------------------------------------------------------------ */

    private static final class Expr {
        private final String s;
        private final ExpressionContext ctx;
        private final Map<String, String> overrides;
        private int pos;

        Expr(String s, ExpressionContext ctx, Map<String, String> overrides) {
            this.s = s;
            this.ctx = ctx;
            this.overrides = overrides;
        }

        /** 入口：整体解析，要求完全消费。 */
        String parse() {
            skipWs();
            String v = ternary();
            skipWs();
            if (pos < s.length()) throw new IllegalArgumentException("trailing chars at " + pos);
            return v;
        }

        /* 语法：ternary := or ( '?' ternary ':' ternary )? */
        private String ternary() {
            String cond = or();
            if (peek() == '?') {
                pos++;
                String t = ternary();
                expect(':');
                String f = ternary();
                return isTruthy(cond) ? t : f;
            }
            return cond;
        }

        /* 语法：or := and ( '||' and )* */
        private String or() {
            String v = and();
            while (peekStr("||")) {
                pos += 2;
                String r = and();
                v = (isTruthy(v) || isTruthy(r)) ? "true" : "false";
            }
            return v;
        }

        /* 语法：and := relation ( '&&' relation )* */
        private String and() {
            String v = relation();
            while (peekStr("&&")) {
                pos += 2;
                String r = relation();
                v = (isTruthy(v) && isTruthy(r)) ? "true" : "false";
            }
            return v;
        }

        /* 语法：relation := additive ( ('=='|'!='|'>'|'>='|'<'|'<=') additive )* */
        private String relation() {
            String l = additive();
            while (true) {
                String op = peekRelOp();
                if (op == null) return l;
                pos += op.length();
                String r = additive();
                l = compare(l, r, op);
            }
        }

        /* 语法：additive := unary ( '+' unary )* */
        private String additive() {
            String l = unary();
            while (peek() == '+') {
                pos++;
                String r = unary();
                l = addStr(l, r);
            }
            return l;
        }

        /* 语法：unary := '!' unary | primary */
        private String unary() {
            if (peek() == '!') {
                pos++;
                return isTruthy(unary()) ? "false" : "true";
            }
            return primary();
        }

        /* 语法：primary := '(' ternary ')' | literal | ident ( '(' args ')' | 变量 ) */
        private String primary() {
            char c = peek();
            if (c == '(') {
                pos++;
                String v = ternary();
                expect(')');
                return v;
            }
            if (c == '"' || c == '\'') return readString();
            if (c >= '0' && c <= '9') return readNumber();
            if (Character.isLetter(c) || c == '_') return identOrCall();
            throw new IllegalArgumentException("unexpected char '" + c + "' at " + pos);
        }

        /* 字面量 null / true / false 或变量，或函数调用。 */
        private String identOrCall() {
            int start = pos;
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (Character.isLetterOrDigit(c) || c == '_') pos++;
                else break;
            }
            String word = s.substring(start, pos);
            if ("null".equals(word)) return null;
            if ("true".equals(word)) return "true";
            if ("false".equals(word)) return "false";
            if (peek() == '(') {
                pos++;
                if ("get".equals(word)) {
                    String url = arg();
                    long ttl = trailingTtlArg();
                    expect(')');
                    return ttl > 0 ? ctx.executeGet(url, ttl) : ctx.executeGet(url);
                }
                if ("post".equals(word)) {
                    String url = arg();
                    expect(',');
                    String body = arg();
                    long ttl = trailingTtlArg();
                    expect(')');
                    return ttl > 0 ? ctx.executePost(url, body, ttl) : ctx.executePost(url, body);
                }
                throw new IllegalArgumentException("unknown function " + word);
            }
            return variableValue(word, ctx, overrides);
        }

        /** 解析可选的可选 ttl 参数：若当前是 {@code ,<数字>} 则消费并返回；否则返回 0。
         *  注意区分 get/body 自身可能含逗号——ttl 参数仅当后续立即接 <ttl 数字> 且之后是
         *  {@code )} 时才视为 ttl，避免把 url 里的数字误当 ttl。 */
        private long trailingTtlArg() {
            int save = pos;
            skipWs();
            if (pos >= s.length() || s.charAt(pos) != ',') {
                pos = save;
                return 0;
            }
            pos++; // 吃掉逗号
            skipWs();
            int numStart = pos;
            while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) pos++;
            if (pos == numStart) {
                pos = save; // 逗号后不是数字：不是 ttl，回退
                return 0;
            }
            String numStr = s.substring(numStart, pos);
            skipWs();
            if (pos < s.length() && s.charAt(pos) == ')') {
                try {
                    return (long) Double.parseDouble(numStr);
                } catch (NumberFormatException e) {
                    return 0;
                }
            }
            pos = save; // 逗号后数字但不以 ')' 结尾：不是 ttl（如 url 里带逗号+数字），回退
            return 0;
        }

        /**
         * 解析函数参数：先框定到下一个顶层逗号 / 右括号，再尝试整体作为表达式求值；
         * 失败则按裸文本字面值（去引号）返回——从而既支持 {@code get("http://x?a=" + battery)}
         * 这类嵌套，也兼容 {@code get(https://x)}、{@code post(https://x, "{"a":1}")} 的旧写法。
         */
        private String arg() {
            skipWs();
            int start = pos;
            char quote = 0;
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (quote != 0) {
                    if (c == '\\') pos++;
                    else if (c == quote) quote = 0;
                    pos++;
                    continue;
                }
                if (c == '"' || c == '\'') quote = c;
                else if (c == ',' || c == ')') break;
                pos++;
            }
            String raw = s.substring(start, pos).trim();
            if (raw.isEmpty()) return "";
            Expr sub = new Expr(raw, ctx, overrides);
            try {
                return sub.parse();
            } catch (Throwable t) {
                return unquote(raw);
            }
        }

        private String compare(String l, String r, String op) {
            if ("==".equals(op)) {
                return equalsOrNull(l, r) ? "true" : "false";
            }
            if ("!=".equals(op)) {
                return equalsOrNull(l, r) ? "false" : "true";
            }
            double ld = numOrNull(l);
            double rd = numOrNull(r);
            int cmp;
            if (!Double.isNaN(ld) && !Double.isNaN(rd)) {
                cmp = Double.compare(ld, rd);
            } else {
                cmp = (l == null ? "" : l).compareTo(r == null ? "" : r);
            }
            switch (op) {
                case ">": return cmp > 0 ? "true" : "false";
                case ">=": return cmp >= 0 ? "true" : "false";
                case "<": return cmp < 0 ? "true" : "false";
                case "<=": return cmp <= 0 ? "true" : "false";
                default: return "false";
            }
        }

        /** 相等：空串与 null 视为相等（"无该值"语义一致）。 */
        private static boolean equalsOrNull(String l, String r) {
            boolean ln = l == null || l.isEmpty();
            boolean rn = r == null || r.isEmpty();
            if (ln || rn) return ln && rn;
            return l.equals(r);
        }

        /* ---- 词法助手 ---- */

        private void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }

        private char peek() {
            skipWs();
            return pos < s.length() ? s.charAt(pos) : '\0';
        }

        private boolean peekStr(String t) {
            skipWs();
            if (pos + t.length() <= s.length()) {
                return s.startsWith(t, pos);
            }
            return false;
        }

        /** 当前比较运算符；无则 null。默认最长匹配（>= 先于 >）。 */
        private String peekRelOp() {
            if (peekStr(">=") || peekStr("<=")) {
                return s.substring(pos, pos + 2);
            }
            if (peekStr("==") || peekStr("!=")) {
                return s.substring(pos, pos + 2);
            }
            if (peekStr(">") || peekStr("<")) {
                return s.substring(pos, pos + 1);
            }
            return null;
        }

        private void expect(char c) {
            skipWs();
            if (pos >= s.length() || s.charAt(pos) != c) {
                throw new IllegalArgumentException("expected '" + c + "' at " + pos);
            }
            pos++;
        }

        private String readString() {
            skipWs();
            char q = s.charAt(pos++);
            StringBuilder sb = new StringBuilder();
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == '\\' && pos + 1 < s.length()) {
                    sb.append(s.charAt(pos + 1));
                    pos += 2;
                    continue;
                }
                if (c == q) {
                    pos++;
                    return sb.toString();
                }
                sb.append(c);
                pos++;
            }
            throw new IllegalArgumentException("unterminated string");
        }

        private String readNumber() {
            skipWs();
            int start = pos;
            while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) pos++;
            return s.substring(start, pos);
        }
    }

    /** 数字比较的解析；不可解析返回 NaN。 */
    private static double numOrNull(String v) {
        if (v == null || v.trim().isEmpty()) return Double.NaN;
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static String addStr(String a, String b) {
        double ld = numOrNull(a);
        double rd = numOrNull(b);
        if (!Double.isNaN(ld) && !Double.isNaN(rd)) {
            if (a != null && a.contains(".") || b != null && b.contains(".")) {
                return trimNum(ld + rd);
            }
            long sum = Math.round(ld) + Math.round(rd);
            return String.valueOf(sum);
        }
        return (a == null ? "" : a) + (b == null ? "" : b);
    }

    private static String trimNum(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v) && Math.abs(v) < 1e15) {
            return String.valueOf((long) v);
        }
        return String.valueOf(v);
    }

    private static String variableValue(String name, ExpressionContext ctx, Map<String, String> overrides) {
        if (overrides != null && overrides.containsKey(name)) {
            String v = overrides.get(name);
            return v == null ? "" : v;
        }
        return ctx.getVariable(name);
    }

    /** 判断字符串是否为"真"：非空、非 "false"、非 "0"。 */
    private static boolean isTruthy(String s) {
        if (s == null || s.isEmpty()) return false;
        if ("false".equalsIgnoreCase(s) || "0".equals(s)) return false;
        return true;
    }

    /** 去掉首尾的单引号或双引号。 */
    private static String unquote(String s) {
        if (s.length() >= 2) {
            if ((s.startsWith("'") && s.endsWith("'"))
                    || (s.startsWith("\"") && s.endsWith("\""))) {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
    }
}
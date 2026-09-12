package io.github.fnyoat.tipismine.hook;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class ExpressionEngineTest {

    private static final ExpressionContext CTX = new ExpressionContext() {
        @Override
        public String getVariable(String name) {
            if ("battery".equals(name)) return "80";
            if ("charging".equals(name)) return "true";
            if ("time".equals(name)) return "12:00";
            return "";
        }

        @Override
        public String executeGet(String url) {
            return "get:" + url;
        }

        @Override
        public String executePost(String url, String body) {
            return "post:" + url + ":" + body;
        }

        @Override
        public String executeGet(String url, long ttl) {
            return "get:" + url + ":ttl" + ttl;
        }

        @Override
        public String executePost(String url, String body, long ttl) {
            return "post:" + url + ":" + body + ":ttl" + ttl;
        }

        @Override
        public void clearCache() { }
    };

    @Test
    public void plainText_passesThrough() {
        assertEquals("hello world", ExpressionEngine.evaluate("hello world", CTX));
    }

    @Test
    public void nullOrEmpty_isReturnedAsIs() {
        assertEquals(null, ExpressionEngine.evaluate(null, CTX));
        assertEquals("", ExpressionEngine.evaluate("", CTX));
    }

    @Test
    public void singleVariable_isReplaced() {
        assertEquals("80", ExpressionEngine.evaluate("${battery}", CTX));
    }

    @Test
    public void variableInsideText_isReplaced() {
        assertEquals("电量 80%", ExpressionEngine.evaluate("电量 ${battery}%", CTX));
    }

    @Test
    public void multipleExpressions_areReplaced() {
        assertEquals("80 | true", ExpressionEngine.evaluate("${battery} | ${charging}", CTX));
    }

    @Test
    public void unknownVariable_becomesEmpty() {
        assertEquals("", ExpressionEngine.evaluate("${unknown}", CTX));
    }

    @Test
    public void getFunction_returnsResponse() {
        assertEquals("get:https://x", ExpressionEngine.evaluate("${get(https://x)}", CTX));
    }

    @Test
    public void getFunction_withQuotes() {
        assertEquals("get:https://x", ExpressionEngine.evaluate("${get(\"https://x\")}", CTX));
    }

    @Test
    public void postFunction_returnsResponse() {
        assertEquals("post:https://x:{a:1}",
                ExpressionEngine.evaluate("${post(https://x, \"{a:1}\")}", CTX));
    }

    @Test
    public void ternary_trueBranch() {
        assertEquals("已充电", ExpressionEngine.evaluate("${charging ? \"已充电\" : \"未充电\"}", CTX));
    }

    @Test
    public void ternary_falseBranch_whenCondEmpty() {
        assertEquals("未充电", ExpressionEngine.evaluate("${unknown ? \"已充电\" : \"未充电\"}", CTX));
    }

    @Test
    public void ternary_falseBranch_whenCondZero() {
        assertEquals("B", ExpressionEngine.evaluate("${0 ? \"A\" : \"B\"}", CTX));
    }

    @Test
    public void danglingDollar_untouched() {
        assertEquals("$var", ExpressionEngine.evaluate("$var", CTX));
    }

    /* ------------------------------------------------------------------ */
    /* 比较运算符 == / != 与 owner / vpn 覆写变量                           */
    /* ------------------------------------------------------------------ */

    @Test
    public void equalsOp_returnsTrue() {
        assertEquals("true", ExpressionEngine.evaluate("${battery == \"80\"}", CTX));
    }

    @Test
    public void equalsOp_returnsFalse() {
        assertEquals("false", ExpressionEngine.evaluate("${battery == \"90\"}", CTX));
    }

    @Test
    public void notEqualsOp_returnsTrue() {
        assertEquals("true", ExpressionEngine.evaluate("${battery != \"90\"}", CTX));
    }

    @Test
    public void equalsWithNullLiteral_whenMissing() {
        ExpressionContext ctx = new ExpressionContext() {
            @Override public String getVariable(String name) { return ""; }
            @Override public String executeGet(String url) { return ""; }
            @Override public String executePost(String url, String body) { return ""; }
            @Override public String executeGet(String url, long ttl) { return ""; }
            @Override public String executePost(String url, String body, long ttl) { return ""; }
            @Override public void clearCache() { }
        };
        assertEquals("true", ExpressionEngine.evaluate("${owner == null}", ctx));
    }

    @Test
    public void notEqualsWithNullLiteral_whenMissing() {
        ExpressionContext ctx = new ExpressionContext() {
            @Override public String getVariable(String name) { return ""; }
            @Override public String executeGet(String url) { return ""; }
            @Override public String executePost(String url, String body) { return ""; }
            @Override public String executeGet(String url, long ttl) { return ""; }
            @Override public String executePost(String url, String body, long ttl) { return ""; }
            @Override public void clearCache() { }
        };
        assertEquals("false", ExpressionEngine.evaluate("${owner != null}", ctx));
    }

    @Test
    public void ternaryWithNullComparison() {
        Map<String, String> overrides = new HashMap<>();
        overrides.put("owner", null);
        assertEquals("无归属", ExpressionEngine.evaluate(
                "${owner == null ? \"无归属\" : owner}", CTX, overrides));
    }

    @Test
    public void ternaryWithNullComparison_presentValue() {
        Map<String, String> overrides = new HashMap<>();
        overrides.put("owner", "公司A");
        assertEquals("公司A", ExpressionEngine.evaluate(
                "${owner == null ? \"无归属\" : owner}", CTX, overrides));
    }

    @Test
    public void ownerVariable_replacedFromOverrides() {
        Map<String, String> overrides = new HashMap<>();
        overrides.put("owner", "公司A");
        overrides.put("vpn", "公司VPN");
        assertEquals("公司A / 公司VPN", ExpressionEngine.evaluate(
                "${owner} / ${vpn}", CTX, overrides));
    }

    @Test
    public void ownerVariable_nullWhenMissing_becomesEmpty() {
        Map<String, String> overrides = new HashMap<>();
        overrides.put("owner", null);
        assertEquals("", ExpressionEngine.evaluate("${owner}", CTX, overrides));
    }

    /* ------------------------------------------------------------------ */
    /* 嵌套语法：括号 / 逻辑 / 数值比较 / 嵌套三元 / 函数参数内嵌表达式     */
    /* ------------------------------------------------------------------ */

    @Test
    public void nestedTernary_rightAssociative() {
        Map<String, String> overrides = new HashMap<>();
        overrides.put("owner", "公司A");
        assertEquals("总部", ExpressionEngine.evaluate(
                "${owner == null ? \"无\" : owner == \"公司A\" ? \"总部\" : owner}",
                CTX, overrides));
    }

    @Test
    public void nestedTernary_threeLevels() {
        assertEquals("中", ExpressionEngine.evaluate(
                "${battery > 90 ? \"高\" : battery > 70 ? \"中\" : \"低\"}", CTX));
    }

    @Test
    public void logicalAnd_parens() {
        assertEquals("充足", ExpressionEngine.evaluate(
                "${(battery >= 80) && charging ? \"充足\" : \"不足\"}", CTX));
    }

    @Test
    public void logicalOr_not() {
        assertEquals("高", ExpressionEngine.evaluate(
                "${(battery <= 70) || !charging ? \"低\" : \"高\"}", CTX));
    }

    @Test
    public void notOperator_flipsBoolean() {
        assertEquals("充电中", ExpressionEngine.evaluate(
                "${!charging ? \"未充电\" : \"充电中\"}", CTX));
    }

    @Test
    public void numericComparison_greaterThan() {
        assertEquals("高", ExpressionEngine.evaluate("${battery > 50 ? \"高\" : \"低\"}", CTX));
    }

    @Test
    public void numericComparison_range() {
        assertEquals("刚刚好", ExpressionEngine.evaluate(
                "${battery <= \"80\" && battery >= 80 ? \"刚刚好\" : \"no\"}", CTX));
    }

    @Test
    public void plus_addsNumbers() {
        assertEquals("85", ExpressionEngine.evaluate("${battery + 5}", CTX));
    }

    @Test
    public void plus_concatenatesStrings() {
        assertEquals("80%", ExpressionEngine.evaluate("${battery + \"%\"}", CTX));
    }

    @Test
    public void functionArg_nestedExpression() {
        assertEquals("get:http://x?a=80", ExpressionEngine.evaluate(
                "${get(\"http://x?a=\" + battery)}", CTX));
    }

    @Test
    public void functionArg_nestedTernary() {
        assertEquals("post:http://x:on", ExpressionEngine.evaluate(
                "${post(\"http://x\", charging ? \"on\" : \"off\")}", CTX));
    }

    @Test
    public void bareUrl_keepsLegacy() {
        assertEquals("get:https://x?a=1", ExpressionEngine.evaluate(
                "${get(https://x?a=1)}", CTX));
    }

    @Test
    public void getWithTtl_passesTtl() {
        assertEquals("get:https://x:ttl30000", ExpressionEngine.evaluate(
                "${get(https://x, 30000)}", CTX));
    }

    @Test
    public void postWithTtl_passesTtl() {
        assertEquals("post:https://x:id=1:ttl60000", ExpressionEngine.evaluate(
                "${post(\"https://x\", \"id=1\", 60000)}", CTX));
    }

    @Test
    public void ttl_withDecimal_isTruncated() {
        assertEquals("get:https://x:ttl500", ExpressionEngine.evaluate(
                "${get(https://x, 500.5)}", CTX));
    }

    @Test
    public void commaInQuotedUrl_isNotTtl() {
        assertEquals("post:https://x?a=1,2:{a:1}", ExpressionEngine.evaluate(
                "${post(\"https://x?a=1,2\", \"{a:1}\")}", CTX));
    }

    @Test
    public void syntaxError_keepsOriginal() {
        assertEquals("${battery >}", ExpressionEngine.evaluate("${battery >}", CTX));
    }

    @Test
    public void unclosed_exression_untouched() {
        assertEquals("${battery", ExpressionEngine.evaluate("${battery", CTX));
    }
}
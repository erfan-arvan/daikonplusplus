package edu.njit.jerse.daikonplusplus.util;

public final class DpFlags {
    private DpFlags() {}

    public static boolean debug()             { return "1".equals(System.getenv("DP_DEBUG")); }
    public static boolean noQualityFilter()   { return "1".equals(System.getenv("DP_NO_QUALITY_FILTER")); }
    public static boolean keepWork()          { return "1".equals(System.getenv("DP_KEEP_WORK")); }
    public static boolean includeBody()       { return "1".equals(System.getenv("DP_INCLUDE_BODY")); }

    public static int llmTotalTimeoutSec()    { return parseInt("DP_LLM_TOTAL_TIMEOUT_SEC", 180); }
    public static int llmPerReqTimeoutSec()   { return parseInt("DP_LLM_REQ_TIMEOUT_SEC", 45); }
    public static int bodyMaxChars()          { return parseInt("DP_BODY_MAX_CHARS", 2000); }

    private static int parseInt(String key, int def) {
        try { return Integer.parseInt(System.getenv().getOrDefault(key, String.valueOf(def))); }
        catch (NumberFormatException e) { return def; }
    }
}

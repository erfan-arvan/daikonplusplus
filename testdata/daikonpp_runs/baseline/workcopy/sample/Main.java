package sample;

public class Main {
  public static void main(String[] args) {
  try { System.out.println("INV_EXD:4f688a30-85d4-4042-a504-a84f59a1dc38"); if (!(args != null)) { System.out.println("{\"type\":\"INV_FAIL\",\"id\":\"4f688a30-85d4-4042-a504-a84f59a1dc38\",\"element\":\"sample.Main#main(String[]):void\",\"file\":\"sample/Main.java\",\"expr\":\"args != null\",\"phase\":\"ENTRY\",\"error\":\"\"}"); } } catch (Throwable __dp_ex_4f688a3085d44042a504a84f59a1dc38_en0) { System.out.println("{\"type\":\"INV_FAIL\",\"id\":\"4f688a30-85d4-4042-a504-a84f59a1dc38\",\"element\":\"sample.Main#main(String[]):void\",\"file\":\"sample/Main.java\",\"expr\":\"args != null\",\"phase\":\"ENTRY\",\"error\":\"" + __dp_ex_4f688a3085d44042a504a84f59a1dc38_en0.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"}"); }
  try { System.out.println("INV_EXD:06eca5a1-52a2-4d1c-a739-1914d630ed25"); if (!(args.length >= 0)) { System.out.println("{\"type\":\"INV_FAIL\",\"id\":\"06eca5a1-52a2-4d1c-a739-1914d630ed25\",\"element\":\"sample.Main#main(String[]):void\",\"file\":\"sample/Main.java\",\"expr\":\"args.length >= 0\",\"phase\":\"ENTRY\",\"error\":\"\"}"); } } catch (Throwable __dp_ex_06eca5a152a24d1ca7391914d630ed25_en1) { System.out.println("{\"type\":\"INV_FAIL\",\"id\":\"06eca5a1-52a2-4d1c-a739-1914d630ed25\",\"element\":\"sample.Main#main(String[]):void\",\"file\":\"sample/Main.java\",\"expr\":\"args.length >= 0\",\"phase\":\"ENTRY\",\"error\":\"" + __dp_ex_06eca5a152a24d1ca7391914d630ed25_en1.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"}"); }
  // Simple calls to exercise both ENTRY and EXIT points.
    int s = MathUtils.sum(1, 2);     // 3
    int m = MathUtils.max(3, 2);     // 3

    if (s != 3) throw new AssertionError("sum wrong: " + s);
    if (m != 3) throw new AssertionError("max wrong: " + m);

    // Keep program short & deterministic
    System.out.println("OK:" + s + "," + m);
    try { System.out.println("INV_EXD:2dce125c-4649-4d31-ae43-8341f79536d3"); if (!(args != null)) { System.out.println("{\"type\":\"INV_FAIL\",\"id\":\"2dce125c-4649-4d31-ae43-8341f79536d3\",\"element\":\"sample.Main#main(String[]):void\",\"file\":\"sample/Main.java\",\"expr\":\"args != null\",\"phase\":\"EXIT\",\"error\":\"\"}"); } } catch (Throwable __dp_ex_2dce125c46494d31ae438341f79536d3_tail_0) { System.out.println("{\"type\":\"INV_FAIL\",\"id\":\"2dce125c-4649-4d31-ae43-8341f79536d3\",\"element\":\"sample.Main#main(String[]):void\",\"file\":\"sample/Main.java\",\"expr\":\"args != null\",\"phase\":\"EXIT\",\"error\":\"" + __dp_ex_2dce125c46494d31ae438341f79536d3_tail_0.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"}"); }
    try { System.out.println("INV_EXD:792b430a-ca45-4102-b456-c92dbf605892"); if (!(args.length >= 0)) { System.out.println("{\"type\":\"INV_FAIL\",\"id\":\"792b430a-ca45-4102-b456-c92dbf605892\",\"element\":\"sample.Main#main(String[]):void\",\"file\":\"sample/Main.java\",\"expr\":\"args.length >= 0\",\"phase\":\"EXIT\",\"error\":\"\"}"); } } catch (Throwable __dp_ex_792b430aca454102b456c92dbf605892_tail_1) { System.out.println("{\"type\":\"INV_FAIL\",\"id\":\"792b430a-ca45-4102-b456-c92dbf605892\",\"element\":\"sample.Main#main(String[]):void\",\"file\":\"sample/Main.java\",\"expr\":\"args.length >= 0\",\"phase\":\"EXIT\",\"error\":\"" + __dp_ex_792b430aca454102b456c92dbf605892_tail_1.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"}"); }
  } }


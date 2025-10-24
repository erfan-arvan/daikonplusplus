package sample;

public class MathUtils {

  // Target 1
  public static int sum(int a, int b) {
  try { System.out.println("INV_EXD:d64ce820-c1e1-41ee-89b6-c0cdfa402334"); if (!(a >= 0)) { System.out.println("{\"type\":\"INV_FAIL\",\"id\":\"d64ce820-c1e1-41ee-89b6-c0cdfa402334\",\"element\":\"sample.MathUtils#sum(int,int):int\",\"file\":\"sample/MathUtils.java\",\"expr\":\"a >= 0\",\"phase\":\"ENTRY\",\"error\":\"\"}"); } } catch (Throwable __dp_ex_d64ce820c1e141ee89b6c0cdfa402334_en0) { System.out.println("{\"type\":\"INV_FAIL\",\"id\":\"d64ce820-c1e1-41ee-89b6-c0cdfa402334\",\"element\":\"sample.MathUtils#sum(int,int):int\",\"file\":\"sample/MathUtils.java\",\"expr\":\"a >= 0\",\"phase\":\"ENTRY\",\"error\":\"" + __dp_ex_d64ce820c1e141ee89b6c0cdfa402334_en0.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"}"); }
  if (a < 0) throw new IllegalArgumentException("negative a not allowed");
    {
        final var __dp_res1 = (a + b);
        try { System.out.println("INV_EXD:ca653e6a-045e-4849-acc9-54008d8e9bfa"); if (!(__dp_res1 == a + b)) { System.out.println("{\"type\":\"INV_FAIL\",\"id\":\"ca653e6a-045e-4849-acc9-54008d8e9bfa\",\"element\":\"sample.MathUtils#sum(int,int):int\",\"file\":\"sample/MathUtils.java\",\"expr\":\"__dp_res1 == a + b\",\"phase\":\"EXIT\",\"error\":\"\"}"); } } catch (Throwable __dp_ex_ca653e6a045e4849acc954008d8e9bfa_ex1_0) { System.out.println("{\"type\":\"INV_FAIL\",\"id\":\"ca653e6a-045e-4849-acc9-54008d8e9bfa\",\"element\":\"sample.MathUtils#sum(int,int):int\",\"file\":\"sample/MathUtils.java\",\"expr\":\"__dp_res1 == a + b\",\"phase\":\"EXIT\",\"error\":\"" + __dp_ex_ca653e6a045e4849acc954008d8e9bfa_ex1_0.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"}"); }
        return __dp_res1; }
  }

  // Target 2
  public static int max(int a, int b) {
    {
        final var __dp_res1 = ((a >= b) ? a : b);
        try { System.out.println("INV_EXD:aeac8e11-4738-4655-a374-c7800b333ad7"); if (!(__dp_res1 >= a)) { System.out.println("{\"type\":\"INV_FAIL\",\"id\":\"aeac8e11-4738-4655-a374-c7800b333ad7\",\"element\":\"sample.MathUtils#max(int,int):int\",\"file\":\"sample/MathUtils.java\",\"expr\":\"__dp_res1 >= a\",\"phase\":\"EXIT\",\"error\":\"\"}"); } } catch (Throwable __dp_ex_aeac8e1147384655a374c7800b333ad7_ex1_0) { System.out.println("{\"type\":\"INV_FAIL\",\"id\":\"aeac8e11-4738-4655-a374-c7800b333ad7\",\"element\":\"sample.MathUtils#max(int,int):int\",\"file\":\"sample/MathUtils.java\",\"expr\":\"__dp_res1 >= a\",\"phase\":\"EXIT\",\"error\":\"" + __dp_ex_aeac8e1147384655a374c7800b333ad7_ex1_0.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"}"); }
        try { System.out.println("INV_EXD:4cf47b4b-730d-419d-ae60-e38447db0a92"); if (!(__dp_res1 >= b)) { System.out.println("{\"type\":\"INV_FAIL\",\"id\":\"4cf47b4b-730d-419d-ae60-e38447db0a92\",\"element\":\"sample.MathUtils#max(int,int):int\",\"file\":\"sample/MathUtils.java\",\"expr\":\"__dp_res1 >= b\",\"phase\":\"EXIT\",\"error\":\"\"}"); } } catch (Throwable __dp_ex_4cf47b4b730d419dae60e38447db0a92_ex1_1) { System.out.println("{\"type\":\"INV_FAIL\",\"id\":\"4cf47b4b-730d-419d-ae60-e38447db0a92\",\"element\":\"sample.MathUtils#max(int,int):int\",\"file\":\"sample/MathUtils.java\",\"expr\":\"__dp_res1 >= b\",\"phase\":\"EXIT\",\"error\":\"" + __dp_ex_4cf47b4b730d419dae60e38447db0a92_ex1_1.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"}"); }
        try { System.out.println("INV_EXD:a2fcdc36-7ec9-4e26-b4e7-fb51103655e0"); if (!(__dp_res1 == a || __dp_res1 == b)) { System.out.println("{\"type\":\"INV_FAIL\",\"id\":\"a2fcdc36-7ec9-4e26-b4e7-fb51103655e0\",\"element\":\"sample.MathUtils#max(int,int):int\",\"file\":\"sample/MathUtils.java\",\"expr\":\"__dp_res1 == a || __dp_res1 == b\",\"phase\":\"EXIT\",\"error\":\"\"}"); } } catch (Throwable __dp_ex_a2fcdc367ec94e26b4e7fb51103655e0_ex1_2) { System.out.println("{\"type\":\"INV_FAIL\",\"id\":\"a2fcdc36-7ec9-4e26-b4e7-fb51103655e0\",\"element\":\"sample.MathUtils#max(int,int):int\",\"file\":\"sample/MathUtils.java\",\"expr\":\"__dp_res1 == a || __dp_res1 == b\",\"phase\":\"EXIT\",\"error\":\"" + __dp_ex_a2fcdc367ec94e26b4e7fb51103655e0_ex1_2.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"}"); }
        return __dp_res1; }
  } }


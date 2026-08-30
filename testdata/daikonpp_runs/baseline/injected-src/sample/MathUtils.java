package sample;

public class MathUtils {

  // Target 1
  public static int sum(int a, int b) {
  {
      //__DP_INVARIANT_BEGIN__
      ;
      try {
          String __dp_id = "d64ce820-c1e1-41ee-89b6-c0cdfa402334";
          if (!daikonpp.DpRuntime.DISABLED.contains(__dp_id) && !daikonpp.DpRuntime.SEEN_AT_START.contains(__dp_id)) {
              daikonpp.DpRuntime.recordExecuted(__dp_id);
              daikonpp.DpRuntime.markCurrent(__dp_id);
              boolean __dp_ok = true;
              if (daikonpp.DpRuntime.GUARD.get().compareAndSet(false, true)) {
                  try {
                      __dp_ok = (a >= 0);
                  } catch (Throwable __t) {
                      __dp_ok = false;
                  } finally {
                      daikonpp.DpRuntime.GUARD.get().set(false);
                  }
              }
              daikonpp.DpRuntime.clearCurrent(__dp_id);
              if (!__dp_ok) {
                  String __json = "{\"type\":\"INV_FAIL\"," + "\"id\":\"d64ce820-c1e1-41ee-89b6-c0cdfa402334\"," + "\"element\":\"sample.MathUtils#sum(int,int):int\"," + "\"file\":\"sample/MathUtils.java\"," + "\"expr\":\"a >= 0\"," + "\"phase\":\"ENTRY\"}";
                  daikonpp.DpRuntime.recordFailed(__dp_id, __json);
              }
          }
      } catch (Throwable __dp_ex_d64ce820c1e141ee89b6c0cdfa402334_en) {
          String __json = "{\"type\":\"INV_FAIL\"," + "\"id\":\"d64ce820-c1e1-41ee-89b6-c0cdfa402334\"," + "\"error\":\"" + __dp_ex_d64ce820c1e141ee89b6c0cdfa402334_en.toString() + "\"}";
          daikonpp.DpRuntime.recordFailed("d64ce820-c1e1-41ee-89b6-c0cdfa402334", __json);
      }
      //__DP_INVARIANT_END__
      ;
  }
  if (a < 0) throw new IllegalArgumentException("negative a not allowed");
    {
        final int __dp_res1 = a + b;
        {
            //__DP_INVARIANT_BEGIN__
            ;
            try {
                String __dp_id = "ca653e6a-045e-4849-acc9-54008d8e9bfa";
                if (!daikonpp.DpRuntime.DISABLED.contains(__dp_id) && !daikonpp.DpRuntime.SEEN_AT_START.contains(__dp_id)) {
                    daikonpp.DpRuntime.recordExecuted(__dp_id);
                    daikonpp.DpRuntime.markCurrent(__dp_id);
                    boolean __dp_ok = true;
                    if (daikonpp.DpRuntime.GUARD.get().compareAndSet(false, true)) {
                        try {
                            __dp_ok = (__dp_res1 == a + b);
                        } catch (Throwable __t) {
                            __dp_ok = false;
                        } finally {
                            daikonpp.DpRuntime.GUARD.get().set(false);
                        }
                    }
                    daikonpp.DpRuntime.clearCurrent(__dp_id);
                    if (!__dp_ok) {
                        String __json = "{\"type\":\"INV_FAIL\"," + "\"id\":\"ca653e6a-045e-4849-acc9-54008d8e9bfa\"," + "\"element\":\"sample.MathUtils#sum(int,int):int\"," + "\"file\":\"sample/MathUtils.java\"," + "\"expr\":\"__dp_res1 == a + b\"," + "\"phase\":\"EXIT\"}";
                        daikonpp.DpRuntime.recordFailed(__dp_id, __json);
                    }
                }
            } catch (Throwable __dp_ex_ca653e6a045e4849acc954008d8e9bfa_ex0) {
                String __json = "{\"type\":\"INV_FAIL\"," + "\"id\":\"ca653e6a-045e-4849-acc9-54008d8e9bfa\"," + "\"error\":\"" + __dp_ex_ca653e6a045e4849acc954008d8e9bfa_ex0.toString() + "\"}";
                daikonpp.DpRuntime.recordFailed("ca653e6a-045e-4849-acc9-54008d8e9bfa", __json);
            }
            //__DP_INVARIANT_END__
            ;
        }
        return __dp_res1;
    }
  }

  // Target 2
  public static int max(int a, int b) {
    {
        final int __dp_res1 = (a >= b) ? a : b;
        {
            //__DP_INVARIANT_BEGIN__
            ;
            try {
                String __dp_id = "aeac8e11-4738-4655-a374-c7800b333ad7";
                if (!daikonpp.DpRuntime.DISABLED.contains(__dp_id) && !daikonpp.DpRuntime.SEEN_AT_START.contains(__dp_id)) {
                    daikonpp.DpRuntime.recordExecuted(__dp_id);
                    daikonpp.DpRuntime.markCurrent(__dp_id);
                    boolean __dp_ok = true;
                    if (daikonpp.DpRuntime.GUARD.get().compareAndSet(false, true)) {
                        try {
                            __dp_ok = (__dp_res1 >= a);
                        } catch (Throwable __t) {
                            __dp_ok = false;
                        } finally {
                            daikonpp.DpRuntime.GUARD.get().set(false);
                        }
                    }
                    daikonpp.DpRuntime.clearCurrent(__dp_id);
                    if (!__dp_ok) {
                        String __json = "{\"type\":\"INV_FAIL\"," + "\"id\":\"aeac8e11-4738-4655-a374-c7800b333ad7\"," + "\"element\":\"sample.MathUtils#max(int,int):int\"," + "\"file\":\"sample/MathUtils.java\"," + "\"expr\":\"__dp_res1 >= a\"," + "\"phase\":\"EXIT\"}";
                        daikonpp.DpRuntime.recordFailed(__dp_id, __json);
                    }
                }
            } catch (Throwable __dp_ex_aeac8e1147384655a374c7800b333ad7_ex0) {
                String __json = "{\"type\":\"INV_FAIL\"," + "\"id\":\"aeac8e11-4738-4655-a374-c7800b333ad7\"," + "\"error\":\"" + __dp_ex_aeac8e1147384655a374c7800b333ad7_ex0.toString() + "\"}";
                daikonpp.DpRuntime.recordFailed("aeac8e11-4738-4655-a374-c7800b333ad7", __json);
            }
            //__DP_INVARIANT_END__
            ;
        }
        {
            //__DP_INVARIANT_BEGIN__
            ;
            try {
                String __dp_id = "4cf47b4b-730d-419d-ae60-e38447db0a92";
                if (!daikonpp.DpRuntime.DISABLED.contains(__dp_id) && !daikonpp.DpRuntime.SEEN_AT_START.contains(__dp_id)) {
                    daikonpp.DpRuntime.recordExecuted(__dp_id);
                    daikonpp.DpRuntime.markCurrent(__dp_id);
                    boolean __dp_ok = true;
                    if (daikonpp.DpRuntime.GUARD.get().compareAndSet(false, true)) {
                        try {
                            __dp_ok = (__dp_res1 >= b);
                        } catch (Throwable __t) {
                            __dp_ok = false;
                        } finally {
                            daikonpp.DpRuntime.GUARD.get().set(false);
                        }
                    }
                    daikonpp.DpRuntime.clearCurrent(__dp_id);
                    if (!__dp_ok) {
                        String __json = "{\"type\":\"INV_FAIL\"," + "\"id\":\"4cf47b4b-730d-419d-ae60-e38447db0a92\"," + "\"element\":\"sample.MathUtils#max(int,int):int\"," + "\"file\":\"sample/MathUtils.java\"," + "\"expr\":\"__dp_res1 >= b\"," + "\"phase\":\"EXIT\"}";
                        daikonpp.DpRuntime.recordFailed(__dp_id, __json);
                    }
                }
            } catch (Throwable __dp_ex_4cf47b4b730d419dae60e38447db0a92_ex1) {
                String __json = "{\"type\":\"INV_FAIL\"," + "\"id\":\"4cf47b4b-730d-419d-ae60-e38447db0a92\"," + "\"error\":\"" + __dp_ex_4cf47b4b730d419dae60e38447db0a92_ex1.toString() + "\"}";
                daikonpp.DpRuntime.recordFailed("4cf47b4b-730d-419d-ae60-e38447db0a92", __json);
            }
            //__DP_INVARIANT_END__
            ;
        }
        {
            //__DP_INVARIANT_BEGIN__
            ;
            try {
                String __dp_id = "a2fcdc36-7ec9-4e26-b4e7-fb51103655e0";
                if (!daikonpp.DpRuntime.DISABLED.contains(__dp_id) && !daikonpp.DpRuntime.SEEN_AT_START.contains(__dp_id)) {
                    daikonpp.DpRuntime.recordExecuted(__dp_id);
                    daikonpp.DpRuntime.markCurrent(__dp_id);
                    boolean __dp_ok = true;
                    if (daikonpp.DpRuntime.GUARD.get().compareAndSet(false, true)) {
                        try {
                            __dp_ok = (__dp_res1 == a || __dp_res1 == b);
                        } catch (Throwable __t) {
                            __dp_ok = false;
                        } finally {
                            daikonpp.DpRuntime.GUARD.get().set(false);
                        }
                    }
                    daikonpp.DpRuntime.clearCurrent(__dp_id);
                    if (!__dp_ok) {
                        String __json = "{\"type\":\"INV_FAIL\"," + "\"id\":\"a2fcdc36-7ec9-4e26-b4e7-fb51103655e0\"," + "\"element\":\"sample.MathUtils#max(int,int):int\"," + "\"file\":\"sample/MathUtils.java\"," + "\"expr\":\"__dp_res1 == a || __dp_res1 == b\"," + "\"phase\":\"EXIT\"}";
                        daikonpp.DpRuntime.recordFailed(__dp_id, __json);
                    }
                }
            } catch (Throwable __dp_ex_a2fcdc367ec94e26b4e7fb51103655e0_ex2) {
                String __json = "{\"type\":\"INV_FAIL\"," + "\"id\":\"a2fcdc36-7ec9-4e26-b4e7-fb51103655e0\"," + "\"error\":\"" + __dp_ex_a2fcdc367ec94e26b4e7fb51103655e0_ex2.toString() + "\"}";
                daikonpp.DpRuntime.recordFailed("a2fcdc36-7ec9-4e26-b4e7-fb51103655e0", __json);
            }
            //__DP_INVARIANT_END__
            ;
        }
        return __dp_res1;
    }
  }
}


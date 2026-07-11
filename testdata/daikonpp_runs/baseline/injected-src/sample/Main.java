package sample;

public class Main {
  public static void main(String[] args) {
  {
      //__DP_INVARIANT_BEGIN__
      ;
      try {
          String __dp_id = "4f688a30-85d4-4042-a504-a84f59a1dc38";
          if (!daikonpp.DpRuntime.DISABLED.contains(__dp_id) && !daikonpp.DpRuntime.SEEN.contains(__dp_id)) {
              daikonpp.DpRuntime.recordExecuted(__dp_id);
              daikonpp.DpRuntime.markCurrent(__dp_id);
              boolean __dp_ok = true;
              if (daikonpp.DpRuntime.GUARD.get().compareAndSet(false, true)) {
                  try {
                      __dp_ok = (args != null);
                  } catch (Throwable __t) {
                      __dp_ok = false;
                  } finally {
                      daikonpp.DpRuntime.GUARD.get().set(false);
                  }
              }
              daikonpp.DpRuntime.clearCurrent(__dp_id);
              if (!__dp_ok) {
                  String __json = "{\"type\":\"INV_FAIL\"," + "\"id\":\"4f688a30-85d4-4042-a504-a84f59a1dc38\"," + "\"element\":\"sample.Main#main(String[]):void\"," + "\"file\":\"sample/Main.java\"," + "\"expr\":\"args != null\"," + "\"phase\":\"ENTRY\"}";
                  daikonpp.DpRuntime.recordFailed(__dp_id, __json);
              }
          }
      } catch (Throwable __dp_ex_4f688a3085d44042a504a84f59a1dc38_en) {
          String __json = "{\"type\":\"INV_FAIL\"," + "\"id\":\"4f688a30-85d4-4042-a504-a84f59a1dc38\"," + "\"error\":\"" + __dp_ex_4f688a3085d44042a504a84f59a1dc38_en.toString() + "\"}";
          daikonpp.DpRuntime.recordFailed("4f688a30-85d4-4042-a504-a84f59a1dc38", __json);
      }
      //__DP_INVARIANT_END__
      ;
  }
  {
      //__DP_INVARIANT_BEGIN__
      ;
      try {
          String __dp_id = "06eca5a1-52a2-4d1c-a739-1914d630ed25";
          if (!daikonpp.DpRuntime.DISABLED.contains(__dp_id) && !daikonpp.DpRuntime.SEEN.contains(__dp_id)) {
              daikonpp.DpRuntime.recordExecuted(__dp_id);
              daikonpp.DpRuntime.markCurrent(__dp_id);
              boolean __dp_ok = true;
              if (daikonpp.DpRuntime.GUARD.get().compareAndSet(false, true)) {
                  try {
                      __dp_ok = (args.length >= 0);
                  } catch (Throwable __t) {
                      __dp_ok = false;
                  } finally {
                      daikonpp.DpRuntime.GUARD.get().set(false);
                  }
              }
              daikonpp.DpRuntime.clearCurrent(__dp_id);
              if (!__dp_ok) {
                  String __json = "{\"type\":\"INV_FAIL\"," + "\"id\":\"06eca5a1-52a2-4d1c-a739-1914d630ed25\"," + "\"element\":\"sample.Main#main(String[]):void\"," + "\"file\":\"sample/Main.java\"," + "\"expr\":\"args.length >= 0\"," + "\"phase\":\"ENTRY\"}";
                  daikonpp.DpRuntime.recordFailed(__dp_id, __json);
              }
          }
      } catch (Throwable __dp_ex_06eca5a152a24d1ca7391914d630ed25_en) {
          String __json = "{\"type\":\"INV_FAIL\"," + "\"id\":\"06eca5a1-52a2-4d1c-a739-1914d630ed25\"," + "\"error\":\"" + __dp_ex_06eca5a152a24d1ca7391914d630ed25_en.toString() + "\"}";
          daikonpp.DpRuntime.recordFailed("06eca5a1-52a2-4d1c-a739-1914d630ed25", __json);
      }
      //__DP_INVARIANT_END__
      ;
  }
  // Simple calls to exercise both ENTRY and EXIT points.
    int s = MathUtils.sum(1, 2);     // 3
    int m = MathUtils.max(3, 2);     // 3

    if (s != 3) throw new AssertionError("sum wrong: " + s);
    if (m != 3) throw new AssertionError("max wrong: " + m);

    // Keep program short & deterministic
    System.out.println("OK:" + s + "," + m);
    {
        //__DP_INVARIANT_BEGIN__
        ;
        try {
            String __dp_id = "2dce125c-4649-4d31-ae43-8341f79536d3";
            if (!daikonpp.DpRuntime.DISABLED.contains(__dp_id) && !daikonpp.DpRuntime.SEEN.contains(__dp_id)) {
                daikonpp.DpRuntime.recordExecuted(__dp_id);
                daikonpp.DpRuntime.markCurrent(__dp_id);
                boolean __dp_ok = true;
                if (daikonpp.DpRuntime.GUARD.get().compareAndSet(false, true)) {
                    try {
                        __dp_ok = (args != null);
                    } catch (Throwable __t) {
                        __dp_ok = false;
                    } finally {
                        daikonpp.DpRuntime.GUARD.get().set(false);
                    }
                }
                daikonpp.DpRuntime.clearCurrent(__dp_id);
                if (!__dp_ok) {
                    String __json = "{\"type\":\"INV_FAIL\"," + "\"id\":\"2dce125c-4649-4d31-ae43-8341f79536d3\"," + "\"element\":\"sample.Main#main(String[]):void\"," + "\"file\":\"sample/Main.java\"," + "\"expr\":\"args != null\"," + "\"phase\":\"EXIT\"}";
                    daikonpp.DpRuntime.recordFailed(__dp_id, __json);
                }
            }
        } catch (Throwable __dp_ex_2dce125c46494d31ae438341f79536d3_tail) {
            String __json = "{\"type\":\"INV_FAIL\"," + "\"id\":\"2dce125c-4649-4d31-ae43-8341f79536d3\"," + "\"error\":\"" + __dp_ex_2dce125c46494d31ae438341f79536d3_tail.toString() + "\"}";
            daikonpp.DpRuntime.recordFailed("2dce125c-4649-4d31-ae43-8341f79536d3", __json);
        }
        //__DP_INVARIANT_END__
        ;
    }
    {
        //__DP_INVARIANT_BEGIN__
        ;
        try {
            String __dp_id = "792b430a-ca45-4102-b456-c92dbf605892";
            if (!daikonpp.DpRuntime.DISABLED.contains(__dp_id) && !daikonpp.DpRuntime.SEEN.contains(__dp_id)) {
                daikonpp.DpRuntime.recordExecuted(__dp_id);
                daikonpp.DpRuntime.markCurrent(__dp_id);
                boolean __dp_ok = true;
                if (daikonpp.DpRuntime.GUARD.get().compareAndSet(false, true)) {
                    try {
                        __dp_ok = (args.length >= 0);
                    } catch (Throwable __t) {
                        __dp_ok = false;
                    } finally {
                        daikonpp.DpRuntime.GUARD.get().set(false);
                    }
                }
                daikonpp.DpRuntime.clearCurrent(__dp_id);
                if (!__dp_ok) {
                    String __json = "{\"type\":\"INV_FAIL\"," + "\"id\":\"792b430a-ca45-4102-b456-c92dbf605892\"," + "\"element\":\"sample.Main#main(String[]):void\"," + "\"file\":\"sample/Main.java\"," + "\"expr\":\"args.length >= 0\"," + "\"phase\":\"EXIT\"}";
                    daikonpp.DpRuntime.recordFailed(__dp_id, __json);
                }
            }
        } catch (Throwable __dp_ex_792b430aca454102b456c92dbf605892_tail) {
            String __json = "{\"type\":\"INV_FAIL\"," + "\"id\":\"792b430a-ca45-4102-b456-c92dbf605892\"," + "\"error\":\"" + __dp_ex_792b430aca454102b456c92dbf605892_tail.toString() + "\"}";
            daikonpp.DpRuntime.recordFailed("792b430a-ca45-4102-b456-c92dbf605892", __json);
        }
        //__DP_INVARIANT_END__
        ;
    }
  }
}


package sample;

public class Main {
  public static void main(String[] args) {
  try {
      if (daikonpp.DpRuntime.ENABLED) {
          String __dp_id = "4f688a30-85d4-4042-a504-a84f59a1dc38";
          if (daikonpp.DpRuntime.EXECUTED.putIfAbsent(__dp_id, Boolean.TRUE) == null) {
              System.out.println("INV_EXD:" + __dp_id);
              if (daikonpp.DpRuntime.HOOK_REGISTERED.compareAndSet(false, true)) {
                  Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                      
                      public void run() {
                          try {
                              String __d = daikonpp.DpRuntime.INV_DIR;
                              if (__d == null || __d.trim().length() == 0)
                                  return;
                              java.io.File __dir = new java.io.File(__d);
                              __dir.mkdirs();
                              java.io.File __out = new java.io.File(__dir, "dp-events-" + java.util.UUID.randomUUID().toString() + ".log");
                              StringBuilder __sb = new StringBuilder();
                              for (String __k : daikonpp.DpRuntime.EXECUTED.keySet()) {
                                  __sb.append("INV_EXD:").append(__k).append('\n');
                              }
                              for (String __v : daikonpp.DpRuntime.FAIL_JSON.values()) {
                                  if (__v != null && __v.trim().length() > 0)
                                      __sb.append(__v).append('\n');
                              }
                              if (__sb.length() > 0) {
                                  java.io.OutputStream __os = null;
                                  try {
                                      __os = new java.io.FileOutputStream(__out, true);
                                      __os.write(__sb.toString().getBytes("UTF-8"));
                                  } finally {
                                      if (__os != null)
                                          try {
                                              __os.close();
                                          } catch (Throwable __t) {
                                          }
                                  }
                              }
                          } catch (Throwable __ignore) {
                          }
                      }
                  }));
              }
          }
          boolean __dp_ok = true;
          if (daikonpp.DpRuntime.GUARD.compareAndSet(false, true)) {
              try {
                  __dp_ok = (args != null);
              } catch (Throwable __t) {
                  __dp_ok = false;
              } finally {
                  daikonpp.DpRuntime.GUARD.set(false);
              }
          }
          if (!__dp_ok) {
              String __json = "{\"type\":\"INV_FAIL\"," + "\"id\":\"4f688a30-85d4-4042-a504-a84f59a1dc38\"," + "\"element\":\"sample.Main#main(String[]):void\"," + "\"file\":\"sample/Main.java\"," + "\"expr\":\"args != null\"," + "\"phase\":\"ENTRY\"}";
              if (daikonpp.DpRuntime.FAIL_JSON.putIfAbsent(__dp_id, __json) == null) {
                  System.out.println(__json);
              }
          }
      }
  } catch (Throwable __dp_ex_4f688a3085d44042a504a84f59a1dc38_en0) {
      String __json = "{\"type\":\"INV_FAIL\"," + "\"id\":\"4f688a30-85d4-4042-a504-a84f59a1dc38\"," + "\"error\":\"" + __dp_ex_4f688a3085d44042a504a84f59a1dc38_en0.toString() + "\"}";
      if (daikonpp.DpRuntime.FAIL_JSON.putIfAbsent("4f688a30-85d4-4042-a504-a84f59a1dc38", __json) == null) {
          System.out.println(__json);
      }
  }
  try {
      if (daikonpp.DpRuntime.ENABLED) {
          String __dp_id = "06eca5a1-52a2-4d1c-a739-1914d630ed25";
          if (daikonpp.DpRuntime.EXECUTED.putIfAbsent(__dp_id, Boolean.TRUE) == null) {
              System.out.println("INV_EXD:" + __dp_id);
              if (daikonpp.DpRuntime.HOOK_REGISTERED.compareAndSet(false, true)) {
                  Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                      
                      public void run() {
                          try {
                              String __d = daikonpp.DpRuntime.INV_DIR;
                              if (__d == null || __d.trim().length() == 0)
                                  return;
                              java.io.File __dir = new java.io.File(__d);
                              __dir.mkdirs();
                              java.io.File __out = new java.io.File(__dir, "dp-events-" + java.util.UUID.randomUUID().toString() + ".log");
                              StringBuilder __sb = new StringBuilder();
                              for (String __k : daikonpp.DpRuntime.EXECUTED.keySet()) {
                                  __sb.append("INV_EXD:").append(__k).append('\n');
                              }
                              for (String __v : daikonpp.DpRuntime.FAIL_JSON.values()) {
                                  if (__v != null && __v.trim().length() > 0)
                                      __sb.append(__v).append('\n');
                              }
                              if (__sb.length() > 0) {
                                  java.io.OutputStream __os = null;
                                  try {
                                      __os = new java.io.FileOutputStream(__out, true);
                                      __os.write(__sb.toString().getBytes("UTF-8"));
                                  } finally {
                                      if (__os != null)
                                          try {
                                              __os.close();
                                          } catch (Throwable __t) {
                                          }
                                  }
                              }
                          } catch (Throwable __ignore) {
                          }
                      }
                  }));
              }
          }
          boolean __dp_ok = true;
          if (daikonpp.DpRuntime.GUARD.compareAndSet(false, true)) {
              try {
                  __dp_ok = (args.length >= 0);
              } catch (Throwable __t) {
                  __dp_ok = false;
              } finally {
                  daikonpp.DpRuntime.GUARD.set(false);
              }
          }
          if (!__dp_ok) {
              String __json = "{\"type\":\"INV_FAIL\"," + "\"id\":\"06eca5a1-52a2-4d1c-a739-1914d630ed25\"," + "\"element\":\"sample.Main#main(String[]):void\"," + "\"file\":\"sample/Main.java\"," + "\"expr\":\"args.length >= 0\"," + "\"phase\":\"ENTRY\"}";
              if (daikonpp.DpRuntime.FAIL_JSON.putIfAbsent(__dp_id, __json) == null) {
                  System.out.println(__json);
              }
          }
      }
  } catch (Throwable __dp_ex_06eca5a152a24d1ca7391914d630ed25_en1) {
      String __json = "{\"type\":\"INV_FAIL\"," + "\"id\":\"06eca5a1-52a2-4d1c-a739-1914d630ed25\"," + "\"error\":\"" + __dp_ex_06eca5a152a24d1ca7391914d630ed25_en1.toString() + "\"}";
      if (daikonpp.DpRuntime.FAIL_JSON.putIfAbsent("06eca5a1-52a2-4d1c-a739-1914d630ed25", __json) == null) {
          System.out.println(__json);
      }
  }
  // Simple calls to exercise both ENTRY and EXIT points.
    int s = MathUtils.sum(1, 2);     // 3
    int m = MathUtils.max(3, 2);     // 3

    if (s != 3) throw new AssertionError("sum wrong: " + s);
    if (m != 3) throw new AssertionError("max wrong: " + m);

    // Keep program short & deterministic
    System.out.println("OK:" + s + "," + m);
    try {
        if (daikonpp.DpRuntime.ENABLED) {
            String __dp_id = "2dce125c-4649-4d31-ae43-8341f79536d3";
            if (daikonpp.DpRuntime.EXECUTED.putIfAbsent(__dp_id, Boolean.TRUE) == null) {
                System.out.println("INV_EXD:" + __dp_id);
                if (daikonpp.DpRuntime.HOOK_REGISTERED.compareAndSet(false, true)) {
                    Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                        
                        public void run() {
                            try {
                                String __d = daikonpp.DpRuntime.INV_DIR;
                                if (__d == null || __d.trim().length() == 0)
                                    return;
                                java.io.File __dir = new java.io.File(__d);
                                __dir.mkdirs();
                                java.io.File __out = new java.io.File(__dir, "dp-events-" + java.util.UUID.randomUUID().toString() + ".log");
                                StringBuilder __sb = new StringBuilder();
                                for (String __k : daikonpp.DpRuntime.EXECUTED.keySet()) {
                                    __sb.append("INV_EXD:").append(__k).append('\n');
                                }
                                for (String __v : daikonpp.DpRuntime.FAIL_JSON.values()) {
                                    if (__v != null && __v.trim().length() > 0)
                                        __sb.append(__v).append('\n');
                                }
                                if (__sb.length() > 0) {
                                    java.io.OutputStream __os = null;
                                    try {
                                        __os = new java.io.FileOutputStream(__out, true);
                                        __os.write(__sb.toString().getBytes("UTF-8"));
                                    } finally {
                                        if (__os != null)
                                            try {
                                                __os.close();
                                            } catch (Throwable __t) {
                                            }
                                    }
                                }
                            } catch (Throwable __ignore) {
                            }
                        }
                    }));
                }
            }
            boolean __dp_ok = true;
            if (daikonpp.DpRuntime.GUARD.compareAndSet(false, true)) {
                try {
                    __dp_ok = (args != null);
                } catch (Throwable __t) {
                    __dp_ok = false;
                } finally {
                    daikonpp.DpRuntime.GUARD.set(false);
                }
            }
            if (!__dp_ok) {
                String __json = "{\"type\":\"INV_FAIL\"," + "\"id\":\"2dce125c-4649-4d31-ae43-8341f79536d3\"," + "\"element\":\"sample.Main#main(String[]):void\"," + "\"file\":\"sample/Main.java\"," + "\"expr\":\"args != null\"," + "\"phase\":\"EXIT\"}";
                if (daikonpp.DpRuntime.FAIL_JSON.putIfAbsent(__dp_id, __json) == null) {
                    System.out.println(__json);
                }
            }
        }
    } catch (Throwable __dp_ex_2dce125c46494d31ae438341f79536d3_tail_0) {
        String __json = "{\"type\":\"INV_FAIL\"," + "\"id\":\"2dce125c-4649-4d31-ae43-8341f79536d3\"," + "\"error\":\"" + __dp_ex_2dce125c46494d31ae438341f79536d3_tail_0.toString() + "\"}";
        if (daikonpp.DpRuntime.FAIL_JSON.putIfAbsent("2dce125c-4649-4d31-ae43-8341f79536d3", __json) == null) {
            System.out.println(__json);
        }
    }
    try {
        if (daikonpp.DpRuntime.ENABLED) {
            String __dp_id = "792b430a-ca45-4102-b456-c92dbf605892";
            if (daikonpp.DpRuntime.EXECUTED.putIfAbsent(__dp_id, Boolean.TRUE) == null) {
                System.out.println("INV_EXD:" + __dp_id);
                if (daikonpp.DpRuntime.HOOK_REGISTERED.compareAndSet(false, true)) {
                    Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                        
                        public void run() {
                            try {
                                String __d = daikonpp.DpRuntime.INV_DIR;
                                if (__d == null || __d.trim().length() == 0)
                                    return;
                                java.io.File __dir = new java.io.File(__d);
                                __dir.mkdirs();
                                java.io.File __out = new java.io.File(__dir, "dp-events-" + java.util.UUID.randomUUID().toString() + ".log");
                                StringBuilder __sb = new StringBuilder();
                                for (String __k : daikonpp.DpRuntime.EXECUTED.keySet()) {
                                    __sb.append("INV_EXD:").append(__k).append('\n');
                                }
                                for (String __v : daikonpp.DpRuntime.FAIL_JSON.values()) {
                                    if (__v != null && __v.trim().length() > 0)
                                        __sb.append(__v).append('\n');
                                }
                                if (__sb.length() > 0) {
                                    java.io.OutputStream __os = null;
                                    try {
                                        __os = new java.io.FileOutputStream(__out, true);
                                        __os.write(__sb.toString().getBytes("UTF-8"));
                                    } finally {
                                        if (__os != null)
                                            try {
                                                __os.close();
                                            } catch (Throwable __t) {
                                            }
                                    }
                                }
                            } catch (Throwable __ignore) {
                            }
                        }
                    }));
                }
            }
            boolean __dp_ok = true;
            if (daikonpp.DpRuntime.GUARD.compareAndSet(false, true)) {
                try {
                    __dp_ok = (args.length >= 0);
                } catch (Throwable __t) {
                    __dp_ok = false;
                } finally {
                    daikonpp.DpRuntime.GUARD.set(false);
                }
            }
            if (!__dp_ok) {
                String __json = "{\"type\":\"INV_FAIL\"," + "\"id\":\"792b430a-ca45-4102-b456-c92dbf605892\"," + "\"element\":\"sample.Main#main(String[]):void\"," + "\"file\":\"sample/Main.java\"," + "\"expr\":\"args.length >= 0\"," + "\"phase\":\"EXIT\"}";
                if (daikonpp.DpRuntime.FAIL_JSON.putIfAbsent(__dp_id, __json) == null) {
                    System.out.println(__json);
                }
            }
        }
    } catch (Throwable __dp_ex_792b430aca454102b456c92dbf605892_tail_1) {
        String __json = "{\"type\":\"INV_FAIL\"," + "\"id\":\"792b430a-ca45-4102-b456-c92dbf605892\"," + "\"error\":\"" + __dp_ex_792b430aca454102b456c92dbf605892_tail_1.toString() + "\"}";
        if (daikonpp.DpRuntime.FAIL_JSON.putIfAbsent("792b430a-ca45-4102-b456-c92dbf605892", __json) == null) {
            System.out.println(__json);
        }
    }
  }
}


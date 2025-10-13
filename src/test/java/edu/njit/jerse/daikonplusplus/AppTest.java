package edu.njit.jerse.daikonplusplus;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import edu.njit.jerse.daikonplusplus.model.ProgramPoint;
import edu.njit.jerse.daikonplusplus.model.ProgramPointKind;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class AppTest {

  // --- helper: invoke private static methods ---
  @SuppressWarnings("unchecked")
  private static <T> T invokePrivateStatic(
      Class<?> cls, String name, Class<?>[] params, Object... args) {
    try {
      Method m = cls.getDeclaredMethod(name, params);
      m.setAccessible(true);
      return (T) m.invoke(null, args);
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException(e);
    }
  }

  // ---------- keyFor ----------

  @Test
  void keyFor_normalizesWhitespace_andIncludesKindAndElement() {
    ProgramPoint pt = mock(ProgramPoint.class, RETURNS_DEEP_STUBS);
    when(pt.kind()).thenReturn(ProgramPointKind.METHOD_ENTRY);
    when(pt.elementId().toString()).thenReturn("SomeElementId(sig=foo)");

    String messyExpr = "  x   ==   42\t&&\n y!=null  ";

    String key =
        invokePrivateStatic(
            App.class, "keyFor", new Class<?>[] {ProgramPoint.class, String.class}, pt, messyExpr);

    assertEquals("METHOD_ENTRY|SomeElementId(sig=foo)|x == 42 && y!=null", key);
  }

  // ---------- parseRegistryLite ----------

  @Test
  void parseRegistryLite_readsValidJsonl(@TempDir Path tmp) throws Exception {
    Path f = tmp.resolve("registry.jsonl");
    String id1 = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    String id2 = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    String l1 =
        "{\"id\":\""
            + id1
            + "\",\"expr\":\"x > 0\",\"kind\":\"METHOD_ENTRY\",\"element\":\"C#m()\"}";
    String l2 =
        "{\"id\":\""
            + id2
            + "\",\"expr\":\"y != null\",\"kind\":\"METHOD_EXIT\",\"element\":\"C#m()\"}";
    Files.writeString(f, l1 + System.lineSeparator() + l2 + System.lineSeparator());

    @SuppressWarnings("unchecked")
    Map<UUID, ?> out =
        invokePrivateStatic(App.class, "parseRegistryLite", new Class<?>[] {Path.class}, f);

    assertEquals(2, out.size());
    assertTrue(out.containsKey(UUID.fromString(id1)));
    assertTrue(out.containsKey(UUID.fromString(id2)));
  }

  // ---------- copyTree / deleteTree ----------

  @Test
  void copyTree_then_deleteTree_behaves(@TempDir Path tmp) throws Exception {
    Path src = tmp.resolve("src");
    Path dst = tmp.resolve("dst");
    Files.createDirectories(src.resolve("a/b"));
    Files.writeString(src.resolve("a/b/hello.txt"), "hi");
    Files.writeString(src.resolve("root.txt"), "top");

    invokePrivateStatic(App.class, "copyTree", new Class<?>[] {Path.class, Path.class}, src, dst);

    assertTrue(Files.exists(dst.resolve("a/b/hello.txt")));
    assertEquals("hi", Files.readString(dst.resolve("a/b/hello.txt")));
    assertEquals("top", Files.readString(dst.resolve("root.txt")));

    invokePrivateStatic(App.class, "deleteTree", new Class<?>[] {Path.class}, dst);

    assertFalse(Files.exists(dst));
  }
}

package foo;

import java.util.List;

/** Utility class for processing users. */
public class Bar {

  /** Returns total length of all user names. */
  public static int totalNameLength(List<User> users) {
    int sum = 0;

    for (User u : users) {
      if (u.name != null) {
        sum += u.name.length();
      }
    }

    return sum;
  }
}

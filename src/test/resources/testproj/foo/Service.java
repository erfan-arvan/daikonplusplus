package foo;

import java.util.Arrays;
import java.util.List;

public class Service {

  public int process() {
    List<User> users = Arrays.asList(new User(1, "Alice"), new User(2, "Bob"));

    return Bar.totalNameLength(users); // 🔥 CALL SITE
  }
}

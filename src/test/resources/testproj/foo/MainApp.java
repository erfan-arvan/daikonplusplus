package foo;

public class MainApp {

  public static void main(String[] args) {
    Service s = new Service();
    int result = s.process(); // 🔥 CALL SITE
    System.out.println(result);
  }
}

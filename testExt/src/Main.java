public class Main {
    public static int div(int a, int b) {
        // Daikon++ will inject invariants here
        return a / b;
    }

    public static void main(String[] args) {
        System.out.println(div(10, 2));
        System.out.println(div(10, 0)); // runtime crash is fine
    }
}


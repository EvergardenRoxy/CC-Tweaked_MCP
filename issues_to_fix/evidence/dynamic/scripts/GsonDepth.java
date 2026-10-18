import com.google.gson.*;
public class GsonDepth {
  public static void main(String[] a) {
    int depth = Integer.parseInt(a[0]);
    String s = "[".repeat(depth) + "0" + "]".repeat(depth);
    try { JsonElement e = JsonParser.parseString(s); System.out.println("OK depth=" + depth + " length=" + s.length()); }
    catch (Throwable t) { System.out.println("THROW depth=" + depth + " " + t); }
  }
}
import java.util.HashMap;
import java.util.Map;
interface Database { String readRequest(String key); void writeRequest(String key, String value); }
// In-memory stand-in: no Redis server or credentials required.
class RedisDatabase implements Database {
    private final Map<String, String> data = new HashMap<>();
    public String readRequest(String key) { System.out.println("DB read: " + key); return data.get(key); }
    public void writeRequest(String key, String value) { data.put(key, value); }
}
class ProxyDatabase implements Database {
    private final Map<String, String> cache = new HashMap<>();
    private final Database database;
    ProxyDatabase(Database database) { this.database = database; }
    public String readRequest(String key) {
        if (cache.containsKey(key)) { System.out.println("Cache hit: " + key); return cache.get(key); }
        String value = database.readRequest(key);
        if (value != null) cache.put(key, value);
        return value;
    }
    public void writeRequest(String key, String value) {
        database.writeRequest(key, value);
        cache.remove(key); // Invalidate only after a successful write.
    }
}
public class ProxyDesignPattern {
    public static void main(String[] args) {
        Database db = new ProxyDatabase(new RedisDatabase());
        db.writeRequest("lesson", "Java");
        System.out.println(db.readRequest("lesson")); System.out.println(db.readRequest("lesson"));
        db.writeRequest("lesson", "Patterns"); System.out.println(db.readRequest("lesson"));
        System.out.println(db.readRequest("missing") == null ? "Missing key" : "Found");
    }
}

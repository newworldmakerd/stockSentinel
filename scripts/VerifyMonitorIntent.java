import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksentinel.commands.DeepSeekMonitorIntentClient;
import java.time.Duration;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;

/** Explicit operator-only live protocol verification, never run by the test suite. */
class VerifyMonitorIntent {
    public static void main(String[] args) {
        String key=System.getenv("DEEPSEEK_API_KEY");
        if(key==null || key.isBlank()) {System.out.println("MISSING_KEY");System.exit(2);}
        try {
            var transport=new JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
            transport.setReadTimeout(Duration.ofSeconds(30));
            var client=new DeepSeekMonitorIntentClient(RestClient.builder().baseUrl("https://api.deepseek.com")
                .requestFactory(transport).defaultHeader("Authorization","Bearer "+key).build(),new ObjectMapper(),"deepseek-flash");
            String result=client.stockQuery("请监控四川美丰");
            if(!"四川美丰".equals(result)) throw new IllegalStateException();
            System.out.println("LIVE_MONITOR_INTENT_PASS: add_stock_monitor / 四川美丰 (no database mutation or group message)");
        } catch(Exception failure) {System.out.println("LIVE_MONITOR_INTENT_FAILED: "+failure.getClass().getSimpleName());System.exit(1);}
    }
}

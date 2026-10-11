package InterviewQuestions;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

public class FoodDeliveryChecks {
    public static void main(String[] args) throws Exception {
        var gateway = new Zomato_Food_Delivery.PaymentGateway();
        var service = new Zomato_Food_Delivery.FoodDelivery(gateway);
        var request = new Zomato_Food_Delivery.Request("alice", "r1", "Home", "pizza", 2);
        var pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<String>> tasks = new ArrayList<>();
            for (int index = 0; index < 50; index++) {
                tasks.add(() -> service.place("same", request));
            }
            for (var future : pool.invokeAll(tasks)) {
                Zomato_Food_Delivery.check(future.get().equals("PLACED"));
            }
            Zomato_Food_Delivery.check(gateway.charges == 1);
            for (String id : List.of("same", "other")) {
                service.place(id, request);
                for (String state : List.of("ACCEPTED", "PREPARING", "READY")) {
                    service.advance(id, state);
                }
            }
            List<Callable<Boolean>> assignments =
                List.of(() -> service.assign("same"), () -> service.assign("other"));
            int assigned = 0;
            for (var future : pool.invokeAll(assignments)) {
                if (future.get()) {
                    assigned++;
                }
            }
            Zomato_Food_Delivery.check(assigned == 1);
        } finally {
            pool.shutdownNow();
        }
        System.out.println("Food delivery concurrency checks passed");
    }
}

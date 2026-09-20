public class ChainOfResponsibility {
    static abstract class Handler {
        private final Handler next;
        Handler(Handler next) {
            this.next = next;
        }

        abstract boolean canHandle(int level);
        abstract String name();
        String handle(int level) {
            if (level < 0) {
                throw new IllegalArgumentException("Level cannot be negative");
            }

            if (canHandle(level)) {
                return name();
            }

            return next == null ? "Unhandled" : next.handle(level);
        }
    }

    static class WarningHandler extends Handler {
        WarningHandler(Handler next) {
            super(next);
        }

        boolean canHandle(int level) {
            return level < 2;
        }

        String name() {
            return "Warning";
        }
    }

    static class ErrorHandler extends Handler {
        ErrorHandler(Handler next) {
            super(next);
        }

        boolean canHandle(int level) {
            return level < 4;
        }

        String name() {
            return "Error";
        }
    }

    static class FatalHandler extends Handler {
        FatalHandler(Handler next) {
            super(next);
        }

        boolean canHandle(int level) {
            return level < 6;
        }

        String name() {
            return "Fatal";
        }
    }

    public static void main(String[] args) {
        Handler chain = new WarningHandler(new ErrorHandler(new FatalHandler(null)));
        for (int level : new int[] {0, 1, 2, 3, 4, 5, 6}) {
            System.out.println(level + ": " + chain.handle(level));
        }

        System.out.println("Truncated: " + new WarningHandler(null).handle(3));
        try {
            chain.handle(-1);
            throw new AssertionError("Accepted negative level");
        } catch (IllegalArgumentException expected) {
            System.out.println("Invalid level rejected");
        }
    }
}

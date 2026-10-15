import com.sun.jdi.Bootstrap;
import com.sun.jdi.LongValue;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.StringReference;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.event.MethodExitEvent;
import com.sun.jdi.request.EventRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** External test-only JDI barrier. No halt, observer, or fault switch is added to production code. */
public final class P3ProfileCrashBarrier {
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]);
        int port = Integer.parseInt(args[1]);
        long pid = Long.parseLong(args[2]);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Long.parseLong(args[3]));
        var identity = new Properties();
        try (var input = Files.newInputStream(directory.resolve("profile-arm.properties"))) { identity.load(input); }
        String boundary = identity.getProperty("boundary");
        if (pid != Long.parseLong(identity.getProperty("pid")) || port < 1 || port > 65535
                || !Set.of("PREPARED", "SUSPENDED", "APPLIED", "VERIFIED").contains(boundary)) {
            throw new IllegalArgumentException("Wrong debugger fixture identity");
        }
        var connector = Bootstrap.virtualMachineManager().attachingConnectors().stream()
                .filter(c -> c.name().equals("com.sun.jdi.SocketAttach")).findFirst().orElseThrow();
        var options = connector.defaultArguments();
        options.get("hostname").setValue("127.0.0.1"); options.get("port").setValue(Integer.toString(port));
        options.get("timeout").setValue("10000");
        var vm = connector.attach(options);
        boolean hit = false;
        try {
            var type = vm.classesByName("dev.hardrush.gametest.P3ProfileCrashRegression").stream().findFirst().orElseThrow();
            if (((LongValue) type.getValue(type.fieldByName("PID"))).value() != pid) {
                throw new IllegalStateException("Attached VM is not this runner's dedicated server child");
            }
            var exits = vm.eventRequestManager().createMethodExitRequest();
            exits.addClassFilter("dev.hardrush.p1.player.ProfileJournal");
            // Every thread freezes before the caller can apply another journal write or native effect.
            exits.setSuspendPolicy(EventRequest.SUSPEND_ALL); exits.enable();
            write(directory.resolve("profile-debugger-ready.json"), "{\"pid\":" + pid + "}\n");
            while (System.nanoTime() < deadline) {
                var events = vm.eventQueue().remove(1000);
                if (events == null) continue;
                for (var event : events) {
                    if (!(event instanceof MethodExitEvent exit) || !exit.thread().name().equals("hardrush-p1-profiles")) continue;
                    String method = exit.method().name();
                    boolean matches = boundary.equals("PREPARED") ? method.equals("prepare")
                            : boundary.equals("SUSPENDED") ? method.equals("suspend")
                            : method.equals("advance") && phase(exit).equals(boundary);
                    if (!matches) continue;
                    var player = (ObjectReference) exit.thread().frame(0).getArgumentValues().getFirst();
                    long most = ((LongValue) player.getValue(player.referenceType().fieldByName("mostSigBits"))).value();
                    long least = ((LongValue) player.getValue(player.referenceType().fieldByName("leastSigBits"))).value();
                    if (!new java.util.UUID(most, least).toString().equals(identity.getProperty("playerId"))) continue;
                    hit = true;
                    String json = "{\"status\":\"KILL_READY\",\"scenario\":\"PROFILE\",\"operation\":\"write\",\"pid\":" + pid
                            + ",\"boundary\":\"" + boundary + "\",\"method\":\"" + method + "\",\"thread\":\"" + exit.thread().name()
                            + "\",\"suspendPolicy\":\"ALL\"}\n";
                    write(directory.resolve("profile-hit.json"), json);
                    // Keep the exact return event suspended until the parent kills that VM.
                    while (System.nanoTime() < deadline) vm.eventQueue().remove(1000);
                    throw new IllegalStateException("Parent failed to kill the suspended writer");
                }
                events.resume();
            }
            throw new IllegalStateException("Requested profile boundary was not reached");
        } catch (VMDisconnectedException disconnected) {
            if (!hit) throw disconnected;
        } finally {
            try { vm.dispose(); } catch (VMDisconnectedException ignored) {}
        }
    }

    private static String phase(MethodExitEvent exit) {
        var transition = (ObjectReference) exit.returnValue();
        var phase = (ObjectReference) transition.getValue(transition.referenceType().fieldByName("phase"));
        return ((StringReference) phase.getValue(phase.referenceType().fieldByName("name"))).value();
    }

    private static void write(Path path, String json) throws Exception {
        Path pending = path.resolveSibling(path.getFileName() + ".pending");
        Files.writeString(pending, json);
        Files.move(pending, path, StandardCopyOption.ATOMIC_MOVE);
    }
}

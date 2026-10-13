import com.sun.jdi.Bootstrap;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.request.EventRequest;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Real JDI attach, breakpoint, stack and variables; always resumes and detaches. */
public class JdwpProbe {
    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 5005;
        if (port != 5005 && port != 5006) throw new IllegalArgumentException("Only debug ports 5005/5006");
        var connector = Bootstrap.virtualMachineManager().attachingConnectors().stream()
                .filter(c -> c.name().equals("com.sun.jdi.SocketAttach")).findFirst().orElseThrow();
        var options = connector.defaultArguments();
        options.get("hostname").setValue("127.0.0.1"); options.get("port").setValue("" + port);
        options.get("timeout").setValue("10000");
        var vm = connector.attach(options);
        try {
            System.out.println("Attached: " + vm.name() + " " + vm.version() + "; threads=" + vm.allThreads().size());
            if (args.length < 4) return;
            var type = vm.classesByName(args[1]).stream().findFirst().orElseThrow();
            var method = type.methodsByName(args[2]).stream().findFirst().orElseThrow();
            var breakpoint = vm.eventRequestManager().createBreakpointRequest(method.location());
            breakpoint.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD); breakpoint.enable();
            Path root = Path.of(System.getProperty("hardrush.root", ".")).toAbsolutePath();
            var trigger = new ProcessBuilder("python3", root.resolve("scripts/debug-tools.py").toString(),
                    "command", "--command", args[3]).redirectErrorStream(true).start();
            boolean hit = false;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (!hit && System.nanoTime() < deadline) {
                EventSet events = vm.eventQueue().remove(1000);
                if (events == null) continue;
                try {
                    for (var event : events) {
                        if (!(event instanceof BreakpointEvent b)) continue;
                        hit = true;
                        System.out.println("Breakpoint hit: " + b.location() + "; thread=" + b.thread().name());
                        for (var frame : b.thread().frames()) System.out.println("  at " + frame.location());
                        try {
                            for (var local : b.thread().frame(0).visibleVariables())
                                System.out.println("  local " + local.name() + "=" + b.thread().frame(0).getValue(local));
                        } catch (com.sun.jdi.AbsentInformationException ignored) {}
                        for (var field : type.allFields()) {
                            if (field.isStatic()) System.out.println("  static " + field.name() + "=" + type.getValue(field));
                        }
                    }
                } finally { events.resume(); }
            }
            breakpoint.disable();
            if (!hit) throw new IllegalStateException("Breakpoint not reached within 30 seconds");
            if (!trigger.waitFor(45, TimeUnit.SECONDS)) throw new IllegalStateException("Trigger command did not finish");
            System.out.println(new String(trigger.getInputStream().readAllBytes()));
            if (trigger.exitValue() != 0) throw new IllegalStateException("Trigger command failed");
        } finally { vm.resume(); vm.dispose(); }
    }
}

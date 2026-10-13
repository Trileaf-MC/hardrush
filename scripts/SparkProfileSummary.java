import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.ArrayList;
import me.lucko.spark.proto.SparkSamplerProtos.SamplerData;
import me.lucko.spark.proto.SparkSamplerProtos.StackTraceNode;

/** Decodes spark's actual protobuf samples locally; no viewer upload needed. */
public class SparkProfileSummary {
    public static void main(String[] args) throws Exception {
        var data = SamplerData.parseFrom(Files.readAllBytes(Path.of(args[0])));
        var meta = data.getMetadata();
        if (data.getThreadsCount() == 0 || meta.getEndTime() <= meta.getStartTime())
            throw new IllegalStateException("Empty/invalid spark sampling data");
        System.out.println("durationMs=" + (meta.getEndTime() - meta.getStartTime())
                + "; ticks=" + meta.getNumberOfTicks() + "; engine=" + meta.getSamplerEngine()
                + "; intervalUs=" + meta.getInterval() + "; threads=" + data.getThreadsCount());
        for (var thread : data.getThreadsList()) {
            double ms = thread.getTimesList().stream().mapToDouble(Double::doubleValue).sum();
            System.out.println("thread=" + thread.getName() + "; sampledMs=" + ms);
            if (!thread.getName().equals("Server thread") && !thread.getName().equals("Render thread")) continue;
            var nodes = new ArrayList<>(thread.getChildrenList());
            nodes.sort(Comparator.comparingDouble((StackTraceNode n) -> n.getTimesList().stream().mapToDouble(Double::doubleValue).sum()).reversed());
            for (var node : nodes.stream().limit(20).toList()) {
                System.out.println("  inclusiveMs=" + node.getTimesList().stream().mapToDouble(Double::doubleValue).sum()
                        + "; at=" + node.getClassName() + "." + node.getMethodName() + ":" + node.getLineNumber());
            }
        }
    }
}

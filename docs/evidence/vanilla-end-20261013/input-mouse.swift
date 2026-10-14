import Cocoa
import CoreGraphics

// Explicitly authorized native mouse events, restricted to the isolated HRProbeA.
let identity = "dev.hardrush.p0.hrprobea"
let matching = NSRunningApplication.runningApplications(withBundleIdentifier: identity)
guard matching.count == 1, let target = matching.first else {
    fputs("Refusing input: exactly one isolated HRProbeA must be running.\n", stderr)
    exit(2)
}
guard CommandLine.arguments.count == 3,
      let dx = Int(CommandLine.arguments[1]),
      let dy = Int(CommandLine.arguments[2]),
      abs(dx) <= 1200, abs(dy) <= 1200 else {
    fputs("Usage: mouse <relative dx> <relative dy>; each limited to 1200.\n", stderr)
    exit(2)
}
guard let source = CGEventSource(stateID: .hidSystemState),
      let current = CGEvent(source: nil) else { exit(3) }
let point = current.location
let steps = max(1, max(abs(dx), abs(dy)) / 8)
var lastX = 0
var lastY = 0
for i in 1...steps {
    let x = dx * i / steps
    let y = dy * i / steps
    guard let event = CGEvent(mouseEventSource: source, mouseType: .mouseMoved,
                              mouseCursorPosition: point, mouseButton: .left) else { exit(3) }
    event.setIntegerValueField(.mouseEventDeltaX, value: Int64(x - lastX))
    event.setIntegerValueField(.mouseEventDeltaY, value: Int64(y - lastY))
    event.postToPid(target.processIdentifier)
    lastX = x
    lastY = y
    usleep(8_000)
}
print("Sent native mouse motion to HRProbeA: \(dx),\(dy)")

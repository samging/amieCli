package org.example.project.components.terminal.window.controller.modelLoaders

/**
 * DJL's tokenizer library phones home when the first tokenizer is made: it asks the EC2 metadata address
 * (169.254.169.254) who it runs on, then reports to an S3 bucket. Off AWS the first of those calls waits for its
 * connection to time out, which held the encoder's load 3 seconds on a Mac with a network (the load was 5.0 s, 2.0 s
 * without the call) and would hold it longer where that address is not answered quickly. Nothing the app does needs
 * that call, so it is opted out of through DJL's own switch, `OPT_OUT_TRACKING`, before any tokenizer is made. A
 * machine that sets that variable or property itself (to false, say) keeps what it chose; `-Damie.djl.telemetry=true`
 * leaves DJL as it is, to compare.
 */
internal object DjlTelemetry {
    private const val SWITCH = "OPT_OUT_TRACKING"

    fun optOut() {
        if (System.getProperty("amie.djl.telemetry") == "true") return
        if (System.getenv(SWITCH) != null || System.getProperty(SWITCH) != null) return
        System.setProperty(SWITCH, "true")
    }
}

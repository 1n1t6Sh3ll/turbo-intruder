package burp


class RunHandler (){
    private var running = false
    private var engine: RequestEngine? = null
    private var statusOverride: String? = null
    @Volatile private var scriptCompleted = false
    var msg: String = ""
    var code: String = ""
    var baseRequest: String = ""
    var rawRequest: ByteArray = "".toByteArray()

    fun isRunning(): Boolean {
        return running
    }

    fun setComplete() {
        engine?.showStats(-1)
    }

    fun markScriptCompleted() {
        scriptCompleted = true
    }

    fun hasFinished(): Boolean {
        // If engine exists, check its state
        if (engine != null) {
            return engine!!.runState.get() >= 3
        }
        // If no engine was created, check if script has completed
        return scriptCompleted
    }

    fun setRequestEngine(engine: RequestEngine) {
        running = true
        this.engine = engine
    }

    fun statusString(): String {
        if (statusOverride != null){
            return statusOverride!!
        }

        if (engine != null) {
            return engine!!.statusString() + " | "+msg
        }

        return "Engine warming up..."
    }

    fun overrideStatus(msg: String) {
        statusOverride = msg
    }

    fun setMessage(msg: String) {
        this.msg = msg
    }

    fun abort() {
        running = false
        this.engine?.cancel()
    }
}